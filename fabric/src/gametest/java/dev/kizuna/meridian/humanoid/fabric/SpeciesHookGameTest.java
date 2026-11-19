package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.humanoid.server.SpeciesBodyRules;
import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages;
import dev.kizuna.meridian.protocol.pb.HealthMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.MovementAction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.MovementIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostRequest;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.technique.server.TechniqueProtocol;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;

public final class SpeciesHookGameTest {
    private static final int SPAWN = 2;
    private static final int OBSERVE_TICKS = 5;
    private static final String BODY = "meridian_humanoid";
    private static final int CLAIM_QUERIES = 1000;
    private static final int ATTACK_INTERVAL = 20;

    @GameTest
    public void unregisteredTypeIsResolvedAtMostOnce(GameTestHelper test) {
        var cow = test.spawn(EntityTypes.COW, SPAWN, SPAWN, SPAWN);
        int before = HumanoidSpeciesHooks.typeResolutions();
        for (int i = 0; i < CLAIM_QUERIES; i++) {
            test.assertTrue(!HumanoidFabric.isClaimed(cow), "牛被认领");
        }
        test.assertTrue(HumanoidSpeciesHooks.typeResolutions() - before <= 1, "牛重复转换类型 ID 并查物种表");
        test.succeed();
    }

    @GameTest
    public void customBirthSurvivesEntitySave(GameTestHelper test) {
        var goat = test.spawn(EntityTypes.GOAT, SPAWN, SPAWN, SPAWN);
        goat.addTag(SpeciesHookFixture.CLAIM);
        HumanoidSpeciesBirths.birth(goat, HumanoidTier.MORTAL);
        var born = HumanoidGameTest.inspect(goat).getBody();
        test.assertTrue(born.getTemplate().getId().equals(SpeciesHookFixture.TEMPLATE), "出生未调用物种生成器");
        test.assertTrue(born.getId().equals("meridian_humanoid:entity/" + goat.getUUID()), "出生身份错误");
        var saved = HumanoidEntityNbt.save(goat);
        test.assertTrue(saved.contains(BODY), "出生身体未保存");
        ((HumanoidEntityAccess) goat).meridian$humanoidBody(null);
        HumanoidEntityNbt.load(goat, saved);
        test.assertTrue(HumanoidGameTest.inspect(goat).getBody().equals(born), "读档改变了出生快照");
        test.succeed();
    }

    @GameTest
    public void malformedBirthNeverBinds(GameTestHelper test) {
        var entity = test.spawn(EntityTypes.MOOSHROOM, SPAWN, SPAWN, SPAWN);
        entity.addTag(SpeciesHookFixture.CLAIM);
        rejectsBirth(test, entity, HumanoidTier.MORTAL, "身体 ID");
        rejectsBirth(test, entity, HumanoidTier.WANDERING_QI, "结算时刻");
        test.assertTrue(!HumanoidEntityNbt.save(entity).contains(BODY), "坏出生快照进了存档");
        test.succeed();
    }

    private static void rejectsBirth(GameTestHelper test, LivingEntity entity, HumanoidTier tier, String diagnostic) {
        try {
            HumanoidSpeciesBirths.birth(entity, tier);
        } catch (IllegalArgumentException rejected) {
            test.assertTrue(rejected.getMessage().contains(diagnostic), "出生报错原因错误：" + rejected);
            return;
        }
        test.fail("接受了错误的出生" + diagnostic);
    }

    @GameTest
    public void claimsOnlyTaggedIndividualAcrossTicks(GameTestHelper test) {
        var accepted = test.spawn(EntityTypes.GOAT, SPAWN, SPAWN, SPAWN);
        accepted.addTag(SpeciesHookFixture.CLAIM);
        var rejected = test.spawn(EntityTypes.GOAT, SPAWN, SPAWN, SPAWN);
        accepted.setNoAi(false /* noAi */);
        rejected.setNoAi(false /* noAi */);
        test.assertTrue(HumanoidBrain.managed(accepted), "同物种认领个体未接入大脑");
        test.assertTrue(HumanoidGameTest.inspect(accepted).hasBody(), "同物种的认领个体没有身体");
        test.assertTrue(HumanoidGameTest.inspect(rejected).getUnbound(), "健康宿主认领了排除个体");
        test.runAfterDelay(OBSERVE_TICKS, () -> {
            test.assertTrue(!HumanoidBrain.managed(rejected), "大脑按种类缓存了认领");
            test.assertTrue(HumanoidBrain.directive(rejected.getUUID()).isEmpty(), "排除个体运行了大脑");
            test.assertTrue(((HumanoidEntityAccess) rejected).meridian$humanoidBody() == null, "排除个体每刻出生");
            test.assertTrue(!HumanoidEntityNbt.save(rejected).contains(BODY), "排除个体写了身体存档");
            test.succeed();
        });
    }

    @GameTest
    public void revokedClaimDropsRuntimeState(GameTestHelper test) {
        var goat = test.spawn(EntityTypes.GOAT, SPAWN, SPAWN, SPAWN);
        goat.addTag(SpeciesHookFixture.CLAIM);
        goat.setNoAi(false /* noAi */);
        var session = HumanoidTechniqueHost.prepare(goat);
        test.runAfterDelay(OBSERVE_TICKS, () -> {
            test.assertTrue(HumanoidBrain.directive(goat.getUUID()).isPresent(), "认领后没有大脑状态");
            double freeSpeed = goat.getAttributeValue(Attributes.MOVEMENT_SPEED);
            lockMovement(session);
            test.assertTrue(goat.getAttributeValue(Attributes.MOVEMENT_SPEED) == 0, "施放没有锁住移速");
            goat.removeTag(SpeciesHookFixture.CLAIM);
            test.assertTrue(HumanoidGameTest.inspect(goat).getUnbound(), "撤销认领后宿主仍返回身体");
            test.runAfterDelay(OBSERVE_TICKS, () -> {
                test.assertTrue(!HumanoidTechniqueHost.hasSession(goat), "撤销认领后仍保有功法会话");
                test.assertTrue(goat.getAttributeValue(Attributes.MOVEMENT_SPEED) == freeSpeed, "施放限速未撤回");
                test.assertTrue(HumanoidBrain.directive(goat.getUUID()).isEmpty(), "撤销认领后仍保有大脑状态");
                goat.addTag(SpeciesHookFixture.CLAIM);
                HumanoidTechniqueHost.prepare(goat);
                goat.removeTag(SpeciesHookFixture.CLAIM);
                goat.discard();
                test.assertTrue(!HumanoidTechniqueHost.hasSession(goat), "撤销当刻移除仍保有会话");
                test.succeed();
            });
        });
    }

    private static void lockMovement(HumanoidTechniqueHost.Session session) {
        var movement = MovementIntent.newBuilder()
                .setAction(MovementAction.LOCK)
                .setMovementSpeedMultiplier(0)
                .build();
        var effect = EffectIntent.newBuilder()
                .setCastId(UUID.randomUUID().toString())
                .setMovement(movement)
                .build();
        session.effects().submit(new TechniqueHost.EffectSubmission(UUID.randomUUID().toString(), effect));
    }

    @GameTest
    public void revokedBodySurvivesSaveAndReclaim(GameTestHelper test) {
        var goat = test.spawn(EntityTypes.GOAT, SPAWN, SPAWN, SPAWN);
        goat.addTag(SpeciesHookFixture.CLAIM);
        double vanillaSpeed = goat.getAttributeValue(Attributes.MOVEMENT_SPEED);
        var wounded = SpeciesHookFixture.wounded(goat);
        double qi = wounded.releaseInventory().totalQi();
        test.assertTrue(qi > 0, "封存前身体没有真元");
        HumanoidTempo.update(goat);
        var projected = projection(goat);
        test.assertTrue(projected.speed() < vanillaSpeed && projected.interval() > ATTACK_INTERVAL,
                "伤体的移速和攻击间隔没有投影");
        var stored = HumanoidEntityNbt.save(goat).getCompound(BODY).orElseThrow();
        var before = new Sealed(stored, wounded.snapshot(), qi, projected);
        goat.removeTag(SpeciesHookFixture.CLAIM);
        assertSealed(test, goat, stored);
        // 关闭 AI 仍应在下一次实体 tick 撤回投影。
        goat.setNoAi(true /* noAi */);
        test.runAfterDelay(1, () -> {
            var current = projection(goat);
            test.assertTrue(current.speed() == vanillaSpeed, "撤销后移速未回到没有修正的值");
            test.assertTrue(current.interval() == ATTACK_INTERVAL, "撤销后攻击间隔未归一");
            test.runAfterDelay(OBSERVE_TICKS, () -> reloadSealed(test, goat, before));
        });
    }

    private static void assertSealed(GameTestHelper test, Mob mob, CompoundTag expected) {
        var saved = HumanoidEntityNbt.save(mob).getCompound(BODY);
        test.assertTrue(saved.isPresent(), "封存身体的存档键丢失");
        test.assertTrue(saved.orElseThrow().equals(expected), "封存期间快照或回执改变");
        var access = (HumanoidEntityAccess) mob;
        test.assertTrue(access.meridian$humanoidBody() == null, "封存身体仍可取用");
        test.assertTrue(HumanoidGameTest.inspect(mob).getUnbound(), "健康宿主仍认领封存身体");
        var inspect = TechniqueHostRequest.newBuilder()
                .setRequestId(UUID.randomUUID().toString())
                .setEntityUuid(mob.getStringUUID())
                .setInspect(true /* inspect */)
                .build();
        byte[] reply = new HumanoidTechniqueHost().exchange(TechniqueProtocol.encodeHost(inspect));
        test.assertTrue(TechniqueProtocol.decodeHostResponse(reply).getUnbound(), "功法宿主仍认领封存身体");
    }

    private static void reloadSealed(GameTestHelper test, Mob goat, Sealed before) {
        assertSealed(test, goat, before.tag());
        var nbt = HumanoidEntityNbt.save(goat);
        goat.discard();
        var loaded = EntityTypes.GOAT.create(test.getLevel(), EntitySpawnReason.LOAD);
        HumanoidEntityNbt.load(loaded, nbt);
        test.getLevel().addFreshEntity(loaded);
        assertSealed(test, loaded, before.tag());
        loaded.setNoAi(false /* noAi */);
        test.runAfterDelay(OBSERVE_TICKS, () -> {
            assertSealed(test, loaded, before.tag());
            test.assertTrue(HumanoidBrain.directive(loaded.getUUID()).isEmpty(), "封存时大脑仍推进");
            test.assertTrue(!HumanoidTechniqueHost.hasSession(loaded), "封存时仍有功法会话");
            loaded.addTag(SpeciesHookFixture.CLAIM);
            var species = HumanoidSpeciesHooks.claimed(loaded).orElseThrow();
            var body = HumanoidHealthHost.bodyState(loaded, species, HumanoidFabric.now());
            test.assertTrue(body.snapshot().equals(before.body()), "重新认领改变了身体身份、伤势或结算起点");
            test.assertTrue(body.releaseInventory().totalQi() == before.qi(), "封存前后真元总量改变");
            var resumed = HumanoidGameTest.inspect(loaded).getBody();
            test.assertTrue(resumed.getSettledAt().getElapsedMillis() > before.body().getSettledAt().getElapsedMillis(),
                    "重新认领没有从封存时刻结算");
            test.runAfterDelay(1, () -> {
                test.assertTrue(projection(loaded).equals(before.projected()), "重新认领后伤体投影未回来");
                test.succeed();
            });
        });
    }

    private static Projection projection(Mob mob) {
        return new Projection(mob.getAttributeValue(Attributes.MOVEMENT_SPEED),
                HumanoidTempo.attackInterval(mob, ATTACK_INTERVAL));
    }

    private record Projection(double speed, int interval) { }

    private record Sealed(CompoundTag tag, BodyMessages.BodySnapshot body, double qi, Projection projected) { }

    @GameTest
    public void contentInstalledAndLateRegistrationRejected(GameTestHelper test) {
        test.assertTrue(HumanoidFabric.content().findTemplate(SpeciesHookFixture.TEMPLATE, 1).isPresent(),
                "物种内容包缺少模板");
        String id = "species_test:late";
        try {
            HumanoidSpeciesHooks.registerAll(SpeciesHookFixture.TABLE.formatted(id), SpeciesBodyRules.DEFAULT,
                    entity -> true, List.of(SpeciesHookFixture.bundle()));
        } catch (IllegalStateException rejected) {
            test.assertTrue(rejected.getMessage().contains("已安装"), "晚交内容包的错误原因不对");
            test.assertTrue(HumanoidSpecies.fromEntityId(id).isEmpty(), "晚交内容包留下了物种");
            test.succeed();
            return;
        }
        test.fail("已安装内容目录仍接受新包");
    }

    @GameTest
    public void speciesVerdictSeesWoundsInBothDirections(GameTestHelper test) {
        var humanHead = test.spawn(EntityTypes.VILLAGER, SPAWN, SPAWN, SPAWN);
        var humanFoot = test.spawn(EntityTypes.VILLAGER, SPAWN, SPAWN, SPAWN);
        var customHead = test.spawn(EntityTypes.GOAT, SPAWN, SPAWN, SPAWN);
        var customFoot = test.spawn(EntityTypes.GOAT, SPAWN, SPAWN, SPAWN);
        customHead.addTag(SpeciesHookFixture.CLAIM);
        customFoot.addTag(SpeciesHookFixture.CLAIM);
        test.assertTrue(strike(humanHead, "head").getVitality().getDead(), "人体头伤应当致死");
        test.assertTrue(!strike(humanFoot, "left_foot").getVitality().getDead(), "人体脚伤应当存活");
        test.assertTrue(!strike(customHead, "head").getVitality().getDead(), "自带裁决应保留头伤者");
        test.assertTrue(strike(customFoot, "left_foot").getVitality().getDead(), "自带裁决应读到本击脚伤");
        test.succeed();
    }

    private static BodyMessages.BodySnapshot strike(LivingEntity entity, String part) {
        var body = HumanoidGameTest.inspect(entity).getBody();
        var hit = BodyMessages.HealthTarget.newBuilder().setPartId(part).build();
        double hp = body.getPartStatesList().stream().filter(p -> p.getId().equals(part)).findFirst().orElseThrow()
                .getHp();
        var damage = HealthMessages.DamageBody.newBuilder()
                .setSourceId("species_test:hit")
                .setDamageType("species_test:hit")
                .setKind(HealthMessages.DamageKind.CUSTOM)
                .setHit(hit)
                .setDamageHp(hp)
                .build();
        var command = MeridianMessages.BodyCommand.newBuilder()
                .setBodyId(body.getId())
                .setExpectedRevision(body.getRevision())
                .setDamageBody(damage)
                .build();
        String id = UUID.randomUUID().toString();
        var effect = MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(id)
                .setBodyCommand(command)
                .build();
        var request = HealthHostMessages.HealthHostRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(id)
                .setSubjectId(entity.getStringUUID())
                .setEffect(effect)
                .build();
        var response = HealthHostProtobuf.decodeResponse(new HumanoidHealthHost().exchange(
                HealthHostProtobuf.encodeRequest(request)));
        if (!response.hasBody()) {
            throw new AssertionError("部位伤害未提交：" + response);
        }
        return response.getBody();
    }
}
