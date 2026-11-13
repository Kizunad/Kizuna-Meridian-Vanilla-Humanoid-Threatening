package dev.kizuna.meridian.humanoid.fabric;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 《生人见闻录》：图书管理员交易给出的成书，书名和每页都是翻译键，简中、英文都要有字；
 * 简中正文是世界内文本，不许有机制说法和英文字母。
 */
public final class FieldNotesTextTest {
    private static final String TRADE =
            "/data/meridian_humanoid/villager_trade/librarian/1/emerald_and_book_field_notes.json";
    private static final String CHINESE = "zh_cn";
    private static final List<String> LANGUAGES = List.of(CHINESE, "en_us");
    private static final List<String> MECHANIC_WORDS = List.of(
            "分数", "倍率", "机制", "判定", "时窗", "冷却", "动作栏", "窗口", "界面", "按键",
            "右键", "左键", "主手", "副手", "玩家", "生物", "实体", "×", "%");
    private static final Pattern LATIN = Pattern.compile("[A-Za-z]");

    @Test
    public void everyPageHasTextInEveryLanguage() throws IOException {
        // 交易里的书名键和每页的键，两种语言都要有，而且不是空白。
        List<String> keys = bookKeys();
        assertTrue("书里没有内页", keys.size() > 1);
        for (String language : LANGUAGES) {
            JsonObject lang = read(langPath(language));
            for (String key : keys) {
                assertTrue(language + " 缺 " + key, lang.has(key));
                assertFalse(language + " 的 " + key + " 是空的", lang.get(key).getAsString().isBlank());
            }
        }
    }

    @Test
    public void chineseTextUsesNoMechanicWords() throws IOException {
        // 简中的书名和每页里找机制说法和英文字母，一处都不许有。
        JsonObject lang = read(langPath(CHINESE));
        List<String> found = new ArrayList<>();
        for (String key : bookKeys()) {
            String text = lang.get(key).getAsString();
            for (String word : MECHANIC_WORDS) {
                if (text.contains(word)) {
                    found.add(key + "：" + word);
                }
            }
            if (LATIN.matcher(text).find()) {
                found.add(key + "：英文字母");
            }
        }
        assertTrue("正文里有机制说法：" + found, found.isEmpty());
    }

    private static List<String> bookKeys() throws IOException {
        // 书名键在物品名上，正文键在成书内容的每一页上，按页序排。
        JsonObject components = read(TRADE).getAsJsonObject("gives").getAsJsonObject("components");
        List<String> keys = new ArrayList<>();
        keys.add(components.getAsJsonObject("minecraft:item_name").get("translate").getAsString());
        var pages = components.getAsJsonObject("minecraft:written_book_content").getAsJsonArray("pages");
        for (JsonElement page : pages) {
            keys.add(page.getAsJsonObject().get("translate").getAsString());
        }
        return keys;
    }

    private static String langPath(String language) {
        // 本模组语言文件在类路径上的位置。
        return "/assets/meridian_humanoid/lang/" + language + ".json";
    }

    private static JsonObject read(String resource) throws IOException {
        // 从类路径读主资源里的一份 JSON。
        try (InputStream stream = FieldNotesTextTest.class.getResourceAsStream(resource)) {
            assertNotNull("找不到 " + resource, stream);
            var reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
