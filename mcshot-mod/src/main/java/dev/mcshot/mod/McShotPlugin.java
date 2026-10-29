package dev.mcshot.mod;

/**
 * The {@code "mcshot"} entrypoint. List your implementation in {@code fabric.mod.json}:
 *
 * <pre>{@code "entrypoints": { "mcshot": ["com.example.MyMcShotScreens"] }}</pre>
 *
 * <p>It is only loaded when mcshot is running, so it is a safe place for dev-only screen registrations.
 */
@FunctionalInterface
public interface McShotPlugin {
	void registerScreens();
}
