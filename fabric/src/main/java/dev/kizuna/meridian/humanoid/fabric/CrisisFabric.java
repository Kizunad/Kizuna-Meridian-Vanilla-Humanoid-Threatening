package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.CrisisParty;
import dev.kizuna.meridian.humanoid.server.CrisisReferences;
import dev.kizuna.meridian.humanoid.server.CrisisRules;
import dev.kizuna.meridian.humanoid.server.CrisisThreat;
import dev.kizuna.meridian.origin.fabric.Origins;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.TechniqueVolumeSources;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 修为招敌的接线：启动时读配置、取身体宿主；开服读队伍表，第一个 tick 核对队伍表、出身仇家和卷；
 * 挂上 tick、出身选定和进服、生物死亡、实体加载、学会功法、下线、停服，登记指令。玩家死前走 mixin。
 */
final class CrisisFabric {
    static final String CONFIG_FILE = "meridian-humanoid-crisis.toml";
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid/crisis");
    private static CrisisEngine engine;
    private static Map<String, CrisisParty> parties = Map.of();
    static final Identifier AFTER_ORIGINS =
            Identifier.fromNamespaceAndPath("meridian_humanoid", "after_origins");
    private static boolean checked;

    private CrisisFabric() {
        // 只有静态接线。
    }

    static void initialize(Path configDirectory) {
        // 配置坏了启动失败；进度触发器在注册表冻结前登记。
        // 重载排在默认阶段之后，出身附属先重读完出身，这边再核对、刷新功法谱的仇家来源。
        engine = new CrisisEngine(CrisisRules.load(configDirectory.resolve(CONFIG_FILE)), new CrisisBodies());
        CrisisTrigger.register();
        CrisisVolumeSources.register();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> parties = CrisisParties.load(server));
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER_ORIGINS);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register(AFTER_ORIGINS, (server, resources, success) -> {
            if (success) {
                reload(server);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(CrisisFabric::tick);
        Origins.CHOSEN.register((player, origin) -> engine.chosen(player, origin));
        Origins.READY.register((player, origin) -> engine.ready(player, origin));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> engine.endings().died(entity, source));
        ServerEntityEvents.ENTITY_LOAD.register(CrisisRetreats::loaded);
        TechniqueFabric.KNOWN_CHANGED.register(engine::learned);
        // 玩家从在线名单移除前，在世界线程提交战果并卸载来人。
        ServerPlayerEvents.LEAVE.register(engine::disconnect);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> stop());
        CrisisCommands.register(engine);
    }

    private static void tick(MinecraftServer server) {
        // 功法服务在启动回调里才建好、出身在开服时才读完，所以放到第一个 tick 核对；不对直接失败。
        if (!checked) {
            requireReferences(parties, CrisisOrigins.byOrigin());
            checked = true;
        }
        engine.tick(server);
        CrisisRetreats.tick(server);
    }

    static void requireReferences(Map<String, CrisisParty> loaded, Map<String, List<CrisisThreat>> threatsByOrigin) {
        // 通用队伍表、出身仇家指向的队伍表和卷都要在（卷还要有卷轴，打赢最后一拨要掉），队伍表人数不超过上限。
        var registry = TechniqueFabric.service().registry();
        CrisisReferences.require(engine.rules(), loaded, threatsByOrigin,
                volume -> registry.findVolume(volume).isPresent() && CrisisVolumes.scroll(volume).isPresent());
    }

    private static void reload(MinecraftServer server) {
        // /reload：新队伍表不对就留着旧的，已经排上的那一场不会突然没了队伍表；收下以后在线玩家换一份功法谱来源。
        try {
            Map<String, CrisisParty> reloaded = CrisisParties.load(server);
            requireReferences(reloaded, CrisisOrigins.byOrigin());
            parties = reloaded;
            TechniqueVolumeSources.refresh(server);
        } catch (RuntimeException invalid) {
            LOGGER.error("招敌队伍表重载失败，沿用重载前的队伍表：{}", invalid.getMessage(), invalid);
        }
    }

    private static void stop() {
        // 停服时玩家都已下线写回，这里只清内存，下次开服重新读、重新核对。
        engine.clear();
        parties = Map.of();
        checked = false;
    }

    static CrisisEngine engine() {
        // GameTest 和后续各步从这里拿引擎。
        return engine;
    }

    static Map<String, CrisisParty> parties() {
        // 当前收下的队伍表，按 ID。
        return parties;
    }
}
