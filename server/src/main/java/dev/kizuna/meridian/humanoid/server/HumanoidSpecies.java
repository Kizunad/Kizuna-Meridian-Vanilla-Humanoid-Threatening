package dev.kizuna.meridian.humanoid.server;

import java.util.Arrays;
import java.util.Optional;

/** 本批次允许认领的七种原版生人实体。 */
public enum HumanoidSpecies {
    VILLAGER("minecraft:villager"),
    WANDERING_TRADER("minecraft:wandering_trader"),
    PILLAGER("minecraft:pillager"),
    VINDICATOR("minecraft:vindicator"),
    EVOKER("minecraft:evoker"),
    WITCH("minecraft:witch"),
    ILLUSIONER("minecraft:illusioner");

    private final String entityId;

    HumanoidSpecies(String entityId) {
        // 实体 ID 是宿主认领边界的稳定字符串，不使用类名推导。
        this.entityId = entityId;
    }

    public String entityId() {
        // 对外返回固定 ID，供 Fabric 宿主建立白名单映射。
        return entityId;
    }

    public static Optional<HumanoidSpecies> fromEntityId(String entityId) {
        // 未知或空 ID 保持未绑定语义，不能猜测相近的实体。
        if (entityId == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(species -> species.entityId.equals(entityId)).findFirst();
    }
}
