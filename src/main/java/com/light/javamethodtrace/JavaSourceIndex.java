package com.light.javamethodtrace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.jdt.core.dom.IMethodBinding;

/**
 * 簡單的 Java Method Index。
 */
public final class JavaSourceIndex {

    private final List<MethodNode> methods = new ArrayList<>();
    private final Map<String, List<MethodNode>> methodsByBindingKey = new HashMap<>();
    private final Map<String, List<MethodNode>> methodsByUniqueKey = new HashMap<>();

    /**
     * 加入一個 Method。
     *
     * @param method MethodNode
     */
    public void add(MethodNode method) {
        methods.add(method);
        addToMap(methodsByUniqueKey, method.getUniqueKey(), method);
        if (method.getBindingKey() != null && !method.getBindingKey().isBlank()) {
            addToMap(methodsByBindingKey, method.getBindingKey(), method);
        }
    }

    /**
     * 依照 Class 名稱與 Method 名稱或完整 Signature 找候選方法。
     *
     * @param className Class 名稱，可為簡單名稱或完整名稱
     * @param methodSpec Method 名稱或完整 Signature
     * @return 候選方法
     */
    public List<MethodNode> findCandidates(String className, String methodSpec) {
        MethodQuery query = MethodQuery.parse(methodSpec);
        List<MethodNode> result = methods.stream()
                .filter(method -> classMatches(method, className))
                .filter(method -> method.getMethodName().equals(query.methodName()))
                .filter(method -> !query.hasParameterList()
                        || parameterTypesMatch(method, query.parameterTypes()))
                .sorted(Comparator.comparing(MethodNode::getMethodSignature))
                .collect(Collectors.toList());
        return result;
    }

    /**
     * 依照方法 Binding 找到專案內的 Method。
     *
     * @param binding 呼叫端取得的 Method Binding
     * @return 專案內 Method，外部方法或無法解析時回傳 null
     */
    public MethodNode findByBinding(IMethodBinding binding) {
        if (binding == null) {
            return null;
        }

        IMethodBinding declaration = binding.getMethodDeclaration();
        String bindingKey = declaration.getKey();
        List<MethodNode> bindingMatches = methodsByBindingKey.get(bindingKey);
        if (bindingMatches != null && !bindingMatches.isEmpty()) {
            return bindingMatches.get(0);
        }

        if (declaration.getDeclaringClass() == null) {
            return null;
        }

        String qualifiedClassName = declaration.getDeclaringClass().getQualifiedName();
        String methodSignature = MethodNode.signatureFromBinding(declaration);
        List<MethodNode> signatureMatches = methodsByUniqueKey.get(
                qualifiedClassName.replace('$', '.') + "#" + methodSignature);
        if (signatureMatches == null || signatureMatches.isEmpty()) {
            return null;
        }
        return signatureMatches.get(0);
    }

    /**
     * 取得 Index 中的 Method 數量。
     *
     * @return Method 數量
     */
    public int size() {
        return methods.size();
    }

    /**
     * 判斷 Class 名稱是否相符。
     *
     * @param method Method
     * @param className 查詢 Class 名稱
     * @return 是否相符
     */
    private static boolean classMatches(MethodNode method, String className) {
        return method.getClassName().equals(className)
                || method.getFullyQualifiedClassName().equals(className);
    }

    /**
     * 比對輸入的參數型別與 JDT Index 型別。
     *
     * @param method Method
     * @param requestedTypes 輸入參數型別
     * @return 是否相符
     */
    private static boolean parameterTypesMatch(MethodNode method, List<String> requestedTypes) {
        List<String> actualTypes = method.getParameterTypes();
        if (actualTypes.size() != requestedTypes.size()) {
            return false;
        }

        for (int index = 0; index < actualTypes.size(); index++) {
            String actual = normalizeType(actualTypes.get(index));
            String requested = normalizeType(requestedTypes.get(index));
            if (!actual.equals(requested)
                    && !MethodNode.simpleTypeName(actual).equals(MethodNode.simpleTypeName(requested))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 正規化輸入的型別名稱，以支援簡單名稱、完整名稱與泛型寫法。
     *
     * @param typeName 型別名稱
     * @return 正規化型別名稱
     */
    private static String normalizeType(String typeName) {
        String normalized = typeName.trim()
                .replace("...", "[]")
                .replace(" ", "")
                .replace('$', '.');

        StringBuilder result = new StringBuilder();
        int genericDepth = 0;
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (character == '<') {
                genericDepth++;
            } else if (character == '>') {
                genericDepth = Math.max(0, genericDepth - 1);
            } else if (genericDepth == 0) {
                result.append(character);
            }
        }
        return result.toString();
    }

    /**
     * 將 Method 加入索引 Map。
     *
     * @param map 索引 Map
     * @param key 索引鍵
     * @param method Method
     */
    private static void addToMap(Map<String, List<MethodNode>> map, String key, MethodNode method) {
        map.computeIfAbsent(key, ignored -> new ArrayList<>()).add(method);
    }

    /**
     * Method 查詢條件。
     *
     * @param methodName Method 名稱
     * @param parameterTypes 參數型別
     * @param hasParameterList 是否有指定參數清單
     */
    private static final class MethodQuery {

        private final String methodName;
        private final List<String> parameterTypes;
        private final boolean hasParameterList;

        /**
         * 建立 Method 查詢條件。
         *
         * @param methodName Method 名稱
         * @param parameterTypes 參數型別
         * @param hasParameterList 是否有指定參數清單
         */
        private MethodQuery(
                String methodName,
                List<String> parameterTypes,
                boolean hasParameterList) {
            this.methodName = methodName;
            this.parameterTypes = parameterTypes;
            this.hasParameterList = hasParameterList;
        }

        /**
         * 解析 Method 名稱或完整 Signature。
         *
         * @param methodSpec 原始輸入
         * @return 查詢條件
         */
        private static MethodQuery parse(String methodSpec) {
            String value = methodSpec.trim();
            int openParenthesis = value.indexOf('(');
            if (openParenthesis < 0) {
                return new MethodQuery(value, List.of(), false);
            }

            if (!value.endsWith(")")) {
                throw new IllegalArgumentException("Method Signature 必須以 ) 結尾：" + methodSpec);
            }

            String methodName = value.substring(0, openParenthesis).trim();
            if (methodName.isBlank()) {
                throw new IllegalArgumentException("Method 名稱不可為空：" + methodSpec);
            }

            String parameterText = value.substring(openParenthesis + 1, value.length() - 1).trim();
            if (parameterText.isBlank()) {
                return new MethodQuery(methodName, List.of(), true);
            }

            return new MethodQuery(methodName, splitParameterTypes(parameterText), true);
        }

        /**
         * 將參數清單切成型別名稱。
         *
         * @param parameterText 參數文字
         * @return 參數型別
         */
        private static List<String> splitParameterTypes(String parameterText) {
            List<String> result = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            int genericDepth = 0;

            for (int index = 0; index < parameterText.length(); index++) {
                char character = parameterText.charAt(index);
                if (character == '<') {
                    genericDepth++;
                } else if (character == '>') {
                    genericDepth = Math.max(0, genericDepth - 1);
                }

                if (character == ',' && genericDepth == 0) {
                    result.add(current.toString().trim());
                    current.setLength(0);
                } else {
                    current.append(character);
                }
            }

            if (current.length() > 0) {
                result.add(current.toString().trim());
            }
            return result;
        }

        /**
         * 取得 Method 名稱。
         *
         * @return Method 名稱
         */
        private String methodName() {
            return methodName;
        }

        /**
         * 取得參數型別清單。
         *
         * @return 參數型別清單
         */
        private List<String> parameterTypes() {
            return parameterTypes;
        }

        /**
         * 判斷是否有指定參數清單。
         *
         * @return true 代表有指定參數清單
         */
        private boolean hasParameterList() {
            return hasParameterList;
        }
    }
}
