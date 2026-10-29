package dev.blockscraper.mod;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Comparator;

import net.minecraft.client.gui.screens.Screen;

import dev.blockscraper.core.CommandException;

/**
 * Builds a screen from whichever public constructor the given values fit, so vanilla registrations and
 * {@code open class:<fqcn>} survive constructor signature changes between Minecraft versions.
 */
final class Constructors {
	private Constructors() {
	}

	/**
	 * Each parameter takes the first unused value of a matching type. Parameters with no match get {@code parent} if
	 * they are a {@link Screen}, else false, 0, "" or null. The constructor that uses the most values wins.
	 */
	static Screen create(Class<?> type, Screen parent, Object... values) throws Exception {
		if (!Screen.class.isAssignableFrom(type)) {
			throw new CommandException(type.getName() + " is not a Screen");
		}
		Constructor<?>[] constructors = type.getConstructors();
		if (constructors.length == 0) {
			throw new CommandException(type.getName() + " has no public constructor");
		}
		Arrays.sort(constructors, Comparator.comparingInt(Constructor::getParameterCount));

		Constructor<?> best = null;
		Object[] bestArgs = null;
		int bestScore = -1;
		for (Constructor<?> c : constructors) {
			Class<?>[] params = c.getParameterTypes();
			Object[] args = new Object[params.length];
			boolean[] used = new boolean[values.length];
			int score = 0;
			boolean ok = true;
			for (int i = 0; i < params.length && ok; i++) {
				Class<?> p = box(params[i]);
				int match = -1;
				for (int v = 0; v < values.length; v++) {
					if (!used[v] && values[v] != null && p.isInstance(values[v])) {
						match = v;
						break;
					}
				}
				if (match >= 0) {
					used[match] = true;
					args[i] = values[match];
					score++;
				} else if (Screen.class.isAssignableFrom(params[i])) {
					args[i] = parent;
				} else if (params[i] == boolean.class) {
					args[i] = false;
				} else if (params[i] == int.class) {
					args[i] = 0;
				} else if (params[i] == String.class) {
					args[i] = "";
				} else if (!params[i].isPrimitive()) {
					args[i] = null;
				} else {
					ok = false;
				}
			}
			if (ok && score > bestScore) {
				best = c;
				bestArgs = args;
				bestScore = score;
			}
		}
		if (best == null) {
			throw new CommandException("no usable constructor on " + type.getName());
		}
		try {
			return (Screen) best.newInstance(bestArgs);
		} catch (InvocationTargetException e) {
			throw e.getCause() instanceof Exception ex ? ex : e;
		}
	}

	private static Class<?> box(Class<?> c) {
		if (!c.isPrimitive()) {
			return c;
		}
		if (c == boolean.class) {
			return Boolean.class;
		}
		if (c == int.class) {
			return Integer.class;
		}
		if (c == float.class) {
			return Float.class;
		}
		if (c == double.class) {
			return Double.class;
		}
		if (c == long.class) {
			return Long.class;
		}
		return c;
	}
}
