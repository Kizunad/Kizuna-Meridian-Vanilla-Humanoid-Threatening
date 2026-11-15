package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** 风声怎么涨、怎么落，玩家在线时间怎么走；只改记录，不碰世界。 */
public final class CrisisHeat {
    private CrisisHeat() {
        // 纯规则，不持有状态。
    }

    /** 从玩家身体读到的两件事：是不是真炁、开了几条脉。 */
    public record Observation(boolean trueQi, int openedMeridians) { }

    /** 玩家所在的档，决定来的人有多强。 */
    public enum Band {
        // 游炁（含还没开脉的）、真炁开脉少、真炁开脉多。
        WANDERING, TRUE_FEW, TRUE_MANY;

        public String id() {
            // 队伍表里写小写名，如 true_many。
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<Band> fromId(String id) {
            // 只认小写名；认不出为空，由调用方报错。
            return Arrays.stream(values()).filter(band -> band.id().equals(id)).findFirst();
        }
    }

    public static CrisisRecord advance(CrisisRecord record, long ticks, CrisisRules.Decay decay) {
        // 在线时间往前走，顺带把该落的风声落掉。
        if (ticks < 0) {
            throw HumanoidErrors.invalid(HumanoidErrors.CRISIS_RECORD + ": 在线时间不能倒退");
        }
        return decay(record.toBuilder().setOnlineTicks(record.getOnlineTicks() + ticks).build(), decay);
    }

    public static CrisisRecord observe(CrisisRecord record, Observation body, CrisisRules.Rise rise) {
        // 第一次读到身体只记下来；之后头一次突破到真炁、头一次开脉开到某个节点才涨，掉回去再开回来不重复涨。
        int opened = Math.max(record.getOpenedMeridians(), body.openedMeridians());
        boolean trueQi = record.getTrueQi() || body.trueQi();
        CrisisRecord seen = record.toBuilder().setBodySeen(true).setTrueQi(trueQi).setOpenedMeridians(opened).build();
        if (!record.getBodySeen()) {
            return seen;
        }
        double gain = 0;
        if (trueQi && !record.getTrueQi()) {
            gain += rise.trueQiBreakthrough();
        }
        for (int node : rise.meridianNodes()) {
            if (record.getOpenedMeridians() < node && opened >= node) {
                gain += rise.meridianNode();
            }
        }
        return raise(seen, gain);
    }

    public static CrisisRecord raise(CrisisRecord record, double amount) {
        // 涨风声并记下时刻，往下落从这时重新起算；涨 0 不算一件事。
        if (!(amount >= 0) || Double.isInfinite(amount)) {
            throw HumanoidErrors.invalid(HumanoidErrors.CRISIS_RECORD + ": 风声涨幅必须是不为负的有限数值");
        }
        if (amount == 0) {
            return record;
        }
        long now = record.getOnlineTicks();
        return record.toBuilder().setHeat(record.getHeat() + amount).setHeatRaisedAt(now).setHeatDecayedAt(now)
                .build();
    }

    public static CrisisRecord keep(CrisisRecord record, double share) {
        // 一场了结后风声只留几成；留下的部分照常从现在起等着落。
        long now = record.getOnlineTicks();
        return record.toBuilder().setHeat(record.getHeat() * share).setHeatRaisedAt(now).setHeatDecayedAt(now)
                .build();
    }

    static CrisisRecord decay(CrisisRecord record, CrisisRules.Decay decay) {
        // 最近一次涨以后满 afterDays 天才开始落；之后每满一天落 perDay，落到 0 为止。
        long start = Math.max(record.getHeatRaisedAt() + decay.afterDays() * CrisisRules.DAY_TICKS,
                record.getHeatDecayedAt());
        long days = (record.getOnlineTicks() - start) / CrisisRules.DAY_TICKS;
        if (record.getOnlineTicks() < start || days == 0) {
            return record;
        }
        double heat = Math.max(0, record.getHeat() - days * decay.perDay());
        return record.toBuilder().setHeat(heat).setHeatDecayedAt(start + days * CrisisRules.DAY_TICKS).build();
    }

    public static Band band(Observation body, CrisisRules.Strength strength) {
        // 游炁（包括还没开脉的）一档；真炁按开脉数分少和多。
        if (!body.trueQi()) {
            return Band.WANDERING;
        }
        if (body.openedMeridians() >= strength.trueManyMeridians()) {
            return Band.TRUE_MANY;
        }
        return Band.TRUE_FEW;
    }

    public static boolean heatRaidDue(CrisisRecord record, CrisisRules rules) {
        // 开着总开关、没有排着的一场、风声过线、过了保护期和最短间隔，游炁玩家还要脉开到快突破。
        boolean ready = record.getTrueQi()
                || record.getOpenedMeridians() >= rules.frequency().wanderingReadyMeridians();
        return rules.enabled() && !record.hasPending() && record.getHeat() >= rules.heat().threshold()
                && ready && quiet(record, rules.frequency());
    }

    public static boolean quiet(CrisisRecord record, CrisisRules.Frequency frequency) {
        // 过了开局保护期；打过的话离上一场了结也过了最短间隔。通用招敌和出身的仇家都看这一条。
        long now = record.getOnlineTicks();
        if (now < frequency.protectionDays() * CrisisRules.DAY_TICKS) {
            return false;
        }
        return record.getSettledCount() == 0
                || now - record.getSettledAt() >= frequency.minIntervalDays() * CrisisRules.DAY_TICKS;
    }
}
