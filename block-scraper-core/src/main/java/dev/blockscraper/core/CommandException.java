package dev.blockscraper.core;

/** A failure caused by the request (bad args, wrong state). Its message is shown to the user as-is. */
public class CommandException extends RuntimeException {
	public CommandException(String message) {
		super(message);
	}

	public CommandException(String message, Throwable cause) {
		super(message, cause);
	}
}
