package com.invertedworld;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import org.lwjgl.glfw.GLFW;

public class ClientSetup {
    public static KeyMapping OPEN_GUI_KEY;
    private static boolean openRequestedNextTick = false;

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(ClientSetup::onRegisterKeyMappings);
        MinecraftForge.EVENT_BUS.addListener(ClientSetup::onClientTick);

        // Register the "Config" button in Forge's Mods menu!
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parentScreen) -> new InvertedWorldScreen(parentScreen))
        );
    }

    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        OPEN_GUI_KEY = new KeyMapping(
                "Открыть меню шансов (Inverted World)",
                KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_I,
                "Inverted World"
        );
        event.register(OPEN_GUI_KEY);
    }

    public static void openScreenDelayed() {
        openRequestedNextTick = true;
    }

    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (openRequestedNextTick) {
            openRequestedNextTick = false;
            mc.setScreen(new InvertedWorldScreen(null));
            return;
        }

        if (OPEN_GUI_KEY != null && OPEN_GUI_KEY.consumeClick()) {
            mc.setScreen(new InvertedWorldScreen(mc.screen));
        }
    }
}
