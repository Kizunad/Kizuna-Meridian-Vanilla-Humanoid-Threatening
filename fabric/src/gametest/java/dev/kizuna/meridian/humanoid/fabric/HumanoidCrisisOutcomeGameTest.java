package dev.kizuna.meridian.humanoid.fabric;

import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.attached;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.endings;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.check;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.engine;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.finish;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.moveTo;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.raiders;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.run;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.standApart;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.strikeCommand;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.waitUntil;
import static dev.kizuna.meridian.humanoid.fabric.HumanoidCrisisRaidGameTest.knows;
import static dev.kizuna.meridian.humanoid.fabric.HumanoidCrisisRaidGameTest.leaderName;
import static dev.kizuna.meridian.humanoid.fabric.HumanoidCrisisRaidGameTest.opened;
import static dev.kizuna.meridian.humanoid.fabric.HumanoidCrisisRaidGameTest.snapshot;
import static dev.kizuna.meridian.humanoid.fabric.HumanoidCrisisRaidGameTest.strikeAsTrueMany;

import com.google.protobuf.ByteString;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Feud;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Grudge;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Pending;
import dev.kizuna.meridian.humanoid.server.CrisisRules;
import dev.kizuna.meridian.humanoid.server.CrisisSchedule;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * 修为招敌第 4 步：一场怎么收场。头目死了算赢，甩开了、下线了、来人一个都不在名下了算逃，死在来人手里算输；
 * 了结后来人撤走、没人看得见就消失；逃了、输了头目记仇，下一场由它带队回来；当着生人杀修炼的生人、学会稀有卷涨风声。
 * 用测试队伍表 {@code meridian_humanoid_test:feud}：游炁玩家这一档是一个真炁卫道士。
 */
public final class HumanoidCrisisOutcomeGameTest {
    private static final int TIMEOUT = 600;
    private static final String PARTY = "meridian_humanoid_test:feud";
    private static final String COVETOUS = "meridian_humanoid:covetous";
    private static final String ORIGIN = "meridian_humanoid_test:orphan";
    private static final String FEUD = ORIGIN + "#0";
    private static final String FOE_VOLUME = "meridian_door_breaker:lower";
    private static final String VINDICATOR = "minecraft:vindicator";
    private static final String PILLAGER = "minecraft:pillager";
    private static final int LAST_STAGE = 1;
    private static final int STAGES = 2;
    private static final int BOTH_REALMS = 16;
    private static final int AWAY = 160;
    private static final int RELOG_GAP_TICKS = 3;
    private static final double NEAR = 4;
    private static final double EPSILON = 1e-9;
    private static final int CORPSE_KILL_LANE = 14;

    @GameTest(maxTicks = TIMEOUT)
    public void lastStageLeaderDropsTheFoeVolumeAndEndsTheFeud(GameTestHelper context) {
        // 出身仇家第一条写了两拨：已经赢过一拨，第 2 拨（最后一拨）的头目被玩家打死。
        // 在它倒下的地方必掉 foe_volume 的卷轴；这条仇了断；一场了结、不记仇，头目不再挂在招敌名下。
        // 进度：打死前三条都没有，打死后「不速之客」「了断」到手，没有记仇的人所以不给「冤家路窄」。
        CrisisTestPlayer fixture = CrisisTestPlayer.create(context);
        ServerPlayer player = fixture.player();
        standApart(context, player, 1);
        waitUntil(context, () -> attached(player), () -> {
            check(context, run(context, "meridian debug origin set " + player.getScoreboardName() + " " + ORIGIN) > 0,
                    "出身调试指令没有成功");
            engine().update(player, record -> CrisisSchedule.schedule(wonOnce(record),
                    new CrisisSchedule.Raid(PARTY, FEUD, LAST_STAGE, 0), 0));
            waitUntil(context, () -> run(context, strikeCommand(player)) > 0, () -> {
                Mob leader = raiders(context, player).getFirst();
                endings(context, player);
                leader.die(player.damageSources().playerAttack(player));
                endings(context, player, "repelled", "feud_ended");
                check(context, !itemsNear(context, leader, HumanoidCrisisOutcomeGameTest::isFoeScroll).isEmpty(),
                        "头目没掉 " + FOE_VOLUME);
                CrisisRecord record = record(player);
                Feud feud = record.getFeuds(0);
                check(context, feud.getKey().equals(FEUD) && feud.getStage() == STAGES && feud.getFinished(),
                        "仇家：" + feud);
                check(context, settledOnce(record) && !record.hasGrudge(), "打赢以后：" + record);
                check(context, !CrisisRaiders.sent(leader), "打死的头目还挂在招敌名下");
                finish(context, fixture);
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void followersRetreatWhenTheLeaderFallsAndVanishUnseen(GameTestHelper context) {
        // 精锐头目被打死：赢、不记仇；随从转成撤走，不再盯人，原版要它们打玩家也改不过去；
        // 48 格内没有玩家看得见（玩家转旁观）以后，撤够 3 秒就消失。这一场不是出身仇家的：只给「不速之客」。
        CrisisTestPlayer fixture = CrisisTestPlayer.create(context);
        ServerPlayer player = fixture.player();
        standApart(context, player, 2);
        waitUntil(context, () -> attached(player) && strikeAsTrueMany(context, player), () -> {
            List<Mob> raiders = raiders(context, player);
            raiders.getFirst().die(player.damageSources().playerAttack(player));
            endings(context, player, "repelled");
            List<Mob> followers = raiders.subList(1, raiders.size());
            CrisisRecord record = record(player);
            check(context, settledOnce(record) && !record.hasGrudge(), "打赢以后：" + record);
            check(context, followers.stream().allMatch(CrisisRaiders::retreating), "随从没转成撤走：" + followers);
            waitUntil(context, () -> followers.stream().allMatch(follower -> follower.getTarget() == null), () -> {
                followers.forEach(follower -> follower.setTarget(player));
                check(context, followers.stream().allMatch(follower -> follower.getTarget() == null),
                        "撤走的随从又盯上了玩家");
                player.setGameMode(GameType.SPECTATOR);
                waitUntil(context, () -> followers.stream().allMatch(Entity::isRemoved), () -> {
                    check(context, followers.stream().allMatch(follower -> !CrisisRaiders.sent(follower)
                            && follower.getRemovalReason() == Entity.RemovalReason.DISCARDED), "随从不是撤走消失的");
                    finish(context, fixture);
                });
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void escapedLeaderHoldsAGrudgeAndComesBackStronger(GameTestHelper context) {
        // 走出逃脱距离、30 秒没被看见：逃。头目记仇（物种、档位、会的卷、哪张队伍表、是哪一只），来人撤走；
        // 再来一场由它带队，记着的卷都会，并且多会一卷，没得多会就成了精锐。逃不算打退，三条进度都不给。
        CrisisTestPlayer fixture = CrisisTestPlayer.create(context);
        ServerPlayer player = fixture.player();
        standApart(context, player, 3);
        waitUntil(context, () -> attached(player) && run(context, strikeCommand(player) + " " + PARTY) > 0, () -> {
            Mob leader = raiders(context, player).getFirst();
            List<String> volumes = record(player).getPending().getLeaderVolumesList();
            moveTo(context, player, player.blockPosition().offset(AWAY, 0, 0));
            engine().update(player, HumanoidCrisisOutcomeGameTest::unseenLongAgo);
            waitUntil(context, () -> record(player).hasGrudge(), () -> {
                CrisisRecord record = record(player);
                Grudge grudge = record.getGrudge();
                check(context, settledOnce(record) && grudge.getSpecies().equals(VINDICATOR)
                        && grudge.getTier().equals(HumanoidTier.TRUE_QI.id()) && grudge.getEncounters() == 1
                        && grudge.getParty().equals(PARTY) && grudge.getVolumesList().equals(volumes)
                        && grudge.getEntity().equals(leader.getUUID().toString()), "逃了以后：" + record);
                endings(context, player);
                waitUntil(context, () -> leader.isRemoved() && run(context, strikeCommand(player) + " " + PARTY) > 0,
                        () -> comesBackStronger(context, fixture, grudge));
            });
        });
    }

    private static void comesBackStronger(GameTestHelper context, CrisisTestPlayer fixture, Grudge grudge) {
        // 撤走的头目已经不在了，再叫来的一场由记仇的人带队，名字照它那张队伍表。
        ServerPlayer player = fixture.player();
        Pending pending = record(player).getPending();
        Mob leader = raiders(context, player).getFirst();
        check(context, pending.getGrudgeLeads() && leader instanceof Vindicator
                && HumanoidTier.of(snapshot(leader)) == HumanoidTier.TRUE_QI, "第二场：" + pending);
        check(context, grudge.getVolumesList().stream().allMatch(volume -> knows(leader, volume)),
                "回来的头目忘了记着的卷：" + grudge.getVolumesList());
        Optional<String> extra = HumanoidBrain.config().volumes().listed(VINDICATOR, HumanoidTier.TRUE_QI).stream()
                .filter(volume -> !grudge.getVolumesList().contains(volume)).findFirst();
        if (extra.isPresent()) {
            check(context, knows(leader, extra.get()), "回来的头目没多会 " + extra.get());
        } else {
            check(context, opened(leader) == BOTH_REALMS, "没得多会的头目没成精锐");
        }
        check(context, leaderName(leader).equals("crisis.meridian_humanoid_test.feud.leader_name"),
                "回来的头目名字：" + leader.getCustomName());
        raiders(context, player).forEach(Mob::discard);
        finish(context, fixture);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void dyingToTheLeaderLosesAnUnreadScrollUntilItIsCaught(GameTestHelper context) {
        // 中档输：死在头目手里，头目先挑没读的卷轴拿走一卷（药留在身上随死亡掉落），记仇、带着它撤走；
        // 撤走途中被追上打死，卷轴掉回来，这份仇了断。输了不给进度；追上打死只给「冤家路窄」（那一场已经算输）。
        CrisisTestPlayer fixture = CrisisTestPlayer.create(context);
        ServerPlayer player = fixture.player();
        standApart(context, player, 4);
        waitUntil(context, () -> attached(player) && run(context, strikeCommand(player) + " " + PARTY) > 0, () -> {
            player.getInventory().add(CrisisVolumes.scroll(FOE_VOLUME).orElseThrow());
            player.getInventory().add(healingPotion());
            Mob leader = raiders(context, player).getFirst();
            CrisisEncounters.watch(player, "test:loss", HumanoidReceiptGameTest.watch(""));
            player.die(leader.damageSources().mobAttack(leader));
            var receipt = record(player).getReceiptsOrThrow("test:loss");
            check(context, receipt.getOutcome().equals("loss") && !receipt.hasFall(), "败局回执不符");
            Grudge grudge = record(player).getGrudge();
            check(context, grudge.getCarriedCount() == 1 && CrisisSpoils.decode(grudge.getCarried(0),
                    player.registryAccess()).filter(HumanoidCrisisOutcomeGameTest::isFoeScroll).isPresent()
                    && grudge.getEntity().equals(leader.getUUID().toString()), "记仇的人：" + grudge);
            check(context, itemsNear(context, player, HumanoidCrisisOutcomeGameTest::isFoeScroll).isEmpty(),
                    "夺走的卷轴还掉在玩家身边");
            check(context, !itemsNear(context, player, HumanoidCrisisOutcomeGameTest::isHealing).isEmpty(),
                    "药没有随死亡掉落");
            check(context, CrisisRaiders.retreating(leader), "头目没撤");
            endings(context, player);
            leader.die(context.getLevel().damageSources().genericKill());
            endings(context, player, "grudge_slain");
            check(context, !itemsNear(context, leader, HumanoidCrisisOutcomeGameTest::isFoeScroll).isEmpty(),
                    "追上打死记仇的人，卷轴没掉回来");
            check(context, !record(player).hasGrudge(), "追上打死以后仇还在");
            finish(context, fixture);
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void grudgeHolderLeadsTheNextRaidAndDropsWhatItTook(GameTestHelper context) {
        // 记仇的人是通用队伍表里来过的游炁掠夺者，身上带着夺走的治疗药水：下一场由它带队，物种照它、档位跟上这一档，
        // 多会一卷，名字照通用队伍表；打死它，药水掉回来，这份仇了断，「不速之客」「冤家路窄」一起到手。
        CrisisTestPlayer fixture = CrisisTestPlayer.create(context);
        ServerPlayer player = fixture.player();
        standApart(context, player, 5);
        waitUntil(context, () -> attached(player), () -> {
            ByteString potion = CrisisSpoils.encode(healingPotion(), player.registryAccess());
            engine().update(player, record -> record.toBuilder().setGrudge(Grudge.newBuilder().setSpecies(PILLAGER)
                    .setTier(HumanoidTier.WANDERING_QI.id()).setParty(COVETOUS).setEncounters(1).addCarried(potion))
                    .build());
            waitUntil(context, () -> run(context, strikeCommand(player) + " " + PARTY) > 0, () -> {
                Mob leader = raiders(context, player).getFirst();
                Pending pending = record(player).getPending();
                check(context, leader instanceof Pillager && pending.getGrudgeLeads()
                        && HumanoidTier.of(snapshot(leader)) == HumanoidTier.TRUE_QI, "带队的：" + pending);
                String extra = HumanoidBrain.config().volumes().listed(PILLAGER, HumanoidTier.TRUE_QI).getFirst();
                check(context, knows(leader, extra), "记仇的人回来没多会 " + extra);
                check(context, leaderName(leader).equals("crisis.meridian_humanoid.covetous.leader_name"),
                        "带队的名字：" + leader.getCustomName());
                endings(context, player);
                leader.die(player.damageSources().playerAttack(player));
                endings(context, player, "repelled", "grudge_slain");
                check(context, !itemsNear(context, leader, HumanoidCrisisOutcomeGameTest::isHealing).isEmpty(),
                        "药水没掉回来");
                CrisisRecord record = record(player);
                check(context, settledOnce(record) && !record.hasGrudge(), "打赢以后：" + record);
                finish(context, fixture);
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void loggingOutCountsAsEscapeAndClearsTheRaiders(GameTestHelper context) {
        // 来了人时下线：这一场按逃了结、头目记仇，来人当场清走；重进后记录里没有排着的，记仇的人还在。
        CrisisTestPlayer first = CrisisTestPlayer.create(context);
        ServerPlayer player = first.player();
        standApart(context, player, 6);
        waitUntil(context, () -> attached(player) && run(context, strikeCommand(player) + " " + PARTY) > 0, () -> {
            List<Mob> raiders = raiders(context, player);
            first.close();
            check(context, raiders.stream().allMatch(Entity::isRemoved), "下线后来人还在：" + raiders);
            context.runAfterDelay(RELOG_GAP_TICKS, () -> {
                CrisisTestPlayer second = first.rejoin(context);
                waitUntil(context, () -> attached(second.player()), () -> {
                    CrisisRecord record = record(second.player());
                    check(context, settledOnce(record) && record.getGrudge().getEncounters() == 1
                            && record.getGrudge().getEntity().equals(raiders.getFirst().getUUID().toString()),
                            "重进以后：" + record);
                    finish(context, second);
                });
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void unclaimedRaidersSettleAsEscapeAndOrphansAreCleared(GameTestHelper context) {
        // 来了人、名下却一个也不剩（崩服后重进就是这样）：下一步按逃了结、头目记仇。
        // 打了招敌标签却没人认领的来人，一加载进来就清走。
        CrisisTestPlayer fixture = CrisisTestPlayer.create(context);
        ServerPlayer player = fixture.player();
        standApart(context, player, 7);
        waitUntil(context, () -> attached(player) && run(context, strikeCommand(player) + " " + PARTY) > 0, () -> {
            List<Mob> raiders = raiders(context, player);
            CrisisEncounters.watch(player, "test:orphan", HumanoidReceiptGameTest.watch(""));
            CrisisRaiders.release(player.getUUID());
            raiders.forEach(Mob::discard);
            waitUntil(context, () -> !record(player).hasPending(), () -> {
                CrisisRecord record = record(player);
                var receipt = record.getReceiptsOrThrow("test:orphan");
                check(context, receipt.getOutcome().equals("escape") && !receipt.hasFall(), "孤儿场没有逃局回执");
                check(context, settledOnce(record) && record.getGrudge().getEncounters() == 1, "了结以后：" + record);
                Mob orphan = spawnAt(context, EntityTypes.VINDICATOR, player.position().add(NEAR, 0, 0),
                        mob -> mob.addTag(CrisisArrivals.RAIDER_TAG));
                waitUntil(context, orphan::isRemoved, () -> finish(context, fixture));
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void witnessedKillsAndRareVolumesRaiseHeat(GameTestHelper context) {
        // 只有僵尸旁观时杀修士不涨风声，换成村民后同样的击杀涨一次。
        CrisisTestPlayer fixture = CrisisTestPlayer.create(context);
        ServerPlayer player = fixture.player();
        standApart(context, player, 8);
        waitUntil(context, () -> attached(player), () -> {
            CrisisRules.Rise rise = engine().rules().heat().rise();
            List<LivingEntity> around = context.getLevel().getEntitiesOfClass(LivingEntity.class,
                    player.getBoundingBox().inflate(rise.witnessRadius()), HumanoidSpeciesHooks::isLivingHumanoid);
            check(context, around.isEmpty(), "旁边本来就有生人：" + around);
            double before = heat(player);
            Vec3 nearby = player.position().add(0, 0, NEAR);
            Mob zombie = spawnAt(context, EntityTypes.ZOMBIE, nearby, mob -> { });
            check(context, HumanoidFabric.isClaimed(zombie) && zombie.hasLineOfSight(player), "僵尸未认领或看不见玩家");
            cultivator(context, player).die(player.damageSources().playerAttack(player));
            check(context, heat(player) == before, "只有僵尸见证也涨了风声");
            zombie.discard();
            Mob witness = spawnAt(context, EntityTypes.VILLAGER, nearby, mob -> mob.setNoAi(true /* noAi */));
            cultivator(context, player).die(player.damageSources().playerAttack(player));
            check(context, Math.abs(heat(player) - before - rise.witnessedKill()) < EPSILON,
                    "当着村民杀了修炼的卫道士，风声从 " + before + " 到 " + heat(player));
            witness.discard();
            learnsRareVolume(context, fixture, rise);
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void killingBlackZombieInViewOfVillagerDoesNotRaiseHeat(GameTestHelper context) {
        // 当着村民杀有游炁身体的黑僵，不能算杀了修炼的生人。
        CrisisTestPlayer fixture = CrisisTestPlayer.create(context);
        ServerPlayer player = fixture.player();
        standApart(context, player, CORPSE_KILL_LANE);
        waitUntil(context, () -> attached(player), () -> {
            Mob witness = spawnAt(context, EntityTypes.VILLAGER, player.position().add(0, 0, NEAR), mob -> { });
            check(context, witness.hasLineOfSight(player), "村民看不见玩家");
            var corpse = spawnAt(context, EntityTypes.ZOMBIE, player.position().add(NEAR, 0, 0),
                    mob -> HumanoidSpeciesBirths.birth(mob, HumanoidTier.WANDERING_QI));
            var body = ((HumanoidEntityAccess) corpse).meridian$humanoidBody();
            check(context, body != null && HumanoidTier.of(body.snapshot()) == HumanoidTier.WANDERING_QI,
                    "未生成游炁档黑僵");
            check(context, HumanoidTiers.of(corpse).isEmpty(), "生人职守查到了黑僵的档位");
            double before = heat(player);
            corpse.die(player.damageSources().playerAttack(player));
            check(context, heat(player) == before, "当着村民击杀黑僵也涨了风声");
            witness.discard();
            finish(context, fixture);
        });
    }

    private static void learnsRareVolume(GameTestHelper context, CrisisTestPlayer fixture, CrisisRules.Rise rise) {
        // 学会门破斩下卷（稀有卷）连同它的前置卷：新学会的卷里每一卷稀有的涨一次 rare_volume。
        ServerPlayer player = fixture.player();
        var service = TechniqueFabric.service();
        CrisisRules rules = engine().rules();
        check(context, CrisisVolumes.rare(FOE_VOLUME, rules), FOE_VOLUME + " 不算稀有卷");
        List<String> volumes = CrisisArrivals.withPrerequisites(FOE_VOLUME,
                id -> service.registry().findVolume(id).orElseThrow().getPrerequisiteVolumeIdsList());
        long rare = volumes.stream().filter(volume -> CrisisVolumes.rare(volume, rules)).count();
        double before = heat(player);
        volumes.forEach(volume -> service.learn(player.getUUID(), volume));
        check(context, Math.abs(heat(player) - before - rare * rise.rareVolume()) < EPSILON,
                "学会 " + volumes + "（稀有 " + rare + " 卷），风声从 " + before + " 到 " + heat(player));
        finish(context, fixture);
    }

    private static CrisisRecord wonOnce(CrisisRecord record) {
        // 仇家第一条记成已经赢过一拨，和真排到最后一拨时一样。
        return record.toBuilder().setFeuds(0, record.getFeuds(0).toBuilder().setStage(LAST_STAGE)).build();
    }

    private static CrisisRecord unseenLongAgo(CrisisRecord record) {
        // 来人上次看见玩家是逃脱要求的秒数再早一步之前。
        long unseen = engine().rules().outcome().escapeUnseenSeconds() * 20L + CrisisEngine.STEP_TICKS;
        return record.toBuilder().setPending(record.getPending().toBuilder()
                .setLastSeenAt(record.getOnlineTicks() - unseen)).build();
    }

    private static Mob cultivator(GameTestHelper context, ServerPlayer player) {
        // 玩家身边两格出生一只真炁卫道士。
        return HumanoidSpawns.event(context.getLevel(), HumanoidSpecies.VINDICATOR,
                mob -> HumanoidSpeciesBirths.birth(mob, HumanoidTier.TRUE_QI), player.position().add(2, 0, 0));
    }

    static <T extends Mob> T spawnAt(GameTestHelper context, EntityType<T> type, Vec3 position,
                                     Consumer<T> before) {
        // 原版指令出生；进世界前先做 before（比如打标签），加载回调看得到。
        T mob = type.create(context.getLevel(), EntitySpawnReason.COMMAND);
        check(context, mob != null, "生不出 " + type);
        mob.snapTo(position.x, position.y, position.z, 0, 0);
        before.accept(mob);
        check(context, context.getLevel().addFreshEntity(mob), type + " 进不了世界");
        return mob;
    }

    private static List<ItemStack> itemsNear(GameTestHelper context, Entity entity, Predicate<ItemStack> wanted) {
        // 它身边几格内地上的东西里合要求的。
        return context.getLevel().getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(NEAR)).stream()
                .map(ItemEntity::getItem).filter(wanted).toList();
    }

    private static boolean isFoeScroll(ItemStack stack) {
        // 门破斩下卷的卷轴。
        return CrisisVolumes.volumeOf(stack).filter(FOE_VOLUME::equals).isPresent();
    }

    private static ItemStack healingPotion() {
        // 原版治疗药水。
        return PotionContents.createItemStack(Items.POTION, Potions.HEALING);
    }

    private static boolean isHealing(ItemStack stack) {
        // 和原版治疗药水是同一种东西。
        return ItemStack.isSameItemSameComponents(stack, healingPotion());
    }

    private static boolean settledOnce(CrisisRecord record) {
        // 了结了一场，没有排着的。
        return !record.hasPending() && record.getSettledCount() == 1;
    }

    private static CrisisRecord record(ServerPlayer player) {
        // 引擎里这名玩家此刻的记录。
        return engine().record(player).orElseThrow();
    }

    private static double heat(ServerPlayer player) {
        // 此刻的风声。
        return record(player).getHeat();
    }
}
