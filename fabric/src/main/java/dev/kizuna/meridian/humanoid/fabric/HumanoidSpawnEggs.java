package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidVariant;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 类人专用创造页与十四个分档刷怪蛋。 */
public final class HumanoidSpawnEggs {
    public static final String NAMESPACE = "meridian_humanoid";
    public static final Identifier GROUP_ID = new Identifier(NAMESPACE, "humanoids");
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
                var id = new Identifier(NAMESPACE, selection.path());
                var item = new HumanoidSpawnEggItem(selection);
                Registry.register(Registries.ITEM, id, item);
                registered.put(id, item);
            }
        }
        eggs = Collections.unmodifiableMap(registered);
        var icon = new HumanoidSpawns.Selection(HumanoidSpecies.VINDICATOR, HumanoidVariant.TRUE_QI);
        var iconItem = eggs.get(new Identifier(NAMESPACE, icon.path()));
        ItemGroup group = FabricItemGroup.builder()
                .displayName(Text.translatable("itemGroup.meridian_humanoid.humanoids"))
                .icon(() -> new ItemStack(iconItem))
                .entries((context, entries) -> eggs.values().forEach(entries::add))
                .build();
        Registry.register(Registries.ITEM_GROUP, GROUP_ID, group);
    }

    public static Map<Identifier, HumanoidSpawnEggItem> eggs() {
        // 注册表和客户端使用同一组物品，不重复维护蛋清单。
        return eggs;
    }
}
