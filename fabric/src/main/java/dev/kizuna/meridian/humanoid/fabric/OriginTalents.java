package dev.kizuna.meridian.humanoid.fabric;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.kizuna.meridian.humanoid.server.OriginVolumes;
import dev.kizuna.meridian.origin.fabric.OriginEntrypoint;
import dev.kizuna.meridian.origin.fabric.OriginRegistrar;
import dev.kizuna.meridian.origin.fabric.OriginTalentType;
import dev.kizuna.meridian.origin.fabric.Origins;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 出身附属的登记入口：一次性天赋 {@code meridian_humanoid:starting_volume}（选定出身时学会一卷功法），
 * 常驻天赋 {@code meridian_humanoid:villager_standing}（每个村民对这名玩家的声望多出一截，买卖跟着便宜）。
 * 开局卷放在这里而不放功法库：出身附属要 Human 或本附属的身体宿主才能初始化，功法库自己的测试装配里没有。
 */
public final class OriginTalents implements OriginEntrypoint {
    /** 声望天赋的上下限：原版闲话声望到 -100 铁傀儡就动手，出身不该一笔把人推到那一步。 */
    static final int MAX_REPUTATION = 100;
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid/origin");
    static final Codec<StartingVolume> VOLUME = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("volume").forGetter(StartingVolume::volume))
            .apply(instance, StartingVolume::new));
    private static final Codec<Integer> REPUTATION = Codec.intRange(-MAX_REPUTATION, MAX_REPUTATION);
    static final Codec<VillagerStanding> STANDING = RecordCodecBuilder.create(instance -> instance.group(
            REPUTATION.fieldOf("reputation").forGetter(VillagerStanding::reputation))
            .apply(instance, VillagerStanding::new));
    /** 开局卷这一类。 */
    public static final OriginTalentType<StartingVolume> STARTING_VOLUME = OriginTalentType.oneShot(
            Identifier.parse("meridian_humanoid:starting_volume"), VOLUME, OriginTalents::learn);
    /** 村民声望这一类。 */
    public static final OriginTalentType<VillagerStanding> VILLAGER_STANDING = OriginTalentType.standing(
            Identifier.parse("meridian_humanoid:villager_standing"), STANDING);

    @Override
    public void register(OriginRegistrar registrar) {
        // 出身附属读数据包前收齐各附属的类型。
        registrar.talent(STARTING_VOLUME);
        registrar.talent(VILLAGER_STANDING);
    }

    static void initialize() {
        // 开服在第一个 tick 核对（和学卷表一起）；/reload 出身附属重读完再核对一次，不对只记错误，出身已经换成新的了。
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register(CrisisFabric.AFTER_ORIGINS,
                (server, resources, success) -> reloaded(success));
    }

    private static void reloaded(boolean success) {
        // 重载没成就还是旧出身，不用再核对。
        if (!success) {
            return;
        }
        try {
            requireVolumes();
        } catch (RuntimeException invalid) {
            LOGGER.error("重载后的出身开局卷不对，选了这些出身的玩家学不到：{}", invalid.getMessage(), invalid);
        }
    }

    static void requireVolumes() {
        // 每个出身的开局卷都要已装、前置卷写在前面；没有开局卷的出身也列上，核对时跳过。
        var registry = TechniqueFabric.service().registry();
        Map<String, List<String>> volumes = new LinkedHashMap<>();
        Origins.all().forEach((id, origin) -> volumes.put(id.toString(),
                origin.talents(STARTING_VOLUME).stream().map(StartingVolume::volume).toList()));
        OriginVolumes.require(volumes, volume -> registry.findVolume(volume).isPresent(),
                volume -> registry.findVolume(volume).orElseThrow().getPrerequisiteVolumeIdsList());
    }

    static void learn(ServerPlayer player, StartingVolume params) {
        // 走功法服务的正式学卷，已经会了就不变；身体宿主不在、前置卷没会时抛，出身附属下次进服再发。
        TechniqueFabric.service().learn(player.getUUID(), params.volume());
    }

    /** 出身给这名玩家的村民声望，几条相加；没选出身是 0。读的是出身附属的内存缓存。 */
    public static int reputation(ServerPlayer player) {
        return Origins.talents(player, VILLAGER_STANDING).stream().mapToInt(VillagerStanding::reputation).sum();
    }

    /** 出身 JSON 里开局卷这一条的参数：一卷功法的完整 ID。 */
    public record StartingVolume(String volume) {
        // 只有卷 ID，装没装开服时核对。
    }

    /** 出身 JSON 里声望这一条的参数：加在原版闲话声望上的数。 */
    public record VillagerStanding(int reputation) {
        // 只有一个数，可正可负。
    }
}
