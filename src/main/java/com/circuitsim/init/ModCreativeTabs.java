package com.circuitsim.init;

import com.circuitsim.CircuitSimMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CircuitSimMod.MODID);

    public static final RegistryObject<CreativeModeTab> CIRCUIT_SIM_TAB =
            CREATIVE_MODE_TABS.register("circuitsim_tab", () ->
                    CreativeModeTab.builder()
                            .title(Component.literal("Circuit Simulator"))
                            .icon(() -> new ItemStack(ModItems.SIMULATE.get()))
                            .displayItems((parameters, output) -> {
                                output.accept(ModItems.RESISTOR.get());
                                output.accept(ModItems.CAPACITOR.get());
                                output.accept(ModItems.INDUCTOR.get());
                                output.accept(ModItems.VOLTAGE_SOURCE.get());
                                output.accept(ModItems.VOLTAGE_SOURCE_SIN.get());
                                output.accept(ModItems.CURRENT_SOURCE.get());
                                output.accept(ModItems.DIODE.get());
                                output.accept(ModItems.WIRE.get());
                                output.accept(ModItems.GROUND.get());
                                output.accept(ModItems.PROBE.get());
                                output.accept(ModItems.CURRENT_PROBE.get());
                                output.accept(ModItems.SIMULATE.get());
                                output.accept(ModItems.PARAMETRIC.get());
                                output.accept(ModItems.COMMANDS.get());
                                output.accept(ModItems.IC_RESISTOR.get());
                                output.accept(ModItems.IC_CAPACITOR.get());
                                output.accept(ModItems.IC_NMOS4.get());
                                output.accept(ModItems.IC_PMOS4.get());
                            })
                            .build()
            );

    public static void register(IEventBus eventBus) {
        CREATIVE_MODE_TABS.register(eventBus);
    }
}