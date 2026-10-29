package dev.mcshot.sample;

import dev.mcshot.mod.McShot;
import dev.mcshot.mod.McShotPlugin;

/** Registers the sample screen with mcshot. Copy this pattern into your mod. */
public final class SampleScreens implements McShotPlugin {
	@Override
	public void registerScreens() {
		// mcshot open sample-config --arg name=Steve
		McShot.register("sample-config", args -> new SampleConfigScreen(args.parent(), args.get("name", "")));
	}
}
