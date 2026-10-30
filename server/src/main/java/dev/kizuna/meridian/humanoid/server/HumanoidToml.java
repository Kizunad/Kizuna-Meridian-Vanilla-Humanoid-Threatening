package dev.kizuna.meridian.humanoid.server;

import org.tomlj.Toml;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** 类人各份配置共用的 TOML 读取：默认资源导出、章节与数值取值、字段白名单；错误前缀指明是哪份配置。 */
final class HumanoidToml {
    private final String error;

    HumanoidToml(String error) {
        // 错误前缀在构造时固定，同一份配置的报错文本一致。
        this.error = error;
    }

    String resource(String name) {
        // JAR 资源是默认值唯一来源。
        try (InputStream input = HumanoidToml.class.getResourceAsStream(name)) {
            if (input == null) {
                throw invalid("缺少默认资源");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw invalid("无法读取默认资源");
        }
    }

    String readOrExport(Path path, String resource) {
        // 首次加载导出默认文件；已有文件只读取，不覆盖运营方调整。
        if (path == null) {
            throw invalid("配置路径不能为空");
        }
        try {
            if (!Files.exists(path)) {
                Path parent = path.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(path, resource(resource), StandardCharsets.UTF_8);
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw invalid("无法读取配置文件");
        }
    }

    TomlTable parse(String text) {
        // 空文本和语法错误都在取字段前拒绝。
        if (text == null || text.isBlank()) {
            throw invalid("配置文本不能为空");
        }
        var parsed = Toml.parse(text);
        if (parsed.hasErrors()) {
            throw invalid("TOML 解析失败");
        }
        return parsed;
    }

    TomlTable table(TomlTable parent, String key, String path) {
        // 缺少章节和标量冒充章节都视为配置错误，避免产生半套规则。
        Object value = parent.get(List.of(key));
        if (!(value instanceof TomlTable result)) {
            throw invalid("缺少或错误的表 " + path);
        }
        return result;
    }

    int integer(TomlTable table, String key, String path) {
        // 只接受完整正整数，不通过 double 截断。
        Object value = table.get(List.of(key));
        if (!(value instanceof Long integer) || integer <= 0 || integer > Integer.MAX_VALUE) {
            throw invalid(path + " 必须是正整数");
        }
        return integer.intValue();
    }

    double number(TomlTable table, String key, String path) {
        // TOML 整数和小数统一为有限 double，范围由调用方继续校验。
        Object value = table.get(List.of(key));
        if (value instanceof Number number) {
            double numericValue = number.doubleValue();
            if (Double.isFinite(numericValue)) {
                return numericValue;
            }
        }
        throw invalid(path + " 必须是有限数值");
    }

    void checkKeys(TomlTable table, Set<String> allowed, String prefix) {
        // 每层独立检查，错误路径直接包含章节名，便于定位运维配置拼写错误。
        for (String key : table.keySet()) {
            if (!allowed.contains(key)) {
                throw invalid("未知字段 " + prefix + key);
            }
        }
    }

    IllegalArgumentException invalid(String detail) {
        // 统一加上配置名前缀。
        return HumanoidErrors.invalid(error + ": " + detail);
    }
}
