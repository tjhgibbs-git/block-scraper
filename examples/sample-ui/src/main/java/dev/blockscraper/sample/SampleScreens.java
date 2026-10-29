package dev.blockscraper.sample;

import dev.blockscraper.mod.BlockScraper;
import dev.blockscraper.mod.BlockScraperPlugin;

/** Registers the sample screen with block-scraper. Copy this pattern into your mod. */
public final class SampleScreens implements BlockScraperPlugin {
	@Override
	public void registerScreens() {
		// block-scraper open sample-config --arg name=Steve
		BlockScraper.register("sample-config", args -> new SampleConfigScreen(args.parent(), args.get("name", "")));
	}
}
