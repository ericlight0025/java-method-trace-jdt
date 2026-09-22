package com.light.javamethodtrace;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;

/**
 * 使用 Eclipse JDT Core 掃描 Java 原始碼並建立 Method 呼叫追蹤。
 */
public final class JdtAnalyzer {

    private static final Set<String> IGNORED_DIRECTORY_NAMES = Set.of(
            ".git",
            "target",
            "build",
            "node_modules",
            ".idea");

    private final Path projectPath;
    private final Charset sourceCharset;
    private int scannedFileCount;
    private JavaSourceIndex sourceIndex;
    private Map<String, List<MethodNode>> callersByCalleeKey = new HashMap<String, List<MethodNode>>();
    private Map<Path, Path> sourceRootByFile = new HashMap<Path, Path>();
    private List<Path> sourceRoots = List.of();
    private List<String> classpathEntries = List.of();

    /**
     * 建立預設 UTF-8 的 JDT 分析器。
     *
     * @param projectPath Java 專案根目錄
     */
    public JdtAnalyzer(Path projectPath) {
        this(projectPath, StandardCharsets.UTF_8);
    }

    /**
     * 建立 JDT 分析器。
     *
     * @param projectPath Java 專案根目錄
     * @param sourceCharset Java 原始檔編碼
     */
    public JdtAnalyzer(Path projectPath, Charset sourceCharset) {
        this.projectPath = projectPath.toAbsolutePath().normalize();
        this.sourceCharset = sourceCharset;
    }

    /**
     * 掃描專案並建立 Java Method Index。
     *
     * @return Java Method Index
     * @throws IOException 讀取檔案失敗時拋出
     */
    public JavaSourceIndex buildIndex() throws IOException {
        List<Path> javaFiles = scanJavaFiles();
        scannedFileCount = javaFiles.size();
        Map<Path, String> sourceByFile = readSources(javaFiles);
        sourceRootByFile = detectSourceRoots(javaFiles, sourceByFile);
        sourceRoots = sourceRootByFile.values().stream()
                .distinct()
                .sorted(Comparator.comparing(Path::toString))
                .collect(Collectors.toList());
        classpathEntries = detectClasspathEntries();
        sourceIndex = new JavaSourceIndex();

        for (Path javaFile : javaFiles) {
            String source = sourceByFile.get(javaFile);
            CompilationUnit compilationUnit;
            try {
                compilationUnit = parse(javaFile, source);
            } catch (RuntimeException exception) {
                System.err.println("Warning: JDT 無法解析檔案，已略過："
                        + projectPath.relativize(javaFile)
                        + "，原因：" + exception.getMessage());
                continue;
            }

            compilationUnit.accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodDeclaration declaration) {
                    if (!declaration.isConstructor()) {
                        sourceIndex.add(MethodNode.from(
                                compilationUnit,
                                declaration,
                                javaFile,
                                source));
                    }
                    return true;
                }
            });
        }

        buildCallerIndex();

        return sourceIndex;
    }

    /**
     * 取得掃描到的 Java 檔案數。
     *
     * @return Java 檔案數
     */
    public int getScannedFileCount() {
        return scannedFileCount;
    }

    /**
     * 從指定 Root Method 開始追蹤專案內呼叫。
     *
     * @param root 根 Method
     * @param maxDepth 最大深度，Root 為 0
     * @return 呼叫追蹤樹
     */
    public MethodNode.TraceNode trace(MethodNode root, int maxDepth) {
        return trace(root, maxDepth, TraceDirection.DOWN);
    }

    /**
     * 從指定 Root Method 開始依方向追蹤專案內呼叫關係。
     *
     * @param root 根 Method
     * @param maxDepth 最大深度，Root 為 0
     * @param direction 追蹤方向
     * @return 呼叫追蹤樹
     */
    public MethodNode.TraceNode trace(
            MethodNode root,
            int maxDepth,
            TraceDirection direction) {
        if (sourceIndex == null) {
            throw new IllegalStateException("請先呼叫 buildIndex()。");
        }
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth 不可小於 0。");
        }
        if (direction == null) {
            throw new IllegalArgumentException("追蹤方向不可為空。");
        }

        Set<String> currentPath = new HashSet<String>();
        currentPath.add(root.getUniqueKey());
        return traceNode(root, 0, maxDepth, currentPath, direction);
    }

    /**
     * 遞迴建立單一 Method 的追蹤節點。
     *
     * @param method 目前 Method
     * @param depth 目前深度
     * @param maxDepth 最大深度
     * @param currentPath 目前呼叫路徑上的 Method Key
     * @return 追蹤節點
     */
    private MethodNode.TraceNode traceNode(
            MethodNode method,
            int depth,
            int maxDepth,
            Set<String> currentPath,
            TraceDirection direction) {
        MethodNode.TraceNode traceNode = new MethodNode.TraceNode(method, false);
        if (depth >= maxDepth) {
            return traceNode;
        }

        for (MethodNode relatedMethod : findRelatedMethods(method, direction)) {
            boolean cycle = currentPath.contains(relatedMethod.getUniqueKey());
            MethodNode.TraceNode child = new MethodNode.TraceNode(relatedMethod, cycle);
            traceNode.addChild(child);

            if (!cycle) {
                Set<String> nextPath = new HashSet<String>(currentPath);
                nextPath.add(relatedMethod.getUniqueKey());
                MethodNode.TraceNode expandedChild = traceNode(
                        relatedMethod,
                        depth + 1,
                        maxDepth,
                        nextPath,
                        direction);
                for (MethodNode.TraceNode grandChild : expandedChild.getChildren()) {
                    child.addChild(grandChild);
                }
            }
        }
        return traceNode;
    }

    /**
     * 找出某個 Method 內所有可解析且位於目前專案的 Method 呼叫。
     *
     * @param method 目前 Method
     * @return 專案內被呼叫的 Method，順序與原始碼出現順序相同
     */
    private List<MethodNode> findProjectMethodInvocations(MethodNode method) {
        List<MethodNode> result = new ArrayList<MethodNode>();
        method.getDeclaration().accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration declaration) {
                return declaration == method.getDeclaration();
            }

            @Override
            public boolean visit(LambdaExpression lambdaExpression) {
                // 第一版不進入 Lambda 內部，避免把 Lambda 特殊語意誤當成一般 Method Body。
                return false;
            }

            @Override
            public boolean visit(MethodInvocation invocation) {
                IMethodBinding binding = invocation.resolveMethodBinding();
                MethodNode target = sourceIndex.findByBinding(binding);
                if (target != null) {
                    result.add(target);
                }
                return true;
            }
        });
        return result;
    }

    /**
     * 依追蹤方向取得下一層相關 Method。
     *
     * @param method 目前 Method
     * @param direction 追蹤方向
     * @return 下一層 Method
     */
    private List<MethodNode> findRelatedMethods(MethodNode method, TraceDirection direction) {
        if (direction == TraceDirection.DOWN) {
            return findProjectMethodInvocations(method);
        }
        List<MethodNode> callers = callersByCalleeKey.get(method.getUniqueKey());
        return callers == null ? List.of() : callers;
    }

    /**
     * 建立被呼叫 Method 到呼叫端 Method 的反向索引，供向上追蹤使用。
     */
    private void buildCallerIndex() {
        callersByCalleeKey = new HashMap<String, List<MethodNode>>();
        for (MethodNode caller : sourceIndex.getMethods()) {
            for (MethodNode callee : findProjectMethodInvocations(caller)) {
                List<MethodNode> callers = callersByCalleeKey.computeIfAbsent(
                        callee.getUniqueKey(),
                        ignored -> new ArrayList<MethodNode>());
                if (!callers.contains(caller)) {
                    callers.add(caller);
                }
            }
        }

        Comparator<MethodNode> callerOrder = Comparator
                .comparing((MethodNode method) -> method.getFilePath().toString())
                .thenComparingInt(MethodNode::getStartLine);
        for (List<MethodNode> callers : callersByCalleeKey.values()) {
            callers.sort(callerOrder);
        }
    }

    /**
     * 掃描所有 Java 檔案，排除指定目錄。
     *
     * @return Java 檔案清單
     * @throws IOException 掃描失敗時拋出
     */
    private List<Path> scanJavaFiles() throws IOException {
        try (Stream<Path> pathStream = Files.walk(projectPath)) {
            return pathStream
                    .filter(Files::isRegularFile)
                    .filter(this::isJavaFile)
                    .filter(path -> !isUnderIgnoredDirectory(path))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    /**
     * 讀取所有 Java 原始碼，後續 Source Root 與 AST 都使用同一份內容。
     *
     * @param javaFiles Java 檔案清單
     * @return 檔案與原始碼對照表
     * @throws IOException 讀檔失敗時拋出
     */
    private Map<Path, String> readSources(List<Path> javaFiles) throws IOException {
        Map<Path, String> result = new HashMap<Path, String>();
        for (Path javaFile : javaFiles) {
            String source = Files.readString(javaFile, sourceCharset);
            if (!source.isEmpty() && source.charAt(0) == '\uFEFF') {
                source = source.substring(1);
            }
            result.put(javaFile, source);
        }
        return result;
    }

    /**
     * 判斷是否為 Java 檔案。
     *
     * @param path 檔案路徑
     * @return 是否為 Java 檔案
     */
    private boolean isJavaFile(Path path) {
        return path.getFileName().toString().endsWith(".java");
    }

    /**
     * 判斷檔案是否位於忽略目錄內。
     *
     * @param path 檔案路徑
     * @return 是否忽略
     */
    private boolean isUnderIgnoredDirectory(Path path) {
        Path relativePath = projectPath.relativize(path);
        for (Path part : relativePath) {
            if (IGNORED_DIRECTORY_NAMES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 依 Java 檔 package 宣告推算個別檔案的 Source Root。
     *
     * @param javaFiles Java 檔案
     * @param sourceByFile 檔案與原始碼對照表
     * @return 檔案與 Source Root 對照表
     */
    private Map<Path, Path> detectSourceRoots(
            List<Path> javaFiles,
            Map<Path, String> sourceByFile) {
        Map<Path, Path> result = new HashMap<Path, Path>();
        for (Path javaFile : javaFiles) {
            result.put(javaFile, inferSourceRoot(javaFile, sourceByFile.get(javaFile)));
        }
        return result;
    }

    /**
     * 由 package path 倒推 Source Root，支援 Maven、Gradle、Eclipse src 與自訂資料夾。
     *
     * @param javaFile Java 檔案
     * @param source 原始碼
     * @return Source Root
     */
    private Path inferSourceRoot(Path javaFile, String source) {
        List<String> packageSegments = splitPackageName(readPackageName(source));
        Path current = javaFile.getParent();

        for (int index = packageSegments.size() - 1; index >= 0; index--) {
            if (current == null || current.getFileName() == null
                    || !packageSegments.get(index).equals(current.getFileName().toString())) {
                return projectPath;
            }
            current = current.getParent();
        }

        if (current == null || !current.startsWith(projectPath)) {
            return projectPath;
        }
        return current;
    }

    /**
     * 使用 JDT 讀取 package 宣告，不以 Regex 解析 Java 語法。
     *
     * @param source 原始碼
     * @return package 名稱，沒有 package 時回傳空字串
     */
    private String readPackageName(String source) {
        ASTParser parser = ASTParser.newParser(AST.JLS11);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        CompilationUnit compilationUnit = (CompilationUnit) parser.createAST(null);
        return compilationUnit.getPackage() == null
                ? ""
                : compilationUnit.getPackage().getName().getFullyQualifiedName();
    }

    /**
     * 將 package 名稱切成路徑片段。
     *
     * @param packageName package 名稱
     * @return package 片段
     */
    private List<String> splitPackageName(String packageName) {
        List<String> result = new ArrayList<String>();
        if (packageName == null || packageName.isEmpty()) {
            return result;
        }

        StringBuilder current = new StringBuilder();
        for (int index = 0; index < packageName.length(); index++) {
            char character = packageName.charAt(index);
            if (character == '.') {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    /**
     * 偵測專案內可直接協助 JDT Binding 的編譯輸出與依賴 JAR。
     *
     * @return Classpath 清單
     * @throws IOException 掃描失敗時拋出
     */
    private List<String> detectClasspathEntries() throws IOException {
        Set<String> entries = new LinkedHashSet<String>();
        addIfDirectory(entries, projectPath.resolve("target/classes"));
        addIfDirectory(entries, projectPath.resolve("target/test-classes"));

        Path targetPath = projectPath.resolve("target");
        if (Files.isDirectory(targetPath)) {
            try (Stream<Path> pathStream = Files.walk(targetPath)) {
                pathStream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".jar"))
                        .forEach(path -> entries.add(path.toAbsolutePath().normalize().toString()));
            }
        }
        return new ArrayList<String>(entries);
    }

    /**
     * 將存在的目錄加入 Classpath。
     *
     * @param entries Classpath 集合
     * @param path 目錄
     */
    private static void addIfDirectory(Set<String> entries, Path path) {
        if (Files.isDirectory(path)) {
            entries.add(path.toAbsolutePath().normalize().toString());
        }
    }

    /**
     * 使用 JDT ASTParser 解析單一 Java 原始檔。
     *
     * @param javaFile Java 檔案
     * @param source 原始碼
     * @return CompilationUnit
     */
    @SuppressWarnings("unchecked")
    private CompilationUnit parse(Path javaFile, String source) {
        ASTParser parser = ASTParser.newParser(AST.JLS11);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);
        parser.setCompilerOptions(createJava11Options());
        parser.setEnvironment(
                classpathEntries.toArray(new String[0]),
                sourceRoots.stream().map(Path::toString).toArray(String[]::new),
                sourceRoots.stream().map(path -> sourceCharset.name()).toArray(String[]::new),
                true);
        parser.setUnitName(buildUnitName(javaFile));

        return (CompilationUnit) parser.createAST(null);
    }

    /**
     * 建立 JDT 所需的 Unit Name。
     *
     * @param javaFile Java 檔案
     * @return Unit Name
     */
    private String buildUnitName(Path javaFile) {
        Path sourceRoot = sourceRootByFile.get(javaFile);
        if (sourceRoot == null || !javaFile.startsWith(sourceRoot)) {
            sourceRoot = projectPath;
        }

        String relativePath = sourceRoot.relativize(javaFile).toString()
                .replace(File.separatorChar, '/');
        return "/" + relativePath;
    }

    /**
     * 建立 Java 11 的 JDT 編譯器選項。
     *
     * @return 編譯器選項
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> createJava11Options() {
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.VERSION_11, options);
        return options;
    }

    /**
     * Method Call Trace 的方向。
     */
    public enum TraceDirection {
        /** 從目前 Method 往下尋找被呼叫的 Method。 */
        DOWN,
        /** 從目前 Method 往上尋找呼叫它的 Method。 */
        UP;

        /**
         * 解析 CLI 方向參數。
         *
         * @param value CLI 輸入值
         * @return 追蹤方向
         */
        public static TraceDirection fromCliValue(String value) {
            if ("down".equalsIgnoreCase(value)) {
                return DOWN;
            }
            if ("up".equalsIgnoreCase(value)) {
                return UP;
            }
            throw new IllegalArgumentException("--direction 僅支援 up 或 down：" + value);
        }

        /**
         * 取得 CLI 顯示名稱。
         *
         * @return 小寫方向名稱
         */
        public String getCliValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
