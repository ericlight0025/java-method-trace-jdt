package com.light.javamethodtrace;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
    private int scannedFileCount;
    private JavaSourceIndex sourceIndex;
    private List<Path> sourceRoots = List.of();
    private List<String> classpathEntries = List.of();

    /**
     * 建立 JDT 分析器。
     *
     * @param projectPath Java 專案根目錄
     */
    public JdtAnalyzer(Path projectPath) {
        this.projectPath = projectPath.toAbsolutePath().normalize();
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
        sourceRoots = detectSourceRoots(javaFiles);
        classpathEntries = detectClasspathEntries();
        sourceIndex = new JavaSourceIndex();

        for (Path javaFile : javaFiles) {
            String source = Files.readString(javaFile, StandardCharsets.UTF_8);
            CompilationUnit compilationUnit;
            try {
                compilationUnit = parse(javaFile, source);
            } catch (RuntimeException exception) {
                System.err.println("Warning: JDT 無法解析檔案，已略過："
                        + projectPath.relativize(javaFile));
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
        if (sourceIndex == null) {
            throw new IllegalStateException("請先呼叫 buildIndex()。");
        }
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth 不可小於 0。");
        }

        Set<String> currentPath = new HashSet<>();
        currentPath.add(root.getUniqueKey());
        return traceNode(root, 0, maxDepth, currentPath);
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
            Set<String> currentPath) {
        MethodNode.TraceNode traceNode = new MethodNode.TraceNode(method, false);
        if (depth >= maxDepth) {
            return traceNode;
        }

        for (MethodNode calledMethod : findProjectMethodInvocations(method)) {
            boolean cycle = currentPath.contains(calledMethod.getUniqueKey());
            MethodNode.TraceNode child = new MethodNode.TraceNode(calledMethod, cycle);
            traceNode.addChild(child);

            if (!cycle) {
                Set<String> nextPath = new HashSet<>(currentPath);
                nextPath.add(calledMethod.getUniqueKey());
                MethodNode.TraceNode expandedChild = traceNode(
                        calledMethod,
                        depth + 1,
                        maxDepth,
                        nextPath);
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
        List<MethodNode> result = new ArrayList<>();
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
     * 偵測常見 Maven、Gradle 與一般 Java 專案的 Source Root。
     *
     * @param javaFiles Java 檔案
     * @return Source Root 清單
     */
    private List<Path> detectSourceRoots(List<Path> javaFiles) {
        Set<Path> roots = new LinkedHashSet<>();
        for (Path javaFile : javaFiles) {
            Path relative = projectPath.relativize(javaFile);
            Path currentRoot = projectPath;
            boolean foundJavaDirectory = false;

            for (Path part : relative) {
                currentRoot = currentRoot.resolve(part);
                if ("java".equalsIgnoreCase(part.toString())) {
                    roots.add(currentRoot);
                    foundJavaDirectory = true;
                    break;
                }
            }

            if (!foundJavaDirectory) {
                roots.add(projectPath);
            }
        }

        if (roots.isEmpty()) {
            roots.add(projectPath);
        }
        return roots.stream().sorted(Comparator.comparing(Path::toString)).toList();
    }

    /**
     * 偵測專案內可直接協助 JDT Binding 的編譯輸出與依賴 JAR。
     *
     * @return Classpath 清單
     * @throws IOException 掃描失敗時拋出
     */
    private List<String> detectClasspathEntries() throws IOException {
        Set<String> entries = new LinkedHashSet<>();
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
        return List.copyOf(entries);
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
        ASTParser parser = ASTParser.newParser(AST.JLS17);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);

        MapBuilder compilerOptions = new MapBuilder();
        parser.setCompilerOptions(compilerOptions.createJava17Options());
        parser.setEnvironment(
                classpathEntries.toArray(String[]::new),
                sourceRoots.stream().map(Path::toString).toArray(String[]::new),
                sourceRoots.stream().map(ignored -> StandardCharsets.UTF_8.name()).toArray(String[]::new),
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
        Path sourceRoot = sourceRoots.stream()
                .filter(javaFile::startsWith)
                .max(Comparator.comparing(Path::getNameCount))
                .orElse(projectPath);
        String relativePath = sourceRoot.relativize(javaFile).toString()
                .replace(File.separatorChar, '/');
        return "/" + relativePath;
    }

    /**
     * 集中處理 JDT Java 17 編譯器選項，避免把設定細節散落在分析流程中。
     */
    private static final class MapBuilder {

        /**
         * 建立 Java 17 編譯器選項。
         *
         * @return 編譯器選項
         */
        private java.util.Map<String, String> createJava17Options() {
            java.util.Map<String, String> options = JavaCore.getOptions();
            JavaCore.setComplianceOptions(JavaCore.VERSION_17, options);
            return options;
        }
    }
}
