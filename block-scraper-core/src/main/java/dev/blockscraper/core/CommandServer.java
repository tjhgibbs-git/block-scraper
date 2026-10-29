package dev.blockscraper.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Newline-delimited JSON over TCP, bound to loopback only.
 *
 * <p>Request: {@code {"id": any, "cmd": "name", "args": {...}}}. Reply: {@code {"id": same, "ok": true, "result": ...}}
 * or {@code {"id": same, "ok": false, "error": "message"}}. Connections are served concurrently but every command runs
 * through one {@link CommandQueue}, so commands never overlap.
 */
public final class CommandServer implements AutoCloseable {
	public static final int DEFAULT_PORT = 25599;

	private final ServerSocket socket;
	private final CommandQueue queue;
	private final Consumer<String> log;
	private volatile boolean closed;

	public CommandServer(int port, CommandQueue queue, Consumer<String> log) throws IOException {
		this.socket = new ServerSocket(port, 8, InetAddress.getLoopbackAddress());
		this.queue = queue;
		this.log = log;
	}

	public int port() {
		return socket.getLocalPort();
	}

	public void start() {
		Thread acceptor = new Thread(this::acceptLoop, "block-scraper-server");
		acceptor.setDaemon(true);
		acceptor.start();
	}

	private void acceptLoop() {
		while (!closed) {
			try {
				Socket client = socket.accept();
				Thread t = new Thread(() -> serve(client), "block-scraper-conn-" + client.getPort());
				t.setDaemon(true);
				t.start();
			} catch (IOException e) {
				if (!closed) {
					log.accept("accept failed: " + e);
				}
			}
		}
	}

	private void serve(Socket client) {
		try (client;
				BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
				Writer out = new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8)) {
			String line;
			while ((line = in.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				out.write(handle(line).toString());
				out.write('\n');
				out.flush();
			}
		} catch (SocketException e) {
			// client went away
		} catch (IOException e) {
			log.accept("connection failed: " + e);
		}
	}

	JsonObject handle(String line) {
		JsonElement id = JsonNull.INSTANCE;
		try {
			JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
			id = obj.has("id") ? obj.get("id") : JsonNull.INSTANCE;
			if (!obj.has("cmd")) {
				return error(id, "request has no 'cmd'");
			}
			JsonObject args = obj.has("args") && obj.get("args").isJsonObject() ? obj.getAsJsonObject("args") : new JsonObject();
			Request request = new Request(id, obj.get("cmd").getAsString(), args);
			JsonElement result = queue.submit(request).get();
			JsonObject reply = new JsonObject();
			reply.add("id", id);
			reply.addProperty("ok", true);
			reply.add("result", result == null ? JsonNull.INSTANCE : result);
			return reply;
		} catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
			return error(id, "malformed request: " + e.getMessage());
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof CommandException) {
				return error(id, cause.getMessage());
			}
			log.accept("command failed: " + cause);
			return error(id, cause.getClass().getSimpleName() + ": " + cause.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return error(id, "interrupted");
		}
	}

	private static JsonObject error(JsonElement id, String message) {
		JsonObject reply = new JsonObject();
		reply.add("id", id);
		reply.addProperty("ok", false);
		reply.addProperty("error", message);
		return reply;
	}

	@Override
	public void close() throws IOException {
		closed = true;
		socket.close();
	}
}
