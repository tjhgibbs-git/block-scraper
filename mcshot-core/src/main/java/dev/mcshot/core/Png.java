package dev.mcshot.core;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads a PNG's size from its IHDR chunk, without pulling in AWT/ImageIO (unsafe to load inside the game on macOS). */
public final class Png {
	private static final long SIGNATURE = 0x89504E470D0A1A0AL;

	private Png() {
	}

	public record Size(int width, int height) {
	}

	public static Size size(Path file) throws IOException {
		try (InputStream raw = Files.newInputStream(file); DataInputStream in = new DataInputStream(raw)) {
			if (in.readLong() != SIGNATURE) {
				throw new IOException(file + " is not a PNG");
			}
			in.readInt(); // IHDR length
			if (in.readInt() != 0x49484452) {
				throw new IOException(file + " has no IHDR chunk");
			}
			return new Size(in.readInt(), in.readInt());
		}
	}
}
