package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidBodyGenerator;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidVariant;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages;
import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages;
import dev.kizuna.meridian.protocol.pb.HealthMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.WitchEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.world.Difficulty;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 类人宿主的真实 Fabric 服务端回归；测试入口只通过公开字节协议访问健康宿主。
 */
public final class HumanoidGameTest implements FabricGameTest {
    private static final int SPAWN_X = 2;
    private static final int SPAWN_Y = 2;
    private static final int SPAWN_Z = 2;
    private static final int SECOND_SPAWN_X = 4;
    private static final int UNCLAIMED_SPAWN_Z = 5;
    private static final int OFF_THREAD_TIMEOUT_SECONDS = 5;
    private static final int RECEIPT_COUNT = 32;
    private static final int REMOVED_ENTITY_COUNT = 2;
    private static final int RAID_ENTITY_COUNT = 32;
    private static final int RAID_GRID_WIDTH = 8;
    private static final List<EntityType<? extends LivingEntity>> CLAIMED_TYPES = List.of(
            EntityType.VILLAGER,
            EntityType.WANDERING_TRADER,
            EntityType.PILLAGER,
            EntityType.VINDICATOR,
            EntityType.EVOKER,
            EntityType.WITCH,
            EntityType.ILLUSIONER);

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void claimsSevenHumanoidSpeciesAndLeavesOthersUnbound(TestContext context) {
        // 每种生人创建独立身体，其他实体保持 unbound。
        Set<String> bodyIds = new HashSet<>();
        int speciesCount = CLAIMED_TYPES.size();
        for (int index = 0; index < speciesCount; index++) {
            EntityType<? extends LivingEntity> type = CLAIMED_TYPES.get(index);
            LivingEntity entity = context.spawnEntity(type, SPAWN_X + index, SPAWN_Y, SPAWN_Z);
            disableAi(entity);
            HealthHostMessages.HealthHostResponse response = inspect(entity);
            context.assertTrue(response.hasBody(), "生人类型未认领：" + type
                    + " response=" + response + " ready=" + HumanoidFabric.isReady());
            context.assertTrue(bodyIds.add(response.getBody().getId()), "不同生人共享了身体 ID");
            context.assertTrue(((HumanoidEntityAccess) entity).meridian$humanoidBody() != null,
                    "首次查询后未绑定实体身体");
        }

        LivingEntity cow = context.spawnEntity(EntityType.COW, SPAWN_X, SPAWN_Y, UNCLAIMED_SPAWN_Z);
        HealthHostMessages.HealthHostResponse unbound = inspect(cow);
        context.assertTrue(unbound.getUnbound(), "非生人实体不应被类人宿主认领");
        context.assertTrue(((HumanoidEntityAccess) cow).meridian$humanoidBody() == null,
                "非生人实体被创建了身体");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void firstQueryCreatesBodyAndHumanUnboundDoesNotCreateOne(TestContext context) throws Exception {
        // 首次查询负责创建，缺少区域服务时不能留下半创建状态。
        LivingEntity fresh = context.spawnEntity(EntityType.VILLAGER, SPAWN_X, SPAWN_Y, SPAWN_Z);
        disableAi(fresh);
        context.assertTrue(((HumanoidEntityAccess) fresh).meridian$humanoidBody() == null,
                "健康查询前不应预先创建身体");
        HealthHostMessages.HealthHostResponse created = inspect(fresh);
        context.assertTrue(created.hasBody(), "首次健康查询未创建身体 response=" + created
                + " ready=" + HumanoidFabric.isReady());

        LivingEntity blocked = context.spawnEntity(EntityType.VILLAGER, SECOND_SPAWN_X, SPAWN_Y, SPAWN_Z);
        disableAi(blocked);
        Object delegate = setHumanDelegate(null);
        try {
            HealthHostMessages.HealthHostResponse response = inspect(blocked);
            context.assertTrue(response.hasFailure()
                            && response.getFailure().getCode() == MeridianMessages.FailureCode.ENVIRONMENT_UNAVAILABLE,
                    "Human 区域服务未绑定时应明确返回环境不可用");
            context.assertTrue(((HumanoidEntityAccess) blocked).meridian$humanoidBody() == null,
                    "Human 区域服务未绑定时不应创建身体");
        } finally {
            restoreHumanDelegate(delegate);
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void healthHostRejectsOffThreadAccess(TestContext context) throws Exception {
        // 先在主线程创建，再从线程池查询同一个实体。
        LivingEntity entity = context.spawnEntity(EntityType.VILLAGER, SPAWN_X, SPAWN_Y, SPAWN_Z);
        disableAi(entity);
        inspect(entity);
        HealthHostMessages.HealthHostResponse response = CompletableFuture
                .supplyAsync(() -> inspect(entity))
                .get(OFF_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        context.assertTrue(response.hasFailure()
                        && response.getFailure().getCode() == MeridianMessages.FailureCode.ACCESS_DENIED,
                "非主线程健康查询未返回 ACCESS_DENIED");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void nbtRoundTripPreservesSnapshotAndThirtyTwoReceipts(TestContext context) {
        // 保存完整回执窗口，清空实体缓存后验证快照和最后一条请求的幂等重放。
        LivingEntity entity = context.spawnEntity(EntityType.VILLAGER, SPAWN_X, SPAWN_Y, SPAWN_Z);
        disableAi(entity);
        ReceiptBatch batch = commitReceipts(context, entity);

        NbtCompound saved = entity.writeNbt(new NbtCompound());
        NbtCompound stored = saved.getCompound("meridian_humanoid");
        context.assertTrue(stored.getList("receipts", NbtElement.BYTE_ARRAY_TYPE).size() == RECEIPT_COUNT,
                "实体 NBT 未保存完整 32 条回执");
        BodyMessages.BodySnapshot beforeRoundTrip = batch.body();
        HumanoidEntityAccess access = (HumanoidEntityAccess) entity;
        access.meridian$humanoidBody(null);
        entity.readNbt(saved);

        HealthHostMessages.HealthHostResponse restored = inspect(entity);
        context.assertTrue(restored.hasBody() && restored.getBody().equals(beforeRoundTrip),
                "实体 NBT 往返改变了权威身体快照");
        HealthHostMessages.HealthHostResponse replay = exchange(batch.requests().get(RECEIPT_COUNT - 1));
        context.assertTrue(replay.hasBody() && replay.hasHealth()
                        && replay.getBody().equals(beforeRoundTrip),
                "恢复后的 32 条回执不能按原 request_id 幂等重放");
        context.complete();
    }

    private static ReceiptBatch commitReceipts(TestContext context, LivingEntity entity) {
        // 每次命令使用上次提交返回的修订号，填满实体的回执窗口。
        BodyMessages.BodySnapshot currentBody = inspect(entity).getBody();
        String partId = currentBody.getPartStates(0).getId();
        List<HealthHostMessages.HealthHostRequest> requests = new ArrayList<>();
        for (int index = 0; index < RECEIPT_COUNT; index++) {
            HealthHostMessages.HealthHostRequest request = healRequest(entity, currentBody, partId,
                    "gametest:receipt/" + index, 1.0);
            HealthHostMessages.HealthHostResponse response = exchange(request);
            context.assertTrue(response.hasBody() && response.hasHealth(), "健康回执未提交：" + index);
            currentBody = response.getBody();
            requests.add(request);
        }
        return new ReceiptBatch(currentBody, requests);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void damageAndDeathUseTheSameBodyChain(TestContext context) {
        // 受伤和死亡都应更新首次查询创建的权威身体。
        LivingEntity entity = context.spawnEntity(EntityType.VILLAGER, SPAWN_X, SPAWN_Y, SPAWN_Z);
        disableAi(entity);
        BodyMessages.BodySnapshot before = inspect(entity).getBody();
        var damageSources = entity.getDamageSources();
        entity.damage(damageSources.generic(), 1.0F);
        HumanoidBodyState afterHit = ((HumanoidEntityAccess) entity).meridian$humanoidBody();
        context.assertTrue(afterHit != null && afterHit.snapshot().getRevision() > before.getRevision(),
                "原版伤害没有进入类人身体事务");

        // 原版死亡回调是可信的外部事实，沿 Damage Addon 的确认路径完成身体死亡清账。
        entity.onDeath(damageSources.genericKill());
        HumanoidBodyState afterDeath = ((HumanoidEntityAccess) entity).meridian$humanoidBody();
        context.assertTrue(afterDeath != null && afterDeath.snapshot().getVitality().getDead(),
                "致死伤害没有标记身体死亡");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void killedAndDiscardedReleasesRetryUntilSuccessOrDuplicate(TestContext context) throws Exception {
        // 服务不可用时保留释放记录，恢复后在下一 tick 删除成功或重复请求。
        int before = pendingCount();
        LivingEntity killed = context.spawnEntity(EntityType.VILLAGER, SPAWN_X, SPAWN_Y, SPAWN_Z);
        LivingEntity discarded = context.spawnEntity(EntityType.VILLAGER, SECOND_SPAWN_X, SPAWN_Y, SPAWN_Z);
        disableAi(killed);
        disableAi(discarded);
        inspect(killed);
        inspect(discarded);
        Object delegate = setHumanDelegate(null);
        try {
            killed.remove(Entity.RemovalReason.KILLED);
            discarded.remove(Entity.RemovalReason.DISCARDED);
            context.assertTrue(pendingCount() == before + REMOVED_ENTITY_COUNT,
                    "KILLED/DISCARDED 没有各写入一条释放账本");

            HumanoidWorldState state = worldState();
            state.retry();
            context.assertTrue(pendingCount() == before + REMOVED_ENTITY_COUNT,
                    "Human 不可用时释放账本不应丢记录");
            List<EnvironmentHostMessages.RegionRelease> pending = pendingEntries();
            List<String> ownReleaseIds = releaseIdsFrom(pending, before);
            EnvironmentHostMessages.RegionRelease first = pending.get(before);
            verifyReleasesAfterRecovery(context, first, ownReleaseIds, delegate);
        } finally {
            restoreHumanDelegate(delegate);
        }
    }

    private static List<String> releaseIdsFrom(List<EnvironmentHostMessages.RegionRelease> pending, int start) {
        // 仅记录当前测试追加的释放请求，下一 tick 按这些 ID 检查删除结果。
        List<String> releaseIds = new ArrayList<>();
        int pendingCount = pending.size();
        for (int index = start; index < pendingCount; index++) {
            releaseIds.add(pending.get(index).getReleaseId());
        }
        return releaseIds;
    }

    private static void verifyReleasesAfterRecovery(TestContext context,
                                                    EnvironmentHostMessages.RegionRelease first,
                                                    List<String> releaseIds, Object delegate) throws Exception {
        // 预提交第一条请求制造 duplicate，其余请求由正常 tick 首次提交。
        restoreHumanDelegate(delegate);
        context.assertTrue(HumanoidFabric.release(first), "预提交释放请求失败");
        context.assertTrue(HumanoidFabric.release(first), "重复释放请求未返回 duplicate 成功");
        context.waitAndRun(1, () -> {
            context.assertTrue(!containsPending(releaseIds),
                    "成功与 duplicate 释放都应从账本删除，剩余=" + pendingCount());
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void chunkUnloadDoesNotReleaseBody(TestContext context) {
        // 区块卸载只保存实体，不把身体库存释放回环境。
        int before = pendingCount();
        LivingEntity entity = context.spawnEntity(EntityType.VILLAGER, SPAWN_X, SPAWN_Y, SPAWN_Z);
        disableAi(entity);
        inspect(entity);
        entity.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        context.assertTrue(pendingCount() == before,
                "区块卸载不应把实体身体写入释放账本");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void lightningKeepsBoundVillagersAndConvertsUnboundVillager(TestContext context) {
        // 两档身体都保留村民身份；没有身体的村民仍由原版转化为女巫。
        ServerWorld world = context.getWorld();
        world.getServer().setDifficulty(Difficulty.NORMAL, true /* forceDifficulty */);
        List<LightningSurvivor> survivors = new ArrayList<>();
        for (HumanoidVariant variant : HumanoidVariant.values()) {
            survivors.add(strikeBoundVillager(context, variant));
        }
        VillagerEntity unbound = strikeUnboundVillager(context);
        context.waitAndRun(1, () -> {
            for (LightningSurvivor survivor : survivors) {
                verifyLightningSurvivor(context, survivor);
            }
            List<WitchEntity> witches = world.getEntitiesByType(EntityType.WITCH,
                    unbound.getBoundingBox(), entity -> true);
            context.assertTrue(witches.size() == 1, "无身体村民雷击后没有按原版生成一名女巫");
            context.assertTrue(world.getDifficulty() == Difficulty.NORMAL, "雷击接线改变了世界难度");
            context.complete();
        });
    }

    private static LightningSurvivor strikeBoundVillager(TestContext context, HumanoidVariant variant) {
        // 显式生成指定档位，雷击后检查着火和真实身体损伤，不能只取消整个雷击方法。
        VillagerEntity villager = (VillagerEntity) context.spawnEntity(EntityType.VILLAGER,
                SPAWN_X, SPAWN_Y, SPAWN_Z);
        disableAi(villager);
        HumanoidBodyState body = bindVillagerBody(villager, variant);
        BodyMessages.BodySnapshot before = body.snapshot();
        villager.setFireTicks(0);
        strikeByLightning(context, villager);
        BodyMessages.BodySnapshot after = body.snapshot();
        double beforeHp = before.getPartStatesList().stream().mapToDouble(part -> part.getHp()).sum();
        double afterHp = after.getPartStatesList().stream().mapToDouble(part -> part.getHp()).sum();
        context.assertTrue(villager.isOnFire(), "有身体村民雷击后没有执行普通着火逻辑：" + variant);
        context.assertTrue(after.getRevision() > before.getRevision() && afterHp < beforeHp,
                "有身体村民的雷击伤害未经过健康宿主：" + variant);
        return new LightningSurvivor(villager, body, before.getId());
    }

    private static HumanoidBodyState bindVillagerBody(VillagerEntity villager, HumanoidVariant variant) {
        // 用真实生成器和存档恢复入口绑定指定档位，避免随机抽样漏掉某一档。
        var region = HumanoidFabric.region(villager);
        BodyMessages.TimeCursor now = HumanoidFabric.now();
        var entityId = villager.getUuid();
        var request = new HumanoidBodyGenerator.GenerationRequest(
                HumanoidSpecies.VILLAGER, variant, entityId, region.inventory(), now);
        long worldSeed = ((ServerWorld) villager.getWorld()).getSeed();
        BodyMessages.BodySnapshot snapshot = HumanoidFabric.generator().generate(request, worldSeed);
        var saved = new HumanoidBodyState.SavedBody(entityId, snapshot.toByteArray(), new byte[0][], region.sourceId());
        HumanoidBodyState body = HumanoidBodyState.restore(entityId, saved, now);
        ((HumanoidEntityAccess) villager).meridian$humanoidBody(body);
        return body;
    }

    private static VillagerEntity strikeUnboundVillager(TestContext context) {
        // 不查询健康宿主，确保无身体村民仍能执行原版女巫转化。
        VillagerEntity villager = (VillagerEntity) context.spawnEntity(EntityType.VILLAGER,
                SECOND_SPAWN_X, SPAWN_Y, SPAWN_Z);
        disableAi(villager);
        HumanoidEntityAccess access = (HumanoidEntityAccess) villager;
        context.assertTrue(access.meridian$humanoidBody() == null, "雷击前无身体村民被提前创建了身体");
        strikeByLightning(context, villager);
        context.assertTrue(villager.isRemoved(), "无身体村民雷击后没有按原版移除");
        context.assertTrue(access.meridian$humanoidBody() == null && !access.meridian$humanoidReleaseQueued(),
                "无身体村民雷击时错误创建或释放了身体");
        return villager;
    }

    private static void strikeByLightning(TestContext context, VillagerEntity villager) {
        // 直接触发实体雷击入口，避免闪电实体的范围伤害波及同批测试的其他实体。
        ServerWorld world = context.getWorld();
        LightningEntity lightning = EntityType.LIGHTNING_BOLT.create(world);
        context.assertTrue(lightning != null, "无法创建测试闪电");
        lightning.refreshPositionAndAngles(villager.getX(), villager.getY(), villager.getZ(), 0, 0);
        villager.onStruckByLightning(world, lightning);
    }

    private static void verifyLightningSurvivor(TestContext context, LightningSurvivor survivor) {
        // 下一 tick 仍须是同一实体、同一身体，且没有转化女巫或登记移除释放。
        VillagerEntity villager = survivor.entity();
        ServerWorld world = context.getWorld();
        context.assertTrue(villager.isAlive() && world.getEntity(villager.getUuid()) == villager,
                "有身体村民雷击后没有保留同一个村民");
        HumanoidEntityAccess access = (HumanoidEntityAccess) villager;
        context.assertTrue(access.meridian$humanoidBody() == survivor.body()
                        && survivor.body().snapshot().getId().equals(survivor.bodyId()),
                "有身体村民雷击后没有保留原身体");
        context.assertTrue(!access.meridian$humanoidReleaseQueued(), "有身体村民雷击后错误释放了身体");
        context.assertTrue(world.getEntitiesByType(EntityType.WITCH,
                villager.getBoundingBox(), entity -> true).isEmpty(), "有身体村民雷击后错误生成了女巫");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void zombieVillagerConversionReleasesOriginalBody(TestContext context) {
        // 转为亡灵后不再认领，原有生人身体按死亡路径释放。
        int before = pendingCount();
        VillagerEntity villager = (VillagerEntity) context.spawnEntity(EntityType.VILLAGER,
                SPAWN_X, SPAWN_Y, SPAWN_Z);
        disableAi(villager);
        inspect(villager);
        MobEntity converted = villager.convertTo(EntityType.ZOMBIE_VILLAGER, true /* keepEquipment */);
        context.assertTrue(converted != null && converted.getType() == EntityType.ZOMBIE_VILLAGER,
                "村民没有转成僵尸村民");
        context.assertTrue(pendingCount() == before + 1,
                "村民转僵尸村民没有按死亡路径写释放账本");
        context.assertTrue(((HumanoidEntityAccess) converted).meridian$humanoidBody() == null,
                "僵尸村民错误迁移了生人身体");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void raidBatchCreatesBodiesForManyClaimedEntities(TestContext context) {
        // 按网格连续生成多种生人，检查批量创建不会共享身体 ID。
        Set<String> ids = new HashSet<>();
        int speciesCount = CLAIMED_TYPES.size();
        for (int index = 0; index < RAID_ENTITY_COUNT; index++) {
            EntityType<? extends LivingEntity> type = CLAIMED_TYPES.get(index % speciesCount);
            LivingEntity raider = context.spawnEntity(type,
                    1 + index % RAID_GRID_WIDTH, SPAWN_Y, 1 + index / RAID_GRID_WIDTH);
            disableAi(raider);
            HealthHostMessages.HealthHostResponse response = inspect(raider);
            context.assertTrue(response.hasBody(), "袭击批量实体未创建身体：" + type);
            context.assertTrue(ids.add(response.getBody().getId()), "袭击批量实体身体 ID 重复");
        }
        context.assertTrue(ids.size() == RAID_ENTITY_COUNT, "袭击压力测试没有处理全部实体");
        context.complete();
    }

    private static HealthHostMessages.HealthHostResponse inspect(LivingEntity entity) {
        // 使用公开字节协议触发只读健康查询。
        String subjectId = entity.getUuidAsString();
        return exchange(HealthHostMessages.HealthHostRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId("gametest:inspect/" + subjectId)
                .setSubjectId(subjectId)
                .setInspect(true /* inspectBody */)
                .build());
    }

    private static HealthHostMessages.HealthHostResponse exchange(HealthHostMessages.HealthHostRequest request) {
        // 请求和响应都经过协议编解码，覆盖宿主的真实入口。
        return HealthHostProtobuf.decodeResponse(new HumanoidHealthHost().exchange(
                HealthHostProtobuf.encodeRequest(request)));
    }

    private static HealthHostMessages.HealthHostRequest healRequest(LivingEntity entity,
                                                                   BodyMessages.BodySnapshot body,
                                                                   String partId,
                                                                   String requestId,
                                                                   double amount) {
        // 健康目标和剂量独立构造，再装入身体命令与宿主信封。
        BodyMessages.HealthTarget target = BodyMessages.HealthTarget.newBuilder()
                .setPartId(partId)
                .build();
        HealthMessages.Healing dose = HealthMessages.Healing.newBuilder()
                .setTarget(target)
                .setHp(amount)
                .build();
        HealthMessages.HealBody healing = HealthMessages.HealBody.newBuilder()
                .setSourceId("gametest:nbt")
                .addDoses(dose)
                .build();
        MeridianMessages.BodyCommand command = MeridianMessages.BodyCommand.newBuilder()
                .setBodyId(body.getId())
                .setExpectedRevision(body.getRevision())
                .setHealBody(healing)
                .build();
        MeridianMessages.RequestEnvelope effect = MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(requestId)
                .setBodyCommand(command)
                .build();
        return HealthHostMessages.HealthHostRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(requestId)
                .setSubjectId(entity.getUuidAsString())
                .setEffect(effect)
                .build();
    }

    private static void disableAi(LivingEntity entity) {
        // 固定实体位置，避免原版寻路干扰生命周期断言。
        if (entity instanceof MobEntity mob) {
            mob.setAiDisabled(true /* aiDisabled */);
        }
    }

    private static HumanoidWorldState worldState() throws Exception {
        // 只在测试中读取世界账本，生产接线不暴露可变队列。
        Field field = HumanoidFabric.class.getDeclaredField("worldState");
        field.setAccessible(true /* allowAccess */);
        Object value = field.get(null);
        if (!(value instanceof HumanoidWorldState state)) {
            throw new IllegalStateException("类人释放账本未初始化");
        }
        return state;
    }

    @SuppressWarnings("unchecked")
    private static List<EnvironmentHostMessages.RegionRelease> pendingEntries() throws Exception {
        // 读取待释放队列，用于确认失败保留和成功删除。
        Field field = HumanoidWorldState.class.getDeclaredField("pending");
        field.setAccessible(true /* allowAccess */);
        return (List<EnvironmentHostMessages.RegionRelease>) field.get(worldState());
    }

    private static int pendingCount() {
        // 将反射读取失败报告为测试失败，不能误判为空队列。
        try {
            return pendingEntries().size();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static boolean containsPending(List<String> releaseIds) {
        // 只检查当前测试创建的记录，避免其他测试队列项干扰。
        try {
            for (EnvironmentHostMessages.RegionRelease release : pendingEntries()) {
                if (releaseIds.contains(release.getReleaseId())) {
                    return true;
                }
            }
            return false;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Object setHumanDelegate(Object value) throws Exception {
        // 临时解除 Human 服务绑定，并清除类人客户端的入口缓存。
        Field delegate = humanDelegateField();
        Object previous = delegate.get(null);
        delegate.set(null, value);
        clearEnvironmentClient();
        return previous;
    }

    private static Object restoreHumanDelegate(Object previous) throws Exception {
        // 恢复原服务后重新发现入口，不沿用临时测试绑定。
        Field delegate = humanDelegateField();
        Object current = delegate.get(null);
        delegate.set(null, previous);
        clearEnvironmentClient();
        return current;
    }

    private static Field humanDelegateField() throws Exception {
        // 从 Fabric 注册的公开入口定位服务绑定。
        List<Object> entrypoints = FabricLoader.getInstance()
                .getEntrypoints("meridian_environment_host", Object.class);
        if (entrypoints.isEmpty()) {
            throw new IllegalStateException("Human 环境入口未加载");
        }
        Field field = entrypoints.get(0).getClass().getDeclaredField("delegate");
        field.setAccessible(true /* allowAccess */);
        return field;
    }

    private static void clearEnvironmentClient() throws Exception {
        // 同时清除方法和目标引用，让后续请求重新绑定服务。
        Field environmentField = HumanoidFabric.class.getDeclaredField("environment");
        environmentField.setAccessible(true /* allowAccess */);
        Object client = environmentField.get(null);
        if (client == null) {
            return;
        }
        for (String name : List.of("exchange", "endpoint")) {
            Field field = client.getClass().getDeclaredField(name);
            field.setAccessible(true /* allowAccess */);
            field.set(client, null);
        }
    }

    private record ReceiptBatch(BodyMessages.BodySnapshot body,
                                List<HealthHostMessages.HealthHostRequest> requests) {
    }

    private record LightningSurvivor(VillagerEntity entity, HumanoidBodyState body, String bodyId) {
    }
}
