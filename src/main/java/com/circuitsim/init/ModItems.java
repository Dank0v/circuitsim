package com.circuitsim.init;

import com.circuitsim.CircuitSimMod;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, CircuitSimMod.MODID);

    public static final RegistryObject<Item> RESISTOR           = blockItem(ModBlocks.RESISTOR);
    public static final RegistryObject<Item> CAPACITOR          = blockItem(ModBlocks.CAPACITOR);
    public static final RegistryObject<Item> INDUCTOR           = blockItem(ModBlocks.INDUCTOR);
    public static final RegistryObject<Item> VOLTAGE_SOURCE     = blockItem(ModBlocks.VOLTAGE_SOURCE);
    public static final RegistryObject<Item> VOLTAGE_SOURCE_SIN = blockItem(ModBlocks.VOLTAGE_SOURCE_SIN);
    public static final RegistryObject<Item> CURRENT_SOURCE     = blockItem(ModBlocks.CURRENT_SOURCE);
    public static final RegistryObject<Item> DIODE              = blockItem(ModBlocks.DIODE);
    public static final RegistryObject<Item> WIRE               = blockItem(ModBlocks.WIRE);
    public static final RegistryObject<Item> GROUND             = blockItem(ModBlocks.GROUND);
    public static final RegistryObject<Item> PROBE              = blockItem(ModBlocks.PROBE);
    public static final RegistryObject<Item> CURRENT_PROBE      = blockItem(ModBlocks.CURRENT_PROBE);
    public static final RegistryObject<Item> SIMULATE           = blockItem(ModBlocks.SIMULATE);
    public static final RegistryObject<Item> PARAMETRIC         = blockItem(ModBlocks.PARAMETRIC);
    public static final RegistryObject<Item> COMMANDS           = blockItem(ModBlocks.COMMANDS);
    public static final RegistryObject<Item> IC_RESISTOR          = blockItem(ModBlocks.IC_RESISTOR);
    public static final RegistryObject<Item> IC_CAPACITOR         = blockItem(ModBlocks.IC_CAPACITOR);
    public static final RegistryObject<Item> IC_NMOS4             = blockItem(ModBlocks.IC_NMOS4);
    public static final RegistryObject<Item> IC_PMOS4             = blockItem(ModBlocks.IC_PMOS4);

    private static RegistryObject<Item> blockItem(RegistryObject<Block> block) {
        return ITEMS.register(block.getId().getPath(),
                () -> new BlockItem(block.get(), new Item.Properties()));
    }

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}