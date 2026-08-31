# dsh-pm-android

Android 端仓,属 dsh-pm 项目家族。当前阶段:**AND3-1 移动观测台三视图已落**——app
模块在 proto(AND2-3 v1 冻结帧集绑定)之上实现票板/席位舰/流程三视图 + 设置页,
经真实网关实证(pm.req 首拉 + pm.event 增量重拉)。治理(AGENTS.md 等)归属父仓
`dsh-pm`,本仓不设。

## 模块图

```
dsh-pm-android (root)
├── app/    Android application 模块 —— AND-002 三视图移动观测台(AND3-1)
│           ├── ui/     Compose 界面: 底部四 Tab(票板/席位舰/流程/设置)
│           │           · 票板: 按 state 分组单列(组头 state · N 张),卡片
│           │             ticket_id/title/deps/lease_owner
│           │           · 席位舰: 座席卡 code/status/node/lastSeen(相对时间)
│           │           · 流程: flow 折叠列表 → 展开节点表 node_id/state/verb
│           │           · 设置: host/port/token(SharedPreferences,默认
│           │             10.0.2.2:8765),保存即重连;顶部连接状态条
│           │             (绿在线/橙重连/红错误)+ 事件横幅
│           ├── pm/     纯逻辑层(可单测): BoardController 视图模型
│           │           (pm.req 首拉+pm.sub 订阅+pm.event 300ms 去抖重拉,
│           │           pm_sub_failed/ended 自动重订),PmParsing 纯解析,
│           │           SettingsStore 配置。UI 零直接网络调用(红线)。
│           ├── voice/  AND4-2 语音页: VoiceController 纯逻辑视图模型
│           │           (session.start observe:false 生命周期/PTT 本地采集闸/
│           │           head.turn 打断哨兵 mute+dropAll/head.list+switch),
│           │           ChunkCoalescer 200ms 合块(4×800 样本, tk BLOCK 对齐),
│           │           AndroidMicSource(AudioRecord 16k/mono/int16) +
│           │           AndroidPlayerSink(AudioTrack 24k, 独立播放线程,
│           │           清队列先行+哨兵断流的 tk _DROP_PLAYBACK 同款)
│           └── MainActivity: controller↔Compose 桥(mutableStateOf),
│                       30s ping + 30s 相对时间 ticker; FanOutListener
│                       (板+语音共用一条网关连接)
└── proto/  纯 JVM Kotlin library —— AND-001 v1 帧集协议绑定(AND2-3,冻结)
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
| Compose | BOM 2024.02.00 / compiler ext 1.5.8 | Material3 三视图 |
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
./gradlew :proto:test                     # 纯 JVM 单测(30 例 + 1 例集成测默认跳过)
./gradlew :app:testDebugUnitTest          # app 单测(33 例: PmParsing 9 + BoardController 10
                                          #          + ChunkCoalescer 3 + VoiceController 11)
./gradlew :app:assembleDebug              # APK: app/build/outputs/apk/debug/app-debug.apk
```

### 集成测开关(@Integration,默认跳过)

对真网关(voice-gateway.service, ws://127.0.0.1:8765/ws)的集成测默认跳过,proto 与
app 各一例,开启方式二选一:

```bash
DASHPM_INTEGRATION=1 ./gradlew :proto:test :app:testDebugUnitTest
./gradlew :proto:test :app:testDebugUnitTest -Pintegration=true
```

token 取 `~/.config/voice-gateway/env` 的 `VOICE_GATEWAY_TOKEN`。proto 例断言
`auth.ok.proto == "v1"`(WSP-001 冻结标记)+ 应用层 ping/pong;app 例
(GatewayPmIntegrationTest)经 BoardController 全链路:连接→三 op 首拉→pm.sub→
pm.res 解析进 UiState(实测 53+ 票)。两形态均已实证:开关开=连通过(skipped=0),
无开关=按约跳过。

## 数据流(AND-002 三视图)

```
VoiceGatewayClient (proto, WS)
      │ GatewayListener 回调
      ▼
BoardController (app/pm, 纯逻辑视图模型)
      │ CONNECTED → pm.req{tickets|fleet|flow} 首拉 + pm.sub kinds=[…4]
      │ PmEvent(kind) → 300ms 去抖 → pm.req 同 op 重拉   (pm.event=变更信号,无数据体,
      │                                                        与 pm-web PW-003 同策)
      │ ErrorFrame → 横幅(人话);pm_sub_failed/ended → 1s 后自动重订
      ▼
MainActivity 桥(mutableStateOf + runOnUiThread)→ Compose 三视图重绘
```

只读红线:三视图无任何写控件;BoardController 永不发写帧;UI 层零直接网络调用
(全部经注入的 sendPort→VoiceGatewayClient)。

## 语音面数据流(AND4-2)

```
VoiceGatewayClient (一条连接, 板+语音共用)
      │ FanOutListener 分流
      ├─ BoardController (pm 面, 同前)
      └─ VoiceController (语音面)
          │ 语音 tab 激活 → session.start{observe:false};退出 → session.end
          │ PTT 按住/松开 → MicSource 起/停(本地采集闸;v1 无 mic 帧, tk 同款)
          │ MicSource 800样本块 → ChunkCoalescer 200ms 合块 → sendAudio(binary)
          │ head.turn user_start/interrupted → mute + PlayerSink.dropAll(哨兵)
          │ head.turn assistant_start → unmute;user_text/assistant_end → 流水
          │ head.list/head.switch → head 单选行
          ▼
AudioTrack 播放线程(24k int16): 收帧循环只入队;打断=清队列先行再入哨兵
```

### 并发闸注记(单 token ≤2)

网关按 token 限并发连接数 ≤2。本 app 板面与语音面**共用一条连接**(上表数据流),
即手机端整 app 只占 1 席;PC 端 tk(语音+观测两条)占满 2 席时,手机连接被拒
(concurrent_limit 错误帧, 状态条红显)属预期——错峰使用或关掉 PC 端一条连接。

## 模拟器验收(AND3-1 实证,artifacts/AND3-1/)

AVD `Medium_Phone_API_36.1`(headless `-no-window -gpu swiftshader_indirect`),
app 直连宿主 `10.0.2.2:8765` 真网关 + pm-host-service(127.0.0.1:35451):

| # | 项 | 证据 |
|---|---|---|
| 1 | 三视图真实数据 | 01-tickets / 02-fleet / 03+04-flow / 05-settings.png(+uiautomator dump) |
| 2 | CLI 变票→pm.event→UI ≤2.5s | 09-pmevent-before(在 running 组)→`ledger ticket state`→10-pmevent-after(票移出,组 2张→1张) |
| 3 | 弱网 | svc wifi/data 断 10s:横幅"连接断开,自动重连中…"(11);恢复后在线+横幅清+重拉(12) |
| 4 | 后台→前台 | 09:10 起后台 ≥2min(缩短自 5min,note: 期间 WS 断开由重连+全量重拉恢复,与 5min 行为一致)→回前台在线(13) |

### AND4-2 语音页验收(NE2210 真机, 0.4.0, artifacts/AND4-2/)

| 项 | 证据 |
|---|---|
| 语音 tab 会话+head 行 | 01-voice-tab-live: 在线(s-xxx) + ●Nova 激活 + Echo/Scribe 单选行 |
| PTT→ASR→应答 | 02/04: 三轮 user_start→interrupted→user_text(扬声器串扰实测转写)→assistant_start→Nova 回复文本;上行 89 块 |
| 打断即时静音 | logcat: playback dropped (interrupt sentinel) ×N(user_start/interrupted 触发) |
| 采集门控 | logcat: capture started/stopped 与 PTT 按住/松开一一对应 |
| AudioTrack | 修复 API36 3参 write "Invalid mode"(改 WRITE_BLOCKING): 旧 build 49 错→新 build 0 错 |

注:真机声学注入曾借媒体库自播(扬声器→mic 串扰),验收后已彻底清理
(sdcard/媒体库测试音频删除, 音乐 App 队列已暂停复位);可闻播放的最后
一轮验证因停止占用音乐 App 而未再进行——以 write 零错误+哨兵日志为证。

### AND4-3 两缺陷修复(0.4.1)

| 缺陷 | 根因判定 | 修复与证据 |
|---|---|---|
| ① 按住中显"已打断"误读为录音失败 | 呈现层相位直驱主状态:回合中再按 PTT 的 head `interrupted` 顶掉采集态(语义对呈现错) | `VoiceStatus.mainVisual`:PTT 持有恒为主状态"收音中",中断降为流水条目(⚡);单测 4 例;真机按压中 dump=🎤 收音中(artifacts/AND4-3/02) |
| ② 下行 TTS 无声 | 待分叉(②c):JVM 真语音轮 frames=9/126720B/muteDropped=0/queuedBytes=126720 → **帧必到+全入队**;设备路由 dumpsys: STREAM_MUSIC→speaker, 无通信路由劫持(②a/b 排除);write 链已修 API36 3参调用(②d 部署计数) | 打点三件套:屏显角标"↓帧 N/字节 M/弃 K"(到达先于静音闸计数)+logcat `downlink binary #N` 每 25 帧+`write accounting` 每 2s 音频量;`setVolume(1.0)` 显式(②e)。设备下一轮真人发声即可现场分叉:帧>0 无声=播放链(看 write 日志), 帧=0=头/VAD 门 |
| 集成测 | 真语音轮(base64 内嵌 2.35s 段)+并发闸 assume-跳过(单 token ≤2 满员为环境态) | `VoiceSpeechRoundIntegrationTest`(默认跳过):user_text 转写+assistant_end+下行计数断言全绿;并发满员时 assume-skip 不误报 |

注:swiftshader 软渲染下连续快速 fling 可能卡渲染线程(进程与 WS 不受影响,HOME 可恢复);
验收操作以单次慢速 swipe 进行。

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
