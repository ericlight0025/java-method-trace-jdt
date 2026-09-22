package com.light.javamethodtrace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Java Method Trace CLI 程式進入點。
 */
public final class Main {

    private Main() {
        // 工具類別不需要建立物件。
    }

    /**
     * 啟動命令列工具。
     *
     * @param args 命令列參數
     */
    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * 執行一次分析流程。
     *
     * @param args 命令列參數
     * @return 程式結束碼，0 代表成功
     */
    static int run(String[] args) {
        Arguments arguments;
        try {
            arguments = Arguments.parse(args);
        } catch (IllegalArgumentException exception) {
            System.err.println("Error: " + exception.getMessage());
            printUsage();
            return 2;
        }

        if (arguments.help()) {
            printUsage();
            return 0;
        }

        if (!Files.isDirectory(arguments.projectPath())) {
            System.err.println("Error: project path 不存在或不是目錄：" + arguments.projectPath());
            return 2;
        }

        try {
            System.out.println("Scanning Java files...");

            JdtAnalyzer analyzer = new JdtAnalyzer(arguments.projectPath());
            JavaSourceIndex index = analyzer.buildIndex();

            System.out.println("Found " + analyzer.getScannedFileCount() + " Java files.");
            System.out.println("Finding root method...");

            List<MethodNode> candidates = index.findCandidates(
                    arguments.className(),
                    arguments.methodSpec());

            if (candidates.isEmpty()) {
                System.err.println("Root method not found: "
                        + arguments.className() + "." + arguments.methodSpec());
                return 3;
            }

            if (candidates.size() > 1) {
                System.err.println("Found multiple methods:");
                for (int indexNumber = 0; indexNumber < candidates.size(); indexNumber++) {
                    MethodNode candidate = candidates.get(indexNumber);
                    System.err.println((indexNumber + 1) + ". "
                            + candidate.getFullyQualifiedClassName() + "."
                            + candidate.getMethodSignature());
                }
                System.err.println("請改用完整 Method Signature，例如："
                        + "--method \"updatePolicy(PolicyRequest)\"");
                return 4;
            }

            MethodNode root = candidates.get(0);
            System.out.println();
            System.out.println("Root:");
            System.out.println(root.getDisplayName());
            System.out.println();
            System.out.println("Tracing...");

            MethodNode.TraceNode traceRoot = analyzer.trace(root, arguments.maxDepth());
            printTrace(traceRoot, 0);

            MarkdownGenerator.write(
                    arguments.outputPath(),
                    traceRoot,
                    arguments.projectPath(),
                    arguments.maxDepth());

            System.out.println();
            System.out.println("Generated:");
            System.out.println(arguments.outputPath());
            return 0;
        } catch (IOException exception) {
            System.err.println("Error: 讀取或寫入檔案失敗：" + exception.getMessage());
            return 5;
        } catch (RuntimeException exception) {
            System.err.println("Error: 分析失敗：" + exception.getMessage());
            return 6;
        }
    }

    /**
     * 在 Console 顯示簡潔的呼叫追蹤結果。
     *
     * @param traceNode 追蹤節點
     * @param depth 目前深度
     */
    private static void printTrace(MethodNode.TraceNode traceNode, int depth) {
        String prefix = depth == 0 ? "" : "-> " + "  ".repeat(depth - 1);
        String cycleText = traceNode.isCycle() ? " [CYCLE]" : "";
        System.out.println(prefix + traceNode.getMethod().getDisplayName() + cycleText);

        for (MethodNode.TraceNode child : traceNode.getChildren()) {
            printTrace(child, depth + 1);
        }
    }

    /**
     * 顯示命令列使用方式。
     */
    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java -jar java-method-trace.jar"
                + " --project <path>"
                + " --class <class-name>"
                + " --method <method-name-or-signature>"
                + " --depth <number>"
                + " [--output <file>]");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar java-method-trace.jar"
                + " --project \"D:\\workspace\\insurance\""
                + " --class \"PolicyService\""
                + " --method \"updatePolicy(PolicyRequest)\""
                + " --depth 5"
                + " --output \"trace.md\"");
        System.out.println();
        System.out.println("未指定 --output 時，預設輸出到目前執行目錄的 trace.md。");
    }

    /**
     * 已解析的命令列參數。
     *
     * @param projectPath Java 專案路徑
     * @param className Class 名稱
     * @param methodSpec Method 名稱或完整 Method Signature
     * @param maxDepth 最大追蹤深度
     * @param outputPath 輸出檔案
     * @param help 是否顯示說明
     */
    private record Arguments(
            Path projectPath,
            String className,
            String methodSpec,
            int maxDepth,
            Path outputPath,
            boolean help) {

        /**
         * 解析命令列參數。
         *
         * @param args 原始命令列參數
         * @return 已解析參數
         */
        private static Arguments parse(String[] args) {
            if (args == null || args.length == 0) {
                throw new IllegalArgumentException("缺少命令列參數。");
            }

            String project = null;
            String className = null;
            String method = null;
            String depth = null;
            String output = null;

            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if ("--help".equals(argument) || "-h".equals(argument)) {
                    return new Arguments(null, null, null, 0, null, true);
                }

                if (!argument.startsWith("--")) {
                    throw new IllegalArgumentException("不支援的參數格式：" + argument);
                }

                String optionName;
                String optionValue;
                int equalIndex = argument.indexOf('=');
                if (equalIndex > 2) {
                    optionName = argument.substring(2, equalIndex);
                    optionValue = argument.substring(equalIndex + 1);
                } else {
                    optionName = argument.substring(2);
                    if (index + 1 >= args.length) {
                        throw new IllegalArgumentException("參數缺少值：--" + optionName);
                    }
                    optionValue = args[++index];
                }

                if (optionValue == null || optionValue.isBlank()) {
                    throw new IllegalArgumentException("參數值不可為空：--" + optionName);
                }

                switch (optionName) {
                    case "project" -> project = optionValue;
                    case "class" -> className = optionValue;
                    case "method" -> method = optionValue;
                    case "depth" -> depth = optionValue;
                    case "output" -> output = optionValue;
                    default -> throw new IllegalArgumentException("不支援的參數：--" + optionName);
                }
            }

            if (project == null || className == null || method == null || depth == null) {
                throw new IllegalArgumentException("必須提供 --project、--class、--method、--depth。");
            }

            int maxDepth;
            try {
                maxDepth = Integer.parseInt(depth);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("--depth 必須是非負整數：" + depth);
            }

            if (maxDepth < 0) {
                throw new IllegalArgumentException("--depth 必須是非負整數：" + depth);
            }

            Path projectPath = Path.of(project).toAbsolutePath().normalize();
            Path outputPath = output == null
                    ? Path.of("trace.md").toAbsolutePath().normalize()
                    : Path.of(output).toAbsolutePath().normalize();

            return new Arguments(
                    projectPath,
                    className.trim(),
                    method.trim(),
                    maxDepth,
                    outputPath,
                    false);
        }
    }
}
