package dev.mcshot.mod;

import java.util.Map;
import java.util.TreeMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

import dev.mcshot.core.CommandException;

/** Vanilla screens, screens from {@link McShot#register}, and {@code class:<fqcn>}. */
final class ScreenRegistry {
	private static final Map<String, McShot.ScreenFactory> VANILLA = new TreeMap<>();

	static {
		VANILLA.put("title", a -> Constructors.create(TitleScreen.class, a.parent()));
		VANILLA.put("options", a -> Constructors.create(OptionsScreen.class, a.parent(), a.parent(), Minecraft.getInstance().options));
		VANILLA.put("chat", a -> Constructors.create(ChatScreen.class, a.parent(), a.get("text", "")));
		VANILLA.put("pause", a -> Constructors.create(PauseScreen.class, a.parent(), true));
		VANILLA.put("inventory", a -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null) {
				throw new CommandException("'inventory' needs a world: run `mcshot world` first");
			}
			return Constructors.create(InventoryScreen.class, a.parent(), mc.player);
		});
	}

	private ScreenRegistry() {
	}

	/** Runs every {@code "mcshot"} entrypoint. A broken plugin is logged and skipped, not fatal. */
	static void loadPlugins() {
		for (EntrypointContainer<McShotPlugin> plugin : FabricLoader.getInstance().getEntrypointContainers("mcshot", McShotPlugin.class)) {
			try {
				plugin.getEntrypoint().registerScreens();
			} catch (Throwable t) {
				McShotSession.LOGGER.error("mcshot plugin from {} failed", plugin.getProvider().getMetadata().getId(), t);
			}
		}
	}

	static Map<String, McShot.ScreenFactory> all() {
		Map<String, McShot.ScreenFactory> all = new TreeMap<>(VANILLA);
		all.putAll(McShot.screens());
		return all;
	}

	static McShot.ScreenFactory resolve(String id) {
		if (id.startsWith("class:")) {
			String name = id.substring("class:".length());
			return a -> {
				Class<?> type;
				try {
					type = Class.forName(name, true, ScreenRegistry.class.getClassLoader());
				} catch (ClassNotFoundException e) {
					throw new CommandException("no class " + name);
				}
				return Constructors.create(type, a.parent(), a.parent());
			};
		}
		McShot.ScreenFactory f = all().get(id);
		if (f == null) {
			throw new CommandException("unknown screen '" + id + "'. Known: " + String.join(", ", all().keySet())
					+ ". Or use class:<fully.qualified.ScreenClass>");
		}
		return f;
	}

	static Screen defaultParent(Minecraft mc) {
		return mc.level == null ? new TitleScreen() : null;
	}
}
