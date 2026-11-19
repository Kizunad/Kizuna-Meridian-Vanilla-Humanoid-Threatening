package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TimeCursor;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageResolution;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** 空的办法分别沿用人体生成器和物种表里的死亡规则。 */
public record SpeciesBodyRules(BirthFactory birth, DeathVerdict death) {
    public static final SpeciesBodyRules DEFAULT = new SpeciesBodyRules(null, null);

    public interface Region {
        RegionInventory inventory();
        String sourceId();
    }

    public record Birth(UUID entityId, Region region, TimeCursor now, HumanoidTier tier,
                        RandomGenerator random) {
        public String bodyId() {
            return "meridian_humanoid:entity/" + entityId;
        }
    }

    @FunctionalInterface
    public interface BirthFactory {
        BodySnapshot create(Birth request);
    }

    @FunctionalInterface
    public interface DeathVerdict {
        boolean lethal(BodySnapshot wounded, DamageResolution human);
    }
}
