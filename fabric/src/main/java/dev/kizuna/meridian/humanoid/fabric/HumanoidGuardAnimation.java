package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.human.server.HumanTechniqueAnimations.GuardMotion;
import dev.kizuna.meridian.human.server.HumanTechniqueAnimations.GuardStyle;
import dev.kizuna.meridian.human.server.HumanTechniqueAnimations.Playback;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SustainedCast;
import dev.kizuna.playeranimation.mobs.MobAnimationServer;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;

/** 一次守式实际下发的保持段和短动作，由施放结束或实体卸载清理。 */
final class HumanoidGuardAnimation {
    private static final TagKey<Item> WEAPONS = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("meridian_vanilla_damage", "guard_weapons"));
    final String castId;
    private final LivingEntity entity;
    private final Playback clock;
    private GuardStyle holding;
    private String feedback;
    private boolean recovering;

    HumanoidGuardAnimation(LivingEntity entity, String castId, Playback clock) {
        this.entity = entity;
        this.castId = castId;
        this.clock = clock;
        playHold(mainHandStyle());
    }

    private GuardStyle mainHandStyle() {
        return entity.getMainHandItem().is(WEAPONS) ? GuardStyle.WEAPON : GuardStyle.ARMS;
    }

    void refresh() {
        if (holding == null) {
            return;
        }
        GuardStyle next = mainHandStyle();
        if (next != holding) {
            stopHold();
            playHold(next);
        }
    }

    private void playHold(GuardStyle style) {
        // 换兵和碎裂从同一个起播时刻续播，保留原起手进度和速度。
        MobAnimationServer.playFrom(entity, style.clip(GuardMotion.HOLD), clock.startTick(), clock.rate());
        holding = style;
    }

    void stopHold() {
        if (holding != null) {
            MobAnimationServer.stop(entity, holding.clip(GuardMotion.HOLD));
            holding = null;
        }
    }

    void hit(GuardStyle contact) {
        if (holding != null) {
            refresh();
            playFeedback(contact.clip(GuardMotion.HIT));
        }
    }

    void recover(SustainedCast sustained) {
        if (recovering) {
            return;
        }
        stopHold();
        GuardStyle style;
        if (sustained.hasWeaponContactOnBreak()) {
            style = sustained.getWeaponContactOnBreak() ? GuardStyle.WEAPON : GuardStyle.ARMS;
        } else {
            style = mainHandStyle();
        }
        GuardMotion motion = sustained.getExhausted() ? GuardMotion.HIT : GuardMotion.LOWER;
        playFeedback(style.clip(motion));
        recovering = true;
    }

    private void playFeedback(String clip) {
        // 同一片段再次挡中也要重播；改播另一段时先撤去之前的追踪补发。
        if (feedback != null && !feedback.equals(clip)) {
            MobAnimationServer.stop(entity, feedback);
        }
        MobAnimationServer.play(entity, clip);
        feedback = clip;
    }

    void stop() {
        stopHold();
        if (feedback != null) {
            MobAnimationServer.stop(entity, feedback);
            feedback = null;
        }
    }
}
