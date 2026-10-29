package dev.mcshot.mod;

import java.lang.reflect.Field;

import net.minecraft.client.Minecraft;

import dev.mcshot.core.CommandException;

/**
 * Minecraft APIs whose 26.x names we have not confirmed yet (they moved during the Gui refactor). Found by
 * reflection so a rename is a warning, not a compile error. Replace with direct calls once the names are known.
 */
final class Compat {
	private Compat() {
	}

	/** Whether the HUD is hidden (F1). */
	static boolean hudHidden(Minecraft client) {
		for (Object owner : new Object[] {client.options, client.gui, client}) {
			Field f = field(owner, "hideGui", "hidden", "hideHud");
			if (f != null) {
				try {
					return f.getBoolean(owner);
				} catch (IllegalAccessException e) {
					throw new IllegalStateException(e);
				}
			}
		}
		throw new CommandException("mcshot cannot find the HUD-hidden flag in this Minecraft version");
	}

	static void setHudHidden(Minecraft client, boolean hidden) {
		for (Object owner : new Object[] {client.options, client.gui, client}) {
			Field f = field(owner, "hideGui", "hidden", "hideHud");
			if (f != null) {
				try {
					f.setBoolean(owner, hidden);
					return;
				} catch (IllegalAccessException e) {
					throw new IllegalStateException(e);
				}
			}
		}
		throw new CommandException("mcshot cannot find the HUD-hidden flag in this Minecraft version");
	}

	private static Field field(Object target, String... names) {
		for (String name : names) {
			for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
				try {
					Field f = c.getDeclaredField(name);
					if (f.getType() == boolean.class) {
						f.setAccessible(true);
						return f;
					}
				} catch (NoSuchFieldException e) {
					// keep looking
				} catch (RuntimeException e) {
					return null; // module/access issue
				}
			}
		}
		return null;
	}
}
