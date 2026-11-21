package dev.kizuna.meridian.humanoid.fabric;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.kizuna.meridian.fabric.MeridianCommands;
import dev.kizuna.meridian.human.fabric.HumanBodyStatus;
import dev.kizuna.meridian.humanoid.server.HumanoidBodyGenerator;
import dev.kizuna.meridian.humanoid.server.HumanoidBrainConfigLoader;
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
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeaps;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 类人实体 Fabric 适配器；只管理实体索引、区域服务和快照存档。 */
public final class HumanoidFabric implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid_host");
    private static final String STATE_ID = "meridian_humanoid";
    private static final SimpleCommandExceptionType BODY_NOT_CREATED =
            new SimpleCommandExceptionType(Component.literal("目标尚未创建身体"));
    private static MinecraftServer activeServer;
    private static boolean stopping;
    private static HumanoidConfig config;
    private static AddonRegistry content;
    private static QiVariantRegistry variants;
    private static HumanoidBodyGenerator generator;
    private static HumanoidEnvironmentClient environment;
    private static HumanoidWorldState worldState;
    private static boolean volumesChecked;

    @Override
    public void onInitialize() {
        // 内容目录在服务端启动时固定；缺少 Human 或 Qi-Variants 登记会直接阻止接线。
        HumanoidSpawnEggs.register();
        BirthEquipment.initialize();
        var configDirectory = FabricLoader.getInstance().getConfigDir();
        config = HumanoidConfigLoader.load(configDirectory.resolve("meridian-humanoid.toml"));
        var brainConfig = HumanoidBrainConfigLoader.load(configDirectory.resolve("meridian-humanoid-brain.toml"));
        HumanoidBrain.configure(brainConfig.withVolumes(HumanoidAddonVolumes.joined(brainConfig.volumes())));
        HumanoidVolumeSources.register();
        HumanoidCharmController.initialize();
        HumanoidRestrictions.initialize();
        HumanoidTechniqueHost.registerGuardCheck();
        HumanoidStaggerAnimation.initialize();
        HumanoidWitchAi.initialize(configDirectory);
        CrisisFabric.initialize(configDirectory);
        OriginTalents.initialize();
        HumanoidEvokerSpells.initialize();
        VanillaDamageFabric.registerDamageObserver(HumanoidThreats::damaged);
        TechniqueLeaps.addDirector(HumanoidBrain::hasOpponent);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> initializeContent());
        environment = new HumanoidEnvironmentClient();
        ServerLifecycleEvents.SERVER_STARTED.register(HumanoidFabric::startServer);
        ServerLifecycleEvents.AFTER_SAVE.register(HumanoidFabric::saveWorld);
        ServerTickEvents.END_SERVER_TICK.register(HumanoidFabric::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stopping(true));
        ServerLifecycleEvents.SERVER_STOPPED.register(HumanoidFabric::stopServer);
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> onEntityUnloaded(entity));
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> onEntityLoaded(entity));
        registerCommands();
    }

    private static void startServer(MinecraftServer server) {
        // 世界释放请求从持久化状态恢复，无法恢复时拒绝已认领实体的健康请求。
        activeServer = server;
        stopping = false;
        try {
            Path file = stateFile(server);
            if (Files.exists(file)) {
                worldState = HumanoidWorldState.load(file);
            } else {
                worldState = new HumanoidWorldState();
            }
            worldState.bind(file);
        } catch (IOException | RuntimeException failure) {
            worldState = null;
            LOGGER.error("无法加载类人实体释放账本，类人实体健康请求将失败", failure);
        }
    }

    private static void saveWorld(MinecraftServer server, boolean flush, boolean force) {
        // 跟随原版世界保存（含停服）；/save-off 后只在强制保存时写入，与原版主世界存档数据一致。
        if (server == activeServer && worldState != null && (force || !server.overworld().noSave())) {
            worldState.save(stateFile(server).toFile());
        }
    }

    private static Path stateFile(MinecraftServer server) {
        // 账本沿用原来的世界数据文件位置，世界保存与 tick 末交接写同一个文件。
        return server.getWorldPath(LevelResource.ROOT).resolve("data/" + STATE_ID + ".dat");
    }

    private static void stopServer(MinecraftServer server) {
        // 服务器停止后拒绝健康查询，等待下一次世界接线。
        worldState = null;
        volumesChecked = false;
        HumanoidTechniqueHost.clear();
        activeServer = null;
    }

    private static void tick(MinecraftServer server) {
        if (server != activeServer) {
            return;
        }
        if (!volumesChecked) {
            checkVolumes();
        }
        if (worldState == null) {
            return;
        }
        // 释放调用必须在主线程进行；失败项在下一个服务器 tick 或重启后重试。
        worldState.retry();
    }

    private static void checkVolumes() {
        // 功法服务在服务器启动回调里才建好，学卷表和出身开局卷放到第一个 tick 对注册表核对：缺卷、前置卷排在后面都直接失败。
        var registry = TechniqueFabric.service().registry();
        var volumes = HumanoidBrain.config().volumes();
        volumes.requireInstalled(volume -> registry.findVolume(volume).isPresent());
        volumes.requireOrdered(volume -> registry.findVolume(volume).orElseThrow().getPrerequisiteVolumeIdsList());
        BirthVolumes.validate(HumanoidBrain.config().volumePool(), registry);
        OriginTalents.requireVolumes();
        volumesChecked = true;
    }

    static boolean isReady() {
        // 世界存在且释放账本、内容和环境入口都已装配时才允许健康附属查询。
        return activeServer != null && worldState != null && generator != null && environment != null;
    }

    static boolean isStopping() {
        // 停服开始到世界关完之间：功法服务已经取消全部施放并关闭，世界还在存盘、实体还在卸载。
        return stopping;
    }

    static void stopping(boolean value) {
        // 停服事件和 GameTest 用；开服时复位。
        stopping = value;
    }

    static MinecraftServer server() {
        // 调用方先用 isReady 检查；空引用属于宿主装配错误。
        return activeServer;
    }

    static HumanoidConfig config() {
        // 生成参数（开脉扩容量等）出生以后补开经脉时也要用同一份。
        return config;
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
        // 只接受 Human 的同步写盘确认；失败或响应丢失时继续保留原请求。
        try {
            return environment.release(release);
        } catch (HumanoidEnvironmentClient.Unavailable failure) {
            LOGGER.debug("类人实体释放暂不可用 releaseId={}: {}", release.getReleaseId(), failure.getMessage());
            return false;
        }
    }

    static boolean forgetRelease(String releaseId) {
        // 遗忘失败和丢响应都保留待遗忘 ID，下一 tick 使用同一个 ID 重发。
        try {
            return environment.forgetRelease(releaseId);
        } catch (HumanoidEnvironmentClient.Unavailable failure) {
            LOGGER.debug("类人实体释放遗忘暂不可用 releaseId={}: {}", releaseId, failure.getMessage());
            return false;
        }
    }

    static void enqueueRelease(Entity entity, dev.kizuna.meridian.core.QiInventory qi) {
        // 空库存不写账本，已有账本才允许入队。
        if (worldState == null || qi == null || qi.entries().isEmpty()) {
            return;
        }
        worldState.enqueue(entity.getUUID(), entity, qi);
    }

    static void enqueueRelease(Entity entity, BodyMessages.QiInventory qi) {
        // 协议库存沿用同一释放入口，避免产生不同的重试语义。
        if (worldState == null || qi == null || qi.getEntriesCount() == 0) {
            return;
        }
        worldState.enqueue(entity.getUUID(), entity, qi);
    }

    static LivingEntity findEntity(UUID id) {
        // 健康宿主只在主线程扫描已加载世界；卸载区块中的实体不会被凭空重建。
        if (activeServer == null || id == null) {
            return null;
        }
        for (var world : activeServer.getAllLevels()) {
            Entity entity = world.getEntity(id);
            if (entity instanceof LivingEntity living && !living.isRemoved()) {
                return living;
            }
        }
        return null;
    }

    public static boolean isClaimed(LivingEntity entity) {
        return HumanoidSpeciesHooks.claimed(entity).isPresent();
    }

    public static void beforeDimensionTransfer(Entity source, Entity target) {
        // Entity.restoreFrom 在复制源实体 NBT 前调用，先提交源维度最后一段年龄。
        if (source.level().isClientSide() || source.level() == target.level()
                || !(source instanceof LivingEntity living)
                || !(living instanceof HumanoidEntityAccess access)) {
            return;
        }
        MinecraftServer server = source.level().getServer();
        if (server == null || !server.isSameThread() || !isReady()
                || access.meridian$humanoidLoading() || access.meridian$humanoidLoadFailed()) {
            return;
        }
        HumanoidSpecies species = HumanoidSpeciesHooks.claimed(living).orElse(null);
        if (species == null) {
            return;
        }
        if (target instanceof HumanoidEntityAccess targetAccess) {
            targetAccess.meridian$markDimensionTransferred();
        }
        try {
            BodyMessages.TimeCursor now = now();
            HumanoidBodyState body = access.meridian$humanoidBody();
            byte[] saved = access.meridian$humanoidSavedBody();
            if (body == null && saved != null && saved.length > 0) {
                body = HumanoidHealthHost.bodyState(living, species, now);
            }
            if (body != null) {
                String sourceDimensionId = source.level().dimension().identifier().toString();
                body.prepareDimensionTransfer(sourceDimensionId, now);
            }
        } catch (RuntimeException failure) {
            report(living, failure);
        }
    }

    private static void onEntityLoaded(Entity entity) {
        // 只给已有身体的已认领实体记录载入边界，不在区块加载时创建新身体。
        if (!(entity instanceof LivingEntity living) || !isReady()
                || !(living instanceof HumanoidEntityAccess access)
                || access.meridian$humanoidLoading() || access.meridian$humanoidLoadFailed()
                || !isClaimed(living)) {
            return;
        }
        byte[] savedBody = access.meridian$humanoidSavedBody();
        if (savedBody == null || savedBody.length == 0) {
            return;
        }
        try {
            access.meridian$loadedAtMillis(now().getElapsedMillis());
        } catch (RuntimeException failure) {
            report(living, failure);
        }
    }

    public static void beforeEntitySave(LivingEntity living) {
        // 先把已绑定身体推进到写盘时刻；普通自动保存不会冻结在线年龄。
        if (!isReady() || !activeServer.isSameThread() || !(living instanceof HumanoidEntityAccess access)
                || access.meridian$humanoidLoading() || access.meridian$humanoidLoadFailed()) {
            return;
        }
        if (!isClaimed(living)) {
            return;
        }
        try {
            BodyMessages.TimeCursor now = now();
            HumanoidBodyState body = access.meridian$humanoidBody();
            if (body == null) {
                HumanoidSpecies species = HumanoidSpeciesHooks.claimed(living).orElse(null);
                byte[] savedBody = access.meridian$humanoidSavedBody();
                if (species == null || savedBody == null || savedBody.length == 0) {
                    return;
                }
                body = HumanoidHealthHost.bodyState(living, species, now);
            }
            String dimensionId = living.level().dimension().identifier().toString();
            body.selectDimension(dimensionId, now);
            enqueueRelease(living, body.settle(now));
        } catch (RuntimeException failure) {
            report(living, failure);
        }
    }

    private static void onEntityUnloaded(Entity entity) {
        // 区块卸载和生物过传送门不经过 Entity.remove，只有这个事件收得到；会话和大脑状态按 UUID 存，
        // 不清的话读档出来的同 UUID 新对象会沿用旧状态，不再装大脑的 Goal。
        // 死亡和丢弃已经在 onEntityRemoved 里清过，这里再清一次什么都不做。
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        try {
            pauseAging(living);
            HumanoidTechniqueHost.removed(living);
        } catch (RuntimeException failure) {
            report(living, failure);
        }
    }

    public static void onEntityRemoved(Entity entity, Entity.RemovalReason reason) {
        // 区块卸载和跨维度只移除运行时对象，身体仍由实体 NBT 承载；只有真正离开世界才释放。
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        try {
            HumanoidTechniqueHost.removed(living);
        } catch (RuntimeException failure) {
            report(living, failure);
        }
        boolean bodyPersists = preservesBody(reason);
        if (bodyPersists) {
            pauseAging(living);
        }
        if (bodyPersists) {
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
            LOGGER.error("类人实体移除释放失败 subject={}", entity.getStringUUID(), failure);
        }
    }

    private static boolean preservesBody(Entity.RemovalReason reason) {
        return reason == Entity.RemovalReason.UNLOADED_TO_CHUNK
                || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER
                || reason == Entity.RemovalReason.CHANGED_DIMENSION;
    }

    private static void pauseAging(LivingEntity living) {
        if (!isReady() || !(living instanceof HumanoidEntityAccess access)) {
            return;
        }
        HumanoidBodyState body = access.meridian$humanoidBody();
        if (body == null || !isClaimed(living)) {
            return;
        }
        try {
            body.pauseAging(now());
        } catch (RuntimeException failure) {
            report(living, failure);
        }
    }

    private static QiInventory settleRemovedBody(Entity entity, HumanoidBodyState body) {
        // 原版移除可能先于 Human 服务恢复；无法结算时间时仍保留身体当前库存供释放。
        QiInventory empty = QiInventory.empty();
        try {
            QiInventory settled = BodyProtobuf.fromInventory(body.settle(now()));
            return QiInventory.combine(List.of(empty, settled));
        } catch (HumanoidEnvironmentClient.Unavailable unavailable) {
            LOGGER.debug("移除时无法结算类人实体时间 subject={}", entity.getStringUUID());
        } catch (RuntimeException failure) {
            LOGGER.error("移除时结算类人实体时间失败 subject={}", entity.getStringUUID(), failure);
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

    private static void registerCommands() {
        // /meridian humanoid spawn 登记在 Framework 的注册点，要求权限 2；
        // 类人身体快照并入 Human 的 /meridian body status，作为非玩家目标的视图登记。
        MeridianCommands.register(MeridianCommands.Branch.HUMANOID, "spawn", MeridianCommands.GAMEMASTER,
                (node, context) -> HumanoidSpawnCommands.attach(node));
        HumanBodyStatus.registerView(HumanoidFabric::showBody);
    }

    private static boolean showBody(CommandSourceStack source, Entity entity) throws CommandSyntaxException {
        // 不是已认领的类人实体时交还 Human 继续分派；只读取已绑定状态，不触发身体创建。
        if (!(entity instanceof LivingEntity living) || !isClaimed(living)) {
            return false;
        }
        HumanoidBodyState body;
        if (!(living instanceof HumanoidEntityAccess access) || (body = access.meridian$humanoidBody()) == null) {
            throw BODY_NOT_CREATED.create();
        }
        Component message = inspectMessage(body);
        source.sendSuccess(() -> message, false /* broadcastToOps */);
        return true;
    }

    private static Component inspectMessage(HumanoidBodyState body) {
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
        return Component.literal("档位=" + snapshot.getRealm().name()
                + " 开拓列表=" + opened + " 主属性=" + mainAttribute(snapshot)
                + " 密度=" + averageDensity + " 装填体积=" + volume
                + " 区域=" + (source.isBlank() ? "无" : source));
    }

    private static void initializeContent() {
        if (content == null) {
            content = installContent();
            variants = QiVariantRegistry.load(content, QiVariantsContent.catalog());
            generator = new HumanoidBodyGenerator(variants, config);
        }
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
                .addAllBundles(HumanoidSpeciesHooks.freezeContent())
                .build();
        return AddonRegistry.load(registration);
    }

    static void report(Entity entity, RuntimeException failure) {
        // 单个实体的接线失败只记录并拒绝当前请求，保留原有身体状态。
        LOGGER.error("类人实体身体宿主失败 subject={}", entity.getStringUUID(), failure);
    }
}
