# Java Method Trace

這是一個使用 Eclipse JDT Core 的 Java 17 CLI 工具。工具會遞迴掃描指定專案的 `.java` 檔案，透過 JDT Binding 找出專案內 Java Method 的呼叫關係，最後輸出 Markdown 報告。

## 建置

```bash
mvn clean package
```

建置完成後會產生：

```text
target/java-method-trace.jar
```

## CLI 參數

```text
--project  Java 專案根目錄
--class    Class 名稱，可使用簡單名稱或完整名稱
--method   Method 名稱，或完整 Signature
--depth    最大追蹤深度，Root 為第 0 層
--output   輸出 Markdown 檔案，可省略
```

未指定 `--output` 時，會在目前執行目錄產生 `trace.md`。

## Demo

先編譯 Demo：

```bash
mvn -f demo-project/pom.xml clean package
```

再執行 A → B → C 追蹤：

```bash
java -jar target/java-method-trace.jar \
  --project "demo-project" \
  --class "AService" \
  --method "execute" \
  --depth 5 \
  --output "demo-trace.md"
```

預期呼叫樹：

```text
AService.execute()
└─ BService.process()
   └─ CService.calculate()
```

## Overload

如果只輸入 Method 名稱，而同一個 Class 存在多個 overload，工具會列出候選方法並結束，不會自行猜測：

```bash
java -jar target/java-method-trace.jar \
  --project "D:\\workspace\\insurance" \
  --class "PolicyService" \
  --method "updatePolicy(PolicyRequest)" \
  --depth 5 \
  --output "trace.md"
```

## 第一版刻意不處理

- JSP、JavaScript、XML、SQL、MyBatis Mapper
- Spring Runtime Bean 與 Reflection
- Lambda 特殊追蹤、Method Reference、Constructor Trace
- Field Data Flow、Variable Trace、Call Graph UI、HTML、Graphviz、Mermaid

JDK、Spring、Apache Commons、Jackson 等外部 Method 不會往下展開；第一版只追蹤目前專案 Index 中存在 Source Code 的 Method。
