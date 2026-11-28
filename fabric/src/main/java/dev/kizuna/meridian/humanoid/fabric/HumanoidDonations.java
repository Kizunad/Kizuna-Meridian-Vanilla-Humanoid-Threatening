package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.human.server.InheritanceDonor;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.LumenExport;
import dev.kizuna.meridian.server.BodyExports;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.entity.LivingEntity;

/** 按来客实体身份重绑的出资端，场次和受功规则由调用方持有。 */
public record HumanoidDonations(UUID elder) implements InheritanceDonor {
    @Override
    public Optional<BodySnapshot> inspect() {
        // 已退场或区块未加载就不给新报价；真实来客按正常出生与时间结算入口取得身体。
        return live().map(HumanoidBodyState::currentBody).filter(body -> !body.vitality().dead());
    }

    @Override
    public Optional<LumenExport> lookup(UUID operation) {
        // 永久退场的凭据仍可交付；暂时卸载的老人随实体重载后继续。
        if (!ready()) {
            return Optional.empty();
        }
        var archived = HumanoidFabric.worldState().departedDonation(elder, operation);
        if (archived.isPresent()) {
            return archived;
        }
        return live().flatMap(state -> BodyExports.find(state.currentBody(), operation));
    }

    @Override
    public CompletableFuture<LumenExport> record(LumenExport allocation) {
        // 玩家只能使用已落盘的实扣凭据；实体数据交给原版区块写队列。
        var operation = UUID.fromString(allocation.getOperationId());
        if (!ready()) {
            throw new IllegalStateException("出资宿主尚未就绪");
        }
        var world = HumanoidFabric.worldState();
        if (world.departedDonation(elder, operation).isPresent()) {
            return world.settleDeparted(elder, allocation);
        }
        var body = live().orElseThrow();
        return body.recordExport(allocation, HumanoidFabric.findEntity(elder));
    }

    private Optional<HumanoidBodyState> live() {
        // 禁止给同 UUID 的其他种类或坏档实体补造身体，也不在异步线程加载区块。
        if (!ready()) {
            return Optional.empty();
        }
        LivingEntity entity = HumanoidFabric.findEntity(elder);
        if (!(entity instanceof HumanoidEntityAccess access) || !entity.entityTags().contains(HumanoidVisitors.VISITOR)
                || access.meridian$humanoidLoading() || access.meridian$humanoidLoadFailed()) {
            return Optional.empty();
        }
        try {
            var species = HumanoidSpeciesHooks.claimed(entity);
            if (species.isEmpty()) {
                return Optional.empty();
            }
            var now = HumanoidFabric.now();
            var state = HumanoidHealthHost.bodyState(entity, species.orElseThrow(), now);
            HumanoidFabric.enqueueRelease(entity, state.settle(now));
            return Optional.of(state);
        } catch (HumanoidEnvironmentClient.Unavailable unavailable) {
            return Optional.empty();
        }
    }

    private static boolean ready() {
        // 只在当前服务器主线程且宿主已装配时读写权威库存。
        return HumanoidFabric.isReady() && HumanoidFabric.server().isSameThread() && !HumanoidFabric.isStopping();
    }
}
