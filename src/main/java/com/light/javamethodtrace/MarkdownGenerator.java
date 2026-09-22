package com.light.javamethodtrace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 將 Method Trace 輸出成 Markdown。
 */
public final class MarkdownGenerator {

    private MarkdownGenerator() {
        // 工具類別不需要建立物件。
    }

    /**
     * 寫出 Markdown 報告。
     *
     * @param outputPath 輸出檔案
     * @param root 追蹤根節點
     * @param projectPath 專案路徑
     * @param maxDepth 最大深度
     * @throws IOException 寫檔失敗時拋出
     */
    public static void write(
            Path outputPath,
            MethodNode.TraceNode root,
            Path projectPath,
            int maxDepth) throws IOException {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Java Method Trace\n\n");
        markdown.append("Root: `")
                .append(root.getMethod().getDisplayName())
                .append("`\n\n");
        markdown.append("Project: `")
                .append(projectPath)
                .append("`\n\n");
        markdown.append("Max Depth: `")
                .append(maxDepth)
                .append("`\n\n");
        markdown.append("---\n\n");
        markdown.append("## Call Tree\n\n");
        appendTree(markdown, root, "", true, true, projectPath);
        markdown.append("\n## Method Detail\n\n");

        Map<String, MethodNode> methods = new LinkedHashMap<>();
        collectMethods(root, methods);
        for (MethodNode method : methods.values()) {
            appendMethodDetail(markdown, method, projectPath);
        }

        Path absoluteOutput = outputPath.toAbsolutePath().normalize();
        Path parent = absoluteOutput.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                absoluteOutput,
                markdown.toString(),
                StandardCharsets.UTF_8);
    }

    /**
     * 輸出呼叫樹。
     *
     * @param markdown Markdown 緩衝區
     * @param node 目前節點
     * @param prefix 樹狀圖前綴
     * @param isLast 是否為同層最後一個節點
     * @param root 是否為根節點
     * @param projectPath 專案路徑
     */
    private static void appendTree(
            StringBuilder markdown,
            MethodNode.TraceNode node,
            String prefix,
            boolean isLast,
            boolean root,
            Path projectPath) {
        String cycleText = node.isCycle() ? " [CYCLE]" : "";

        if (root) {
            markdown.append(node.getMethod().getDisplayName())
                    .append(cycleText)
                    .append('\n');
            markdown.append(formatLocation(node.getMethod(), projectPath)).append('\n');
        } else {
            markdown.append(prefix)
                    .append(isLast ? "└─ " : "├─ ")
                    .append(node.getMethod().getDisplayName())
                    .append(cycleText)
                    .append('\n');
            String locationPrefix = prefix + (isLast ? "   " : "│  ");
            markdown.append(locationPrefix)
                    .append(formatLocation(node.getMethod(), projectPath))
                    .append('\n');
        }

        String childPrefix = root
                ? ""
                : prefix + (isLast ? "   " : "│  ");
        List<MethodNode.TraceNode> children = node.getChildren();
        for (int index = 0; index < children.size(); index++) {
            appendTree(
                    markdown,
                    children.get(index),
                    childPrefix,
                    index == children.size() - 1,
                    false,
                    projectPath);
        }
    }

    /**
     * 收集 Trace 過程中出現過的 Method，並移除重複項目。
     *
     * @param node 目前節點
     * @param methods 方法 Map
     */
    private static void collectMethods(
            MethodNode.TraceNode node,
            Map<String, MethodNode> methods) {
        methods.putIfAbsent(node.getMethod().getUniqueKey(), node.getMethod());
        for (MethodNode.TraceNode child : node.getChildren()) {
            collectMethods(child, methods);
        }
    }

    /**
     * 輸出單一 Method Detail。
     *
     * @param markdown Markdown 緩衝區
     * @param method Method
     * @param projectPath 專案路徑
     */
    private static void appendMethodDetail(
            StringBuilder markdown,
            MethodNode method,
            Path projectPath) {
        markdown.append("### `")
                .append(method.getDisplayName())
                .append("`\n\n");
        markdown.append("File: `")
                .append(formatRelativePath(method.getFilePath(), projectPath))
                .append("`\n\n");
        markdown.append("Line: `")
                .append(method.getStartLine())
                .append(" - ")
                .append(method.getEndLine())
                .append("`\n\n");
        String codeFence = resolveCodeFence(method.getSourceCode());
        markdown.append(codeFence).append("java\n")
                .append(method.getSourceCode())
                .append('\n').append(codeFence).append("\n\n");
    }

    /**
     * 取得不會被 Method 原始碼中的反引號提前結束的 Markdown 程式碼圍欄。
     *
     * @param sourceCode Method 原始碼
     * @return 可安全使用的程式碼圍欄
     */
    private static String resolveCodeFence(String sourceCode) {
        int longestRun = 0;
        int currentRun = 0;
        for (int index = 0; index < sourceCode.length(); index++) {
            if (sourceCode.charAt(index) == '`') {
                currentRun++;
                longestRun = Math.max(longestRun, currentRun);
            } else {
                currentRun = 0;
            }
        }
        return "`".repeat(Math.max(3, longestRun + 1));
    }

    /**
     * 格式化 Method 位置。
     *
     * @param method Method
     * @param projectPath 專案路徑
     * @return 檔案與行號文字
     */
    private static String formatLocation(MethodNode method, Path projectPath) {
        String lineText = method.getStartLine() == method.getEndLine()
                ? Integer.toString(method.getStartLine())
                : method.getStartLine() + " - " + method.getEndLine();
        return formatRelativePath(method.getFilePath(), projectPath) + ":" + lineText;
    }

    /**
     * 將檔案路徑轉成 Markdown 使用的相對路徑。
     *
     * @param filePath 檔案路徑
     * @param projectPath 專案路徑
     * @return 相對路徑
     */
    private static String formatRelativePath(Path filePath, Path projectPath) {
        return projectPath.relativize(filePath).toString()
                .replace('\\', '/');
    }
}
