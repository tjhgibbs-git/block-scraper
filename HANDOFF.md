# Handoff: state of mcshot

The first session ran in a sandbox that **could not reach maven.fabricmc.net or Mojang's servers**, so the
Fabric side was compiled and run only through GitHub Actions (below). Delete this file once e2e is green.

## Verified in the sandbox

- `mcshot-core`: socket server and command queue. 6 tests: replies, error passing, malformed input, ordering.
- `mcshot-agent`: hot swap. 2 tests that start a child JVM with the agent, recompile a class on disk and check the
  running code changes, and that a schema change gives `restart required` and leaves the old code running.
- `mcshot` CLI: 14 tests against `tests/fake_mod.py`, including the `start`/`stop` lifecycle with a fake
  `gradlew`, PID file, scripts, PNG stats/diff, exit codes.

Run: `./gradlew -Pmcshot.coreOnly test && python3 -m unittest discover -s tests`

## Verified by CI (GitHub Actions, ubuntu-latest, Temurin 25)

Run 3 (commit 8b3bb6b), the first one where the client launched:
- `./gradlew build` passes: Loom 1.19.3 resolves, Minecraft 26.3 downloads, everything compiles.
- **M1 passes**: `start` under Xvfb answers in 34 s (cold), `status` is right (1280x720, GUI scale 3,
  427x240 GUI), a title-screen shot takes ~250 ms and is non-blank (stddev 62), and a second shot is
  **pixel-identical** (0 pixels differ). Mesa llvmpipe gives GL 4.5 core under Xvfb.
- M2 so far: `screens`, `open sample-config --arg`, `widgets` (classes, bounds, EditBox value, hovered) and
  `hover` work. `click` went through MouseHandler and did not reach the screen.

Run 4: confirmed `MouseButtonEvent(double, double, MouseButtonInfo)`, `GuiEventListener.mouseClicked(MouseButtonEvent,
boolean)`, `GuiGraphicsExtractor.outline/centeredText/fill`. Shutdown crashed because cleanup threw before
returning to the title screen (fixed: the gametest runner requires ending on the title screen).

Earlier fixes: `click` now calls the screen's `mouseClicked`/`mouseReleased` directly
(reflection, until the `MouseButtonEvent` signature is confirmed); CI prints the client log and a `javap`
probe when e2e fails.

## Still open

1. `click` on the sample's counter button doesn't change it, through MouseHandler (run 3) or by calling
   `Screen.mouseClicked/mouseReleased` directly (run 4, no exception). `click` now reports `pressHandled` /
   `releaseHandled` to narrow it down.
2. 26.3 has no HUD-hidden boolean on `Minecraft`, `Gui` or `Options`. `hud on|off` now presses F1 and mcshot
   tracks the state (`status` → `hud`).
3. Rest of M2 (tooltip, scale, lang), M3 (hot reload timing) and M4 (world, HUD, inventory) haven't run yet.
5. The artifact download host (blob.core.windows.net) is blocked from the first sandbox, so the PNGs were not
   looked at yet. Download `mcshot-shots` from the Actions run page.

## Design decisions (and why)

- **Built on Fabric's client gametest API** rather than our own mixins: it's maintained per MC version, pauses the
  game for determinism, and already does screenshots, input, resizing and worlds. Cost: the session must be a
  gametest, so `runMcshot` sets `-Dfabric.client.gametest` with a modid filter so other mods' gametests don't run.
- **Hot reload via a `-javaagent`**, not JDWP: no debugger port, works on any JDK, and the agent can diff class
  files itself. JBR is optional for schema changes.
- **CLI in Python** (stdlib): no build step, starts in ~50 ms, and can decode PNGs for blank/diff checks.
- **Spec deviations:** added `screens`, `leave`, `wait`, `restart`, `log`, `stats`, `diff`, `click --text`;
  `world` defaults to flat (`--normal` for vanilla); screen factories get a `ScreenArgs` (parent + `--arg` map)
  and can also be registered through a `"mcshot"` entrypoint so nothing loads outside mcshot runs.
