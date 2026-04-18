package com.circuitsim.client;
import com.circuitsim.CircuitSimMod;
import com.circuitsim.init.ModMenuTypes;
import com.circuitsim.screen.ComponentEditScreen;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
@Mod.EventBusSubscriber(modid = CircuitSimMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientSetup {
    private static BlockPos lastInteractedPos = BlockPos.ZERO;
    public static void setLastInteractedPos(BlockPos pos) {
        lastInteractedPos = pos.immutable();
    }
    public static BlockPos getLastInteractedPos() {
        return lastInteractedPos;
    }
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(ModMenuTypes.COMPONENT_EDIT.get(), ComponentEditScreen::new);
        });
    }
}