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
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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

    static Villager villager(GameTestHelper context, UUID id) {
        // UUID 在加入世界前固定，宿主索引与实体身份保持一致；GameTest 世界跨运行保留，上次留下的同 UUID 实体先移除。
        // 26.2 创建实体须注明来源；测试直接放置，按命令生成处理。
        Entity stale = context.getLevel().getEntity(id);
        if (stale != null) {
            stale.discard();
        }
        Villager entity = EntityTypes.VILLAGER.create(context.getLevel(), EntitySpawnReason.COMMAND);
        if (entity == null) {
            throw new IllegalStateException("无法创建测试村民");
        }
        entity.setUUID(id);
        spawn(context, entity);
        return entity;
    }

    static Villager restoredVillager(GameTestHelper context, CompoundTag nbt) {
        // 真实 load 在加入世界前恢复身份，覆盖 LivingEntity Mixin 的保存边界；26.2 创建实体须注明来源，按读档处理。
        Villager entity = EntityTypes.VILLAGER.create(context.getLevel(), EntitySpawnReason.LOAD);
        if (entity == null) {
            throw new IllegalStateException("无法创建恢复村民");
        }
        HumanoidEntityNbt.load(entity, nbt);
        spawn(context, entity);
        return entity;
    }

    private static void spawn(GameTestHelper context, Villager entity) {
        // 固定实体位置与 AI，避免寻路和重力影响本次持久化断言；26.2 的 moveTo 改名 snapTo。
        BlockPos position = context.absolutePos(
                new BlockPos(SPAWN_COORDINATE, SPAWN_COORDINATE, SPAWN_COORDINATE));
        entity.snapTo(position.getX(), position.getY(), position.getZ(), 0, 0);
        entity.setNoAi(true /* aiDisabled */);
        entity.setNoGravity(true /* noGravity */);
        context.assertTrue(context.getLevel().addFreshEntity(entity), "测试村民未加入真实世界");
    }

    static HealthHostMessages.HealthHostResponse inspect(LivingEntity entity) {
        // 使用公开健康字节协议，而不是直接调用内部状态计算。
        String subjectId = entity.getStringUUID();
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

    static ReceiptBatch commitReceipts(GameTestHelper context, Villager entity) {
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
            Villager entity, BodyMessages.BodySnapshot body, int index) {
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
                .setSubjectId(entity.getStringUUID())
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

    static HumanLedger human() throws ReflectiveOperationException {
        // Human 服务实例由 Fabric 加载器创建，测试不另建第二个接收端；26.2 起账本不再是 SavedData，按反射包一层。
        for (ModInitializer initializer : FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class)) {
            Class<?> type = initializer.getClass();
            if (type.getName().equals("dev.kizuna.meridian.human.fabric.HumanFabric")) {
                Field field = type.getDeclaredField("state");
                field.setAccessible(true /* allowAccess */);
                Object value = field.get(initializer);
                if (value != null) {
                    return new HumanLedger(value);
                }
            }
        }
        throw new IllegalStateException("Human 真实接收账本未初始化");
    }

    static List<EnvironmentHostMessages.RegionRelease> pending(HumanoidWorldState state) throws IOException {
        // 读取生产 save 的结果，避免测试自己维护一份队列。
        CompoundTag saved = state.save(new CompoundTag());
        ListTag entries = saved.getListOrEmpty("pending");
        var releases = new ArrayList<EnvironmentHostMessages.RegionRelease>();
        for (int index = 0; index < entries.size(); index++) {
            var bytes = ((net.minecraft.nbt.ByteArrayTag) entries.get(index)).getAsByteArray();
            releases.add(EnvironmentHostMessages.RegionRelease.parseFrom(bytes));
        }
        return List.copyOf(releases);
    }

    static boolean humanHasRelease(HumanLedger state, String releaseId) throws IOException {
        // pending 与已受理 ID 都由同一 Human 持久化快照提供。
        return savedHasRelease(state.snapshot(), releaseId);
    }

    static boolean humanFileHasRelease(GameTestHelper context, String releaseId) throws IOException {
        // 直接读取正式文件，排除只在接收端内存受理的假阳性。
        CompoundTag saved = read(worldFile(context, HUMAN_FILE)).getCompoundOrEmpty("data");
        return savedHasRelease(saved, releaseId);
    }

    private static boolean savedHasRelease(CompoundTag saved, String releaseId) throws IOException {
        // 内存快照和正式文件使用同一解码，逐项核对实际保存的原请求 ID。
        ListTag entries = saved.getListOrEmpty("release_ids");
        for (int index = 0; index < entries.size(); index++) {
            var request = EnvironmentHostMessages.RegionRelease.parseFrom(
                    entries.getCompoundOrEmpty(index).getByteArray("request").orElse(new byte[0]));
            if (request.getReleaseId().equals(releaseId)) {
                return true;
            }
        }
        return false;
    }

    static Path worldFile(GameTestHelper context, String fileName) {
        // 持久化操作只指向 GameTest 的世界目录。
        return context.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve(fileName);
    }

    static void save(HumanoidWorldState state, Path file) throws IOException {
        // 发送端账本按原版存档结构直接写出，不经过宿主的 tick 末交接。
        saveData(state.save(new CompoundTag()), file);
    }

    static void save(HumanLedger state, Path file) throws IOException {
        // 按原版存档结构直接写出；Human 的 save(File) 同 tick 只写一次，夹具不能依赖它。
        saveData(state.snapshot(), file);
    }

    private static void saveData(CompoundTag data, Path file) throws IOException {
        // 两端账本文件外层相同：data 字段放正文，并带当前数据版本。
        var root = new CompoundTag();
        root.put("data", data);
        NbtUtils.addCurrentDataVersion(root);
        write(root, file);
    }

    static CompoundTag read(Path file) throws IOException {
        // 读回压缩 NBT 文件；测试证据和世界账本都不设读取上限。
        return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
    }

    static void write(CompoundTag nbt, Path file) throws IOException {
        // 证据先落盘，再进入进程强制退出窗口。
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(nbt, file);
        force(file);
    }

    private static void force(Path file) throws IOException {
        // 测试的进程崩溃不应把证据输出本身留在缓冲区。
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true /* metaData */);
        }
    }

    static List<Long> savedRevisions(CompoundTag stored) {
        // 输出按版本排序的实际保留集合，便于核对期望的最新窗口。
        ListTag entries = stored.getListOrEmpty("receipts");
        var revisions = new ArrayList<Long>();
        for (int index = 0; index < entries.size(); index++) {
            byte[] bytes = ((net.minecraft.nbt.ByteArrayTag) entries.get(index)).getAsByteArray();
            revisions.add(MeridianProtocol.decodeReceipt(bytes).getCommitted().getRevision());
        }
        revisions.sort(Long::compare);
        return List.copyOf(revisions);
    }

    record ReceiptBatch(BodyMessages.BodySnapshot body, List<HealthHostMessages.HealthHostRequest> requests) { }

    /** Human 真实接收账本；类型在 Human 包内不公开，测试只经反射读取它的持久化快照和 dirty 标记。 */
    record HumanLedger(Object state) {
        CompoundTag snapshot() {
            // 调用 Human 自己的 save(CompoundTag)，与写盘内容同源。
            return (CompoundTag) invoke("save", new Class<?>[] {CompoundTag.class}, new CompoundTag());
        }

        boolean isDirty() {
            // 写盘失败时 Human 保留 dirty，测试据此确认没有误清。
            return (boolean) invoke("isDirty", new Class<?>[0]);
        }

        private Object invoke(String name, Class<?>[] parameters, Object... arguments) {
            // 账本方法自身抛出的运行期异常原样上抛，反射失败才包装成测试环境错误。
            try {
                Method method = state.getClass().getDeclaredMethod(name, parameters);
                method.setAccessible(true /* allowAccess */);
                return method.invoke(state, arguments);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException("Human 账本方法失败：" + name, failure.getCause());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("无法调用 Human 账本方法：" + name, failure);
            }
        }
    }
}
