# 应用更新隐藏器（AppUpdateHider）

> 一个 **LSPosed 模块**：在应用商店 / 应用市场中隐藏指定应用的更新信息，让商店不再把它们列为「可更新」，也不再提示更新。

- 只作用于**应用商店进程**，不拦截安装、不改动系统行为
- 支持 ColorOS / OPPO、vivo、华为、荣耀、小米、三星、魅族、联想、Google Play、Amazon、Aurora、F-Droid、Aptoide、Steam、RuStore 等常见商店
- 同时使用「列表过滤 / 视为未安装 / 版本号伪装」三层手段，兼容大多数商店的检测方式

---

## 功能

| 场景 | 效果 |
|---|---|
| 商店「应用更新」列表 | 被勾选的应用不再出现 |
| 商店内的版本检测 | 视为已是最新版本，不提示更新 |
| 安装/更新行为 | **不拦截**（本模块只负责隐藏更新信息） |

## 工作原理

在应用商店进程（如 ColorOS 的 `com.heytap.market`）里 hook 它的 `PackageManager` 查询：

| Hook 点 | 处理 |
|---|---|
| `getInstalledPackages` / `getInstalledApplications` | 从结果中过滤掉黑名单应用 |
| `getPackageInfo` / `getApplicationInfo` | 抛 `NameNotFoundException`（视为未安装） |
| `getLaunchIntentForPackage` | 返回 `null` |
| `queryIntentActivities` / `queryIntentServices` / `queryBroadcastReceivers` / `queryContentProviders` | 过滤黑名单 |
| `PackageInfo.getLongVersionCode()` | 返回 `Long.MAX_VALUE`（版本号伪装，商店认为已是最新） |
| `IPackageManager$Stub$Proxy` 的已安装列表查询 | 覆盖系统商店绕过 `ApplicationPackageManager` 的 Binder 直连路径 |

关键实现细节：

- 清单声明 **`xposedsharedprefs=true`**：LSPosed 才会把模块配置暴露给商店进程，否则 Android 10+ 的私有目录 + SELinux 会让黑名单读取失败；
- 初始化延迟到 **`ContextWrapper.attachBaseContext`**：此时类加载完整、`Context` 可用，hook 最可靠；
- 黑名单读取以 **`XSharedPreferences`** 为主、**`ContentProvider`** 为兜底；
- `xposedscope` 用资源数组列出常见商店，LSPosed 作用域页会标为「推荐」。

## 推荐作用域

模块已内置以下商店包名（只对本机已安装的显示「推荐」，未安装的不会出现）：

| 类别 | 商店 |
|---|---|
| 国内厂商 | OPPO / 一加 / realme、vivo / iQOO、华为、荣耀、小米 / Redmi、三星、魅族、联想 |
| 国内第三方 | 腾讯应用宝、360 手机助手、百度手机助手、豌豆荚 |
| 海外 | Google Play、Amazon Appstore、Aurora Store、F-Droid、Aptoide、Steam、RuStore |

## 安装与使用

1. 安装 `AppUpdateHider.apk`（或自行构建）；
2. **LSPosed Manager → 模块**：启用「应用更新隐藏器」；
3. **作用域**：勾选**应用商店**（推荐项，或搜索 `com.heytap.market`）；**不需要勾系统框架**；
4. **重启手机**（首次启用必须）；
5. 打开「应用更新隐藏器」，勾选要隐藏更新信息的应用；
6. 若商店之前已缓存“可更新”列表，进入 **设置 → 应用 → 应用商店 → 存储 → 清除数据**，让商店重新扫描。

## 构建

### GitHub Actions 自动生成 Debug APK（推荐，无需本地环境）

仓库自带 `.github/workflows/build.yml`，推上 GitHub 后即生效：

| 触发方式 | 结果 |
|---|---|
| 推送到 `main` / `master` | 自动构建 debug APK，去 **Actions → Build Debug APK → 对应运行 → Artifacts** 下载 `AppUpdateHider-debug` |
| Pull Request | 同上，用于验证改动能否编译通过 |
| 手动 **Run workflow** | 同上 |
| 推送 `v*` tag（如 `v1.0`） | 额外自动创建 **GitHub Release**，把 debug APK 作为附件发布，可直接分享下载链接 |

工作流自动准备 JDK 17、Android SDK、Gradle 缓存，产物固定在 `app/build/outputs/apk/debug/AppUpdateHider-<versionName>-debug.apk`，并会执行 `apksigner verify` 校验签名。

> **签名为什么固定**：仓库内置 `keystore/debug.keystore`（标准 Android debug key，仅用于测试分发），`app/build.gradle` 已指定使用它。这样每次 CI 构建出来的 APK 签名一致，新包能直接覆盖安装旧包，不会出现「应用未安装 / 签名不一致」。要正式签名时，换成自己的 keystore 即可。

### 本地构建（可选）

用 Android Studio 打开本目录，等待 Gradle 同步完成：

```
Build → Build Bundle(s) / APK(s) → Build APK(s)
```

或用命令行（需要 JDK 17 和 Android SDK）：

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/AppUpdateHider-1.0-debug.apk
```

> `app/libs/api-82.jar` 是传统 Xposed API 的编译期 stub（仅编译用，不会打包进 APK），运行期由 LSPosed 提供实现。



## 上传到 GitHub

```bash
cd AppUpdateHider-Source
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/<你的用户名>/AppUpdateHider.git
git push -u origin main
```

## 项目结构

```
AppUpdateHider/
├── .github/workflows/build.yml     # GitHub Actions：自动构建 debug APK（tag 时发 Release）
├── .gitignore
├── LICENSE                         # MIT
├── README.md
├── keystore/debug.keystore         # 固定 debug 签名，保证每次产物可覆盖安装
├── build.gradle
├── settings.gradle
├── gradle.properties
├── gradlew / gradlew.bat
├── gradle/wrapper/                 # Gradle Wrapper（8.9）
└── app/
    ├── build.gradle
    ├── proguard-rules.pro
    ├── libs/api-82.jar             # Xposed API 编译期 stub
    └── src/main/
        ├── AndroidManifest.xml     # xposedmodule / xposedsharedprefs / xposedscope
        ├── assets/xposed_init      # 模块入口类
        ├── java/com/appupdatehider/
        │   ├── AppUpdateHider.java # 模块入口 + 隐藏/版本伪装
        │   ├── SettingsActivity.java
        │   └── BlockedProvider.java
        └── res/
            ├── layout/
            ├── values/             # strings / arrays(推荐作用域) / styles
            └── mipmap-*/
```

## 兼容性

| 项目 | 支持情况 |
|---|---|
| Android | 重点支持 12–16；11 及以下未适配 |
| CPU | arm / arm64 / x86 / x64 |
| 框架 | LSPosed（传统 Xposed API） |
| 应用商店 | ColorOS / OPPO、vivo、华为、荣耀、小米、三星、魅族、联想、Google Play、Amazon、Aurora、F-Droid、Aptoide、Steam、RuStore 等 |

## 注意事项

- 本模块**只隐藏更新信息，不阻止安装**。
- 建议只对确有“新版不好用”的应用做隐藏；长期不更新（尤其银行、支付类）会有安全更新滞后风险。
- 若商店仍显示可更新，依次检查：
  1. 作用域是否勾了**应用商店**（包名是否正确）；
  2. LSPosed 日志（搜 `AppUpdateHider`）是否出现 `handleLoadPackage: <商店包名>`；
  3. 是否出现 `Block list via XSharedPreferences, size=N`（N 应为正数）；
  4. 是否出现 `Hide hooks installed`；
  5. 商店是否缓存了旧列表（清数据后重试）。

## 免责声明

本项目仅供学习与技术研究使用。使用本模块造成的任何后果（包括但不限于应用无法更新、商店异常、设备问题）由使用者自行承担。请遵守当地法律法规与应用商店服务条款。

## License

[MIT](LICENSE)
