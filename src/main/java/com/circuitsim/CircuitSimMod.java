package com.circuitsim;

import com.circuitsim.init.ModBlockEntities;
import com.circuitsim.init.ModBlocks;
import com.circuitsim.init.ModCreativeTabs;
import com.circuitsim.init.ModItems;
import com.circuitsim.init.ModMenuTypes;
import com.circuitsim.network.ModMessages;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(CircuitSimMod.MODID)
public class CircuitSimMod {
    public static final String MODID = "circuitsim";
    public static final Logger LOGGER = LogManager.getLogger();

    public CircuitSimMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModMenuTypes.register(modEventBus);
        ModCreativeTabs.register(modEventBus);

        ModMessages.register();

        MinecraftForge.EVENT_BUS.register(this);
    }
}