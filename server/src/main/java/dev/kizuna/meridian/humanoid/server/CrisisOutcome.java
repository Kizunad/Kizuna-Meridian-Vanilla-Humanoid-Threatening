package dev.kizuna.meridian.humanoid.server;

import com.google.protobuf.ByteString;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Grudge;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Pending;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 来了人以后怎么了结：赢（头目死了）、逃（甩开了或撑过了期限）、输（死在来人手里）。
 * 风声留几成、仇家进度、记仇的人、赢了掉哪卷、记仇的人回来时什么样；只改记录，不碰世界。
 */
public final class CrisisOutcome {
    private static final long TICKS_PER_SECOND = 20;

    private CrisisOutcome() {
        // 纯规则，不持有状态。
    }

    /** 三种结局。 */
    public enum Ending { WIN, ESCAPE, LOSS }

    /**
     * 一场的了结。
     *
     * @param ending 结局
     * @param taken 输了被头目夺走的那件（原版物品存档的 NBT 二进制），没有为空
     */
    public record Settlement(Ending ending, ByteString taken) {
        // 三种结局各一个入口，赢和逃不夺东西。
        public static Settlement win() {
            // 头目死了。
            return new Settlement(Ending.WIN, ByteString.EMPTY);
        }

        public static Settlement escape() {
            // 甩开了、撑过了期限，或者下线了。
            return new Settlement(Ending.ESCAPE, ByteString.EMPTY);
        }

        public static Settlement loss(ByteString taken) {
            // 死在来人手里；轻档什么也不拿时 taken 为空。
            return new Settlement(Ending.LOSS, taken);
        }
    }

    /**
     * 记仇的人带队回来的样子。
     *
     * @param leader 换上去的头目：物种、档位、精锐与否
     * @param volumes 除精锐该会的以外，它还要会的卷
     */
    public record Return(CrisisParty.Member leader, List<String> volumes) { }

    public static CrisisRecord settle(CrisisRecord record, Settlement settlement, List<CrisisFeuds.Entry> entries,
                                      CrisisRules rules, long seed) {
        // 风声赢了留 kept_after_win、逃了输了留 kept_after_escape；记一场了结；仇家的这一拨往前走；
        // 赢了记仇的人了断，逃了、输了头目记仇；排着的那一场清掉。
        Pending pending = record.getPending();
        if (pending.getRoadRequest() > 0) {
            // 轻遭遇的间隔单独计算，完整来犯的风声和记仇者保持原值。
            return record.toBuilder()
                    .setRoadCount(Math.incrementExact(record.getRoadCount()))
                    .setRoadSettledAt(record.getOnlineTicks())
                    .clearPending()
                    .build();
        }
        boolean won = settlement.ending() == Ending.WIN;
        CrisisRecord settled = CrisisHeat.keep(record, won ? rules.heat().keptAfterWin()
                : rules.heat().keptAfterEscape());
        if (!pending.getFeud().isEmpty()) {
            settled = CrisisFeuds.settle(settled, pending.getFeud(), settlement.ending(), entries, seed);
        }
        CrisisRecord.Builder builder = settled.toBuilder().setSettledCount(record.getSettledCount() + 1)
                .setSettledAt(record.getOnlineTicks()).clearPending();
        if (won) {
            builder.clearGrudge();
        } else {
            builder.setGrudge(grudge(record, settlement.taken()));
        }
        return builder.build();
    }

    static Grudge grudge(CrisisRecord record, ByteString taken) {
        // 带队的就是记仇的人：交手次数 +1，身上的东西留着；不然这一场的头目从头记起。
        // 物种、档位、会的卷和这一身都照来人时记下的，夺走的那件接在身上原有的后面。
        Pending pending = record.getPending();
        Grudge.Builder grudge = pending.getGrudgeLeads() ? record.getGrudge().toBuilder()
                : Grudge.newBuilder().setParty(pending.getParty()).setFeud(pending.getFeud());
        grudge.setEncounters(grudge.getEncounters() + 1).setSpecies(pending.getLeaderSpecies())
                .setTier(pending.getLeaderTier()).clearVolumes().addAllVolumes(pending.getLeaderVolumesList())
                .setEntity(pending.getLeader());
        if (!taken.isEmpty()) {
            grudge.addCarried(taken);
        }
        return grudge.build();
    }

    public static boolean escaped(Pending pending, long now, double nearestRaider, CrisisRules.Outcome outcome) {
        // 离所有来人都有 escape_distance 远、并且 escape_unseen_seconds 没被看见；或者从来人起撑过了 escape_time_limit_days。
        boolean unseen = now - pending.getLastSeenAt() >= outcome.escapeUnseenSeconds() * TICKS_PER_SECOND;
        boolean outlasted = now - pending.getArrivedAt() >= outcome.escapeTimeLimitDays() * CrisisRules.DAY_TICKS;
        return unseen && nearestRaider >= outcome.escapeDistance() || outlasted;
    }

    public static CrisisRecord seen(CrisisRecord record) {
        // 被来人看见了：逃脱从这一刻重新计时。
        Pending pending = record.getPending().toBuilder().setLastSeenAt(record.getOnlineTicks()).build();
        return record.toBuilder().setPending(pending).build();
    }

    public static Optional<String> spoils(Pending pending, Optional<String> foeVolume, Predicate<String> rare,
                                          long seed) {
        // 赢了必掉一卷：仇家最后一拨的头目带的 foe_volume；不然在头目会的稀有卷里按种子挑一卷；都没有就不掉。
        if (foeVolume.isPresent()) {
            return foeVolume;
        }
        List<String> candidates = pending.getLeaderVolumesList().stream().filter(rare).toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(candidates.get(CrisisSchedule.random(seed).nextInt(candidates.size())));
    }

    public static Return returning(Grudge grudge, CrisisParty.Member lineupLeader, VolumeTable table) {
        // 物种用它的；档位取它和这一档阵容头目里高的；会它记着的卷，再多一卷：本物种该档学卷表里按顺序第一卷还不会的。
        // 阵容头目是精锐的照样精锐；没有可多的卷、又是真炁，就精锐出生（精锐会该档全部卷），每回来一次都更难缠。
        HumanoidTier remembered = HumanoidTier.fromId(grudge.getTier()).orElseThrow(
                () -> HumanoidErrors.invalid(HumanoidErrors.CRISIS_RECORD + ": 记仇的人档位 " + grudge.getTier()));
        HumanoidTier tier = remembered.compareTo(lineupLeader.tier()) >= 0 ? remembered : lineupLeader.tier();
        List<String> volumes = new ArrayList<>(grudge.getVolumesList());
        Optional<String> extra = table.listed(grudge.getSpecies(), tier).stream()
                .filter(volume -> !volumes.contains(volume))
                .findFirst();
        extra.ifPresent(volumes::add);
        boolean elite = lineupLeader.elite() || extra.isEmpty() && tier == HumanoidTier.TRUE_QI;
        return new Return(new CrisisParty.Member(grudge.getSpecies(), tier, elite), List.copyOf(volumes));
    }
}
