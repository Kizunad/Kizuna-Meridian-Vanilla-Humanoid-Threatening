package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.Decision;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.util.Optional;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.phys.Vec3;

import static dev.kizuna.meridian.humanoid.fabric.HumanoidGameTestTicks.DEFAULT_TIMEOUT;
import static dev.kizuna.meridian.humanoid.fabric.HumanoidWayfarerTestSupport.*;

/** 行矢事实与真实大脑的回马、双臂虚脱边界。 */
public final class HumanoidWayfarerDecisionGameTest {
    private static final double NEAR_DISTANCE = 6;
    private static final double MEDIUM_DISTANCE = 12;
    private static final double APPROACH_SPEED = -0.1;
    private static final double WOUNDED_PART_RATIO = 0.25;
    private static final double WOUNDED_LEG_RATIO = 0.5;
    private static final double EXHAUSTED_FATIGUE_RATIO = 0.5;
    private static final double HALF_HP = 0.5;

    @GameTest(maxTicks = DEFAULT_TIMEOUT, structure = STRUCTURE)
    public void frontFanCountsEnemiesAndExcludesAlliesAndRearTargets(GameTestHelper context) {
        // 前方同伴和身后追兵都不计入横风扇面，第二个前方敌人出现后才开放横风。
        Pillager mob = pillager(context, HumanoidTier.TRUE_QI);
        var target = target(context, mob, NEAR_DISTANCE);
        Pillager ally = pillager(context, HumanoidTier.MORTAL);
        ally.setPos(mob.position().add(-1, 0, NEAR_DISTANCE));
        var hunter = context.spawn(EntityTypes.ZOMBIE, ORIGIN, ORIGIN, ORIGIN);
        hunter.setNoAi(true /* aiDisabled */);
        hunter.setNoGravity(true /* noGravity */);
        hunter.setTarget(mob);
        hunter.setPos(mob.position().add(0, 0, -NEAR_DISTANCE));
        var single = HumanoidWayfarerFacts.read(mob, target);
        context.assertTrue(single.nearbyEnemies() == 1, "同伴或身后追兵被算入扇面");
        var gale = definition(mob, "gale");
        context.assertTrue(!HumanoidWayfarerAi.allowed(mob, body(mob), gale, Optional.of(single)),
                "八格内仅一个敌人也选择横风");
        hunter.setPos(mob.position().add(1, 0, NEAR_DISTANCE));
        var multiple = HumanoidWayfarerFacts.read(mob, target);
        context.assertTrue(multiple.nearbyEnemies() == 2, "前方第二个敌人没有计入");
        context.assertTrue(HumanoidWayfarerAi.allowed(mob, body(mob), gale, Optional.of(multiple)),
                "多个近处敌人没有开放横风");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, structure = STRUCTURE)
    public void woundedApproachedPillagerChoosesParthian(GameTestHelper context) {
        // 保留双臂完好，只降低其余部位 HP，区分半血回马与双臂虚脱撤退。
        Pillager mob = pillager(context, HumanoidTier.TRUE_QI);
        TechniqueFabric.service().learn(mob.getUUID(), PREFIX + "bow_lower");
        woundOutsideArms(mob);
        var target = target(context, mob, MEDIUM_DISTANCE);
        target.setDeltaMovement(new Vec3(0, 0, APPROACH_SPEED));
        context.assertTrue(body(mob).partHpRatio() < HALF_HP, "测试身体没有降到半血以下");
        decideOnce(mob);
        var logged = HumanoidBrain.decisions(mob.getUUID()).getLast();
        context.assertTrue(logged.decision().technique().orElse("").equals(PREFIX + "parthian"),
                "逼近且不足半血时没有选择回马：" + logged);
        var active = TechniqueState.read(body(mob)).getActiveCast();
        context.assertTrue(active.getRequest().getTechnique().getId().equals(PREFIX + "parthian"), "回马没有真正起手");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, structure = STRUCTURE)
    public void exhaustedArmsRetreatWithoutStartingArchery(GameTestHelper context) {
        // 双臂疲劳使有效力量低于撤退门槛，行矢不能覆盖普通避战决定。
        floor(context);
        Pillager mob = pillager(context, HumanoidTier.TRUE_QI);
        var snapshot = BodyProtobuf.toMessage(body(mob));
        var tired = snapshot.toBuilder();
        for (int index = 0; index < snapshot.getStructure().getTissuesCount(); index++) {
            var tissue = snapshot.getStructure().getTissues(index);
            if (tissue.getTagsList().contains(HumanoidFighters.ARM_MUSCLE)) {
                tired.getTissueStatesBuilder(index)
                        .setFatigue(tissue.getFatigueCapacity() * EXHAUSTED_FATIGUE_RATIO);
            }
        }
        restoreBody(mob, tired.build());
        target(context, mob, NEAR_DISTANCE);
        decideOnce(mob);
        var logged = HumanoidBrain.decisions(mob.getUUID()).getLast();
        Decision decision = logged.decision();
        context.assertTrue(decision.action() == Decision.Action.RETREAT
                || decision.action() == Decision.Action.PREPARE
                && decision.technique().orElse("").equals("meridian_basic_techniques:light_step"),
                "双臂虚脱没有走开或准备撤退机动式：" + logged);
        context.assertTrue(logged.decision().action() != Decision.Action.STRIKE
                && logged.decision().action() != Decision.Action.INTERRUPT, "双臂虚脱仍然出手");
        context.assertTrue(!HumanoidTechniqueHost.castingWayfarer(mob), "双臂虚脱仍建立行矢施放");
        context.succeed();
    }

    private static void woundOutsideArms(Pillager mob) {
        // 按臂肌所属部位保留手臂，其余部位写入非致命伤势，再经 Framework 恢复校验。
        var snapshot = BodyProtobuf.toMessage(body(mob));
        var armParts = snapshot.getStructure().getTissuesList().stream()
                .filter(tissue -> tissue.getTagsList().contains(HumanoidFighters.ARM_MUSCLE))
                .map(tissue -> tissue.getPartId())
                .collect(Collectors.toSet());
        var legParts = snapshot.getStructure().getTissuesList().stream()
                .filter(tissue -> tissue.getTagsList().contains(HumanoidFighters.LEG_MUSCLE))
                .map(tissue -> tissue.getPartId())
                .collect(Collectors.toSet());
        var wounded = snapshot.toBuilder();
        for (var state : wounded.getPartStatesBuilderList()) {
            if (!armParts.contains(state.getId())) {
                double remaining = legParts.contains(state.getId()) ? WOUNDED_LEG_RATIO : WOUNDED_PART_RATIO;
                state.setHp(state.getHp() * remaining);
            }
        }
        restoreBody(mob, wounded.build());
    }

    private static void decideOnce(Pillager mob) {
        // 推进足够的错相 tick，走正式大脑入口并在离开测试前关闭原版 AI。
        mob.setNoAi(false /* aiDisabled */);
        var goals = new GoalSelector();
        var targets = new GoalSelector();
        int interval = HumanoidBrain.config().schedule().decisionIntervalTicks();
        for (int tick = 0; tick < interval; tick++) {
            mob.tickCount++;
            HumanoidBrain.tick(mob, goals, targets);
        }
        mob.setNoAi(true /* aiDisabled */);
    }
}
