package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.protocol.AddonProtobuf;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 大脑读的真元占容与疲劳比使用真实 Framework 身体契约。 */
public final class HumanoidFightersTest {
    private static final double HALF_FULL = 0.5;
    private static final double FREE_DENSITY = 0.01;
    private static final double GAS_DENSITY = 0.2;
    private static final double EPSILON = 0.000001;

    @Test
    public void qiRatioUsesActualCapacityAndInventoryDensity() {
        // 相同装填率在不同密度下相同，库存超过一元也不能误判为满仓。
        for (double density : new double[]{FREE_DENSITY, GAS_DENSITY}) {
            assertEquals(0, HumanoidFighters.qiRatio(body(0, density)), EPSILON);
            BodySnapshot half = body(HALF_FULL, density);
            assertEquals(HALF_FULL, HumanoidFighters.qiRatio(half), EPSILON);
            assertEquals(1, HumanoidFighters.qiRatio(body(1, density)), EPSILON);
        }
        BodySnapshot gas = body(HALF_FULL, GAS_DENSITY);
        double amount = gas.meridianStates().stream().mapToDouble(state -> state.lumenQi().totalQi()).sum();
        assertTrue(amount > 1);
    }

    @Test
    public void qiRatioIncludesEmptyMeridiansAndExcludesWallInventory() {
        // 空经脉仍占容量分母；不可支付的外壁库存不抬高就绪程度。
        var empty = BodyProtobuf.toMessage(body(0, GAS_DENSITY));
        var filled = BodyProtobuf.toMessage(body(1, GAS_DENSITY));
        var snapshot = empty.toBuilder();
        for (int index = 0; index < empty.getMeridianStatesCount(); index++) {
            var state = empty.getMeridianStates(index).toBuilder();
            if (index == 0) {
                state.setLumenQi(filled.getMeridianStates(index).getLumenQi());
            }
            double wallVolume = empty.getStructure().getMeridians(index).getWallVolume();
            state.setWallQi(inventory(wallVolume, GAS_DENSITY));
            snapshot.setMeridianStates(index, state);
        }
        BodySnapshot body = BodyProtobuf.fromMessage(snapshot.build());
        double expected = body.structure().meridians().get(0).lumenVolume() / body.structure().lumenVolume();
        assertEquals(expected, HumanoidFighters.qiRatio(body), EPSILON);
    }

    @Test
    public void fatigueRatioWithoutComparableMusclesIsZero() {
        // 没有绑定组织槽的式不受疲劳门槛约束。
        assertEquals(0, HumanoidFighters.fatigueRatio(body(1, GAS_DENSITY), Set.of()), EPSILON);
    }

    private static BodySnapshot body(double fill, double density) {
        // 正式人体模板与出生候选提供完整合法构型，再按各经脉实际容量装填。
        var template = AddonProtobuf.decodeBundle(HumanContent.payload()).getTemplates(0);
        var time = BodyMessages.TimeCursor.newBuilder()
                .setDomain("test:world")
                .setEpoch("qi-ratio")
                .setElapsedMillis(0)
                .build();
        var initial = BodyProtobuf.decodeSnapshot(HumanBirth.candidate(template, "test-humanoid", time));
        var snapshot = BodyProtobuf.toMessage(initial).toBuilder();
        for (int index = 0; index < snapshot.getMeridianStatesCount(); index++) {
            double capacity = template.getStructure().getMeridians(index).getLumenVolume();
            var state = snapshot.getMeridianStates(index).toBuilder()
                    .setLumenQi(inventory(capacity * fill, density))
                    .build();
            snapshot.setMeridianStates(index, state);
        }
        return BodyProtobuf.fromMessage(snapshot.build());
    }

    private static BodyMessages.QiInventory inventory(double volume, double density) {
        // 零占容使用空账户，非空库存保留属性和密度以覆盖框架的实际换算。
        if (volume == 0) {
            return BodyMessages.QiInventory.getDefaultInstance();
        }
        var qi = BodyMessages.Qi.newBuilder()
                .setAttribute("meridian:none")
                .setAmount(volume * density)
                .setDensity(density)
                .build();
        return BodyMessages.QiInventory.newBuilder().addEntries(qi).build();
    }
}
