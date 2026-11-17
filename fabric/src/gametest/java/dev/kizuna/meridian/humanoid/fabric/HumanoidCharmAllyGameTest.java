package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.brewing.fabric.BrewCharmSessions;
import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import dev.kizuna.meridian.brewing.fabric.BrewingRuntime;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/**
 * 生物施药者和它魅惑的生物：女巫魅惑了正和自己打的掠夺者以后不再打它；魅惑前互相记的仇一笔勾销；
 * 掠夺者替女巫去打伤过她的；受控期间近战、射弩、已经射出去的箭都伤不到女巫；醒了以后两边不凭旧账复打；
 * 调试仇恨覆盖在魅惑时自动解除。
 */
public final class HumanoidCharmAllyGameTest {
    private static final int TIMEOUT = 300;
    private static final double GAP = 6;
    private static final double FOE_GAP = 4;
    /** 优质魅惑控 120 tick；盯这么久，比复现里女巫把掠夺者打醒的第 39 tick 长得多。 */
    private static final int HELD_TICKS = 100;
    private static final int AFTER_TICKS = 60;
    /** 上弦 25 tick 再射；100 tick 的受击记忆内够射中好几箭。 */
    private static final int SHOOT_TICKS = 90;
    private static final int SETTLE_TICKS = 3;
    /** 两秒：远在 30 秒的记仇期以内。 */
    private static final int FEUD_TICKS = 40;
    private static final double FOE_HP = 1_000;
    private static final double HIT_HP = 1;
    private static final float ARROW_DAMAGE = 4;
    private static final String CHARM_CONTROL = "meridian_brewing:charm_control";
    /**
     * 有玩家在线的那条单独一批跑：世界里一有玩家，离他 128 格外、没设「不消失」的怪物当场消失，
     * 同批别的用例里的女巫会被它弄没。
     */
    private static final String PLAYER_BATCH = "meridian_humanoid_test:charm_debug_player";
    private static final double COMMANDER_GAP = 3;

    @GameTest(maxTicks = TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void witchLeavesThePillagerSheCharmedAloneAndNeitherResumesAfterItWakes(GameTestHelper context) {
        // 两只开着真实 AI、互为目标、互相伤过。女巫魅惑掠夺者：她当场放下这个目标，整个受控期间不再盯它、
        // 不把它打醒；醒了以后两边都不凭旧账把对方选回来。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.TRUE_QI);
        Pillager pillager = pillager(context, witch);
        feud(witch, pillager);
        wake(witch, pillager);
        charm(context, pillager, witch);
        context.assertTrue(BrewCharmSessions.controlled(pillager.getUUID()), "优质魅惑没有控住掠夺者");
        context.assertTrue(witch.getTarget() != pillager, "女巫没有放下刚魅惑的目标");
        context.assertTrue(!HumanoidThreats.enemy(witch, pillager) && !HumanoidThreats.enemy(pillager, witch),
                "魅惑着的两边还互为敌人");
        held(context, witch, pillager, 0);
    }

    private static void held(GameTestHelper context, Witch witch, Pillager pillager, int elapsed) {
        // 受控期间每 tick 看一眼；看够了就把掠夺者弄醒，再盯三秒。
        String when = "第 " + elapsed + " tick ";
        context.assertTrue(BrewCharmSessions.controlled(pillager.getUUID()), when + "掠夺者醒了：被施药者打醒的");
        context.assertTrue(witch.getTarget() != pillager, when + "女巫又盯上了自己魅惑的掠夺者");
        context.assertTrue(pillager.getTarget() != witch, when + "受控的掠夺者盯着女巫");
        if (elapsed < HELD_TICKS) {
            context.runAfterDelay(1, () -> held(context, witch, pillager, elapsed + 1));
            return;
        }
        float witchHealth = witch.getHealth();
        float pillagerHealth = pillager.getHealth();
        rouse(pillager);
        context.assertTrue(!BrewCharmSessions.controlled(pillager.getUUID()), "清掉魅惑效果，会话没有结束");
        context.assertTrue(!HumanoidThreats.enemy(witch, pillager) && !HumanoidThreats.enemy(pillager, witch),
                "醒了以后两边还互为敌人");
        after(context, witch, pillager, new float[] {witchHealth, pillagerHealth}, 0);
    }

    private static void after(GameTestHelper context, Witch witch, Pillager pillager, float[] health, int elapsed) {
        String when = "醒后第 " + elapsed + " tick ";
        context.assertTrue(witch.getTarget() != pillager, when + "女巫凭旧账又盯上了掠夺者");
        context.assertTrue(pillager.getTarget() != witch, when + "掠夺者凭旧账又盯上了女巫");
        context.assertTrue(witch.getHealth() >= health[0] && pillager.getHealth() >= health[1],
                when + "两边又打起来了");
        if (elapsed < AFTER_TICKS) {
            context.runAfterDelay(1, () -> after(context, witch, pillager, health, elapsed + 1));
            return;
        }
        discard(witch, pillager);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void witchAndTheVillagerSheCharmedForgetTheirFeud(GameTestHelper context) {
        // 女巫和村民不是一伙的，互相伤过以后大脑各记对方 30 秒的仇（女巫和掠夺者是一伙的，互伤不记这本账，
        // 所以这一条换成村民）。女巫魅惑村民：受控期间两边互不为敌；两秒后把村民弄醒，仇还没到期，但旧账已清，
        // 两边仍然互不为敌。两只都不开 AI，只看这本账。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.TRUE_QI);
        Villager villager = WitchTestSupport.spawn(context, EntityTypes.VILLAGER, HumanoidTier.MORTAL);
        villager.setPos(witch.position().add(0, 0, FOE_GAP));
        hit(witch, villager);
        hit(villager, witch);
        context.assertTrue(HumanoidThreats.enemy(witch, villager) && HumanoidThreats.enemy(villager, witch),
                "前提不成立：互伤以后两边没有互相记仇");
        charm(context, villager, witch);
        context.assertTrue(BrewCharmSessions.controlled(villager.getUUID()), "优质魅惑没有控住村民");
        context.assertTrue(!HumanoidThreats.enemy(witch, villager) && !HumanoidThreats.enemy(villager, witch),
                "魅惑着的两边还互为敌人");
        context.runAfterDelay(FEUD_TICKS, () -> {
            rouse(villager);
            context.assertTrue(!BrewCharmSessions.controlled(villager.getUUID()), "清掉魅惑效果，会话没有结束");
            context.assertTrue(!HumanoidThreats.enemy(witch, villager) && !HumanoidThreats.enemy(villager, witch),
                    "醒了以后两边还凭魅惑前互伤的旧账互为敌人");
            discard(witch, villager);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void charmedPillagerFightsTheOneThatHurtTheWitch(GameTestHelper context) {
        // 女巫手上没有目标，只是刚被一只尸壳伤过。魅惑掠夺者：它的对手就是那只尸壳，四秒半内射中它，女巫没挨打。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.MORTAL);
        Pillager pillager = pillager(context, witch);
        pillager.setPos(witch.position().add(0, 0, FOE_GAP));
        Mob husk = husk(context, witch);
        hit(witch, husk);
        pillager.setNoGravity(false /* noGravity */);
        pillager.setNoAi(false /* aiDisabled */);
        float witchHealth = witch.getHealth();
        charm(context, pillager, witch);
        context.assertTrue(HumanoidCharmController.opponent(pillager) == husk,
                "受控掠夺者的对手不是伤过女巫的尸壳：" + HumanoidCharmController.opponent(pillager));
        context.assertTrue(pillager.getTarget() == husk, "受控掠夺者没有盯上伤过女巫的尸壳");
        shoots(context, witch, pillager, husk, witchHealth, 0);
    }

    private static void shoots(GameTestHelper context, Witch witch, Pillager pillager, Mob husk, float witchHealth,
                               int elapsed) {
        if (husk.getHealth() >= FOE_HP && elapsed < SHOOT_TICKS) {
            context.runAfterDelay(1, () -> shoots(context, witch, pillager, husk, witchHealth, elapsed + 1));
            return;
        }
        context.assertTrue(husk.getHealth() < FOE_HP, "受控掠夺者没有射中伤过女巫的尸壳");
        context.assertTrue(witch.getHealth() >= witchHealth, "受控掠夺者射尸壳时伤到了女巫");
        discard(witch, pillager, husk);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void charmedPillagerCannotTargetShootOrStrikeTheWitch(GameTestHelper context) {
        // 掠夺者本来盯着女巫、伤过她。受控以后：目标改不回女巫，直接调射弩入口不出箭，近战入口打不中，
        // 功法筛选不认她，已经飞在路上的箭也伤不到她；开着 AI 再看三秒，一箭没射、女巫没掉血。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.MORTAL);
        Pillager pillager = pillager(context, witch);
        feud(witch, pillager);
        charm(context, pillager, witch);
        float health = witch.getHealth();
        pillager.setTarget(witch);
        context.assertTrue(pillager.getTarget() != witch, "受控掠夺者的目标被改回了女巫");
        pillager.performRangedAttack(witch, 1 /* power */);
        context.assertTrue(arrows(context, pillager).isEmpty(), "受控掠夺者朝女巫射出了箭");
        context.assertTrue(!pillager.doHurtTarget(context.getLevel(), witch), "受控掠夺者近战打中了女巫");
        context.assertTrue(!HumanoidThreats.hittable(pillager, witch), "受控掠夺者的功法筛选还认女巫");
        var arrow = EntityTypes.ARROW.create(context.getLevel(), EntitySpawnReason.COMMAND);
        arrow.setOwner(pillager);
        witch.hurtServer(context.getLevel(), context.getLevel().damageSources().arrow(arrow, pillager), ARROW_DAMAGE);
        context.assertTrue(witch.getHealth() >= health, "受控前射出去的箭伤到了女巫");
        pillager.setNoGravity(false /* noGravity */);
        pillager.setNoAi(false /* aiDisabled */);
        quiet(context, witch, pillager, health, 0);
    }

    private static void quiet(GameTestHelper context, Witch witch, Pillager pillager, float health, int elapsed) {
        context.assertTrue(arrows(context, pillager).isEmpty(), "第 " + elapsed + " tick 受控掠夺者射了箭");
        context.assertTrue(witch.getHealth() >= health, "第 " + elapsed + " tick 女巫掉血了");
        if (elapsed < AFTER_TICKS) {
            context.runAfterDelay(1, () -> quiet(context, witch, pillager, health, elapsed + 1));
            return;
        }
        discard(witch, pillager);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT, structure = WitchTestSupport.STRUCTURE, environment = PLAYER_BATCH)
    public void debugAggroYieldsToCharmAndTellsThePlayerWhoSetIt(GameTestHelper context) {
        // 玩家用调试命令让两只互相仇恨。女巫魅惑掠夺者：两条覆盖都自动解除，下命令的玩家聊天栏收到一句；
        // 掠夺者醒了以后覆盖不会回来，两边的目标不再被写回去。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.MORTAL);
        witch.setPersistenceRequired();
        Pillager pillager = pillager(context, witch);
        CrisisTestPlayer commander = CrisisTestPlayer.create(context);
        commander.player().setGameMode(GameType.CREATIVE);
        commander.player().snapTo(witch.position().add(COMMANDER_GAP, 0, 0));
        var source = commander.player().createCommandSourceStack().withPermission(LevelBasedPermissionSet.OWNER);
        int linked = CrisisGameTests.run(source,
                "meridian humanoid aggro " + pillager.getStringUUID() + " " + witch.getStringUUID());
        context.assertTrue(linked == 1, "调试仇恨命令没有执行成功：" + linked);
        context.runAfterDelay(SETTLE_TICKS, () -> {
            context.assertTrue(pillager.getTarget() == witch && witch.getTarget() == pillager,
                    "调试仇恨没有设上：掠夺者盯着 " + pillager.getTarget() + "，女巫盯着 " + witch.getTarget());
            commander.connection().packets().clear();
            charm(context, pillager, witch);
            context.runAfterDelay(SETTLE_TICKS, () -> {
                List<String> told = commander.connection().packets().stream()
                        .filter(ClientboundSystemChatPacket.class::isInstance)
                        .map(packet -> ((ClientboundSystemChatPacket) packet).content().getString())
                        .filter(text -> text.contains("被魅惑了")).toList();
                context.assertTrue(told.size() == 2, "两条覆盖解除应当各提示一句，实际：" + told);
                rouse(pillager);
                context.runAfterDelay(SETTLE_TICKS, () -> {
                    context.assertTrue(pillager.getTarget() != witch && witch.getTarget() != pillager,
                            "掠夺者醒了以后调试仇恨又把目标写回去了");
                    commander.close();
                    discard(witch, pillager);
                    context.succeed();
                });
            });
        });
    }

    private static Pillager pillager(GameTestHelper context, Witch witch) {
        // 凡人掠夺者拿弩，站在女巫前方 6 格；先不开 AI。
        Pillager pillager = WitchTestSupport.spawn(context, EntityTypes.PILLAGER, HumanoidTier.MORTAL);
        pillager.setPos(witch.position().add(0, 0, GAP));
        pillager.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
        pillager.setPersistenceRequired();
        return pillager;
    }

    private static Mob husk(GameTestHelper context, Witch witch) {
        // 不动、血很厚的尸壳，站在掠夺者身后，和女巫隔 8 格。
        Mob husk = EntityTypes.HUSK.create(context.getLevel(), EntitySpawnReason.COMMAND);
        husk.setPos(witch.position().add(0, 0, 2 * FOE_GAP));
        husk.setNoAi(true /* aiDisabled */);
        husk.setNoGravity(true /* noGravity */);
        husk.setPersistenceRequired();
        husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(FOE_HP);
        husk.setHealth((float) FOE_HP);
        context.assertTrue(context.getLevel().addFreshEntity(husk), "尸壳没加入世界");
        return husk;
    }

    private static void feud(Witch witch, Pillager pillager) {
        // 互为目标，各伤对方一下：原版目标、类人大脑的受击记忆、原版记的「谁打过我」都有了。
        witch.setTarget(pillager);
        pillager.setTarget(witch);
        hit(witch, pillager);
        hit(pillager, witch);
    }

    private static void wake(Mob... mobs) {
        // 放开真实 AI 和重力。
        for (Mob mob : mobs) {
            mob.setNoGravity(false /* noGravity */);
            mob.setNoAi(false /* aiDisabled */);
        }
    }

    private static void hit(LivingEntity target, LivingEntity attacker) {
        // 指定部位的一点直接伤害，走正式的伤害通知。
        var source = target.damageSources().mobAttack(attacker);
        VanillaDamageFabric.damageDirect(new VanillaDamageFabric.DirectDamage(target, source, "right_upper_arm",
                HIT_HP)).orElseThrow();
    }

    private static void charm(GameTestHelper context, LivingEntity subject, LivingEntity caster) {
        // 正式的喷溅入口：脑力判定、状态、控制器都真走一遍；施药者是这只女巫。
        var potion = BrewItemStacks.create(BrewKind.CHARM, BrewQuality.SUPERIOR, BrewUse.SPLASH);
        BrewingRuntime.splash(context.getLevel(), potion, subject.position(), caster);
    }

    private static void rouse(LivingEntity subject) {
        // 清掉魅惑效果，和到期、喝牛奶走的是同一个结束入口。
        subject.removeEffect(BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(CHARM_CONTROL)).orElseThrow());
    }

    private static List<AbstractArrow> arrows(GameTestHelper context, Pillager pillager) {
        // 这只掠夺者射出去、还在世界里的箭。
        return context.getLevel().getEntitiesOfClass(AbstractArrow.class,
                pillager.getBoundingBox().inflate(WitchTestSupport.FAR * 2), arrow -> arrow.getOwner() == pillager);
    }

    private static void discard(Mob... mobs) {
        for (Mob mob : mobs) {
            mob.discard();
        }
    }
}
