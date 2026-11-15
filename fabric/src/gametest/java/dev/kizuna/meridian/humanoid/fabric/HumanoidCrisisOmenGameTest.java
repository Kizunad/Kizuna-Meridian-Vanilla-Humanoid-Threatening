package dev.kizuna.meridian.humanoid.fabric;

import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.COMMAND_FAILED;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.advanced;
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
import static dev.kizuna.meridian.humanoid.fabric.HumanoidCrisisOutcomeGameTest.spawnAt;

import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Grudge;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Pending;
import dev.kizuna.meridian.humanoid.server.CrisisRules;
import dev.kizuna.meridian.humanoid.server.CrisisSchedule;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

/**
 * 征兆：到了时刻身边有村民就由村民说传言、一场只留一张字条、最后一次必留；没有村民只留字条；
 * 床近字条放床上，不然放身后；字条不会消失；调试指令不等时刻、可以指定队伍表；记仇的人回来照面说一句认得玩家的话，只说一次。
 * 说的话从 {@link HumanoidSpeech} 事件里听。
 */
public final class HumanoidCrisisOmenGameTest {
    private static final int TIMEOUT = 600;
    private static final String COVETOUS = "meridian_humanoid:covetous";
    private static final String PARTY = "meridian_humanoid_test:feud";
    private static final String UNKNOWN_PARTY = "meridian_humanoid_test:nobody";
    private static final String RUMOR_FIRST = "crisis.meridian_humanoid.covetous.omen.rumor.1";
    private static final String LEADER_RETURN = "crisis.meridian_humanoid.covetous.leader_return";
    private static final long STRIKE_AT = 10 * CrisisRules.DAY_TICKS;
    private static final int OMENS = 2;
    private static final int UNLIMITED_AGE = -32768;
    private static final double IN_FRONT = 3;
    private static final double LEADER_NEAR = 5;
    private static final double NOTE_SEARCH = 8;
    private static final double ON_BED = 1;
    private static final int QUIET_STEPS = 2;
    private static final List<Spoken> HEARD = new CopyOnWriteArrayList<>();

    static {
        // 整个测试服只登记一次；各条用例按对着说的玩家分开看。
        HumanoidSpeech.SPOKE.register((speaker, addressee, line) ->
                HEARD.add(new Spoken(speaker.getUUID(), addressee.getUUID(), key(line))));
    }

    /** 听到的一句：谁说的、对谁说、哪个键。 */
    record Spoken(UUID speaker, UUID addressee, String key) {
        // 只用来比较。
    }

    @GameTest(maxTicks = TIMEOUT)
    public void withoutVillagersOnlyTheNoteIsLeft(GameTestHelper context) {
        // 身边没有村民：第一次留字条，在玩家身后的地上、不会消失；第二次到了时刻也只能等，不留第二张，调试指令也给不出。
        // 进度「风声」在第一张字条留下时到手，之前没有。
        CrisisTestPlayer fixture = CrisisTestPlayer.create(context);
        ServerPlayer player = fixture.player();
        standApart(context, player, 9);
        waitUntil(context, () -> attached(player), () -> {
            check(context, !advanced(player, "omen"), "还没有征兆就有了「风声」");
            engine().update(player, record -> omenTime(scheduled(record), 0));
            waitUntil(context, () -> pending(player).getOmensGiven() == 1, () -> {
                List<ItemEntity> notes = notesNear(context, player, COVETOUS);
                check(context, pending(player).getNoteGiven() && notes.size() == 1, "字条：" + notes);
                check(context, advanced(player, "omen"), "留了字条没给「风声」");
                check(context, notes.getFirst().getAge() == UNLIMITED_AGE, "字条会消失");
                check(context, behind(player, notes.getFirst().position()), "字条不在玩家身后");
                check(context, heard(player).isEmpty(), "没有村民却有人说了话：" + heard(player));
                engine().update(player, record -> omenTime(record, 1));
                context.runAfterDelay(QUIET_STEPS * CrisisEngine.STEP_TICKS, () -> {
                    check(context, pending(player).getOmensGiven() == 1
                            && notesNear(context, player, COVETOUS).size() == 1, "没有村民的第二次：" + pending(player));
                    check(context, run(context, omenCommand(player)) == COMMAND_FAILED, "调试指令又给了一次");
                    finish(context, fixture);
                });
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void villagerPassesTheRumorAndTheLastOmenIsTheNote(GameTestHelper context) {
        // 第一次身边站着村民：村民对玩家说第一句传言，不留字条，「风声」到手；最后一次村民还在也要留字条，不再说话。
        CrisisTestPlayer fixture = CrisisTestPlayer.create(context);
        ServerPlayer player = fixture.player();
        standApart(context, player, 10);
        waitUntil(context, () -> attached(player), () -> {
            Villager villager = spawnAt(context, EntityTypes.VILLAGER, inFront(player, IN_FRONT),
                    mob -> mob.setNoAi(true));
            engine().update(player, record -> omenTime(scheduled(record), 0));
            waitUntil(context, () -> pending(player).getOmensGiven() == 1, () -> {
                check(context, !pending(player).getNoteGiven() && notesNear(context, player, COVETOUS).isEmpty(),
                        "说传言的那一次留了字条");
                check(context, heard(player).equals(List.of(new Spoken(villager.getUUID(), player.getUUID(),
                        RUMOR_FIRST))), "传言：" + heard(player));
                check(context, advanced(player, "omen"), "听了传言没给「风声」");
                engine().update(player, record -> omenTime(record, 1));
                waitUntil(context, () -> pending(player).getOmensGiven() == OMENS, () -> {
                    check(context, pending(player).getNoteGiven() && notesNear(context, player, COVETOUS).size() == 1,
                            "最后一次没留字条：" + pending(player));
                    check(context, heard(player).size() == 1, "留字条那一次也说了话：" + heard(player));
                    finish(context, fixture);
                });
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void omenCommandPutsTheNoteOnTheNearbyBed(GameTestHelper context) {
        // 重生点是近处的床：调试指令不等时刻，立刻把字条留在床上；留过以后身边又没有村民，再给就说给不了；补全里有 omen。
        // 调试指令给的也算见到征兆，「风声」到手。事先没排着：指令照风声招来的那样排一场通用队伍表再给。
        CrisisTestPlayer fixture = CrisisTestPlayer.create(context);
        ServerPlayer player = fixture.player();
        standApart(context, player, 11);
        waitUntil(context, () -> attached(player), () -> {
            BlockPos bed = BlockPos.containing(inFront(player, IN_FRONT));
            context.getLevel().setBlock(bed, Blocks.BED.red().defaultBlockState(), Block.UPDATE_CLIENTS);
            var respawn = LevelData.RespawnData.of(context.getLevel().dimension(), bed, 0, 0);
            player.setRespawnPosition(new ServerPlayer.RespawnConfig(respawn, true /* forced */),
                    false /* showMessage */);
            check(context, !engine().record(player).orElseThrow().hasPending(), "开始前就排着一场");
            check(context, suggestions(context.getLevel().getServer().createCommandSourceStack(),
                    "meridian humanoid crisis ").contains("omen"), "补全里没有 omen");
            check(context, run(context, omenCommand(player)) > 0, "调试指令没给出征兆");
            List<ItemEntity> notes = notesNear(context, player, COVETOUS);
            check(context, notes.size() == 1 && notes.getFirst().getAge() == UNLIMITED_AGE, "字条：" + notes);
            check(context, notes.getFirst().position().distanceTo(Vec3.atBottomCenterOf(bed)) < ON_BED,
                    "字条不在床上：" + notes.getFirst().position());
            check(context, pending(player).getOmensGiven() == 1 && pending(player).getNoteGiven()
                    && pending(player).getParty().equals(COVETOUS) && pending(player).getOmenAtCount() >= OMENS,
                    "调试指令没排上、没记进记录：" + pending(player));
            check(context, advanced(player, "omen"), "调试指令留了字条没给「风声」");
            check(context, run(context, omenCommand(player)) == COMMAND_FAILED, "没有村民又给了一次");
            finish(context, fixture);
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void omenCommandCanNameTheParty(GameTestHelper context) {
        // omen 后面写队伍表：没有这张表就失败、什么也不排；有就排这张表的一场，字条按这张表推键；
        // 排着这张表时再写别的表失败；补全里有收下的队伍表。
        CrisisTestPlayer fixture = CrisisTestPlayer.create(context);
        ServerPlayer player = fixture.player();
        standApart(context, player, 13);
        waitUntil(context, () -> attached(player), () -> {
            String command = omenCommand(player);
            check(context, suggestions(context.getLevel().getServer().createCommandSourceStack(), command + " ")
                    .contains(PARTY), "补全里没有队伍表");
            check(context, run(context, command + " " + UNKNOWN_PARTY) == COMMAND_FAILED, "没有的队伍表也给了");
            check(context, !engine().record(player).orElseThrow().hasPending(), "没有的队伍表也排上了");
            check(context, run(context, command + " " + PARTY) > 0, "指定队伍表没给出征兆");
            check(context, pending(player).getParty().equals(PARTY) && pending(player).getOmensGiven() == 1,
                    "排的不是指定的队伍表：" + pending(player));
            check(context, notesNear(context, player, PARTY).size() == 1, "字条不是指定队伍表的");
            check(context, run(context, command + " " + COVETOUS) == COMMAND_FAILED, "排着别的队伍表也给了");
            finish(context, fixture);
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void returningGrudgeHolderGreetsThePlayerOnce(GameTestHelper context) {
        // 记仇的人带队回来：还没照面不说话；走到跟前、看得见玩家时说一句认得玩家的话，键照它原来的队伍表；只说一次。
        CrisisTestPlayer fixture = CrisisTestPlayer.create(context);
        ServerPlayer player = fixture.player();
        standApart(context, player, 12);
        player.getAbilities().invulnerable = true;
        player.onUpdateAbilities();
        waitUntil(context, () -> attached(player), () -> {
            engine().update(player, record -> record.toBuilder().setGrudge(Grudge.newBuilder()
                    .setSpecies("minecraft:vindicator").setTier(HumanoidTier.WANDERING_QI.id()).setParty(COVETOUS)
                    .setEncounters(1)).build());
            waitUntil(context, () -> run(context, strikeCommand(player) + " " + PARTY) > 0, () -> {
                Mob leader = raiders(context, player).getFirst();
                check(context, pending(player).getGrudgeLeads() && heard(player).isEmpty(), "还没照面就说了话");
                Vec3 near = inFront(player, LEADER_NEAR);
                leader.teleportTo(near.x, near.y, near.z);
                waitUntil(context, () -> !heard(player).isEmpty(), () -> {
                    check(context, heard(player).equals(List.of(new Spoken(leader.getUUID(), player.getUUID(),
                            LEADER_RETURN))), "记仇的人说的：" + heard(player));
                    context.runAfterDelay(QUIET_STEPS * CrisisEngine.STEP_TICKS, () -> {
                        check(context, heard(player).size() == 1, "说了不止一次：" + heard(player));
                        finish(context, fixture);
                    });
                });
            });
        });
    }

    private static CrisisRecord scheduled(CrisisRecord record) {
        // 从第 0 天排一场通用队伍表，第 10 天来人，两次征兆；测试里不会到来人的时刻。
        CrisisRecord start = record.toBuilder().setOnlineTicks(0).build();
        return CrisisSchedule.schedule(start, new CrisisSchedule.Raid(COVETOUS, "", 0, STRIKE_AT), OMENS);
    }

    private static CrisisRecord omenTime(CrisisRecord record, int index) {
        // 在线时间拨到第 index 次征兆的时刻。
        return record.toBuilder().setOnlineTicks(record.getPending().getOmenAt(index)).build();
    }

    private static Pending pending(ServerPlayer player) {
        // 引擎里这名玩家此刻排着的那一场。
        return engine().record(player).orElseThrow().getPending();
    }

    private static List<Spoken> heard(ServerPlayer player) {
        // 对这名玩家说过的话。
        return HEARD.stream().filter(spoken -> spoken.addressee().equals(player.getUUID())).toList();
    }

    private static String key(Component line) {
        // 翻译文本取键，别的取原文。
        return line.getContents() instanceof TranslatableContents translatable ? translatable.getKey()
                : line.getString();
    }

    private static Vec3 inFront(ServerPlayer player, double distance) {
        // 玩家脚下往脸朝的水平方向走几格。
        return player.position().add(Vec3.directionFromRotation(0, player.getYRot()).scale(distance));
    }

    private static boolean behind(ServerPlayer player, Vec3 position) {
        // 在玩家脸朝方向的反面半边。
        return Vec3.directionFromRotation(0, player.getYRot()).dot(position.subtract(player.position())) < 0;
    }

    private static List<ItemEntity> notesNear(GameTestHelper context, ServerPlayer player, String party) {
        // 玩家几格内地上这张队伍表的字条。
        return context.getLevel().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(NOTE_SEARCH),
                item -> isNote(item.getItem(), party));
    }

    private static boolean isNote(ItemStack stack, String party) {
        // 原版纸，物品名是字条的键，正文几行是这张队伍表推出来的键。
        ItemLore lore = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        List<String> expected = IntStream.rangeClosed(1, CrisisOmens.NOTE_LINES)
                .mapToObj(line -> CrisisArrivals.textKey(party, "omen.note." + line)).toList();
        Component name = stack.get(DataComponents.ITEM_NAME);
        return stack.is(Items.PAPER) && name != null && key(name).equals(CrisisOmens.NOTE_NAME)
                && lore.lines().stream().map(HumanoidCrisisOmenGameTest::key).toList().equals(expected);
    }

    private static String omenCommand(ServerPlayer player) {
        // 给这名玩家立刻来一次征兆。
        return "meridian humanoid crisis omen " + player.getScoreboardName();
    }
}
