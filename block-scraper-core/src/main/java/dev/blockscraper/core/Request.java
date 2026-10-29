package dev.blockscraper.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** One command from the CLI: {@code {"id": ..., "cmd": "...", "args": {...}}}. */
public record Request(JsonElement id, String cmd, JsonObject args) {
	public String string(String name) {
		JsonElement e = args.get(name);
		if (e == null || e.isJsonNull()) {
			throw new CommandException("missing argument '" + name + "'");
		}
		return e.getAsString();
	}

	public String string(String name, String fallback) {
		JsonElement e = args.get(name);
		return e == null || e.isJsonNull() ? fallback : e.getAsString();
	}

	public int integer(String name) {
		return number(name, string(name));
	}

	public int integer(String name, int fallback) {
		JsonElement e = args.get(name);
		return e == null || e.isJsonNull() ? fallback : number(name, e.getAsString());
	}

	public double decimal(String name) {
		String raw = string(name);
		try {
			return Double.parseDouble(raw);
		} catch (NumberFormatException ex) {
			throw new CommandException("argument '" + name + "' must be a number, got '" + raw + "'");
		}
	}

	public boolean flag(String name) {
		JsonElement e = args.get(name);
		if (e == null || e.isJsonNull()) {
			return false;
		}
		if (e instanceof JsonPrimitive p && p.isBoolean()) {
			return p.getAsBoolean();
		}
		return Boolean.parseBoolean(e.getAsString());
	}

	/** The {@code args.args} object, used for screen-factory arguments ({@code open --arg k=v}). */
	public JsonObject object(String name) {
		JsonElement e = args.get(name);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
	}

	private static int number(String name, String raw) {
		try {
			return Integer.parseInt(raw.trim());
		} catch (NumberFormatException ex) {
			throw new CommandException("argument '" + name + "' must be an integer, got '" + raw + "'");
		}
	}
}
