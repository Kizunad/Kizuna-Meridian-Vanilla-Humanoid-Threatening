package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.humanoid.server.RegionInventory;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BindingOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.QiCost;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueBinder;
import dev.kizuna.meridian.technique.server.TechniqueState;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.illager.Evoker;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static dev.kizuna.meridian.humanoid.fabric.HumanoidGameTestTicks.DEFAULT_TIMEOUT;

/** 唤魔者出生：真炁档会《附》、付得起本行附伤式，游炁金、土付得起本行强身式（C6c SPEC 2.2、2.5、第三节）。 */
public final class HumanoidEvokerBirthGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid_evoker");
    private static final String PREFIX = "meridian_evoker:";
    private static final long WORLD_SEED = 987654321L;
    private static final int SEEDS = 8;
    private static final int ORIGIN = 1;
    private static final List<Element> TRUE_QI = List.of(
            new Element("meridian:metal", "metal_edge"),
            new Element("meridian:earth", "earth_quake"),
            new Element("meridian:fire", "fire_burn"),
            new Element("meridian:water", "water_frost"));
    private static final List<Element> WANDERING_QI = List.of(
            new Element("meridian:metal", "metal_arms"),
            new Element("meridian:earth", "earth_legs"));
    private static final Set<String> INFUSIONS = Set.of(PREFIX + "metal_edge", PREFIX + "earth_quake",
            PREFIX + "fire_burn", PREFIX + "water_frost", PREFIX + "wood_thunder");

    /** 主属性和这一行要核对的式。 */
    private record Element(String attribute, String technique) { }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void trueQiEvokersLearnInfuseAndCanPayTheirElementInfusion(GameTestHelper context) {
        // 金、土、火、水各若干只：学到本行附伤式，绑得上、付得起也做得了；日志记本行真元够放几次（SPEC 2.5 实测）。
        for (Element element : TRUE_QI) {
            assertPayable(context, HumanoidTier.TRUE_QI, element);
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void wanderingMetalAndEarthEvokersCanPayTheirStrengthening(GameTestHelper context) {
        // 游炁金、土靠保证开着的本行经放金臂、厚土；游炁档不学《附》。
        for (Element element : WANDERING_QI) {
            assertPayable(context, HumanoidTier.WANDERING_QI, element);
        }
        context.succeed();
    }

    private static void assertPayable(GameTestHelper context, HumanoidTier tier, Element element) {
        // 每只走正式出生入口和学卷，再用大脑选式的同一预检判断；查完就移走，不留到别的用例。
        List<Double> casts = new ArrayList<>();
        for (int seed = 0; seed < SEEDS; seed++) {
            String where = tier + " " + element.attribute() + " 种子 " + seed;
            Evoker mob = born(context, tier, element, seed);
            BodyMessages.BodySnapshot snapshot = HumanoidTechniqueHost.prepare(mob).body().snapshot();
            BodySnapshot body = BodyProtobuf.fromMessage(snapshot);
            List<TechniqueReference> known = TechniqueState.read(body).getKnownList().stream()
                    .map(technique -> technique.getTechnique())
                    .toList();
            boolean infuse = known.stream().anyMatch(reference -> INFUSIONS.contains(reference.getId()));
            context.assertTrue(infuse == (tier == HumanoidTier.TRUE_QI), where + " 会不会《附》不对：" + known);
            TechniqueDefinition definition = definition(known, element)
                    .orElseThrow(() -> new AssertionError(where + " 没学到 " + element.technique()));
            BindingOutcome binding = TechniqueBinder.bind(body, definition, Hand.HAND_UNSPECIFIED,
                    HumanoidWayfarerWeapons.inspect(mob).getMainHandSide());
            context.assertTrue(binding.hasBinding(), where + " 绑不上：" + binding.getReason());
            context.assertTrue(HumanoidRepertoires.payable(body, definition, binding.getBinding()),
                    where + " 付不起 " + element.technique());
            casts.add(castsAffordable(snapshot, definition));
            mob.discard();
        }
        LOGGER.info("evoker birth tier={} element={} technique={} casts={}", tier, element.attribute(),
                element.technique(), casts.stream().sorted().map(value -> String.format("%.1f", value)).toList());
    }

    private static Evoker born(GameTestHelper context, HumanoidTier tier, Element element, int seed) {
        // 单属性区域让主属性固定是这一行；身体走自然出生同一入口，只指定档位。
        Evoker mob = EntityTypes.EVOKER.create(context.getLevel(), EntitySpawnReason.COMMAND);
        UUID id = UUID.nameUUIDFromBytes(("c6c-evoker/" + tier + "/" + element.attribute() + "/" + seed)
                .getBytes(StandardCharsets.UTF_8));
        mob.setUUID(id);
        Vec3 point = Vec3.atBottomCenterOf(context.absolutePos(new BlockPos(ORIGIN, ORIGIN, ORIGIN)));
        mob.snapTo(point.x, point.y, point.z, 0, 0);
        mob.setNoAi(true /* aiDisabled */);
        mob.setNoGravity(true /* noGravity */);
        context.getLevel().addFreshEntity(mob);
        var inventory = RegionInventory.fromShares(1, Map.of(element.attribute(), 1.0));
        var region = new HumanoidEnvironmentClient.RegionSnapshot(inventory, HumanoidFabric.region(mob).sourceId());
        var subject = new HumanoidBodyState.BirthSubject(HumanoidSpecies.EVOKER, id, tier);
        var body = HumanoidBodyState.born(subject, WORLD_SEED, region, HumanoidFabric.now());
        ((HumanoidEntityAccess) mob).meridian$humanoidBody(body);
        return mob;
    }

    private static Optional<TechniqueDefinition> definition(List<TechniqueReference> known, Element element) {
        // 只查真实学到的精确版本，不在测试里重造目录。
        return known.stream()
                .filter(reference -> reference.getId().equals(PREFIX + element.technique()))
                .findFirst()
                .flatMap(reference -> TechniqueFabric.service().registry().find(reference));
    }

    private static double castsAffordable(BodyMessages.BodySnapshot snapshot, TechniqueDefinition definition) {
        // 本行费用只从槽位标签那几条开着的经内腔里付，同属性且够相态；合计除以单次费用就是够放几次。
        QiCost cost = definition.getCost().getQi(0);
        String tag = definition.getSlotsList().stream()
                .filter(slot -> slot.getId().equals(cost.getSlotId()))
                .findFirst().orElseThrow()
                .getTags(0);
        Set<String> channels = snapshot.getStructure().getMeridiansList().stream()
                .filter(meridian -> meridian.getTagsList().contains(tag))
                .map(BodyMessages.MeridianDefinition::getId)
                .collect(Collectors.toSet());
        double total = snapshot.getMeridianStatesList().stream()
                .filter(state -> channels.contains(state.getId())
                        && state.getOpening().getStage() == MeridianOpeningStage.OPENED)
                .flatMap(state -> state.getLumenQi().getEntriesList().stream())
                .filter(qi -> qi.getAttribute().equals(cost.getAttribute())
                        && QiDensity.phase(qi.getDensity()).getNumber() >= cost.getPhase().getNumber())
                .mapToDouble(BodyMessages.Qi::getAmount)
                .sum();
        return total / cost.getAmount().getValue();
    }
}
