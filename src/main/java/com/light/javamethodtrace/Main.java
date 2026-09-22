package com.light.javamethodtrace;

import java.io.IOException;
import java.nio.charset.Charset;
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

            JdtAnalyzer analyzer = new JdtAnalyzer(
                    arguments.projectPath(),
                    arguments.sourceCharset());
            JavaSourceIndex index = analyzer.buildIndex();

            System.out.println("Found " + analyzer.getScannedFileCount() + " Java files.");
            System.out.println("Source Encoding: " + arguments.sourceCharset().name());
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
                            + candidate.getQualifiedMethodSignature());
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
                + " [--encoding <charset>]"
                + " [--output <file>]");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar java-method-trace.jar"
                + " --project \"D:\\workspace\\insurance\""
                + " --class \"PolicyService\""
                + " --method \"updatePolicy(PolicyRequest)\""
                + " --depth 5"
                + " --encoding \"MS950\""
                + " --output \"trace.md\"");
        System.out.println();
        System.out.println("未指定 --output 時，預設輸出到目前執行目錄的 trace.md。");
        System.out.println("未指定 --encoding 時，預設使用 UTF-8。");
    }

    /**
     * 已解析的命令列參數。
     */
    private static final class Arguments {

        private final Path projectPath;
        private final String className;
        private final String methodSpec;
        private final int maxDepth;
        private final Path outputPath;
        private final Charset sourceCharset;
        private final boolean help;

        /**
         * 建立已解析的命令列參數。
         *
         * @param projectPath Java 專案路徑
         * @param className Class 名稱
         * @param methodSpec Method 名稱或完整 Method Signature
         * @param maxDepth 最大追蹤深度
         * @param outputPath 輸出檔案
         * @param sourceCharset Java 原始檔編碼
         * @param help 是否顯示說明
         */
        private Arguments(
                Path projectPath,
                String className,
                String methodSpec,
                int maxDepth,
                Path outputPath,
                Charset sourceCharset,
                boolean help) {
            this.projectPath = projectPath;
            this.className = className;
            this.methodSpec = methodSpec;
            this.maxDepth = maxDepth;
            this.outputPath = outputPath;
            this.sourceCharset = sourceCharset;
            this.help = help;
        }

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
            String encoding = "UTF-8";

            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if ("--help".equals(argument) || "-h".equals(argument)) {
                    return new Arguments(null, null, null, 0, null, null, true);
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
                    case "project":
                        project = optionValue;
                        break;
                    case "class":
                        className = optionValue;
                        break;
                    case "method":
                        method = optionValue;
                        break;
                    case "depth":
                        depth = optionValue;
                        break;
                    case "output":
                        output = optionValue;
                        break;
                    case "encoding":
                        encoding = optionValue;
                        break;
                    default:
                        throw new IllegalArgumentException("不支援的參數：--" + optionName);
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

            Charset sourceCharset;
            try {
                sourceCharset = Charset.forName(encoding.trim());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("不支援的 --encoding：" + encoding);
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
                    sourceCharset,
                    false);
        }

        /**
         * 取得 Java 專案路徑。
         *
         * @return Java 專案路徑
         */
        private Path projectPath() {
            return projectPath;
        }

        /**
         * 取得 Class 名稱。
         *
         * @return Class 名稱
         */
        private String className() {
            return className;
        }

        /**
         * 取得 Method 查詢條件。
         *
         * @return Method 名稱或 Signature
         */
        private String methodSpec() {
            return methodSpec;
        }

        /**
         * 取得最大追蹤深度。
         *
         * @return 最大追蹤深度
         */
        private int maxDepth() {
            return maxDepth;
        }

        /**
         * 取得輸出檔案。
         *
         * @return 輸出檔案
         */
        private Path outputPath() {
            return outputPath;
        }

        /**
         * 取得 Java 原始檔編碼。
         *
         * @return Java 原始檔編碼
         */
        private Charset sourceCharset() {
            return sourceCharset;
        }

        /**
         * 判斷是否顯示說明。
         *
         * @return true 代表顯示說明
         */
        private boolean help() {
            return help;
        }
    }
}
