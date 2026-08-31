# dsh-pm-android

Android 端仓,属 dsh-pm 项目家族。当前阶段:**AND2-3 协议绑定已落**——proto 纯 JVM
模块承载 AND-001 v1 冻结帧集(spec-ws-protocol-v1.md)的编解码与 WS 客户端;app 仍为
Compose 空壳(UI 属 AND-002)。治理(AGENTS.md 等)归属父仓 `dsh-pm`,本仓不设。

## 模块图

```
dsh-pm-android (root)
├── app/    Android application 模块
│           · 单 Activity Compose 空壳;第二行显示 gw 连接状态(仅证 proto 依赖链,UI 留 AND-002)
│           · 权限清单: INTERNET / RECORD_AUDIO / MODIFY_AUDIO_SETTINGS / FOREGROUND_SERVICE
└── proto/  纯 JVM Kotlin library —— AND-001 v1 帧集协议绑定(AND2-3)
    ├── frame/   48 个帧类型密封层级: ClientFrame(18 入向) + ServerFrame(16 应答)
    │            + EventFrame(14 事件 kind);另有 UnknownFrame/MalformedFrame 容忍壳
    │            (媒体 2 向为裸 PCM binary,不走帧类型,见 ws/ 客户端 API)
    ├── codec/   FrameCodec 手写编解码:字段名/语义严格对冻结文档,未知帧/未知字段容忍
    └── ws/      VoiceGatewayClient: auth(token) → 心跳 ping/pong → 断线重连(退避)
                 + session.start{session_id} 续接(TK-001)+ (source,msgid) 去重窗
                 (PM-007 语义: 60s TTL / >1000 截半)+ MsgIdDedup
```

## 技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| Gradle | 8.11.1 | wrapper 复用 `~/.gradle/wrapper/dists` 本地发行包 |
| AGP / Kotlin | 8.2.2 / 1.9.22 | app=android, proto=jvm, JDK 17 |
| Compose | BOM 2024.02.00 / compiler ext 1.5.8 | app 空壳 |
| kotlinx-serialization-json | 1.6.3 | **仅 runtime**(手写 JsonObject 编解码,不用编译器插件/@Serializable) |
| Java-WebSocket | 1.5.7 | WS 传输(选型理由见下) |
| junit | 4.13.2 | test only |

### proto 依赖选型理由(派发契约要求 README 记录)

- **WS 库选 Java-WebSocket 而非 OkHttp**:单 jar、零传递依赖(OkHttp 会拖 okio 等一串);
  且自带 `WebSocketServer`,单测的 mock 网关直接用它零成本搭建。符合"依赖最小择一"。
- **JSON 选 kotlinx-serialization-json(仅 runtime)**:codec 手写在 JsonObject 之上——
  冻结契约要求字段名/可选性/双字段先例(error 帧 code+msg 与 type+message 双发、
  fleet.brief.result 的 camelCase `sessionId`、fleet.cleanup 的连字符键 `binding-cleared`)
  逐字段精确控制,声明式映射反而不透明;只引 runtime 无编译器插件,依赖面最小。

## 构建

前置:JDK 17;`local.properties`(已 gitignore)指向本机 SDK:

```properties
sdk.dir=/home/yy/Android/Sdk
```

```bash
./gradlew :proto:test                     # 纯 JVM 单测(默认 31 例,含 1 例集成测默认跳过)
./gradlew :app:assembleDebug              # APK: app/build/outputs/apk/debug/app-debug.apk
```

### 集成测开关(@Integration,默认跳过)

对真网关(voice-gateway.service, ws://127.0.0.1:8765/ws)的集成测默认跳过,开启方式二选一:

```bash
DASHPM_INTEGRATION=1 ./gradlew :proto:test
./gradlew :proto:test -Pintegration=true
```

token 取 `~/.config/voice-gateway/env` 的 `VOICE_GATEWAY_TOKEN`;断言
`auth.ok.proto == "v1"`(WSP-001 冻结标记)+ 应用层 ping/pong。本仓已实证两形态:
开关开=连通过(skipped=0),无开关=按约跳过(skipped=1)。

## v1 帧集绑定注记

- 冻结语义"只增不改"按 spec §0 消费者义务落实:未知 `t` → `UnknownFrame`(不断不崩),
  未知字段忽略,坏 JSON → `MalformedFrame`;前向兼容有专测。
- 实验区帧(orch.ack/gate/metrics,payload 待 WSP-002)绑定为 raw payload 透传。
- `pm.req/pm.res/pm.sub/pm.unsub` 的 `id` 为 string|int,绑定为 `JsonPrimitive` 保形。
- 重连恢复:非 observe 会话断线后自动 `session.start{session_id}` 重播种(TK-001);
  pm.event 重放经 `(source,msgid)` 去重窗对账,每事件恰好交付一次(PM-007)。
- 红线:proto 模块零 Android 依赖(纯 JVM);帧字段名/语义严格对冻结文档不改义。

## Remote

待补:本仓目前仅本地 git,远端未配置,待治理侧(dsh-pm)确定托管位置后补
`git remote add origin …`。
