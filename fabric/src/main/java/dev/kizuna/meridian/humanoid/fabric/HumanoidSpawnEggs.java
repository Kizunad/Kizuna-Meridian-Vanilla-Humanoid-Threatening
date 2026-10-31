package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidVariant;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 类人专用创造页与十四个分档刷怪蛋。 */
public final class HumanoidSpawnEggs {
    public static final String NAMESPACE = "meridian_humanoid";
    public static final Identifier GROUP_ID = Identifier.fromNamespaceAndPath(NAMESPACE, "humanoids");
    private static Map<Identifier, HumanoidSpawnEggItem> eggs = Map.of();

    private HumanoidSpawnEggs() {
        // 注册仅发生在两端公共初始化期间，目录不随世界切换变化。
    }

    static void register() {
        // 物种、档位枚举给出稳定顺序，原版物品注册表负责身份冲突校验。
        var registered = new LinkedHashMap<Identifier, HumanoidSpawnEggItem>();
        for (HumanoidSpecies species : HumanoidSpecies.values()) {
            for (HumanoidVariant variant : HumanoidVariant.values()) {
                var selection = new HumanoidSpawns.Selection(species, variant);
                var id = Identifier.fromNamespaceAndPath(NAMESPACE, selection.path());
                var item = new HumanoidSpawnEggItem(id, selection);
                Registry.register(BuiltInRegistries.ITEM, id, item);
                registered.put(id, item);
            }
        }
        eggs = Collections.unmodifiableMap(registered);
        var icon = new HumanoidSpawns.Selection(HumanoidSpecies.VINDICATOR, HumanoidVariant.TRUE_QI);
        var iconItem = eggs.get(Identifier.fromNamespaceAndPath(NAMESPACE, icon.path()));
        CreativeModeTab group = FabricCreativeModeTab.builder()
                .title(Component.translatable("itemGroup.meridian_humanoid.humanoids"))
                .icon(() -> new ItemStack(iconItem))
                .displayItems((context, entries) -> eggs.values().forEach(entries::accept))
                .build();
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, GROUP_ID, group);
    }

    public static Map<Identifier, HumanoidSpawnEggItem> eggs() {
        // 注册表和测试使用同一组物品，不重复维护蛋清单。
        return eggs;
    }
}
