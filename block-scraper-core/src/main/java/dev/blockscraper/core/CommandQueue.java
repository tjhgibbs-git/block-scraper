package dev.blockscraper.core;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonElement;

/**
 * Hands requests from the socket thread to the single thread that may touch the game (in the mod: the client
 * gametest thread). The socket thread blocks until the command has finished, so a reply always means "done".
 */
public final class CommandQueue {
	public record Pending(Request request, CompletableFuture<JsonElement> result) {
	}

	private final BlockingQueue<Pending> queue = new LinkedBlockingQueue<>();

	CompletableFuture<JsonElement> submit(Request request) {
		Pending pending = new Pending(request, new CompletableFuture<>());
		queue.add(pending);
		return pending.result();
	}

	/** Waits up to {@code timeoutMillis} for the next command; null if none arrived. */
	public Pending poll(long timeoutMillis) throws InterruptedException {
		return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
	}

	/** Fails every queued command, e.g. when the game is shutting down. */
	public void failAll(String message) {
		Pending p;
		while ((p = queue.poll()) != null) {
			p.result().completeExceptionally(new CommandException(message));
		}
	}
}
