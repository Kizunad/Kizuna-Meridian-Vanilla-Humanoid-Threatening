package dev.kizuna.meridian.humanoid.fabric.client;

import dev.kizuna.meridian.humanoid.fabric.HumanoidSpawnEggs;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidVariant;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.SpawnEggItem;

/** 沿用原版蛋的双色遮罩，真炁档混入白色以便创造栏辨识。 */
public final class HumanoidSpawnEggClient implements ClientModInitializer {
    private static final int EVEN_CHANNEL_MASK = 0xFEFEFE;
    private static final int LIGHTEN_CHANNELS = 0x808080;

    @Override
    public void onInitializeClient() {
        // 幻术师没有原版蛋，沿用同属施法灾厄村民的唤魔者配色。
        for (var item : HumanoidSpawnEggs.eggs().values()) {
            var selection = item.selection();
            EntityType<?> paletteType = selection.entityType();
            if (selection.species() == HumanoidSpecies.ILLUSIONER) {
                paletteType = EntityType.EVOKER;
            }
            SpawnEggItem palette = SpawnEggItem.byId(paletteType);
            ColorProviderRegistry.ITEM.register((stack, tint) -> {
                // 两层都使用相同明度规则，保持原版底色与斑点关系。
                int base = palette.getColor(tint);
                if (selection.variant() == HumanoidVariant.TRUE_QI) {
                    return ((base & EVEN_CHANNEL_MASK) >>> 1) + LIGHTEN_CHANNELS;
                }
                return base;
            }, item);
        }
    }
}
