package dev.kizuna.meridian.creature.server;

/** 生物内容的本地输入错误；跨附属协议错误仍由 Framework 负责定义。 */
final class CreatureErrors {
    static final String CONFIG = "生物配置无效";
    static final String REGION = "区域真元库存无效";
    static final String TEMPLATE = "生物必须使用 human:adult 模板";

    private CreatureErrors() {
        // 错误常量不持有运行状态。
    }

    static IllegalArgumentException invalid(String detail) {
        // 保留业务定位信息，调用方可以在配置或生成入口直接修正输入。
        return new IllegalArgumentException(detail);
    }
}
