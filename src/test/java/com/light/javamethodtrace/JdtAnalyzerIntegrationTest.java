package com.light.javamethodtrace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 驗證 JDT 分析器在實際多檔案專案中的主要流程。
 */
class JdtAnalyzerIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    /**
     * 驗證自訂 Source Root 的 A -> B -> C 呼叫能正確追蹤，並可安全輸出 Markdown。
     *
     * @throws IOException 建立測試專案或讀取輸出失敗時拋出
     */
    @Test
    void shouldTraceMethodsUnderCustomSourceRootAndWriteSafeMarkdown() throws IOException {
        Path projectPath = temporaryDirectory.resolve("custom-project");
        Path sourceRoot = projectPath.resolve("app-source/com/demo");
        writeJava(sourceRoot.resolve("AService.java"),
                "package com.demo;\n"
                        + "public class AService {\n"
                        + "    private final BService bService = new BService();\n"
                        + "    public String execute() {\n"
                        + "        return bService.process();\n"
                        + "    }\n"
                        + "}\n");
        writeJava(sourceRoot.resolve("BService.java"),
                "package com.demo;\n"
                        + "public class BService {\n"
                        + "    private final CService cService = new CService();\n"
                        + "    public String process() {\n"
                        + "        return cService.finish();\n"
                        + "    }\n"
                        + "}\n");
        writeJava(sourceRoot.resolve("CService.java"),
                "package com.demo;\n"
                        + "public class CService {\n"
                        + "    public String finish() {\n"
                        + "        return \"```\";\n"
                        + "    }\n"
                        + "}\n");

        JdtAnalyzer analyzer = new JdtAnalyzer(projectPath, StandardCharsets.UTF_8);
        JavaSourceIndex index = analyzer.buildIndex();
        List<MethodNode> roots = index.findCandidates("AService", "execute");

        assertEquals(1, roots.size());
        MethodNode.TraceNode traceRoot = analyzer.trace(roots.get(0), 5);
        assertEquals("AService.execute()", traceRoot.getMethod().getDisplayName());
        assertEquals("BService.process()", traceRoot.getChildren().get(0).getMethod().getDisplayName());
        assertEquals("CService.finish()", traceRoot.getChildren().get(0).getChildren().get(0)
                .getMethod().getDisplayName());

        List<MethodNode> finishMethods = index.findCandidates("CService", "finish");
        assertEquals(1, finishMethods.size());
        MethodNode.TraceNode callersTrace = analyzer.trace(
                finishMethods.get(0),
                5,
                JdtAnalyzer.TraceDirection.UP);
        assertEquals("BService.process()", callersTrace.getChildren().get(0).getMethod().getDisplayName());
        assertEquals("AService.execute()", callersTrace.getChildren().get(0).getChildren().get(0)
                .getMethod().getDisplayName());

        Path outputPath = projectPath.resolve("trace.md");
        MarkdownGenerator.write(
                outputPath,
                callersTrace,
                projectPath,
                5,
                JdtAnalyzer.TraceDirection.UP);
        String markdown = Files.readString(outputPath, StandardCharsets.UTF_8);
        assertTrue(markdown.contains("````java"));
        assertTrue(markdown.contains("Direction: `up`"));
        assertTrue(markdown.contains("AService.execute()"));
    }

    /**
     * 驗證同名多載 Method 可保留候選項目，並由完整 Signature 精確選取。
     *
     * @throws IOException 建立測試專案失敗時拋出
     */
    @Test
    void shouldRequireFullSignatureToDisambiguateOverloadedMethods() throws IOException {
        Path projectPath = temporaryDirectory.resolve("overload-project");
        Path sourceFile = projectPath.resolve("source-code/com/example/OverloadService.java");
        writeJava(sourceFile,
                "package com.example;\n"
                        + "public class OverloadService {\n"
                        + "    public void update(String policyNo) { }\n"
                        + "    public void update(Integer policyId) { }\n"
                        + "}\n");

        JavaSourceIndex index = new JdtAnalyzer(projectPath, StandardCharsets.UTF_8).buildIndex();

        assertEquals(2, index.findCandidates("OverloadService", "update").size());
        List<MethodNode> stringMethod = index.findCandidates("OverloadService", "update(String)");
        assertEquals(1, stringMethod.size());
        assertEquals("update(String)", stringMethod.get(0).getMethodSignature());
    }

    /**
     * 驗證 CLI 可讀取 YAML，且命令列參數會覆蓋 YAML 同名設定。
     *
     * @throws IOException 建立測試專案或讀取輸出失敗時拋出
     */
    @Test
    void shouldLoadYamlAndLetCommandLineOverrideValues() throws IOException {
        Path projectPath = temporaryDirectory.resolve("yaml-project");
        Path sourceFile = projectPath.resolve("source/com/demo/AService.java");
        writeJava(sourceFile,
                "package com.demo;\n"
                        + "public class AService {\n"
                        + "    public void execute() { }\n"
                        + "}\n");

        Path configPath = temporaryDirectory.resolve("trace.yml");
        Path outputPath = temporaryDirectory.resolve("yaml-trace.md");
        String config = "project: \"" + projectPath.toString().replace('\\', '/') + "\"\n"
                + "class: \"AService\"\n"
                + "method: \"execute\"\n"
                + "depth: 0\n"
                + "direction: \"up\"\n"
                + "output: \"" + outputPath.toString().replace('\\', '/') + "\"\n";
        Files.writeString(configPath, config, StandardCharsets.UTF_8);

        Map<String, String> values = YamlConfigLoader.load(configPath);
        assertEquals("0", values.get("depth"));
        assertEquals("up", values.get("direction"));

        int exitCode = Main.run(new String[]{
                "--config", configPath.toString(),
                "--depth", "1",
                "--direction", "down"});

        assertEquals(0, exitCode);
        String markdown = Files.readString(outputPath, StandardCharsets.UTF_8);
        assertTrue(markdown.contains("Max Depth: `1`"));
        assertTrue(markdown.contains("Direction: `down`"));
    }

    /**
     * 建立一個 UTF-8 Java 原始檔。
     *
     * @param filePath 原始檔路徑
     * @param source 原始碼
     * @throws IOException 寫檔失敗時拋出
     */
    private static void writeJava(Path filePath, String source) throws IOException {
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, source, StandardCharsets.UTF_8);
    }
}
