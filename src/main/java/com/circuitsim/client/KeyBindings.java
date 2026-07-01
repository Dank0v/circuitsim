package com.circuitsim.client;

import com.circuitsim.CircuitSimMod;
import com.circuitsim.screen.AnnotateOpScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(
    modid = CircuitSimMod.MODID,
    bus = Mod.EventBusSubscriber.Bus.FORGE,
    value = Dist.CLIENT
)
public class KeyBindings {

    public static final KeyMapping TOGGLE_LABELS = new KeyMapping(
        "key.circuitsim.toggle_labels",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_L,
        "key.categories.circuitsim"
    );

    public static final KeyMapping ANNOTATE_OP = new KeyMapping(
        "key.circuitsim.annotate_op",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_K,
        "key.categories.circuitsim"
    );

    public static boolean labelsVisible = true;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();

        while (TOGGLE_LABELS.consumeClick()) {
            labelsVisible = !labelsVisible;
            if (mc.player != null) {
                mc.player.displayClientMessage(
                    Component.literal("CircuitSim labels: " + (labelsVisible ? "on" : "off")),
                    true
                );
            }
        }

        while (ANNOTATE_OP.consumeClick()) {
            // Only meaningful right after an .OP run that produced device data.
            if (mc.screen != null) continue;          // don't steal focus from a GUI
            if (ClientOpData.hasData()) {
                mc.setScreen(new AnnotateOpScreen());
            } else if (mc.player != null) {
                mc.player.displayClientMessage(
                    Component.literal("Run an .OP simulation first to annotate operating points."),
                    true
                );
            }
        }
    }
}
