package dev.blockscraper.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PngTest {
	@Test
	void readsSize(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("a.png");
		ImageIO.write(new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
		assertEquals(new Png.Size(1280, 720), Png.size(file));
	}

	@Test
	void rejectsNonPng(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("a.png");
		Files.writeString(file, "definitely not a png");
		assertThrows(IOException.class, () -> Png.size(file));
	}
}
