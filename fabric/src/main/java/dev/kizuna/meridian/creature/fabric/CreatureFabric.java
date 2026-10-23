package dev.kizuna.meridian.creature.fabric;

import com.mojang.brigadier.context.CommandContext;
import dev.kizuna.meridian.creature.server.CreatureBodyGenerator;
import dev.kizuna.meridian.creature.server.CreatureConfig;
import dev.kizuna.meridian.creature.server.CreatureConfigLoader;
import dev.kizuna.meridian.creature.server.CreatureSpecies;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.protocol.pb.AddonMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.qivariants.protocol.pb.QiVariantsMessages;
import dev.kizuna.meridian.qivariants.server.QiVariantRegistry;
import dev.kizuna.meridian.qivariants.server.QiVariantsContent;
import dev.kizuna.meridian.server.AddonRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.util.math.Box;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 生物 Fabric 适配器；只管理实体索引、区域服务和快照存档。 */
public final class CreatureFabric implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_creature_host");
    private static final String STATE_ID = "meridian_creature";
    private static MinecraftServer activeServer;
    private static CreatureConfig config;
    private static AddonRegistry content;
    private static QiVariantRegistry variants;
    private static CreatureBodyGenerator generator;
    private static CreatureEnvironmentClient environment;
    private static CreatureWorldState worldState;
    private static long tickCounter;

    @Override
    public void onInitialize() {
        // 内容目录在服务端启动时固定；缺少 Human 或 Qi-Variants 登记会直接阻止接线。
        config = CreatureConfigLoader.load(FabricLoader.getInstance().getConfigDir()
                .resolve("meridian-creature.toml"));
        content = installContent();
        variants = QiVariantRegistry.load(content, QiVariantsContent.catalog());
        generator = new CreatureBodyGenerator(variants, config);
        environment = new CreatureEnvironmentClient();
        ServerLifecycleEvents.SERVER_STARTED.register(CreatureFabric::startServer);
        ServerTickEvents.END_SERVER_TICK.register(CreatureFabric::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(CreatureFabric::stopServer);
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> registerCommands(dispatcher));
    }

    private static void startServer(MinecraftServer server) {
        activeServer = server;
        tickCounter = 0;
        try {
            PersistentStateManager persistence = server.getOverworld().getPersistentStateManager();
            var file = server.getSavePath(WorldSavePath.ROOT).resolve("data/" + STATE_ID + ".dat");
            if (Files.exists(file)) {
                worldState = persistence.get(CreatureWorldState::read, STATE_ID);
                if (worldState == null) {
                    throw new IllegalArgumentException("生物释放账本为空");
                }
            } else {
                worldState = new CreatureWorldState();
                persistence.set(STATE_ID, worldState);
            }
        } catch (RuntimeException failure) {
            worldState = null;
            LOGGER.error("无法加载生物释放账本，生物宿主保持未绑定", failure);
        }
    }

    private static void stopServer(MinecraftServer server) {
        worldState = null;
        activeServer = null;
        tickCounter = 0;
    }

    private static void tick(MinecraftServer server) {
        if (server != activeServer || worldState == null) {
            return;
        }
        tickCounter++;
        // 释放调用必须在主线程进行；失败项留在世界账本中等待下一轮或重启。
        if (tickCounter % 20 == 0) {
            worldState.retry();
        }
        if (config.particleEnabled() && tickCounter % config.particleIntervalTicks() == 0) {
            emitParticles(server);
        }
    }

    static boolean isReady() {
        // 世界存在且释放账本、内容和环境入口都已装配时才允许健康附属查询。
        return activeServer != null && worldState != null && generator != null && environment != null;
    }

    static MinecraftServer server() {
        // 调用方先用 isReady 检查；空引用属于宿主装配错误。
        return activeServer;
    }

    static CreatureBodyGenerator generator() {
        // 生成器只读固定目录，实体查询不会重建随机配置。
        return generator;
    }

    static AddonRegistry content() {
        // 身体命令和生成器共用同一批已验证内容。
        return content;
    }

    static CreatureEnvironmentClient.RegionSnapshot region(LivingEntity entity) {
        // 区域库存和浓度必须来自 Human 服务；未绑定时不创建临时身体。
        return environment.query(entity);
    }

    static BodyMessages.TimeCursor now() {
        // 身体时间域直接沿用 Human 世界时钟，跨重启只读取同一 epoch。
        return environment.clockResponse().getNow();
    }

    static boolean release(dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionRelease release) {
        // Human 返回成功或 duplicate 都表示这笔释放已受理；所有其他结果保留待重试。
        try {
            return environment.release(release);
        } catch (CreatureEnvironmentClient.Unavailable failure) {
            LOGGER.debug("生物释放暂不可用 releaseId={}: {}", release.getReleaseId(), failure.getMessage());
            return false;
        }
    }

    static void enqueueRelease(Entity entity, dev.kizuna.meridian.core.QiInventory qi) {
        if (worldState == null || qi == null || qi.entries().isEmpty()) {
            return;
        }
        worldState.enqueue(entity.getUuid(), entity, qi);
    }

    static void enqueueRelease(Entity entity, BodyMessages.QiInventory qi) {
        if (worldState == null || qi == null || qi.getEntriesCount() == 0) {
            return;
        }
        worldState.enqueue(entity.getUuid(), entity, qi);
    }

    static LivingEntity findEntity(UUID id) {
        // 健康宿主只在主线程扫描已加载世界；卸载区块中的实体不会被凭空重建。
        if (activeServer == null || id == null) {
            return null;
        }
        for (var world : activeServer.getWorlds()) {
            Entity entity = world.getEntity(id);
            if (entity instanceof LivingEntity living && !living.isRemoved()) {
                return living;
            }
        }
        return null;
    }

    static boolean isClaimed(LivingEntity entity) {
        return CreatureSpecies.fromEntityId(net.minecraft.registry.Registries.ENTITY_TYPE
                .getId(entity.getType()).toString()).isPresent();
    }

    public static void onEntityRemoved(Entity entity, Entity.RemovalReason reason) {
        // 区块卸载和跨维度只移除运行时对象，身体仍由实体 NBT 承载；只有真正离开世界才释放。
        if (!(entity instanceof LivingEntity living) || !isClaimed(living)
                || reason == Entity.RemovalReason.UNLOADED_TO_CHUNK
                || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER
                || reason == Entity.RemovalReason.CHANGED_DIMENSION) {
            return;
        }
        if (!(living instanceof CreatureEntityAccess access) || access.meridian$creatureConversionPending()
                || access.meridian$creatureBody() == null
                || access.meridian$creatureReleaseQueued()) {
            return;
        }
        access.meridian$creatureReleaseQueued(true);
        try {
            // 有时原版移除先于 Human 服务可用；此时至少保留当前库存，服务恢复后由账本重试。
            try {
                BodyMessages.QiInventory released = access.meridian$creatureBody().settle(now());
                enqueueRelease(entity, released);
            } catch (CreatureEnvironmentClient.Unavailable unavailable) {
                LOGGER.debug("移除时无法结算生物时间 subject={}", entity.getUuidAsString());
            }
            enqueueRelease(entity, access.meridian$creatureBody().releaseInventory());
        } catch (RuntimeException failure) {
            LOGGER.error("生物移除释放失败 subject={}", entity.getUuidAsString(), failure);
        }
    }

    public static void migrateBody(LivingEntity oldEntity, LivingEntity newEntity) {
        // VillagerEntity.convertTo 会先复制原版字段；这里迁移同一身体对象，保持身体 ID、伤势和年龄。
        if (!(oldEntity instanceof CreatureEntityAccess oldAccess)
                || !(newEntity instanceof CreatureEntityAccess newAccess)) {
            return;
        }
        oldAccess.meridian$creatureConversionPending(false);
        if (oldAccess.meridian$creatureBody() != null) {
            newAccess.meridian$creatureBody(oldAccess.meridian$creatureBody());
            oldAccess.meridian$creatureBody(null);
            return;
        }
        byte[] saved = oldAccess.meridian$creatureSavedBody();
        if (saved != null && saved.length > 0) {
            newAccess.meridian$creatureSavedBody(saved, oldAccess.meridian$creatureSavedReceipts(),
                    oldAccess.meridian$creatureSavedRegionSource(), newEntity.getUuid());
            oldAccess.meridian$creatureSavedBody(null, new byte[0][], "");
        }
    }

    public static void markConversion(LivingEntity oldEntity, boolean pending) {
        if (oldEntity instanceof CreatureEntityAccess access) {
            access.meridian$creatureConversionPending(pending);
        }
    }

    static int colorFor(String attribute) {
        Integer configured = config.particleColors().get(attribute);
        if (configured != null) {
            return configured;
        }
        return variants.catalog().getAttributesList().stream()
                .filter(value -> value.getId().equals(attribute) && value.hasColorRgb())
                .mapToInt(QiVariantsMessages.VariantAttribute::getColorRgb).findFirst().orElse(0xB8B8B8);
    }

    static String mainAttribute(BodyMessages.BodySnapshot snapshot) {
        Map<String, Double> amounts = new HashMap<>();
        snapshot.getMeridianStatesList().forEach(state -> {
            state.getLumenQi().getEntriesList().forEach(qi -> amounts.merge(qi.getAttribute(), qi.getAmount(), Double::sum));
            state.getWallQi().getEntriesList().forEach(qi -> amounts.merge(qi.getAttribute(), qi.getAmount(), Double::sum));
        });
        snapshot.getOrganStatesList().forEach(state -> state.getBuffer().getEntriesList()
                .forEach(qi -> amounts.merge(qi.getAttribute(), qi.getAmount(), Double::sum)));
        return amounts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElse("meridian:none");
    }

    private static void emitParticles(MinecraftServer server) {
        for (var world : server.getWorlds()) {
            Box bounds = new Box(world.getWorldBorder().getBoundWest(), world.getBottomY(),
                    world.getWorldBorder().getBoundNorth(), world.getWorldBorder().getBoundEast(),
                    world.getTopY(), world.getWorldBorder().getBoundSouth());
            for (LivingEntity entity : world.getEntitiesByClass(LivingEntity.class, bounds,
                    CreatureFabric::hasTrueQiBody)) {
                var access = (CreatureEntityAccess) entity;
                int color = colorFor(mainAttribute(access.meridian$creatureBody().snapshot()));
                var rgb = new Vector3f(((color >> 16) & 0xFF) / 255f, ((color >> 8) & 0xFF) / 255f,
                        (color & 0xFF) / 255f);
                world.spawnParticles(new DustParticleEffect(rgb, 0.55f), entity.getX(),
                        entity.getBodyY(0.6), entity.getZ(), 1, .18, .25, .18, .01);
            }
        }
    }

    private static boolean hasTrueQiBody(LivingEntity entity) {
        return isClaimed(entity) && entity instanceof CreatureEntityAccess access
                && access.meridian$creatureBody() != null
                && access.meridian$creatureBody().snapshot().getRealm() == BodyMessages.Realm.TRUE_QI
                && !access.meridian$creatureBody().snapshot().getVitality().getDead();
    }

    private static void registerCommands(com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("meridian").then(CommandManager.literal("creature")
                .then(CommandManager.literal("inspect").requires(source -> source.hasPermissionLevel(2))
                        .executes(context -> inspect(context, context.getSource().getEntity()))
                        .then(CommandManager.argument("target", EntityArgumentType.entity())
                                .executes(context -> inspect(context,
                                        EntityArgumentType.getEntity(context, "target")))))));
    }

    private static int inspect(CommandContext<ServerCommandSource> context, Entity entity) {
        if (!(entity instanceof LivingEntity living) || !isClaimed(living)) {
            context.getSource().sendError(Text.literal("目标不是本批次生物"));
            return 0;
        }
        if (!(living instanceof CreatureEntityAccess access) || access.meridian$creatureBody() == null) {
            context.getSource().sendError(Text.literal("目标尚未创建身体"));
            return 0;
        }
        var snapshot = access.meridian$creatureBody().snapshot();
        var opened = snapshot.getMeridianStatesList().stream()
                .filter(state -> state.getOpening().getStage() == BodyMessages.MeridianOpeningStage.OPENED)
                .map(BodyMessages.MeridianState::getId).toList();
        double volume = snapshot.getMeridianStatesList().stream()
                .flatMap(state -> java.util.stream.Stream.concat(state.getLumenQi().getEntriesList().stream(),
                        state.getWallQi().getEntriesList().stream()))
                .mapToDouble(qi -> qi.getAmount() / qi.getDensity()).sum();
        double amount = snapshot.getMeridianStatesList().stream()
                .flatMap(state -> java.util.stream.Stream.concat(state.getLumenQi().getEntriesList().stream(),
                        state.getWallQi().getEntriesList().stream()))
                .mapToDouble(BodyMessages.Qi::getAmount).sum();
        String source = access.meridian$creatureBody().regionSource();
        context.getSource().sendFeedback(() -> Text.literal("档位=" + snapshot.getRealm().name()
                + " 开拓=" + opened.size() + " 主属性=" + mainAttribute(snapshot)
                + " 密度=" + (volume == 0 ? 0 : amount / volume) + " 装填体积=" + volume
                + " 区域=" + (source.isBlank() ? "无" : source)), false);
        return 1;
    }

    private static AddonRegistry installContent() {
        // Host 能力完整声明后再加载 Human 模板、动作、配方和开脉规则，确保依赖缺失显式失败。
        AddonMessages.AddonHostProfile host = AddonMessages.AddonHostProfile.newBuilder()
                .setHostId("meridian:java_reference").setApiVersion(1)
                .addCapabilities(AddonRegistry.BODY_TEMPLATES)
                .addCapabilities(AddonRegistry.ORGAN_RECIPES)
                .addCapabilities(AddonRegistry.ORGAN_INTAKE)
                .addCapabilities(AddonRegistry.ACTIONS)
                .addCapabilities(AddonRegistry.TISSUE_RECOVERY)
                .addCapabilities(AddonRegistry.PASSIVE_QI_FLOW)
                .addCapabilities(AddonRegistry.OPENING_DEFINITIONS)
                .addCapabilities(AddonRegistry.OPENING_RUNTIME)
                .addCapabilities(AddonRegistry.ORGAN_ENDURANCE).build();
        return AddonRegistry.load(AddonMessages.AddonRegistration.newBuilder().setProtocolVersion(1).setHost(host)
                .addBundles(HumanContent.bundle()).addBundles(QiVariantsContent.bundle()).build());
    }

    static void report(Entity entity, RuntimeException failure) {
        // 单个实体的接线失败只记录并拒绝本次请求，不能回落为另一份生命账本。
        LOGGER.error("生物身体宿主失败 subject={}", entity.getUuidAsString(), failure);
    }
}
