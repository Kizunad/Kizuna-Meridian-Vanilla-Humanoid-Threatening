package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TimeCursor;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageResolution;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** 空的办法分别沿用人体生成器和物种表里的死亡规则。 */
public record SpeciesBodyRules(BirthFactory birth, DeathVerdict death, SpawnRoll spawnRoll) {
    public static final SpeciesBodyRules DEFAULT = new SpeciesBodyRules(null, null);

    public SpeciesBodyRules(BirthFactory birth, DeathVerdict death) {
        this(birth, death, null);
    }

    public interface Region {
        RegionInventory inventory();
        String sourceId();
    }

    /** 原版原因用枚举名，未经历生成初始化的旧实体用 UNKNOWN。当地难度取生成时的有效难度。 */
    public record Spawn(String entityType, String reason, double difficulty, Optional<HumanoidTier> requested) {
        public Spawn {
            if (!Double.isFinite(difficulty) || difficulty < 0) {
                throw HumanoidErrors.invalid("出生当地难度须为非负有限数");
            }
        }
    }

    @FunctionalInterface
    public interface SpawnRoll {
        HumanoidTier select(Spawn spawn, RandomGenerator random);
    }

    public record Identity(UUID entityId, Spawn spawn) { }

    public record Birth(Identity identity, Region region, TimeCursor now, HumanoidTier tier,
                        RandomGenerator random) {
        public UUID entityId() {
            return identity.entityId();
        }

        public String bodyId() {
            return "meridian_humanoid:entity/" + entityId();
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
