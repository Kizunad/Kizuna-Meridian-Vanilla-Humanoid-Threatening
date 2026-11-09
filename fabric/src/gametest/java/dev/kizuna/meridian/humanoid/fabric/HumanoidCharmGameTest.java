package dev.kizuna.meridian.humanoid.fabric;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.brewing.fabric.BrewCharmSessions;
import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import dev.kizuna.meridian.brewing.fabric.BrewingRuntime;
import dev.kizuna.meridian.brewing.protocol.pb.CharmMessages.CharmCommand;
import dev.kizuna.meridian.brewing.protocol.pb.CharmMessages.CharmEndReason;
import dev.kizuna.meridian.brewing.protocol.pb.CharmMessages.CharmReply;
import dev.kizuna.meridian.brewing.protocol.pb.CharmMessages.CharmSession;
import dev.kizuna.meridian.brewing.server.BrewBodyFacts;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewThresholds;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.server.CharmController;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** 生物魅惑的真实药效、决策、动作门禁和原版自喝回归。 */
public final class HumanoidCharmGameTest {
    private static final int TIMEOUT = 200;
    private static final int ACTION_TICKS = 45;
    private static final int FOLLOW_TICKS = 30;
    private static final int LOCK_TICKS = 8;
    private static final int TEMPO_TICKS = 20;
    private static final int FLOOR_EDGE = 7;
    private static final int SESSION_TICKS = 120;
    private static final int SHORTEN_TICKS = 20;
    private static final double TARGET_HP = 1_000;
    private static final double WOUND_HP = 5;
    private static final double EPSILON = 0.001;
    private static final double HALF_SPEED = 0.5;
    private static final long STAGGER_MILLIS = 1_000;
    private static final String VOLUME = "meridian_door_breaker:upper";
    private static final String KNOCK = "meridian_door_breaker:knock";
    private static final BlockPos SUBJECT = new BlockPos(2, 1, 2);
    private static final BlockPos TARGET = new BlockPos(2, 1, 4);
    private static final BlockPos CASTER = new BlockPos(6, 1, 6);

    @GameTest(maxTicks = TIMEOUT)
    public void charmCultivatorCastsAtCasterTargetAndThenFollows(GameTestHelper context) {
        // 真炁身体使用正式学卷与药效入口，切换目标后须真正花自己的功法费用。
        Vindicator subject = subject(context, HumanoidTier.TRUE_QI);
        Witch caster = caster(context);
        Mob target = target(context);
        caster.setTarget(target);
        TechniqueFabric.service().learn(subject.getUUID(), VOLUME);
        splash(context, subject, caster, BrewQuality.SUPERIOR);
        context.assertTrue(BrewCharmSessions.controlled(subject.getUUID()), "优质魅惑没有开启控制");
        context.assertTrue(subject.getTarget() == target, "修炼者没有改打施药者目标");
        context.assertTrue(!subject.doHurtTarget(context.getLevel(), caster), "受控者伤到了施药者");
        subject.setNoAi(false /* noAi */);
        context.runAfterDelay(ACTION_TICKS, () -> {
            var body = BodyProtobuf.fromMessage(HumanoidTechniqueHost.prepare(subject).body().snapshot());
            var progress = TechniqueState.read(body);
            boolean used = progress.hasActiveCast()
                    || progress.getCooldownsMap().keySet().stream()
                    .anyMatch(id -> id.startsWith("meridian_door_breaker:"));
            context.assertTrue(used, "受控修炼者没有实际施放自己的功法：" + HumanoidBrain.decisions(subject.getUUID()));
            context.assertTrue(target.getHealth() < TARGET_HP, "受控功法没有伤到新目标");
            caster.setTarget(null);
            target.discard();
            double distance = subject.distanceTo(caster);
            context.runAfterDelay(FOLLOW_TICKS, () -> {
                context.assertTrue(subject.distanceTo(caster) < distance, "无目标时没有跟随施药者");
                cleanup(subject, caster);
                context.succeed();
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void charmMortalUsesVanillaTargetAndAttack(GameTestHelper context) {
        // 凡人保留原版近战 Goal；原版重新选敌不能夺回施药者指定的目标。
        Vindicator subject = subject(context, HumanoidTier.MORTAL);
        Witch caster = caster(context);
        Mob target = target(context);
        caster.setTarget(target);
        subject.setTarget(caster);
        splash(context, subject, caster, BrewQuality.SUPERIOR);
        subject.setTarget(caster);
        context.assertTrue(subject.getTarget() == target, "凡人目标被原版仇恨覆盖");
        subject.setNoAi(false /* noAi */);
        context.runAfterDelay(ACTION_TICKS, () -> {
            context.assertTrue(target.getHealth() < TARGET_HP, "凡人没有用原版攻击打新目标");
            context.assertTrue(!subject.doHurtTarget(context.getLevel(), caster), "凡人攻击没有盟友门禁");
            cleanup(subject, caster, target);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void charmStaggerBlocksMotionAttackUseAndTechnique(GameTestHelper context) {
        // 魅惑定身停主动动作，随后单独受击不能启用同一全锁。
        Vindicator subject = subject(context, HumanoidTier.TRUE_QI);
        Witch caster = caster(context);
        Mob target = target(context);
        subject.setTarget(target);
        splash(context, subject, caster, BrewQuality.POOR);
        context.assertTrue(HumanoidRestrictions.locked(subject), "良好脑力没有魅惑定身");
        Vec3 before = subject.position();
        subject.setNoAi(false /* noAi */);
        assertLockedActions(context, subject, target);
        context.runAfterDelay(LOCK_TICKS, () -> {
            context.assertTrue(subject.position().subtract(before).horizontalDistance() < EPSILON, "定身仍主动移动");
            assertLockedActions(context, subject, target);
            cleanup(subject, caster, target);
            context.succeed();
        });
    }

    private static void assertLockedActions(GameTestHelper context, Vindicator subject, LivingEntity target) {
        // 调实际动作入口；被拦截的状态不会消耗物品或提交新式。
        context.assertTrue(!subject.doHurtTarget(context.getLevel(), target), "定身仍能近战");
        subject.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        subject.startUsingItem(InteractionHand.OFF_HAND);
        context.assertTrue(!subject.isUsingItem(), "定身仍能用物品");
        var reference = TechniqueReference.newBuilder()
                .setId(KNOCK)
                .setVersion(1)
                .build();
        var request = CastRequest.newBuilder()
                .setBodyId(HumanoidTechniqueHost.prepare(subject).body().snapshot().getId())
                .setCastId(UUID.randomUUID().toString())
                .setTechnique(reference)
                .build();
        var outcome = TechniqueFabric.service().cast(subject.getUUID(), request);
        context.assertTrue(outcome.getFailure() == TechniqueFailure.STAGGERED, "定身仍接受功法");
    }

    @GameTest(maxTicks = TIMEOUT)
    public void charmHitStaggerKeepsVanillaUseAvailable(GameTestHelper context) {
        // HIT 只保留既有减速和技法拒绝，不拦原版攻击与举盾。
        Vindicator subject = subject(context, HumanoidTier.MORTAL);
        Mob target = target(context);
        TechniqueFabric.service().stagger(subject.getUUID(),
                new TechniqueService.Stagger(STAGGER_MILLIS, HALF_SPEED, TechniqueService.StaggerKind.HIT));
        context.assertTrue(!HumanoidRestrictions.locked(subject), "受击误触发魅惑全锁");
        context.assertTrue(subject.doHurtTarget(context.getLevel(), target), "受击期间原版近战被拦");
        subject.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        subject.startUsingItem(InteractionHand.OFF_HAND);
        context.assertTrue(subject.isUsingItem(), "受击期间不能举盾");
        cleanup(subject, target);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void charmRelatedTempoSlowsVanillaCooldownAndMovement(GameTestHelper context) {
        // 真正苦黄药封影响左右腿和主手臂，原版攻击 Goal 的冷却须相应拉长。
        Vindicator subject = subject(context, HumanoidTier.MORTAL);
        double speed = subject.getAttributeValue(Attributes.MOVEMENT_SPEED);
        var goal = new ObservedMelee(subject);
        int interval = goal.interval();
        for (String part : List.of("right_upper_arm", "left_upper_arm", "left_thigh", "right_thigh")) {
            VanillaDamageFabric.damageDirect(new VanillaDamageFabric.DirectDamage(subject,
                    subject.damageSources().generic(), part, WOUND_HP)).orElseThrow();
        }
        var potion = BrewItemStacks.create(BrewKind.BITTER_YELLOW, BrewQuality.POOR, BrewUse.DRINK);
        BrewingRuntime.consume(subject.level(), subject, potion);
        context.runAfterDelay(TEMPO_TICKS, () -> {
            HumanoidTempo.update(subject);
            context.assertTrue(goal.interval() > interval, "药封没有拉长原版攻击 Goal 冷却");
            context.assertTrue(subject.getAttributeValue(Attributes.MOVEMENT_SPEED) < speed, "药封没有减慢移动");
            subject.removeAllEffects();
            HumanoidTempo.update(subject);
            context.assertTrue(goal.interval() == interval, "药封撤销后原版攻击间隔没有恢复");
            context.assertTrue(Math.abs(subject.getAttributeValue(Attributes.MOVEMENT_SPEED) - speed) < EPSILON,
                    "药封撤销后移动倍率没有恢复");
            cleanup(subject);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void charmHostInspectAndWitchNauseaUseActualBody(GameTestHelper context) {
        // inspect 必须携带两肾与脑；反胃拦女巫绕过 startUsingItem 的专用自喝分支。
        Witch witch = caster(context);
        var body = VanillaDamageFabric.inspectBody(witch).orElseThrow();
        var kinds = body.getStructure().getOrgansList().stream().map(organ -> organ.getKind()).toList();
        context.assertTrue(kinds.containsAll(Set.of("human:left_kidney", "human:right_kidney", "human:brain")),
                "类人 inspect 缺两肾或脑");
        for (var organ : body.getStructure().getOrgansList()) {
            context.assertTrue(body.getOrganStatesList().stream()
                            .anyMatch(state -> state.getId().equals(organ.getId())),
                    "器官构型没有对应状态：" + organ.getId());
        }
        BrewBodyFacts.detox(body, BrewThresholds.DEFAULT); // 校验同一快照足以计算肾档，失败抛异常。
        witch.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.POTION));
        witch.setUsingItem(true /* drinking */);
        var potion = BrewItemStacks.create(BrewKind.BITTER_YELLOW, BrewQuality.POOR, BrewUse.DRINK);
        BrewingRuntime.consume(witch.level(), witch, potion);
        witch.aiStep();
        context.assertTrue(!witch.isDrinkingPotion() && witch.getMainHandItem().isEmpty(), "反胃未中止女巫自喝");
        context.runAfterDelay(TEMPO_TICKS, () -> {
            context.assertTrue(!witch.isDrinkingPotion(), "反胃期间重新开始自喝");
            cleanup(witch);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void charmSessionRetriesNeverRestoreOldDeadlineOrEndedControl(GameTestHelper context) {
        // START、UPDATE、END 重放走正式入口；旧 START 不能复活已结束或延长已经减时的会话。
        Vindicator subject = subject(context, HumanoidTier.MORTAL);
        Witch caster = caster(context);
        long tick = context.getLevel().getServer().overworld().getGameTime();
        var session = CharmSession.newBuilder()
                .setSessionId(UUID.randomUUID().toString())
                .setSubjectUuid(subject.getStringUUID())
                .setCasterUuid(caster.getStringUUID())
                .setStartedTick(tick)
                .setEndsTick(tick + SESSION_TICKS)
                .build();
        exchange(context, CharmCommand.Action.START, session, CharmEndReason.CHARM_END_UNSPECIFIED);
        var shorter = session.toBuilder().setEndsTick(session.getEndsTick() - SHORTEN_TICKS).build();
        exchange(context, CharmCommand.Action.UPDATE, shorter, CharmEndReason.CHARM_END_UNSPECIFIED);
        exchange(context, CharmCommand.Action.START, session, CharmEndReason.CHARM_END_UNSPECIFIED);
        context.assertTrue(HumanoidCharmController.session(subject.getUUID()).equals(shorter), "重试 START 回滚期限");
        exchange(context, CharmCommand.Action.END, shorter, CharmEndReason.EFFECT_REMOVED);
        exchange(context, CharmCommand.Action.END, shorter, CharmEndReason.EFFECT_REMOVED);
        exchange(context, CharmCommand.Action.START, session, CharmEndReason.CHARM_END_UNSPECIFIED);
        context.assertTrue(!HumanoidCharmController.controlled(subject), "已结束会话被重放复活");
        context.runAfterDelay(SESSION_TICKS - SHORTEN_TICKS + 1, () -> {
            // 缩短后的期限已经过去，原始 START 尚未过期，结束墓碑仍须挡住重放。
            exchange(context, CharmCommand.Action.END, shorter, CharmEndReason.EFFECT_REMOVED);
            exchange(context, CharmCommand.Action.START, session, CharmEndReason.CHARM_END_UNSPECIFIED);
            context.assertTrue(!HumanoidCharmController.controlled(subject), "缩短期限到期后旧 START 复活了控制");
            cleanup(subject, caster);
            context.succeed();
        });
    }

    private static void exchange(GameTestHelper context, CharmCommand.Action action,
                                 CharmSession session, CharmEndReason reason) {
        // 通过实际登记的二进制服务入口检查回执，漏装控制器会直接失败。
        var command = CharmCommand.newBuilder()
                .setAction(action)
                .setSession(session)
                .setReason(reason)
                .build();
        CharmController controller = FabricLoader.getInstance()
                .getEntrypoints(CharmController.ENTRYPOINT, CharmController.class).getFirst();
        try {
            var reply = CharmReply.parseFrom(controller.exchange(CharmController.CALLER, command.toByteArray()));
            context.assertTrue(reply.getAccepted(), "魅惑控制器拒绝合法重试");
        } catch (InvalidProtocolBufferException invalid) {
            throw new IllegalStateException("控制器回执损坏", invalid);
        }
    }

    private static Vindicator subject(GameTestHelper context, HumanoidTier tier) {
        // 出生档位走正式内容入口，测试只固定境界与手持斧，随后可以开启真实 AI。
        for (int x = 0; x <= FLOOR_EDGE; x++) {
            for (int z = 0; z <= FLOOR_EDGE; z++) {
                context.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        Vindicator subject = spawn(context, EntityTypes.VINDICATOR, SUBJECT, tier);
        subject.setLeftHanded(false /* leftHanded */);
        subject.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        return subject;
    }

    private static Witch caster(GameTestHelper context) {
        // 施药者留在喷溅范围外，避免测试无意控制施药者本身。
        return spawn(context, EntityTypes.WITCH, CASTER, HumanoidTier.MORTAL);
    }

    private static <T extends Mob> T spawn(GameTestHelper context, EntityType<T> type,
                                           BlockPos relative, HumanoidTier tier) {
        // 加入世界前出生唯一权威身体，不覆盖已经认领的运行中身体。
        T mob = type.create(context.getLevel(), EntitySpawnReason.COMMAND);
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(relative));
        mob.setPos(position);
        mob.setNoAi(true /* noAi */);
        mob.setPersistenceRequired();
        HumanoidSpeciesBirths.birth(mob, tier);
        context.getLevel().addFreshEntity(mob);
        return mob;
    }

    private static Mob target(GameTestHelper context) {
        // 高血量牛只观察实际打击，不引入另一具身体或自动反击。
        Mob target = context.spawn(EntityTypes.COW, TARGET);
        target.setNoAi(true /* noAi */);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(TARGET_HP);
        target.setHealth((float) TARGET_HP);
        return target;
    }

    private static void splash(GameTestHelper context, LivingEntity subject, LivingEntity caster, BrewQuality quality) {
        // 正式范围命中包含脑力判定、状态、控制器和后续醒的生命周期。
        var potion = BrewItemStacks.create(BrewKind.CHARM, quality, BrewUse.SPLASH);
        BrewingRuntime.splash(context.getLevel(), potion, subject.position(), caster);
    }

    private static void cleanup(Mob... entities) {
        // 卸载事件结束会话并释放身体，避免测试实体继续干扰后续场地。
        for (Mob entity : entities) {
            entity.discard();
        }
    }

    private static final class ObservedMelee extends MeleeAttackGoal {
        private ObservedMelee(PathfinderMob mob) {
            // 暴露原版实际冷却，避免只测试 tempo 换算工具本身。
            super(mob, 1, true /* followingTargetEvenIfNotSeen */);
        }

        private int interval() {
            // 每次由原版重置，再观察已应用物种与组织速度后的冷却。
            resetAttackCooldown();
            return getTicksUntilNextAttack();
        }
    }
}
