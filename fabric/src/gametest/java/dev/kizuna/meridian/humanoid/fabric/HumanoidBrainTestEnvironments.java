package dev.kizuna.meridian.humanoid.fabric;

import com.mojang.serialization.MapCodec;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;

/**
 * GameTest 批次环境类型：批次开始前换大脑学卷表，批次结束后换回启动配置。
 *
 * <p>26.2 按环境分批并在批次前后调用环境的 setup/teardown，代替 1.20.1 的批次前后钩子。
 */
public final class HumanoidBrainTestEnvironments implements ModInitializer {
    private static final String NAMESPACE = "meridian_humanoid_test";

    @Override
    public void onInitialize() {
        // 环境类型须在注册表冻结前登记；test_environment 数据按类型 ID 引用。
        for (Volumes volumes : Volumes.values()) {
            Registry.register(BuiltInRegistries.TEST_ENVIRONMENT_DEFINITION_TYPE,
                    Identifier.fromNamespaceAndPath(NAMESPACE, volumes.path), volumes.codec);
        }
    }

    private enum Volumes implements TestEnvironmentDefinition<Unit> {
        DOOR_BREAKER_ONLY("door_breaker_only"),
        VILLAGER_WITHOUT_VOLUMES("villager_without_volumes"),
        PILLAGER_WITHOUT_VOLUMES("pillager_without_volumes");

        private final String path;
        private final MapCodec<Volumes> codec;

        Volumes(String path) {
            // 环境数据不带参数，类型本身就是配置。
            this.path = path;
            this.codec = MapCodec.unit(this);
        }

        @Override
        public Unit setup(ServerLevel level) {
            // 破门旧用例只发破门卷；撤退与原版射击用例清空对应物种的学卷表。
            switch (this) {
                case DOOR_BREAKER_ONLY -> HumanoidBrainTestConfigs.doorBreakerOnly();
                case VILLAGER_WITHOUT_VOLUMES ->
                        HumanoidBrainTestConfigs.withVolumes(HumanoidSpecies.VILLAGER.entityId(), List.of());
                case PILLAGER_WITHOUT_VOLUMES ->
                        HumanoidBrainTestConfigs.withVolumes(HumanoidSpecies.PILLAGER.entityId(), List.of());
            }
            return Unit.INSTANCE;
        }

        @Override
        public void teardown(ServerLevel level, Unit saved) {
            // 后续批次用启动配置。
            HumanoidBrainTestConfigs.restore();
        }

        @Override
        public MapCodec<Volumes> codec() {
            // 每个环境类型对应一个无参数编解码器。
            return codec;
        }
    }
}
