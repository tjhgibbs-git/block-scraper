package dev.blockscraper.mod;

/**
 * The {@code "block-scraper"} entrypoint. List your implementation in {@code fabric.mod.json}:
 *
 * <pre>{@code "entrypoints": { "block-scraper": ["com.example.MyBlockScraperScreens"] }}</pre>
 *
 * <p>It is only loaded when block-scraper is running, so it is a safe place for dev-only screen registrations.
 */
@FunctionalInterface
public interface BlockScraperPlugin {
	void registerScreens();
}
