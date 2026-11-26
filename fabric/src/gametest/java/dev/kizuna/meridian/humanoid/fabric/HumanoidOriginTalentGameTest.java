package dev.kizuna.meridian.humanoid.fabric;

import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.attached;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.check;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.finish;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.run;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.waitUntil;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.kizuna.meridian.humanoid.server.CrisisHeat;
import dev.kizuna.meridian.humanoid.server.CrisisFeuds;
import dev.kizuna.meridian.humanoid.server.CrisisRules;
import dev.kizuna.meridian.humanoid.server.CrisisOutcome;
import dev.kizuna.meridian.humanoid.server.CrisisSchedule;
import dev.kizuna.meridian.humanoid.server.CrisisParty;
import dev.kizuna.meridian.origin.fabric.Origin;
import dev.kizuna.meridian.origin.fabric.Origins;
import dev.kizuna.meridian.origin.fabric.OriginNetworking;
import dev.kizuna.meridian.origin.protocol.pb.OriginMessages.OriginChoice;
import dev.kizuna.meridian.origin.server.OriginDraw;
import dev.kizuna.meridian.origin.server.OriginRecords;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.locale.Language;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;

/** 重生的选择、常驻授权、仇家，以及数据包天赋的授予和校验。 */
public final class HumanoidOriginTalentGameTest {
    private static final int TIMEOUT = 200;
    private static final int BREATHING_TIMEOUT = 600;
    private static final String NAMESPACE = "meridian_humanoid";
    private static final String REBORN = NAMESPACE + ":reborn";
    private static final String REBORN_VOLUME = "meridian_basic_techniques:later_breathing_volume";
    private static final String DOOR_BREAKER_UPPER = "meridian_door_breaker:upper";
    private static final int REPUTATION_BONUS = 20;
    private static final BlockPos VILLAGER_AT = new BlockPos(1, 2, 1);
    private static final long SEED = 1;
    private static final int FIRST_MIN = 6;
    private static final int FIRST_MAX = 10;
    private static final int AGAIN_MIN = 12;
    private static final int AGAIN_MAX = 16;
    private static final int SECOND_ROSTER = 2;
    private static final int FINAL_ROSTER = 4;

    @GameTest(maxTicks = BREATHING_TIMEOUT)
    public void firstRebornChoiceGrantsItsVolumeAndRejoinRetainsIt(GameTestHelper context) {
        // 出身必须在真实候选里，选择包只能发一次；再次登录保留这卷并且不重复记账。
        var fixture = offeredReborn(context);
        var player = fixture.player();
        waitUntil(context, () -> attached(player), () -> {
            var before = OriginRecords.read(CrisisGameTests.body(player));
            check(context, before.getOfferedList().contains(REBORN), "新生玩家候选里没有重生");
            check(context, !CrisisVolumes.known(player.getUUID()).contains(REBORN_VOLUME), "未选出身已获专卷");
            var choice = OriginChoice.newBuilder().setId(REBORN).build();
            player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(
                    OriginNetworking.CHOOSE.payload(choice.toByteArray())));
            var selected = OriginRecords.read(CrisisGameTests.body(player));
            check(context, selected.getChosen().equals(REBORN), "首次选择未保存");
            check(context, CrisisVolumes.known(player.getUUID()).contains(REBORN_VOLUME), "选择重生没有学卷");
            long unpracticed = CrisisGameTests.engine().record(player).orElseThrow().getFeuds(0).getDueAt();
            RebornFirstBreath.exercise(context, fixture, () -> {
                var practicing = CrisisGameTests.engine().record(player).orElseThrow().getFeuds(0);
                check(context, practicing.getDueAt() == unpracticed, "持续吐纳改动了同一玩家的仇家日程");
                fixture.close();
                var second = fixture.rejoin(context);
                waitUntil(context, () -> attached(second.player()), () -> {
                    var returned = OriginRecords.read(CrisisGameTests.body(second.player()));
                    check(context, returned.getGrantedList().equals(selected.getGrantedList()), "重登重复记账");
                    check(context, CrisisVolumes.known(second.player().getUUID()).contains(REBORN_VOLUME),
                            "重登丢卷");
                    finish(context, second);
                });
            });
        });
    }

    private static CrisisTestPlayer offeredReborn(GameTestHelper context) {
        // 筛选第一张就能抽到重生的身份，不改权重、不覆盖候选记录。
        var candidates = Origins.all().values().stream()
                .map(origin -> new OriginDraw.Candidate(origin.id().toString(), origin.weight(), origin.group()))
                .toList();
        long world = context.getLevel().getServer().overworld().getSeed();
        check(context, Origins.all().containsKey(Identifier.parse(REBORN)), "未加载重生出身");
        for (int attempt = 0; attempt < TIMEOUT; attempt++) {
            var id = UUID.randomUUID();
            long seed = OriginDraw.seed(world, id, 0);
            if (OriginDraw.draw(candidates, 1, seed).contains(REBORN)) {
                return CrisisTestPlayer.create(context, id);
            }
        }
        throw new AssertionError("未找到重生候选身份");
    }

    @GameTest(maxTicks = TIMEOUT)
    public void existingOriginsAndPoolCannotGrantRebornVolume(GameTestHelper context) {
        // 每个测试出身走真实授卷入口；卷池中入门和进阶两个位置都不能出现专卷。
        var fixture = CrisisTestPlayer.create(context);
        var player = fixture.player();
        waitUntil(context, () -> attached(player), () -> {
            for (String origin : List.of("orphan", "trained", "respected")) {
                check(context, choose(context, player, origin) > 0, "无法定出身 " + origin);
                check(context, !CrisisVolumes.known(player.getUUID()).contains(REBORN_VOLUME),
                        origin + " 得到专卷");
            }
            var pool = HumanoidBrain.config().volumePool();
            for (var book : pool.books()) {
                check(context, !book.upper().contains(REBORN_VOLUME), "专卷进入入门卷池");
                check(context, book.middle().filter(REBORN_VOLUME::equals).isEmpty(), "专卷进入进阶卷池");
            }
            var reborn = Origins.all().get(Identifier.parse(REBORN));
            var entries = CrisisOrigins.entries(reborn);
            check(context, reborn.threats().size() == 1 && entries.size() == 1
                    && entries.getFirst().threat().party().equals("meridian_humanoid:old_register")
                    && missingText(reborn).isEmpty(),
                    "重生数据或文本不完整");
            finish(context, fixture);
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void rebornFeudReturnsAfterEveryEndingAndCapsItsRoster(GameTestHelper context) {
        var origin = Origins.all().get(Identifier.parse(REBORN));
        var entries = CrisisOrigins.entries(origin);
        var empty = dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord.getDefaultInstance();
        var baseline = CrisisFeuds.start(CrisisFeuds.reconcile(empty, entries), entries, SEED);
        var first = baseline.getFeuds(0);
        long day = CrisisRules.DAY_TICKS;
        check(context, first.getDueAt() >= FIRST_MIN * day && first.getDueAt() <= FIRST_MAX * day, "首拨日期不符");
        var at = baseline.toBuilder().setOnlineTicks(first.getDueAt()).build();
        var rules = CrisisGameTests.engine().rules();
        var scheduled = CrisisFeuds.due(at, entries, rules).orElseThrow();
        var pending = CrisisSchedule.schedule(at, scheduled, 1);
        check(context, pending.getHeat() == 0 && pending.getOpenedMeridians() == 0, "初始风声或脉数不为零");
        var generic = new CrisisSchedule.Raid(rules.genericParty(), "", 0, first.getDueAt());
        var busy = CrisisSchedule.schedule(at, generic, 1);
        check(context, CrisisFeuds.due(busy, entries, rules).isEmpty(), "仇家挤占通用 pending");
        for (var result : List.of(CrisisOutcome.Settlement.win(), CrisisOutcome.Settlement.escape(),
                CrisisOutcome.Settlement.loss(com.google.protobuf.ByteString.EMPTY))) {
            var after = CrisisOutcome.settle(pending, result, entries, rules, SEED);
            var next = after.getFeuds(0);
            long gap = next.getDueAt() - at.getOnlineTicks();
            check(context, gap >= AGAIN_MIN * day && gap <= AGAIN_MAX * day, "续拨日期不符 " + result.ending());
            var later = after.toBuilder().setOnlineTicks(next.getDueAt()).build();
            check(context, !next.getFinished() && CrisisFeuds.due(later, entries, rules).isPresent(),
                    "赢逃输后必须还有下一拨 " + result.ending());
        }
        checkRoster(context, CrisisFabric.parties().get(scheduled.party()));
        context.succeed();
    }

    private static void checkRoster(GameTestHelper context, CrisisParty party) {
        for (var band : CrisisHeat.Band.values()) {
            var random = new SplittableRandom(SEED);
            var first = party.lineup(band, 0, random);
            var middle = party.lineup(band, SECOND_ROSTER, random);
            var last = party.lineup(band, FINAL_ROSTER, random);
            check(context, first.maxSize() < middle.maxSize() && middle.maxSize() < last.maxSize(), "档内没有增援");
            check(context, last.equals(party.lineup(band, Integer.MAX_VALUE, random)), "最高拨阵容未封顶");
            if (band == CrisisHeat.Band.TRUE_MANY) {
                check(context, last.maxSize() == FINAL_ROSTER && last.leader().elite(), "多脉末档阵容不符");
            }
        }
    }

    @GameTest(maxTicks = TIMEOUT)
    public void startingVolumeGrantsOnce(GameTestHelper context) {
        // 定成授卷出身：马上会破门斩木门卷；再定一次不出错，还是会这一卷。
        CrisisTestPlayer fixture = CrisisTestPlayer.create(context);
        ServerPlayer player = fixture.player();
        waitUntil(context, () -> attached(player), () -> {
            check(context, !CrisisVolumes.known(player.getUUID()).contains(DOOR_BREAKER_UPPER), "还没定出身就会了木门卷");
            check(context, choose(context, player, "trained") > 0, "出身调试指令没有成功");
            check(context, CrisisVolumes.known(player.getUUID()).contains(DOOR_BREAKER_UPPER), "定了授卷出身没会木门卷");
            check(context, choose(context, player, "trained") > 0, "再定一次出身没有成功");
            check(context, CrisisVolumes.known(player.getUUID()).contains(DOOR_BREAKER_UPPER), "再定一次以后木门卷没了");
            finish(context, fixture);
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void standingTalentChangesReputation(GameTestHelper context) {
        // 同一个村民：定成无声望天赋出身声望不变，改定声望天赋出身以后多 20。
        CrisisTestPlayer fixture = CrisisTestPlayer.create(context);
        ServerPlayer player = fixture.player();
        Villager villager = context.spawn(EntityTypes.VILLAGER, VILLAGER_AT);
        waitUntil(context, () -> attached(player), () -> {
            int before = villager.getPlayerReputation(player);
            check(context, choose(context, player, "orphan") > 0, "出身调试指令没有成功");
            check(context, villager.getPlayerReputation(player) == before, "无声望天赋出身也加了村民声望");
            check(context, choose(context, player, "respected") > 0, "出身调试指令没有成功");
            int after = villager.getPlayerReputation(player);
            check(context, after == before + REPUTATION_BONUS, "声望天赋出身的村民声望：" + before + " → " + after);
            finish(context, fixture);
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void badTalentParamsAreRejected(GameTestHelper context) {
        // 开局卷漏写卷，声望超出上下限或漏写，都在读出身数据时拒。
        check(context, parsesVolume("{\"volume\":\"meridian_door_breaker:upper\"}"), "写对的开局卷读不出来");
        check(context, !parsesVolume("{}"), "漏写卷的开局卷没被拒");
        check(context, parsesStanding("{\"reputation\":20}"), "写对的声望读不出来");
        check(context, !parsesStanding("{\"reputation\":101}"), "超出上限的声望没被拒");
        check(context, !parsesStanding("{\"reputation\":-101}"), "超出下限的声望没被拒");
        check(context, !parsesStanding("{}"), "漏写的声望没被拒");
        context.succeed();
    }

    private static int choose(GameTestHelper context, ServerPlayer player, String origin) {
        // 走出身附属的管理指令定出身，和玩家选定一样发一次性天赋。
        String id = "meridian_humanoid_test:" + origin;
        return run(context, "meridian debug origin set " + player.getScoreboardName() + " " + id);
    }

    private static List<String> missingText(Origin origin) {
        // 出身卡读的键：名字、来历、天赋第 1..n 行、麻烦第 1..n 行。
        String key = "origin." + origin.id().getNamespace() + "." + origin.id().getPath();
        List<String> keys = new ArrayList<>(List.of(key + ".name", key + ".story"));
        for (int line = 1; line <= origin.talents().size(); line++) {
            keys.add(key + ".talent." + line);
        }
        for (int line = 1; line <= origin.threats().size(); line++) {
            keys.add(key + ".threat." + line);
        }
        return keys.stream().filter(candidate -> !Language.getInstance().has(candidate)).toList();
    }

    private static boolean parsesVolume(String json) {
        // 开局卷的参数解码成没成。
        return OriginTalents.VOLUME.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).isSuccess();
    }

    private static boolean parsesStanding(String json) {
        // 声望的参数解码成没成。
        return OriginTalents.STANDING.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).isSuccess();
    }
}
