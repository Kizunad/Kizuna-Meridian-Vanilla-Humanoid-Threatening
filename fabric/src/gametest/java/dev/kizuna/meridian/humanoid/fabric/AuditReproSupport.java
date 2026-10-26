package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages;
import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages;
import dev.kizuna.meridian.protocol.pb.HealthMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.test.TestContext;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class AuditReproSupport {
    static final String BODY_TAG = "meridian_humanoid";
    static final String HUMAN_FILE = "meridian_human.dat";
    static final String SENDER_FILE = "meridian_humanoid.dat";
    static final String FIXTURE_HUMAN_FILE = "bad-ledger-human.dat";
    static final String FIXTURE_ENTITY_FILE = "bad-ledger-villager.nbt";
    static final String RELEASE_MARKER_FILE = "release-marker.nbt";
    static final int MAX_RECEIPTS = 32;
    static final int REQUEST_COUNT = 100;
    static final int CRASH_EXIT_CODE = 87;
    private static final int SPAWN_COORDINATE = 2;
    private static final Path EVIDENCE = Path.of(System.getProperty("meridian.repro.evidence"));

    private AuditReproSupport() {
        // 测试只共享协议和证据读写，不改生产宿主的执行路径。
    }

    static Path evidence(String fileName) {
        // 持久证据目录通过 JVM 参数指定，避免写入临时目录。
        return EVIDENCE.resolve(fileName);
    }

    static VillagerEntity villager(TestContext context, UUID id) {
        // UUID 在加入世界前固定，宿主索引与实体身份保持一致。
        VillagerEntity entity = EntityType.VILLAGER.create(context.getWorld());
        if (entity == null) {
            throw new IllegalStateException("无法创建测试村民");
        }
        entity.setUuid(id);
        spawn(context, entity);
        return entity;
    }

    static VillagerEntity restoredVillager(TestContext context, NbtCompound nbt) {
        // 真实 readNbt 在加入世界前恢复身份，覆盖 LivingEntity Mixin 的保存边界。
        VillagerEntity entity = EntityType.VILLAGER.create(context.getWorld());
        if (entity == null) {
            throw new IllegalStateException("无法创建恢复村民");
        }
        entity.readNbt(nbt);
        spawn(context, entity);
        return entity;
    }

    private static void spawn(TestContext context, VillagerEntity entity) {
        // 固定实体位置与 AI，避免寻路和重力影响本次持久化断言。
        BlockPos position = context.getAbsolutePos(
                new BlockPos(SPAWN_COORDINATE, SPAWN_COORDINATE, SPAWN_COORDINATE));
        entity.refreshPositionAndAngles(position.getX(), position.getY(), position.getZ(), 0, 0);
        entity.setAiDisabled(true /* aiDisabled */);
        entity.setNoGravity(true /* noGravity */);
        context.assertTrue(context.getWorld().spawnEntity(entity), "测试村民未加入真实世界");
    }

    static HealthHostMessages.HealthHostResponse inspect(LivingEntity entity) {
        // 使用公开健康字节协议，而不是直接调用内部状态计算。
        String subjectId = entity.getUuidAsString();
        var request = HealthHostMessages.HealthHostRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId("repro:inspect/" + subjectId)
                .setSubjectId(subjectId)
                .setInspect(true /* inspectBody */)
                .build();
        return exchange(request);
    }

    static HealthHostMessages.HealthHostResponse exchange(HealthHostMessages.HealthHostRequest request) {
        // 输入与输出都经过 Framework 协议编解码。
        return HealthHostProtobuf.decodeResponse(
                new HumanoidHealthHost().exchange(HealthHostProtobuf.encodeRequest(request)));
    }

    static ReceiptBatch commitReceipts(TestContext context, VillagerEntity entity) {
        // 同一 tick 连续提交，回执版本只由健康命令推进，便于核对完整窗口。
        var initial = inspect(entity);
        context.assertTrue(initial.hasBody(), "无法初始化身体：" + initial);
        BodyMessages.BodySnapshot body = initial.getBody();
        var requests = new ArrayList<HealthHostMessages.HealthHostRequest>();
        for (int index = 0; index < REQUEST_COUNT; index++) {
            var request = healRequest(entity, body, index);
            var response = exchange(request);
            context.assertTrue(response.hasBody() && response.hasHealth(), "健康提交失败：" + response);
            body = response.getBody();
            requests.add(request);
        }
        return new ReceiptBatch(body, List.copyOf(requests));
    }

    private static HealthHostMessages.HealthHostRequest healRequest(
            VillagerEntity entity, BodyMessages.BodySnapshot body, int index) {
        // 分开构造消息，保留真实健康命令和 expected_revision 校验。
        var target = BodyMessages.HealthTarget.newBuilder()
                .setPartId(body.getPartStates(0).getId())
                .build();
        var dose = HealthMessages.Healing.newBuilder()
                .setTarget(target)
                .setHp(1)
                .build();
        var healing = HealthMessages.HealBody.newBuilder()
                .setSourceId("repro:receipts")
                .addDoses(dose)
                .build();
        var command = MeridianMessages.BodyCommand.newBuilder()
                .setBodyId(body.getId())
                .setExpectedRevision(body.getRevision())
                .setHealBody(healing)
                .build();
        String requestId = "audit-request-" + index;
        var effect = MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(requestId)
                .setBodyCommand(command)
                .build();
        return HealthHostMessages.HealthHostRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(requestId)
                .setSubjectId(entity.getUuidAsString())
                .setEffect(effect)
                .build();
    }

    static HumanoidWorldState sender() throws ReflectiveOperationException {
        // 只读取测试运行中的真实宿主，不替换发送或接收实现。
        Field field = HumanoidFabric.class.getDeclaredField("worldState");
        field.setAccessible(true /* allowAccess */);
        Object value = field.get(null);
        if (value instanceof HumanoidWorldState state) {
            return state;
        }
        throw new IllegalStateException("类人释放账本未初始化");
    }

    static PersistentState human() throws ReflectiveOperationException {
        // Human 服务实例由 Fabric 加载器创建，测试不另建第二个接收端。
        for (ModInitializer initializer : FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class)) {
            Class<?> type = initializer.getClass();
            if (type.getName().equals("dev.kizuna.meridian.human.fabric.HumanFabric")) {
                Field field = type.getDeclaredField("state");
                field.setAccessible(true /* allowAccess */);
                Object value = field.get(initializer);
                if (value instanceof PersistentState state) {
                    return state;
                }
            }
        }
        throw new IllegalStateException("Human 真实接收账本未初始化");
    }

    static List<EnvironmentHostMessages.RegionRelease> pending(HumanoidWorldState state) throws IOException {
        // 读取生产 writeNbt 的结果，避免测试自己维护一份队列。
        NbtCompound saved = state.writeNbt(new NbtCompound());
        NbtList entries = saved.getList("pending", NbtElement.BYTE_ARRAY_TYPE);
        var releases = new ArrayList<EnvironmentHostMessages.RegionRelease>();
        for (int index = 0; index < entries.size(); index++) {
            var bytes = ((net.minecraft.nbt.NbtByteArray) entries.get(index)).getByteArray();
            releases.add(EnvironmentHostMessages.RegionRelease.parseFrom(bytes));
        }
        return List.copyOf(releases);
    }

    static boolean humanHasRelease(PersistentState state, String releaseId) throws IOException {
        // pending 与已受理 ID 都由同一 Human 持久化快照提供。
        NbtCompound saved = state.writeNbt(new NbtCompound());
        NbtList entries = saved.getList("release_ids", NbtElement.COMPOUND_TYPE);
        for (int index = 0; index < entries.size(); index++) {
            var request = EnvironmentHostMessages.RegionRelease.parseFrom(
                    entries.getCompound(index).getByteArray("request"));
            if (request.getReleaseId().equals(releaseId)) {
                return true;
            }
        }
        return false;
    }

    static Path worldFile(TestContext context, String fileName) {
        // 持久化操作只指向 GameTest 的世界目录。
        return context.getWorld().getServer().getSavePath(WorldSavePath.ROOT).resolve("data").resolve(fileName);
    }

    static void save(PersistentState state, Path file) throws IOException {
        // 使用原版保存实现，并验证确有可读取文件；markDirty 本身不作为持久化证据。
        Files.createDirectories(file.getParent());
        state.markDirty();
        state.save(file.toFile());
        // 校验保存文件可被 NBT 解码，损坏时抛出 IOException。
        NbtIo.readCompressed(file.toFile());
        force(file);
    }

    static void write(NbtCompound nbt, Path file) throws IOException {
        // 证据先落盘，再进入进程强制退出窗口。
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(nbt, file.toFile());
        force(file);
    }

    private static void force(Path file) throws IOException {
        // 测试的进程崩溃不应把证据输出本身留在缓冲区。
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true /* metaData */);
        }
    }

    static List<Long> savedRevisions(NbtCompound stored) {
        // 输出按版本排序的实际保留集合，便于核对期望的最新窗口。
        NbtList entries = stored.getList("receipts", NbtElement.BYTE_ARRAY_TYPE);
        var revisions = new ArrayList<Long>();
        for (int index = 0; index < entries.size(); index++) {
            byte[] bytes = ((net.minecraft.nbt.NbtByteArray) entries.get(index)).getByteArray();
            revisions.add(MeridianProtocol.decodeReceipt(bytes).getCommitted().getRevision());
        }
        revisions.sort(Long::compare);
        return List.copyOf(revisions);
    }

    record ReceiptBatch(BodyMessages.BodySnapshot body, List<HealthHostMessages.HealthHostRequest> requests) { }
}
