package com.circuitsim.init;

import com.circuitsim.CircuitSimMod;
import com.circuitsim.screen.ComponentEditMenu;
import com.circuitsim.screen.ParametricEditMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModMenuTypes {

    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, CircuitSimMod.MODID);

    public static final RegistryObject<MenuType<ComponentEditMenu>> COMPONENT_EDIT =
            MENU_TYPES.register("component_edit",
                    () -> IForgeMenuType.create((id, inv, data) -> new ComponentEditMenu(id, inv)));

    public static final RegistryObject<MenuType<ParametricEditMenu>> PARAMETRIC_EDIT =
            MENU_TYPES.register("parametric_edit",
                    () -> IForgeMenuType.create((id, inv, data) -> new ParametricEditMenu(id, inv)));

    public static void register(IEventBus eventBus) {
        MENU_TYPES.register(eventBus);
    }
}