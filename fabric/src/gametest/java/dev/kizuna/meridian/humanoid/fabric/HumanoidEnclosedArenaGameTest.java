package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.Decision;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 封闭石室内由真实实体 AI 持续交战；观察器不代替寻路、选式或伤害结算。 */
public final class HumanoidEnclosedArenaGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid_arena");
    private static final String STRUCTURE = "meridian_humanoid_test:crowd";
    private static final int EDGE = 9;
    private static final int CEILING = 6;
    private static final int OBSERVATION_TICKS = 1200;
    private static final int TIMEOUT = OBSERVATION_TICKS + 20;
    private static final int MAX_QUIET_TICKS = 400;
    private static final int SAMPLE_TICKS = 100;
    private static final int ADVANCE_WINDOW_TICKS = 20;
    private static final double ADVANCE_DISPLACEMENT = 1.0;
    private static final double ADVANCE_CLOSING = 0.5;
    private static final BlockPos FIRST = new BlockPos(2, 1, 2);
    private static final BlockPos SECOND = new BlockPos(7, 1, 7);
    private static final BlockPos CORNER = new BlockPos(1, 1, 1);
    private static final BlockPos PURSUER = new BlockPos(3, 1, 3);
    private static final Set<Decision.Movement> EVASIONS = EnumSet.of(Decision.Movement.RETREAT,
            Decision.Movement.KITE, Decision.Movement.DODGE, Decision.Movement.FACE);
    private static final Set<Decision.Action> PURSUITS = EnumSet.of(Decision.Action.APPROACH,
            Decision.Action.ASSAULT, Decision.Action.STRIKE, Decision.Action.INTERRUPT, Decision.Action.RELEASE);
    private static final Set<Decision.Movement> ENGAGING_MOVEMENTS =
            EnumSet.of(Decision.Movement.HOLD_RANGE, Decision.Movement.FOLLOW);

    private enum Expectation { DUEL, PEDDLER_DEFENDS, PEDDLER_HITS }

    @GameTest(maxTicks = TIMEOUT, structure = STRUCTURE)
    public void trueQiIllagersKeepFightingInClosedRoom(GameTestHelper context) {
        duel(context, HumanoidTier.TRUE_QI);
    }

    @GameTest(maxTicks = TIMEOUT, structure = STRUCTURE)
    public void wanderingIllagersKeepFightingInClosedRoom(GameTestHelper context) {
        duel(context, HumanoidTier.WANDERING_QI);
    }

    @GameTest(maxTicks = TIMEOUT, structure = STRUCTURE)
    public void wanderingPeddlerDefendsInCorner(GameTestHelper context) {
        peddler(context, HumanoidTier.WANDERING_QI, Expectation.PEDDLER_DEFENDS);
    }

    @GameTest(maxTicks = TIMEOUT, structure = STRUCTURE)
    public void trueQiPeddlerDefendsInCorner(GameTestHelper context) {
        peddler(context, HumanoidTier.TRUE_QI, Expectation.PEDDLER_DEFENDS);
    }

    @GameTest(maxTicks = TIMEOUT, structure = STRUCTURE)
    public void wanderingPeddlerHitsMortalPursuer(GameTestHelper context) {
        peddler(context, HumanoidTier.WANDERING_QI, Expectation.PEDDLER_HITS);
    }

    @GameTest(maxTicks = TIMEOUT, structure = STRUCTURE)
    public void trueQiPeddlerHitsMortalPursuer(GameTestHelper context) {
        peddler(context, HumanoidTier.TRUE_QI, Expectation.PEDDLER_HITS);
    }

    private static void duel(GameTestHelper context, HumanoidTier tier) {
        enclose(context);
        var first = spawn(context, EntityTypes.VINDICATOR, tier, "duel-vindicator-" + tier, FIRST);
        var second = spawn(context, EntityTypes.PILLAGER, tier, "duel-pillager-" + tier, SECOND);
        first.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        second.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
        first.setTarget(second);
        second.setTarget(first);
        new Watch(context, first, second, Expectation.DUEL).tick();
    }

    private static void peddler(GameTestHelper context, HumanoidTier tier, Expectation expectation) {
        enclose(context);
        HumanoidTier hunterTier = HumanoidTier.TRUE_QI;
        String label = tier.toString();
        if (expectation == Expectation.PEDDLER_HITS) {
            hunterTier = HumanoidTier.MORTAL;
            label = tier + "-mortal";
        }
        var trader = spawn(context, EntityTypes.WANDERING_TRADER, tier, "trader-" + label, CORNER);
        var hunter = spawn(context, EntityTypes.VINDICATOR, hunterTier, "hunter-" + label, PURSUER);
        // 持斧真炁验证受压反击；徒手凡人验证腿法命中，避免首击致残遮蔽效果接线。
        if (expectation == Expectation.PEDDLER_DEFENDS) {
            hunter.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        }
        hunter.setTarget(trader);
        new Watch(context, trader, hunter, expectation).tick();
    }

    private static void enclose(GameTestHelper context) {
        // 8×8 内室有完整地面、墙壁与顶棚，无法靠逃出测试结构获得安全距离。
        for (int x = 0; x <= EDGE; x++) {
            for (int y = 0; y <= CEILING; y++) {
                for (int z = 0; z <= EDGE; z++) {
                    if (x == 0 || x == EDGE || y == 0 || y == CEILING || z == 0 || z == EDGE) {
                        context.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                    }
                }
            }
        }
    }

    private static <T extends Mob> T spawn(GameTestHelper context, EntityType<T> type, HumanoidTier tier,
                                          String name, BlockPos relative) {
        T mob = type.create(context.getLevel(), EntitySpawnReason.COMMAND);
        UUID id = UUID.nameUUIDFromBytes(("enclosed-arena/" + name).getBytes(StandardCharsets.UTF_8));
        mob.setUUID(id);
        mob.getRandom().setSeed(id.getMostSignificantBits());
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(relative));
        mob.snapTo(position.x, position.y, position.z, 0, 0);
        mob.setPersistenceRequired();
        HumanoidSpeciesBirths.birth(mob, tier);
        context.assertTrue(context.getLevel().addFreshEntity(mob), "竞技场实体未加入世界：" + name);
        HumanoidTechniqueHost.prepare(mob);
        alignDecisionPhase(mob);
        return mob;
    }

    private static void alignDecisionPhase(Mob mob) {
        // 测试夹具固定下一次大脑决策相位，实体 ID 不影响观察窗口内的真实行为。
        int interval = HumanoidBrain.config().schedule().decisionIntervalTicks();
        mob.tickCount = Math.floorMod(-mob.getId() - 1, interval);
    }

    private static double health(Mob mob) {
        var snapshot = ((HumanoidEntityAccess) mob).meridian$humanoidBody().snapshot();
        return BodyProtobuf.fromMessage(snapshot).partHpRatio();
    }

    private static final class Watch {
        private final GameTestHelper context;
        private final Mob first;
        private final Mob second;
        private final Expectation expectation;
        private final Set<String> firstCasts = new LinkedHashSet<>();
        private final Set<String> secondCasts = new LinkedHashSet<>();
        private final Deque<Vec3> firstTrail = new ArrayDeque<>();
        private final Deque<Vec3> secondTrail = new ArrayDeque<>();
        private double firstHealth;
        private double secondHealth;
        private int ticks;
        private int quietTicks;
        private int chaseTicks;
        private int idleChaseTicks;
        private int damageEvents;
        private boolean firstAttacked;
        private boolean secondAttacked;
        private boolean secondHurt;

        private Watch(GameTestHelper context, Mob first, Mob second, Expectation expectation) {
            this.context = context;
            this.first = first;
            this.second = second;
            this.expectation = expectation;
            firstHealth = health(first);
            secondHealth = health(second);
        }

        private void tick() {
            record(first, firstTrail);
            record(second, secondTrail);
            observeDamage();
            observeCast(first, firstCasts);
            observeCast(second, secondCasts);
            firstAttacked |= first.swinging || first.isUsingItem() || offensive(firstCasts);
            secondAttacked |= second.swinging || second.isUsingItem() || offensive(secondCasts);
            if (ticks % SAMPLE_TICKS == 0) {
                LOGGER.info("arena={} tick={} quiet={} chase={} damage={} idleChase={} first={} second={}",
                        first.getStringUUID(), ticks, quietTicks, chaseTicks, damageEvents, idleChaseTicks,
                        describe(first), describe(second));
            }
            if (!first.isAlive() || !second.isAlive() || ticks == OBSERVATION_TICKS) {
                finish();
                return;
            }
            if (quietTicks >= MAX_QUIET_TICKS) {
                fail("连续 " + quietTicks + " tick 无伤害进展");
                return;
            }
            ticks++;
            context.runAfterDelay(1, this::tick);
        }

        private void observeDamage() {
            // 有伤害就清零静默；一方在躲（含墙角转身等）、另一方正追着它而且真在挪动的 tick 只停表不清零，
            // 其余无伤害的 tick 计静默。
            double currentFirst = health(first);
            double currentSecond = health(second);
            if (currentFirst < firstHealth || currentSecond < secondHealth) {
                quietTicks = 0;
                damageEvents++;
            } else if (chase()) {
                chaseTicks++;
            } else {
                quietTicks++;
            }
            secondHurt |= currentSecond < secondHealth;
            firstHealth = currentFirst;
            secondHealth = currentSecond;
        }

        private boolean chase() {
            // 躲开追打是有效防守，不是双方都卡住的僵持：商人腿力不够时绕着躲，对手的长招一次次落空。
            // 追的一方还得真在缩短距离或换位置；决策像在追、人却原地跟着转或贴着墙磨的 tick 另记一笔，照样计静默。
            boolean firstChased = evading(first, second);
            boolean secondChased = evading(second, first);
            boolean firstPressed = firstChased && advancing(secondTrail, first);
            boolean secondPressed = secondChased && advancing(firstTrail, second);
            boolean pressed = firstPressed || secondPressed;
            if ((firstChased || secondChased) && !pressed) {
                idleChaseTicks++;
            }
            return pressed;
        }

        private static void record(Mob mob, Deque<Vec3> trail) {
            // 只留最近一个窗口的位置：窗口满时队首是 ADVANCE_WINDOW_TICKS tick 前的位置，队尾是这一 tick 的。
            trail.addLast(mob.position());
            if (trail.size() > ADVANCE_WINDOW_TICKS + 1) {
                trail.removeFirst();
            }
        }

        private static boolean advancing(Deque<Vec3> chaserTrail, Mob evader) {
            // 看追的一方最近 1 秒（两个决策间隔）：水平挪了至少 ADVANCE_DISPLACEMENT 格算换位置，
            // 它自己的移动让到被追方现在位置的水平距离缩短至少 ADVANCE_CLOSING 格算在逼近；
            // 原地跟着转、贴着墙磨两样都到不了。窗口没满不算。
            if (chaserTrail.size() <= ADVANCE_WINDOW_TICKS) {
                return false;
            }
            Vec3 then = chaserTrail.getFirst();
            Vec3 now = chaserTrail.getLast();
            Vec3 target = evader.position();
            boolean moved = horizontal(then, now) >= ADVANCE_DISPLACEMENT;
            boolean closed = horizontal(then, target) - horizontal(now, target) >= ADVANCE_CLOSING;
            return moved || closed;
        }

        private static double horizontal(Vec3 from, Vec3 to) {
            return Math.hypot(to.x - from.x, to.z - from.z);
        }

        private static boolean evading(Mob evader, Mob chaser) {
            // 各看最后一条决策：躲的一方在拉开、闪避，或在墙角转身等着；追的一方正对着它接近、突击、打击、打断、
            // 交给原版攻击，或在做接敌准备，或者正在施放、同时跟着它走；起手到收式期间大脑不再记决策，
            // 追的一方沿用起手那一条。
            Optional<HumanoidMind.Logged> fled = last(evader);
            Optional<HumanoidMind.Logged> chased = last(chaser);
            if (fled.isEmpty() || chased.isEmpty()) {
                return false;
            }
            HumanoidMind.Logged pursuit = chased.get();
            Decision chase = pursuit.decision();
            boolean away = EVASIONS.contains(fled.get().decision().movement());
            boolean pressing = PURSUITS.contains(chase.action()) || preparingToEngage(chase)
                    || castingWhileFollowing(chaser);
            boolean atEvader = pursuit.opponent().filter(evader::equals).isPresent();
            return away && pressing && atEvader;
        }

        private static boolean preparingToEngage(Decision decision) {
            // 接敌前准备（轻身、冲锋）时保持跃步带或跟着对手走；撤退时放的机动式移动通道是 RETREAT，不算。
            return decision.action() == Decision.Action.PREPARE && ENGAGING_MOVEMENTS.contains(decision.movement());
        }

        private static boolean castingWhileFollowing(Mob chaser) {
            // 施放中的决策（HOLD、KEEP）沿用上一条移动指令；指令仍是跟随，就是边施放边追着对手走。
            boolean following = HumanoidBrain.directive(chaser.getUUID())
                    .filter(directive -> directive.movement() == HumanoidMotion.Movement.FOLLOW)
                    .isPresent();
            return following && HumanoidTechniqueHost.hasActiveCast(chaser);
        }

        private static Optional<HumanoidMind.Logged> last(Mob mob) {
            // 决策环形记录的最后一条；还没决策过时为空。
            var decisions = HumanoidBrain.decisions(mob.getUUID());
            if (decisions.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(decisions.getLast());
        }

        private static void observeCast(Mob mob, Set<String> casts) {
            var snapshot = ((HumanoidEntityAccess) mob).meridian$humanoidBody().snapshot();
            var progress = TechniqueState.read(BodyProtobuf.fromMessage(snapshot));
            if (progress.hasActiveCast()) {
                casts.add(progress.getActiveCast().getRequest().getTechnique().getId());
            }
        }

        private void finish() {
            boolean kicked = firstCasts.stream().anyMatch(id -> id.startsWith("meridian_peddler:"));
            if (expectation != Expectation.DUEL && !kicked) {
                fail("商人未尝试施放腿法反击");
                return;
            }
            if (expectation == Expectation.PEDDLER_HITS && !secondHurt) {
                fail("腿法未伤及凡人追兵");
                return;
            }
            if (damageEvents == 0) {
                fail("竞技场没有实际伤害");
                return;
            }
            if (expectation == Expectation.DUEL && (!firstAttacked || !secondAttacked)) {
                fail("对战存在一方全程没有攻击尝试");
                return;
            }
            LOGGER.info("arena complete tick={} damage={} firstCasts={} secondCasts={} first={} second={}",
                    ticks, damageEvents, firstCasts, secondCasts, describe(first), describe(second));
            clean();
            context.succeed();
        }

        private static boolean offensive(Set<String> casts) {
            // 准备、机动和恢复不算反击；强敌可以打断或躲开出手，不要求弱者每次都命中。
            return casts.stream().anyMatch(id -> id.startsWith("meridian_door_breaker:")
                    || id.startsWith("meridian_wayfarer:") || id.startsWith("meridian_peddler:"));
        }

        private void fail(String reason) {
            String evidence = reason + " tick=" + ticks + " first=" + describe(first)
                    + " second=" + describe(second) + " casts=" + firstCasts + "/" + secondCasts;
            LOGGER.error(evidence);
            clean();
            context.fail(evidence);
        }

        private String describe(Mob mob) {
            // 位置和寻路目标都减去结构原点的绝对坐标；GameTest 网格在远处，绝对坐标看着像跑去了远点。
            BlockPos origin = context.absolutePos(BlockPos.ZERO);
            var navigation = mob.getNavigation();
            var path = navigation.getPath();
            String destination = "none";
            if (path != null) {
                destination = path.getTarget().subtract(origin) + "/reachable=" + path.canReach();
            }
            var decisions = HumanoidBrain.decisions(mob.getUUID());
            String decision = "none";
            if (!decisions.isEmpty()) {
                decision = decisions.getLast().decision().toString();
            }
            Vec3 relative = mob.position().subtract(Vec3.atLowerCornerOf(origin));
            var snapshot = ((HumanoidEntityAccess) mob).meridian$humanoidBody().snapshot();
            var body = BodyProtobuf.fromMessage(snapshot);
            return mob.getType() + " hp=" + health(mob) + " pos=" + relative
                    + " condition=" + HumanoidFighters.condition(body)
                    + " legForce=" + HumanoidFighters.legForceNewtons(body)
                    + " navDone=" + navigation.isDone() + " path=" + destination + " decision=" + decision;
        }

        private void clean() {
            first.discard();
            second.discard();
        }
    }
}
