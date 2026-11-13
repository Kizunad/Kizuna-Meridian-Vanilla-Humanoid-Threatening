package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidBrainConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

/** 盯上距离：配置里列出的物种把原版跟踪距离抬到配置值，远处交战的不被当作闲着随机消失。 */
final class HumanoidNotice {
    /** 抬高跟踪距离的临时修饰 ID：有这条修饰的才按盯上距离算交战，GameTest 也按它确认改了哪些物种。 */
    static final Identifier MODIFIER = Identifier.parse("meridian_humanoid:notice_distance");

    private HumanoidNotice() {
        // 只改原版属性和计时，不保存状态。
    }

    static void widen(Mob mob, HumanoidBrainConfig.ThreatRules rules) {
        // 原版选敌、放下目标都按跟踪距离算；临时修饰不写进存档，读档出来的新对象接入时重新加。
        // 按总值相乘正好到配置值，原版出生时的随机加成也算在内；原值已经够远或为零（不选敌）的不动。
        String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
        Double notice = rules.noticeDistances().get(entityId);
        AttributeInstance range = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (notice == null || range == null) {
            return;
        }
        range.removeModifier(MODIFIER);
        double vanilla = range.getValue();
        if (vanilla <= 0 || vanilla >= notice) {
            return;
        }
        double amount = notice / vanilla - 1;
        var modifier = new AttributeModifier(MODIFIER, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        range.addTransientModifier(modifier);
    }

    static void keepEngaged(Mob mob, LivingEntity opponent) {
        // 原版只在 32 格内有玩家时清零无动作计时，32 格外计时过 600 的每 tick 有八百分之一的机会消失
        // （掠夺者每 tick 记 3，十秒就过）；盯上距离放宽了的，跟跟踪距离内的玩家交战照样算有动作。
        // 128 格外的立即消失照旧。
        AttributeInstance range = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (range == null || range.getModifier(MODIFIER) == null || !(opponent instanceof Player)) {
            return;
        }
        if (mob.distanceTo(opponent) <= range.getValue()) {
            mob.setNoActionTime(0);
        }
    }
}
