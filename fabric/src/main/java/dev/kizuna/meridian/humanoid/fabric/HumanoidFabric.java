package dev.kizuna.meridian.humanoid.fabric;

import com.mojang.brigadier.context.CommandContext;
import dev.kizuna.meridian.humanoid.server.HumanoidBodyGenerator;
import dev.kizuna.meridian.humanoid.server.HumanoidConfig;
import dev.kizuna.meridian.humanoid.server.HumanoidConfigLoader;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.protocol.pb.AddonMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.core.QiInventory;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.PersistentStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 类人实体 Fabric 适配器；只管理实体索引、区域服务和快照存档。 */
public final class HumanoidFabric implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid_host");
    private static final String STATE_ID = "meridian_humanoid";
    private static final int INSPECT_PERMISSION_LEVEL = 2;
    private static MinecraftServer activeServer;
    private static HumanoidConfig config;
    private static AddonRegistry content;
    private static QiVariantRegistry variants;
    private static HumanoidBodyGenerator generator;
    private static HumanoidEnvironmentClient environment;
    private static HumanoidWorldState worldState;

    @Override
    public void onInitialize() {
        // 内容目录在服务端启动时固定；缺少 Human 或 Qi-Variants 登记会直接阻止接线。
        config = HumanoidConfigLoader.load(FabricLoader.getInstance().getConfigDir()
                .resolve("meridian-humanoid.toml"));
        content = installContent();
        variants = QiVariantRegistry.load(content, QiVariantsContent.catalog());
        generator = new HumanoidBodyGenerator(variants, config);
        environment = new HumanoidEnvironmentClient();
        ServerLifecycleEvents.SERVER_STARTED.register(HumanoidFabric::startServer);
        ServerTickEvents.END_SERVER_TICK.register(HumanoidFabric::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(HumanoidFabric::stopServer);
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> registerCommands(dispatcher));
    }

    private static void startServer(MinecraftServer server) {
        // 世界释放请求从持久化状态恢复，无法恢复时保持宿主未绑定。
        activeServer = server;
        try {
            PersistentStateManager persistence = server.getOverworld().getPersistentStateManager();
            var file = server.getSavePath(WorldSavePath.ROOT).resolve("data/" + STATE_ID + ".dat");
            if (Files.exists(file)) {
                worldState = persistence.get(HumanoidWorldState::read, STATE_ID);
                if (worldState == null) {
                    throw new IllegalArgumentException("类人实体释放账本为空");
                }
            } else {
                worldState = new HumanoidWorldState();
                persistence.set(STATE_ID, worldState);
            }
        } catch (RuntimeException failure) {
            worldState = null;
            LOGGER.error("无法加载类人实体释放账本，类人实体宿主保持未绑定", failure);
        }
    }

    private static void stopServer(MinecraftServer server) {
        // 服务器停止后拒绝健康查询，等待下一次世界接线。
        worldState = null;
        activeServer = null;
    }

    private static void tick(MinecraftServer server) {
        if (server != activeServer || worldState == null) {
            return;
        }
        // 释放调用必须在主线程进行；失败项在下一个服务器 tick 或重启后重试。
        worldState.retry();
    }

    static boolean isReady() {
        // 世界存在且释放账本、内容和环境入口都已装配时才允许健康附属查询。
        return activeServer != null && worldState != null && generator != null && environment != null;
    }

    static MinecraftServer server() {
        // 调用方先用 isReady 检查；空引用属于宿主装配错误。
        return activeServer;
    }

    static HumanoidBodyGenerator generator() {
        // 生成器只读固定目录，实体查询不会重建随机配置。
        return generator;
    }

    static AddonRegistry content() {
        // 身体命令和生成器共用同一批已验证内容。
        return content;
    }

    static HumanoidEnvironmentClient.RegionSnapshot region(LivingEntity entity) {
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
        } catch (HumanoidEnvironmentClient.Unavailable failure) {
            LOGGER.debug("类人实体释放暂不可用 releaseId={}: {}", release.getReleaseId(), failure.getMessage());
            return false;
        }
    }

    static void enqueueRelease(Entity entity, dev.kizuna.meridian.core.QiInventory qi) {
        // 空库存不写账本，已有账本才允许入队。
        if (worldState == null || qi == null || qi.entries().isEmpty()) {
            return;
        }
        worldState.enqueue(entity.getUuid(), entity, qi);
    }

    static void enqueueRelease(Entity entity, BodyMessages.QiInventory qi) {
        // 协议库存沿用同一释放入口，避免产生不同的重试语义。
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
        // 认领范围只由固定实体类型 ID 决定。
        return HumanoidSpecies.fromEntityId(net.minecraft.registry.Registries.ENTITY_TYPE
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
        if (!(living instanceof HumanoidEntityAccess access)) {
            return;
        }
        HumanoidBodyState body = access.meridian$humanoidBody();
        if (body == null || access.meridian$humanoidReleaseQueued()) {
            return;
        }
        access.meridian$markHumanoidReleaseQueued();
        try {
            // 结算损耗和身体剩余库存合并成一条 RegionRelease，避免同一实体产生多笔释放。
            QiInventory settledLoss = settleRemovedBody(entity, body);
            QiInventory released = QiInventory.combine(List.of(settledLoss, body.releaseInventory()));
            enqueueRelease(entity, released);
        } catch (RuntimeException failure) {
            LOGGER.error("类人实体移除释放失败 subject={}", entity.getUuidAsString(), failure);
        }
    }

    private static QiInventory settleRemovedBody(Entity entity, HumanoidBodyState body) {
        // 原版移除可能先于 Human 服务恢复；无法结算时间时仍保留身体当前库存供释放。
        QiInventory empty = QiInventory.empty();
        try {
            QiInventory settled = BodyProtobuf.fromInventory(body.settle(now()));
            return QiInventory.combine(List.of(empty, settled));
        } catch (HumanoidEnvironmentClient.Unavailable unavailable) {
            LOGGER.debug("移除时无法结算类人实体时间 subject={}", entity.getUuidAsString());
        } catch (RuntimeException failure) {
            LOGGER.error("移除时结算类人实体时间失败 subject={}", entity.getUuidAsString(), failure);
        }
        return empty;
    }

    static String mainAttribute(BodyMessages.BodySnapshot snapshot) {
        // 调试显示按经脉和器官库存的元数合计选择主属性。
        Map<String, Double> amounts = new HashMap<>();
        snapshot.getMeridianStatesList().forEach(state -> {
            state.getLumenQi().getEntriesList()
                    .forEach(qi -> amounts.merge(qi.getAttribute(), qi.getAmount(), Double::sum));
            state.getWallQi().getEntriesList()
                    .forEach(qi -> amounts.merge(qi.getAttribute(), qi.getAmount(), Double::sum));
        });
        snapshot.getOrganStatesList().forEach(state -> state.getBuffer().getEntriesList()
                .forEach(qi -> amounts.merge(qi.getAttribute(), qi.getAmount(), Double::sum)));
        return amounts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElse("meridian:none");
    }

    private static void registerCommands(com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher) {
        // 只有具备权限的调用方可以读取身体快照，可省略目标以检查执行实体。
        var target = CommandManager.argument("target", EntityArgumentType.entity())
                .executes(context -> inspect(context, EntityArgumentType.getEntity(context, "target")));
        var inspect = CommandManager.literal("inspect")
                .requires(source -> source.hasPermissionLevel(INSPECT_PERMISSION_LEVEL))
                .executes(context -> inspect(context, context.getSource().getEntity()))
                .then(target);
        var humanoid = CommandManager.literal("humanoid").then(inspect);
        dispatcher.register(CommandManager.literal("meridian").then(humanoid));
    }

    private static int inspect(CommandContext<ServerCommandSource> context, Entity entity) {
        // 调试命令只读取已绑定状态，不触发身体创建。
        ServerCommandSource source = context.getSource();
        if (!(entity instanceof LivingEntity living) || !isClaimed(living)) {
            source.sendError(Text.literal("目标不是类人实体"));
            return 0;
        }
        HumanoidBodyState body;
        if (!(living instanceof HumanoidEntityAccess access) || (body = access.meridian$humanoidBody()) == null) {
            source.sendError(Text.literal("目标尚未创建身体"));
            return 0;
        }
        Text message = inspectMessage(body);
        source.sendFeedback(() -> message, false /* broadcastToOps */);
        return 1;
    }

    private static Text inspectMessage(HumanoidBodyState body) {
        // 检查信息保留主属性和体积加权密度，库存读取只用于显示。
        var snapshot = body.snapshot();
        var meridianStates = snapshot.getMeridianStatesList();
        var opened = meridianStates.stream()
                .filter(state -> state.getOpening().getStage() == BodyMessages.MeridianOpeningStage.OPENED)
                .map(BodyMessages.MeridianState::getId).toList();
        List<BodyMessages.Qi> meridianQi = meridianStates.stream()
                .flatMap(state -> java.util.stream.Stream.concat(state.getLumenQi().getEntriesList().stream(),
                        state.getWallQi().getEntriesList().stream()))
                .toList();
        double volume = meridianQi.stream().mapToDouble(qi -> qi.getAmount() / qi.getDensity()).sum();
        double amount = meridianQi.stream().mapToDouble(BodyMessages.Qi::getAmount).sum();
        double averageDensity;
        if (volume == 0) {
            averageDensity = 0;
        } else {
            averageDensity = amount / volume;
        }
        String source = body.regionSource();
        return Text.literal("档位=" + snapshot.getRealm().name()
                + " 开拓列表=" + opened + " 主属性=" + mainAttribute(snapshot)
                + " 密度=" + averageDensity + " 装填体积=" + volume
                + " 区域=" + (source.isBlank() ? "无" : source));
    }

    private static AddonRegistry installContent() {
        // Host 能力完整声明后再加载 Human 模板、动作、配方和开脉规则，确保依赖缺失显式失败。
        AddonMessages.AddonHostProfile host = AddonMessages.AddonHostProfile.newBuilder()
                .setHostId("meridian:java_reference")
                .setApiVersion(1)
                .addCapabilities(AddonRegistry.BODY_TEMPLATES)
                .addCapabilities(AddonRegistry.ORGAN_RECIPES)
                .addCapabilities(AddonRegistry.ORGAN_INTAKE)
                .addCapabilities(AddonRegistry.ACTIONS)
                .addCapabilities(AddonRegistry.TISSUE_RECOVERY)
                .addCapabilities(AddonRegistry.PASSIVE_QI_FLOW)
                .addCapabilities(AddonRegistry.OPENING_DEFINITIONS)
                .addCapabilities(AddonRegistry.OPENING_RUNTIME)
                .addCapabilities(AddonRegistry.ORGAN_ENDURANCE)
                .build();
        AddonMessages.AddonRegistration registration = AddonMessages.AddonRegistration.newBuilder()
                .setProtocolVersion(1)
                .setHost(host)
                .addBundles(HumanContent.bundle())
                .addBundles(QiVariantsContent.bundle())
                .build();
        return AddonRegistry.load(registration);
    }

    static void report(Entity entity, RuntimeException failure) {
        // 单个实体的接线失败只记录并拒绝当前请求，保留原有身体状态。
        LOGGER.error("类人实体身体宿主失败 subject={}", entity.getUuidAsString(), failure);
    }
}
