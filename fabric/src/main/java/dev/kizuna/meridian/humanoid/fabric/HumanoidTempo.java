package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.human.server.HumanLimbTempo;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** 组织 tempo 只调整原版移动与攻击间隔，功法时长仍由 Technique 计算。 */
public final class HumanoidTempo {
    private static final Identifier MODIFIER = Identifier.parse("meridian_humanoid:body_tempo");
    private static final double MIN_CHANGE = 0.001;
    private static final long MILLIS_PER_TICK = 50;
    private static final Map<Mob, HumanLimbTempo> indices = new WeakHashMap<>();
    private static final Map<Mob, Double> arms = new WeakHashMap<>();

    private HumanoidTempo() {
        // 只缓存构型和投影值，权威修正仍保存在身体中。
    }

    public static void update(Mob mob) {
        // 没有已创建身体时不为纯原版 AI 强行初始化；曲线直接按当前可信时刻求值。
        var bodyState = ((HumanoidEntityAccess) mob).meridian$humanoidBody();
        if (bodyState == null) {
            return;
        }
        var body = bodyState.currentBody();
        if (body.modifiers().isEmpty()) {
            // 无修正的闲置生物不遍历肌肉；曾经投影过倍率的实体仍须归一。
            if (arms.remove(mob) != null) {
                movement(mob, 1);
            }
            return;
        }
        HumanLimbTempo index = index(mob, body);
        HumanLimbTempo.Side side = HumanLimbTempo.Side.RIGHT;
        if (mob.getMainArm() == HumanoidArm.LEFT) {
            side = HumanLimbTempo.Side.LEFT;
        }
        long millis = mob.level().getServer().overworld().getGameTime() * MILLIS_PER_TICK;
        var multipliers = index.at(body, side, millis);
        arms.put(mob, multipliers.arm());
        movement(mob, multipliers.movement());
    }

    private static HumanLimbTempo index(Mob mob, BodySnapshot body) {
        // 只有确有修正且构型改变时才重建索引，缓存不保留权威身体。
        HumanLimbTempo cached = indices.get(mob);
        if (cached != null && cached.matches(body)) {
            return cached;
        }
        var index = new HumanLimbTempo(body.structure());
        indices.put(mob, index);
        return index;
    }

    private static void movement(Mob mob, double multiplier) {
        // 原版同步脏属性，只在累计变化超过阈值或归一时更新。
        var attribute = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        var previous = attribute.getModifier(MODIFIER);
        double amount = multiplier - 1;
        double previousAmount = 0;
        if (previous != null) {
            previousAmount = previous.amount();
        }
        if (amount == 0 && previous == null || amount != 0 && Math.abs(previousAmount - amount) < MIN_CHANGE) {
            return;
        }
        attribute.removeModifier(MODIFIER);
        if (amount != 0) {
            attribute.addTransientModifier(new AttributeModifier(MODIFIER, amount,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    public static int attackInterval(Mob mob, int vanillaTicks) {
        // 沿用物种和距离决定的原版间隔，只除以主手臂动作速度一次。
        double tempo = arms.getOrDefault(mob, 1.0);
        return Math.max(1, (int) Math.ceil(vanillaTicks / tempo));
    }
}
