package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.protocol.pb.AddonMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import dev.kizuna.meridian.qivariants.server.QiVariantRegistry;
import dev.kizuna.meridian.qivariants.server.QiVariantsContent;
import dev.kizuna.meridian.server.AddonRegistry;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 唤魔者按主属性保证本行表里经（C6c SPEC 第三节）；其余出生和改动前逐字节一致。 */
public class HumanoidEvokerOpeningTest {
    // 基准是加唤魔者补开之前，用本类同一套用例写出的（C6c 第 2 段）：一行一个用例，键、快照摘要、开脉位图。
    // 以后有意改出生规则，要用改动前的生成器重写这份基准。
    private static final String FIXTURE = "evoker-opening-births.txt";
    private static final long WORLD_SEED = 987654321L;
    private static final int RULE_SEEDS = 200;
    private static final int FIXTURE_SEEDS = 6;
    private static final int DIGEST_BYTES = 8;
    private static final int HEX = 16;
    private static final String NONE = "meridian:none";
    private static final List<String> REGIONS = List.of("meridian:metal", "meridian:earth", "meridian:fire",
            "meridian:water", "meridian:wood", NONE);
    private static final Map<Birth, List<String>> BIASED = Map.of(
            Birth.WANDERING, List.of("meridian:metal", "meridian:earth"),
            Birth.TRUE, List.of("meridian:fire", "meridian:water"));
    private HumanoidBodyGenerator generator;

    private enum Birth { WANDERING, TRUE, MORTAL }

    private record Case(HumanoidSpecies species, Birth birth, String region, int seed) {
        String key() {
            // 基准文件按这个键逐行对应。
            return species.name() + " " + birth + " " + region + " " + seed;
        }
    }

    @Before
    public void setUp() {
        // 生成器只读 Qi-Variants 目录抽主属性；模板直接取 Human 内容。
        AddonMessages.AddonRegistration registration = AddonMessages.AddonRegistration.newBuilder()
                .setProtocolVersion(1)
                .setHost(AddonMessages.AddonHostProfile.newBuilder()
                        .setHostId("meridian:java_reference")
                        .setApiVersion(1))
                .addBundles(QiVariantsContent.bundle())
                .build();
        QiVariantRegistry variants = QiVariantRegistry.load(AddonRegistry.load(registration),
                QiVariantsContent.catalog());
        generator = new HumanoidBodyGenerator(variants, HumanoidConfigLoader.defaults());
    }

    @Test
    public void cultivatorEvokersOpenAnElementMeridianOfTheirMainAttribute() {
        // 每种保证档、每个种子都至少开着一条本行表里经，补开那条走 Framework 快照校验。
        for (var entry : BIASED.entrySet()) {
            for (String element : entry.getValue()) {
                String tag = elementTag(element);
                for (int seed = 0; seed < RULE_SEEDS; seed++) {
                    BodyMessages.BodySnapshot body = generate(new Case(HumanoidSpecies.EVOKER, entry.getKey(),
                            element, seed));
                    assertTrue(entry.getKey() + " " + element + " 种子 " + seed + " 没开本行表里经",
                            openedWithTag(body, tag) > 0);
                }
            }
        }
    }

    @Test
    public void otherBirthsMatchThePreChangeSnapshotsByteForByte() throws IOException {
        // 开脉不变的出生快照和改动前逐字节一致；变了的只能是缺本行经的唤魔者，补开的正是该标签的第一条。
        List<Case> cases = fixtureCases();
        List<String> fixture = fixture();
        assertEquals(cases.size(), fixture.size());
        int filled = 0;
        for (int index = 0; index < cases.size(); index++) {
            Case value = cases.get(index);
            String[] expected = fixture.get(index).split("\t");
            assertEquals(value.key(), expected[0]);
            BodyMessages.BodySnapshot body = generate(value);
            long before = Long.parseUnsignedLong(expected[2], HEX);
            long after = openedMask(body);
            if (after == before) {
                assertEquals(value.key() + " 出生快照变了", expected[1], digest(body));
            } else {
                assertOnlyEvokerElementAdded(value, body, before, after);
                filled++;
            }
        }
        assertTrue("基准里没有补开的出生，测不到补开分支", filled > 0);
    }

    private static void assertOnlyEvokerElementAdded(Case value, BodyMessages.BodySnapshot body, long before,
                                                     long after) {
        // 只增不减、只多一条；这一档这一行该补，改动前一条本行经都没开，补的是内容定义里该标签的第一条。
        String tag = biasedTag(value).orElseThrow(() -> new AssertionError(value.key() + " 不该补开却变了开脉"));
        long added = after & ~before;
        assertEquals(value.key() + " 关掉了原来开着的经", 0, before & ~after);
        assertEquals(value.key() + " 补开不止一条", 1, Long.bitCount(added));
        assertEquals(value.key() + " 改动前已有本行经", 0, before & tagMask(body, tag));
        int first = Long.numberOfTrailingZeros(tagMask(body, tag));
        assertEquals(value.key() + " 补开的不是该标签第一条", 1L << first, added);
    }

    private static Optional<String> biasedTag(Case value) {
        // 规格第三节的表：唤魔者游炁金、土，真炁火、水。
        boolean biased = value.species() == HumanoidSpecies.EVOKER
                && BIASED.getOrDefault(value.birth(), List.of()).contains(value.region());
        return biased ? Optional.of(elementTag(value.region())) : Optional.empty();
    }

    private static String elementTag(String element) {
        // 五行 ID 去掉命名空间就是 Human 经脉标签的后缀。
        return "human:meridian_" + element.substring(element.indexOf(':') + 1);
    }

    private static long tagMask(BodyMessages.BodySnapshot body, String tag) {
        // 结构里带该标签的经，按模板顺序记位。
        long mask = 0;
        List<BodyMessages.MeridianDefinition> meridians = body.getStructure().getMeridiansList();
        for (int index = 0; index < meridians.size(); index++) {
            if (meridians.get(index).getTagsList().contains(tag)) {
                mask |= 1L << index;
            }
        }
        return mask;
    }

    private static int openedWithTag(BodyMessages.BodySnapshot body, String tag) {
        // 开着且带该标签的经数。
        return Long.bitCount(openedMask(body) & tagMask(body, tag));
    }

    private static List<String> fixture() throws IOException {
        // 改动前用同一套用例写出的基准：键、快照摘要、开脉位图。
        var stream = Objects.requireNonNull(HumanoidEvokerOpeningTest.class.getResourceAsStream(FIXTURE), FIXTURE);
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        }
    }

    private List<Case> fixtureCases() {
        // 七种生人 × 六种单属性区域 × 若干种子 × 游炁、真炁、凡人三种出生。
        var cases = new ArrayList<Case>();
        for (HumanoidSpecies species : HumanoidSpecies.values()) {
            for (String region : REGIONS) {
                for (int seed = 0; seed < FIXTURE_SEEDS; seed++) {
                    for (Birth birth : Birth.values()) {
                        cases.add(new Case(species, birth, region, seed));
                    }
                }
            }
        }
        return cases;
    }

    private BodyMessages.BodySnapshot generate(Case value) {
        // 单属性区域让主属性固定是这一行；凡人走不开脉入口，种子与另外两种出生相同。
        UUID id = UUID.nameUUIDFromBytes((value.species().entityId() + "/" + value.region() + "/" + value.seed())
                .getBytes(StandardCharsets.UTF_8));
        RegionInventory region = RegionInventory.fromShares(1, Map.of(value.region(), 1.0));
        HumanoidVariant variant = value.birth() == Birth.TRUE ? HumanoidVariant.TRUE_QI : HumanoidVariant.WANDERING_QI;
        var request = new HumanoidBodyGenerator.GenerationRequest(value.species(), variant, id, region, null);
        if (value.birth() == Birth.MORTAL) {
            return generator.generateUnopened(request, HumanoidBodyGenerator.randomFor(WORLD_SEED, id));
        }
        return generator.generate(request, WORLD_SEED);
    }

    private static long openedMask(BodyMessages.BodySnapshot body) {
        // 按模板经脉顺序记开着的经，36 条放得进一个 long。
        long mask = 0;
        List<BodyMessages.MeridianState> states = body.getMeridianStatesList();
        for (int index = 0; index < states.size(); index++) {
            if (states.get(index).getOpening().getStage() == MeridianOpeningStage.OPENED) {
                mask |= 1L << index;
            }
        }
        return mask;
    }

    private static String digest(BodyMessages.BodySnapshot body) {
        // 快照字节的 SHA-256 取前 8 字节，够分辨改动前后是否逐字节一致。
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray());
            return HexFormat.of().formatHex(hash, 0, DIGEST_BYTES);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
