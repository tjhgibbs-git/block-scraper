# Handoff: state of block-scraper

**Status: all milestones pass headless on GitHub Actions** (run 8, commit 429286e, ubuntu-latest, Temurin 25,
Xvfb + Mesa llvmpipe). The first session's sandbox could not reach maven.fabricmc.net or Mojang, so the
Fabric side was compiled and debugged entirely through CI; core, agent and CLI were also tested locally.

## Verified (CI run 8)

| Milestone | Result |
|---|---|
| M1 | `start` answers in 22 s (warm Gradle cache), 1280x720 title shot in ~240 ms, non-blank (stddev 62), repeat shot pixel-identical |
| M2 | registry + `--arg`, `widgets`, `click --text` changes the counter (`Clicked 0 times` → `Clicked 1 times`), hover tooltip, `scale 2`, `lang de_de`, `reopen` |
| M3 | edit colour → `reload` → new PNG in **3.9 s** (build 3.1 s, hot swap 0.2 s); the PNG changes; revert reload 1.9 s |
| M4 | `world --flat`, `hud off` changes the shot, inventory shot non-blank |
| Unit | 6 core + 2 agent Java tests, 14 CLI tests |

Shots take 200–600 ms. The spec's "done when" list: CI runs M1–M4 and uploads PNGs ✓; same commands give
the same pixels ✓ (title screen); README + AGENTS.md ✓; inert without the flag ✓ by construction (see below).

## Things learned about Minecraft 26.3 (all handled in the code)

- Screens live on `Gui`: `client.gui.screen()/setScreen()/overlay()/toastManager()`; `resizeDisplay()` is `resizeGui()`.
- Rendering: `render(GuiGraphics…)` → `extractRenderState(GuiGraphicsExtractor…)`; `fill`, `outline`,
  `centeredText`, `text`.
- Windowing is SDL3: **mouse buttons start at 1** (`InputConstants.MOUSE_BUTTON_LEFT`). Sending 0 is silently
  ignored by widgets while `Screen.mouseClicked` still returns true (it does whenever a child is under the cursor).
- No public HUD-hidden flag; `hud on|off` presses F1 and block-scraper tracks the state.
- The renderer is Vulkan-capable ("renderpearl"); under Xvfb it logs harmless swapchain warnings.
- Fabric's gametest runner crashes the game if the session doesn't end on the title screen with no world,
  so `Commands.cleanUp` restores that before returning.

## Not verified yet

- **Nobody has looked at the PNGs.** The artifact host (blob.core.windows.net) was blocked from the first
  sandbox, so only automated checks (non-blank, pixel diffs) ran. Download `block-scraper-shots` from the run page
  and eyeball them, especially the tooltip, de_de and inventory shots.
- Running with the enable flag unset: inert by construction (Fabric only calls gametest entrypoints with
  `-Dfabric.client.gametest`; block-scraper also checks `-Dblock-scraper.enabled`), but there's no test for it.
- JetBrains Runtime / `--java-home` (enhanced redefinition), macOS and Windows, a visible desktop window
  (`--idle-tick`), and `open class:<fqcn>` / `options` / `chat` / `pause` screens.
- `key` and `type` aren't exercised by e2e.
- A cold-Gradle `reload` takes ~11 s; e2e warms Gradle first because the target is the steady-state loop.

## Possible next steps

- Wrap the CLI as an MCP server (the protocol is already JSON-lines).
- Publish `block-scraper-mod`/`block-scraper-agent` (or a Gradle plugin) so other mod repos can depend on it instead of
  copying `gradle/block-scraper.gradle`.
- e2e coverage for `key`/`type`, `class:`, and the flag-unset check.
- CI cleanup: remove the javap probe steps once nobody needs them; bump actions off Node 20.

## Design decisions (and why)

- **Built on Fabric's client gametest API** rather than our own mixins: it's maintained per MC version, pauses the
  game for determinism, and already does screenshots, input, resizing and worlds. Cost: the session must be a
  gametest, so `runBlockScraper` sets `-Dfabric.client.gametest` with a modid filter so other mods' gametests don't run.
- **Hot reload via a `-javaagent`**, not JDWP: no debugger port, works on any JDK, and the agent can diff class
  files itself. JBR is optional for schema changes.
- **CLI in Python** (stdlib): no build step, starts in ~50 ms, and can decode PNGs for blank/diff checks.
- **Spec deviations:** added `screens`, `leave`, `wait`, `restart`, `log`, `stats`, `diff`, `click --text`;
  `world` defaults to flat (`--normal` for vanilla); screen factories get a `ScreenArgs` (parent + `--arg` map)
  and can also be registered through a `"block-scraper"` entrypoint so nothing loads outside block-scraper runs.
