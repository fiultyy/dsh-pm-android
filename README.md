# dsh-pm-android

Android 端骨架仓,属 dsh-pm 项目家族。本仓当前为**纯空壳骨架**:只证明
Kotlin + Jetpack Compose 构建链在本地环境跑通,不含任何业务逻辑。
业务(语音管道、帧编解码等)由后续任务(AND2-3 起)在此骨架上生长。
治理(AGENTS.md 等)归属父仓 `dsh-pm`,本仓不设。

## 模块图

```
dsh-pm-android (root)
├── app/    Android application 模块
│           · 单 Activity Compose 空壳(HelloScreen,仅证构建链)
│           · 权限清单: INTERNET / RECORD_AUDIO / MODIFY_AUDIO_SETTINGS
│             / FOREGROUND_SERVICE(为后续语音管道预留,当前无代码申请)
│           · 依赖 proto(仅模块图接线,未使用其任何符号)
└── proto/  纯 JVM Kotlin library,零依赖
            · 为 AND2-3 的帧编解码(frame codec)预留
            · 当前仅含编译占位(Placeholder),无任何业务代码
```

## 技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| Gradle | 8.11.1 | wrapper 已生成,复用 `~/.gradle/wrapper/dists` 本地发行包 |
| AGP | 8.2.2 | com.android.tools.build:gradle |
| Kotlin | 1.9.22 | app 用 kotlin-android,proto 用 kotlin-jvm |
| Compose | BOM 2024.02.00 / compiler ext 1.5.8 | 与 Kotlin 1.9.22 配对 |
| JDK | 17 | compileOptions / jvmTarget / jvmToolchain 均为 17 |
| SDK | compileSdk 34 / minSdk 26 / targetSdk 34 | 对齐本机 `~/Android/Sdk` |

依赖红线:Maven 第三方依赖仅限上表最小集(androidx core/activity/compose),
proto 模块零依赖。

## 构建

前置:JDK 17;`local.properties` 指向本机 SDK(已 gitignore):

```properties
sdk.dir=/home/yy/Android/Sdk
```

常用命令:

```bash
./gradlew :app:assembleDebug      # 出 APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:compileDebugKotlin # 只验 Kotlin 编译链
./gradlew :proto:jar              # 纯 JVM 协议模块出 jar
```

骨架验收实况(2026-08-31):`./gradlew :app:assembleDebug` →
`BUILD SUCCESSFUL in 1m 17s / 36 actionable tasks: 36 executed`。

## Remote

待补:本仓目前仅本地 git(init 于 2026-08-31),远端 remote 尚未配置,
待治理侧(dsh-pm)确定托管位置后补 `git remote add origin …`。

## 红线(承派发契约)

- 零业务逻辑:骨架纯空壳,一切功能留待后续任务。
- 最小依赖:不引入超出上表最小集的 Maven 依赖。
- `local.properties` 不入库。
