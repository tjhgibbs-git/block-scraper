---
name: block-scraper
description: See and drive a running Minecraft (Fabric) dev client to iterate on GUI code. Use for any work on a Minecraft screen, widget, tooltip, HUD or inventory layout in this repo, when asked to screenshot the game, check how a screen looks, or verify a UI change visually. Covers starting the client, opening registered screens, clicking/typing, taking PNG shots, hot reloading code, and checking layout at other sizes, scales and languages.
---

# block-scraper: look at the game, edit, look again

`./block-scraper` (repo root, Python 3 stdlib) talks to one long-running Minecraft dev client over
`127.0.0.1:25599`. Each command finishes in the game before it returns. The game is paused between commands,
so shots are deterministic.

Exit codes: `0` ok, `1` command failed, `2` bad usage, `3` no client running. Add `--json` to any command for
machine-readable output. `./block-scraper <command> -h` documents each command.

## The loop

```sh
./block-scraper status                         # exit 3 → nothing running, so:
./block-scraper start                          # once per session (first run downloads Minecraft: minutes; run it in the background or with a long timeout)
./block-scraper screens                        # ids that `open` accepts
./block-scraper open <screen-id> [--arg k=v]   # or: open class:<fully.qualified.Screen>
./block-scraper shot shots/<name>.png          # prints the absolute path, size and time
#   → READ that PNG with the Read tool. Every time.
# ... edit the screen's Java code ...
./block-scraper reload                         # gradle classes + hot swap + reopen the screen
./block-scraper shot shots/<name>-v2.png       # READ it and compare with the previous shot
```

**Never claim a UI change works because `reload` succeeded.** Read the PNG and say what you see. When a
before/after matters, `./block-scraper diff a.png b.png` shows how much changed, and
`./block-scraper stats x.png --min-stddev 5` catches blank/black shots.

Reuse the running client across edits; don't `stop`/`start` between iterations. `stop` when the task is done.

## Commands

| Command | Use |
|---|---|
| `start [--size WxH] [--java-home <jbr>] [--headless]` / `stop` / `restart` | Client lifecycle. Headless (no `DISPLAY`) runs under `xvfb-run` automatically. |
| `status` | Version, current screen, window size, GUI size, `guiScale`, language, world, hot-reload mode |
| `screens` / `open <id> [--arg k=v ...]` / `close` / `reopen` | Pick a screen; `reopen` rebuilds it with the same args |
| `widgets` | Every widget: class, x/y/w/h (GUI coords), text, active/visible/focused/hovered, EditBox value |
| `click --text "Label"` / `click <x> <y> [--button 0\|1\|2]` | Prefer `--text` (exact visible text) over coordinates |
| `hover <x> <y>` | Move the cursor (tooltips). `hover 0 0` to move away |
| `drag <x1> <y1> <x2> <y2> [--steps N]` / `mouse down\|up` | Drag sliders etc.; `mouse` holds/releases at the cursor |
| `key <name>` / `type <text>` | `escape`, `enter`, `tab`, `shift+tab`, `ctrl+a`, `f3`, letters…; `type` goes to the focused widget |
| `wait <ticks>` | Advance the game (animations, delayed state) |
| `shot <out.png> [--size WxH] [--wait-frames N]` | Render and save; `--size` resizes for this shot only (layout included) |
| `record <out.gif> <script> [--size WxH] [--fps N]` | Animated demo of a script (e.g. a slider drag); look at a few frames in `<out>.frames/` to check it |
| `size <w>x<h>` / `scale <n>` / `lang <code>` | Window size, GUI scale (0 = auto), language (e.g. `de_de`) |
| `world [--flat\|--normal] [--creative]` / `leave` / `hud on\|off` | Throwaway singleplayer world (flat, seed 1, frozen time), F1 |
| `command <text>` | Server command with full permissions, e.g. `command 'tp @a 0 -60 0 facing 0 -60 10'` |
| `reload [--resources] [--no-build] [--no-reopen]` | Rebuild + hot swap; `--resources` for textures/lang/sprites |
| `run steps.txt` | One command per line, `#` comments, stops at the first error |
| `log` | Tail of the client log: stack traces, crashes |
| `stats <png>` / `diff <a> <b>` | Local PNG checks, no client needed |

## Coordinates

`widgets`, `click` and `hover` use **GUI coordinates**, the same ones the screen's `init()` uses. Screenshot pixels
= GUI coordinates × `guiScale` (from `status`). If you measure something on the PNG, divide by the scale before
clicking it.

## Interacting

```sh
./block-scraper widgets                        # find what's clickable and where
./block-scraper click --text "Done"
./block-scraper click 120 64                   # focus a text field by coordinates
./block-scraper key ctrl+a && ./block-scraper type "Steve"
./block-scraper hover 200 90 && ./block-scraper shot shots/tooltip.png && ./block-scraper hover 0 0
```

Check `widgets` again after a click to confirm state changed (text, `active`, `focused`, EditBox value) rather
than inferring it.

## Check layout beyond the default size

Overflow, clipping and overlap show up at the edges. Before calling a screen done, shoot at least:

```sh
./block-scraper size 854x480 && ./block-scraper shot shots/x-small.png
./block-scraper size 1920x1080 && ./block-scraper shot shots/x-large.png
./block-scraper scale 4 && ./block-scraper shot shots/x-scale4.png      # also try 1..3; 0 = auto
./block-scraper lang de_de && ./block-scraper shot shots/x-de.png       # long strings
./block-scraper size 1280x720 && ./block-scraper scale 0 && ./block-scraper lang en_us   # restore
```

Or batch it with `./block-scraper run` and a steps file (keep it in your scratchpad, not the repo).

## Hot reload limits

- Method-body changes hot swap. **`reload` fails with `restart required`** when you add/remove methods or fields,
  change a class signature, add a lambda, or touch a mixin or mixin target. Then run `./block-scraper restart` and
  `open` the screen again. Don't contort the code to dodge the error. (On JetBrains Runtime, started with
  `start --java-home <jbr>`, adding methods/fields hot swaps too; mixins still need a restart.)
- Compile errors come back from `reload` with the compiler output. Fix and `reload` again.
- Textures, lang files, sprites: `./block-scraper reload --resources`.
- A cold-Gradle `reload` can take ~10 s; warm ones are a few seconds.

## Registering a new screen

Screens are registered in a `BlockScraperPlugin` that the mod lists under the `"block-scraper"` entrypoint in
`fabric.mod.json`. Template: `examples/sample-ui/src/main/java/dev/blockscraper/sample/SampleScreens.java`.

```java
BlockScraper.register("my-screen", args -> new MyScreen(args.parent(), args.get("mode", "default")));
```

A new registration adds a lambda (a new method), so it usually needs `./block-scraper restart`. After that, edits to
`MyScreen` hot reload. Screens that need a player or world (inventory, HUD): `./block-scraper world` first, then
`open inventory` or `hud on|off`.

## When something is off

- Exit code `3`: no client. `start` it (or check `.block-scraper/client.json` for a stale client).
- Shot is black/blank (`stats` stddev near 0): `wait 5` and shoot again; check `log`. Under Xvfb (Mesa llvmpipe)
  GUI screens and flat worlds render fine; shaders and heavy world rendering may come out black.
- Click does nothing: re-run `widgets`; the text must match exactly and the widget must be visible and active.
- Anything else: `./block-scraper log` for the stack trace. Swapchain warnings under Xvfb are harmless.
- Animated elements (title panorama, blinking cursor) can differ by a few pixels between identical shots.

## Housekeeping

Put shots under `shots/` (or your scratchpad); don't commit them. Restore size/scale/lang if you changed them and
others will reuse the client. `./block-scraper stop` when finished.
