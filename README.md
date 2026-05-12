# LongTermStockPicker（中长线低位反转选股 · 本地测试版）

个人在安卓手机上使用的**本地研究/测试工具**：从 **Tushare Pro** 拉取日线，在端侧做周线/月线聚合与简化评分。**不构成投资建议**。

## 安全与仓库规范

- **GitHub 仅提交源码**：不要把真实 Token、APK、大体量数据提交进仓库。
- Token 放在项目根目录 `local.properties`（已在 `.gitignore` 中忽略），构建时注入 `BuildConfig.TUSHARE_TOKEN`。
- 可参考根目录 `local.properties.example` 自行复制为 `local.properties`。

示例（`local.properties`）：

```properties
TUSHARE_TOKEN=你的真实token
```

若未配置 Token，应用会提示：**请先在 local.properties 配置 TUSHARE_TOKEN**。

## 环境要求

- Android Studio（建议最新稳定版）
- JDK 17
- Android SDK（`compileSdk = 35`）

## 运行

1. 将 `local.properties.example` 复制为 `local.properties`，填入 `TUSHARE_TOKEN`。
2. 用 Android Studio 打开本目录 `LongTermStockPicker`。
3. 同步 Gradle，选择设备或模拟器，运行 `app`。

## 打包 APK（本地）

在 Android Studio：**Build → Build Bundle(s) / APK(s) → Build APK(s)**，或在终端（需本机 Android SDK）执行：

```bash
./gradlew :app:assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/`。该路径已加入 `.gitignore`，请勿把 APK 提交到 GitHub。

## 功能概要

- 首页输入多只股票代码（每行一只，如 `000001.SZ`）、开始/结束日期（默认约最近 10 年）。
- **使用本地缓存** / **强制重新拉取**：日线缓存为 `filesDir/cache/daily/{ts_code}.json`。
- 拉取 Tushare `daily` 后：聚合周线/月线，计算价格低位分、多周期 MACD 分、简化财务安全分、企业性质分（示例映射表），并生成总分排序与详情说明。

## 技术栈

- Kotlin、Jetpack Compose、OkHttp、`kotlinx.serialization`、Gradle Kotlin DSL。

## 免责声明

本工具仅用于个人中长线选股**模型研究**与本地验证，**不构成任何投资建议**。市场有风险，决策需独立判断。
