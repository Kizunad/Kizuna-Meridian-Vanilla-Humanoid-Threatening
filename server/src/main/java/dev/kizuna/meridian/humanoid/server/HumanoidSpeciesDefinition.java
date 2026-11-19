package dev.kizuna.meridian.humanoid.server;

import java.util.Objects;

/**
 * 登记物种的数据定义：实体 ID、身体模板、命令与放置的默认档位、出生画像，以及死亡规则和大脑开关。
 * 登记整张表时核对模板所需的出生办法。
 */
public record HumanoidSpeciesDefinition(String entityId, String template, HumanoidTier defaultTier,
                                        HumanoidConfig.SpeciesProfile profile, Control control) {
    /** 类人大脑是否接管该物种；不接管时既不决策也不占用原版控制。 */
    public enum Brain { MANAGED, UNMANAGED }

    /** 身体死亡规则与大脑开关。 */
    public record Control(HumanoidDeathRule deathRule, Brain brain) {
        public Control {
            // 两项都必须明确给出，缺省值由物种表读取器填入。
            Objects.requireNonNull(deathRule, "deathRule");
            Objects.requireNonNull(brain, "brain");
        }
    }

    public HumanoidSpeciesDefinition {
        // 实体 ID 必须带命名空间；模板受生成器限制；其余字段不能为空。
        if (entityId == null || !namespaced(entityId)) {
            throw HumanoidErrors.invalid(HumanoidErrors.SPECIES + ": 实体 ID 必须是命名空间 ID");
        }
        if (template == null || !namespaced(template)) {
            throw HumanoidErrors.invalid(HumanoidErrors.SPECIES + ": 模板必须是命名空间 ID");
        }
        Objects.requireNonNull(defaultTier, "defaultTier");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(control, "control");
    }

    public HumanoidDeathRule deathRule() {
        // 便于宿主直接读取死亡规则。
        return control.deathRule();
    }

    private static boolean namespaced(String id) {
        // 冒号前后都不能为空。
        int separator = id.indexOf(':');
        return separator > 0 && separator < id.length() - 1;
    }
}
