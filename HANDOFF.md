# Handoff: state of mcshot

Written at the end of the first session, which ran in a sandbox that **could not reach maven.fabricmc.net or
Mojang's servers**, so Minecraft could not be downloaded, compiled against or run. Everything that doesn't need
Minecraft was built and tested; the Fabric side is written against the real 26.3 APIs but has **never been
compiled**. Delete this file once the e2e run is green.

## Verified here

- `mcshot-core`: socket server and command queue. 6 tests: replies, error passing, malformed input, ordering.
- `mcshot-agent`: hot swap. 2 tests that start a child JVM with the agent, recompile a class on disk and check the
  running code changes, and that a schema change gives `restart required` and leaves the old code running.
- `mcshot` CLI: 14 tests against `tests/fake_mod.py`, including the `start`/`stop` lifecycle with a fake
  `gradlew`, PID file, scripts, PNG stats/diff, exit codes.

Run: `./gradlew -Pmcshot.coreOnly test && python3 -m unittest discover -s tests`

## Not verified (next session, in an environment with Fabric/Mojang access)

1. `./gradlew build`: first compile of `mcshot-mod` and `examples/sample-ui`. The MC/Fabric names came from Fabric
   API's own 26.3 sources (`client.gui.screen()`, `TestInput`, `TestScreenshotOptions`, `Screens.getWidgets` …). The
   guesses most likely to need fixing:
   - `Minecraft.resizeDisplay()` (in `Commands.scale`)
   - `Options.hideSplashTexts()`, `realmsNotifications()`, `narrator()`; `Minecraft.getToastManager().clear()`
   - `InputConstants.getKey(String)` and `InputConstants.UNKNOWN` (in `Commands.key`)
   - `LanguageManager.getLanguage/setSelected`, `Minecraft.reloadResourcePacks()`, `getOverlay()`
   - `Window.getGuiScale()` (int or double; we only stringify it)
   - `CycleButton.onOffBuilder(...).withTooltip(...).create(...)` and `GuiGraphics.renderOutline` in the sample
   - Loom DSL in `gradle/mcshot.gradle` (`property`, `vmArg`, `runDir`) and `loom.mods { sourceSet 'main', ':mcshot-mod' }`
2. `scripts/e2e.sh` (M1–M4). Things to watch:
   - Does the gametest thread stay alive indefinitely while idle? (We poll the queue; the game is paused.)
   - Does Xvfb + Mesa work with Minecraft 26.x's SDL3 window? `start` sets `SDL_VIDEODRIVER=x11` under Xvfb.
   - Does the Gradle daemon pass `DISPLAY` from `xvfb-run` to the forked client? If not, set it in the run config.
   - Are the agent's watched dirs right? `status` → `hotReload`; the agent defaults to the class path directories
     plus `fabric.classPathGroups`. Override with `-Dmcshot.classDirs`.
   - Shot time (target < 2 s) and edit→PNG time (target < 10 s; `reload` prints both halves).
3. Push and check the GitHub Actions run; the PNGs are uploaded as the `mcshot-shots` artifact.

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
