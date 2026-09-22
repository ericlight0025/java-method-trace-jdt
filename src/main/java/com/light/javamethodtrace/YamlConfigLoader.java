package com.light.javamethodtrace;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * 讀取 Java Method Trace 的 YAML 設定檔。
 */
public final class YamlConfigLoader {

    private static final Set<String> SUPPORTED_KEYS = Set.of(
            "project",
            "class",
            "method",
            "depth",
            "direction",
            "output",
            "encoding");

    private YamlConfigLoader() {
        // 工具類別不需要建立物件。
    }

    /**
     * 讀取設定檔中的純量設定值。
     *
     * @param configPath YAML 設定檔路徑
     * @return 設定名稱與文字值
     * @throws IOException 讀取設定檔失敗時拋出
     */
    public static Map<String, String> load(Path configPath) throws IOException {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object document;
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            try {
                document = yaml.load(reader);
            } catch (YAMLException exception) {
                throw new IllegalArgumentException(
                        "YAML 格式錯誤：" + configPath + "，原因：" + exception.getMessage(),
                        exception);
            }
        }

        if (document == null) {
            return Collections.emptyMap();
        }
        if (!(document instanceof Map)) {
            throw new IllegalArgumentException("YAML 根節點必須是設定物件：" + configPath);
        }

        Map<String, String> result = new LinkedHashMap<String, String>();
        Map<?, ?> values = (Map<?, ?>) document;
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException("YAML 設定名稱必須是文字：" + configPath);
            }

            String key = (String) entry.getKey();
            if (!SUPPORTED_KEYS.contains(key)) {
                throw new IllegalArgumentException("不支援的 YAML 設定：" + key);
            }
            Object value = entry.getValue();
            if (!(value instanceof String)
                    && !(value instanceof Number)
                    && !(value instanceof Boolean)) {
                throw new IllegalArgumentException(
                        "YAML 設定值必須是文字、數字或布林值：" + key);
            }
            String textValue = String.valueOf(value).trim();
            if (textValue.isEmpty()) {
                throw new IllegalArgumentException("YAML 設定值不可為空：" + key);
            }
            result.put(key, textValue);
        }
        return result;
    }
}
