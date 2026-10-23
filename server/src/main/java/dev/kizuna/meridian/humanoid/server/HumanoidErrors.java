package dev.kizuna.meridian.humanoid.server;

/** 类人实体内容的本地输入错误；跨附属协议错误仍由 Framework 负责定义。 */
final class HumanoidErrors {
    static final String CONFIG = "类人实体配置无效";
    static final String REGION = "区域真元库存无效";
    static final String TEMPLATE = "类人实体必须使用 human:adult 模板";

    private HumanoidErrors() {
        // 错误常量不持有运行状态。
    }

    static IllegalArgumentException invalid(String detail) {
        // 保留业务定位信息，调用方可以在配置或生成入口直接修正输入。
        return new IllegalArgumentException(detail);
    }
}
