# Java Method Trace

這是一個使用 Eclipse JDT Core 的 Java 11 CLI 工具。工具會遞迴掃描指定專案的 `.java` 檔案，透過 JDT Binding 找出專案內 Java Method 的呼叫關係，最後輸出 Markdown 報告。

## 建置

```bash
mvn clean package
```

執行測試：

```bash
mvn test
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
--direction 追蹤方向，可省略；down 為往下找被呼叫 Method，up 為往上找呼叫者
--output   輸出 Markdown 檔案，可省略
--encoding Java 原始檔編碼，可省略，預設 UTF-8，例如 MS950 或 Big5
--config   YAML 設定檔，可省略；CLI 參數會覆蓋 YAML 同名設定
```

未指定 `--output` 時，會在目前執行目錄產生 `trace.md`。

## YAML 設定檔

可複製根目錄的 `trace.example.yml`，修改後執行：

```bash
java -jar target/java-method-trace.jar --config "trace.yml"
```

YAML 支援以下欄位：

```yaml
project: "D:/workspace/insurance"
class: "PolicyService"
method: "updatePolicy(PolicyRequest)"
depth: 5
direction: "down"
encoding: "UTF-8"
output: "trace.md"
```

也可以用 CLI 覆蓋 YAML 設定，例如：

```bash
java -jar target/java-method-trace.jar \
  --config "trace.yml" \
  --direction "up" \
  --depth 10
```

相對路徑會以目前執行 CLI 的目錄為基準。YAML 設定檔使用 UTF-8；Windows 路徑建議使用 `/`，避免反斜線跳脫問題。

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
  --direction "down" \
  --encoding "UTF-8" \
  --output "demo-trace.md"
```

預期呼叫樹：

```text
AService.execute()
└─ BService.process()
   └─ CService.calculate()
```

向上追蹤 CService 的呼叫者：

```bash
java -jar target/java-method-trace.jar \
  --project "demo-project" \
  --class "CService" \
  --method "calculate" \
  --depth 5 \
  --direction "up" \
  --output "demo-up-trace.md"
```

預期呼叫樹：

```text
CService.calculate()
└─ BService.process()
   └─ AService.execute()
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

## Source Folder 與編碼

工具會依每個 Java 檔的 `package` 宣告推算 Source Root，因此可支援 Maven、Gradle、Eclipse `src` 與自訂 Source Folder。若舊專案不是 UTF-8，請指定 `--encoding "MS950"` 或實際使用的 Charset。
