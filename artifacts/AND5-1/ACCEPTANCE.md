# AND5-1 acceptance evidence — NE2210 (44581ee0), Android 0.5.0 (code 2), commit 9578a83

Date: 2026-08-31 21:37–22:00 CST. Gateway: ws://192.168.3.196:8765 (rt_gateway.py, voice-gateway.service), token VOICE_GATEWAY_TOKEN.

## ① 冷启动→首屏 ≤3s — PASS
- `am start -W` after force-stop: `LaunchState: COLD`, **TotalTime 647ms** (token configured, main screen w/ live board)
- No-token cold start (after `pm clear`): TotalTime 1089ms; second cold run 682ms
- Evidence: 01-coldstart-main.png (在线 badge + 票板 data rendered)

## ② 语音锁屏/后台 ≥10min → FGS 存活 + 音频续通 — PASS
- Voice tab active → `VoiceForegroundService` foreground, `types=0x00000080` (microphone), channel=voice, ongoing notification
- Screen off (keyevent 26) at T0=1788183887; sampled WITHOUT waking:
  - t=14s / 195s / 375s / 555s / **735s (12min15s)**: `fgs_records=1`, `ws_conns=1` every sample → 02-lock-fgs-10min.log
- After wake+unlock: same pid (6836), FGS still `isForeground=true`; PTT hold → mic capture frames resume (`tail zero block N/10 sent`, 上行 16 块) → 02c-audio-resume-ptt.png
- Bonus: an unrelated gateway-side drop at ~21:58 (pm_sub_failed TransferEncodingError) hit the board path → honest banner + auto-reconnect (重连中→在线); voice session re-established automatically as s-a87cbffb (retry-on-CONNECTED), FGS unaffected → 06-regression-voice-reconnect.png

## ③ 深链/图标幂等, 不双实例不双WS — PASS
- Manifest: `launchMode="singleTask"` (launch log shows `LAUNCH_SINGLE_TASK`)
- Launcher relaunch → `onNewIntent null — instance reused, no reconnect`
- Deep link `dshpm://launch` → `onNewIntent dshpm://launch — instance reused, no reconnect`
- `pidof dev.dshpm.android` → 1 process
- `ss -tn sport = :8765` → exactly ONE established socket (peer 192.168.3.232) after both relaunches → 03-single-instance-ws.txt

## ④ 无 token → 引导页, 不崩溃 — PASS
- `pm clear` → cold start: `DshPmMain: token blank — gateway connect skipped (onboarding)`, zero network attempts (reconnect early-returns before any socket)
- Guide page renders (title/steps/host/port/token/保存并进入 gated on all fields) → 04-notoken-guide.png; no FATAL/AndroidRuntime in logcat
- Seeded token prefs → next cold start lands in 5-Tab (also proves cold-start restore path)

## Diagnostics (new, spec §3 AND-003 settings)
- Settings 诊断 section: gateway addr, token state, **网关 RTT(app 层 ping): 20 ms** → 07-settings-rtt.png
- RTT = client-side ping→pong timing (Ping has no ts; WS v1 untouched), first pong after each 5s probe

## Launcher icon
- Adaptive icon (vector only, no bitmaps): white mic + cyan waveform on indigo → 05-launcher-icon.png (home screen)

## Regression
- 5-Tab: 票板 (live data, 01), 席位 (06-regression-fleet.png), 语音 (02/02b/02c), 设置 (07) — 流程 code untouched, tab sweep traversed
- AND4 voice chain: session.start ack (在线 s-d090752a → s-a87cbffb after reconnect), PTT capture→uplink frames, playback worker up, interrupt sentinel playback path — all intact
- Red lines: proto/frames zero diff, WS v1 frozen, deps unchanged (no new dependencies)

## Notes / observations (honest)
- `pm_sub_failed` banner at 21:58 was gateway-upstream-side (TransferEncodingError 400), pre-dated this build's changes; app behaved per spec (banner, auto-reconnect, data restored)
- RTT is ≈值 by design: internal 30s heartbeat pongs can alias into a probe (documented in UI)
- Screen state quirk: ColorOS AOD needed keyevent-224 + fingerprint-tap + bottom-swipe to wake; adb-only unlock sequence documented above
