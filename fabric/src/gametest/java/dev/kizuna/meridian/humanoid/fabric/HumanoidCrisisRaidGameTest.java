package dev.kizuna.meridian.humanoid.fabric;

import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.COMMAND_FAILED;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.attached;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.check;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.engine;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.finish;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.raiders;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.run;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.standApart;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.strikeCommand;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.suggestions;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.waitUntil;

import dev.kizuna.meridian.humanoid.server.CrisisSchedule;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.illager.Evoker;
import net.minecraft.world.entity.monster.illager.Vindicator;

/**
 * 修为招敌第 3 步：到点来人。用 {@code crisis strike}（不看时辰）叫测试队伍表 {@code meridian_humanoid_test:feud}：
 * 游炁一档是一个真炁卫道士；开脉多的真炁一档是精锐唤魔者带 2–3 个真炁卫道士。来人只盯这名玩家。
 */
public final class HumanoidCrisisRaidGameTest {
    private static final int TIMEOUT = 400;
    private static final String PARTY = "meridian_humanoid_test:feud";
    private static final String ORIGIN = "meridian_humanoid_test:orphan";
    private static final String FOE_VOLUME = "meridian_door_breaker:lower";
    private static final String EVOKER = "minecraft:evoker";
    private static final int TRUE_MANY_OPENED = 16;
    private static final int BOTH_REALMS = 16;
    private static final double MIN_DISTANCE = 24;
    private static final double MAX_DISTANCE = 40;
    private static final double SPOT_TOLERANCE = 1.5;

    @GameTest(maxTicks = TIMEOUT)
    public void eliteLineupArrivesAndHuntsOnlyThePlayer(GameTestHelper context) {
        // 开脉多的真炁：精锐唤魔者带 2–3 个真炁卫道士，24–40 格外现身；头目两层全开、本物种真炁档的卷全会、有名字；
        // 都打了标签、只盯这名玩家，原版改目标改不到别人；已经来了人再叫失败；队伍表能补全。
        CrisisTestPlayer fixture = CrisisTestPlayer.create(context);
        ServerPlayer player = fixture.player();
        standApart(context, player);
        waitUntil(context, () -> attached(player) && strikeAsTrueMany(context, player), () -> {
            List<Mob> raiders = raiders(context, player);
            Mob leader = raiders.getFirst();
            check(context, leader instanceof Evoker && raiders.size() >= 3 && raiders.size() <= 4,
                    "来人：" + raiders);
            check(context, raiders.subList(1, raiders.size()).stream().allMatch(Vindicator.class::isInstance),
                    "随从不全是卫道士：" + raiders);
            raiders.forEach(raider -> assertRaider(context, player, raider));
            check(context, opened(leader) == BOTH_REALMS, "精锐头目开了 " + opened(leader) + " 条");
            for (String volume : HumanoidBrain.config().volumes().listed(EVOKER, HumanoidTier.TRUE_QI)) {
                check(context, knows(leader, volume), "精锐头目不会 " + volume);
            }
            check(context, leaderName(leader).equals("crisis.meridian_humanoid_test.feud.leader_name"),
                    "头目名字：" + leader.getCustomName());
            check(context, run(context, strikeCommand(player)) == COMMAND_FAILED
                    && engine().strike(player, Optional.empty()) == CrisisEngine.Strike.ALREADY_ARRIVED,
                    "已经来了人还能再叫");
            Set<String> parties = suggestions(context.getLevel().getServer().createCommandSourceStack(),
                    "meridian humanoid crisis strike " + player.getScoreboardName() + " ");
            check(context, parties.containsAll(Set.of(PARTY, "meridian_humanoid:covetous")), "补全：" + parties);
            waitUntil(context, () -> raiders.stream().allMatch(raider -> raider.getTarget() == player),
                    () -> rejectBystander(context, fixture, raiders));
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void lastFeudStageLeaderBringsTheFoeVolume(GameTestHelper context) {
        // 出身仇家第一条写了两拨和 foe_volume：排到第 2 拨（最后一拨）时，游炁玩家这一档的真炁卫道士头目会这卷。
        CrisisTestPlayer fixture = CrisisTestPlayer.create(context);
        ServerPlayer player = fixture.player();
        standApart(context, player);
        waitUntil(context, () -> attached(player), () -> {
            check(context, run(context, "meridian debug origin set " + player.getScoreboardName() + " " + ORIGIN) > 0,
                    "出身调试指令没有成功");
            engine().update(player, record -> CrisisSchedule.schedule(record,
                    new CrisisSchedule.Raid(PARTY, ORIGIN + "#0", 1, 0), 0));
            waitUntil(context, () -> run(context, strikeCommand(player)) > 0, () -> {
                List<Mob> raiders = raiders(context, player);
                Mob leader = raiders.getFirst();
                check(context, leader instanceof Vindicator && raiders.size() == 1, "来人：" + raiders);
                assertRaider(context, player, leader);
                check(context, knows(leader, FOE_VOLUME), "最后一拨的头目不会 " + FOE_VOLUME);
                raiders.forEach(Mob::discard);
                finish(context, fixture);
            });
        });
    }

    static boolean strikeAsTrueMany(GameTestHelper context, ServerPlayer player) {
        // 记录里改成开脉多的真炁（每 100 tick 会按身体改回去，所以和指令放在同一刻），再叫测试队伍表来人。
        engine().update(player, record -> record.toBuilder().setTrueQi(true)
                .setOpenedMeridians(TRUE_MANY_OPENED).build());
        return run(context, strikeCommand(player) + " " + PARTY) > 0;
    }

    private static void assertRaider(GameTestHelper context, ServerPlayer player, Mob raider) {
        // 真炁档、打了标签、登记成盯这名玩家；离玩家 24–40 格（随从散开不到半格）。
        String who = raider.getType() + " " + raider.getUUID();
        check(context, HumanoidTier.of(snapshot(raider)) == HumanoidTier.TRUE_QI, who + " 不是真炁");
        check(context, raider.entityTags().contains(CrisisArrivals.RAIDER_TAG), who + " 没打标签");
        check(context, CrisisRaiders.hunting(raider) && CrisisRaiders.quarry(raider).orElse(null) == player,
                who + " 没登记成盯这名玩家");
        double distance = Math.hypot(raider.getX() - player.getX(), raider.getZ() - player.getZ());
        check(context, distance >= MIN_DISTANCE - SPOT_TOLERANCE && distance <= MAX_DISTANCE + SPOT_TOLERANCE,
                who + " 离玩家 " + distance + " 格");
    }

    private static void rejectBystander(GameTestHelper context, CrisisTestPlayer fixture, List<Mob> raiders) {
        // 全都盯上了玩家；原版要它改打旁边的村民，改不过去；收拾干净。
        var bystander = context.spawn(EntityTypes.VILLAGER, new BlockPos(1, 1, 1));
        Mob leader = raiders.getFirst();
        leader.setTarget(bystander);
        check(context, leader.getTarget() == fixture.player(), "派来的头目改去打了别人：" + leader.getTarget());
        bystander.discard();
        raiders.forEach(Mob::discard);
        finish(context, fixture);
    }

    static BodyMessages.BodySnapshot snapshot(Mob mob) {
        // 技法宿主里这只的身体。
        return HumanoidTechniqueHost.prepare(mob).body().snapshot();
    }

    static long opened(Mob mob) {
        // 开通了几条经脉。
        return snapshot(mob).getMeridianStatesList().stream()
                .filter(state -> state.getOpening().getStage() == MeridianOpeningStage.OPENED)
                .count();
    }

    static boolean knows(Mob mob, String volume) {
        // 这卷里的每一式都在已学列表里。
        List<TechniqueReference> known = TechniqueState.read(BodyProtobuf.fromMessage(snapshot(mob))).getKnownList()
                .stream().map(technique -> technique.getTechnique()).toList();
        return TechniqueFabric.service().registry().findVolume(volume).orElseThrow().getTechniquesList().stream()
                .allMatch(known::contains);
    }

    static String leaderName(Mob leader) {
        // 头目名字是翻译键，文字在语言文件里。
        if (leader.getCustomName() != null
                && leader.getCustomName().getContents() instanceof TranslatableContents translatable) {
            return translatable.getKey();
        }
        return "";
    }
}
