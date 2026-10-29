package dev.mcshot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommandServerTest {
	private final CommandQueue queue = new CommandQueue();
	private final List<String> executed = new ArrayList<>();
	private CommandServer server;
	private Thread worker;

	@BeforeEach
	void setUp() throws Exception {
		server = new CommandServer(0, queue, msg -> { });
		server.start();
		// Stands in for the gametest thread: one thread runs every command in order.
		worker = new Thread(() -> {
			try {
				while (true) {
					CommandQueue.Pending p = queue.poll(1000);
					if (p == null) {
						continue;
					}
					executed.add(p.request().cmd());
					try {
						switch (p.request().cmd()) {
							case "echo" -> p.result().complete(new JsonPrimitive(p.request().string("text")));
							case "boom" -> throw new IllegalStateException("kaput");
							case "int" -> p.result().complete(new JsonPrimitive(p.request().integer("n")));
							default -> throw new CommandException("unknown command '" + p.request().cmd() + "'");
						}
					} catch (RuntimeException e) {
						p.result().completeExceptionally(e);
					}
				}
			} catch (InterruptedException e) {
				// done
			}
		});
		worker.setDaemon(true);
		worker.start();
	}

	@AfterEach
	void tearDown() throws Exception {
		worker.interrupt();
		server.close();
	}

	private List<JsonObject> send(String... lines) throws Exception {
		try (Socket s = new Socket(InetAddress.getLoopbackAddress(), server.port());
				Writer out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
				BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
			List<JsonObject> replies = new ArrayList<>();
			for (String line : lines) {
				out.write(line + "\n");
				out.flush();
				replies.add(JsonParser.parseString(in.readLine()).getAsJsonObject());
			}
			return replies;
		}
	}

	@Test
	void repliesWithResultAndEchoesId() throws Exception {
		JsonObject reply = send("{\"id\":7,\"cmd\":\"echo\",\"args\":{\"text\":\"hi\"}}").get(0);
		assertTrue(reply.get("ok").getAsBoolean());
		assertEquals(7, reply.get("id").getAsInt());
		assertEquals("hi", reply.get("result").getAsString());
	}

	@Test
	void commandExceptionMessageIsPassedThrough() throws Exception {
		JsonObject reply = send("{\"id\":\"a\",\"cmd\":\"nope\"}").get(0);
		assertFalse(reply.get("ok").getAsBoolean());
		assertEquals("a", reply.get("id").getAsString());
		assertEquals("unknown command 'nope'", reply.get("error").getAsString());
	}

	@Test
	void unexpectedExceptionsAreNamed() throws Exception {
		JsonObject reply = send("{\"id\":1,\"cmd\":\"boom\"}").get(0);
		assertEquals("IllegalStateException: kaput", reply.get("error").getAsString());
	}

	@Test
	void badArgumentsGiveReadableErrors() throws Exception {
		List<JsonObject> replies = send("{\"id\":1,\"cmd\":\"int\",\"args\":{\"n\":\"x\"}}", "{\"id\":2,\"cmd\":\"echo\",\"args\":{}}");
		assertEquals("argument 'n' must be an integer, got 'x'", replies.get(0).get("error").getAsString());
		assertEquals("missing argument 'text'", replies.get(1).get("error").getAsString());
	}

	@Test
	void malformedLinesDoNotKillTheConnection() throws Exception {
		List<JsonObject> replies = send("not json", "{\"id\":2}", "{\"id\":3,\"cmd\":\"echo\",\"args\":{\"text\":\"still here\"}}");
		assertTrue(replies.get(0).get("error").getAsString().startsWith("malformed request"));
		assertEquals("request has no 'cmd'", replies.get(1).get("error").getAsString());
		assertEquals("still here", replies.get(2).get("result").getAsString());
	}

	@Test
	void commandsRunInOrderOnOneThread() throws Exception {
		send("{\"id\":1,\"cmd\":\"echo\",\"args\":{\"text\":\"a\"}}", "{\"id\":2,\"cmd\":\"int\",\"args\":{\"n\":3}}");
		assertEquals(List.of("echo", "int"), executed);
	}
}
