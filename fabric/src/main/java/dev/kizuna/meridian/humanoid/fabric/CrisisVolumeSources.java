package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.CrisisThreat;
import dev.kizuna.meridian.origin.fabric.Origins;
import dev.kizuna.meridian.technique.fabric.TechniqueVolumeSources;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.VolumeFoe;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.VolumeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;

/** 把出身仇家必掉的那一卷（{@code foe_volume}）登记成功法谱的来源暗示：「某出身的仇家身上有」。 */
final class CrisisVolumeSources {
    static final Identifier ID = Identifier.fromNamespaceAndPath("meridian_humanoid_host", "crisis_foes");

    private CrisisVolumeSources() {
        // 只有静态入口。
    }

    static void register() {
        // 每次汇总都读出身附属当前那份数据包，/reload 以后调 TechniqueVolumeSources.refresh 即可。
        TechniqueVolumeSources.register(ID, server -> sources(CrisisOrigins.byOrigin()));
    }

    static List<VolumeSource> sources(Map<String, List<CrisisThreat>> threatsByOrigin) {
        // 出身按 ID，出身里按仇家写的先后；同一出身里重复的卷只登记一次，没写 foe_volume 的仇家不登记。
        var sources = new ArrayList<VolumeSource>();
        threatsByOrigin.forEach((origin, threats) -> threats.stream()
                .flatMap(threat -> threat.foeVolume().stream())
                .distinct()
                .forEach(volume -> sources.add(source(origin, volume))));
        return List.copyOf(sources);
    }

    private static VolumeSource source(String origin, String volume) {
        // 功法谱那边按出身显示名的语言键拼句子。
        var foe = VolumeFoe.newBuilder().setOriginKey(Origins.nameKey(Identifier.parse(origin)));
        return VolumeSource.newBuilder().setVolumeId(volume).setFoe(foe).build();
    }
}
