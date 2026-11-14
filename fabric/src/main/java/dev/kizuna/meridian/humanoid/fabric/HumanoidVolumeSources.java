package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import dev.kizuna.meridian.humanoid.server.VolumeTable;
import dev.kizuna.meridian.technique.fabric.TechniqueVolumeSources;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.VolumeKnower;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.VolumeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import net.minecraft.resources.Identifier;

/** 把学卷表登记成功法谱的来源暗示：哪种类人到哪一档会哪一卷、多少比例会。 */
final class HumanoidVolumeSources {
    static final Identifier ID = Identifier.fromNamespaceAndPath("meridian_humanoid_host", "volume_table");

    private HumanoidVolumeSources() {
        // 只有静态入口。
    }

    static void register() {
        // 学卷表在初始化期已加载；每次汇总都读当前那份，换表后调 TechniqueVolumeSources.refresh 即可。
        TechniqueVolumeSources.register(ID, server -> sources(HumanoidBrain.config().volumes()));
    }

    static List<VolumeSource> sources(VolumeTable table) {
        // 物种按 ID 排序、档位按游炁到真炁，表内卷序不变；凡人档没有境界可说，概率为零的卷不会出现，都不登记。
        var sources = new ArrayList<VolumeSource>();
        new TreeMap<>(table.species()).forEach((entityId, tiers) -> {
            for (HumanoidTier tier : List.of(HumanoidTier.WANDERING_QI, HumanoidTier.TRUE_QI)) {
                tiers.getOrDefault(tier, List.of()).stream()
                        .filter(grant -> grant.chance() > 0)
                        .forEach(grant -> sources.add(source(entityId, tier, grant)));
            }
        });
        return List.copyOf(sources);
    }

    private static VolumeSource source(String entityId, HumanoidTier tier, VolumeTable.Grant grant) {
        // 档位换成协议里的境界（同名枚举），学到的概率就是这一档里会这卷的比例。
        var knower = VolumeKnower.newBuilder()
                .setEntityType(entityId)
                .setRealm(Realm.valueOf(tier.variant().realm().name()))
                .setShare(grant.chance());
        return VolumeSource.newBuilder().setVolumeId(grant.volume()).setKnower(knower).build();
    }
}
