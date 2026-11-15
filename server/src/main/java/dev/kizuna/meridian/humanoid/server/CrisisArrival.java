package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Pending;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 来人现身的规矩：什么时辰来、给哪一档的阵容、头目带不带仇家的卷，来了以后记录怎么记；只改记录，不碰世界。 */
public final class CrisisArrival {
    /** 一天里黄昏开始的时刻（日落）和夜里结束的时刻（天亮前），按原版一天 24000 tick 算。 */
    public static final long DUSK = 12000;
    public static final long DAWN = 23000;

    private CrisisArrival() {
        // 纯规则，不持有状态。
    }

    public static boolean nightfall(long clockTime) {
        // 黄昏到天亮前都算；时钟可以跨好几天，按一天取余。
        long time = Math.floorMod(clockTime, CrisisRules.DAY_TICKS);
        return time >= DUSK && time < DAWN;
    }

    public static CrisisHeat.Band band(CrisisRecord record, CrisisRules.Strength strength) {
        // 按记录里最近一次看到的身体分档，和排场时看的是同一份。
        return CrisisHeat.band(new CrisisHeat.Observation(record.getTrueQi(), record.getOpenedMeridians()), strength);
    }

    public static long seed(long playerSeed, Pending pending) {
        // 选阵容、随从个数、落脚点都从这一个种子出，同一场重试时结果不变。
        return playerSeed * 31 + pending.getStrikeAt();
    }

    public static Optional<String> foeVolume(Pending pending, List<CrisisFeuds.Entry> entries) {
        // 出身的仇家才带卷：最后一拨（不限拨数时每一拨）的头目会 foe_volume；通用招敌不带。
        return entries.stream().filter(entry -> entry.key().equals(pending.getFeud())).findFirst()
                .map(CrisisFeuds.Entry::threat)
                .filter(threat -> threat.stages() == 0 || pending.getStage() >= threat.stages() - 1)
                .flatMap(CrisisThreat::foeVolume);
    }

    public static CrisisRecord strikeNow(CrisisRecord record) {
        // 调试指令立刻来人：来人的时刻提到现在；还没到点的征兆时刻一并压到这一刻（记录要求征兆不晚于来人），
        // 没给完的不再补，状态里仍看得出原本排了几次。
        Pending pending = record.getPending();
        long strikeAt = Math.min(pending.getStrikeAt(), record.getOnlineTicks());
        List<Long> omens = pending.getOmenAtList().stream().map(at -> Math.min(at, strikeAt)).toList();
        return record.toBuilder().setPending(pending.toBuilder().setStrikeAt(strikeAt).clearOmenAt()
                .addAllOmenAt(omens)).build();
    }

    /**
     * 现身的头目，结局时照这份记仇。
     *
     * @param entity 这一身的实体 UUID
     * @param species 实体 ID
     * @param tier 出生的档位
     * @param volumes 现身时会的卷
     * @param grudge 是不是记仇的人带队回来
     */
    public record Leader(UUID entity, String species, HumanoidTier tier, List<String> volumes, boolean grudge) { }

    public static CrisisRecord arrived(CrisisRecord record, Leader leader, List<UUID> raiders) {
        // 记下来的时刻、头目（连同物种、档位、会的卷）和全部来人（头目在内）；玩家此刻算被看见，逃脱从这里起算。
        long now = record.getOnlineTicks();
        Pending.Builder pending = record.getPending().toBuilder().setArrived(true).setArrivedAt(now)
                .setLeader(leader.entity().toString()).setLastSeenAt(now).clearRaiders()
                .setLeaderSpecies(leader.species()).setLeaderTier(leader.tier().id()).clearLeaderVolumes()
                .addAllLeaderVolumes(leader.volumes()).setGrudgeLeads(leader.grudge());
        raiders.forEach(raider -> pending.addRaiders(raider.toString()));
        return record.toBuilder().setPending(pending).build();
    }
}
