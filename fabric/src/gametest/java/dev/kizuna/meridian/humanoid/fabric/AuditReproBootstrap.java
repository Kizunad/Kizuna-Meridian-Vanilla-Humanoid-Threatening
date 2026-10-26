package dev.kizuna.meridian.humanoid.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class AuditReproBootstrap implements ModInitializer {
    private static final String BAD_LEDGER_MODE = "bad-ledger";
    private static final long INVALID_SEQUENCE = -1;

    @Override
    public void onInitialize() {
        // 只在专用坏档启动模式注入文件，正常测试不替换任何宿主对象。
        String mode = System.getProperty("meridian.repro.mode");
        if (BAD_LEDGER_MODE.equals(mode)) {
            ServerLifecycleEvents.SERVER_STARTING.register(AuditReproBootstrap::writeCorruptLedger);
        }
    }

    private static void writeCorruptLedger(MinecraftServer server) {
        // 在 SERVER_STARTED 加载账本前准备损坏数据，只作用于专用坏档测试世界。
        try {
            var data = server.getSavePath(WorldSavePath.ROOT).resolve("data");
            Files.createDirectories(data);
            var fixture = AuditReproSupport.evidence(AuditReproSupport.FIXTURE_HUMAN_FILE);
            Files.copy(fixture, data.resolve(AuditReproSupport.HUMAN_FILE), StandardCopyOption.REPLACE_EXISTING);
            NbtCompound human = NbtIo.readCompressed(fixture.toFile());
            NbtCompound corrupt = new NbtCompound();
            corrupt.putLong("next_sequence", INVALID_SEQUENCE);
            corrupt.put("pending", new NbtList());
            corrupt.put("pending_forget", new NbtList());
            NbtCompound root = new NbtCompound();
            root.putInt("DataVersion", human.getInt("DataVersion"));
            root.put("data", corrupt);
            AuditReproSupport.write(root, data.resolve(AuditReproSupport.SENDER_FILE));
            System.out.println("REPRO-A-04-BOOT corrupt-ledger=" + data.resolve(AuditReproSupport.SENDER_FILE));
        } catch (IOException failure) {
            throw new IllegalStateException("坏账本启动夹具无法写入", failure);
        }
    }
}
