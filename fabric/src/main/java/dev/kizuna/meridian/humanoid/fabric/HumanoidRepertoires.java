package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.humanoid.server.Fighter.Means;
import dev.kizuna.meridian.humanoid.server.Repertoire;
import dev.kizuna.meridian.humanoid.server.StrikeGeometry;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeaps;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.AiRole;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BindingOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItemRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.KnownTechnique;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ModuleKind;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueBinding;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueAvailability;
import dev.kizuna.meridian.technique.server.TechniqueBinder;
import dev.kizuna.meridian.technique.server.TechniqueForecasts;
import dev.kizuna.meridian.technique.server.TechniqueHeldItems;
import dev.kizuna.meridian.technique.server.TechniqueInfusions;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;
import dev.kizuna.meridian.technique.server.TechniqueSelector;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** 本次决策的候选式与时机：绑定、费用、做功、冷却与持物都通过的打击式和辅助式，以及轻身、下一击与走出范围的毫秒。 */
final class HumanoidRepertoires {
    private static final double WALK_BLOCKS_PER_SECOND_PER_SPEED = 43.17;
    private static final double PEDDLER_MINIMUM_LEG_FORCE = 3000;
    private static final double PEDDLER_HALF_HP = 0.5;
    private static final double PEDDLER_BEHIND_DISTANCE = 3;
    // 身后对手进到这距离内，转身期间就会挨打（原版近战大约够到 1.4 到 2 格）。
    private static final double PEDDLER_TURN_DISTANCE = 2;
    private static final double PEDDLER_RANGE_MARGIN = 0.5;
    private static final int SINGLE_OPPONENT_COUNT = 1;
    private static final long MILLIS_PER_TICK = 50;
    private static final String PEDDLER_PREFIX = "meridian_peddler:";
    private static final String SNAP_KICK = PEDDLER_PREFIX + "snap_kick";
    private static final String PUSH_KICK = PEDDLER_PREFIX + "push_kick";
    private static final String GROUND_SWEEP = PEDDLER_PREFIX + "ground_sweep";
    private static final String ROUNDHOUSE = PEDDLER_PREFIX + "roundhouse";
    private static final String TRIPLE_KICK = PEDDLER_PREFIX + "triple_kick";
    private static final String MULE_KICK = PEDDLER_PREFIX + "mule_kick";
    private static final String FLYING_KICK = PEDDLER_PREFIX + "flying_kick";
    private static final String AXE_KICK = PEDDLER_PREFIX + "axe_kick";
    private static final String WHIRLWIND = PEDDLER_PREFIX + "whirlwind";
    private static final Set<String> PEDDLER_DEFENSES = Set.of(SNAP_KICK, PUSH_KICK, MULE_KICK, GROUND_SWEEP);

    /** 读出的候选：战术用的式表、自己的功法手段、选式上下文、可选打击式，以及连发射得到的最远距离。 */
    record Readout(Repertoire repertoire, Set<Means> means, TechniqueSelector.Context context,
                   List<KnownTechnique> strikes, List<KnownTechnique> known,
                   Map<String, HumanoidWayfarerWeapons.Exchange> exchanges, OptionalDouble volleyReach) { }

    /** 一次决策读的实体、身体、目录与功法进度。 */
    private record Source(Mob mob, BodySnapshot body, TechniqueRegistry registry, TechniqueProgress progress,
                          Optional<HumanoidWayfarerFacts.Target> target) { }

    /** 绑定、费用、做功与持物都通过的一式；书没有持物规则时选定手为空。 */
    private record Ready(KnownTechnique known, TechniqueDefinition definition, BindingOutcome binding,
                         Optional<Hand> hand, Optional<HeldItemRules> rules,
                         Optional<HumanoidWayfarerWeapons.Exchange> exchange) {
        AiRole role() {
            // 未设角色的是打击式。
            return definition.getAiHints().getSupport().getRole();
        }
    }

    /** 一式最早打到人的那一下：时刻、够得着的距离；正好是兵器打击时带上打击形状。 */
    record FirstStrike(long millis, double distance, Optional<TechniqueForecasts.FirstStrike> weapon) { }

    private HumanoidRepertoires() {
        // 每次决策重新读身体，不缓存绑定。
    }

    static boolean peddlerShouldRetreat(Readout readout) {
        // 学了千里腿却没有当前防守或主动候选时，保留功法的默认逃跑性格。
        boolean knowsPeddler = readout.known().stream()
                .anyMatch(known -> known.getTechnique().getId().startsWith(PEDDLER_PREFIX));
        return knowsPeddler && readout.repertoire().strikes().isEmpty();
    }

    static boolean peddlerCanDefend(Readout readout) {
        // 候选已通过腿力、费用、冷却与前后方处境筛选；防守腿法不能被通用撤退姿态屏蔽。
        return readout.repertoire().strikes().stream()
                .anyMatch(strike -> PEDDLER_DEFENSES.contains(strike.technique()));
    }

    static Readout read(Mob mob, BodySnapshot body, Optional<HumanoidOpponent> opponent,
                        Predicate<String> blocked) {
        // 被失败屏蔽的式不参与；冷却未过的打击式仍算「会打」，只是本次不进候选。
        var target = opponent.map(value -> HumanoidWayfarerFacts.read(mob, value.entity()));
        var source = new Source(mob, body, TechniqueFabric.service().registry(), TechniqueState.read(body), target);
        List<Ready> ready = ready(source, HumanoidWayfarerWeapons.inspect(mob), blocked);
        var context = context(source, opponent, ready);
        var castable = ready.stream()
                .filter(entry -> TechniqueSelector.castable(context, entry.known(), entry.definition()))
                .toList();
        var readyStrikes = castable.stream()
                .filter(entry -> entry.role() == AiRole.AI_ROLE_UNSPECIFIED)
                .filter(entry -> firstStrike(entry.definition()).isPresent())
                .filter(entry -> peddlerStrikeAllowed(source, opponent, entry))
                .filter(entry -> HumanoidWayfarerAi.allowed(mob, body, entry.definition(), source.target()))
                .toList();
        boolean followUpAvailable = source.progress().hasActiveCast() && !readyStrikes.isEmpty();
        var repertoire = new Repertoire(strikes(source, readyStrikes, opponent), supports(body, castable),
                timing(source, opponent, followUpAvailable));
        return new Readout(repertoire, means(source, opponent, ready), context,
                readyStrikes.stream().map(Ready::known).toList(),
                source.progress().getKnownList(), exchanges(readyStrikes), volleyReach(ready));
    }

    static double refreshGate(BodySnapshot body) {
        // 会的准备式里目录设了补放提前量的（附伤式），取最远的起手下限；冷却、费用、正在施放都不管，没有记 0。
        TechniqueRegistry registry = TechniqueFabric.service().registry();
        return TechniqueState.read(body).getKnownList().stream()
                .flatMap(known -> registry.find(known.getTechnique()).stream())
                .map(TechniqueDefinition::getAiHints)
                .filter(hints -> hints.getSupport().getRole() == AiRole.AI_ROLE_PREPARE)
                .filter(hints -> hints.getSupport().hasRefreshBeforeExpiryMillis())
                .mapToDouble(hints -> hints.getMinimumDistance().getValue())
                .max()
                .orElse(0);
    }

    private static OptionalDouble volleyReach(List<Ready> ready) {
        // 绑定、费用、做功与持物都通过的连发里最远的射程，冷却中也算；射程取目录 ai_hints 最远距离，
        // 目录按满力弹道定。连发是看得见的目标在八格外随时能放的式，别的远射只在特定处境放，不定站位。
        return ready.stream()
                .filter(entry -> HumanoidWayfarerAi.VOLLEYS.contains(entry.definition().getId()))
                .mapToDouble(entry -> entry.definition().getAiHints().getMaximumDistance().getValue())
                .max();
    }

    private static Map<String, HumanoidWayfarerWeapons.Exchange> exchanges(List<Ready> ready) {
        // 只保留最终候选的库存预览，选式后才会实际交换。
        return ready.stream().filter(entry -> entry.exchange().isPresent())
                .collect(Collectors.toUnmodifiableMap(entry -> entry.definition().getId(),
                        entry -> entry.exchange().orElseThrow()));
    }

    private static Set<Means> means(Source source, Optional<HumanoidOpponent> opponent, List<Ready> ready) {
        // 会一个绑定、费用、做功与持物都通过的打击式即算可施放，冷却中也算；防守腿法学会即另记一项，
        // 可施放见 defenseReady，学会了但腿力没过出腿门槛另记腿力不够；有当前施放另记，不限速的式也算。
        // 出腿门槛比的是不计伤势的腿力（见 unhurtLegForceNewtons）：腿累了才算腿力不够，腿伤了不算。
        var means = EnumSet.noneOf(Means.class);
        if (ready.stream().anyMatch(entry -> entry.role() == AiRole.AI_ROLE_UNSPECIFIED
                && firstStrike(entry.definition()).isPresent())) {
            means.add(Means.CASTABLE_STRIKE);
        }
        if (source.progress().getKnownList().stream()
                .anyMatch(known -> PEDDLER_DEFENSES.contains(known.getTechnique().getId()))) {
            means.add(Means.KNOWN_DEFENSE);
        }
        boolean weakLegs = HumanoidFighters.unhurtLegForceNewtons(source.body()) < PEDDLER_MINIMUM_LEG_FORCE;
        if (means.contains(Means.KNOWN_DEFENSE) && weakLegs) {
            means.add(Means.WEAK_LEGS);
        }
        if (!weakLegs && defenseReady(source, opponent, ready)) {
            means.add(Means.CASTABLE_DEFENSE);
        }
        if (source.progress().hasActiveCast()) {
            means.add(Means.CASTING);
        }
        return means;
    }

    private static boolean defenseReady(Source source, Optional<HumanoidOpponent> opponent, List<Ready> ready) {
        // 腿力门槛由调用方先查；这里看有没有一式眼下用得上的防守腿法绑定、费用、做功与持物都通过、冷却已过；
        // 距离不看，远了迎上去。冷却中或用不上都不算：商人这时照腿力不够的办法躲开拉距离，
        // 冷却好了、转得过身再迎上去出腿。
        long now = source.body().settledAt().elapsedMillis();
        var cooldowns = source.progress().getCooldownsMap();
        var usable = usableDefenses(source.mob(), opponent);
        return ready.stream()
                .map(entry -> entry.known().getTechnique().getId())
                .filter(usable::contains)
                .anyMatch(id -> now >= cooldowns.getOrDefault(id, 0L));
    }

    private static Set<String> usableDefenses(Mob mob, Optional<HumanoidOpponent> opponent) {
        // 被围（夹住）时正面两式出不了，只剩扫堂和尥蹶。没被围时扫堂不算，否则会扫堂的商人永远算出得了腿。
        // 对手贴在身后、转身期间就会挨打时只剩尥蹶；离得远些就先转身面对，再出正面两式。
        if (HumanoidThreats.surrounded(mob)) {
            return Set.of(GROUND_SWEEP, MULE_KICK);
        }
        boolean closeBehind = opponent.map(HumanoidOpponent::entity)
                .filter(target -> targetBehind(mob, target))
                .filter(target -> HumanoidMotion.distance(mob, target) <= PEDDLER_TURN_DISTANCE)
                .isPresent();
        if (closeBehind) {
            return Set.of(MULE_KICK);
        }
        return Set.of(SNAP_KICK, PUSH_KICK, MULE_KICK);
    }

    private static TechniqueSelector.Context context(Source source, Optional<HumanoidOpponent> opponent,
                                                     List<Ready> ready) {
        // 情境、可用性与战斗事实；打击式抽样与辅助式筛选共用同一份。
        BodySnapshot body = source.body();
        Mob mob = source.mob();
        double distance = opponent.map(target -> HumanoidMotion.distance(mob, target.entity())).orElse(0.0);
        var situation = new TechniqueSelector.Situation(body.settledAt().elapsedMillis(),
                Math.min(1, body.partHpRatio()), HumanoidFighters.qiRatio(body), distance,
                TechniqueSelector.TargetState.ENGAGED, HumanoidFighters.unhurtLegForceNewtons(body));
        Map<TechniqueReference, BindingOutcome> bindings = ready.stream()
                .collect(Collectors.toMap(entry -> entry.known().getTechnique(), Ready::binding));
        var availability = new TechniqueSelector.Availability(bindings, bindings.keySet(),
                source.progress().getCooldownsMap());
        Set<TechniqueSelector.CombatFact> facts = opponent.map(target -> facts(mob, target.entity()))
                .orElse(Set.of());
        var ranged = source.target().map(target -> HumanoidWayfarerFacts.context(mob, target));
        var combat = new TechniqueSelector.CombatContext(facts, source.progress(), ranged);
        return new TechniqueSelector.Context(source.registry(), situation, availability, combat);
    }

    private static Set<TechniqueSelector.CombatFact> facts(Mob mob, LivingEntity target) {
        // 举盾、被围、受击、被追、身后、腾空与逃跑直接读当前世界事实。
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
        if (pursued(mob, target)) {
            facts.add(TechniqueSelector.CombatFact.PURSUED);
        }
        if (targetBehind(mob, target)) {
            facts.add(TechniqueSelector.CombatFact.TARGET_BEHIND);
        }
        if (!target.onGround()) {
            facts.add(TechniqueSelector.CombatFact.TARGET_AIRBORNE);
        }
        var towardTarget = target.position().subtract(mob.position());
        if (mob.getDeltaMovement().dot(towardTarget) < 0) {
            facts.add(TechniqueSelector.CombatFact.SELF_FLEEING);
        }
        return facts;
    }

    private static boolean peddlerStrikeAllowed(Source source, Optional<HumanoidOpponent> opponent, Ready entry) {
        // 千里腿保留「能跑就跑」：目录负责权重，宿主只补目录无法表达的
        // 档位、血量和人数门槛。
        String id = entry.known().getTechnique().getId();
        if (!id.startsWith(PEDDLER_PREFIX)) {
            return true;
        }
        boolean weakLegs = HumanoidFighters.unhurtLegForceNewtons(source.body()) < PEDDLER_MINIMUM_LEG_FORCE;
        if (opponent.isEmpty() || weakLegs) {
            return false;
        }
        LivingEntity target = opponent.get().entity();
        double distance = HumanoidMotion.distance(source.mob(), target);
        boolean behind = targetBehind(source.mob(), target);
        boolean surrounded = HumanoidThreats.surrounded(source.mob());
        double maximumDistance = entry.definition().getAiHints().getMaximumDistance().getValue();
        double frontDistance = Math.max(0, maximumDistance - PEDDLER_RANGE_MARGIN);
        boolean frontBlocked = !behind && !surrounded && distance <= frontDistance;
        if (id.equals(MULE_KICK)) {
            return distance <= PEDDLER_BEHIND_DISTANCE && (behind || pursued(source.mob(), target));
        }
        if (id.equals(GROUND_SWEEP)) {
            return surrounded;
        }
        if (id.equals(SNAP_KICK) || id.equals(PUSH_KICK)) {
            return frontBlocked;
        }
        boolean activeReady = activePeddler(source, target);
        if (id.equals(AXE_KICK)) {
            return activeReady && (target.isBlocking() || !target.onGround());
        }
        if (id.equals(ROUNDHOUSE) || id.equals(TRIPLE_KICK) || id.equals(FLYING_KICK)
                || id.equals(WHIRLWIND)) {
            boolean defensive = behind || surrounded || frontBlocked || target.isBlocking() || !target.onGround();
            return activeReady && !defensive;
        }
        return false;
    }

    private static boolean activePeddler(Source source, LivingEntity target) {
        // 主动腿法只给真炁、半血以上且只有一个敌人的生物。
        BodySnapshot body = source.body();
        return body.realm() == Realm.TRUE_QI
                && Math.min(1, body.partHpRatio()) > PEDDLER_HALF_HP
                && HumanoidThreats.enemyCount(source.mob(), target) == SINGLE_OPPONENT_COUNT;
    }

    private static boolean targetBehind(Mob mob, LivingEntity target) {
        // 只看水平朝向；目标在身后半圈时交给尥蹶情境。
        var towardTarget = target.position().subtract(mob.position()).multiply(1, 0, 1);
        var facing = mob.getLookAngle().multiply(1, 0, 1);
        return towardTarget.lengthSqr() > 0 && facing.lengthSqr() > 0 && facing.dot(towardTarget) < 0;
    }

    private static boolean pursued(Mob mob, LivingEntity target) {
        // 双方水平速度都朝向我时视为被追。
        var towardMob = mob.position().subtract(target.position());
        return target.getDeltaMovement().dot(towardMob) > 0 && mob.getDeltaMovement().dot(towardMob) > 0;
    }

    private static List<Ready> ready(Source source, HeldItems items, Predicate<String> blocked) {
        // 逐式绑定并检查费用、做功与持物；目录里找不到的旧版本跳过。
        BodySnapshot body = source.body();
        TechniqueRegistry registry = source.registry();
        var ready = new ArrayList<Ready>();
        for (KnownTechnique known : source.progress().getKnownList()) {
            TechniqueReference reference = known.getTechnique();
            var found = registry.find(reference);
            if (found.isEmpty() || blocked.test(reference.getId())) {
                continue;
            }
            TechniqueDefinition definition = found.orElseThrow();
            var rules = registry.bookOf(reference).filter(book -> book.hasHeldItemRules())
                    .map(book -> book.getHeldItemRules());
            var exchange = HumanoidWayfarerWeapons.forTechnique(source.mob(), definition);
            HeldItems preview = exchange.map(value -> value.preview(items)).orElse(items);
            var hand = rules.flatMap(held -> TechniqueHeldItems.find(held, preview, definition));
            if (rules.isPresent() && hand.isEmpty()) {
                continue;
            }
            BindingOutcome binding = TechniqueBinder.bind(body, definition,
                    hand.orElse(Hand.HAND_UNSPECIFIED), preview.getMainHandSide());
            if (!binding.hasBinding() || !payable(body, definition, binding.getBinding())) {
                continue;
            }
            ready.add(new Ready(known, definition, binding, hand, rules, exchange));
        }
        return ready;
    }

    static boolean payable(BodySnapshot body, TechniqueDefinition definition, TechniqueBinding binding) {
        // 读候选共用的身体预检：真元付得起，组织和器官也做得了这一式的功；
        // 腿肌累了出不了功的式不进候选，免得照选照失败、攒够连续失败次数被暂停。
        return TechniqueAvailability.affordable(body, definition, binding)
                && TechniqueAvailability.workable(body, definition, binding);
    }

    private static List<Repertoire.Strike> strikes(Source source, List<Ready> strikes,
                                                   Optional<HumanoidOpponent> opponent) {
        // 首击毫秒与打击距离取目录；只有对方起手可打断时才估僵直。
        var interruptible = opponent.filter(target -> target.cast()
                .filter(TechniqueForecasts.CastForecast::interruptible).isPresent());
        var result = new ArrayList<Repertoire.Strike>();
        for (Ready entry : strikes) {
            var first = firstStrike(entry.definition()).orElseThrow();
            long stagger = interruptible.flatMap(target -> first.weapon()
                    .map(weapon -> staggerMillis(source, entry, weapon, target))).orElse(0L);
            result.add(new Repertoire.Strike(entry.definition().getId(), first.millis(),
                    first.distance(), stagger));
        }
        return result;
    }

    static Optional<FirstStrike> firstStrike(TechniqueDefinition definition) {
        // 会不会打人、多远够得着由功法库统一算（兵器、腿、拳掌、擒、投射物）；
        // 最早那一下正好是兵器打击时带上打击形状，估僵直用。
        return TechniqueForecasts.firstHit(definition)
                .map(hit -> new FirstStrike(hit.millis(), hit.reach(), TechniqueForecasts.firstStrike(definition)
                        .filter(weapon -> weapon.millis() == hit.millis())));
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
                    support.getRefreshBeforeExpiryMillis(), tracked(role, entry.definition())));
        }
        return result;
    }

    private static Repertoire.Tracked tracked(AiRole role, TechniqueDefinition definition) {
        // 机动式补放看轻身剩余；设了补放提前量、含附行效果的准备式看附行剩余（C6c SPEC 5.2）；其余不看。
        if (role == AiRole.AI_ROLE_REPOSITION) {
            return Repertoire.Tracked.LIGHT_BODY;
        }
        boolean infusion = definition.getEffectsList().stream().anyMatch(TechniqueEffect::hasElementalInfusion);
        if (role == AiRole.AI_ROLE_PREPARE && infusion
                && definition.getAiHints().getSupport().hasRefreshBeforeExpiryMillis()) {
            return Repertoire.Tracked.INFUSION;
        }
        return Repertoire.Tracked.NONE;
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

    private static Repertoire.Timing timing(Source source, Optional<HumanoidOpponent> opponent,
                                            boolean followUpAvailable) {
        // 轻身剩余读状态效果；附行剩余读身体里的附行（没有附行就不解析）；下一击读自己的施放预报；
        // 走出范围按走路与跃步取快者。
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
                .orElse(Long.MAX_VALUE);
        long infusion = source.progress().hasActiveInfusion() ? TechniqueInfusions.remainingMillis(source.body()) : 0;
        return new Repertoire.Timing(lightBody, ownNextHit, exit, leapMinimum, followUpAvailable, infusion);
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
