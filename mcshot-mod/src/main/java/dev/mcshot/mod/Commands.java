package dev.mcshot.mod;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;

import dev.mcshot.core.CommandException;
import dev.mcshot.core.Png;
import dev.mcshot.core.Request;

/** Executes one command at a time on the client gametest thread. Every method returns once the game has caught up. */
final class Commands {
	private static final Duration RESOURCE_RELOAD_TIMEOUT = Duration.ofSeconds(120);

	private final ClientGameTestContext context;
	private final TestInput input;
	private TestSingleplayerContext world;
	private String lastScreenId;
	private Map<String, String> lastScreenArgs = Map.of();
	private boolean quit;
	private boolean hudVisible = true;

	Commands(ClientGameTestContext context) {
		this.context = context;
		this.input = context.getInput();
	}

	boolean quitRequested() {
		return quit;
	}

	JsonElement run(Request r) throws Exception {
		return switch (r.cmd()) {
			case "ping" -> new JsonPrimitive("pong");
			case "status" -> status();
			case "screens" -> screens();
			case "open" -> open(r.string("id"), toMap(r.object("args")));
			case "reopen" -> reopen();
			case "close" -> close();
			case "size" -> size(r.integer("width"), r.integer("height"));
			case "scale" -> scale(r.integer("scale"));
			case "lang" -> lang(r.string("code"));
			case "world" -> world(r.flag("normal"));
			case "leave" -> leave();
			case "hud" -> hud(r.flag("on"));
			case "hover" -> hover(r.decimal("x"), r.decimal("y"));
			case "click" -> r.args().has("text")
					? clickText(r.string("text"), r.integer("button", 0))
					: click(r.decimal("x"), r.decimal("y"), r.integer("button", 0));
			case "key" -> key(r.string("key"));
			case "type" -> type(r.string("text"));
			case "wait" -> waitTicks(r.integer("ticks"));
			case "shot" -> shot(Path.of(r.string("path")), r.integer("wait", 2), r.integer("width", 0), r.integer("height", 0));
			case "widgets" -> widgets();
			case "reload" -> reload(r.flag("resources"), r.flag("noReopen"));
			case "quit" -> {
				quit = true;
				yield new JsonPrimitive("bye");
			}
			default -> throw new CommandException("unknown command '" + r.cmd() + "'");
		};
	}

	// --- state -------------------------------------------------------------------------------------------------

	private JsonElement status() {
		JsonObject o = new JsonObject();
		o.addProperty("mcshot", version("mcshot"));
		o.addProperty("minecraft", version("minecraft"));
		o.addProperty("pid", ProcessHandle.current().pid());
		context.runOnClient(client -> {
			Window w = client.getWindow();
			Screen screen = client.gui.screen();
			o.addProperty("screen", screen == null ? null : screen.getClass().getName());
			o.addProperty("screenTitle", screen == null ? null : screen.getTitle().getString());
			o.addProperty("width", w.getWidth());
			o.addProperty("height", w.getHeight());
			o.addProperty("guiWidth", w.getGuiScaledWidth());
			o.addProperty("guiHeight", w.getGuiScaledHeight());
			o.addProperty("guiScale", String.valueOf(w.getGuiScale()));
			o.addProperty("guiScaleSetting", client.options.guiScale().get());
			o.addProperty("lang", client.options.languageCode);
			o.addProperty("inWorld", client.level != null);
			o.addProperty("hud", hudVisible);
		});
		o.addProperty("lastOpen", lastScreenId);
		o.addProperty("hotReload", Agent.describe());
		return o;
	}

	private JsonElement screens() {
		JsonArray a = new JsonArray();
		ScreenRegistry.all().keySet().forEach(a::add);
		return a;
	}

	// --- screens -----------------------------------------------------------------------------------------------

	private JsonElement open(String id, Map<String, String> args) {
		McShot.ScreenFactory factory = ScreenRegistry.resolve(id);
		String opened = context.computeOnClient(client -> {
			Screen screen;
			try {
				screen = factory.create(new McShot.ScreenArgs(ScreenRegistry.defaultParent(client), args));
			} catch (CommandException e) {
				throw e;
			} catch (Exception e) {
				McShotSession.LOGGER.error("screen factory for '{}' failed", id, e);
				throw new CommandException("screen '" + id + "' failed to build: " + e, e);
			}
			client.gui.setScreen(screen);
			Screen now = client.gui.screen();
			return now == null ? null : now.getClass().getName();
		});
		lastScreenId = id;
		lastScreenArgs = args;
		context.waitTick();
		JsonObject o = new JsonObject();
		o.addProperty("screen", opened);
		return o;
	}

	private JsonElement reopen() {
		if (lastScreenId == null) {
			throw new CommandException("nothing to reopen: use `open <id>` first");
		}
		return open(lastScreenId, lastScreenArgs);
	}

	private JsonElement close() {
		context.runOnClient(client -> client.gui.setScreen(null));
		context.waitTick();
		return new JsonPrimitive(currentScreen());
	}

	// --- window ------------------------------------------------------------------------------------------------

	void applyInitialSize(String spec) {
		String[] parts = spec.toLowerCase(Locale.ROOT).split("x");
		if (parts.length == 2) {
			size(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
		}
	}

	private JsonElement size(int width, int height) {
		if (width < 64 || height < 64 || width > 8192 || height > 8192) {
			throw new CommandException("size must be between 64x64 and 8192x8192");
		}
		input.resizeWindow(width, height);
		context.waitTick();
		return context.computeOnClient(client -> {
			JsonObject o = new JsonObject();
			o.addProperty("width", client.getWindow().getWidth());
			o.addProperty("height", client.getWindow().getHeight());
			o.addProperty("guiWidth", client.getWindow().getGuiScaledWidth());
			o.addProperty("guiHeight", client.getWindow().getGuiScaledHeight());
			return o;
		});
	}

	private JsonElement scale(int scale) {
		if (scale < 0) {
			throw new CommandException("scale must be 0 (auto) or positive");
		}
		JsonObject o = context.computeOnClient(client -> {
			client.options.guiScale().set(scale);
			if (client.options.guiScale().get() != scale) {
				throw new CommandException("gui scale " + scale + " is not available at this window size");
			}
			client.resizeGui();
			JsonObject r = new JsonObject();
			r.addProperty("guiScale", String.valueOf(client.getWindow().getGuiScale()));
			r.addProperty("guiWidth", client.getWindow().getGuiScaledWidth());
			r.addProperty("guiHeight", client.getWindow().getGuiScaledHeight());
			return r;
		});
		context.waitTick();
		return o;
	}

	private JsonElement lang(String code) {
		CompletableFuture<?> reload = context.computeOnClient(client -> {
			if (client.getLanguageManager().getLanguage(code) == null) {
				throw new CommandException("unknown language '" + code + "' (codes look like en_us, de_de, ja_jp)");
			}
			client.getLanguageManager().setSelected(code);
			client.options.languageCode = code;
			return client.reloadResourcePacks();
		});
		awaitResourceReload(reload);
		return new JsonPrimitive(code);
	}

	/** 26.3 has no public HUD-hidden flag, so toggle it the way a player does: F1. mcshot tracks the state. */
	private JsonElement hud(boolean on) {
		if (on != hudVisible) {
			input.pressKey(InputConstants.getKey("key.keyboard.f1"));
			hudVisible = on;
		}
		context.waitTick();
		return new JsonPrimitive(on);
	}

	// --- world -------------------------------------------------------------------------------------------------

	private JsonElement world(boolean normal) {
		if (world == null) {
			// Consistent settings = flat, seed 1, no mobs, frozen time and weather.
			world = context.worldBuilder().setUseConsistentSettings(!normal).create();
			world.getConnection().waitForChunksRender();
		}
		JsonObject o = new JsonObject();
		o.addProperty("save", world.getWorldSave().getSaveDirectory().toString());
		o.addProperty("screen", currentScreen());
		return o;
	}

	private JsonElement leave() {
		if (world == null) {
			throw new CommandException("not in a world");
		}
		world.close();
		world = null;
		return new JsonPrimitive(currentScreen());
	}

	// --- input -------------------------------------------------------------------------------------------------

	/** Moves the cursor to GUI coordinates (the ones widgets use), whatever the GUI scale or window DPI. */
	private JsonElement hover(double x, double y) {
		double[] window = context.computeOnClient(client -> {
			Window w = client.getWindow();
			return new double[] {
				x * w.getScreenWidth() / w.getGuiScaledWidth(),
				y * w.getScreenHeight() / w.getGuiScaledHeight()
			};
		});
		input.setCursorPos(window[0], window[1]);
		context.waitTick();
		JsonObject o = new JsonObject();
		o.add("widget", widgetAt(x, y));
		return o;
	}

	private JsonElement click(double x, double y, int button) {
		JsonObject o = hover(x, y).getAsJsonObject();
		// Simulated clicks through MouseHandler don't reach screens in 26.3 under the gametest framework, so deliver
		// the press and release to the screen directly, as MouseHandler would.
		boolean[] handled = context.computeOnClient(client -> {
			Screen screen = client.gui.screen();
			if (screen == null) {
				throw new CommandException("no screen open to click on");
			}
			MouseButtonEvent event = new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
			boolean pressed = screen.mouseClicked(event, false);
			boolean released = screen.mouseReleased(event);
			return new boolean[] {pressed, released};
		});
		context.waitTick();
		o.addProperty("pressHandled", handled[0]);
		o.addProperty("releaseHandled", handled[1]);
		o.addProperty("screen", currentScreen());
		return o;
	}

	private JsonElement clickText(String text, int button) {
		JsonArray all = widgets().getAsJsonObject().getAsJsonArray("widgets");
		List<String> seen = new ArrayList<>();
		for (JsonElement e : all) {
			JsonObject w = e.getAsJsonObject();
			String message = w.has("text") && !w.get("text").isJsonNull() ? w.get("text").getAsString() : "";
			seen.add(message);
			if (message.equals(text) && w.get("visible").getAsBoolean()) {
				double cx = w.get("x").getAsInt() + w.get("w").getAsInt() / 2.0;
				double cy = w.get("y").getAsInt() + w.get("h").getAsInt() / 2.0;
				return click(cx, cy, button);
			}
		}
		throw new CommandException("no visible widget with text '" + text + "'. Widgets: " + seen);
	}

	/** Key names are vanilla's ({@code escape}, {@code enter}, {@code tab}, {@code a}, {@code f3}), with optional {@code ctrl+}, {@code shift+}, {@code alt+}. */
	private JsonElement key(String spec) {
		String[] parts = spec.toLowerCase(Locale.ROOT).split("\\+");
		String name = parts[parts.length - 1];
		InputConstants.Key key = InputConstants.getKey(name.startsWith("key.") ? name : "key.keyboard." + name);
		if (key == InputConstants.UNKNOWN) {
			throw new CommandException("unknown key '" + name + "' (try escape, enter, tab, backspace, left, a, f1)");
		}
		List<Runnable> release = new ArrayList<>();
		for (int i = 0; i < parts.length - 1; i++) {
			switch (parts[i]) {
				case "ctrl", "control", "cmd" -> {
					input.holdControl();
					release.add(input::releaseControl);
				}
				case "shift" -> {
					input.holdShift();
					release.add(input::releaseShift);
				}
				case "alt" -> {
					input.holdAlt();
					release.add(input::releaseAlt);
				}
				default -> throw new CommandException("unknown modifier '" + parts[i] + "' (ctrl, shift, alt)");
			}
		}
		try {
			input.pressKey(key);
		} finally {
			release.forEach(Runnable::run);
		}
		context.waitTick();
		return new JsonPrimitive(currentScreen());
	}

	private JsonElement type(String text) {
		input.typeChars(text);
		context.waitTick();
		return new JsonPrimitive(text);
	}

	private JsonElement waitTicks(int ticks) {
		if (ticks < 0 || ticks > 20 * 60 * 10) {
			throw new CommandException("ticks must be between 0 and 12000");
		}
		context.waitTicks(ticks);
		return new JsonPrimitive(ticks);
	}

	// --- output ------------------------------------------------------------------------------------------------

	private JsonElement shot(Path path, int waitTicks, int width, int height) throws Exception {
		long start = System.nanoTime();
		Path out = path.toAbsolutePath().normalize();
		Files.createDirectories(out.getParent());
		if (waitTicks > 0) {
			context.waitTicks(waitTicks);
		}
		context.runOnClient(client -> client.gui.toastManager().clear());

		String fileName = out.getFileName().toString();
		String name = fileName.endsWith(".png") ? fileName.substring(0, fileName.length() - 4) : fileName;
		TestScreenshotOptions options = TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(out.getParent());
		if (width > 0 && height > 0) {
			options = options.withSize(width, height);
		}
		Path saved = context.takeScreenshot(options);
		if (!saved.toAbsolutePath().normalize().equals(out)) {
			Files.move(saved, out, StandardCopyOption.REPLACE_EXISTING);
		}

		Png.Size size = Png.size(out);
		JsonObject o = new JsonObject();
		o.addProperty("path", out.toString());
		o.addProperty("width", size.width());
		o.addProperty("height", size.height());
		o.addProperty("screen", currentScreen());
		o.addProperty("millis", (System.nanoTime() - start) / 1_000_000);
		return o;
	}

	private JsonElement widgets() {
		return context.computeOnClient(client -> {
			JsonObject o = new JsonObject();
			Screen screen = client.gui.screen();
			o.addProperty("screen", screen == null ? null : screen.getClass().getName());
			o.addProperty("guiWidth", client.getWindow().getGuiScaledWidth());
			o.addProperty("guiHeight", client.getWindow().getGuiScaledHeight());
			JsonArray list = new JsonArray();
			if (screen != null) {
				for (AbstractWidget w : Screens.getWidgets(screen)) {
					list.add(describe(w));
				}
			}
			o.add("widgets", list);
			return o;
		});
	}

	private static JsonObject describe(AbstractWidget w) {
		JsonObject o = new JsonObject();
		o.addProperty("class", w.getClass().getName());
		o.addProperty("x", w.getX());
		o.addProperty("y", w.getY());
		o.addProperty("w", w.getWidth());
		o.addProperty("h", w.getHeight());
		o.addProperty("text", w.getMessage().getString());
		o.addProperty("active", w.active);
		o.addProperty("visible", w.visible);
		o.addProperty("focused", w.isFocused());
		o.addProperty("hovered", w.isHovered());
		if (w instanceof EditBox box) {
			o.addProperty("value", box.getValue());
		}
		return o;
	}

	private JsonElement widgetAt(double x, double y) {
		for (JsonElement e : widgets().getAsJsonObject().getAsJsonArray("widgets")) {
			JsonObject w = e.getAsJsonObject();
			int wx = w.get("x").getAsInt();
			int wy = w.get("y").getAsInt();
			if (w.get("visible").getAsBoolean() && x >= wx && y >= wy && x < wx + w.get("w").getAsInt() && y < wy + w.get("h").getAsInt()) {
				return w;
			}
		}
		return null;
	}

	// --- hot reload --------------------------------------------------------------------------------------------

	private JsonElement reload(boolean resources, boolean noReopen) {
		Map<String, List<String>> report = Agent.reload();
		List<String> error = report.getOrDefault("error", List.of());
		if (!error.isEmpty()) {
			throw new CommandException(error.get(0));
		}
		ScreenRegistry.loadPlugins(); // picks up edited registrations
		if (resources) {
			awaitResourceReload(context.computeOnClient(Minecraft::reloadResourcePacks));
		}
		JsonObject o = new JsonObject();
		o.add("redefined", toArray(report.get("redefined")));
		o.add("pending", toArray(report.get("pending")));
		o.addProperty("resources", resources);
		if (!noReopen && lastScreenId != null) {
			o.add("reopened", open(lastScreenId, lastScreenArgs));
		}
		return o;
	}

	private void awaitResourceReload(CompletableFuture<?> reload) {
		// The loading overlay fades out on wall-clock time after the reload itself finishes.
		waitUntil(client -> reload.isDone() && client.gui.overlay() == null, RESOURCE_RELOAD_TIMEOUT, "resource reload");
		if (reload.isCompletedExceptionally()) {
			reload.join(); // rethrows the failure
		}
	}

	// --- helpers -----------------------------------------------------------------------------------------------

	private void waitUntil(Predicate<Minecraft> done, Duration timeout, String what) {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (!context.computeOnClient(done::test)) {
			if (System.nanoTime() > deadline) {
				throw new CommandException(what + " did not finish within " + timeout.toSeconds() + "s");
			}
			context.waitTick();
		}
	}

	private String currentScreen() {
		return context.computeOnClient(client -> {
			Screen s = client.gui.screen();
			return s == null ? null : s.getClass().getName();
		});
	}

	private static String version(String modId) {
		return FabricLoader.getInstance().getModContainer(modId)
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}

	private static Map<String, String> toMap(JsonObject o) {
		Map<String, String> m = new LinkedHashMap<>();
		o.entrySet().forEach(e -> m.put(e.getKey(), e.getValue().getAsString()));
		return m;
	}

	private static JsonArray toArray(List<String> list) {
		JsonArray a = new JsonArray();
		if (list != null) {
			list.forEach(a::add);
		}
		return a;
	}

	/** The gametest runner insists the game ends on the title screen with no world, so leave it that way. */
	void cleanUp() {
		try {
			if (world != null) {
				world.close();
				world = null;
			}
			context.runOnClient(client -> client.gui.setScreen(new TitleScreen()));
			if (!hudVisible) {
				hud(true);
			}
		} catch (Throwable t) {
			McShotSession.LOGGER.warn("mcshot cleanup failed", t);
		}
	}

	/** Reaches the hot-swap agent through the system class loader; the game's class loader has its own copy. */
	static final class Agent {
		private static final String CLASS = "dev.mcshot.agent.HotSwapAgent";

		private Agent() {
		}

		private static Class<?> find() {
			try {
				Class<?> c = ClassLoader.getSystemClassLoader().loadClass(CLASS);
				return (Boolean) c.getMethod("isInstalled").invoke(null) ? c : null;
			} catch (ReflectiveOperationException e) {
				return null;
			}
		}

		static String describe() {
			Class<?> c = find();
			if (c == null) {
				return "unavailable (client not started with -javaagent:mcshot-agent.jar)";
			}
			try {
				return (Boolean) c.getMethod("enhancedRedefinition").invoke(null)
						? "enhanced (methods and fields can change)" : "method bodies only";
			} catch (ReflectiveOperationException e) {
				return "unknown: " + e;
			}
		}

		@SuppressWarnings("unchecked")
		static Map<String, List<String>> reload() {
			Class<?> c = find();
			if (c == null) {
				throw new CommandException("hot reload unavailable: the client was not started with the mcshot agent. Use `mcshot start`, or `mcshot restart`");
			}
			try {
				return (Map<String, List<String>>) c.getMethod("reload").invoke(null);
			} catch (InvocationTargetException e) {
				throw new CommandException("hot reload failed: " + e.getCause(), e.getCause());
			} catch (ReflectiveOperationException e) {
				throw new CommandException("hot reload failed: " + e, e);
			}
		}
	}
}
