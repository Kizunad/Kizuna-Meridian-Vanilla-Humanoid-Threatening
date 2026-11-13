package dev.kizuna.meridian.humanoid.fabric;

import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.TradeSet;
import net.minecraft.world.item.trading.TradeSets;
import net.minecraft.world.item.trading.VillagerTrade;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import static dev.kizuna.meridian.humanoid.fabric.HumanoidGameTestTicks.DEFAULT_TIMEOUT;

/**
 * 《生人见闻录》的来源：新手图书管理员的交易组改指本模组的标签，标签里是原版这一组的全部交易加这本书；
 * 照原版村民的上下文能换出这本成书。GameTest 服务端开着交易调整实验，原版标签换成实验那一份，这一组照样有书。
 */
public final class HumanoidFieldNotesGameTest {
    private static final ResourceKey<VillagerTrade> FIELD_NOTES = ResourceKey.create(Registries.VILLAGER_TRADE,
            Identifier.fromNamespaceAndPath("meridian_humanoid", "librarian/1/emerald_and_book_field_notes"));
    private static final TagKey<VillagerTrade> VANILLA_NOVICE = TagKey.create(Registries.VILLAGER_TRADE,
            Identifier.withDefaultNamespace("librarian/level_1"));
    private static final String TITLE_KEY = "book.meridian_humanoid.field_notes";
    private static final String PAGE_KEY = TITLE_KEY + ".page.";
    private static final int NOVICE = 1;
    private static final int ORIGIN = 1;
    private static final int FLOOR = 2;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void noviceLibrarianSellsFieldNotes(GameTestHelper context) {
        // 新手图书管理员的交易组里有这本书、原版那一组一单不少；再照原版取货的上下文换出一份，看书名、每页和价钱。
        var registries = context.getLevel().registryAccess();
        var trades = registries.lookupOrThrow(Registries.VILLAGER_TRADE);
        Holder<VillagerTrade> trade = trades.getOrThrow(FIELD_NOTES);
        TradeSet novice = registries.lookupOrThrow(Registries.TRADE_SET).getValueOrThrow(TradeSets.LIBRARIAN_LEVEL_1);
        HolderSet<VillagerTrade> offered = novice.getTrades();
        context.assertTrue(offered.contains(trade), "新手图书管理员的交易组里没有生人见闻录：" + offered);
        for (Holder<VillagerTrade> vanilla : trades.getOrThrow(VANILLA_NOVICE)) {
            context.assertTrue(offered.contains(vanilla), "交易组丢了原版的一单：" + vanilla);
        }
        Villager librarian = librarian(context);
        MerchantOffer offer = trade.value().getOffer(offerContext(context.getLevel(), librarian));
        context.assertTrue(offer != null, "图书管理员换不出生人见闻录");
        context.assertTrue(offer.getBaseCostA().is(Items.EMERALD) && offer.getCostB().is(Items.BOOK),
                "价钱不是绿宝石加一本书：" + offer.getBaseCostA() + " + " + offer.getCostB());
        assertBook(context, offer.getResult());
        librarian.discard();
        context.succeed();
    }

    private static Villager librarian(GameTestHelper context) {
        // 一位刚入行的图书管理员，和原版交易时一样作为取货上下文里的商人。
        Villager villager = context.spawn(EntityTypes.VILLAGER, ORIGIN, FLOOR, ORIGIN);
        var registries = context.getLevel().registryAccess();
        var data = villager.getVillagerData().withProfession(registries, VillagerProfession.LIBRARIAN);
        villager.setVillagerData(data.withLevel(NOVICE));
        return villager;
    }

    private static LootContext offerContext(ServerLevel level, Villager librarian) {
        // 照原版村民补货时的取货参数：位置、商人本身、允许追加价钱。
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, librarian.position())
                .withParameter(LootContextParams.THIS_ENTITY, librarian)
                .withParameter(LootContextParams.ADDITIONAL_COST_COMPONENT_ALLOWED, Unit.INSTANCE)
                .create(LootContextParamSets.VILLAGER_TRADE);
        return new LootContext.Builder(params).create(Optional.empty());
    }

    private static void assertBook(GameTestHelper context, ItemStack book) {
        // 成书的名字和每一页都是翻译键，按页序编号，客户端按自己的语言显示。
        context.assertTrue(book.is(Items.WRITTEN_BOOK), "换出来的不是成书：" + book);
        context.assertTrue(translates(book.getHoverName(), TITLE_KEY), "书名不是生人见闻录：" + book.getHoverName());
        WrittenBookContent content = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
        context.assertTrue(content != null && !content.pages().isEmpty(), "成书没有内页");
        List<Component> pages = content.getPages(/* filtered */ false);
        for (int index = 0; index < pages.size(); index++) {
            String key = PAGE_KEY + (index + 1);
            context.assertTrue(translates(pages.get(index), key), "第 " + (index + 1) + " 页不是 " + key);
        }
    }

    private static boolean translates(Component text, String key) {
        // 这段文字是不是直接引用这个翻译键。
        return text.getContents() instanceof TranslatableContents translatable && translatable.getKey().equals(key);
    }
}
