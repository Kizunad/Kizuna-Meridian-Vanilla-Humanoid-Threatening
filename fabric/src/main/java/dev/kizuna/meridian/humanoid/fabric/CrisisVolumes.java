package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.CrisisRules;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItem;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueVolume;
import dev.kizuna.meridian.technique.server.TechniqueService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.world.item.ItemStack;

/** 招敌和功法库打交道的地方：谁会哪几卷、某卷的卷轴、某卷稀不稀有。 */
final class CrisisVolumes {
    private CrisisVolumes() {
        // 只有静态查询。
    }

    static List<String> known(UUID entity) {
        // 现查这只（或这名玩家）会哪几卷。
        return covered(TechniqueFabric.service().known(entity));
    }

    static List<String> covered(List<TechniqueService.KnownEntry> known) {
        // 一卷里的每一式都在已学列表里才算会这卷；按注册表的顺序。
        Set<TechniqueReference> techniques = known.stream().map(TechniqueService.KnownEntry::technique)
                .collect(Collectors.toSet());
        return TechniqueFabric.service().registry().volumes().stream()
                .filter(volume -> !volume.getTechniquesList().isEmpty()
                        && techniques.containsAll(volume.getTechniquesList()))
                .map(TechniqueVolume::getId)
                .toList();
    }

    static Optional<ItemStack> scroll(String volume) {
        // 这卷的卷轴，一卷；没有卷轴物品的卷为空。
        return TechniqueScrollItems.scrolls().values().stream()
                .filter(item -> item.scroll().volume().getId().equals(volume))
                .findFirst()
                .map(ItemStack::new);
    }

    static Optional<String> volumeOf(ItemStack stack) {
        // 这摞是哪卷的卷轴；不是卷轴为空。
        if (stack.getItem() instanceof TechniqueScrollItem item) {
            return Optional.of(item.scroll().volume().getId());
        }
        return Optional.empty();
    }

    static boolean rare(String volume, CrisisRules rules) {
        // 学卷表里每一处授这卷的概率都不超过 rare_chance。
        return HumanoidBrain.config().volumes().rare(volume, rules.heat().rise().rareChance());
    }
}
