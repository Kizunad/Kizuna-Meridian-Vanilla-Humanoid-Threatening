package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.CrisisFeuds;
import dev.kizuna.meridian.humanoid.server.CrisisThreat;
import dev.kizuna.meridian.origin.fabric.Origin;
import dev.kizuna.meridian.origin.fabric.OriginEntrypoint;
import dev.kizuna.meridian.origin.fabric.OriginRegistrar;
import dev.kizuna.meridian.origin.fabric.OriginThreatType;
import dev.kizuna.meridian.origin.fabric.Origins;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.resources.Identifier;

/**
 * 出身附属的登记入口：登记威胁类型 {@code meridian_humanoid:crisis}，出身 JSON 里写了它就是这个出身的仇家。
 * 参数照任务单「第 2 段接口草稿」第六条定死，解析器在 {@link CrisisCodecs#THREAT}。
 */
public final class CrisisOrigins implements OriginEntrypoint {
    static final OriginThreatType<CrisisThreat> CRISIS = new OriginThreatType<>(
            Identifier.parse("meridian_humanoid:crisis"), CrisisCodecs.THREAT);

    @Override
    public void register(OriginRegistrar registrar) {
        // 出身附属在读数据包前收齐各附属的类型。
        registrar.threat(CRISIS);
    }

    static List<CrisisFeuds.Entry> entries(Origin origin) {
        // 出身里每条 crisis 一条仇家，按写的先后，键是「出身 ID#下标」。
        List<CrisisThreat> threats = origin.threats(CRISIS);
        List<CrisisFeuds.Entry> entries = new ArrayList<>(threats.size());
        for (int index = 0; index < threats.size(); index++) {
            entries.add(new CrisisFeuds.Entry(CrisisFeuds.key(origin.id().toString(), index), threats.get(index)));
        }
        return List.copyOf(entries);
    }

    static Map<String, List<CrisisThreat>> byOrigin() {
        // 数据包里所有带仇家的出身，启动后核对队伍表和卷用。
        return Origins.threatsByOrigin(CRISIS).entrySet().stream().collect(Collectors.toMap(
                entry -> entry.getKey().toString(), Map.Entry::getValue, (left, right) -> left, LinkedHashMap::new));
    }
}
