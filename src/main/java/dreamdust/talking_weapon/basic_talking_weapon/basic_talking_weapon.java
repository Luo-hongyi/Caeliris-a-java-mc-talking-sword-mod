package dreamdust.talking_weapon.basic_talking_weapon;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger.TickEventWatcher;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfigScreen;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod("talking_weapon")
public class basic_talking_weapon {

        public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS,
                        "talking_weapon");

        public static final RegistryObject<Item> TALKING_SWORD = ITEMS.register("talking_sword", TalkingSword::new);

        public basic_talking_weapon() {
                IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

                ITEMS.register(modEventBus);
                TalkingWeaponConfig.register();

                // 注册 Mod 列表中的「配置」按钮 → 打开自定义界面
                ModLoadingContext.get().registerExtensionPoint(
                                ConfigScreenHandler.ConfigScreenFactory.class,
                                () -> new ConfigScreenHandler.ConfigScreenFactory(
                                                (mc, parent) -> new TalkingWeaponConfigScreen(parent)));

                MinecraftForge.EVENT_BUS.register(this);
                MinecraftForge.EVENT_BUS.register(new ClientEventHandler());
                MinecraftForge.EVENT_BUS.register(new TickEventWatcher());

                // KeyMapping 必须在 mod event bus 上注册
                modEventBus.addListener(ClientEventHandler::onRegisterKeys);
        }
}
