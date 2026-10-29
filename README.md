# Block Scraper

Screenshot-driven UI iteration for Fabric mods. One Minecraft dev client stays running; a CLI opens any screen,
sets window size, GUI scale and language, clicks and types, and saves PNGs. An agent (or you) can edit code,
hot-reload it and look at the result without restarting the game.

```
edit code → ./block-scraper reload → ./block-scraper shot shots/x.png → look at the PNG → repeat
```

Stack: Minecraft 26.3, Fabric Loader 0.20.1, Fabric API 0.165.0+26.3, Loom 1.19, Java 25 (see `gradle.properties`).

## Quickstart

```sh
scripts/setup-headless.sh          # once, on a Linux box with no display (Debian/Ubuntu)
./block-scraper start                     # launches the client; the first run downloads Minecraft (minutes)
./block-scraper open sample-config --arg name=Steve
./block-scraper shot shots/config.png     # prints: /abs/path/shots/config.png 1280x720 (180 ms)
./block-scraper widgets                   # what's on screen, with GUI coordinates
./block-scraper click --text "Clicked 0 times"
./block-scraper shot shots/config-clicked.png
# edit examples/sample-ui/.../SampleConfigScreen.java, then:
./block-scraper reload                    # gradle classes + hot swap + reopen
./block-scraper shot shots/config-v2.png
./block-scraper stop
```

`./block-scraper -h` lists every command; `./block-scraper <command> -h` explains one. Add `--json` for machine-readable output.
Exit codes: 0 ok, 1 command failed, 2 bad usage, 3 no client running.

| Command | What it does |
|---|---|
| `start` / `stop` / `restart` | Launch the dev client (`runBlockScraper`, wrapped in `xvfb-run` when there's no display) and wait until it answers. `.block-scraper/client.json` stops a second client. Log: `.block-scraper/client.log` (`block-scraper log`). |
| `status` | Minecraft version, current screen, window and GUI size, GUI scale, language, world, hot reload mode |
| `screens` / `open <id> [--arg k=v]` | List / open registered screens. `class:<fqcn>` opens any screen class by reflection. |
| `close` / `reopen` | Close the screen / build it again with the same args (picks up code changes) |
| `size <w>x<h>` / `scale <n>` / `lang <code>` | Window size, GUI scale (0 = auto), language (reloads resources) |
| `world [--flat\|--normal] [--creative\|--cheats]` / `leave` | Throwaway singleplayer world (flat, seed 1, frozen time and weather by default); `--creative` also allows commands |
| `command <text>` | Run a command on the integrated server with full permissions (`'tp @a 0 -60 0'`) |
| `hud on\|off` | F1 |
| `hover <x> <y>` / `click <x> <y> [--button 0\|1\|2]` / `click --text T` | Mouse, in GUI coordinates (the ones `widgets` prints); buttons are left/right/middle |
| `mouse down\|up` / `drag <x1> <y1> <x2> <y2> [--steps 8]` | Hold/release a button; drag (sliders, drag-to-rotate) |
| `key <name>` / `type <text>` | `escape`, `enter`, `tab`, `a`, `f3`, `ctrl+a`, `shift+tab` …; type into the focused widget |
| `wait <ticks>` | Advance the game |
| `shot <out.png> [--wait-frames 2] [--size WxH]` | Render and save; prints absolute path and size. `--size` resizes the window for this shot (the GUI re-lays out) and restores it |
| `record <out.gif\|.mp4> <script> [--fps 8] [--size WxH]` | Run a script and record it: a frame after every step, every drag move and every `--tick-step` ticks of a `wait`; encoded with ffmpeg (frames kept in `<out>.frames/` either way) |
| `widgets` | Class, x/y/w/h, text, active/visible/focused/hovered, EditBox value |
| `reload [--resources]` | `./gradlew blockScraperClasses` (every non-test source set, incl. Loom's `client`), redefine changed classes, optionally reload resources, reopen |
| `run <script>` | One command per line, `#` comments; stops at the first error |
| `stats <png> [--min-stddev N]` / `diff <a> <b>` | Local PNG checks: blank detection, pixel comparison |

## How it works

```
CLI ──JSON lines over 127.0.0.1:25599──▶ block-scraper mod (in the client) ──▶ PNG on disk
```

- **`block-scraper-mod`** runs the whole session as one Fabric *client gametest*. Fabric's gametest framework keeps the
  game paused between commands and provides deterministic screenshots, simulated input, window resizing and
  throwaway worlds, and Fabric maintains it for each Minecraft version. Every command finishes before the reply is sent.
- **`block-scraper-core`** (plain Java): the socket server and request queue.
- **`block-scraper-agent`** (plain Java): a `-javaagent` that hashes class files at startup and, on `reload`, redefines every
  loaded class whose file changed (`Instrumentation.redefineClasses`). Classes that aren't loaded yet just load fresh.
  Stock HotSpot swaps method bodies only. Adding or removing methods or fields gives a clear `restart required` error;
  run the client on [JetBrains Runtime](https://github.com/JetBrains/JetBrainsRuntime) with `block-scraper start --java-home <jbr>`
  (adds `-XX:+AllowEnhancedClassRedefinition`) to allow those too. Mixin classes and mixin targets always need a restart.
- **`block-scraper`** (Python 3, stdlib only): the CLI.
- **`examples/sample-ui`**: a small config screen (text field, toggle with tooltip, counter button) used by the
  tests and as a template.

**Safety:** block-scraper does nothing unless the JVM has both `-Dfabric.client.gametest` (Fabric only runs gametests then)
and `-Dblockscraper.enabled=true`. Only the `runBlockScraper` task sets them. It only listens on loopback.

**Privacy:** `shot` reads Minecraft's own framebuffer (Fabric's `takeScreenshot` renders a frame and reads it
back). It never captures the screen or desktop, so other windows, notifications and anything behind or over the
game window can't appear in a PNG. Keep it that way: don't add OS-level screen capture.

**Determinism:** the game only advances when a command asks it to; shots render with a fixed frame delta. block-scraper
also turns off pause-on-lost-focus, the narrator, splash texts, Realms notifications and toasts, and the gametest
API turns off tutorials, clouds, onboarding and music. Animated things (the title panorama, blinking cursors) can
still differ by a few pixels.

**Headless:** with no `DISPLAY`, `start` runs the client under `xvfb-run -s "-screen 0 1920x1080x24"`. Mesa
llvmpipe renders GUI screens and a flat world fine (that's what CI does). Shaders and heavy world rendering
are slow and may come out black.

## Using it in your own mod

For now block-scraper lives in this repo; copy your mod in as a subproject next to `examples/sample-ui` (or publish
`block-scraper-mod` to mavenLocal). Then:

1. Depend on it in dev only: `implementation project(':block-scraper-mod')` (never `include` it in your jar).
2. Register screens with the `block-scraper` entrypoint, which is only loaded when block-scraper runs:
   ```json
   "entrypoints": { "block-scraper": ["com.example.MyBlockScraperScreens"] }
   ```
   ```java
   public final class MyBlockScraperScreens implements BlockScraperPlugin {
       public void registerScreens() {
           BlockScraper.register("my-config", args -> new MyConfigScreen(args.parent()));
       }
   }
   ```
3. Add `apply from: rootProject.file('gradle/block-scraper.gradle')` to your build to get the `runBlockScraper` task, and list
   both mods under `loom { mods { … } }` like `examples/sample-ui/build.gradle` does.

## Development

```sh
./gradlew -Pblockscraper.coreOnly test            # core + agent tests; no Minecraft needed
python3 -m unittest discover -s tests       # CLI tests against a fake mod
scripts/e2e.sh                              # milestones M1–M4 against the real client (what CI runs)
```

CI (`.github/workflows/block-scraper.yml`) runs all three on ubuntu-latest and uploads the PNGs as artifacts.

See [AGENTS.md](AGENTS.md) for the loop written for coding agents, and [HANDOFF.md](HANDOFF.md) for what's verified, what isn't, and what changed in Minecraft 26.3.
