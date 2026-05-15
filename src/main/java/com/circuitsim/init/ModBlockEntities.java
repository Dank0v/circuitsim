package com.circuitsim.init;

import com.circuitsim.CircuitSimMod;
import com.circuitsim.blockentity.ComponentBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, CircuitSimMod.MODID);

    public static final RegistryObject<BlockEntityType<ComponentBlockEntity>> COMPONENT_BE =
            BLOCK_ENTITIES.register("component_be", () ->
                    BlockEntityType.Builder.of(ComponentBlockEntity::new,
                            ModBlocks.RESISTOR.get(),
                            ModBlocks.CAPACITOR.get(),
                            ModBlocks.INDUCTOR.get(),
                            ModBlocks.VOLTAGE_SOURCE.get(),
                            ModBlocks.VOLTAGE_SOURCE_SIN.get(),
                            ModBlocks.CURRENT_SOURCE.get(),
                            ModBlocks.DIODE.get(),
                            ModBlocks.WIRE.get(),
                            ModBlocks.GROUND.get(),
                            ModBlocks.PROBE.get(),
                            ModBlocks.CURRENT_PROBE.get(),
                            ModBlocks.SIMULATE.get(),
                            ModBlocks.PARAMETRIC.get(),
                            ModBlocks.COMMANDS.get(),
                            ModBlocks.IC_RESISTOR.get(),
                            ModBlocks.IC_CAPACITOR.get(),
                            ModBlocks.IC_NMOS4.get(),
                            ModBlocks.IC_PMOS4.get()
                    ).build(null)
            );

    public static void register(IEventBus eventBus) {
        BLOCK_ENTITIES.register(eventBus);
    }
}