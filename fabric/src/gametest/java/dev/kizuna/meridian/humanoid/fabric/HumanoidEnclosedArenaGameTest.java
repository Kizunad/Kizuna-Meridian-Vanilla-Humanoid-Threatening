package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
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
    private static final BlockPos FIRST = new BlockPos(2, 1, 2);
    private static final BlockPos SECOND = new BlockPos(7, 1, 7);
    private static final BlockPos CORNER = new BlockPos(1, 1, 1);
    private static final BlockPos PURSUER = new BlockPos(3, 1, 3);

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
        return mob;
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
        private double firstHealth;
        private double secondHealth;
        private int ticks;
        private int quietTicks;
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
            observeDamage();
            observeCast(first, firstCasts);
            observeCast(second, secondCasts);
            firstAttacked |= first.swinging || first.isUsingItem() || offensive(firstCasts);
            secondAttacked |= second.swinging || second.isUsingItem() || offensive(secondCasts);
            if (ticks % SAMPLE_TICKS == 0) {
                LOGGER.info("arena={} tick={} quiet={} damage={} first={} second={}",
                        first.getStringUUID(), ticks, quietTicks, damageEvents, describe(first), describe(second));
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
            double currentFirst = health(first);
            double currentSecond = health(second);
            if (currentFirst < firstHealth || currentSecond < secondHealth) {
                quietTicks = 0;
                damageEvents++;
            } else {
                quietTicks++;
            }
            secondHurt |= currentSecond < secondHealth;
            firstHealth = currentFirst;
            secondHealth = currentSecond;
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
            var navigation = mob.getNavigation();
            var path = navigation.getPath();
            String destination = "none";
            if (path != null) {
                destination = path.getTarget() + "/reachable=" + path.canReach();
            }
            var decisions = HumanoidBrain.decisions(mob.getUUID());
            String decision = "none";
            if (!decisions.isEmpty()) {
                decision = decisions.getLast().decision().toString();
            }
            Vec3 relative = mob.position().subtract(Vec3.atLowerCornerOf(context.absolutePos(BlockPos.ZERO)));
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
