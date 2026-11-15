package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueService.StaggerKind;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import net.minecraft.world.entity.LivingEntity;

/**
 * 对手现在的状态里，大脑自己看不全的那部分：别的附属可以把自己管着的状态登记进来。
 *
 * <p>「倒地」：有功法宿主的对手（玩家、生人）问功法库的倒地僵直；没有宿主的原版生物被摔倒，是摔它的那个附属自己记着的，
 * 要登记到这里大脑才看得见。</p>
 */
public final class HumanoidTargets {
    private static final List<Predicate<LivingEntity>> DOWNED = new CopyOnWriteArrayList<>();

    private HumanoidTargets() {
        // 纯静态入口。
    }

    /** 登记一种「这个实体现在倒在地上」的判断；模组初始化时调一次。 */
    public static void registerDowned(Predicate<LivingEntity> downed) {
        DOWNED.add(downed);
    }

    static boolean downed(LivingEntity target) {
        // 功法库记着的倒地僵直，或任何一家登记的判断说它倒着。
        return TechniqueFabric.service().staggerRemainingMillis(target.getUUID(), StaggerKind.DOWN) > 0
                || DOWNED.stream().anyMatch(downed -> downed.test(target));
    }
}
