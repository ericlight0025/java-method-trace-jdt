package com.light.javamethodtrace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;

/**
 * Java Method Index 中的一個 Method 資料節點。
 */
public final class MethodNode {

    private final String packageName;
    private final String className;
    private final String fullyQualifiedClassName;
    private final String methodName;
    private final String methodSignature;
    private final List<String> parameterTypes;
    private final Path filePath;
    private final int startLine;
    private final int endLine;
    private final String sourceCode;
    private final String bindingKey;
    private final CompilationUnit compilationUnit;
    private final MethodDeclaration declaration;

    private MethodNode(
            String packageName,
            String className,
            String fullyQualifiedClassName,
            String methodName,
            String methodSignature,
            List<String> parameterTypes,
            Path filePath,
            int startLine,
            int endLine,
            String sourceCode,
            String bindingKey,
            CompilationUnit compilationUnit,
            MethodDeclaration declaration) {
        this.packageName = packageName;
        this.className = className;
        this.fullyQualifiedClassName = fullyQualifiedClassName;
        this.methodName = methodName;
        this.methodSignature = methodSignature;
        this.parameterTypes = List.copyOf(parameterTypes);
        this.filePath = filePath;
        this.startLine = startLine;
        this.endLine = endLine;
        this.sourceCode = sourceCode;
        this.bindingKey = bindingKey;
        this.compilationUnit = compilationUnit;
        this.declaration = declaration;
    }

    /**
     * 從 JDT MethodDeclaration 建立 MethodNode。
     *
     * @param compilationUnit JDT CompilationUnit
     * @param declaration MethodDeclaration
     * @param filePath Java 原始檔路徑
     * @param source Java 原始檔內容
     * @return MethodNode
     */
    public static MethodNode from(
            CompilationUnit compilationUnit,
            MethodDeclaration declaration,
            Path filePath,
            String source) {
        IMethodBinding binding = declaration.resolveBinding();

        String packageName = compilationUnit.getPackage() == null
                ? ""
                : compilationUnit.getPackage().getName().getFullyQualifiedName();

        String fallbackClassName = findEnclosingClassName(declaration);
        String fullyQualifiedClassName = resolveClassName(binding, packageName, fallbackClassName);
        String className = simpleClassName(fullyQualifiedClassName, fallbackClassName);

        String methodName = declaration.getName().getIdentifier();
        List<String> parameterTypes = binding == null
                ? readParameterTypesFromSource(declaration)
                : readParameterTypesFromBinding(binding);
        String methodSignature = buildMethodSignature(methodName, parameterTypes);

        int startPosition = declaration.getStartPosition();
        int endPosition = startPosition + declaration.getLength();
        int safeStart = Math.max(0, Math.min(startPosition, source.length()));
        int safeEnd = Math.max(safeStart, Math.min(endPosition, source.length()));
        String sourceCode = source.substring(safeStart, safeEnd);

        int startLine = compilationUnit.getLineNumber(startPosition);
        int endLine = compilationUnit.getLineNumber(Math.max(startPosition, endPosition - 1));

        String bindingKey = binding == null
                ? null
                : binding.getMethodDeclaration().getKey();

        return new MethodNode(
                packageName,
                className,
                fullyQualifiedClassName,
                methodName,
                methodSignature,
                parameterTypes,
                filePath,
                startLine,
                endLine,
                sourceCode,
                bindingKey,
                compilationUnit,
                declaration);
    }

    /**
     * 依照 JDT Method Binding 建立方法簽章。
     *
     * @param binding 方法 Binding
     * @return 方法簽章
     */
    static String signatureFromBinding(IMethodBinding binding) {
        List<String> parameterTypes = readParameterTypesFromBinding(binding);
        return buildMethodSignature(binding.getName(), parameterTypes);
    }

    /**
     * 取得套件名稱。
     *
     * @return 套件名稱
     */
    public String getPackageName() {
        return packageName;
    }

    /**
     * 取得簡單 Class 名稱。
     *
     * @return Class 名稱
     */
    public String getClassName() {
        return className;
    }

    /**
     * 取得完整 Class 名稱。
     *
     * @return 完整 Class 名稱
     */
    public String getFullyQualifiedClassName() {
        return fullyQualifiedClassName;
    }

    /**
     * 取得 Method 名稱。
     *
     * @return Method 名稱
     */
    public String getMethodName() {
        return methodName;
    }

    /**
     * 取得 Method Signature。
     *
     * @return Method Signature
     */
    public String getMethodSignature() {
        return methodSignature;
    }

    /**
     * 取得參數型別完整名稱。
     *
     * @return 參數型別清單
     */
    public List<String> getParameterTypes() {
        return parameterTypes;
    }

    /**
     * 取得原始檔路徑。
     *
     * @return 原始檔路徑
     */
    public Path getFilePath() {
        return filePath;
    }

    /**
     * 取得起始行號。
     *
     * @return 起始行號
     */
    public int getStartLine() {
        return startLine;
    }

    /**
     * 取得結束行號。
     *
     * @return 結束行號
     */
    public int getEndLine() {
        return endLine;
    }

    /**
     * 取得 Method 原始碼。
     *
     * @return Method 原始碼
     */
    public String getSourceCode() {
        return sourceCode;
    }

    /**
     * 取得 JDT Method Binding Key。
     *
     * @return Binding Key，無法解析時回傳 null
     */
    public String getBindingKey() {
        return bindingKey;
    }

    /**
     * 取得 JDT CompilationUnit。
     *
     * @return CompilationUnit
     */
    CompilationUnit getCompilationUnit() {
        return compilationUnit;
    }

    /**
     * 取得 JDT MethodDeclaration。
     *
     * @return MethodDeclaration
     */
    MethodDeclaration getDeclaration() {
        return declaration;
    }

    /**
     * 取得用來識別 Method 的唯一鍵。
     *
     * @return 完整 Class 加 Method Signature
     */
    public String getUniqueKey() {
        return fullyQualifiedClassName + "#" + methodSignature;
    }

    /**
     * 取得適合顯示在樹狀圖的名稱。
     *
     * @return Class.Method Signature
     */
    public String getDisplayName() {
        return className + "." + methodSignature;
    }

    /**
     * 建立呼叫追蹤節點。
     */
    public static final class TraceNode {

        private final MethodNode method;
        private final boolean cycle;
        private final List<TraceNode> children = new ArrayList<>();

        /**
         * 建立追蹤節點。
         *
         * @param method Method
         * @param cycle 是否為循環呼叫
         */
        public TraceNode(MethodNode method, boolean cycle) {
            this.method = method;
            this.cycle = cycle;
        }

        /**
         * 取得 Method。
         *
         * @return Method
         */
        public MethodNode getMethod() {
            return method;
        }

        /**
         * 判斷是否為循環節點。
         *
         * @return true 代表循環
         */
        public boolean isCycle() {
            return cycle;
        }

        /**
         * 加入子節點。
         *
         * @param child 子節點
         */
        public void addChild(TraceNode child) {
            children.add(child);
        }

        /**
         * 取得子節點。
         *
         * @return 子節點清單
         */
        public List<TraceNode> getChildren() {
            return Collections.unmodifiableList(children);
        }
    }

    /**
     * 取得 JDT Binding 的參數完整型別。
     *
     * @param binding 方法 Binding
     * @return 參數型別清單
     */
    private static List<String> readParameterTypesFromBinding(IMethodBinding binding) {
        List<String> result = new ArrayList<>();
        for (ITypeBinding parameterType : binding.getParameterTypes()) {
            result.add(typeName(parameterType));
        }
        return result;
    }

    /**
     * 從 MethodDeclaration 取得參數型別，作為 Binding 無法恢復時的後備資料。
     *
     * @param declaration MethodDeclaration
     * @return 參數型別清單
     */
    private static List<String> readParameterTypesFromSource(MethodDeclaration declaration) {
        List<String> result = new ArrayList<>();
        for (Object parameterObject : declaration.parameters()) {
            SingleVariableDeclaration parameter = (SingleVariableDeclaration) parameterObject;
            String typeName = parameter.getType().toString();
            if (parameter.isVarargs()) {
                typeName += "[]";
            }
            result.add(typeName.replace(" ", ""));
        }
        return result;
    }

    /**
     * 建立 Method Signature。
     *
     * @param methodName Method 名稱
     * @param parameterTypes 完整參數型別
     * @return Method Signature
     */
    private static String buildMethodSignature(String methodName, List<String> parameterTypes) {
        List<String> displayTypes = parameterTypes.stream()
                .map(MethodNode::simpleTypeName)
                .toList();
        return methodName + "(" + String.join(", ", displayTypes) + ")";
    }

    /**
     * 取得 JDT 型別名稱。
     *
     * @param typeBinding 型別 Binding
     * @return 型別完整名稱
     */
    private static String typeName(ITypeBinding typeBinding) {
        if (typeBinding == null) {
            return "?";
        }

        ITypeBinding erasure = typeBinding.getErasure();
        if (erasure.isArray()) {
            return typeName(erasure.getElementType()) + "[]";
        }

        String qualifiedName = erasure.getQualifiedName();
        if (qualifiedName == null || qualifiedName.isBlank()) {
            qualifiedName = erasure.getName();
        }
        return qualifiedName.replace('$', '.');
    }

    /**
     * 取得適合顯示的簡單型別名稱。
     *
     * @param typeName 型別名稱
     * @return 簡單型別名稱
     */
    static String simpleTypeName(String typeName) {
        String normalized = typeName == null ? "?" : typeName.trim();
        int genericStart = normalized.indexOf('<');
        if (genericStart >= 0) {
            normalized = normalized.substring(0, genericStart);
        }

        int arrayStart = normalized.indexOf("[]");
        String suffix = arrayStart >= 0 ? normalized.substring(arrayStart) : "";
        String base = arrayStart >= 0 ? normalized.substring(0, arrayStart) : normalized;
        int lastDot = Math.max(base.lastIndexOf('.'), base.lastIndexOf('$'));
        if (lastDot >= 0) {
            base = base.substring(lastDot + 1);
        }
        return base + suffix;
    }

    /**
     * 解析完整 Class 名稱。
     *
     * @param binding Method Binding
     * @param packageName 套件名稱
     * @param fallbackClassName 後備 Class 名稱
     * @return 完整 Class 名稱
     */
    private static String resolveClassName(
            IMethodBinding binding,
            String packageName,
            String fallbackClassName) {
        if (binding != null && binding.getDeclaringClass() != null) {
            ITypeBinding declaringClass = binding.getDeclaringClass();
            String qualifiedName = declaringClass.getQualifiedName();
            if (qualifiedName != null && !qualifiedName.isBlank()) {
                return qualifiedName.replace('$', '.');
            }
        }

        if (packageName == null || packageName.isBlank()) {
            return fallbackClassName;
        }
        return packageName + "." + fallbackClassName;
    }

    /**
     * 取得簡單 Class 名稱。
     *
     * @param fullyQualifiedName 完整 Class 名稱
     * @param fallback 後備名稱
     * @return 簡單 Class 名稱
     */
    private static String simpleClassName(String fullyQualifiedName, String fallback) {
        if (fullyQualifiedName == null || fullyQualifiedName.isBlank()) {
            return fallback;
        }
        int lastDot = fullyQualifiedName.lastIndexOf('.');
        return lastDot >= 0 ? fullyQualifiedName.substring(lastDot + 1) : fullyQualifiedName;
    }

    /**
     * 找出 Method 所屬的 Class 名稱。
     *
     * @param declaration MethodDeclaration
     * @return Class 名稱
     */
    private static String findEnclosingClassName(MethodDeclaration declaration) {
        var current = declaration.getParent();
        while (current != null) {
            if (current instanceof AbstractTypeDeclaration typeDeclaration) {
                return typeDeclaration.getName().getIdentifier();
            }
            current = current.getParent();
        }
        return "UnknownClass";
    }
}
