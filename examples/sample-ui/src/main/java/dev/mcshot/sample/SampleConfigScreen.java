package dev.mcshot.sample;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A small config screen: a text field, a toggle with a tooltip, a counter button and Done. */
public class SampleConfigScreen extends Screen {
	private final Screen parent;
	private final String initialName;
	private int clicks;
	private boolean sparkles = true;

	public SampleConfigScreen(Screen parent, String initialName) {
		super(Component.translatable("mcshot-sample-ui.config.title"));
		this.parent = parent;
		this.initialName = initialName;
	}

	@Override
	protected void init() {
		int x = width / 2 - 100;
		int y = height / 2 - 50;

		EditBox name = new EditBox(font, x, y, 200, 20, Component.translatable("mcshot-sample-ui.config.name"));
		name.setValue(initialName);
		name.setHint(Component.translatable("mcshot-sample-ui.config.name"));
		addRenderableWidget(name);

		addRenderableWidget(CycleButton.onOffBuilder(sparkles)
				.withTooltip(value -> Tooltip.create(Component.translatable("mcshot-sample-ui.config.sparkles.tooltip")))
				.create(x, y + 25, 200, 20, Component.translatable("mcshot-sample-ui.config.sparkles", ""),
						(button, value) -> sparkles = value));

		addRenderableWidget(Button.builder(Component.translatable("mcshot-sample-ui.config.clicks", clicks), button -> {
			clicks++;
			button.setMessage(Component.translatable("mcshot-sample-ui.config.clicks", clicks));
		}).bounds(x, y + 50, 200, 20).build());

		addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
				.bounds(x, y + 85, 200, 20).build());
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);
		// Accent colour (ARGB). Change it and run `mcshot reload` to see hot reload work.
		int accent = 0xFF3FA9F5;
		int x = width / 2 - 110;
		int y = height / 2 - 75;
		graphics.renderOutline(x, y, 220, 170, accent);
		graphics.drawCenteredString(font, title, width / 2, y + 8, accent);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
