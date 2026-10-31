package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.humanoid.server.Fighter.Means;
import dev.kizuna.meridian.humanoid.server.Repertoire;
import dev.kizuna.meridian.humanoid.server.StrikeGeometry;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeaps;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import dev.kizuna.meridian.technique.fabric.world.TechniqueWorldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.AiRole;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BindingOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItemRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.KnownTechnique;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ModuleKind;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueAvailability;
import dev.kizuna.meridian.technique.server.TechniqueBinder;
import dev.kizuna.meridian.technique.server.TechniqueForecasts;
import dev.kizuna.meridian.technique.server.TechniqueHeldItems;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;
import dev.kizuna.meridian.technique.server.TechniqueSelector;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** 本次决策的候选式与时机：绑定、费用、冷却与持物都通过的打击式和辅助式，以及轻身、下一击与走出范围的毫秒。 */
final class HumanoidRepertoires {
    private static final double WALK_BLOCKS_PER_SECOND_PER_SPEED = 43.17;
    private static final long MILLIS_PER_TICK = 50;

    /** 读出的候选：战术用的式表、自己的功法手段、选式上下文与可选打击式。 */
    record Readout(Repertoire repertoire, Set<Means> means, TechniqueSelector.Context context,
                   List<KnownTechnique> strikes, List<KnownTechnique> known) { }

    /** 一次决策读的实体、身体、目录与功法进度。 */
    private record Source(Mob mob, BodySnapshot body, TechniqueRegistry registry, TechniqueProgress progress) { }

    /** 绑定、费用与持物都通过的一式；书没有持物规则时选定手为空。 */
    private record Ready(KnownTechnique known, TechniqueDefinition definition, BindingOutcome binding,
                         Optional<Hand> hand, Optional<HeldItemRules> rules) {
        AiRole role() {
            // 未设角色的是打击式。
            return definition.getAiHints().getSupport().getRole();
        }
    }

    private HumanoidRepertoires() {
        // 每次决策重新读身体，不缓存绑定。
    }

    static Readout read(Mob mob, BodySnapshot body, Optional<HumanoidOpponent> opponent,
                        Predicate<String> blocked) {
        // 被失败屏蔽的式不参与；冷却未过的打击式仍算「会打」，只是本次不进候选。
        var source = new Source(mob, body, TechniqueFabric.service().registry(), TechniqueState.read(body));
        List<Ready> ready = ready(source, TechniqueWorldItems.inspect(mob), blocked);
        var context = context(source, opponent, ready);
        var castable = ready.stream()
                .filter(entry -> TechniqueSelector.castable(context, entry.known(), entry.definition()))
                .toList();
        var strikes = castable.stream()
                .filter(entry -> entry.role() == AiRole.AI_ROLE_UNSPECIFIED)
                .filter(entry -> TechniqueForecasts.firstStrike(entry.definition()).isPresent())
                .toList();
        var repertoire = new Repertoire(strikes(source, strikes, opponent), supports(body, castable),
                timing(source, opponent));
        return new Readout(repertoire, means(source, ready), context, strikes.stream().map(Ready::known).toList(),
                source.progress().getKnownList());
    }

    private static Set<Means> means(Source source, List<Ready> ready) {
        // 会一个绑定、费用与持物都通过的打击式即算可施放，冷却中也算；有当前施放另记，不限速的式也算。
        var means = EnumSet.noneOf(Means.class);
        if (ready.stream().anyMatch(entry -> entry.role() == AiRole.AI_ROLE_UNSPECIFIED
                && TechniqueForecasts.firstStrike(entry.definition()).isPresent())) {
            means.add(Means.CASTABLE_STRIKE);
        }
        if (source.progress().hasActiveCast()) {
            means.add(Means.CASTING);
        }
        return means;
    }

    private static TechniqueSelector.Context context(Source source, Optional<HumanoidOpponent> opponent,
                                                     List<Ready> ready) {
        // 情境、可用性与战斗事实；打击式抽样与辅助式筛选共用同一份。
        BodySnapshot body = source.body();
        Mob mob = source.mob();
        double distance = opponent.map(target -> HumanoidMotion.distance(mob, target.entity())).orElse(0.0);
        var situation = new TechniqueSelector.Situation(body.settledAt().elapsedMillis(),
                Math.min(1, body.partHpRatio()), HumanoidFighters.qiRatio(body), distance,
                TechniqueSelector.TargetState.ENGAGED);
        Map<TechniqueReference, BindingOutcome> bindings = ready.stream()
                .collect(Collectors.toMap(entry -> entry.known().getTechnique(), Ready::binding));
        var availability = new TechniqueSelector.Availability(bindings, bindings.keySet(),
                source.progress().getCooldownsMap());
        Set<TechniqueSelector.CombatFact> facts = opponent.map(target -> facts(mob, target.entity()))
                .orElse(Set.of());
        var combat = new TechniqueSelector.CombatContext(facts, source.progress());
        return new TechniqueSelector.Context(source.registry(), situation, availability, combat);
    }

    private static Set<TechniqueSelector.CombatFact> facts(Mob mob, LivingEntity target) {
        // 举盾、被围、受击与被追直接读当前世界事实。
        var facts = EnumSet.noneOf(TechniqueSelector.CombatFact.class);
        if (target.isBlocking()) {
            facts.add(TechniqueSelector.CombatFact.TARGET_SHIELDING);
        }
        if (HumanoidThreats.surrounded(mob)) {
            facts.add(TechniqueSelector.CombatFact.SURROUNDED);
        }
        if (mob.hurtTime > 0) {
            facts.add(TechniqueSelector.CombatFact.UNDER_ATTACK);
        }
        var towardMob = mob.position().subtract(target.position());
        if (target.getDeltaMovement().dot(towardMob) > 0 && mob.getDeltaMovement().dot(towardMob) > 0) {
            facts.add(TechniqueSelector.CombatFact.PURSUED);
        }
        return facts;
    }

    private static List<Ready> ready(Source source, HeldItems items, Predicate<String> blocked) {
        // 逐式绑定并检查费用与持物；目录里找不到的旧版本跳过。
        BodySnapshot body = source.body();
        TechniqueRegistry registry = source.registry();
        var ready = new ArrayList<Ready>();
        for (KnownTechnique known : source.progress().getKnownList()) {
            TechniqueReference reference = known.getTechnique();
            var definition = registry.find(reference);
            if (definition.isEmpty() || blocked.test(reference.getId())) {
                continue;
            }
            BindingOutcome binding = TechniqueBinder.bind(body, definition.get());
            if (!binding.hasBinding()
                    || !TechniqueAvailability.affordable(body, definition.get(), binding.getBinding())) {
                continue;
            }
            var rules = registry.bookOf(reference).filter(book -> book.hasHeldItemRules())
                    .map(book -> book.getHeldItemRules());
            var hand = rules.flatMap(held -> TechniqueHeldItems.find(held, items));
            if (rules.isPresent() && hand.isEmpty()) {
                continue;
            }
            ready.add(new Ready(known, definition.get(), binding, hand, rules));
        }
        return ready;
    }

    private static List<Repertoire.Strike> strikes(Source source, List<Ready> strikes,
                                                   Optional<HumanoidOpponent> opponent) {
        // 首击毫秒与打击距离取目录；只有对方起手可打断时才估僵直。
        var interruptible = opponent.filter(target -> target.cast()
                .filter(TechniqueForecasts.CastForecast::interruptible).isPresent());
        var result = new ArrayList<Repertoire.Strike>();
        for (Ready entry : strikes) {
            var first = TechniqueForecasts.firstStrike(entry.definition()).orElseThrow();
            long stagger = interruptible.map(target -> staggerMillis(source, entry, first, target)).orElse(0L);
            result.add(new Repertoire.Strike(entry.definition().getId(), first.millis(),
                    first.strike().getDistance().getValue(), stagger));
        }
        return result;
    }

    private static long staggerMillis(Source source, Ready entry, TechniqueForecasts.FirstStrike first,
                                      HumanoidOpponent target) {
        // 我的首击按 Damage 规则估剂量，再按对方身体求僵直；估不出时为 0。
        if (entry.rules().isEmpty() || entry.hand().isEmpty()) {
            return 0;
        }
        var targetBody = target.observation().orElseThrow().body();
        return HumanoidStrikeEstimates.dose(source.mob(), source.body(), entry.hand().get(), entry.rules().get(),
                        first.strike(), target.entity())
                .map(dose -> HumanoidStrikeEstimates.staggerMillis(dose, targetBody))
                .orElse(0L);
    }

    private static List<Repertoire.Support> supports(BodySnapshot body, List<Ready> castable) {
        // 目录 ai_hints.support 声明了角色的式；疲劳比按所绑定的组织槽计算。
        var result = new ArrayList<Repertoire.Support>();
        for (Ready entry : castable) {
            AiRole role = entry.role();
            if (role == AiRole.AI_ROLE_UNSPECIFIED) {
                continue;
            }
            var hints = entry.definition().getAiHints();
            var support = hints.getSupport();
            var fatigue = new Repertoire.Fatigue(support.getMinimumFatigueRatio().getValue(),
                    HumanoidFighters.fatigueRatio(body, boundTissues(entry.binding())));
            var requirements = EnumSet.noneOf(Repertoire.Requirement.class);
            if (support.getRequiresMeleeWeapon()) {
                requirements.add(Repertoire.Requirement.MELEE_WEAPON);
            }
            var gate = new Repertoire.Gate(hints.getMinimumDistance().getValue(),
                    hints.getMaximumDistance().getValue(), hints.getMinimumQiRatio().getValue(), fatigue,
                    requirements);
            result.add(new Repertoire.Support(entry.definition().getId(), role(role), support.getOrder(), gate,
                    support.getRefreshBeforeExpiryMillis()));
        }
        return result;
    }

    private static Set<Anatomy.TissueId> boundTissues(BindingOutcome binding) {
        // 绑定结果里组织槽的模块。
        return binding.getBinding().getSlotsList().stream()
                .filter(slot -> slot.getModule() == ModuleKind.TISSUE)
                .flatMap(slot -> slot.getModuleIdsList().stream())
                .map(Anatomy.TissueId::new)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Repertoire.Role role(AiRole role) {
        // 目录角色到战术角色。
        return switch (role) {
            case AI_ROLE_PREPARE -> Repertoire.Role.PREPARE;
            case AI_ROLE_RECOVER -> Repertoire.Role.RECOVER;
            case AI_ROLE_REPOSITION -> Repertoire.Role.REPOSITION;
            default -> throw new IllegalArgumentException("不是辅助角色：" + role);
        };
    }

    private static Repertoire.Timing timing(Source source, Optional<HumanoidOpponent> opponent) {
        // 轻身剩余读状态效果；下一击读自己的施放预报；走出范围按走路与跃步取快者。
        Mob mob = source.mob();
        long now = source.body().settledAt().elapsedMillis();
        var benefit = TechniqueStatusBenefits.active(mob);
        long lightBody = benefit.map(registered -> mob.getEffect(registered.effect()))
                .map(effect -> effect.getDuration() * MILLIS_PER_TICK)
                .orElse(0L);
        double leapMinimum = benefit.map(registered -> registered.benefit().getLeap().getAiMinimumDistance())
                .orElse(0.0);
        long ownNextHit = TechniqueForecasts.forecast(source.registry(), source.progress(), now)
                .flatMap(forecast -> forecast.strikes().stream()
                        .map(TechniqueForecasts.StrikeWindow::millisUntilHit)
                        .min(Long::compare))
                .orElse(Long.MAX_VALUE);
        long exit = opponent.flatMap(target -> HumanoidStrikeEstimates.exit(mob, target))
                .map(found -> exitMillis(mob, found))
                .orElse(0L);
        return new Repertoire.Timing(lightBody, ownNextHit, exit, leapMinimum);
    }

    private static long exitMillis(Mob mob, HumanoidStrikeEstimates.Exit exit) {
        // 走路速度按移动属性换算；能起跳时比较跃步飞行时间。
        double walk = mob.getAttributeValue(Attributes.MOVEMENT_SPEED) * WALK_BLOCKS_PER_SECOND_PER_SPEED;
        OptionalLong leap = TechniqueLeaps.preview(mob, exit.point())
                .map(preview -> OptionalLong.of(preview.flightMillis()))
                .orElse(OptionalLong.empty());
        return StrikeGeometry.exitMillis(exit.distance(), walk, leap);
    }
}
