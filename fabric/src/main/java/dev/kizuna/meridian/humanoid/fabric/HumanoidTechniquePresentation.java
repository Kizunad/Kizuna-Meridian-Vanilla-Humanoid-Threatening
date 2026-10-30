package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.human.server.HumanTechniqueAnimations;
import dev.kizuna.meridian.human.server.HumanTechniqueAnimations.CastHand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationAction;
import dev.kizuna.playeranimation.mobs.MobAnimationServer;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.entity.LivingEntity;

/** 类人功法通过 Mobs-Addon 同步 PA 表现，与玩家共用公开的人体片段映射。 */
final class HumanoidTechniquePresentation {
    private static final long MILLIS_PER_TICK = 50;
    private final Map<String, String> playing = new HashMap<>();

    void apply(LivingEntity entity, EffectIntent effect) {
        // STOP 使用实际起播片段，避免方向变化后停止了另一段轻步。
        var presentation = effect.getPresentation();
        String key = effect.getCastId() + "/" + presentation.getId();
        if (presentation.getAction() == PresentationAction.STOP) {
            String previous = playing.remove(key);
            if (previous != null) {
                MobAnimationServer.stop(entity, previous);
            }
            return;
        }
        var input = effect.getDirection();
        var direction = new HumanTechniqueAnimations.Direction(entity.getYaw(), input.getX(), input.getZ());
        CastHand hand = effect.getSelectedHand() == Hand.OFF_HAND ? CastHand.OFF : CastHand.MAIN;
        String animation = HumanTechniqueAnimations.resolve(presentation.getId(), direction, hand);
        long nowMillis = entity.getServer().getOverworld().getTime() * MILLIS_PER_TICK;
        long lateness = Math.max(0, nowMillis - effect.getScheduledMillis());
        long offsetTicks = (presentation.getStartMillis() + lateness) / MILLIS_PER_TICK;
        long startTick = Math.max(0, entity.getWorld().getTime() - offsetTicks);
        MobAnimationServer.playFrom(entity, animation, startTick);
        playing.put(key, animation);
    }
}
