package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Pending;
import dev.kizuna.meridian.humanoid.server.CrisisRules;
import dev.kizuna.meridian.humanoid.server.CrisisSchedule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.Vec3;

/**
 * 征兆：到了一次征兆的时刻，玩家身边有村民就让村民说一句传言，该留字条时在床上或玩家身后放一张；
 * 记仇的人带队来时，走到玩家跟前、看得见玩家，先说一句认得玩家的话。说的话只发 {@link HumanoidSpeech} 事件。
 */
final class CrisisOmens {
    static final String NOTE_NAME = "item.meridian_humanoid.crisis_note";
    static final int RUMOR_LINES = 2;
    static final int NOTE_LINES = 3;
    private static final double BED_TOP = 0.6;
    private static final double GREET_RADIUS = 12;
    private static final int[] BEHIND_DISTANCES = {5, 4, 6};
    private static final int[] BEHIND_DEGREES = {0, 30, -30, 60, -60};
    private static final int[] GROUND_OFFSETS = {0, -1, 1, -2, 2, -3, 3};
    private final CrisisRules rules;
    private final Map<UUID, UUID> greeted = new HashMap<>();

    CrisisOmens(CrisisRules rules) {
        // 规则在启动时定死。
        this.rules = rules;
    }

    CrisisRecord due(ServerPlayer player, CrisisRecord record) {
        // 到了这一次的时刻才找村民；说了、留了或这一次过完了就记一次；字条留不下就下一步再试。
        if (!CrisisSchedule.omenDue(record) || !present(player)) {
            return record;
        }
        Optional<Villager> villager = villager(player);
        CrisisSchedule.OmenStep step = CrisisSchedule.omenStep(record, villager.isPresent());
        if (step == CrisisSchedule.OmenStep.SKIP || give(player, step, villager, record.getPending())) {
            return CrisisSchedule.omenDone(record, step);
        }
        return record;
    }

    boolean give(ServerPlayer player, CrisisSchedule.OmenStep kind, Optional<Villager> villager, Pending pending) {
        // 传言由村民说出口就算给了；字条放下去才算。给成了触发进度「风声」。
        boolean given = switch (kind) {
            case RUMOR -> rumor(player, villager.orElseThrow(), pending);
            case NOTE -> note(player, pending);
            case WAIT, SKIP -> false;
        };
        if (given) {
            CrisisTrigger.fire(player, CrisisTrigger.Event.OMEN);
        }
        return given;
    }

    static boolean present(ServerPlayer player) {
        // 活着、不是旁观的玩家才给征兆。
        return player.isAlive() && !player.isSpectator();
    }

    Optional<Villager> villager(ServerPlayer player) {
        // rumor_radius 格内醒着、看得见玩家的村民里挑最近的。
        double radius = rules.omen().rumorRadius();
        List<Villager> near = player.level().getEntitiesOfClass(Villager.class,
                player.getBoundingBox().inflate(radius), villager -> villager.isAlive() && !villager.isSleeping()
                        && villager.distanceToSqr(player) <= radius * radius && villager.hasLineOfSight(player));
        return near.stream().min(Comparator.comparingDouble(villager -> villager.distanceToSqr(player)));
    }

    private static boolean rumor(ServerPlayer player, Villager villager, Pending pending) {
        // 第几次征兆挑第几句传言，两句轮着说；村民转头看着玩家说。
        villager.getLookControl().setLookAt(player);
        int line = pending.getOmensGiven() % RUMOR_LINES + 1;
        Component text = Component.translatable(CrisisArrivals.textKey(pending.getParty(), "omen.rumor." + line));
        HumanoidSpeech.SPOKE.invoker().spoke(villager, player, text);
        return true;
    }

    private boolean note(ServerPlayer player, Pending pending) {
        // 床近就放床上，不然放玩家身后；地方都不合适这一回不放。字条掉在地上不会消失。
        ServerLevel level = player.level();
        Optional<Vec3> spot = bed(player).or(() -> behind(player));
        spot.ifPresent(at -> {
            ItemEntity dropped = new ItemEntity(level, at.x, at.y, at.z, note(pending.getParty()), 0, 0, 0);
            dropped.setUnlimitedLifetime();
            level.addFreshEntity(dropped);
        });
        return spot.isPresent();
    }

    static ItemStack note(String party) {
        // 原版纸，物品名是「字条」，正文按队伍表推的键分三行，灰色正体，像写上去的字。
        ItemStack paper = new ItemStack(Items.PAPER);
        paper.set(DataComponents.ITEM_NAME, Component.translatable(NOTE_NAME));
        List<Component> lines = new ArrayList<>();
        for (int line = 1; line <= NOTE_LINES; line++) {
            lines.add(Component.translatable(CrisisArrivals.textKey(party, "omen.note." + line))
                    .withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY)));
        }
        paper.set(DataComponents.LORE, new ItemLore(lines));
        return paper;
    }

    private Optional<Vec3> bed(ServerPlayer player) {
        // 重生点是床、和玩家在同一维度、note_bed_radius 格内、区块在：字条放在床上。
        ServerPlayer.RespawnConfig respawn = player.getRespawnConfig();
        ServerLevel level = player.level();
        if (respawn == null || respawn.respawnData().dimension() != level.dimension()) {
            return Optional.empty();
        }
        BlockPos bed = respawn.respawnData().pos();
        double radius = rules.omen().noteBedRadius();
        if (Vec3.atCenterOf(bed).distanceToSqr(player.position()) > radius * radius || !level.isLoaded(bed)
                || !level.getBlockState(bed).is(BlockTags.BEDS)) {
            return Optional.empty();
        }
        return Optional.of(Vec3.atBottomCenterOf(bed).add(0, BED_TOP, 0));
    }

    private static Optional<Vec3> behind(ServerPlayer player) {
        // 玩家身后几格、左右至多偏六十度，地上站得住、头顶两格空、和玩家脚下高差三格以内的地方。
        Vec3 back = Vec3.directionFromRotation(0, player.getYRot()).reverse();
        for (int distance : BEHIND_DISTANCES) {
            for (int degrees : BEHIND_DEGREES) {
                Vec3 offset = back.yRot((float) Math.toRadians(degrees)).scale(distance);
                Optional<BlockPos> ground = ground(player.level(), BlockPos.containing(player.position().add(offset)));
                if (ground.isPresent()) {
                    return ground.map(Vec3::atBottomCenterOf);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<BlockPos> ground(ServerLevel level, BlockPos column) {
        // 从玩家脚下这一层往上下两边找，第一处站得住的地方。
        for (int offset : GROUND_OFFSETS) {
            BlockPos at = column.above(offset);
            if (level.isLoaded(at) && CrisisArrivals.standable(level, at)) {
                return Optional.of(at);
            }
        }
        return Optional.empty();
    }

    void greet(ServerPlayer player, CrisisRecord record) {
        // 记仇的人带的这一场：头目走到 12 格内、看得见玩家时，说一句认得玩家的话；一个头目只说一次。
        if (!record.hasPending() || !record.getPending().getGrudgeLeads() || !record.hasGrudge()) {
            return;
        }
        UUID leader = UUID.fromString(record.getPending().getLeader());
        if (leader.equals(greeted.get(player.getUUID()))) {
            return;
        }
        if (player.level().getEntity(leader) instanceof Mob mob && mob.isAlive()
                && mob.distanceToSqr(player) <= GREET_RADIUS * GREET_RADIUS && mob.hasLineOfSight(player)) {
            greeted.put(player.getUUID(), leader);
            String key = CrisisArrivals.textKey(record.getGrudge().getParty(), "leader_return");
            HumanoidSpeech.SPOKE.invoker().spoke(mob, player, Component.translatable(key));
        }
    }

    void forget(UUID player) {
        // 下线时放掉说过话的头目。
        greeted.remove(player);
    }

    void clear() {
        // 停服时清空。
        greeted.clear();
    }
}
