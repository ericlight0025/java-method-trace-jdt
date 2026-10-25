package com.light.javamethodtrace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                        + "    private final Worker worker = new WorkerImpl();\n"
                        + "    public String execute() {\n"
                        + "        return bService.process();\n"
                        + "    }\n"
                        + "    public void invokeWorker() {\n"
                        + "        worker.work();\n"
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
        writeJava(sourceRoot.resolve("Worker.java"),
                "package com.demo;\n"
                        + "public interface Worker {\n"
                        + "    void work();\n"
                        + "}\n");
        writeJava(sourceRoot.resolve("WorkerImpl.java"),
                "package com.demo;\n"
                        + "public class WorkerImpl implements Worker {\n"
                        + "    public void work() { }\n"
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

        List<MethodNode> implementationMethods = index.findCandidates("WorkerImpl", "work");
        assertEquals(1, implementationMethods.size());
        MethodNode.TraceNode implementationCallers = analyzer.trace(
                implementationMethods.get(0),
                2,
                JdtAnalyzer.TraceDirection.UP);
        assertEquals("AService.invokeWorker()",
                implementationCallers.getChildren().get(0).getMethod().getDisplayName());

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
        assertTrue(markdown.contains("## Call Tree\n\n```text\n"));
        assertTrue(markdown.contains("```\n\n## Method Detail"));
    }

    /**
     * 驗證同名多載 Method 可保留候選項目，並由完整 Signature 精確選取。
     *
     * @throws IOException 建立測試專案失敗時拋出
     */
    @Test
    void shouldRequireQualifiedSignatureToDisambiguateSameSimpleTypeNames() throws IOException {
        Path projectPath = temporaryDirectory.resolve("overload-project");
        Path sourceRoot = projectPath.resolve("source-code");
        writeJava(sourceRoot.resolve("com/alpha/Request.java"),
                "package com.alpha;\n"
                        + "public class Request { }\n");
        writeJava(sourceRoot.resolve("com/beta/Request.java"),
                "package com.beta;\n"
                        + "public class Request { }\n");
        writeJava(sourceRoot.resolve("com/example/OverloadService.java"),
                "package com.example;\n"
                        + "public class OverloadService {\n"
                        + "    public void update(com.alpha.Request request) { }\n"
                        + "    public void update(com.beta.Request request) { }\n"
                        + "    public void updateAlpha() { update(new com.alpha.Request()); }\n"
                        + "    public void updateBeta() { update(new com.beta.Request()); }\n"
                        + "}\n");

        JdtAnalyzer analyzer = new JdtAnalyzer(projectPath, StandardCharsets.UTF_8);
        JavaSourceIndex index = analyzer.buildIndex();

        assertEquals(2, index.findCandidates("OverloadService", "update").size());
        List<MethodNode> alphaMethod = index.findCandidates(
                "OverloadService",
                "update(com.alpha.Request)");
        List<MethodNode> betaMethod = index.findCandidates(
                "OverloadService",
                "update(com.beta.Request)");
        assertEquals(1, alphaMethod.size());
        assertEquals(1, betaMethod.size());
        assertNotEquals(alphaMethod.get(0).getUniqueKey(), betaMethod.get(0).getUniqueKey());

        MethodNode.TraceNode alphaCallers = analyzer.trace(
                alphaMethod.get(0), 2, JdtAnalyzer.TraceDirection.UP);
        MethodNode.TraceNode betaCallers = analyzer.trace(
                betaMethod.get(0), 2, JdtAnalyzer.TraceDirection.UP);
        assertEquals("OverloadService.updateAlpha()",
                alphaCallers.getChildren().get(0).getMethod().getDisplayName());
        assertEquals("OverloadService.updateBeta()",
                betaCallers.getChildren().get(0).getMethod().getDisplayName());
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
     * 驗證 YAML 重複設定名稱會被拒絕，避免設定值遭到靜默覆蓋。
     *
     * @throws IOException 寫入測試設定檔失敗時拋出
     */
    @Test
    void shouldRejectDuplicateYamlKeys() throws IOException {
        Path configPath = temporaryDirectory.resolve("duplicate.yml");
        Files.writeString(configPath,
                "project: first\nproject: second\n",
                StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> YamlConfigLoader.load(configPath));
    }

    @Test
    void shouldPreserveArrayDimensionsAndTraceEachOverload() throws IOException {
        Path projectPath = temporaryDirectory.resolve("arrays-project");
        writeJava(projectPath.resolve("src/com/demo/ArraysService.java"),
                "package com.demo; public class ArraysService {\n"
                        + " public void update(String[] value) {}\n"
                        + " public void update(String[][] value) {}\n"
                        + " public void update(String[][][] value) {}\n"
                        + " public void invokeOne() { update(new String[0]); }\n"
                        + " public void invokeTwo() { update(new String[0][0]); }\n"
                        + " public void invokeThree() { update(new String[0][0][0]); }\n"
                        + "}\n");
        JdtAnalyzer analyzer = new JdtAnalyzer(projectPath);
        JavaSourceIndex index = analyzer.buildIndex();
        for (int dimension = 1; dimension <= 3; dimension++) {
            String type = "String" + "[]".repeat(dimension);
            List<MethodNode> candidates = index.findCandidates("ArraysService", "update(" + type + ")");
            assertEquals(1, candidates.size(), type);
            assertEquals("update(java.lang." + type + ")", candidates.get(0).getQualifiedMethodSignature());
            String caller = List.of("invokeOne", "invokeTwo", "invokeThree").get(dimension - 1);
            MethodNode.TraceNode up = analyzer.trace(candidates.get(0), 1, JdtAnalyzer.TraceDirection.UP);
            assertEquals(1, up.getChildren().size());
            assertEquals(caller, up.getChildren().get(0).getMethod().getMethodName());
            MethodNode root = index.findCandidates("ArraysService", caller).get(0);
            assertEquals(candidates.get(0).getUniqueKey(),
                    analyzer.trace(root, 1).getChildren().get(0).getMethod().getUniqueKey());
        }
    }

    @Test
    void shouldTraceSuperCallsWithoutTreatingThemAsVirtualDispatch() throws IOException {
        Path projectPath = temporaryDirectory.resolve("super-project");
        writeJava(projectPath.resolve("src/com/demo/Parent.java"),
                "package com.demo; public class Parent { public void work() {} }\n");
        writeJava(projectPath.resolve("src/com/demo/Child.java"),
                "package com.demo; public class Child extends Parent {\n"
                        + " public void work() { super.work(); }\n"
                        + " public void invokeParent() { super.work(); }\n"
                        + "}\n");
        writeJava(projectPath.resolve("src/com/demo/Caller.java"),
                "package com.demo; public class Caller {\n"
                        + " public void invoke(Parent parent) { parent.work(); }\n"
                        + "}\n");
        JdtAnalyzer analyzer = new JdtAnalyzer(projectPath);
        JavaSourceIndex index = analyzer.buildIndex();
        MethodNode child = index.findCandidates("Child", "work").get(0);
        MethodNode parent = index.findCandidates("Parent", "work").get(0);
        MethodNode.TraceNode down = analyzer.trace(child, 2);
        assertEquals(1, down.getChildren().size());
        assertEquals(parent.getUniqueKey(), down.getChildren().get(0).getMethod().getUniqueKey());
        MethodNode.TraceNode parentUp = analyzer.trace(parent, 1, JdtAnalyzer.TraceDirection.UP);
        assertEquals(3, parentUp.getChildren().size());
        MethodNode.TraceNode childUp = analyzer.trace(child, 1, JdtAnalyzer.TraceDirection.UP);
        assertEquals(1, childUp.getChildren().size());
        assertEquals("Caller.invoke(Parent)", childUp.getChildren().get(0).getMethod().getDisplayName());
    }

    /**
     * 驗證往下追蹤會接到介面的實作，同一 Method 呼叫多次只列一次，重複子樹標記為 SEE ABOVE。
     *
     * @throws IOException 建立測試專案失敗時拋出
     */
    @Test
    void shouldTraceImplementationsDownAndNotExpandRepeatedMethodsTwice() throws IOException {
        Path projectPath = temporaryDirectory.resolve("down-project");
        writeJava(projectPath.resolve("src/com/demo/Service.java"),
                "package com.demo; public interface Service { void update(); }\n");
        writeJava(projectPath.resolve("src/com/demo/ServiceImpl.java"),
                "package com.demo; public class ServiceImpl implements Service {\n"
                        + " private final Dao dao = new Dao();\n"
                        + " public void update() { dao.write(); }\n"
                        + "}\n");
        writeJava(projectPath.resolve("src/com/demo/Dao.java"),
                "package com.demo; public class Dao { public void write() { log(); } void log() { } }\n");
        writeJava(projectPath.resolve("src/com/demo/Controller.java"),
                "package com.demo; public class Controller {\n"
                        + " private Service service; private final Dao dao = new Dao();\n"
                        + " public void handle() { dao.write(); service.update(); service.update(); }\n"
                        + "}\n");

        JdtAnalyzer analyzer = new JdtAnalyzer(projectPath);
        JavaSourceIndex index = analyzer.buildIndex();
        MethodNode handle = index.findCandidates("Controller", "handle").get(0);
        MethodNode.TraceNode down = analyzer.trace(handle, 5);

        List<MethodNode.TraceNode> children = down.getChildren();
        assertEquals(3, children.size());
        MethodNode.TraceNode firstWrite = children.get(0);
        assertEquals("Dao.write()", firstWrite.getMethod().getDisplayName());
        assertEquals(1, firstWrite.getChildren().size());
        assertEquals("Service.update()", children.get(1).getMethod().getDisplayName());
        assertEquals("ServiceImpl.update()", children.get(2).getMethod().getDisplayName());
        MethodNode.TraceNode repeatedWrite = children.get(2).getChildren().get(0);
        assertEquals("Dao.write()", repeatedWrite.getMethod().getDisplayName());
        assertTrue(repeatedWrite.isRepeated());
        assertTrue(repeatedWrite.getChildren().isEmpty());
        assertEquals(" [SEE ABOVE]", repeatedWrite.getMarker());
    }

    /**
     * 驗證匿名類別的 Method 不會被當成外層類別的同名 Method。
     *
     * @throws IOException 建立測試專案失敗時拋出
     */
    @Test
    void shouldNameAnonymousClassMethodsSeparately() throws IOException {
        Path projectPath = temporaryDirectory.resolve("anonymous-project");
        writeJava(projectPath.resolve("src/com/demo/Outer.java"),
                "package com.demo; public class Outer {\n"
                        + " public void start() { new Runnable() { public void run() { helper(); } }.run(); }\n"
                        + " public void run() { }\n"
                        + " void helper() { }\n"
                        + "}\n");

        JdtAnalyzer analyzer = new JdtAnalyzer(projectPath);
        JavaSourceIndex index = analyzer.buildIndex();

        assertEquals(1, index.findCandidates("Outer", "run").size());
        MethodNode start = index.findCandidates("Outer", "start").get(0);
        MethodNode.TraceNode anonymousRun = analyzer.trace(start, 2).getChildren().get(0);
        assertEquals("Outer$1.run()", anonymousRun.getMethod().getDisplayName());
        assertEquals("Outer.helper()", anonymousRun.getChildren().get(0).getMethod().getDisplayName());
    }

    /**
     * 驗證專案混用編碼時，單一檔案解碼失敗不會中止分析，且 Method 位置仍正確。
     *
     * @throws IOException 建立測試專案失敗時拋出
     */
    @Test
    void shouldAnalyzeFilesWithDifferentEncoding() throws IOException {
        Path projectPath = temporaryDirectory.resolve("mixed-encoding-project");
        writeJava(projectPath.resolve("src/com/demo/Modern.java"),
                "package com.demo; public class Modern { public void call() { new Legacy().old(); } }\n");
        Path legacyFile = projectPath.resolve("src/com/demo/Legacy.java");
        Files.write(legacyFile, ("package com.demo;\n"
                + "// 保單舊程式說明\n"
                + "public class Legacy {\n"
                + "    public void old() { }\n"
                + "}\n").getBytes("MS950"));

        JdtAnalyzer analyzer = new JdtAnalyzer(projectPath, StandardCharsets.UTF_8);
        JavaSourceIndex index = analyzer.buildIndex();

        MethodNode old = index.findCandidates("Legacy", "old").get(0);
        assertEquals("public void old() { }", old.getSourceCode());
        assertEquals(4, old.getStartLine());
        MethodNode call = index.findCandidates("Modern", "call").get(0);
        assertEquals("Legacy.old()", analyzer.trace(call, 1).getChildren().get(0).getMethod().getDisplayName());
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
