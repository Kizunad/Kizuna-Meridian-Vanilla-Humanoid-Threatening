package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.fabric.mixin.DonationLevelAccess;
import dev.kizuna.meridian.humanoid.fabric.mixin.DonationSectionAccess;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;

/** 使用原版实体存储的分区快照与有序写队列；只确认这次提交写入并刷盘的结果。 */
public final class DonationChunkSave {
    private static final ScopedValue<DonationChunkSave> ACTIVE = ScopedValue.newInstance();
    private final ChunkPos chunk;
    private CompletableFuture<Void> written;
    private boolean includesRoot;

    private DonationChunkSave(ChunkPos chunk) {
        this.chunk = chunk;
    }

    static CompletableFuture<Void> submit(ServerLevel level, Entity elder) {
        // 乘客随根载具保存。未就绪的区块由原版请求载入，未取得实际写回执时保留原案重试。
        Entity root = elder.getRootVehicle();
        if (!root.shouldBeSaved() || level.getEntity(root.getUUID()) != root) {
            return CompletableFuture.failedFuture(new IllegalStateException("出资访客尚不能保存"));
        }
        var ticket = new DonationChunkSave(root.chunkPosition());
        var manager = ((DonationLevelAccess) level).meridian$entityManager();
        @SuppressWarnings("unchecked")
        var sections = (DonationSectionAccess<Entity>) manager;
        ScopedValue.where(ACTIVE, ticket).run(() -> sections.meridian$storeChunkSections(
                ticket.chunk.pack(), entity -> {
                    if (entity == root) {
                        ticket.includesRoot = true;
                    }
                }));
        if (!ticket.includesRoot || ticket.written == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("出资访客区块尚未保存"));
        }
        return ticket.written;
    }

    public static void capture(ChunkPos chunk, CompletableFuture<?> write, SimpleRegionStorage storage) {
        // 原版保留自己的报错处理；只有本次指定区块的实体写入才需要额外的持久确认。
        if (ACTIVE.isBound()) {
            var ticket = ACTIVE.get();
            if (ticket.chunk.equals(chunk)) {
                ticket.written = write.thenCompose(ignored -> storage.synchronize(true /* flush */));
            }
        }
    }
}
