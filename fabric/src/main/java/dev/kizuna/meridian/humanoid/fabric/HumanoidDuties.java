package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import java.util.Optional;
import net.minecraft.world.entity.LivingEntity;

/**
 * 给生人挂「职守」的公开入口：别的附属在自己的时机来挂、来撤，本模组不认是什么职业。
 *
 * <p>职守记成实体的记分板标签，随实体存盘，重载以后还在；命令和结构 NBT 也能直接打这个标签。</p>
 */
public final class HumanoidDuties {
    /** 不退：不比战力、不看伤势，只有两臂虚脱或没有进攻手段时才撤。 */
    public static final String STAND_GROUND_TAG = "meridian_humanoid.duty.stand_ground";

    private HumanoidDuties() {
        // 纯静态入口。
    }

    public static void standGround(LivingEntity entity, boolean enabled) {
        // 挂上或撤掉「不退」；下一次决策起生效。
        if (enabled) {
            entity.addTag(STAND_GROUND_TAG);
        } else {
            entity.removeTag(STAND_GROUND_TAG);
        }
    }

    /**
     * 任职时把两臂肌肉的训练成长抬到给定值（游炁、真炁各一个数），只升不降；凡人不动。
     * 身体这一刻读不到（不是已认领的生人、世界没接好、区域服务不可用）时返回 false，调用方下一 tick 再来。
     */
    public static boolean trainArms(LivingEntity entity, double wanderingQiGrowth, double trueQiGrowth) {
        // 档位按需出生后从身体读回；成长直接记进身体，随身体存盘。
        Optional<HumanoidTier> tier = HumanoidTiers.of(entity);
        if (tier.isEmpty()) {
            return false;
        }
        if (tier.get() != HumanoidTier.MORTAL) {
            double growth = tier.get() == HumanoidTier.TRUE_QI ? trueQiGrowth : wanderingQiGrowth;
            HumanoidTechniqueHost.prepare(entity).body().raiseArmGrowth(growth);
        }
        return true;
    }

    /**
     * 保底开一条经脉：带这个标签的经脉（例如 {@code human:hand_meridian}）一条都没开时，开其中第一条，
     * 扩容量和出生时开的脉一样；已经有开了的就不动。凡人不动，免得凡人因此变成游炁。
     * 身体这一刻读不到时返回 false，调用方下一 tick 再来。
     */
    public static boolean ensureOpened(LivingEntity entity, String meridianTag) {
        Optional<HumanoidTier> tier = HumanoidTiers.of(entity);
        if (tier.isEmpty()) {
            return false;
        }
        if (tier.get() != HumanoidTier.MORTAL) {
            HumanoidTechniqueHost.prepare(entity).body().openOne(meridianTag,
                    HumanoidFabric.config().openedLumenGain());
        }
        return true;
    }

    public static boolean standsGround(LivingEntity entity) {
        return entity.entityTags().contains(STAND_GROUND_TAG);
    }
}
