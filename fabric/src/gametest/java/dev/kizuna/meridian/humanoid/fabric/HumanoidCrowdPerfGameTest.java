package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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

/**
 * 性能基准，不是回归用例：20 只被大脑接管的类人在封闭石室里混战，量服务端每 tick 的耗时。
 * 只在 -PreproMode=perf 下单独运行，结果写到运行目录的 perf-crowd.txt。
 */
public final class HumanoidCrowdPerfGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid_perf");
    private static final String STRUCTURE = "meridian_humanoid_test:retreat";
    private static final String OUTPUT = "perf-crowd.txt";
    private static final int EDGE = 17;
    private static final int CEILING = 6;
    private static final int PAIRS = 10;
    private static final int ROW = 5;
    private static final int IDLE_TICKS = 40;
    private static final int WARMUP_TICKS = 200;
    private static final int WINDOW_TICKS = 600;
    private static final int BUCKET_TICKS = 100;
    private static final int TIMEOUT = IDLE_TICKS + WARMUP_TICKS + WINDOW_TICKS + 40;
    private static final double NANOS_PER_MILLI = 1_000_000.0;

    private static boolean listening;
    private static long tickStartedNanos;
    private static long lastTickNanos;
    private static final Set<UUID> casting = new HashSet<>();
    private static final Set<UUID> staggered = new HashSet<>();
    private static int castStarts;
    private static int staggerStarts;

    @GameTest(maxTicks = TIMEOUT, structure = STRUCTURE)
    public void twentyHumanoidsBrawl(GameTestHelper context) {
        listen();
        enclose(context);
        new Run(context).tick();
    }

    private static void listen() {
        // 整个服务端 tick 的墙钟时间；施放和僵直只听服务事件，观察本身不读身体。
        if (listening) {
            return;
        }
        listening = true;
        ServerTickEvents.START_SERVER_TICK.register(server -> tickStartedNanos = System.nanoTime());
        ServerTickEvents.END_SERVER_TICK.register(server -> lastTickNanos = System.nanoTime() - tickStartedNanos);
        var service = TechniqueFabric.service();
        service.addCastListener(event -> {
            if (event.outcome().getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED
                    && casting.add(event.entityUuid())) {
                castStarts++;
            }
        });
        service.addCastEndListener(event -> casting.remove(event.entityUuid()));
        service.addStaggerListener(event -> {
            if (event.remainingMillis() == 0) {
                staggered.remove(event.entityUuid());
            } else if (staggered.add(event.entityUuid())) {
                staggerStarts++;
            }
        });
    }

    private static void enclose(GameTestHelper context) {
        // 16×16 内室带地面、墙和顶棚，谁也跑不出去。
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

    private static List<Mob> spawnAll(GameTestHelper context) {
        // 十对卫道士对掠夺者，两档各半；互相指定目标后交给真实 AI。
        var fighters = new ArrayList<Mob>();
        for (int pair = 0; pair < PAIRS; pair++) {
            HumanoidTier tier = HumanoidTier.WANDERING_QI;
            if (pair % 2 == 0) {
                tier = HumanoidTier.TRUE_QI;
            }
            int row = pair / ROW;
            int z = 3 + pair % ROW * 3;
            var first = spawn(context, EntityTypes.VINDICATOR, tier, "vindicator-" + pair,
                    new BlockPos(3 + row * 2, 1, z));
            var second = spawn(context, EntityTypes.PILLAGER, tier, "pillager-" + pair,
                    new BlockPos(14 - row * 2, 1, z));
            first.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
            second.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
            first.setTarget(second);
            second.setTarget(first);
            fighters.add(first);
            fighters.add(second);
        }
        return fighters;
    }

    private static <T extends Mob> T spawn(GameTestHelper context, EntityType<T> type, HumanoidTier tier,
                                          String name, BlockPos relative) {
        // UUID 和随机种子固定，改前改后是同一批身体。
        T mob = type.create(context.getLevel(), EntitySpawnReason.COMMAND);
        UUID id = UUID.nameUUIDFromBytes(("crowd-perf/" + name).getBytes(StandardCharsets.UTF_8));
        mob.setUUID(id);
        mob.getRandom().setSeed(id.getMostSignificantBits());
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(relative));
        mob.snapTo(position.x, position.y, position.z, 0, 0);
        mob.setPersistenceRequired();
        HumanoidSpeciesBirths.birth(mob, tier);
        context.assertTrue(context.getLevel().addFreshEntity(mob), "基准实体未加入世界：" + name);
        HumanoidTechniqueHost.prepare(mob);
        return mob;
    }

    /** 一段连续 tick 的统计。 */
    private static final class Bucket {
        private final String label;
        private final long[] nanos;
        private int ticks;
        private long alive;
        private long casts;
        private long staggers;

        private Bucket(String label, int capacity) {
            this.label = label;
            nanos = new long[capacity];
        }

        private void add(long tickNanos, int aliveNow, int castsNow, int staggersNow) {
            nanos[ticks++] = tickNanos;
            alive += aliveNow;
            casts += castsNow;
            staggers += staggersNow;
        }

        private String describe() {
            long[] sorted = Arrays.copyOf(nanos, ticks);
            Arrays.sort(sorted);
            double mean = Arrays.stream(sorted).average().orElse(0) / NANOS_PER_MILLI;
            double median = sorted[ticks / 2] / NANOS_PER_MILLI;
            double high = sorted[Math.min(ticks - 1, ticks * 95 / 100)] / NANOS_PER_MILLI;
            double worst = sorted[ticks - 1] / NANOS_PER_MILLI;
            return String.format(Locale.ROOT,
                    "%s ticks=%d mean_ms=%.2f median_ms=%.2f p95_ms=%.2f max_ms=%.2f alive=%.1f casting=%.1f staggered=%.1f",
                    label, ticks, mean, median, high, worst, (double) alive / ticks, (double) casts / ticks,
                    (double) staggers / ticks);
        }
    }

    private static final class Run {
        private final GameTestHelper context;
        private final Bucket idle = new Bucket("idle", IDLE_TICKS);
        private final Bucket warmup = new Bucket("warmup", WARMUP_TICKS);
        private final Bucket window = new Bucket("window", WINDOW_TICKS);
        private final List<Bucket> buckets = new ArrayList<>();
        private List<Mob> fighters = List.of();
        private int ticks;

        private Run(GameTestHelper context) {
            this.context = context;
        }

        private void tick() {
            // 每次回调记上一个完整服务端 tick；先量空室，再放实体，热身后进入统计窗口。
            // 第一个 tick 不记：计时监听在它中途才登记，没有起点。
            int alive = (int) fighters.stream().filter(Mob::isAlive).count();
            if (ticks > 1 && ticks <= IDLE_TICKS) {
                idle.add(lastTickNanos, alive, 0, 0);
            } else if (ticks > IDLE_TICKS + 1 && ticks <= IDLE_TICKS + 1 + WARMUP_TICKS) {
                warmup.add(lastTickNanos, alive, casting.size(), staggered.size());
            } else if (ticks > IDLE_TICKS + 1 + WARMUP_TICKS) {
                int index = ticks - IDLE_TICKS - 2 - WARMUP_TICKS;
                if (index % BUCKET_TICKS == 0) {
                    buckets.add(new Bucket("window-" + index / BUCKET_TICKS, BUCKET_TICKS));
                }
                window.add(lastTickNanos, alive, casting.size(), staggered.size());
                buckets.getLast().add(lastTickNanos, alive, casting.size(), staggered.size());
            }
            if (ticks == IDLE_TICKS) {
                fighters = spawnAll(context);
                castStarts = 0;
                staggerStarts = 0;
            }
            if (ticks == IDLE_TICKS + 1 + WARMUP_TICKS + WINDOW_TICKS) {
                finish();
                return;
            }
            ticks++;
            context.runAfterDelay(1, this::tick);
        }

        private void finish() {
            var lines = new ArrayList<String>();
            lines.add(idle.describe());
            lines.add(warmup.describe());
            lines.add(window.describe());
            buckets.forEach(bucket -> lines.add(bucket.describe()));
            lines.add("cast_starts=" + castStarts + " stagger_starts=" + staggerStarts);
            lines.forEach(line -> LOGGER.info("crowd-perf {}", line));
            try {
                Files.write(Path.of(OUTPUT), lines, StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            fighters.forEach(Mob::discard);
            context.succeed();
        }
    }
}
