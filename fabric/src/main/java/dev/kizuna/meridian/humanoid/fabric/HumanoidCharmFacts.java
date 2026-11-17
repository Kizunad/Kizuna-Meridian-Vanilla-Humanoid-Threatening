package dev.kizuna.meridian.humanoid.fabric;

import net.minecraft.world.entity.LivingEntity;

/** 给别的附属问「这只生物是不是正被魅惑着」用的口子：签名里不带 Brewing 的类型，调用方不用为这一句依赖 Brewing。 */
public final class HumanoidCharmFacts {
    private HumanoidCharmFacts() {
        // 只转问魅惑控制器，不存状态。
    }

    public static boolean controlled(LivingEntity entity) {
        // 正在魅惑会话里的受控者。
        return HumanoidCharmController.controlled(entity);
    }
}
