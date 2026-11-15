package dev.kizuna.meridian.humanoid.fabric;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.kizuna.meridian.humanoid.server.CrisisParty;
import java.io.IOException;
import java.io.Reader;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;

/**
 * 数据包里的全部队伍表：{@code data/<命名空间>/meridian_humanoid/crisis_party/<名字>.json}，ID 是「命名空间:名字」。
 * 格式见 {@link CrisisCodecs#PARTY}；一份不对就整批不收，报错里带它的文件 ID。
 */
final class CrisisParties {
    private static final String DIRECTORY = "meridian_humanoid/crisis_party";
    private static final String JSON_SUFFIX = ".json";

    private CrisisParties() {
        // 只读数据包，不持有状态；收下的那一份由 CrisisFabric 管。
    }

    static Map<String, CrisisParty> load(MinecraftServer server) {
        // 逐份解析，按 ID 排序，指令和日志的顺序固定。
        Map<Identifier, Resource> files = server.getResourceManager().listResources(DIRECTORY,
                id -> id.getPath().endsWith(JSON_SUFFIX));
        Map<String, CrisisParty> parties = new TreeMap<>();
        for (Map.Entry<Identifier, Resource> file : files.entrySet()) {
            String id = partyId(file.getKey()).toString();
            try (Reader reader = file.getValue().openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                parties.put(id, CrisisCodecs.PARTY.parse(JsonOps.INSTANCE, json)
                        .getOrThrow(IllegalArgumentException::new));
            } catch (IOException | RuntimeException invalid) {
                throw new IllegalArgumentException("队伍表 " + id + "（" + file.getKey() + "）：" + invalid.getMessage(),
                        invalid);
            }
        }
        return Collections.unmodifiableMap(parties);
    }

    private static Identifier partyId(Identifier file) {
        // meridian_humanoid/crisis_party/<名字>.json → <名字>。
        String path = file.getPath();
        String name = path.substring(DIRECTORY.length() + 1, path.length() - JSON_SUFFIX.length());
        return Identifier.fromNamespaceAndPath(file.getNamespace(), name);
    }
}
