package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.Vec3;

/** 按记分板标签定档：带 meridian_humanoid.tier.<档> 的生人自然出生时就是这一档，别的抽样和指定档位出生一样。 */
public final class HumanoidTierTagGameTest {
    private static final int TIMEOUT = 100;
    private static final BlockPos TAGGED_POS = new BlockPos(2, 2, 2);
    private static final int VILLAGERS_PER_TIER = 8;

    @GameTest(maxTicks = TIMEOUT)
    public void taggedVillagersAreBornAtTheTaggedTier(GameTestHelper context) {
        // 每档各放几个带标签的村民，不指定档位，按需出生后都是标签那一档；自然抽档从不出凡人，凡人档只可能来自标签。
        // 同一个 UUID 另建一个不带标签、直接指定档位出生的村民：两具身体一模一样，说明标签只换了档位，没动别的抽样。
        for (HumanoidTier tier : HumanoidTier.values()) {
            for (int index = 0; index < VILLAGERS_PER_TIER; index++) {
                UUID id = UUID.nameUUIDFromBytes(("tier-tag/" + tier.id() + "/" + index)
                        .getBytes(StandardCharsets.UTF_8));
                Villager tagged = villager(context, id, TAGGED_POS);
                tagged.addTag(HumanoidTier.TAG_PREFIX + tier.id());
                Villager forced = villager(context, id, TAGGED_POS);
                var regionBefore = HumanoidFabric.region(tagged);
                var taggedBody = HumanoidHealthHost.bodyState(tagged, HumanoidSpecies.VILLAGER, HumanoidFabric.now());
                HumanoidSpeciesBirths.birth(forced, tier);
                var regionAfter = HumanoidFabric.region(forced);
                var forcedBody = ((HumanoidEntityAccess) forced).meridian$humanoidBody();
                context.assertTrue(taggedBody.snapshot().equals(forcedBody.snapshot()),
                        "带标签出生和指定档位出生的身体不一样：" + tier.id() + " #" + index
                                + difference(taggedBody.snapshot(), forcedBody.snapshot())
                                + "；两次出生之间区域库存变没变：" + !regionBefore.inventory().equals(regionAfter.inventory()));
                // 查档位走正式的准备流程（要学卷），实体得在世界里。
                context.assertTrue(context.getLevel().addFreshEntity(tagged), "村民未加入世界");
                context.assertTrue(HumanoidTiers.of(tagged).equals(Optional.of(tier)),
                        "带 " + tier.id() + " 标签的村民出生成了 " + HumanoidTiers.of(tagged));
                tagged.discard();
                forced.discard();
            }
        }
        context.succeed();
    }

    private static String difference(BodyMessages.BodySnapshot left, BodyMessages.BodySnapshot right) {
        // 这条用例在全量里偶发过一次、原因没抓到；再出现时报出是身体的哪些字段不同。
        List<String> fields = left.getAllFields().keySet().stream()
                .filter(field -> !left.getField(field).equals(right.getField(field)))
                .map(field -> field.getName())
                .toList();
        return "；不同的字段 " + fields;
    }

    private static Villager villager(GameTestHelper context, UUID id, BlockPos relative) {
        // 先只建实体：身体按 UUID 和世界种子出生，在不在世界里不影响；要查档位的那个随后再加入世界。
        Villager villager = EntityTypes.VILLAGER.create(context.getLevel(), EntitySpawnReason.COMMAND);
        villager.setNoAi(true /* aiDisabled */);
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(relative));
        villager.setUUID(id);
        villager.snapTo(position.x, position.y, position.z, 0, 0);
        return villager;
    }
}
