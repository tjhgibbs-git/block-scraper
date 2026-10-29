package dev.mcshot.mod;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.client.gui.screens.Screen;

/**
 * Public API: register screens so {@code mcshot open <id>} can build them.
 *
 * <p>Call {@link #register} from a {@link McShotPlugin} (the {@code "mcshot"} entrypoint), which only runs when the
 * client was started by {@code mcshot start}, or from any client code before the first {@code open}.
 */
public final class McShot {
	private static final Map<String, ScreenFactory> SCREENS = new TreeMap<>();

	private McShot() {
	}

	/** Builds a screen for {@code mcshot open}. Runs on the render thread. */
	@FunctionalInterface
	public interface ScreenFactory {
		Screen create(ScreenArgs args) throws Exception;
	}

	/**
	 * What {@code open} was given.
	 *
	 * @param parent the screen to return to when this one closes: the title screen outside a world, else null
	 * @param values the {@code --arg k=v} pairs
	 */
	public record ScreenArgs(Screen parent, Map<String, String> values) {
		public String get(String key, String fallback) {
			return values.getOrDefault(key, fallback);
		}

		public int getInt(String key, int fallback) {
			String v = values.get(key);
			return v == null ? fallback : Integer.parseInt(v);
		}

		public boolean getBool(String key, boolean fallback) {
			String v = values.get(key);
			return v == null ? fallback : Boolean.parseBoolean(v);
		}
	}

	public static synchronized void register(String id, ScreenFactory factory) {
		SCREENS.put(id, factory);
	}

	static synchronized Map<String, ScreenFactory> screens() {
		return Collections.unmodifiableMap(new TreeMap<>(SCREENS));
	}
}
