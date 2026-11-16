package dev.kizuna.meridian.humanoid.fabric;

import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.humanoid.server.HumanoidBrainConfig;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueSelector.CombatFact;
import dev.kizuna.meridian.technique.server.TechniqueService.Stagger;
import dev.kizuna.meridian.technique.server.TechniqueService.StaggerKind;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** 守村的职守和两个新战斗事实：对声望极差的玩家和袭击队伍出手、袭击时到钟边守着；看得见对手在起手、对手倒地。 */
public final class HumanoidGuardGameTest {
    private static final String HATED_BATCH = "meridian_humanoid_test:guard_hated_player";
    private static final String RAID_BATCH = "meridian_humanoid_test:guard_raid";
    private static final String FIELD = "meridian_humanoid_test:open_field";
    private static final String BREATH = "meridian_basic_techniques:breath";
    private static final String DOWNED_TAG = "meridian_humanoid_test.downed";
    private static final int TIMEOUT = 400;
    private static final int NOTICE_TICKS = 100;
    private static final int QUIET_TICKS = 40;
    private static final int WALK_TICKS = 200;
    private static final int POI_TICKS = 2;
    private static final BlockPos GUARD_START = new BlockPos(3, 0, 3);
    private static final int HOLD_TICKS = 40;
    /** 重大恶评每点算 −5 声望；25 点是 −125，过了 −100 的门槛。 */
    private static final int BAD_GOSSIP = 25;
    private static final long DOWN_MILLIS = 2000;
    private static final double DOWN_MOVEMENT = 0.2;
    /** 村民离钟 25 格出发；守卫半径 16，走进半径就该站住，不会一路走到钟跟前。 */
    private static final int BELL_DISTANCE = 25;
    private static final double TOO_CLOSE = 10;
    private static final double RADIUS_SLACK = 0.5;
    private static final double RAIDER_DISTANCE = 13;
    private static final double GOLDEN_CHEST_ARMOR = 5;

    static {
        // 别的附属自己管着的「倒地」要登记进来大脑才看得见；这里登记一种测试用的。
        HumanoidTargets.registerDowned(entity -> entity.entityTags().contains(DOWNED_TAG));
    }

    @GameTest(maxTicks = TIMEOUT, environment = HATED_BATCH)
    public void guardTurnsOnAPlayerTheVillageHates(GameTestHelper context) {
        // 两个村民对同一个玩家的声望都是 −125。带着守村的把他当对手；没带的不理他。玩家站着不动、没打任何人。
        Villager guard = mob(context, EntityTypes.VILLAGER, HumanoidTier.TRUE_QI, new BlockPos(2, 2, 2), false);
        Villager plain = mob(context, EntityTypes.VILLAGER, HumanoidTier.TRUE_QI, new BlockPos(5, 2, 2), false);
        HumanoidDuties.guardVillage(guard, true);
        FakePlayer player = player(context, "GuardHated", new BlockPos(3, 2, 5));
        Runnable cleanup = () -> leave(context, player, guard, plain);
        for (Villager villager : new Villager[]{guard, plain}) {
            villager.getGossips().add(player.getUUID(), GossipType.MAJOR_NEGATIVE, BAD_GOSSIP);
            ensure(context, villager.getPlayerReputation(player) <= HumanoidGuards.HOSTILE_REPUTATION,
                    () -> "声望没有降到门槛以下：" + villager.getPlayerReputation(player), cleanup);
        }
        await(context, NOTICE_TICKS, () -> HumanoidBrain.opponent(guard).filter(found -> found == player).isPresent(),
                () -> "守村的村民没有把声望极差的玩家当对手：带着守村=" + HumanoidDuties.guardsVillage(guard)
                        + " 对手=" + HumanoidBrain.opponent(guard), cleanup,
                () -> context.runAfterDelay(QUIET_TICKS, () -> {
                    ensure(context, HumanoidBrain.opponent(plain).isEmpty(), () -> "没带守村的村民也把玩家当了对手", cleanup);
                    cleanup.run();
                    context.succeed();
                }));
    }

    @GameTest(maxTicks = TIMEOUT, environment = RAID_BATCH, structure = FIELD)
    public void guardWalksToTheBellWhenTheVillageIsRaidedAndHoldsThere(GameTestHelper context) {
        // 村子遭袭击：带着守村、记得钟在哪的村民从 25 格外走进钟边 16 格就站住守着，不像原版那样一路走到钟跟前或去躲；
        // 守着的时候袭击队伍的人到了 16 格内，就把他当对手。
        // 原版的袭击只认「有人认领的兴趣点」算村子，不算村子的袭击第一拍就停掉；钟的兴趣点又是放下方块后隔一拍才登记，
        // 所以先放钟，等登记好了替村民认领下来，再起袭击。
        ServerLevel level = context.getLevel();
        BlockPos bell = context.absolutePos(new BlockPos(3, 0, 3 + BELL_DISTANCE));
        context.setBlock(new BlockPos(3, 0, 3 + BELL_DISTANCE), Blocks.BELL);
        context.runAfterDelay(POI_TICKS, () -> {
            var claimed = level.getPoiManager()
                    .take(type -> type.is(PoiTypes.MEETING), (type, at) -> at.equals(bell), bell, 1 /* range */);
            context.assertTrue(claimed.isPresent() && level.isVillage(bell), "钟没有登记成有人认领的集合点");
            Villager guard = mob(context, EntityTypes.VILLAGER, HumanoidTier.WANDERING_QI, GUARD_START, false);
            HumanoidDuties.guardVillage(guard, true);
            guard.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), bell));
            FakePlayer player = player(context, "GuardRaid", new BlockPos(3, 0, 3 + BELL_DISTANCE - 2));
            Runnable cleanup = () -> {
                stopRaid(level, bell);
                leave(context, player, guard);
            };
            ensure(context, level.getRaids().createOrExtendRaid(player, bell) != null,
                    () -> "没能在钟这里起一场袭击", cleanup);
            ensure(context, HumanoidGuards.posted(level, guard), () -> "村子遭袭击了，守村的村民却不算在守", cleanup);
            walksInAndHolds(context, guard, Vec3.atBottomCenterOf(bell), cleanup);
        });
    }

    private static void walksInAndHolds(GameTestHelper context, Villager guard, Vec3 post, Runnable cleanup) {
        // 走进钟边 16 格，再过一阵还站在守卫半径上（没有继续凑到钟跟前）。
        ServerLevel level = context.getLevel();
        await(context, WALK_TICKS, () -> guard.position().distanceTo(post) <= HumanoidGuards.POST_RADIUS,
                () -> "守村的村民没有走进钟边 16 格：离钟 " + guard.position().distanceTo(post) + " " + state(level, guard)
                        + " 寻路走完=" + guard.getNavigation().isDone(), cleanup, () ->
                context.runAfterDelay(HOLD_TICKS, () -> {
                    double distance = guard.position().distanceTo(post);
                    ensure(context, HumanoidGuards.posted(level, guard),
                            () -> "袭击还没完，守村的村民已经不算在守：" + state(level, guard), cleanup);
                    ensure(context, distance > TOO_CLOSE && distance <= HumanoidGuards.POST_RADIUS + RADIUS_SLACK,
                            () -> "守村的村民没有在守卫半径上站住：离钟 " + distance, cleanup);
                    turnsOnARaider(context, guard, post, cleanup);
                }));
    }

    private static void turnsOnARaider(GameTestHelper context, Villager guard, Vec3 post, Runnable cleanup) {
        // 袭击队伍的一员出现在 13 格外、站着不动：村民原版的敌对感知认卫道士只到 10 格，只有守村这条来源认得进来。
        ServerLevel level = context.getLevel();
        Raid raid = level.getRaidAt(BlockPos.containing(post));
        Vec3 spot = guard.position().add(post.subtract(guard.position()).normalize().scale(RAIDER_DISTANCE));
        Vindicator raider = mob(context, EntityTypes.VINDICATOR, HumanoidTier.MORTAL, GUARD_START, true);
        raider.snapTo(spot.x, spot.y, spot.z, 0, 0);
        raider.setCurrentRaid(raid);
        Runnable leaveAll = () -> {
            cleanup.run();
            raider.discard();
        };
        await(context, NOTICE_TICKS, () -> HumanoidBrain.opponent(guard).filter(found -> found == raider).isPresent(),
                () -> "守村的村民没有把 13 格外的袭击队伍成员当对手：在袭击里=" + raider.hasActiveRaid()
                        + " 相距 " + guard.distanceTo(raider) + " 村民在 " + guard.position() + " 队员在 "
                        + raider.position() + " 钟 " + post + " " + state(level, guard)
                        + " 决策=" + HumanoidBrain.decisions(guard.getUUID()), leaveAll, () -> {
                    leaveAll.run();
                    context.succeed();
                });
    }

    private static void stopRaid(ServerLevel level, BlockPos bell) {
        // 用例起的袭击不管成败都要停掉：留着它会一波波刷出袭击队伍，搅到后面批次的用例。
        Raid raid = level.getRaidAt(bell);
        if (raid != null) {
            raid.stop();
        }
    }

    private static String state(ServerLevel level, Villager guard) {
        // 失败时一并带出：职守还在不在、袭击什么状态、大脑认的对手。
        return "带着守村=" + HumanoidDuties.guardsVillage(guard) + " 在守=" + HumanoidGuards.posted(level, guard)
                + " 袭击=" + raidState(level, guard) + " 大脑在指挥=" + HumanoidBrain.directing(guard)
                + " 对手=" + HumanoidBrain.opponent(guard);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void castingAndDownedOpponentsAreSeen(GameTestHelper context) {
        // 对手正在起手、对手倒地，大脑选式时都看得见：倒地认功法库的倒地僵直，也认别的附属登记进来的判断。
        Villager self = mob(context, EntityTypes.VILLAGER, HumanoidTier.TRUE_QI, new BlockPos(2, 2, 2), true);
        Vindicator target = mob(context, EntityTypes.VINDICATOR, HumanoidTier.TRUE_QI, new BlockPos(4, 2, 2), true);
        Cow cow = context.spawn(EntityTypes.COW, new BlockPos(2, 2, 4));
        cow.setNoAi(true /* aiDisabled */);
        HumanoidTechniqueHost.prepare(self);
        HumanoidTechniqueHost.prepare(target);
        context.assertTrue(noneOf(facts(self, target), CombatFact.TARGET_CASTING, CombatFact.TARGET_DOWNED)
                && noneOf(facts(self, cow), CombatFact.TARGET_DOWNED), "什么都没发生就看见了起手或倒地");
        var request = CastRequest.newBuilder()
                .setBodyId(HumanoidTechniqueHost.prepare(target).body().snapshot().getId())
                .setTechnique(TechniqueReference.newBuilder().setId(BREATH).setVersion(1))
                .setCastId(UUID.randomUUID().toString())
                .build();
        var outcome = TechniqueFabric.service().cast(target.getUUID(), request);
        context.assertTrue(outcome.getPhase() == CastPhase.WARMUP, "对手起式没成：" + outcome);
        context.assertTrue(facts(self, target).contains(CombatFact.TARGET_CASTING), "对手在起手，没看见");
        TechniqueFabric.service().stagger(target.getUUID(), new Stagger(DOWN_MILLIS, DOWN_MOVEMENT, StaggerKind.DOWN));
        context.assertTrue(facts(self, target).contains(CombatFact.TARGET_DOWNED), "对手有倒地僵直，没看见");
        cow.addTag(DOWNED_TAG);
        context.assertTrue(facts(self, cow).contains(CombatFact.TARGET_DOWNED), "登记进来的倒地判断没有被用上");
        self.discard();
        target.discard();
        cow.discard();
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void opponentInAnIronChestplateOrBetterIsSeenAsArmored(GameTestHelper context) {
        // 对手胸甲到了铁胸甲那一档，大脑选式时看得见「甲厚」；光着、皮甲、金甲都不算，只穿铁头盔也不算。
        Villager self = mob(context, EntityTypes.VILLAGER, HumanoidTier.TRUE_QI,
                new BlockPos(2, 2, 2), true /* idle */);
        Vindicator target = mob(context, EntityTypes.VINDICATOR, HumanoidTier.TRUE_QI,
                new BlockPos(4, 2, 2), true /* idle */);
        context.assertTrue(noneOf(facts(self, target), CombatFact.TARGET_ARMORED), "没穿甲就算甲厚了");
        for (Item thin : List.of(Items.LEATHER_CHESTPLATE, Items.GOLDEN_CHESTPLATE)) {
            target.setItemSlot(EquipmentSlot.CHEST, new ItemStack(thin));
            context.assertTrue(noneOf(facts(self, target), CombatFact.TARGET_ARMORED), thin + " 不该算甲厚");
        }
        target.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        target.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        context.assertTrue(noneOf(facts(self, target), CombatFact.TARGET_ARMORED), "只戴铁头盔不该算甲厚");
        for (Item thick : List.of(Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE)) {
            target.setItemSlot(EquipmentSlot.CHEST, new ItemStack(thick));
            context.assertTrue(facts(self, target).contains(CombatFact.TARGET_ARMORED), thick + " 应当算甲厚");
        }
        // 门槛读的是大脑配置：调到 5，金胸甲（5 点）也算；用完换回原来的配置。
        target.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.GOLDEN_CHESTPLATE));
        HumanoidBrainConfig original = HumanoidBrain.config();
        try {
            HumanoidBrain.configure(armoredAt(original, GOLDEN_CHEST_ARMOR));
            context.assertTrue(facts(self, target).contains(CombatFact.TARGET_ARMORED), "门槛调到 5，金胸甲应当算甲厚");
        } finally {
            HumanoidBrain.configure(original);
        }
        self.discard();
        target.discard();
        context.succeed();
    }

    private static HumanoidBrainConfig armoredAt(HumanoidBrainConfig config, double chestArmor) {
        // 只换「甲厚」的门槛，别的照旧。
        var response = config.response();
        return new HumanoidBrainConfig(config.schedule(), config.threat(), config.assessment(),
                new HumanoidBrainConfig.ResponseRules(response.tactics(), response.assaultStandOff(),
                        response.failureLimit(), response.failureBlockMillis(), chestArmor),
                config.volumes(), config.concurrency());
    }

    private static Set<CombatFact> facts(Mob self, Mob target) {
        // 和大脑选式时同一个入口：对手带着功法库现在的观察（有身体的才有）。
        return HumanoidRepertoires.facts(self,
                new HumanoidOpponent(target, TechniqueFabric.service().observe(target.getUUID())));
    }

    private static boolean noneOf(Set<CombatFact> facts, CombatFact... unwanted) {
        return Set.of(unwanted).stream().noneMatch(facts::contains);
    }

    private static <T extends Mob> T mob(GameTestHelper context, EntityType<T> type, HumanoidTier tier,
                                        BlockPos relative, boolean idle) {
        // 指定档位出生再加入世界；idle 的不开 AI。
        T mob = type.create(context.getLevel(), EntitySpawnReason.COMMAND);
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(relative));
        mob.snapTo(position.x, position.y, position.z, 0, 0);
        mob.setNoAi(idle);
        mob.setPersistenceRequired();
        HumanoidSpeciesBirths.birth(mob, tier);
        context.assertTrue(context.getLevel().addFreshEntity(mob), "实体未加入世界");
        return mob;
    }

    private static FakePlayer player(GameTestHelper context, String name, BlockPos relative) {
        // 生存模式才算得上敌人（测试世界默认创造）；站着不动，不打任何人。
        ServerLevel level = context.getLevel();
        UUID id = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        FakePlayer player = FakePlayer.get(level, new GameProfile(id, name));
        player.setGameMode(GameType.SURVIVAL);
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(relative));
        player.snapTo(position.x, position.y, position.z, 0, 0);
        level.addNewPlayer(player);
        return player;
    }

    private static void leave(GameTestHelper context, FakePlayer player, Mob... mobs) {
        // 用例结束把玩家和生物都移走，不留给后面的批次。
        context.getLevel().removePlayerImmediately(player, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        for (Mob mob : mobs) {
            mob.discard();
        }
    }

    private static String raidState(ServerLevel level, Villager villager) {
        var raid = level.getRaidAt(villager.blockPosition());
        return raid == null ? "没有"
                : "active=" + raid.isActive() + " over=" + raid.isOver() + " stopped=" + raid.isStopped();
    }

    private static void await(GameTestHelper context, int ticks, java.util.function.BooleanSupplier condition,
                              java.util.function.Supplier<String> failure, Runnable cleanup, Runnable next) {
        // 每 tick 看一次，条件满足就往下走，到期还不满足就收场、失败。
        if (condition.getAsBoolean()) {
            next.run();
        } else if (ticks == 0) {
            ensure(context, false /* held */, failure, cleanup);
        } else {
            context.runAfterDelay(1, () -> await(context, ticks - 1, condition, failure, cleanup, next));
        }
    }

    private static void ensure(GameTestHelper context, boolean held, java.util.function.Supplier<String> failure,
                               Runnable cleanup) {
        // 不成立就先把场上的玩家、生物和袭击收走再报失败：留下的生存玩家和没停的袭击会搅到后面批次的用例。
        if (!held) {
            String message = failure.get();
            cleanup.run();
            throw context.assertionException(Component.literal(message));
        }
    }
}
