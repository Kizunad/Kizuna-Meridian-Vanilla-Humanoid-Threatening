package dev.kizuna.meridian.humanoid.fabric;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** 撤退中有没有被对手逼着：比较前后两次决策看到的对手，它朝我挪近了、或者我又挨了它一下，才算。 */
final class HumanoidPressure {
    static final int NEVER_STRUCK = -1;
    private static final double MILLIS_PER_SECOND = 1000;
    /** 对手自己朝我这边挪得不慢于这个速度（格/秒）才算逼近；原地转身、被挤着蹭动都到不了。 */
    private static final double PRESSING_SPEED = 0.5;

    /** 一次决策时看到的对手：它站在哪，我最近一次挨它打记在自己的第几个 tick（没有记 -1），以及当时的身体时钟。 */
    record Sighting(Vec3 position, int struckTick, long millis) { }

    private HumanoidPressure() {
        // 纯函数集合；前一次看到的对手记在 HumanoidMind。
    }

    static Sighting sight(Mob mob, LivingEntity threat, long nowMillis) {
        // 挨打读原版受击记忆；记着的不是这个对手，就当没挨过它的打。
        int struckTick = NEVER_STRUCK;
        if (mob.getLastHurtByMob() == threat) {
            struckTick = mob.getLastHurtByMobTimestamp();
        }
        return new Sighting(threat.position(), struckTick, nowMillis);
    }

    static boolean pressing(Vec3 self, Sighting before, Sighting now) {
        // 两次决策之间又挨了它一下，或者它自己的位移让它到我现在位置的水平距离缩短得够快，都算被逼着。
        // 我自己退开拉大的距离不算在它头上；受击记忆过期只会让时刻变回「没有」，不当成新挨的一下。
        if (now.struckTick() > before.struckTick()) {
            return true;
        }
        double seconds = (now.millis() - before.millis()) / MILLIS_PER_SECOND;
        double closed = horizontal(before.position(), self) - horizontal(now.position(), self);
        return seconds > 0 && closed >= PRESSING_SPEED * seconds;
    }

    private static double horizontal(Vec3 from, Vec3 to) {
        return Math.hypot(to.x - from.x, to.z - from.z);
    }
}
