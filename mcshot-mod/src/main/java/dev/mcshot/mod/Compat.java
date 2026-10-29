package dev.mcshot.mod;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonInfo;

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

	/**
	 * Calls {@code screen.mouseClicked(MouseButtonEvent, boolean doubleClick)} then {@code mouseReleased(MouseButtonEvent)}.
	 * {@code MouseButtonEvent} is built from whichever constructor takes (double x, double y, MouseButtonInfo).
	 */
	static void clickScreen(Screen screen, double x, double y, int button) {
		try {
			Class<?> eventType = Class.forName("net.minecraft.client.input.MouseButtonEvent", true, Screen.class.getClassLoader());
			Constructor<?> ctor = eventType.getConstructor(double.class, double.class, MouseButtonInfo.class);
			Object event = ctor.newInstance(x, y, new MouseButtonInfo(button, 0));
			Method clicked = screen.getClass().getMethod("mouseClicked", eventType, boolean.class);
			Method released = screen.getClass().getMethod("mouseReleased", eventType);
			clicked.invoke(screen, event, false);
			released.invoke(screen, event);
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			throw cause instanceof RuntimeException re ? re : new IllegalStateException(cause);
		} catch (ReflectiveOperationException e) {
			String methods = Arrays.toString(Arrays.stream(screen.getClass().getMethods())
					.filter(m -> m.getName().startsWith("mouse")).map(Method::toGenericString).toArray());
			throw new CommandException("mcshot cannot deliver clicks in this Minecraft version (" + e + "). Screen mouse methods: " + methods);
		}
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
