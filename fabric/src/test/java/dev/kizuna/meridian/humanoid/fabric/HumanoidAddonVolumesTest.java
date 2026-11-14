package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.humanoid.server.VolumeTable;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 功法包在 fabric.mod.json 里声明的学卷片段并进学卷表。 */
public final class HumanoidAddonVolumesTest {
    private static final String VILLAGER = "minecraft:villager";
    private static final String FRAGMENT = "data/pack/humanoid_volumes.toml";
    private static final VolumeTable.Grant BREATH = new VolumeTable.Grant("meridian_test:breath", 1);
    private static final VolumeTable CONFIGURED =
            new VolumeTable(Map.of(VILLAGER, Map.of(HumanoidTier.WANDERING_QI, List.of(BREATH))));

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void declaredFragmentsJoinInModIdOrderWhateverTheLoadOrder() throws IOException {
        // 两个功法包各登记一卷：接在配置的卷后面，先后按模组 ID，和模组加载顺序无关；没声明的模组不读。
        ModContainer first = mod("a_pack", FRAGMENT, fragment("meridian_test:first"));
        ModContainer second = mod("b_pack", FRAGMENT, fragment("meridian_test:second"));
        ModContainer silent = mod("c_pack", null, folder.newFolder().toPath());
        var expected = List.of(BREATH, new VolumeTable.Grant("meridian_test:first", 1),
                new VolumeTable.Grant("meridian_test:second", 1));
        for (List<ModContainer> installed : List.of(List.of(first, second, silent), List.of(silent, second, first))) {
            VolumeTable joined = HumanoidAddonVolumes.joined(CONFIGURED, installed);
            assertEquals(expected, joined.species().get(VILLAGER).get(HumanoidTier.WANDERING_QI));
        }
    }

    @Test
    public void brokenDeclarationFailsNamingTheModAndPath() throws IOException {
        // 路径不是字符串、文件不在模组里、片段写错都拒绝，报错里带模组和路径。
        Path empty = folder.newFolder().toPath();
        var missing = assertThrows(IllegalArgumentException.class,
                () -> HumanoidAddonVolumes.joined(CONFIGURED, List.of(mod("a_pack", FRAGMENT, empty))));
        assertTrue(missing.getMessage(), missing.getMessage().startsWith("a_pack:" + FRAGMENT));
        Path wrong = fragment("fist");
        var invalid = assertThrows(IllegalArgumentException.class,
                () -> HumanoidAddonVolumes.joined(CONFIGURED, List.of(mod("a_pack", FRAGMENT, wrong))));
        assertTrue(invalid.getMessage(), invalid.getMessage().startsWith("a_pack:" + FRAGMENT));
        assertThrows(IllegalArgumentException.class,
                () -> HumanoidAddonVolumes.joined(CONFIGURED, List.of(mod("a_pack", List.of(FRAGMENT), wrong))));
    }

    private Path fragment(String volume) throws IOException {
        // 一个模组根目录，里面只有一份给村民游炁加一卷的片段。
        Path root = folder.newFolder().toPath();
        Path file = root.resolve(FRAGMENT);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "[volumes.\"" + VILLAGER + "\"]\nwandering_qi = [ { volume = \"" + volume
                + "\", chance = 1.0 } ]\n", StandardCharsets.UTF_8);
        return root;
    }

    private static ModContainer mod(String id, Object declared, Path root) {
        // 只替换读声明要用的 Fabric 元数据，不启动 Minecraft；declared 是字符串时按字符串声明，其他非空值按数组。
        CustomValue value = declared == null ? null : proxy(CustomValue.class, (instance, method, args) ->
                switch (method.getName()) {
                    case "getType" -> declared instanceof String ? CustomValue.CvType.STRING : CustomValue.CvType.ARRAY;
                    case "getAsString" -> declared;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ModMetadata metadata = proxy(ModMetadata.class, (instance, method, args) -> switch (method.getName()) {
            case "getId" -> id;
            case "containsCustomValue" -> value != null;
            case "getCustomValue" -> value;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        return proxy(ModContainer.class, (instance, method, args) -> switch (method.getName()) {
            case "getMetadata" -> metadata;
            case "findPath" -> Optional.of(root.resolve((String) args[0])).filter(Files::exists);
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
