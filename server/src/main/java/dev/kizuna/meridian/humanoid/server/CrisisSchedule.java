package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Pending;

import java.util.SplittableRandom;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** 排一场来犯：定来人的时刻、征兆的时刻，以及每次征兆该给什么；只改记录，不碰世界。 */
public final class CrisisSchedule {
    private CrisisSchedule() {
        // 纯规则，不持有状态。
    }

    /** 排哪一场：队伍表、属于哪条仇家（通用为空）、第几拨、什么时候来人。 */
    public record Raid(String party, String feud, int stage, long strikeAt) { }

    /** 到了一次征兆的时刻该怎么办：再等等、说传言、留字条、这次跳过。 */
    public enum OmenStep { WAIT, RUMOR, NOTE, SKIP }

    public static Raid heatRaid(CrisisRecord record, CrisisRules rules) {
        // 风声招来的一场用通用队伍表，排上以后过完征兆期来人。
        long strikeAt = record.getOnlineTicks() + rules.omen().days() * CrisisRules.DAY_TICKS;
        return new Raid(rules.genericParty(), "", 0, strikeAt);
    }

    public static CrisisRecord schedule(CrisisRecord record, Raid raid, int omenCount) {
        // 征兆在排上和来人之间均匀铺开：两次就在三分之一和三分之二处；来人的时刻已过的话就是现在。
        long now = record.getOnlineTicks();
        long strikeAt = Math.max(raid.strikeAt(), now);
        Pending.Builder pending = Pending.newBuilder().setParty(raid.party()).setFeud(raid.feud())
                .setStage(raid.stage()).setScheduledAt(now).setStrikeAt(strikeAt);
        for (int index = 1; index <= omenCount; index++) {
            pending.addOmenAt(now + (strikeAt - now) * index / (omenCount + 1));
        }
        return record.toBuilder().setPending(pending).build();
    }

    public static int omenCount(long seed, CrisisRules.Omen omen) {
        // 次数在 [最少, 最多] 里按种子取，同一种子同一结果。
        return omen.minCount() + random(seed).nextInt(omen.maxCount() - omen.minCount() + 1);
    }

    public static RandomGenerator random(long seed) {
        // 招敌的随机都从这里出：相邻的种子（第几场只差 1）也要抽得开，所以不用 java.util.Random；同一种子同一结果。
        return new SplittableRandom(seed);
    }

    public static long seed(long worldSeed, UUID player, int settledCount) {
        // 世界种子、玩家和第几场一起定种子：同一玩家每场不同，重开服不变。
        long mixed = worldSeed ^ player.getMostSignificantBits() * 31 ^ player.getLeastSignificantBits();
        return mixed * 1_000_003L + settledCount;
    }

    public static OmenStep omenStep(CrisisRecord record, boolean villagerNear) {
        // 到了这一次征兆的时刻才给：给得出就给，给不出就等，等到这一次过完还没碰上就跳过。
        if (!omenDue(record)) {
            return OmenStep.WAIT;
        }
        Pending pending = record.getPending();
        OmenStep kind = omenKind(pending, villagerNear);
        if (kind != OmenStep.WAIT) {
            return kind;
        }
        int index = pending.getOmensGiven();
        boolean last = index == pending.getOmenAtCount() - 1;
        long windowEnd = last ? pending.getStrikeAt() : pending.getOmenAt(index + 1);
        return record.getOnlineTicks() >= windowEnd ? OmenStep.SKIP : OmenStep.WAIT;
    }

    public static OmenStep omenKind(Pending pending, boolean villagerNear) {
        // 这一次给什么：一场只留一张字条，最后一次还没留过、或者身边没有村民时留；身边有村民就说传言；都不行就等。
        boolean last = pending.getOmensGiven() == pending.getOmenAtCount() - 1;
        if (!pending.getNoteGiven() && (last || !villagerNear)) {
            return OmenStep.NOTE;
        }
        return villagerNear ? OmenStep.RUMOR : OmenStep.WAIT;
    }

    public static boolean omenLeft(CrisisRecord record) {
        // 排着一场、还没来人、还有没给的征兆。
        return record.hasPending() && !record.getPending().getArrived()
                && record.getPending().getOmensGiven() < record.getPending().getOmenAtCount();
    }

    public static boolean omenDue(CrisisRecord record) {
        // 还有没给的征兆，并且到了下一次的时刻。
        return omenLeft(record)
                && record.getOnlineTicks() >= record.getPending().getOmenAt(record.getPending().getOmensGiven());
    }

    public static CrisisRecord omenDone(CrisisRecord record, OmenStep step) {
        // 给过（或跳过）一次就记一次；留了字条另记一笔。
        if (step == OmenStep.WAIT) {
            return record;
        }
        Pending.Builder pending = record.getPending().toBuilder();
        pending.setOmensGiven(pending.getOmensGiven() + 1);
        if (step == OmenStep.NOTE) {
            pending.setNoteGiven(true);
        }
        return record.toBuilder().setPending(pending).build();
    }

    public static boolean strikeDue(CrisisRecord record) {
        // 到点了还没来人；没给完的征兆不再补。
        return record.hasPending() && !record.getPending().getArrived()
                && record.getOnlineTicks() >= record.getPending().getStrikeAt();
    }
}
