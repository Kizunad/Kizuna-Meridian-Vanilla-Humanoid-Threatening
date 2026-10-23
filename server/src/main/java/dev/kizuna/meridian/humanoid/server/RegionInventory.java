package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.qivariants.server.FiveElements;
import dev.kizuna.meridian.qivariants.server.QiVariantRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Human 区域服务投影到类人实体生成器的只读库存和浓度。 */
public record RegionInventory(List<Entry> entries, double concentration, boolean regionPresent) {
    public RegionInventory {
        // 生成器只读取快照；复制列表后拒绝负库存和非法密度。
        entries = List.copyOf(entries);
        if (!Double.isFinite(concentration) || concentration < 0) {
            throw HumanoidErrors.invalid(HumanoidErrors.REGION + ": 浓度必须为有限非负数");
        }
        for (Entry entry : entries) {
            if (entry == null) {
                throw HumanoidErrors.invalid(HumanoidErrors.REGION + ": 库存明细不能为空");
            }
        }
    }

    public static RegionInventory present(double concentration, List<Entry> entries) {
        // 有区域时即使库存为空也保留“区域存在”事实，真炁概率仍按浓度函数计算。
        return new RegionInventory(entries, concentration, true);
    }

    public static RegionInventory absent() {
        // 没有区域来源的维度使用配置中的默认概率因子。
        return new RegionInventory(List.of(), 0, false);
    }

    public static RegionInventory fromShares(double concentration, Map<String, Double> shares) {
        // 测试和纯 Java 宿主可以直接提供属性数量；密度字段使用游离态作为相态占位。
        if (shares == null) {
            throw HumanoidErrors.invalid(HumanoidErrors.REGION + ": shares 不能为空");
        }
        var entries = new ArrayList<Entry>();
        shares.forEach((attribute, amount) -> entries.add(new Entry(attribute, QiDensity.FREE, amount)));
        return present(concentration, entries);
    }

    public Map<String, Double> eligibleShares(QiVariantRegistry registry) {
        // 先按属性合并所有相态数量，再丢弃无属性和未登记属性，交给注册表做阶数筛选。
        if (registry == null) {
            throw HumanoidErrors.invalid(HumanoidErrors.REGION + ": 变体注册表不能为空");
        }
        Set<String> allowed = new HashSet<>(FiveElements.IDS);
        registry.catalog().getAttributesList().forEach(attribute -> allowed.add(attribute.getId()));
        var totals = new TreeMap<String, Double>();
        for (Entry entry : entries) {
            if (entry.attribute().equals("meridian:none") || !allowed.contains(entry.attribute())) {
                continue;
            }
            totals.merge(entry.attribute(), entry.amount(), Double::sum);
        }
        totals.values().removeIf(value -> value == 0);
        return Collections.unmodifiableMap(totals);
    }

    /** 区域库存中的一条属性/相态明细。 */
    public record Entry(String attribute, double density, double amount) {
        public Entry {
            // 同一属性可以有多个相态；合并发生在 eligibleShares，不改变输入明细。
            new DefinitionId(attribute);
            QiDensity.phase(density);
            if (!Double.isFinite(amount) || amount < 0) {
                throw HumanoidErrors.invalid(HumanoidErrors.REGION + ": 数量必须为有限非负数");
            }
        }
    }
}
