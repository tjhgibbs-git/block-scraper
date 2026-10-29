# Iterating on Minecraft UI with block-scraper

You can see the game. Use this loop for any GUI work in this repo: look at the screen, change the code, look again.

## The loop

```sh
./block-scraper start                          # once per session; reuse the running client after that
./block-scraper status                         # check it's up, what screen is open, window and GUI size
./block-scraper open <screen-id> [--arg k=v]   # ./block-scraper screens lists the ids
./block-scraper shot shots/<name>.png          # then READ the PNG it prints
# ... edit the screen's code ...
./block-scraper reload                         # rebuild + hot swap + reopen (prints build/reload time)
./block-scraper shot shots/<name>-v2.png       # READ it again and compare
```

Always look at the PNG after `shot` (open the printed path with your image-reading tool). Don't assume an edit
worked because `reload` succeeded.

## Rules of thumb

- **Coordinates are GUI coordinates**, the same ones `widgets` prints and your `init()` code uses. They are not
  screenshot pixels: pixels = GUI coordinates × GUI scale (`status` shows `guiScale`).
- **Prefer `click --text "Label"`** to guessing coordinates. Run `widgets` to see what's clickable and its state.
- **Check layout at more than one size**: `size 854x480`, `size 1920x1080`, `scale 1..4`, and `lang de_de` for long
  strings. Overflow and overlap bugs show up there.
- **Tooltips:** `hover <x> <y>` over the widget, then `shot`. Move away with `hover 0 0`.
- **`reload` fails with `restart required`** when you add or remove methods or fields, change a class signature, or
  touch mixins. Run `./block-scraper restart`, then `open` the screen again. Don't work around the error.
- **Resources** (textures, lang files): `./block-scraper reload --resources`.
- **Build errors** come back from `reload` with the compiler output. Fix them and reload again.
- **Screens that need a world** (inventory, HUD work): `./block-scraper world`, then `open inventory` or `hud on|off`.
- **Batch steps** with `./block-scraper run steps.txt` (one command per line).
- **Something odd?** `./block-scraper log` shows the end of the client log, where stack traces land.
- **Done?** `./block-scraper stop`. Leaving it running is fine between edits; that's the point.

## Registering a new screen

Add it to the mod's `BlockScraperPlugin` (see `examples/sample-ui/.../SampleScreens.java`):

```java
BlockScraper.register("my-screen", args -> new MyScreen(args.parent(), args.get("mode", "default")));
```

`reload` re-runs the plugins, but each new lambda adds a method to the plugin class, so a new registration
usually needs `./block-scraper restart` (unless the client runs on JetBrains Runtime). After that, edits to `MyScreen`
hot reload.
