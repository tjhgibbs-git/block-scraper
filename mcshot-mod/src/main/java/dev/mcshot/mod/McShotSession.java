package dev.mcshot.mod;

import java.io.IOException;

import com.google.gson.JsonElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.NarratorStatus;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import dev.mcshot.core.CommandQueue;
import dev.mcshot.core.CommandServer;

/**
 * The whole mcshot session is one long client gametest. Fabric's gametest framework pauses the game while the test
 * thread works and gives us deterministic screenshots, input, window resizing and throwaway worlds, so most of mcshot
 * is maintained by Fabric API rather than by us.
 *
 * <p>Fabric only calls this when the JVM has {@code -Dfabric.client.gametest}; mcshot additionally requires
 * {@code -Dmcshot.enabled=true}. Without both the mod never opens a port and never touches the game.
 */
public final class McShotSession implements FabricClientGameTest {
	static final Logger LOGGER = LoggerFactory.getLogger("mcshot");

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!Boolean.getBoolean("mcshot.enabled")) {
			LOGGER.info("mcshot installed but not enabled (-Dmcshot.enabled=true); doing nothing");
			return;
		}
		int port = Integer.getInteger("mcshot.port", CommandServer.DEFAULT_PORT);
		boolean idleTick = Boolean.getBoolean("mcshot.idleTick");

		ScreenRegistry.loadPlugins();
		makeDeterministic(context);
		Commands commands = new Commands(context);
		commands.applyInitialSize(System.getProperty("mcshot.size", "1280x720"));

		CommandQueue queue = new CommandQueue();
		try (CommandServer server = new CommandServer(port, queue, LOGGER::warn)) {
			server.start();
			LOGGER.info("mcshot listening on 127.0.0.1:{}", server.port());
			while (true) {
				CommandQueue.Pending pending = queue.poll(idleTick ? 50 : 250);
				if (pending == null) {
					if (idleTick) {
						context.waitTick(); // keeps a visible window responsive; costs determinism between commands
					}
					continue;
				}
				try {
					JsonElement result = commands.run(pending.request());
					pending.result().complete(result);
				} catch (Throwable t) {
					if (!(t instanceof dev.mcshot.core.CommandException)) {
						LOGGER.error("mcshot command '{}' failed", pending.request().cmd(), t);
					}
					pending.result().completeExceptionally(t);
				}
				if (commands.quitRequested()) {
					break;
				}
			}
			queue.failAll("mcshot is shutting down");
		} catch (IOException e) {
			throw new IllegalStateException("mcshot could not listen on 127.0.0.1:" + port + " (another client running?)", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			commands.cleanUp();
		}
	}

	/** Settings that make shots repeatable. The gametest API already disables tutorials, clouds and onboarding. */
	private static void makeDeterministic(ClientGameTestContext context) {
		context.runOnClient(client -> {
			client.options.pauseOnLostFocus = false;
			client.options.narrator().set(NarratorStatus.OFF);
			client.options.hideSplashTexts().set(true);
			client.options.realmsNotifications().set(false);
			client.gui.toastManager().clear();
		});
	}
}
