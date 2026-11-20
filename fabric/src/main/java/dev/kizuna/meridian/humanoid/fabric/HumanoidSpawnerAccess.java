package dev.kizuna.meridian.humanoid.fabric;

/** 分档蛋在原版刷怪笼设好实体类型后写入出生档位。 */
public interface HumanoidSpawnerAccess {
    void meridian$setBirthTier(HumanoidSpawns.Selection selection);
}
