package dev.kizuna.meridian.humanoid.fabric;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.kizuna.meridian.humanoid.server.CrisisHeat;
import dev.kizuna.meridian.humanoid.server.CrisisParty;
import dev.kizuna.meridian.humanoid.server.CrisisThreat;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.ToIntFunction;
import net.minecraft.resources.Identifier;

/**
 * 出身 JSON 里的 crisis 参数和数据包队伍表的解析器。只管把 JSON 变成 server 里的纯规则记录；
 * 范围不对由那些记录的构造器拒绝，出身框架和队伍表加载各自在报错前面加上是哪一份文件。
 */
final class CrisisCodecs {
    private static final Codec<String> ID = Identifier.CODEC.xmap(Identifier::toString, Identifier::parse);
    private static final Codec<HumanoidTier> TIER = Codec.STRING.comapFlatMap(CrisisCodecs::tier, HumanoidTier::id);
    private static final Codec<CrisisHeat.Band> BAND = Codec.STRING.comapFlatMap(CrisisCodecs::band,
            CrisisHeat.Band::id);
    private static final Codec<CrisisThreat.DayRange> DAYS = pair(CrisisThreat.DayRange::new,
            CrisisThreat.DayRange::min, CrisisThreat.DayRange::max);
    private static final Codec<Count> COUNT = pair(Count::new, Count::min, Count::max);

    /**
     * {@code {"party", "first_after_days": [最早, 最晚], "interval_days": [..], "stages", "min_realm"?, "foe_volume"?}}。
     */
    static final Codec<CrisisThreat> THREAT = RecordCodecBuilder.create(instance -> instance.group(
            ID.fieldOf("party").forGetter(CrisisThreat::party),
            DAYS.fieldOf("first_after_days").forGetter(CrisisThreat::firstAfterDays),
            DAYS.fieldOf("interval_days").forGetter(CrisisThreat::intervalDays),
            Codec.INT.fieldOf("stages").forGetter(CrisisThreat::stages),
            TIER.optionalFieldOf("min_realm", HumanoidTier.WANDERING_QI).forGetter(CrisisThreat::minRealm),
            ID.optionalFieldOf("foe_volume").forGetter(CrisisThreat::foeVolume)
    ).apply(instance, CrisisThreat::new));

    private static final Codec<CrisisParty.Member> MEMBER = RecordCodecBuilder.create(instance -> instance.group(
            ID.fieldOf("species").forGetter(CrisisParty.Member::species),
            TIER.fieldOf("tier").forGetter(CrisisParty.Member::tier),
            Codec.BOOL.optionalFieldOf("elite", false).forGetter(CrisisParty.Member::elite)
    ).apply(instance, CrisisParty.Member::new));

    private static final Codec<CrisisParty.Followers> FOLLOWERS = RecordCodecBuilder.create(instance -> instance.group(
            ID.fieldOf("species").forGetter(CrisisParty.Followers::species),
            TIER.fieldOf("tier").forGetter(CrisisParty.Followers::tier),
            COUNT.fieldOf("count").forGetter(followers -> new Count(followers.min(), followers.max()))
    ).apply(instance, (species, tier, count) -> new CrisisParty.Followers(species, tier, count.min(), count.max())));

    private static final Codec<CrisisParty.Lineup> LINEUP = RecordCodecBuilder.create(instance -> instance.group(
            BAND.fieldOf("band").forGetter(CrisisParty.Lineup::band),
            Codec.INT.optionalFieldOf("stage", 0).forGetter(CrisisParty.Lineup::stage),
            Codec.INT.optionalFieldOf("weight", 1).forGetter(CrisisParty.Lineup::weight),
            MEMBER.fieldOf("leader").forGetter(CrisisParty.Lineup::leader),
            FOLLOWERS.listOf().optionalFieldOf("followers", List.of()).forGetter(CrisisParty.Lineup::followers)
    ).apply(instance, CrisisParty.Lineup::new));

    /** {@code {"lineups": [{"band", "stage"?, "weight"?, "leader": {..}, "followers"?: [..]}]}}。 */
    static final Codec<CrisisParty> PARTY = RecordCodecBuilder.create(instance -> instance.group(
            LINEUP.listOf().fieldOf("lineups").forGetter(CrisisParty::lineups)
    ).apply(instance, CrisisParty::new));

    private CrisisCodecs() {
        // 只有静态解析器。
    }

    private static <T> Codec<T> pair(BiFunction<Integer, Integer, T> build, ToIntFunction<T> first,
                                     ToIntFunction<T> second) {
        // 两个整数写成数组 [前, 后]。
        return Codec.INT.listOf().comapFlatMap(values -> pairOf(values, build),
                value -> List.of(first.applyAsInt(value), second.applyAsInt(value)));
    }

    private static <T> DataResult<T> pairOf(List<Integer> values, BiFunction<Integer, Integer, T> build) {
        // 正好两个才行；范围由记录的构造器管。
        if (values.size() != 2) {
            return DataResult.error(() -> "要写成两个整数 [最少, 最多]：" + values);
        }
        return DataResult.success(build.apply(values.get(0), values.get(1)));
    }

    private static DataResult<HumanoidTier> tier(String id) {
        // 小写名；凡人在这里放行，由记录的构造器说清楚为什么不行。
        Optional<HumanoidTier> tier = HumanoidTier.fromId(id);
        return tier.map(DataResult::success).orElseGet(() -> DataResult.error(() -> "不认识的档位：" + id));
    }

    private static DataResult<CrisisHeat.Band> band(String id) {
        // wandering、true_few、true_many。
        Optional<CrisisHeat.Band> band = CrisisHeat.Band.fromId(id);
        return band.map(DataResult::success).orElseGet(() -> DataResult.error(() -> "不认识的档：" + id));
    }

    /** 随从人数 [最少, 最多]，只在解析时用。 */
    private record Count(int min, int max) { }
}
