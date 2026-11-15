package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Feud;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/**
 * 出身的仇家怎么计日：出身里一条 crisis 参数就是一条仇家，选定出身并到了 min_realm 以后开始数天，到了日子排一场。
 * 只改记录，不碰世界。
 */
public final class CrisisFeuds {
    private CrisisFeuds() {
        // 纯规则，不持有状态。
    }

    /**
     * 玩家出身里的一条仇家。
     *
     * @param key 「出身 ID#下标」，下标是这条在出身的 crisis 威胁里的位置
     * @param threat 这条的参数
     */
    public record Entry(String key, CrisisThreat threat) { }

    public static String key(String origin, int index) {
        // 出身不能改，同一出身里第几条也不会变，所以用它认仇家。
        return origin + "#" + index;
    }

    public static CrisisRecord reconcile(CrisisRecord record, List<Entry> entries) {
        // 出身里有、记录里还没有的仇家补上，从第 0 拨、没开始计日起；记录里多出来的（数据包改过）留着，只是不再排。
        Set<String> known = record.getFeudsList().stream().map(Feud::getKey).collect(Collectors.toSet());
        List<Entry> missing = entries.stream().filter(entry -> !known.contains(entry.key())).toList();
        if (missing.isEmpty()) {
            return record;
        }
        CrisisRecord.Builder builder = record.toBuilder();
        missing.forEach(entry -> builder.addFeuds(Feud.newBuilder().setKey(entry.key())));
        return builder.build();
    }

    public static CrisisRecord start(CrisisRecord record, List<Entry> entries, long seed) {
        // 没开始计日、玩家已到 min_realm 的仇家开始数天：第一拨在 first_after_days 里按种子和仇家取一天。
        CrisisRecord.Builder builder = null;
        for (int index = 0; index < record.getFeudsCount(); index++) {
            Feud feud = record.getFeuds(index);
            Optional<Entry> entry = find(entries, feud.getKey());
            if (feud.getStarted() || entry.isEmpty() || !entry.get().threat().startsAt(record.getTrueQi())) {
                continue;
            }
            long days = days(entry.get().threat().firstAfterDays(),
                    CrisisSchedule.random(seed * 31 + feud.getKey().hashCode()));
            if (builder == null) {
                builder = record.toBuilder();
            }
            builder.setFeuds(index, feud.toBuilder().setStarted(true)
                    .setDueAt(record.getOnlineTicks() + days * CrisisRules.DAY_TICKS));
        }
        return builder == null ? record : builder.build();
    }

    public static Optional<CrisisSchedule.Raid> due(CrisisRecord record, List<Entry> entries, CrisisRules rules) {
        // 按出身里写的先后找第一条到了日子的，和通用招敌共用「同时只排一场」、保护期和最短间隔。
        // 来人的时刻是到期日，征兆提前 omen.days 天开始；被别的一场耽搁过的，从现在起留足征兆期。
        if (!rules.enabled() || record.hasPending() || !CrisisHeat.quiet(record, rules.frequency())) {
            return Optional.empty();
        }
        long now = record.getOnlineTicks();
        long omen = rules.omen().days() * CrisisRules.DAY_TICKS;
        for (Entry entry : entries) {
            Optional<Feud> feud = record.getFeudsList().stream()
                    .filter(candidate -> candidate.getKey().equals(entry.key())).findFirst();
            if (feud.isPresent() && feud.get().getStarted() && !feud.get().getFinished()
                    && now >= feud.get().getDueAt() - omen) {
                long strikeAt = Math.max(feud.get().getDueAt(), now + omen);
                return Optional.of(new CrisisSchedule.Raid(entry.threat().party(), entry.key(),
                        feud.get().getStage(), strikeAt));
            }
        }
        return Optional.empty();
    }

    public static CrisisRecord settle(CrisisRecord record, String key, CrisisOutcome.Ending ending,
                                      List<Entry> entries, long seed) {
        // 了结这条的一拨：赢了拨次 +1，打满 stages 就了断，不然下一拨在 interval_days 里按种子取一天；
        // 逃了、输了拨次不动，下一拨隔 interval_days 最少的天数再来。出身里已经没有这条（数据包改过）就不动。
        Optional<Entry> entry = find(entries, key);
        int index = indexOf(record, key);
        if (entry.isEmpty() || index < 0) {
            return record;
        }
        Feud feud = record.getFeuds(index);
        CrisisThreat threat = entry.get().threat();
        long days = threat.intervalDays().min();
        Feud.Builder next = feud.toBuilder();
        if (ending == CrisisOutcome.Ending.WIN) {
            int stage = feud.getStage() + 1;
            next.setStage(stage).setFinished(threat.stages() > 0 && stage >= threat.stages());
            days = days(threat.intervalDays(), CrisisSchedule.random(seed * 31 + key.hashCode()));
        }
        next.setDueAt(record.getOnlineTicks() + days * CrisisRules.DAY_TICKS);
        return record.toBuilder().setFeuds(index, next).build();
    }

    public static boolean ended(CrisisRecord before, CrisisRecord after, String key) {
        // 这条仇家在这一回了结里从没打完变成打完（进度「了断」只在这一刻给）。
        return !finished(before, key) && finished(after, key);
    }

    static boolean finished(CrisisRecord record, String key) {
        // 记录里有这条仇家并且已经打完。
        int index = indexOf(record, key);
        return index >= 0 && record.getFeuds(index).getFinished();
    }

    private static int indexOf(CrisisRecord record, String key) {
        // 记录里这条仇家的位置；没有为 -1。
        for (int index = 0; index < record.getFeudsCount(); index++) {
            if (record.getFeuds(index).getKey().equals(key)) {
                return index;
            }
        }
        return -1;
    }

    static long days(CrisisThreat.DayRange range, RandomGenerator random) {
        // 两头都含，均匀取整天。
        return range.min() + random.nextInt(range.max() - range.min() + 1);
    }

    private static Optional<Entry> find(List<Entry> entries, String key) {
        // 出身的 crisis 条数很少，直接找。
        return entries.stream().filter(entry -> entry.key().equals(key)).findFirst();
    }
}
