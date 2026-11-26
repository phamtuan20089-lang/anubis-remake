package net.anubis.client.gui;

import net.anubis.client.imgui.ImGuiManager;
import net.anubis.client.util.Platform;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/** Adds an "Anubis" button to the pause and title screens so touch players can open the menu without a keyboard. */
@Environment(EnvType.CLIENT)
public final class MobileMenuButtons {
    private MobileMenuButtons() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if ((Platform.isAndroid() || !ImGuiManager.getInstance().available()) && (screen instanceof PauseScreen || screen instanceof TitleScreen)) {
                Button button = Button.builder(Component.literal("Anubis"), b -> client.setScreen(GuiLauncher.create()))
                    .bounds(6, 6, 80, 20)
                    .build();
                Screens.getButtons(screen).add(button);
            }
        });
    }
}
