package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.inventory.MobInventories;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/** 在同一测试世界分别启停两个 GameTest 进程，第二个进程从区块找到同一只缴械生人。 */
public final class BirthEquipmentRestartGameTest {
    private static final int OUTSIDE_STRUCTURE = 96;
    private static final int LOAD_TIMEOUT = 200;

    @GameTest(maxTicks = LOAD_TIMEOUT)
    public void disarmedCarrierSurvivesServerRestart(GameTestHelper context) throws IOException {
        var level = context.getLevel();
        Path marker = AuditReproSupport.worldFile(context, "birth-equipment-test.nbt");
        if ("equipment-save".equals(System.getProperty("meridian.repro.mode"))) {
            saveDisarmedCarrier(context, marker);
            return;
        }
        var record = AuditReproSupport.read(marker);
        var id = UUID.fromString(record.getString("uuid").orElseThrow());
        var chunk = ChunkPos.unpack(record.getLong("chunk").orElseThrow());
        level.setChunkForced(chunk.x(), chunk.z(), true /* forced */);
        level.getChunk(chunk.x(), chunk.z());
        context.succeedWhen(() -> {
            var entity = level.getEntity(id);
            context.assertTrue(entity instanceof Vindicator, "重启后尚未从原区块找到生人");
            var mob = (Vindicator) entity;
            context.assertTrue(BirthEquipmentGameTest.outcome(mob) == BirthVolumes.Equipment.PACKED,
                    "重启丢失配装终态");
            context.assertTrue(MobInventories.inventory(mob).snapshot().stream().allMatch(stack -> stack.isEmpty()),
                    "重启给已缴械的生人补发了武器");
            level.setChunkForced(chunk.x(), chunk.z(), false /* forced */);
            mob.discard();
        });
    }

    private static void saveDisarmedCarrier(GameTestHelper context, Path marker) {
        var level = context.getLevel();
        var mob = BirthEquipmentGameTest.pending(context, EntityTypes.VINDICATOR, BirthEquipmentGameTest.BOW);
        var position = context.absolutePos(new BlockPos(OUTSIDE_STRUCTURE, 1, 1));
        var chunk = ChunkPos.containing(position);
        level.setChunkForced(chunk.x(), chunk.z(), true /* forced */);
        mob.setNoGravity(true /* noGravity */);
        mob.setPersistenceRequired();
        BirthEquipmentGameTest.enter(context, mob);
        var taken = MobInventories.inventory(mob).extract(0, 1);
        context.assertTrue(taken.is(Items.BOW), "关服前未获得可缴械的出生弓");
        // 在已加载的测试结构内完成出生，再移至结构清理范围之外保存。
        level.getChunkAt(position);
        mob.setPos(Vec3.atBottomCenterOf(position));
        var record = new CompoundTag();
        record.putString("uuid", mob.getStringUUID());
        record.putLong("chunk", chunk.pack());
        context.succeedWhen(() -> {
            context.assertTrue(level.getEntity(mob.getUUID()) == mob, "待保存的生人尚未载入目标区块");
            level.getServer().saveAllChunks(false /* suppressLogs */, true /* flush */, true /* force */);
            try {
                AuditReproSupport.write(record, marker);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
    }
}
