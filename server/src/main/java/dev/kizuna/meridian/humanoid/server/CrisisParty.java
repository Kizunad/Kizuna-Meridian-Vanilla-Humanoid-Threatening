package dev.kizuna.meridian.humanoid.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 一张队伍表：按玩家所在的档和打赢到第几拨，写来哪几种生人、几个、谁是头目。
 * 队伍表是数据包内容（{@code data/<命名空间>/meridian_humanoid/crisis_party/<名字>.json}），这里只管形状和范围。
 *
 * @param lineups 各种阵容；每一档都要有从第 0 拨起的一种，打到哪一拨都排得出人
 */
public record CrisisParty(List<Lineup> lineups) {
    /** 来犯的只用灾民一方：掠夺者、卫道士、唤魔者、女巫。混进村民、行商会被阵营规则自己打起来。 */
    public static final Set<String> RAIDER_SPECIES = Set.of(HumanoidSpecies.PILLAGER.entityId(),
            HumanoidSpecies.VINDICATOR.entityId(), HumanoidSpecies.EVOKER.entityId(),
            HumanoidSpecies.WITCH.entityId());

    public CrisisParty {
        // 缺哪一档的第 0 拨阵容直接拒绝，免得某个玩家排上以后没人可派。
        lineups = List.copyOf(lineups);
        for (CrisisHeat.Band band : CrisisHeat.Band.values()) {
            if (lineups.stream().noneMatch(lineup -> lineup.band() == band && lineup.stage() == 0)) {
                throw new IllegalArgumentException("缺少档 " + band.id() + " 从第 0 拨起的阵容");
            }
        }
    }

    public void requireFits(int maxParty) {
        // 每种阵容最多来的人（头目加随从上限）不能超过配置的 strength.max_party。
        for (int index = 0; index < lineups.size(); index++) {
            int size = lineups.get(index).maxSize();
            if (size > maxParty) {
                throw new IllegalArgumentException("lineups[" + index + "] 最多来 " + size + " 人，超过 max_party "
                        + maxParty);
            }
        }
    }

    public Lineup lineup(CrisisHeat.Band band, int stage, RandomGenerator random) {
        // 这一档里取不超过当前拨次的最大拨次；同一拨次写了几种，按权重抽一种。
        int reached = lineups.stream().filter(lineup -> lineup.band() == band && lineup.stage() <= stage)
                .mapToInt(Lineup::stage).max().orElseThrow();
        List<Lineup> candidates = lineups.stream()
                .filter(lineup -> lineup.band() == band && lineup.stage() == reached).toList();
        int roll = random.nextInt(candidates.stream().mapToInt(Lineup::weight).sum());
        for (Lineup candidate : candidates) {
            roll -= candidate.weight();
            if (roll < 0) {
                return candidate;
            }
        }
        throw new IllegalStateException("权重抽样落空");
    }

    /**
     * 一种阵容。
     *
     * @param band 给哪一档的玩家
     * @param stage 打赢几拨以后起用；同一档里取不超过当前拨次的最大值
     * @param weight 同档同拨次里几种阵容按权重抽
     * @param leader 头目：赢就是打死他
     * @param followers 随从
     */
    public record Lineup(CrisisHeat.Band band, int stage, int weight, Member leader, List<Followers> followers) {
        // 构造时核对，数据包写错就拒绝。
        public Lineup {
            // 拨次从 0 起，权重至少 1。
            Objects.requireNonNull(band, "band");
            Objects.requireNonNull(leader, "leader");
            followers = List.copyOf(followers);
            if (stage < 0) {
                throw new IllegalArgumentException("stage 不能是负数：" + stage);
            }
            if (weight < 1) {
                throw new IllegalArgumentException("weight 至少是 1：" + weight);
            }
        }

        public int maxSize() {
            // 头目一个，加上每种随从的上限。
            return 1 + followers.stream().mapToInt(Followers::max).sum();
        }

        public List<Member> roster(RandomGenerator random) {
            // 头目在最前，随从按写的先后；每种随从的个数在最少到最多之间均匀取。
            List<Member> roster = new ArrayList<>();
            roster.add(leader);
            for (Followers kind : followers) {
                int count = kind.min() + random.nextInt(kind.max() - kind.min() + 1);
                for (int index = 0; index < count; index++) {
                    roster.add(new Member(kind.species(), kind.tier(), false));
                }
            }
            return List.copyOf(roster);
        }
    }

    /**
     * 头目。
     *
     * @param species 实体 ID，只能是灾民一方
     * @param tier 游炁或真炁
     * @param elite 精锐：开脉和装填取上限，带稀有卷；只有真炁能是精锐
     */
    public record Member(String species, HumanoidTier tier, boolean elite) {
        // 构造时核对，数据包写错就拒绝。
        public Member {
            // 物种和档位照来犯的规矩核对。
            requireRaider(species, tier);
            if (elite && tier != HumanoidTier.TRUE_QI) {
                throw new IllegalArgumentException("只有真炁头目能是精锐：" + species);
            }
        }
    }

    /**
     * 一种随从。
     *
     * @param species 实体 ID，只能是灾民一方
     * @param tier 游炁或真炁
     * @param min 最少几个
     * @param max 最多几个
     */
    public record Followers(String species, HumanoidTier tier, int min, int max) {
        // 构造时核对，数据包写错就拒绝。
        public Followers {
            // 人数区间可以从 0 起，最多不少于最少。
            requireRaider(species, tier);
            if (min < 0 || max < min) {
                throw new IllegalArgumentException("count 要满足 0 ≤ 最少 ≤ 最多：[" + min + ", " + max + "]");
            }
        }
    }

    private static void requireRaider(String species, HumanoidTier tier) {
        // 只认灾民一方的四种；凡人不派。
        if (!RAIDER_SPECIES.contains(species)) {
            throw new IllegalArgumentException("来犯的只能是掠夺者、卫道士、唤魔者、女巫：" + species);
        }
        if (tier == null || tier == HumanoidTier.MORTAL) {
            throw new IllegalArgumentException("来犯者的 tier 只能是 wandering_qi 或 true_qi：" + species);
        }
    }
}
