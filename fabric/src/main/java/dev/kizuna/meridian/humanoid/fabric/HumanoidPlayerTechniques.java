package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.human.protocol.pb.ControlMessages.ControlRequest;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BindingOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TargetDistanceBand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Category;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueBinder;
import dev.kizuna.meridian.technique.server.TechniqueHeldItems;
import dev.kizuna.meridian.technique.server.TechniqueSelector;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/** 玩家身体的选式适配：重用功法库绑定、持物、费用、冷却和 AI 权重。 */
final class HumanoidPlayerTechniques {
    private static final double TICKS_PER_SECOND = 20;

    private HumanoidPlayerTechniques() {
        // 无独立已学表或功法费率。
    }

    static Optional<TechniqueReference> choose(ControlRequest input, BodySnapshot body,
                                                ServerPlayer player, LivingEntity target) {
        // 所有候选均来自当前玩家自己的身体，绝不借用施药者的功法。
        var registry = TechniqueFabric.service().registry();
        var progress = TechniqueState.read(body);
        if (progress.hasActiveCast()) {
            return Optional.empty();
        }
        Map<TechniqueReference, BindingOutcome> bindings = bindings(input, body);
        var availability = new TechniqueSelector.Availability(bindings, bindings.keySet(), progress.getCooldownsMap());
        var situation = new TechniqueSelector.Situation(body.settledAt().elapsedMillis(),
                Math.min(1, body.partHpRatio()), HumanoidFighters.qiRatio(body), player.distanceTo(target),
                TechniqueSelector.TargetState.ENGAGED, HumanoidFighters.legForceNewtons(body));
        var combat = new TechniqueSelector.CombatContext(Set.of(), progress, Optional.of(target(player, target)));
        var context = new TechniqueSelector.Context(registry, situation, availability, combat);
        long seed = player.getUUID().getLeastSignificantBits() ^ input.getSequence();
        return TechniqueSelector.choose(context, input.getKnownList(), new Random(seed));
    }

    private static Map<TechniqueReference, BindingOutcome> bindings(ControlRequest input, BodySnapshot body) {
        // 修炼类直接排除；有持物规则时只认当前双手，不自动换装备或消耗物品；绑得上、付得起也做得了功的才进候选。
        var registry = TechniqueFabric.service().registry();
        var heldItems = input.getHeldItems();
        var result = new HashMap<TechniqueReference, BindingOutcome>();
        for (var known : input.getKnownList()) {
            var reference = known.getTechnique();
            var found = registry.find(reference);
            if (found.isEmpty()) {
                continue;
            }
            var definition = found.get();
            if (definition.getCategory() == Category.CULTIVATION) {
                continue;
            }
            var rules = registry.bookOf(reference).filter(book -> book.hasHeldItemRules())
                    .map(book -> book.getHeldItemRules());
            var hand = rules.flatMap(held -> TechniqueHeldItems.find(held, heldItems, definition));
            if (rules.isPresent() && hand.isEmpty()) {
                continue;
            }
            var binding = TechniqueBinder.bind(body, definition, hand.orElse(Hand.HAND_UNSPECIFIED),
                    heldItems.getMainHandSide());
            if (binding.hasBinding() && HumanoidRepertoires.payable(body, definition, binding.getBinding())) {
                result.put(reference, binding);
            }
        }
        return result;
    }

    private static TechniqueSelector.TargetContext target(ServerPlayer player, LivingEntity target) {
        // 远程候选使用真实距离、视线和弹药；背包换武器不在受控模式范围。
        double distance = player.distanceTo(target);
        TargetDistanceBand band = TargetDistanceBand.FAR;
        if (distance < HumanoidWayfarerFacts.NEAR_DISTANCE) {
            band = TargetDistanceBand.NEAR;
        } else if (distance <= HumanoidWayfarerFacts.MEDIUM_DISTANCE) {
            band = TargetDistanceBand.MEDIUM;
        }
        var visibility = TechniqueSelector.Visibility.HIDDEN;
        if (player.hasLineOfSight(target)) {
            visibility = TechniqueSelector.Visibility.VISIBLE;
        }
        var ammunition = TechniqueSelector.Ammunition.UNAVAILABLE;
        if (!player.getProjectile(player.getMainHandItem()).isEmpty()) {
            ammunition = TechniqueSelector.Ammunition.AVAILABLE;
        }
        var approach = TechniqueSelector.Approach.NOT_APPROACHING;
        var offset = target.position().subtract(player.position());
        var targetMovement = target.getDeltaMovement();
        if (offset.dot(targetMovement.subtract(player.getDeltaMovement())) < 0) {
            approach = TechniqueSelector.Approach.APPROACHING;
        }
        return new TechniqueSelector.TargetContext(band, visibility,
                targetMovement.horizontalDistance() * TICKS_PER_SECOND, approach, ammunition, Set.of());
    }
}
