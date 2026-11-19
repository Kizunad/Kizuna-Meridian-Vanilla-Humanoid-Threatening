package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueService.StaggerKind;
import dev.kizuna.playeranimation.mobs.MobAnimationServer;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * 类人生物僵直时的动作，和玩家同一段 PA 剪辑：受击僵直和魅惑定身开始时播，结束时停。
 * 剪辑 0.8 秒后停在末帧，不会自己结束，所以两种都结束了才停。倒地和被擒不播。
 */
final class HumanoidStaggerAnimation {
    private static final String CLIP = "status_stagger";
    private static final Set<StaggerKind> SHOWN = EnumSet.of(StaggerKind.HIT, StaggerKind.CHARM);

    private HumanoidStaggerAnimation() {
        // 不保存僵直状态，剩余时长只问功法服务。
    }

    static void initialize() {
        TechniqueFabric.SERVICE_STARTED.register(service ->
                service.addStaggerListener(event -> apply(service, event)));
    }

    private static void apply(TechniqueService service, TechniqueService.StaggerChanged event) {
        if (!SHOWN.contains(event.kind())) {
            return;
        }
        LivingEntity entity = HumanoidFabric.findEntity(event.entityUuid());
        if (!(entity instanceof Mob) || !HumanoidFabric.isClaimed(entity)) {
            // 玩家由 Human 播；别的生物没有人形骨架。
            return;
        }
        if (event.remainingMillis() > 0) {
            // 僵直中又挨一下会重新收到开始事件：重播就是再踉跄一次。
            MobAnimationServer.play(entity, CLIP);
            return;
        }
        for (StaggerKind kind : SHOWN) {
            if (service.staggerRemainingMillis(event.entityUuid(), kind) > 0) {
                return;
            }
        }
        MobAnimationServer.stop(entity, CLIP);
    }
}
