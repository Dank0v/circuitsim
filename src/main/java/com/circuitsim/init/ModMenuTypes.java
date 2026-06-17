package com.circuitsim.init;

import com.circuitsim.CircuitSimMod;
import com.circuitsim.screen.ComponentEditMenu;
import com.circuitsim.screen.SubcircuitMenu;
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

    public static final RegistryObject<MenuType<SubcircuitMenu>> SUBCIRCUIT =
            MENU_TYPES.register("subcircuit",
                    () -> IForgeMenuType.create((id, inv, data) -> new SubcircuitMenu(id, inv, data)));

    public static void register(IEventBus eventBus) {
        MENU_TYPES.register(eventBus);
    }
}