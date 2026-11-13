package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.humanoid.server.RegionInventory;
import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages;
import dev.kizuna.meridian.protocol.pb.HealthMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveInfusion;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueInfusions;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.illager.Evoker;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 修炼者唤魔者的大脑与原版法术（C6c SPEC 第七节、PLAN 第 3 段）：先放、续放、恼鬼、游炁、本脏、互斥、声音与粒子、
 * 强身、8 格、变羊。都用正式目录、正式出生入口和带 AI 的原版唤魔者，目标是不动的高血量牛。
 */
public final class HumanoidEvokerGameTest {
    /** 有目标的用例同批并行，各占一块 40×40 场地；变羊用例单独一批，免得别处的凡人把羊变掉。 */
    private static final String SPELLS_BATCH = "meridian_humanoid_test:evoker_spells";
    private static final String WOLOLO_BATCH = "meridian_humanoid_test:evoker_wololo";
    private static final String STRUCTURE = "meridian_humanoid_test:retreat";
    private static final String PREFIX = "meridian_evoker:";
    private static final String FIRE_BURN = PREFIX + "fire_burn";
    private static final String EARTH_LEGS = PREFIX + "earth_legs";
    private static final String FIRE = "meridian:fire";
    private static final String EARTH = "meridian:earth";
    private static final String PREPARE_WOLOLO = "minecraft:entity.evoker.prepare_wololo";
    private static final String ORGAN_DAMAGE = "meridian_humanoid_test:organ";
    private static final String HEART = "heart";
    private static final String SEEDED_CAST = "gametest-seeded";
    private static final long WORLD_SEED = 987654321L;
    private static final int SEED = 0;
    private static final BlockPos EVOKER_POS = new BlockPos(5, 0, 20);
    private static final int TIMEOUT = 800;
    private static final int DUEL_TICKS = 700;
    private static final int CUE_TICKS = 70;
    private static final int NEAR_TICKS = 300;
    /** 附行剩 10 s、到 3 s 才补，约 140 tick；等身体时钟最多半个超时，两段加起来不超过超时。 */
    private static final int REFRESH_TICKS = 300;
    private static final int WOLOLO_TICKS = 300;
    private static final int CULTIVATOR_WATCH_TICKS = 200;
    /** 附伤效果在起手 2000 ms，每 tick 50 ms；起手粒子发到这一刻为止，留 2 tick 给 tick 末发包。 */
    private static final int WARMUP_TICKS = 40;
    private static final int PARTICLE_SLACK_TICKS = 2;
    private static final int MINIMUM_HAND_PARTICLES = 20;
    private static final int PARTICLE_LOG = 12;
    private static final int TICKS_PER_SECOND = 20;
    private static final int HEALTH_PROTOCOL = 1;
    private static final double TARGET_HEALTH = 1000;
    private static final int FAR = 10;
    /** 12 格外、轻身（24 格内）也够得着的距离：厚土排序 1、轻身 2，这里也要先放厚土（主会话 11-13 16:55 定）。 */
    private static final int BEYOND_TWELVE = 13;
    private static final int WITHIN_EIGHT = 5;
    /** 唤出的恼鬼离主人不会这么远，清场时在这个范围里找。 */
    private static final double VEX_RADIUS = 64;
    private static final double INFUSION_MINIMUM = 8;
    private static final double TWELVE = 12;
    private static final double DISTANCE_TOLERANCE = 1;
    private static final double CUE_RADIUS = 2.5;
    // 粒子颜色按 8 位通道下发，0.9 回来是 229/255，容差取一个色阶。
    private static final double COLOR_TOLERANCE = 1.0 / 255;
    /** 灼火的本行粒子色，同 HumanoidEvokerSpells。 */
    private static final float[] FIRE_RED = {0.9F, 0.2F, 0.1F};
    private static final long REFRESH_MILLIS = 3000;
    private static final long SEEDED_REMAINING_MILLIS = 10_000;
    /** 补放门槛起作用的证据：剩 10 s 时开打，至少这么多 tick 后才补放（剩 10 s 降到 3 s 要 140 tick）。 */
    private static final int REFRESH_EARLIEST_TICKS = 100;
    private static final BlockPos MORTAL_POS = new BlockPos(3, 0, 3);
    private static final BlockPos MORTAL_SHEEP = new BlockPos(6, 0, 3);
    private static final BlockPos CULTIVATOR_POS = new BlockPos(36, 0, 36);
    private static final BlockPos CULTIVATOR_SHEEP = new BlockPos(33, 0, 36);
    private static final Set<LivingEntity> WATCHED = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final List<Hit> HITS = new ArrayList<>();

    /** 打在被观察目标身上的一次伤害：哪个目标、什么伤害来源。 */
    private record Hit(LivingEntity target, DamageSource source) { }

    /** 一次施放第一次被看到的那一 tick：式、施放 ID、离开场第几 tick、旧附行剩余、离目标多远。 */
    private record Cast(String technique, String castId, int tick, long infusionRemainingMillis, double distance) { }

    static {
        // 原版受伤入口提交以后记下；附伤打在没有身体的牛身上也走这一入口。只记本类登记的目标。
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (WATCHED.contains(entity)) {
                HITS.add(new Hit(entity, source));
            }
        });
    }

    @GameTest(maxTicks = TIMEOUT, environment = SPELLS_BATCH, structure = STRUCTURE)
    public void fireEvokerInfusesBeforeItsSpellsAndItsFangsBurn(GameTestHelper context) {
        // 先放：10 格外的目标，先起灼火，原版法术在它之后才开始、施功法期间从不开始；尖牙咬中带灼热附伤。
        Duel duel = Duel.start(context, "first", HumanoidTier.TRUE_QI, FIRE, FAR);
        duel.observe(DUEL_TICKS, () -> duel.infusedBy(EvokerFangs.class), () -> {
            duel.assertFirstCast(FIRE_BURN);
            context.assertTrue(duel.firstSpellTick > duel.casts.getFirst().tick(),
                    "原版法术先于灼火开始：法术 " + duel.firstSpellTick + " 灼火 " + duel.casts.getFirst().tick());
            duel.assertNoOverlap();
            context.assertTrue(duel.infusedBy(EvokerFangs.class), "尖牙咬中没有灼热附伤：" + duel.describeHits());
            duel.finish();
        });
    }

    @GameTest(maxTicks = TIMEOUT, environment = SPELLS_BATCH, structure = STRUCTURE)
    public void summonedVexesCarryTheInfusion(GameTestHelper context) {
        // 恼鬼：附行期间唤出的恼鬼近战带附伤，经主人一跳记在唤魔者名下。
        Duel duel = Duel.start(context, "vex", HumanoidTier.TRUE_QI, FIRE, FAR);
        duel.observe(DUEL_TICKS, () -> duel.infusedBy(Vex.class), () -> {
            duel.assertFirstCast(FIRE_BURN);
            context.assertTrue(duel.infusedBy(Vex.class), "恼鬼近战没有灼热附伤：" + duel.describeHits());
            duel.assertNoOverlap();
            duel.finish();
        });
    }

    @GameTest(maxTicks = TIMEOUT, environment = SPELLS_BATCH, structure = STRUCTURE)
    public void infusionCastSaysPrepareWololoWithHandParticles(GameTestHelper context) {
        // 声音与粒子：起附伤式的那一 tick 念一次 prepare_wololo（敌对生物）；起手到附行生效前双手冒本行色粒子。
        Duel duel = Duel.start(context, "cue", HumanoidTier.TRUE_QI, FIRE, FAR);
        duel.listen();
        duel.observe(DUEL_TICKS, () -> !duel.casts.isEmpty() && duel.tick >= duel.casts.getFirst().tick() + CUE_TICKS,
                () -> {
                    duel.assertFirstCast(FIRE_BURN);
                    int castTick = duel.casts.getFirst().tick();
                    context.assertTrue(duel.wololoTicks.equals(List.of(castTick)),
                            "prepare_wololo 不是起手那一 tick 念一次：起手 " + castTick + " 念咒 " + duel.wololoTicks);
                    context.assertTrue(duel.wololoSources.equals(Set.of(SoundSource.HOSTILE)),
                            "prepare_wololo 不归敌对生物：" + duel.wololoSources);
                    duel.assertHandParticles(castTick);
                    duel.finish();
                });
    }

    @GameTest(maxTicks = TIMEOUT, environment = SPELLS_BATCH, structure = STRUCTURE)
    public void refreshWaitsUntilTheInfusionIsExpiring(GameTestHelper context) {
        // 续放：开场就带着剩 10 s 的灼火附行、没有冷却；附行剩不到 3 s 才补放，之前不放。
        // 大脑「接敌前准备」时跟住或贴近目标，几秒内就进 8 格（灼火够不着）；这里把唤魔者钉在原地，只测补放门槛。
        Duel.awaitSeedableClock(context, () -> {
            Duel duel = Duel.start(context, "refresh", HumanoidTier.TRUE_QI, FIRE, FAR,
                    HumanoidEvokerGameTest::withExpiringInfusion);
            duel.pin();
            duel.observe(REFRESH_TICKS, () -> duel.first(FIRE_BURN).isPresent(), () -> {
                Cast refresh = duel.first(FIRE_BURN).orElseThrow(() -> duel.failure("一直没补放灼火"));
                context.assertTrue(refresh.infusionRemainingMillis() <= REFRESH_MILLIS,
                        "附行还剩 " + refresh.infusionRemainingMillis() + " ms 就补放了");
                context.assertTrue(refresh.tick() >= REFRESH_EARLIEST_TICKS,
                        "第 " + refresh.tick() + " tick 就补放，附行还长");
                duel.assertNoOverlap();
                duel.finish();
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT, environment = SPELLS_BATCH, structure = STRUCTURE)
    public void wanderingEarthFangsCarryNoInfusion(GameTestHelper context) {
        // 游炁：游炁土唤魔者放不了中卷，尖牙咬中只有原版伤害。
        Duel duel = Duel.start(context, "wandering", HumanoidTier.WANDERING_QI, EARTH, FAR);
        duel.observe(DUEL_TICKS, () -> duel.struckBy(EvokerFangs.class), () -> {
            context.assertTrue(duel.struckBy(EvokerFangs.class), "游炁唤魔者一直没唤尖牙：" + duel.describeHits());
            context.assertTrue(duel.hits(hit -> isInfusion(hit.source())).isEmpty(),
                    "游炁唤魔者的命中带了附伤：" + duel.describeHits());
            duel.finish();
        });
    }

    @GameTest(maxTicks = TIMEOUT, environment = SPELLS_BATCH, structure = STRUCTURE)
    public void destroyedHeartFallsBackToVanillaSpells(GameTestHelper context) {
        // 本脏：心 HP 打到 0 的火唤魔者绑不上灼火，不放；照样唤尖牙、恼鬼，命中都不带附伤。
        Duel duel = Duel.start(context, "heart", HumanoidTier.TRUE_QI, FIRE, FAR);
        destroyOrgan(context, duel.evoker, HEART);
        duel.observe(DUEL_TICKS, () -> duel.struckBy(EvokerFangs.class) && duel.struckBy(Vex.class), () -> {
            context.assertTrue(duel.casts.stream().noneMatch(cast -> cast.technique().equals(FIRE_BURN)),
                    "心损毁后仍放了灼火：" + duel.casts);
            context.assertTrue(duel.struckBy(EvokerFangs.class) && duel.struckBy(Vex.class),
                    "心损毁后没照原版唤尖牙、恼鬼：" + duel.describeHits());
            context.assertTrue(duel.hits(hit -> isInfusion(hit.source())).isEmpty(),
                    "心损毁后命中仍带附伤：" + duel.describeHits());
            duel.finish();
        });
    }

    @GameTest(maxTicks = TIMEOUT, environment = SPELLS_BATCH, structure = STRUCTURE)
    public void earthEvokerStrengthensLegsBeyondTwelveMeters(GameTestHelper context) {
        // 强身：游炁土唤魔者对 13 格外、没在打它的目标先放厚土，不先轻身；厚土不是附伤式，不念咒、不冒手上粒子。
        Duel duel = Duel.start(context, "legs", HumanoidTier.WANDERING_QI, EARTH, BEYOND_TWELVE);
        duel.listen();
        duel.observe(DUEL_TICKS, () -> !duel.casts.isEmpty() && duel.tick >= duel.casts.getFirst().tick() + CUE_TICKS,
                () -> {
                    duel.assertFirstCast(EARTH_LEGS);
                    Cast legs = duel.casts.getFirst();
                    context.assertTrue(legs.distance() >= TWELVE - DISTANCE_TOLERANCE,
                            "目标 " + legs.distance() + " 格时放了厚土");
                    context.assertTrue(duel.wololoTicks.isEmpty(), "放厚土念了 prepare_wololo：" + duel.wololoTicks);
                    context.assertTrue(duel.handParticleTicks.isEmpty(),
                            "放厚土冒了手上粒子：" + duel.handParticleTicks);
                    duel.assertNoOverlap();
                    duel.finish();
                });
    }

    @GameTest(maxTicks = TIMEOUT, environment = SPELLS_BATCH, structure = STRUCTURE)
    public void noInfusionWithinEightMeters(GameTestHelper context) {
        // 8 格：目标在 8 格内时大脑照常对它决策，但不起附伤式；走远了再起是允许的，只查起手那一刻的距离。
        Duel duel = Duel.start(context, "near", HumanoidTier.TRUE_QI, FIRE, WITHIN_EIGHT);
        duel.observe(NEAR_TICKS, () -> false, () -> {
            context.assertTrue(duel.engagedWithinEight > 0, "大脑没在 8 格内对目标决策过，测不到这条门槛");
            for (Cast cast : duel.casts) {
                context.assertTrue(!cast.technique().equals(FIRE_BURN)
                                || cast.distance() >= INFUSION_MINIMUM - DISTANCE_TOLERANCE,
                        "目标 " + cast.distance() + " 格时起了灼火");
            }
            duel.finish();
        });
    }

    @GameTest(maxTicks = TIMEOUT, environment = WOLOLO_BATCH, structure = STRUCTURE)
    public void onlyMortalEvokersWololo(GameTestHelper context) {
        // 变羊：两只都没有目标、16 格内各有一只蓝羊；凡人照原版把羊变红，修炼者不变、也从不起原版施法。
        // 两组隔 30 格以上，原版变羊只看 16 格内的羊。
        Evoker mortal = born(context, "wololo-mortal", HumanoidTier.MORTAL, FIRE, MORTAL_POS, body -> body);
        Evoker cultivator = born(context, "wololo-cultivator", HumanoidTier.TRUE_QI, FIRE, CULTIVATOR_POS,
                body -> body);
        Sheep mortalSheep = blueSheep(context, MORTAL_SHEEP);
        Sheep cultivatorSheep = blueSheep(context, CULTIVATOR_SHEEP);
        List<Entity> spawned = List.of(mortal, cultivator, mortalSheep, cultivatorSheep);
        boolean[] cultivatorCast = {false};
        int[] elapsed = {0};
        poll(context, WOLOLO_TICKS, () -> {
            elapsed[0]++;
            cultivatorCast[0] |= cultivator.isCastingSpell();
            return mortalSheep.getColor() == DyeColor.RED && elapsed[0] >= CULTIVATOR_WATCH_TICKS;
        }, () -> {
            spawned.forEach(Entity::discard);
            context.assertTrue(mortalSheep.getColor() == DyeColor.RED, "凡人唤魔者没有变羊");
            context.assertTrue(cultivatorSheep.getColor() == DyeColor.BLUE,
                    "修炼者唤魔者把羊变成了 " + cultivatorSheep.getColor());
            context.assertTrue(!cultivatorCast[0], "修炼者唤魔者没有目标时起了原版施法");
            context.succeed();
        });
    }

    private static BodyMessages.BodySnapshot withExpiringInfusion(BodyMessages.BodySnapshot body) {
        // 写一段灼火附行：起点往前推，到曲线末点还剩 10 s；定义快照取正式目录。冷却不写，补放只看附行剩余。
        TechniqueEffect effect = infusionEffect(FIRE_BURN);
        long end = effect.getElementalInfusion().getCurveList().getLast().getOffsetMillis();
        long started = body.getSettledAt().getElapsedMillis() - (end - SEEDED_REMAINING_MILLIS);
        var infusion = ActiveInfusion.newBuilder()
                .setCastId(SEEDED_CAST)
                .setTechniqueId(FIRE_BURN)
                .setStartedMillis(started)
                .setInfusion(effect.getElementalInfusion())
                .build();
        TechniqueProgress progress = TechniqueState.empty().toBuilder().setActiveInfusion(infusion).build();
        return body.toBuilder().putAddonProgress(TechniqueState.KEY, TechniqueState.pack(progress)).build();
    }

    private static TechniqueEffect infusionEffect(String technique) {
        // 目录里这一式的附伤效果，带效果时刻与曲线。
        var reference = TechniqueReference.newBuilder().setId(technique).setVersion(1).build();
        return TechniqueFabric.service().registry().find(reference).orElseThrow().getEffectsList().stream()
                .filter(TechniqueEffect::hasElementalInfusion)
                .findFirst()
                .orElseThrow();
    }

    private static Evoker born(GameTestHelper context, String name, HumanoidTier tier, String element,
                               BlockPos relative, UnaryOperator<BodyMessages.BodySnapshot> edit) {
        // 单属性区域让主属性固定是这一行，身体走自然出生同一入口、只指定档位；需要时改快照后从快照恢复。
        // AI 照开，固定 UUID 让出生可重复；保留下来的世界里重跑时先移除同 UUID 的旧实体。
        UUID id = UUID.nameUUIDFromBytes(("c6c-evoker-ai/" + name).getBytes(StandardCharsets.UTF_8));
        Entity stale = context.getLevel().getEntity(id);
        if (stale != null) {
            stale.discard();
        }
        Evoker mob = EntityTypes.EVOKER.create(context.getLevel(), EntitySpawnReason.COMMAND);
        mob.setUUID(id);
        Vec3 point = Vec3.atBottomCenterOf(context.absolutePos(relative));
        mob.snapTo(point.x, point.y, point.z, 0, 0);
        mob.setPersistenceRequired();
        var inventory = RegionInventory.fromShares(1, Map.of(element, 1.0));
        var region = new HumanoidEnvironmentClient.RegionSnapshot(inventory, HumanoidFabric.region(mob).sourceId());
        var subject = new HumanoidBodyState.BirthSubject(HumanoidSpecies.EVOKER, id, tier);
        var body = HumanoidBodyState.born(subject, WORLD_SEED + SEED, region, HumanoidFabric.now());
        var saved = new HumanoidBodyState.SavedBody(id, edit.apply(body.snapshot()).toByteArray(), new byte[0][],
                region.sourceId());
        ((HumanoidEntityAccess) mob).meridian$humanoidBody(
                HumanoidBodyState.restore(id, saved, HumanoidFabric.now(), HumanoidSpecies.EVOKER));
        context.assertTrue(context.getLevel().addFreshEntity(mob), "唤魔者没加入世界：" + name);
        return mob;
    }

    private static Sheep blueSheep(GameTestHelper context, BlockPos relative) {
        // 不动的蓝羊，原版变羊只挑蓝羊。
        Sheep sheep = context.spawn(EntityTypes.SHEEP, relative);
        sheep.setNoAi(true /* aiDisabled */);
        sheep.setSilent(true /* silent */);
        sheep.setColor(DyeColor.BLUE);
        return sheep;
    }

    private static void destroyOrgan(GameTestHelper context, Evoker evoker, String organId) {
        // 可信健康协议照当前修订号精确打一个器官，伤害取它的最大 HP；CUSTOM 不另分配组织伤害。
        BodyMessages.BodySnapshot body = ((HumanoidEntityAccess) evoker).meridian$humanoidBody().snapshot();
        double maximumHp = body.getStructure().getOrgansList().stream()
                .filter(organ -> organ.getId().equals(organId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("唤魔者身体里没有器官 " + organId))
                .getMaxHp();
        var damage = HealthMessages.DamageBody.newBuilder()
                .setSourceId(ORGAN_DAMAGE)
                .setDamageType(ORGAN_DAMAGE)
                .setKind(HealthMessages.DamageKind.CUSTOM)
                .setHit(BodyMessages.HealthTarget.newBuilder().setOrganId(organId))
                .setDamageHp(maximumHp);
        var command = MeridianMessages.BodyCommand.newBuilder()
                .setBodyId(body.getId())
                .setExpectedRevision(body.getRevision())
                .setDamageBody(damage);
        String requestId = "gametest-organ-" + evoker.getStringUUID();
        var effect = MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(HEALTH_PROTOCOL)
                .setRequestId(requestId)
                .setBodyCommand(command);
        var request = HealthHostMessages.HealthHostRequest.newBuilder()
                .setProtocolVersion(HEALTH_PROTOCOL)
                .setRequestId(requestId)
                .setSubjectId(evoker.getStringUUID())
                .setEffect(effect)
                .build();
        var response = HealthHostProtobuf.decodeResponse(new HumanoidHealthHost().exchange(
                HealthHostProtobuf.encodeRequest(request)));
        context.assertTrue(response.hasBody() && !response.getBody().getVitality().getDead(),
                "器官伤害没提交或打死了唤魔者：" + response);
    }

    private static boolean isInfusion(DamageSource source) {
        // 附伤伤害类型都在五脏诀命名空间下。
        return source.typeHolder().unwrapKey().orElseThrow().identifier().getNamespace()
                .equals(PREFIX.substring(0, PREFIX.length() - 1));
    }

    private static void poll(GameTestHelper context, int ticks, BooleanSupplier stop, Runnable next) {
        // 每 tick 查一次，提前满足或到期都进下一步。
        if (stop.getAsBoolean() || ticks == 0) {
            next.run();
            return;
        }
        context.runAfterDelay(1, () -> poll(context, ticks - 1, stop, next));
    }

    /** 一只唤魔者对一头牛：逐 tick 记施放、原版施法、重叠、念咒、手上粒子和命中，结束或失败时清场。 */
    private static final class Duel {
        private final GameTestHelper context;
        private final Evoker evoker;
        private final Cow cow;
        private final List<Cast> casts = new ArrayList<>();
        private final List<Integer> wololoTicks = new ArrayList<>();
        private final Set<SoundSource> wololoSources = new HashSet<>();
        private final List<Integer> handParticleTicks = new ArrayList<>();
        private final List<Integer> fireParticleTicks = new ArrayList<>();
        private final List<String> particleLog = new ArrayList<>();
        private final List<Double> distances = new ArrayList<>();
        private SoundListener listener;
        private int tick;
        private int firstSpellTick = Integer.MAX_VALUE;
        private int overlapTick = -1;
        private int engagedWithinEight;
        private Vec3 pinnedAt;

        private Duel(GameTestHelper context, Evoker evoker, Cow cow) {
            // 场地只属于一个用例。
            this.context = context;
            this.evoker = evoker;
            this.cow = cow;
        }

        static Duel start(GameTestHelper context, String name, HumanoidTier tier, String element, int distance) {
            // 出生快照不改。
            return start(context, name, tier, element, distance, body -> body);
        }

        static Duel start(GameTestHelper context, String name, HumanoidTier tier, String element, int distance,
                          UnaryOperator<BodyMessages.BodySnapshot> edit) {
            // 唤魔者和牛放在同一条线上，相距 distance 格；原版目标直接设成牛，大脑的威胁也从这里来。
            Evoker evoker = born(context, name, tier, element, EVOKER_POS, edit);
            Cow cow = context.spawn(EntityTypes.COW, EVOKER_POS.offset(distance, 0, 0));
            cow.setNoAi(true /* aiDisabled */);
            cow.setSilent(true /* silent */);
            cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(TARGET_HEALTH);
            cow.setHealth((float) TARGET_HEALTH);
            WATCHED.add(cow);
            evoker.setTarget(cow);
            return new Duel(context, evoker, cow);
        }

        static void awaitSeedableClock(GameTestHelper context, Runnable next) {
            // 预置附行的起点不能早于身体时钟零点，开服头几秒先等一等。
            long end = infusionEffect(FIRE_BURN).getElementalInfusion().getCurveList().getLast().getOffsetMillis();
            long needed = end - SEEDED_REMAINING_MILLIS;
            poll(context, TIMEOUT / 2, () -> HumanoidFabric.now().getElapsedMillis() >= needed, next);
        }

        void pin() {
            // 之后每 tick 放回现在的位置，距离一直是开场的距离。
            pinnedAt = evoker.position();
        }

        void listen() {
            // 旁观者站在唤魔者身上收包，之后逐 tick 取走。
            listener = SoundListener.create(evoker);
        }

        void observe(int ticks, BooleanSupplier stop, Runnable next) {
            // 每 tick 先记一笔再判断；记录或断言抛出时也清场。
            poll(context, ticks, () -> {
                try {
                    sample();
                    return stop.getAsBoolean();
                } catch (RuntimeException | Error thrown) {
                    clean();
                    throw thrown;
                }
            }, () -> guarded(next));
        }

        private void sample() {
            // 原版施法、当前施放和两者重叠；有收包的话顺带记念咒和手上粒子。
            tick++;
            if (pinnedAt != null) {
                evoker.teleportTo(pinnedAt.x, pinnedAt.y, pinnedAt.z);
            }
            var body = ((HumanoidEntityAccess) evoker).meridian$humanoidBody().currentBody();
            TechniqueProgress progress = TechniqueState.read(body);
            boolean spell = evoker.isCastingSpell();
            if (spell) {
                firstSpellTick = Math.min(firstSpellTick, tick);
            }
            if (spell && progress.hasActiveCast() && overlapTick < 0) {
                overlapTick = tick;
            }
            double distance = evoker.distanceTo(cow);
            if (tick % TICKS_PER_SECOND == 0) {
                distances.add(Math.round(distance * 10) / 10.0);
            }
            if (progress.hasActiveCast()) {
                noteCast(progress, TechniqueInfusions.remainingMillis(body), distance);
            }
            if (distance < INFUSION_MINIMUM && HumanoidBrain.opponent(evoker).orElse(null) == cow) {
                engagedWithinEight++;
            }
            if (listener != null) {
                drainPackets();
            }
        }

        private void noteCast(TechniqueProgress progress, long infusionRemaining, double distance) {
            // 同一次施放只记第一次看到的那一 tick。
            var request = progress.getActiveCast().getRequest();
            if (!casts.isEmpty() && casts.getLast().castId().equals(request.getCastId())) {
                return;
            }
            casts.add(new Cast(request.getTechnique().getId(), request.getCastId(), tick, infusionRemaining, distance));
        }

        private void drainPackets() {
            // 只认唤魔者身边发出的；取完清空，下一 tick 只看新包。
            for (Object packet : listener.connection().packets()) {
                if (packet instanceof ClientboundSoundPacket sound && near(sound.getX(), sound.getY(), sound.getZ())
                        && sound.getSound().value().location().toString().equals(PREPARE_WOLOLO)) {
                    wololoTicks.add(tick);
                    wololoSources.add(sound.getSource());
                }
                if (packet instanceof ClientboundLevelParticlesPacket any && particleLog.size() < PARTICLE_LOG) {
                    particleLog.add(any.getParticle().getType() + "@" + String.format("%.1f",
                            evoker.position().distanceTo(new Vec3(any.getX(), any.getY(), any.getZ()))));
                }
                if (packet instanceof ClientboundLevelParticlesPacket particles
                        && particles.getParticle() instanceof ColorParticleOption color
                        && color.getType() == ParticleTypes.ENTITY_EFFECT
                        && near(particles.getX(), particles.getY(), particles.getZ())) {
                    handParticleTicks.add(tick);
                    if (isFireRed(color)) {
                        fireParticleTicks.add(tick);
                    }
                }
            }
            listener.connection().packets().clear();
        }

        private boolean near(double x, double y, double z) {
            // 手在头顶高度，声音在脚下；都在唤魔者身周几格内。
            return evoker.position().distanceTo(new Vec3(x, y, z)) < CUE_RADIUS;
        }

        private static boolean isFireRed(ColorParticleOption color) {
            // 浮点颜色按容差比。
            return Math.abs(color.getRed() - FIRE_RED[0]) < COLOR_TOLERANCE
                    && Math.abs(color.getGreen() - FIRE_RED[1]) < COLOR_TOLERANCE
                    && Math.abs(color.getBlue() - FIRE_RED[2]) < COLOR_TOLERANCE;
        }

        Optional<Cast> first(String technique) {
            // 这一式第一次被看到的那次施放。
            return casts.stream().filter(cast -> cast.technique().equals(technique)).findFirst();
        }

        GameTestAssertException failure(String message) {
            // 失败时带上全部施放、每秒一次的距离和最近的决策；走 GameTest 断言，不让测试服崩。
            return context.assertionException(Component.literal(message + "：施放 " + casts + " 距离 " + distances
                    + " 决策 " + decisions()));
        }

        void assertFirstCast(String technique) {
            // 观察期内第一次施放就是这一式。
            context.assertTrue(!casts.isEmpty() && casts.getFirst().technique().equals(technique),
                    "第一次施放不是 " + technique + "：" + casts + " 决策 " + decisions());
        }

        void assertNoOverlap() {
            // 功法施放与原版施法从不在同一 tick 都进行着。
            context.assertTrue(overlapTick < 0, "第 " + overlapTick + " tick 功法和原版法术同时在放：" + casts);
        }

        void assertHandParticles(int castTick) {
            // 起手那一 tick 开始冒，附行生效那一刻（起手 40 tick）为止；之后不再冒。
            context.assertTrue(fireParticleTicks.size() >= MINIMUM_HAND_PARTICLES,
                    "起手期间手上的火红粒子太少：" + fireParticleTicks.size() + " 收到的粒子包 " + particleLog);
            int first = fireParticleTicks.getFirst();
            int last = fireParticleTicks.getLast();
            context.assertTrue(first >= castTick && first <= castTick + PARTICLE_SLACK_TICKS,
                    "手上粒子没在起手时开始：起手 " + castTick + " 第一颗 " + first);
            context.assertTrue(last <= castTick + WARMUP_TICKS + PARTICLE_SLACK_TICKS,
                    "附行生效后手上还在冒粒子：起手 " + castTick + " 最后一颗 " + last);
        }

        boolean infusedBy(Class<? extends Entity> direct) {
            // 附伤伤害、直接实体是尖牙或恼鬼。
            return !hits(hit -> isInfusion(hit.source()) && direct.isInstance(hit.source().getDirectEntity()))
                    .isEmpty();
        }

        boolean struckBy(Class<? extends Entity> direct) {
            // 原版主击，直接实体是尖牙或恼鬼。
            return !hits(hit -> !isInfusion(hit.source()) && direct.isInstance(hit.source().getDirectEntity()))
                    .isEmpty();
        }

        List<Hit> hits(Predicate<Hit> filter) {
            // 只看打在本用例牛身上的。
            return HITS.stream().filter(hit -> hit.target() == cow).filter(filter).toList();
        }

        String describeHits() {
            // 失败时列出牛挨过的每一下：伤害类型和直接实体。
            return hits(hit -> true).stream()
                    .map(hit -> hit.source().typeHolder().unwrapKey().orElseThrow().identifier() + "/"
                            + (hit.source().getDirectEntity() == null ? "-"
                            : hit.source().getDirectEntity().getClass().getSimpleName()))
                    .toList() + " 施放 " + casts + " 原版首次施法 " + firstSpellTick;
        }

        private List<String> decisions() {
            // 最近的大脑决策，排查没起式的原因。
            return HumanoidBrain.decisions(evoker.getUUID()).stream()
                    .map(logged -> logged.tick() + ":" + logged.decision())
                    .toList();
        }

        private void guarded(Runnable step) {
            // 断言失败也不把带 AI 的实体留到后续批次。
            try {
                step.run();
            } catch (RuntimeException | Error thrown) {
                clean();
                throw thrown;
            }
        }

        void finish() {
            // 清场后报告通过。
            clean();
            context.succeed();
        }

        private void clean() {
            // 唤魔者、牛和它唤出的恼鬼一起移走，旁观者断线。
            AABB area = evoker.getBoundingBox().inflate(VEX_RADIUS);
            context.getLevel().getEntitiesOfClass(Vex.class, area, vex -> vex.getOwner() == evoker)
                    .forEach(Entity::discard);
            evoker.discard();
            cow.discard();
            WATCHED.remove(cow);
            if (listener != null) {
                listener.close();
            }
        }
    }
}
