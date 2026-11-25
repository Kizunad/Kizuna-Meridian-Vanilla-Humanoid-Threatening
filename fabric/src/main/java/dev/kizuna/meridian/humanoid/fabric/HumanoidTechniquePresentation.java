package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.human.server.HumanTechniqueAnimations;
import dev.kizuna.meridian.human.server.HumanTechniqueAnimations.CastHand;
import dev.kizuna.meridian.human.server.HumanTechniqueAnimations.Playback;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationAction;
import dev.kizuna.playeranimation.mobs.MobAnimationServer;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.entity.LivingEntity;

/** 类人功法通过 Mobs-Addon 同步 PA 表现，与玩家共用公开的人体片段映射。 */
final class HumanoidTechniquePresentation {
    private static final long MILLIS_PER_TICK = 50;
    private static final String GUARD = "meridian_basic_techniques:guard_arms";
    private final Map<String, String> playing = new HashMap<>();
    private HumanoidGuardAnimation guard;

    void stopAll(LivingEntity entity) {
        clearGuard();
        playing.values().forEach(animation -> MobAnimationServer.stop(entity, animation));
        playing.clear();
    }

    void apply(LivingEntity entity, EffectIntent effect) {
        // STOP 使用实际起播片段，避免方向变化后停止了另一段轻步。
        var presentation = effect.getPresentation();
        String castId = effect.getCastId();
        String id = presentation.getId();
        boolean stopping = presentation.getAction() == PresentationAction.STOP;
        if (id.equals(GUARD)) {
            if (stopping) {
                var current = guardFor(castId);
                if (current != null) {
                    current.stopHold();
                }
            } else {
                clearGuard();
                guard = new HumanoidGuardAnimation(entity, castId, playback(entity, effect));
            }
            return;
        }
        String key = castId + "/" + id;
        if (stopping) {
            String previous = playing.remove(key);
            if (previous != null) {
                MobAnimationServer.stop(entity, previous);
            }
            return;
        }
        var input = effect.getDirection();
        var direction = new HumanTechniqueAnimations.Direction(entity.getYRot(), input.getX(), input.getZ());
        CastHand hand = effect.getSelectedHand() == Hand.OFF_HAND ? CastHand.OFF : CastHand.MAIN;
        String animation = HumanTechniqueAnimations.resolve(id, direction, hand);
        var playback = playback(entity, effect);
        MobAnimationServer.playFrom(entity, animation, playback.startTick(), playback.rate());
        playing.put(key, animation);
    }

    private static Playback playback(LivingEntity entity, EffectIntent effect) {
        var presentation = effect.getPresentation();
        long nowMillis = entity.level().getServer().overworld().getGameTime() * MILLIS_PER_TICK;
        long lateness = Math.max(0, nowMillis - effect.getScheduledMillis());
        // 没带倍率的旧意图按原速播。
        double speed = 1;
        if (presentation.hasPlaybackSpeedMultiplier()) {
            speed = presentation.getPlaybackSpeedMultiplier();
        }
        return HumanTechniqueAnimations.playback(entity.level().getGameTime(), presentation.getStartMillis(),
                lateness, speed);
    }

    HumanoidGuardAnimation guardFor(String castId) {
        if (guard != null && guard.castId.equals(castId)) {
            return guard;
        }
        return null;
    }

    void refreshGuard() {
        if (guard != null) {
            guard.refresh();
        }
    }

    void endGuard(String castId) {
        if (guardFor(castId) != null) {
            clearGuard();
        }
    }

    void clearGuard() {
        if (guard != null) {
            guard.stop();
            guard = null;
        }
    }
}
