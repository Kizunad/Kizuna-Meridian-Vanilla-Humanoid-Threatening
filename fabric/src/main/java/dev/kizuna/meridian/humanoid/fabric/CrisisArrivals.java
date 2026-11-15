package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Pending;
import dev.kizuna.meridian.humanoid.server.CrisisArrival;
import dev.kizuna.meridian.humanoid.server.CrisisFeuds;
import dev.kizuna.meridian.humanoid.server.CrisisOutcome;
import dev.kizuna.meridian.humanoid.server.CrisisParty;
import dev.kizuna.meridian.humanoid.server.CrisisRules;
import dev.kizuna.meridian.humanoid.server.CrisisSchedule;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 来人现身：到点了在主世界、黄昏到夜里，离玩家 24–40 格、玩家看不见的地方，按队伍表派人；
 * 有记仇的人就由它带队回来。头目会学卷、有名字，来人都打上标签、只盯这名玩家。找不到落脚点就隔一会儿再试。
 */
final class CrisisArrivals {
    static final String RAIDER_TAG = "meridian_humanoid.crisis_raider";
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid/crisis");
    private static final int SPOT_TRIES = 24;
    private static final int MAX_RISE = 6;
    private static final double HEAD_HEIGHT = 1.6;
    private static final double SCATTER = 0.4;
    private static final double FOLLOW_MARGIN = 16;
    private static final int TICKS_PER_SECOND = 20;
    private final CrisisRules rules;
    private final Map<UUID, Long> retryAt = new HashMap<>();

    CrisisArrivals(CrisisRules rules) {
        // 规则在启动时定死。
        this.rules = rules;
    }

    Optional<CrisisRecord> whenFit(ServerPlayer player, CrisisRecord record, List<CrisisFeuds.Entry> feuds,
                                   long playerSeed) {
        // 到点了：时辰、地方、玩家都合适，且过了上次没找到落脚点定下的重试时刻，才去找；找不到隔 retry_seconds 再试。
        long now = record.getOnlineTicks();
        if (!fits(player) || now < retryAt.getOrDefault(player.getUUID(), Long.MIN_VALUE)) {
            return Optional.empty();
        }
        Optional<CrisisRecord> arrived = arrive(player, record, feuds, playerSeed);
        if (arrived.isEmpty()) {
            retryAt.put(player.getUUID(), now + (long) rules.arrival().retrySeconds() * TICKS_PER_SECOND);
        } else {
            retryAt.remove(player.getUUID());
        }
        return arrived;
    }

    private static boolean fits(ServerPlayer player) {
        // 只在主世界、黄昏到夜里来；创造、旁观、死了的玩家不来。
        ServerLevel level = player.level();
        return level.dimension() == Level.OVERWORLD && CrisisArrival.nightfall(level.getOverworldClockTime())
                && player.isAlive() && !player.isCreative() && !player.isSpectator();
    }

    Optional<CrisisRecord> arrive(ServerPlayer player, CrisisRecord record, List<CrisisFeuds.Entry> feuds,
                                  long playerSeed) {
        // 不看时辰：找到落脚点就按队伍表派人（有记仇的人就换它当头目）、头目学卷起名、全部盯上这名玩家，记进记录；
        // 找不到落脚点或队伍表不在了，记录不动。
        Pending pending = record.getPending();
        CrisisParty party = CrisisFabric.parties().get(pending.getParty());
        if (party == null) {
            LOGGER.error("玩家 {} 排着的那一场的队伍表 {} 不在了", player.getScoreboardName(), pending.getParty());
            return Optional.empty();
        }
        long seed = CrisisArrival.seed(playerSeed, pending);
        Optional<Vec3> spot = spot(player, CrisisSchedule.random(seed ^ record.getOnlineTicks()));
        if (spot.isEmpty()) {
            return Optional.empty();
        }
        RandomGenerator random = CrisisSchedule.random(seed);
        List<CrisisParty.Member> lineup = party.lineup(CrisisArrival.band(record, rules.strength()),
                pending.getStage(), random).roster(random);
        Optional<CrisisOutcome.Return> back = Optional.of(record).filter(CrisisRecord::hasGrudge)
                .map(present -> CrisisOutcome.returning(present.getGrudge(), lineup.getFirst(),
                        HumanoidBrain.config().volumes()));
        List<CrisisParty.Member> roster = back.map(returned -> led(lineup, returned.leader())).orElse(lineup);
        List<Mob> raiders = spawn(player, roster, spot.get(), random);
        Mob leader = raiders.getFirst();
        teach(leader, roster.getFirst(), CrisisArrival.foeVolume(pending, feuds),
                back.map(CrisisOutcome.Return::volumes).orElse(List.of()));
        String named = back.isPresent() ? record.getGrudge().getParty() : pending.getParty();
        leader.setCustomName(Component.translatable(leaderNameKey(named)));
        List<UUID> ids = raiders.stream().map(Mob::getUUID).toList();
        CrisisRaiders.track(player.getUUID(), ids);
        raiders.forEach(raider -> raider.setTarget(player));
        LOGGER.info("队伍表 {} 来了 {} 人找玩家 {}，在 {}；记仇的人带队：{}", pending.getParty(), ids.size(),
                player.getScoreboardName(), BlockPos.containing(spot.get()), back.isPresent());
        var shown = new CrisisArrival.Leader(leader.getUUID(), roster.getFirst().species(), roster.getFirst().tier(),
                CrisisVolumes.known(leader.getUUID()), back.isPresent());
        return Optional.of(CrisisArrival.arrived(record, shown, ids));
    }

    private static List<CrisisParty.Member> led(List<CrisisParty.Member> lineup, CrisisParty.Member leader) {
        // 阵容里的头目换成记仇的人，随从不动。
        List<CrisisParty.Member> roster = new ArrayList<>(lineup);
        roster.set(0, leader);
        return List.copyOf(roster);
    }

    private Optional<Vec3> spot(ServerPlayer player, RandomGenerator random) {
        // 在离玩家 min–max 格的环上随机找：区块已加载、地表站得住、和玩家高差不大、玩家看不见。
        ServerLevel level = player.level();
        CrisisRules.Arrival arrival = rules.arrival();
        for (int attempt = 0; attempt < SPOT_TRIES; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = arrival.minDistance() + random.nextDouble() * (arrival.maxDistance()
                    - arrival.minDistance());
            BlockPos column = BlockPos.containing(player.getX() + Math.cos(angle) * distance, player.getY(),
                    player.getZ() + Math.sin(angle) * distance);
            if (!level.isLoaded(column)) {
                continue;
            }
            BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
            if (standable(level, ground) && Math.abs(ground.getY() - player.getBlockY()) <= MAX_RISE
                    && unseen(player, ground)) {
                return Optional.of(Vec3.atBottomCenterOf(ground));
            }
        }
        return Optional.empty();
    }

    static boolean standable(ServerLevel level, BlockPos ground) {
        // 脚下是能站的实心面；站的这格和头顶一格没有方块，也不在水里。
        BlockPos below = ground.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
                && level.getFluidState(ground).isEmpty()
                && level.noCollision(new AABB(ground).expandTowards(0, 1, 0));
    }

    private static boolean unseen(ServerPlayer player, BlockPos ground) {
        // 在玩家身后（和看的方向相反的半边），或者玩家眼睛到来人头顶之间有方块挡着，都算看不见。
        Vec3 eye = player.getEyePosition();
        Vec3 head = Vec3.atBottomCenterOf(ground).add(0, HEAD_HEIGHT, 0);
        if (player.getViewVector(1).dot(head.subtract(eye)) < 0) {
            return true;
        }
        var sight = new ClipContext(eye, head, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player);
        return player.level().clip(sight).getType() != HitResult.Type.MISS;
    }

    private List<Mob> spawn(ServerPlayer player, List<CrisisParty.Member> roster, Vec3 spot,
                            RandomGenerator random) {
        // 头目站在落脚点，随从挨着散开；都打标签、追击距离够到整个现身距离以外。
        List<Mob> raiders = new ArrayList<>();
        for (CrisisParty.Member member : roster) {
            Vec3 position = spot.add(scatter(random), 0, scatter(random));
            HumanoidSpecies species = HumanoidSpecies.fromEntityId(member.species()).orElseThrow();
            Mob raider = HumanoidSpawns.event(player.level(), species, birth(member), position);
            raider.addTag(RAIDER_TAG);
            var follow = raider.getAttribute(Attributes.FOLLOW_RANGE);
            follow.setBaseValue(Math.max(follow.getBaseValue(), rules.arrival().maxDistance() + FOLLOW_MARGIN));
            raiders.add(raider);
        }
        return raiders;
    }

    private static double scatter(RandomGenerator random) {
        // 水平方向左右各不到半格。
        return (random.nextDouble() * 2 - 1) * SCATTER;
    }

    private static Consumer<Mob> birth(CrisisParty.Member member) {
        // 精锐走精锐出生，其余按写的档位出生。
        if (member.elite()) {
            return HumanoidSpeciesBirths::birthElite;
        }
        return mob -> HumanoidSpeciesBirths.birth(mob, member.tier());
    }

    private static void teach(Mob leader, CrisisParty.Member member, Optional<String> foeVolume,
                              List<String> remembered) {
        // 精锐头目会本物种真炁档学卷表里写着的每一卷（稀有卷在内）；记仇的人再会它记着的卷（连同多学的那一卷）；
        // 仇家最后一拨的头目再会 foe_volume。前置卷都排在前面先学；记着的卷后来没装了就跳过。
        var service = TechniqueFabric.service();
        var registry = service.registry();
        List<String> volumes = new ArrayList<>();
        if (member.elite()) {
            volumes.addAll(HumanoidBrain.config().volumes().listed(member.species(), HumanoidTier.TRUE_QI));
        }
        remembered.stream().filter(volume -> registry.findVolume(volume).isPresent()).forEach(volumes::add);
        foeVolume.ifPresent(volumes::add);
        var ordered = new LinkedHashSet<String>();
        volumes.forEach(volume -> ordered.addAll(withPrerequisites(volume,
                id -> registry.findVolume(id).orElseThrow().getPrerequisiteVolumeIdsList())));
        ordered.forEach(volume -> service.learn(leader.getUUID(), volume));
    }

    static List<String> withPrerequisites(String volume, Function<String, List<String>> prerequisites) {
        // 前置卷排在前面，深度优先，同一卷只出现一次。
        var ordered = new LinkedHashSet<String>();
        collect(volume, prerequisites, ordered);
        return List.copyOf(ordered);
    }

    private static void collect(String volume, Function<String, List<String>> prerequisites,
                                LinkedHashSet<String> ordered) {
        // 先收前置，再收自己。
        for (String required : prerequisites.apply(volume)) {
            collect(required, prerequisites, ordered);
        }
        ordered.add(volume);
    }

    static String leaderNameKey(String party) {
        // 头目头顶的名号。
        return textKey(party, "leader_name");
    }

    static String textKey(String party, String suffix) {
        // 队伍表「命名空间:名字」→ crisis.命名空间.名字.<后缀>；名字里的斜杠换成点。
        Identifier id = Identifier.parse(party);
        return "crisis." + id.getNamespace() + "." + id.getPath().replace('/', '.') + "." + suffix;
    }

    void forget(UUID player) {
        // 下线时放掉重试时刻。
        retryAt.remove(player);
    }

    void clear() {
        // 停服时清空。
        retryAt.clear();
    }
}
