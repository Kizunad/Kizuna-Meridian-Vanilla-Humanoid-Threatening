package dev.kizuna.meridian.humanoid.fabric;

import static dev.kizuna.meridian.humanoid.fabric.HumanoidGameTestTicks.DEFAULT_TIMEOUT;

import dev.kizuna.meridian.technique.fabric.TechniqueCodex;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItem;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItems;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * 「修行」页功法线的物种分支：跟哪种人交过手（打它或被它打）就完成哪一条，村民、行商做过买卖也算；
 * 「夺卷」认 {@code #meridian_technique:scrolls}，整套装配里每一卷功法卷都在标签里；
 * 用卷轴学会第一卷时「功法谱」送一本功法谱：读掉手上最后一卷时书落进那只手，背包满了掉在脚下。
 */
public final class HumanoidAdvancementGameTest {
    private static final String NAMESPACE = "meridian_humanoid_host";
    private static final float HIT = 1.0F;
    private static final int TRADE_USES = 1;
    private static final int TRADE_XP = 1;
    private static final float TRADE_PRICE_MULTIPLIER = 0.05F;
    private static final BlockPos MOB_POS = new BlockPos(1, 1, 1);
    private static final List<EntityType<? extends LivingEntity>> SPECIES = List.of(EntityTypes.VILLAGER,
            EntityTypes.WANDERING_TRADER, EntityTypes.PILLAGER, EntityTypes.VINDICATOR, EntityTypes.WITCH);
    private static final Identifier FIRST_SCROLL = Identifier.fromNamespaceAndPath(NAMESPACE,
            "cultivators/first_scroll");
    private static final TagKey<Item> SCROLLS = TagKey.create(Registries.ITEM,
            Identifier.parse("meridian_technique:scrolls"));
    private static final Identifier CODEX_GIFT = Identifier.fromNamespaceAndPath(NAMESPACE,
            "cultivators/technique_codex");
    private static final double FEET_RADIUS = 2;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void hittingEachHumanoidCompletesOnlyItsLeaf(GameTestHelper context) {
        // 先打一只僵尸，哪条都不算；再挨个打一遍，每种打之前它那条都还没完成，打完才完成。
        ServerPlayer player = player(context);
        try {
            LivingEntity zombie = context.spawn(EntityTypes.ZOMBIE, MOB_POS);
            hit(context, player, zombie);
            zombie.discard();
            for (EntityType<? extends LivingEntity> species : SPECIES) {
                requireOpen(context, player, leaf(species), "没跟它交手就完成了 " + leaf(species));
            }
            for (EntityType<? extends LivingEntity> species : SPECIES) {
                Identifier leaf = leaf(species);
                requireOpen(context, player, leaf, "打别的种类时就完成了 " + leaf);
                LivingEntity target = context.spawn(species, MOB_POS);
                hit(context, player, target);
                target.discard();
                requireDone(context, player, leaf, "打了它没完成 " + leaf);
            }
        } finally {
            release(player);
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void beingHitByVindicatorCompletesItsLeaf(GameTestHelper context) {
        // 被卫道士打一下也算交过手，别的种类不受影响。
        ServerPlayer player = player(context);
        try {
            LivingEntity vindicator = context.spawn(EntityTypes.VINDICATOR, MOB_POS);
            requireOpen(context, player, leaf(EntityTypes.VINDICATOR), "没挨打就完成了破门斩");
            player.hurtServer(context.getLevel(), player.damageSources().mobAttack(vindicator), HIT);
            vindicator.discard();
            requireDone(context, player, leaf(EntityTypes.VINDICATOR), "挨了卫道士一下没完成破门斩");
            requireOpen(context, player, leaf(EntityTypes.PILLAGER), "挨卫道士打完成了行矢");
        } finally {
            release(player);
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void tradingCompletesVillagerAndTraderLeaves(GameTestHelper context) {
        // 和村民、行商各做一笔买卖，各自那条完成。
        ServerPlayer player = player(context);
        try {
            for (EntityType<? extends AbstractVillager> species : List.of(EntityTypes.VILLAGER,
                    EntityTypes.WANDERING_TRADER)) {
                requireOpen(context, player, leaf(species), "没做买卖就完成了 " + leaf(species));
                AbstractVillager merchant = context.spawn(species, MOB_POS);
                merchant.setTradingPlayer(player);
                merchant.notifyTrade(new MerchantOffer(new ItemCost(Items.EMERALD), new ItemStack(Items.BREAD),
                        TRADE_USES, TRADE_XP, TRADE_PRICE_MULTIPLIER));
                merchant.setTradingPlayer(null);
                merchant.discard();
                requireDone(context, player, leaf(species), "做了买卖没完成 " + leaf(species));
            }
        } finally {
            release(player);
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void techniqueScrollPickupCompletesFirstScroll(GameTestHelper context) {
        // 修炼残卷不是功法卷，不算；行商掉的赶路卷进背包才完成。
        ServerPlayer player = player(context);
        try {
            give(player, item("meridian_progression:fragment_scroll"));
            requireOpen(context, player, FIRST_SCROLL, "拿到修炼残卷就完成了夺卷");
            give(player, item("meridian_peddler:road_scroll"));
            requireDone(context, player, FIRST_SCROLL, "拿到千里腿·赶路卷没完成夺卷");
        } finally {
            release(player);
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void everyTechniqueScrollIsTagged(GameTestHelper context) {
        // 各功法包各自往标签里加卷；整套装配里注册的功法卷一卷不漏，标签里也没有别的东西。
        List<TechniqueScrollItem> scrolls = List.copyOf(TechniqueScrollItems.scrolls().values());
        for (TechniqueScrollItem scroll : scrolls) {
            context.assertTrue(new ItemStack(scroll).is(SCROLLS), "功法卷不在夺卷标签里：" + scroll);
        }
        int tagged = 0;
        for (var ignored : BuiltInRegistries.ITEM.getTagOrEmpty(SCROLLS)) {
            tagged++;
        }
        context.assertTrue(tagged == scrolls.size(), "夺卷标签 " + tagged + " 项，功法卷 " + scrolls.size() + " 卷");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void firstLearnedVolumeGivesOneCodex(GameTestHelper context) {
        // 用卷轴学会第一卷送一本功法谱；再学一卷不再送。
        ServerPlayer player = player(context);
        player.setGameMode(GameType.CREATIVE);
        try {
            requireOpen(context, player, CODEX_GIFT, "没学卷就完成了功法谱");
            learn(context, player, "meridian_basic_techniques:breath_volume", 1);
            requireDone(context, player, CODEX_GIFT, "学会第一卷没完成功法谱");
            context.assertTrue(codexCount(player) == 1, "学会第一卷没送功法谱：" + codexCount(player));
            learn(context, player, "meridian_basic_techniques:light_step_volume", 1);
            context.assertTrue(codexCount(player) == 1, "学会第二卷又送了一本：" + codexCount(player));
        } finally {
            release(player);
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void lastScrollInHandTurnsIntoTheCodex(GameTestHelper context) {
        // 生存玩家读掉手上唯一一卷：送的书进了读空的主手，原版按读空的卷轴清手时不能把它一起清掉。
        ServerPlayer player = player(context);
        try {
            learn(context, player, "meridian_basic_techniques:breath_volume", 1);
            context.assertTrue(player.getMainHandItem().is(TechniqueCodex.item()),
                    "读空的主手里不是功法谱：" + player.getMainHandItem());
            context.assertTrue(codexCount(player) == 1, "读掉唯一一卷后功法谱 " + codexCount(player) + " 本");
        } finally {
            release(player);
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void codexGiftDropsAtFeetWhenInventoryIsFull(GameTestHelper context) {
        // 生存玩家站进测试结构（区块已加载，掉落物才看得见），手上两卷、其余格子塞满石头，学完手上还剩一卷；
        // 送的书掉在脚边。
        // 创造玩家背包满时原版直接吞掉奖励物品，所以这条用生存。
        ServerPlayer player = player(context);
        Vec3 feet = context.absoluteVec(Vec3.atBottomCenterOf(MOB_POS));
        player.connection.teleport(feet.x, feet.y, feet.z, 0, 0);
        try {
            var inventory = player.getInventory();
            for (int slot = 1; slot < inventory.getNonEquipmentItems().size(); slot++) {
                inventory.setItem(slot, new ItemStack(Items.STONE, Items.STONE.getDefaultMaxStackSize()));
            }
            learn(context, player, "meridian_basic_techniques:breath_volume", 2);
            context.assertTrue(codexCount(player) == 0, "背包满了书还进了背包");
            var dropped = context.getLevel().getEntitiesOfClass(ItemEntity.class,
                    player.getBoundingBox().inflate(FEET_RADIUS), entity -> entity.getItem().is(TechniqueCodex.item()));
            context.assertTrue(dropped.size() == 1, "背包满了书没掉在脚下：" + dropped.size());
            dropped.forEach(ItemEntity::discard);
        } finally {
            release(player);
        }
        context.succeed();
    }

    private static void learn(GameTestHelper context, ServerPlayer player, String volume, int count) {
        // 拿卷轴走原版右键入口学会；这卷每一式都进了已学才算学会。
        TechniqueScrollItem scroll = TechniqueScrollItems.scrolls().get(Identifier.parse(volume + "_scroll"));
        ItemStack stack = new ItemStack(scroll, count);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        player.gameMode.useItem(player, player.level(), stack, InteractionHand.MAIN_HAND);
        var known = TechniqueFabric.service().known(player.getUUID()).stream().map(entry -> entry.technique()).toList();
        context.assertTrue(known.containsAll(scroll.scroll().volume().getTechniquesList()), "卷轴没学会：" + volume);
    }

    private static int codexCount(ServerPlayer player) {
        // 背包里功法谱的本数。
        return player.getInventory().countItem(TechniqueCodex.item());
    }

    private static Identifier leaf(EntityType<?> species) {
        // 物种分支的进度 ID 跟原版实体 ID 的路径走。
        return Identifier.fromNamespaceAndPath(NAMESPACE, "cultivators/" + EntityType.getKey(species).getPath());
    }

    private static ServerPlayer player(GameTestHelper context) {
        // 生存模式的测试玩家，先报一次客户端已载入：没载入的玩家服务器不让受伤。
        ServerPlayer player = context.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        return player;
    }

    private static void hit(GameTestHelper context, ServerPlayer player, LivingEntity target) {
        // 走原版受伤入口，伤害来源是这名玩家的近战。
        target.hurtServer(context.getLevel(), player.damageSources().playerAttack(player), HIT);
    }

    private static ItemStack item(String itemId) {
        return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId)));
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        // 和捡起来一样进背包；背包变动在菜单同步时报给进度。
        player.getInventory().add(stack);
        player.inventoryMenu.broadcastChanges();
    }

    private static void release(ServerPlayer player) {
        // 测试玩家下线，免得留在同一服务器的后续测试里。
        player.level().getServer().getPlayerList().remove(player);
    }

    private static void requireDone(GameTestHelper context, ServerPlayer player, Identifier id, String message) {
        // 进度必须已完成。
        context.assertTrue(done(player, id), message);
    }

    private static void requireOpen(GameTestHelper context, ServerPlayer player, Identifier id, String message) {
        // 进度必须还没完成。
        context.assertFalse(done(player, id), message);
    }

    private static boolean done(ServerPlayer player, Identifier id) {
        // 进度文件必须已加载，缺了直接报错而不是当作未完成。
        AdvancementHolder advancement = player.level().getServer().getAdvancements().get(id);
        if (advancement == null) {
            throw new AssertionError("进度没有加载：" + id);
        }
        return player.getAdvancements().getOrStartProgress(advancement).isDone();
    }
}
