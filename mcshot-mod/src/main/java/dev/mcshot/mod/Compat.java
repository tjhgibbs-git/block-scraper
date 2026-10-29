package dev.mcshot.mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.client.Minecraft;

import dev.mcshot.core.CommandException;

/**
 * Minecraft APIs whose 26.x names we have not confirmed yet (they moved during the Gui refactor). Found by
 * reflection so a rename is a warning, not a compile error. Replace with direct calls once the names are known.
 */
final class Compat {
	private Compat() {
	}

	/** Clears pending toasts. Best effort: shots are still taken if this fails. */
	static void clearToasts(Minecraft client) {
		for (Object owner : new Object[] {client, client.gui}) {
			Object toasts = call(owner, "getToastManager", "toastManager", "toasts", "getToasts");
			if (toasts != null && call(toasts, "clear") != null) {
				return;
			}
		}
		McShotSession.LOGGER.debug("mcshot: no toast manager found; toasts not cleared");
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

	/** Calls the first public no-arg method that exists; returns its result (a void call returns the target), or null. */
	private static Object call(Object target, String... names) {
		for (String name : names) {
			try {
				Method m = target.getClass().getMethod(name);
				Object result = m.invoke(target);
				return m.getReturnType() == void.class ? target : result;
			} catch (NoSuchMethodException e) {
				// try the next name
			} catch (ReflectiveOperationException | RuntimeException e) {
				return null;
			}
		}
		return null;
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
