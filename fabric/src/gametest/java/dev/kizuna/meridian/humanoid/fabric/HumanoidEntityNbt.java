package dev.kizuna.meridian.humanoid.fabric;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** 测试经原版实体存读档入口往返 NBT；26.2 的实体存档改走 ValueOutput/ValueInput，标签形态不变。 */
final class HumanoidEntityNbt {
    private HumanoidEntityNbt() {
        // 只提供静态往返入口。
    }

    static CompoundTag save(Entity entity) {
        // 与区块保存同一路径写出不带 id 的实体标签，含 LivingEntity Mixin 写入的身体字段。
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
        entity.saveWithoutId(output);
        return output.buildResult();
    }

    static void load(Entity entity, CompoundTag nbt) {
        // 与区块读档同一路径读入实体标签，身体字段经 Mixin 的读档边界恢复。
        entity.load(TagValueInput.create(ProblemReporter.DISCARDING, entity.registryAccess(), nbt));
    }
}
