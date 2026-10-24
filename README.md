# AirPlaySink

一个**只做音频**的 AirPlay（RAOP / AirTunes）接收端 Android 应用，目标设备是比亚迪车机。

车机连上 iPhone 的个人热点后，打开应用里的开关，车机就会作为一台"音箱"出现在 iPhone 的控制中心 / 音乐 App 的 AirPlay 设备列表里，手机上播放的音频直接送到车机的音响。

---

## 范围：什么做了，什么故意没做

| 类别 | 状态 |
| --- | --- |
| AirPlay 1 音频接收（RAOP，`_raop._tcp`） | ✅ 完整实现 |
| ALAC 解码（352 帧/包，16bit，44.1kHz，双声道） | ✅ 内置解码器 |
| AAC-LC 解码（1024 帧/包，44.1/48kHz，双声道，AirPlay 2） | ✅ 走系统 `MediaCodec` |
| AES-128-CBC 音频解密 + RSA-OAEP 会话密钥解封 | ✅ |
| RTP 收流、乱序重排、缺包重传（resend） | ✅ |
| RTSP 控制：OPTIONS / ANNOUNCE / SETUP / RECORD / FLUSH / TEARDOWN / PAUSE / GET·SET_PARAMETER | ✅ |
| DMAP 元数据（标题 / 艺术家 / 专辑）与 `progress:` | ✅ |
| 音量控制（`volume: -xx.xx` dB） | ✅ |
| **DACP 反向控制**（用方向盘 / 界面按钮遥控 iPhone 切歌、播放暂停） | ✅ 仅 **AirPlay 1** |
| **AP2 反向控制**（`sendMediaRemoteCommand`，走事件通道） | ✅ 默认开启，见下文 |
| **快进 / 快退**（长按上一首 / 下一首，一次一步） | ✅ AP2 用命令 8/9、10/11；AP1 用 DACP `beginff`/`beginrew` + `playresume` |
| 前台服务 + Wi-Fi Lock + Multicast Lock，息屏继续接收 | ✅ |
| 开机自启（可选） | ✅ |
| 进程被杀后自动恢复（START_STICKY + 网络事件拉起） | ✅ |
| 电池优化豁免（界面申请 + adb 白名单说明） | ✅ |
| 日志落盘文件（自动轮转） | ✅ |
| **AirPlay 2 纯音频**（`_airplay._tcp` 广播、`/info`、transient pairing、加密控制通道、realtime + buffered 音频） | ✅ 默认开启 |
| **视频 / 屏幕镜像** | ❌ 全部去掉 |
| **USB / Wi-Fi Direct / 车机开热点 / VPN / 蓝牙 RFCOMM / iAP2 / MFi** | ❌ 全部去掉 |
| 有线音频、多房间、AirPlay 密码（`pw=true`） | ❌ |

**唯一的连接方式是：车机连接 iPhone 的个人热点。**

---

## 与 DiPlay 的关系

本项目是**独立新建**的工程，`DiPlay`（`airplay` 分支）**一行未改**。

DiPlay 里的 "AirPlay" 实际是 **CarPlay 传输层**：在 `AirPlaySession` 之前还有 mDNS `_carplay-ctrl._tcp` 发现、蓝牙 RFCOMM、iAP2 认证、MFi `/auth-setup`，并且挂着完整的视频管线。要把它裁剪成"只收音+只走热点"的音频接收端，等于重写整条链路，所以另起项目更干净。

---

## 工程结构

```
app/src/main/java/com/shilapi/airplaysink/
├── MainActivity.kt            开关 + 状态卡 + 车机名称 + 实时日志
├── SinkService.kt             前台服务：持有 Wi-Fi/Multicast Lock，驱动接收器
├── SinkModel.kt               服务 → UI 的唯一共享状态（线程安全）
├── SinkPreferences.kt         设备名、型号、版本号、deviceId、开机自启
├── BootReceiver.kt            开机后按设置自动拉起服务
├── raop/
│   ├── RaopReceiver.kt        RTSP 控制端口（5000-5005）+ mDNS 广播，单客户端语义
│   └── RaopSession.kt         一次控制连接的全部方法分派
├── audio/
│   ├── RaopAudioTransport.kt  RTP 收流、重传请求、AES 解密
│   ├── AudioPipeline.kt       重排 + 解码 + AudioTrack 输出
│   ├── AudioDecoder.kt        解码器接口（ALAC / AAC 共用一条管线）
│   ├── AlacDecoder.kt         ALAC 解码器（Hammerton 移植，已对照 Apple ag_dec.c）
│   ├── AacDecoder.kt          AAC-LC 解码器（系统 MediaCodec）
│   └── DmapReader.kt          DMAP 元数据解析
├── crypto/
│   ├── AirPortKey.kt          内嵌 AirPort Express RSA-2048 私钥（PKCS#8）
│   ├── AppleChallenge.kt      Apple-Challenge → 裸 RSA 签名 Apple-Response
│   └── RaopCrypto.kt          RSA-OAEP 解会话密钥 / AES-128-CBC 解音频
├── dacp/
│   └── DacpClient.kt          DACP 反向控制：浏览 `_dacp._tcp` + 发 `/ctrl-int/1/*` 命令
├── rtsp/
│   ├── Rtsp.kt                RTSP 请求解析与响应序列化
│   └── SdpParser.kt           SDP（`a=fmtp:` / `a=rsaeskey:` / `a=aesiv:`）解析
├── ap2/
│   ├── Ap2Session.kt          AirPlay 2 会话：配对、M4、事件通道、/feedback
│   ├── Ap2Crypto.kt           SRP-6a / ChaCha20-Poly1305 密钥协商
│   ├── Ap2SecureLink.kt       加密通道（control / events 两条 HKDF 密钥方向不对称）
│   ├── Ap2Streams.kt          音频流：实时(96) 与缓冲(103) 的端口、序列号、事件端口
│   ├── Ap2Remote.kt           反向控制：sendMediaRemoteCommand 的命令号与归档报文
│   ├── MrCommands.kt          updateMRSupportedCommands 解码（App 支持哪些媒体键）
│   ├── AudioFormatMask.kt     audioFormat 位掩码 → 编码与采样率
│   ├── BPlist.kt / BPlistReader.kt  二进制 plist 编解码
│   ├── SrpServer.kt / Tlv8.kt       配对握手
│   └── Ap2Txt.kt              _airplay._tcp 的 TXT 记录
├── net/
│   ├── MdnsAdvertiser.kt      只广播 `_raop._tcp`（走系统 NsdManager）+ AirTunes TXT 记录
│   ├── Ap2Advertiser.kt       广播 `_airplay._tcp`
│   └── LocalNetwork.kt        挑一个地址用于显示与"有没有网络"判断（监听不绑它）
└── util/
    ├── Codec.kt               Hex / 非标准 Base64 / 大端读写
    ├── SeqNo.kt               AirPlay 2 的 23 位序列号口径（窄化 + 模 23 比较）
    └── SinkLog.kt             环形内存日志 + logcat，供界面实时显示
```

---

## 协议要点（实现依据）

这些都是照着 shairport-sync（tag `3.3.8` 的 `rtsp.c` / `rtp.c` / `player.c`）与 Apple 官方 `ag_dec.c` 核对过的：

- **mDNS TXT 记录**（AirPort Express 经典集）
  `txtvers=1 ch=2 cn=0,1 et=0,1 ek=1 sv=false da=true sr=44100 ss=16 pw=false vn=65537 tp=UDP md=0,2 am=AirPort4,107 vs=130.14 sf=0x4 fv=<Android 版本>`
  - `et=0,1` 是触发 iOS 用 **AirPort Express 公钥**加密会话密钥的关键；
  - `cn=0,1` = 提供 PCM 与 ALAC；
  - `am=AirPort4,107` = 自称 AirPort Express 音频型号，iOS 会按"纯音频"特性集协商。
- **mDNS 实例名**：`<12位HEX>@<车机名称>`，HEX 首次运行随机生成并持久化（不读 Wi-Fi MAC）。
- **RSA**：`a=rsaaeskey:` 是 16 字节 AES 密钥，用 **RSA-OAEP(SHA-1, MGF1)** 解封；失败再退 `PKCS#1 v1.5`（老 iTunes）。
- **AES-128-CBC**：IV 取 ANNOUNCE 的 `a=aesiv:`，**每包复用同一个 IV，不递增**；只有整 16 字节块被加密（`len & ~0xF`），尾部余数原样透传。
- **RTP 包**：12 字节头，`byte[1] & 0x7F` 判类型 —— `0x60` 普通音频、`0x56` 重传（多 4 字节前缀，payload 偏移 16）、`0x54` 同步包（走 timing 端口）。
- **重传请求**：8 字节 UDP 包 `[0x80, 0xD5, seq(2), first(2), count(2)]`，发到 sender 的 `control_port`。
- **`RECORD` 响应**带 `Audio-Latency: 11025`（AirPort Express 的取值）。
- `Apple-Challenge` 与 SDP 的 `o=` 会话 ID 可以忽略。

### mDNS 为什么必须走系统 `NsdManager`

这一点是拿真机踩出来的，值得单独记一笔。

Android 的 mDNS 由**系统守护进程 `mdnsd`** 统一代理（`NsdManager` 就是它的门面），它已经占着 UDP 5353。应用再自己开一个组播 socket 去做应答（jmdns、mDNSResponder 之类），在桌面端跑得好好的，到了很多定制 ROM 上会**一条查询都收不到** —— 表现就是"应用日志说广播成功了，但 iPhone 列表里什么都没有"。

所以广播（`_raop._tcp` / `_airplay._tcp`）和浏览（`_dacp._tcp`）**都走 `NsdManager`**，跟设备上其它服务用同一个 responder。

>`_airplay._tcp` 现在默认也广播（AP2 已实现，见「AirPlay 2」一节）。AP2 下 iOS 不再发 DACP 头，
>所以 DACP 那条路失效；AP2 用的是它自己的 `sendMediaRemoteCommand`（见「AirPlay 2 反向控制」一节），
>本机实测可用，不需要 DACP 凭据。

### 两个真机上必须有的权限

| 权限 | 少了会怎样 |
| --- | --- |
| `WAKE_LOCK` | `WifiLock.acquire()` 直接抛 `SecurityException`（它由 `WAKE_LOCK` 把关）。拿不到 Wi-Fi 锁，息屏后热点链路会被断开 |
| `CHANGE_WIFI_MULTICAST_STATE` | 拿不到 `MulticastLock`。Wi-Fi 驱动会把组播整包滤掉，**mDNS 彻底收不到** |

这两个锁是**各自独立获取**的：任何一个失败都不会连累另一个，失败会在日志里明写（`Locks: wifi=true multicast=true` 这行就是给这个用的）。

### 后台常驻（比亚迪 DiLink）

针对 DiLink 3.0（Android 10，Qualcomm trinket）的常驻由四层组成：

| 层 | 机制 | 覆盖场景 |
| --- | --- | --- |
| 前台服务 | `mediaPlayback` 类型 + 常驻通知 + `MediaSession` | 息屏后继续运行的主依据，车机认可"用户可感知"的后台 |
| 锁 | `WifiLock(FULL_HIGH_PERF)` + `MulticastLock` + 音频线程提权 | 息屏时热点链路不断、mDNS 可达、收流不被饿死 |
| 自启 | `BOOT_COMPLETED` / `QUICKBOOT_POWERON` / `MY_PACKAGE_REPLACED`（受"开机后自动开启"开关控制） | 车机冷启动、OTA 覆盖安装后自动拉起 |
| 复活 | `START_STICKY`（进程被杀后系统重建）+ `NetworkAutoStartReceiver`（监听 `android.net.wifi.STATE_CHANGE`，网络一通就把服务拉回来） | 系统省电策略杀进程、LMK 回收后自动恢复 |

**电池优化豁免**：主界面"后台保活"一栏可以申请跳过 Doze / 后台冻结（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）。

> **这台车机的设置页是被阉过的**：DiLink 把 `android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 注册到了一个占位 Activity
> `com.byd.systemsettings/.unsupport.UnsupportActivity` 上，点「申请忽略电池优化」只会弹一句
> **"多媒体系统不支持该操作"** 然后自己关掉。同一个 filter 里还挂着 `MANAGE_OVERLAY_PERMISSION`、
> `MANAGE_UNKNOWN_APP_SOURCES`、`IGNORE_BATTERY_OPTIMIZATION_SETTINGS`、`AIRPLANE_MODE_SETTINGS`、
> `NFC_SETTINGS`、`HOME_SETTINGS` 一大票 action —— 也就是说车机把大半个系统设置页都封了，
> 不是本应用被针对。用 `adb shell cmd package resolve-activity -a <action> -d package:<pkg>` 就能看到落点。
>
> 应用侧对此做了处理：resolve 到 `unsupport` 占位页时**不启动它**，改为把下面的 adb 命令复制到剪贴板，
> 避免又弹一次那句误导性的提示。

车机上只能用 adb：

```bash
adb shell dumpsys deviceidle whitelist +com.shilapi.airplaysink
adb shell appops set com.shilapi.airplaysink SYSTEM_ALERT_WINDOW allow
adb shell appops set com.shilapi.airplaysink RUN_IN_BACKGROUND allow
adb shell appops set com.shilapi.airplaysink RUN_ANY_IN_BACKGROUND allow
```

第一条把应用加进 Doze 白名单（`dumpsys deviceidle whitelist` 里应出现 `user,com.shilapi.airplaysink,<uid>`，
界面随之变成「已忽略电池优化」）。

`SYSTEM_ALERT_WINDOW` 是给 **Android 12+** 准备的：从 12 起"后台启动前台服务"受限，而这个权限是官方豁免项 ——
拿到它，`NetworkAutoStartReceiver` 才能在息屏被杀后真正把服务拉起来。本机实测车机是 **Android 10（SDK 29）**，
这一代还没有该限制，所以这条在本机只是预留；README 早前写的"Android 12"是误记。

**车机自带的后台管理**还有一个入口：`com.byd.appstartmanagement`（界面标题「选择禁止后台启动项」，
组件 `com.byd.appstartmanagement/.frame.AppStartManagement`）。注意它是**禁止**列表 —— 开关打开 =
禁止后台启动。实测本应用默认就是关闭状态（未禁止），不用动；如果哪天被车机自动勾上了，要在这里关掉。

```bash
adb shell am start -n com.byd.appstartmanagement/.frame.AppStartManagement
```

**已知边界**：如果用户在最近任务里**上滑清掉**应用，DiLink 走的是 force-stop —— Android 会停止向 force-stop 的应用投递一切广播，`START_STICKY` 和网络 receiver 都不会生效，只能下次开机或手动打开应用恢复。这是平台行为，没有应用层的绕过方法（DiPlay 靠 USB 插入 / 蓝牙连接的 activity 拉起，纯热点场景没有对应入口）。正常使用（打开开关、不清后台）可以长期常驻。

### 日志文件

界面上的日志只是内存里最近 400 行；完整日志会异步追加写到文件，进程重启不丢：

```
/storage/emulated/0/Android/data/com.shilapi.airplaysink/files/sink.log
```

- 超过 1 MiB 自动轮转成 `sink.log.old`（只保留一份），磁盘占用封顶约 2 MiB；
- 每次进程启动在文件里写一行 `---- process start ----` 分隔标记；
- 界面的"清空"只清内存视图，不动文件（文件是事后排障用的）；
- 用 adb 取回：

```bash
adb shell cat /storage/emulated/0/Android/data/com.shilapi.airplaysink/files/sink.log > sink.log
```

### DACP 反向控制（AirPlay 1 的路）

AirPlay 本身没有"让手机切歌"的 RTSP 方法。AirPlay 1 用的是 **DACP**（Digital Audio Control
Protocol），而它**只在 classic AirPlay 下有效**：`DACP-ID` 与 `Active-Remote` 这两个头只出现在
classic 的请求上，一旦 iOS 按 AP2 协商，请求里就没有它们，接收端拿不到 token。

> **2026-10-20 更正**：本节过去写着「AP2 没有反向控制」，并引用 shairport-sync 的
> `AIRPLAY2.md`「No Remote Control」。那份文档已经更新——现在写的是 *"Remote control
> facilities via MPRIS, D-Bus or MQTT"*，代码在 `remote/remote.c` 里，走的是 AirPlay 2 自己的
> `sendMediaRemoteCommand`，**不是** DACP。DACP 这一节仍然成立：它在 AP2 下确实失效。
>
> 同一天真机实测确认这条路在 AP2 下**可用**：透明配对 + 纯音频会话就能驱动 iPhone，不需要
> MFi、HomeKit 或 HID。早先「iPhone 收到就断开会话」的结论是**事件通道的 HKDF 密钥方向写反了**
> 造成的，详见「[AirPlay 2 反向控制](#airplay-2-反向控制sendmediaremotecommand)」。

因为本项目在 AirPlay 1 模式下只广播 `_raop._tcp`、不广播 `_airplay._tcp`，iOS 是按 classic 模式连的，
**恰好落在 DACP 生效的那一侧**。

流程：

1. iOS 在每个 RTSP 请求上附带两个头：
   `DACP-ID: CFAE09D9DA7527E9` 与 `Active-Remote: 3951361179`（授权 token）。
2. iPhone 同时在链路上广播一个 `_dacp._tcp` 服务，实例名通常是 `iTunes_Ctrl_<DACP-ID>`。
   本端按 **DACP-ID** 匹配实例名（退一步按 **发送端 IP** 匹配），解析出端口。
3. 之后发一条普通 HTTP GET 就能控制：

   ```
   GET /ctrl-int/1/nextitem HTTP/1.1
   Host: <iPhone IP>:<port>
   Active-Remote: 3951361179
   ```

   iPhone 回 `204 No Content`。实现了的命令：`play` / `pause` / `playpause` / `stop` /
   `nextitem` / `previtem`。

实现依据：shairport-sync 的 `dacp.c` —— 请求拼串、端口发现方式、命令集都照它写。

---

## AirPlay 2（纯音频）

界面上的「使用 AirPlay 2」开关决定广播哪套记录，默认开着。两代**共用同一个控制端口 5000**：
`_airplay._tcp` 只是多一条身份，`/info` 与 HAP 配对端点都是同一条连接上的路径，没有第二个服务器。

实现了什么：

| 环节 | 实现 |
| --- | --- |
| 发现 | `_raop._tcp` + `_airplay._tcp`，TXT 里带 `pk` / `pi` / 特征位，两者必须一致 |
| `/info` | 回完整设备描述 bplist（`features` / `supportedFormats` / `txtAirPlay` / `pi` …） |
| 配对 | 只做 **transient pairing**（SRP-6a + M4 proof，固定码 `3939`），不做 `/pair-verify` |
| 控制通道 | M4 之后整条 RTSP 切到 HAP 分帧：`uint16 长度 ‖ ChaCha20-Poly1305 ‖ tag`，双向独立密钥 |
| 音频 | SETUP 两次：先开事件端口，再开流；`96` realtime（UDP RTP）与 `103` buffered（TCP 分帧），ALAC 解密后进同一条 `AudioPipeline` |
| 音量 | `SET_PARAMETER` 的 bplist `volume`（dB，与 RAOP 的 `volume:` 文本行同量纲） |
| 事件通道 | 在 `Events-Salt` 密钥下应答；连上后立刻发一条 `updateInfo`；也用它向发送端发命令（见下一节） |
| `FLUSHBUFFERED` | 发送端说「我丢掉了我缓冲的音频」，答 200 并把播放头移到新位置 |
| `SETRATEANCHORTIME` | 发送端的时钟开关：`rate & 1` 为 1 才会播，为 0 就停，见下面的注 |
| `POST /feedback` | 回 `{streams:[{type,sr}]}` 说明「正在播的是什么」，见下面的注 |
| 反向控制 | 开关打开时发 `sendMediaRemoteCommand`，走事件通道 |

> `supportedFormats.bufferStream` 里带着 AAC-LC（`0x400000` 44.1k、`0x800000` 48k）。这两个位
> 不是给解码器用的，是给发送端看的：Apple Music 先读这张能力表再决定要不要建流，只写 ALAC 时它
> 会一直停在「会话开着、什么都不播」，连 `SETUP (streams)` 都不发——看起来就像接收端死了。
> shairport-sync 的 mask 默认就是 `0xC40000`。
>
> 补上 AAC 位之后，Apple Music 真的来建流了，带来的却是 **AAC-LC 48 kHz**（`ct=4`），
> 而本机当时只有 ALAC 解码器——于是每一帧都被当成非法 ALAC 丢掉，偶尔蒙对几帧就是一片杂音。
> 现在 `AacDecoder` 走系统 `MediaCodec` 解 AAC-LC，`AudioPipeline` 按 `ct` 选解码器。
> 格式的判断有两条独立的依据：SETUP 的 `audioFormat` 位掩码（`0x00800000` = AAC-LC/48k/2ch，
> 位号 23 与发送端另发的 `audioFormatIndex` 是同一个数），以及 RTP 头里的 SSRC
> （shairport-sync 的 `ssrc_t`：`0x17000000` = AAC/48k/2ch）。日志会把两者都打出来对照。
>
> 另一个坑：Apple Music 的 SETUP **不带 `sr`**，所以「取不到就用 44100」会让它的 48 kHz 音频
> 慢一个半音、节奏也全错。采样率必须从 `audioFormat` 掩码里读。
>
> 第三个坑：**`FLUSHBUFFERED`**。缓冲流（`type=103`）允许手机丢弃自己缓冲的音频——拖进度条时
> 就会发，它带着一个 `flushUntilSeq`（序列号终点）。不处理它的后果不是「多播一会儿旧歌」那么
> 简单：手机从新位置继续发，之后每个包的序列号都比播放头前几千，管线把这看成「丢了几千个包」，
> 等一个永远填不上的坑（900 ms），再写几千个包的仿真音频——实测 `skipped 20028`，
> 音轨慢到爬。现在答 200，并把「到终点为止的音频」在到达时就丢掉（`AudioPipeline.flushStale`），
> 播放头随之移到新位置。另外加了一道保险：序列号跳变超过 1000 个包就当成**换了位置**而不是
> **丢了包**，重新锚定而不记成损失。
>
> 这里还有一个更隐蔽的坑：**两边的序列号宽度不一样**。音频块自己带的计数器只有 **23 位**
> （`seq = nctohl(packet[0]) & 0x7FFFFF`，每 8388608 个块回绕一次），而 `flushUntilSeq` 是个
> **32 位**字段。shairport-sync 在 `handle_flushbuffered()` 里明确先做 `flushUntilSeq & 0x7fffff`
> 再比较——不窄化就会拿一个 32 位的数去比 23 位的计数器，朴素比较 `sequence <= until` 对**每一个**
> 包都为真，于是整条音频流被永久丢弃，表现为「FLUSHBUFFERED 之后统计行彻底消失」。
> 真机抓到过的一组数字可以对上：端点 `10299533` 窄化后是 `1910925`，与该流首帧 `1904383` 相差
> 6542 个块，正好等于 RTP 时间戳的位移 `6699008 / 1024 = 6542`。
> 现在端点先经 `util/SeqNo.narrow()` 窄化，比较用**模 23 算术**（`SeqNo.difference()`，按 bit22
> 符号位解释），所以计数器回绕时也不会把「刚发的旧包」误判成「端点之后的新包」。
> 单测 `SeqNoTest` 钉住窄化、端点前后边界、回绕与对称性。
>
> 第四个坑：**`SETRATEANCHORTIME` 是唯一的播放/暂停信号**。AirPlay 2 会话**一个 sync 包都不发**，
> 所以「发送端认为自己在不在播」这件事，只有这个请求会说——shairport-sync 的
> `handle_setrateanchortime` 只读一个位：`rate & 1` 为 1 才把 `ap2_play_enabled` 置 1 并通知 PTP
> 「B」eginning，为 0 就复位锚点。本机以前答 200 之后就把 body 扔了（动词落进兜底分支），
> 于是**整个暂停期间管线一直在播**。症状很有迷惑性：每次暂停后约 150 ms 就跟一个
> `FLUSHBUFFERED`，把队列和喇叭清空，而管线继续往这个空余量里灌码，500 ms 的余量直接塌到 1 ms，
> 触发 `Cushion down to 21ms; refilling`——真机两分钟里 14 次 underrun，全部落在暂停窗口内，
> 听感就是**每拖一次进度条就缺一段**。现在按 `rate & 1` 驱动 `AudioPipeline.setPlayEnabled()`：
> 停表时连 `flushStale`/`flush` 里的 `track.play()` 都不再执行（否则会把刚停下的喇叭又叫醒），
> 队列照常收包；起表时清掉 `primed`，先补够余量再出声。
> 单测 `SetRateAnchorTest` 钉住这一位（包括高位是时间线标识符、负数仍读 bit 0）。

刻意没做：PTP 时钟同步（本机自己走自己的时钟，不参与多房间对齐）、
HomeKit / `/pair-verify`（transient 配对够用，也不需要 Ed25519 身份）。

> `POST /feedback` 是发送端每 2 秒一次的**健康查询**：「你到底在播没有？播的是什么？」
> 答案必须是一个 `streams` 数组（`type` = 96/103，`sr` 是 plist real）；**空 body 的含义是
> 「没在播」**，不是「没事」。shairport-sync 的 `handle_feedback` 就是这么写的。
> 本机早期对它只回一个空 200，等于一直告诉手机「我没有在播」——AirPlay 1 的会话从不问这个问题，
> 所以 AP1 一直正常；AP2 下会拿它建播放时间线的 App（Apple Music）会在流刚建好一秒后自行停掉，
> 表现正是「连上了，一点播放就立即停止」。QQ 音乐不看这个字段，所以同样的接收端它能播。
>
> 追这条时踩过的第二个坑：**它没有「动词」**。整个协议是按 RTSP 方法分发的，但
> `POST /feedback`、`POST /audioMode`、`POST /command` 的方法一律是 `POST`，含义在路径里。
> 只按方法分发时这三个全落进「兜底回 200」，`/feedback` 的应答分支成了永远执行不到的死代码——
> 日志上仍然是干净的 `POST ... answered with 200`，看起来一切正常。现在 `ap2Route()` 先取路径
> 末段再分发，单测 `Ap2RouteTest` 钉住这条。


> `TEARDOWN` 在 AirPlay 2 里是**两个请求共用一个名字**，靠 body 区分：带 `streams` 数组的是
> 「停这条音频流，连接照旧」，不带（或读不出）才是「结束整个会话」。手机**每次暂停都会发前者**。
> 本机早期把两种都当成会话结束，于是暂停后控制连接直接被拆，接下来按「播放」时命令无处可发——
> 表现就是「暂停有效、播放没反应」。现在按 shairport-sync（`handle_teardown_2`）的口径分流：
> 流停掉，配对、事件通道、`groupUUID` 全部留着，手机恢复播放时再发一次 SETUP 拿新端口。
> 单测 `TeardownScopeTest` 钉住这个判定。

> 已知取舍：音频走 realtime（`type 96`，UDP RTP）还是 buffered（`type 103`，TCP 分帧）**由手机决定**，
> 本机两种都收，只是 buffered 断线时发送端拿到的是硬失败、会主动结束会话。
> 想更稳就关掉 AP2 退回 AP1，代价是丢掉 AP2 自己的遥控通道。

### AirPlay 2 反向控制（`sendMediaRemoteCommand`）

依据是 **shairport-sync 自己的实现**：`remote/remote.c` 的
`ap2_event_send_simple_modern_media_remote_command()`，以及它 `AIRPLAY2.md` 里
"Remote control facilities via MPRIS, D-Bus or MQTT" 那一行。这条路**完全不需要 DACP**，
也不需要 MFi 身份或 HID 设备——只用每个 AP2 会话都已经建立的那条加密事件连接。

报文（「AP2 反向控制」默认就是开的，按一次「下一首」）：

```
POST /command RTSP/1.0
Content-Length: 727
Content-Type: application/x-apple-binary-plist

{ type: sendMediaRemoteCommand,
  modernMediaRemoteCommand: "4",          ← 字符串，不是整数
  params: {
    kMRMediaRemoteOptionSendOptionsNumber: 0,
    kMRMediaRemoteOptionCommandID: "<随机 UUID>",
    kMRMediaRemoteOptionIsRedirectingCommand: true,
    kMRMediaRemoteOptionDestinationDeviceUIDs: <data>   ← 嵌套 bplist（NSKeyedArchiver）
  } }
```

没有 `CSeq`：事件端口不是 RTSP 控制通道，shairport-sync 的
`ap2_event_port_post_command()` 只发 `Content-Length` 和 `Content-Type` 两个头。

命令号取自 shairport-sync 的 `simple_command_t`，注释里写明它就是 AP2 实际使用的编号：

| 命令 | 号 | 命令 | 号 |
| --- | --- | --- | --- |
| play | 0 | next | **4** |
| pause | 1 | previous | **5** |
| play/pause | 2 | 开始快进 / 停止快进 | **8 / 9** |
| stop | 3 | 开始快退 / 停止快退 | **10 / 11** |

快进快退是**一对**命令：8 让手机开始往前扫，9 让它停；10 / 11 是反过来的那一对。这四条是
shairport-sync 枚举的最后一段（它到 `rewind_stop = 11` 为止），也正是它注释里说的「AP2 实际使用的编号」。

> **别用 12 / 13。** 最开始本机发的是苹果 `MRCommand` 里的 `kMRGoBackFifteenSeconds = 12` /
> `kMRSkipFifteenSeconds = 13`（iPhone 锁屏那两个「15 秒」按钮），以为能一跳 15 秒。2026-10-20 实测：
> 八次按下，**每次都收到 `RTSP/1.0 200`，音乐一动不动**。200 在这条通道上只代表「收到了」，不代表
> 「执行了」——所以能不能用不能靠应答判断，只能看 8–11 这四个已知有效的号。

界面上**长按上一首 / 下一首**就是这两对：点一下照旧切歌，长按（过系统的长按阈值，约 0.5 秒）发一条
「开始扫」并一直扫到松手，松手发对应的「停止扫」（`ACTION_UP` / `ACTION_CANCEL` 都会停，页面被切走时
`onStop` 也会停，另外还有 20 秒的硬上限，防止 `ACTION_UP` 丢掉后手机一路扫到曲末）。按住期间按别的键
会先停扫再执行那个键。AirPlay 1 没有这条路——DACP 只有 `beginff` / `beginrew`，**没有停止它们的接口**
（shairport-sync 的命令表里两个 stop 都是 NULL），所以 AP1 下长按会直接告诉你它做不到。

App 到底支不支持扫，其实手机自己说过一次：它接管 now-playing 时会发
`POST /command {type:"updateMRSupportedCommands"}`，里面是**一串 hex bplist（plist 套 plist）**，
逐条给出命令号、是否启用，以及两个能力位。8–11 无论 App 支不支持都回 200，所以这张表是唯一能区分
「被忽略」和「本来就不支持」的东西。日志里是一行摘要：

```
AP2: MR app lists 3 commands [play pause next]; scrubbing=yes, position=yes
```

> **键名是短名。** 按苹果头文件里的 `kMRMediaRemoteCommandInfoCommandKey` 去读，一条都读不到，
> 每款 App 都显示成 `app lists 0 commands [none]`——看着像「所有 App 都不支持扫描」，其实是解码器
> 读错了键。真实报文里是 `kCommandInfoCommandKey` / `kCommandInfoEnabledKey`，两个能力位
> （`…CanBeControlledByScrubbingKey`、`…SupportsReferencePosition`，后者**没有** `Key` 后缀）嵌在
> `kCommandInfoOptionsKey` 这个字典里。单测里存了一条从手机抓下来的原始 entry 钉住这个形状。

`DestinationDeviceUIDs` 是唯一不直观的地方：它是一个 **NSKeyedArchiver 归档的
`NSMutableArray`**，里面只有一个字符串——会话的 **group UUID**（`kMRMediaRemoteOptionDestinationDeviceUIDs`
的注释原话是 "the Group UUID of which this player is a member"）。这个 UUID 来自**初始 SETUP 的
`groupUUID`**，日志里会打印：

```
AP2: group UUID 343F4AF7-… — remote commands can name it
```

失败时日志里先是 `AP2: command -> sendMediaRemoteCommand #1 …`，然后没有
`AP2: events <- command reply RTSP/1.0 200`。事件通道上所有 `RTSP/` 开头的帧都是手机对我们命令的
**响应**，必须跳过而不是回 200，否则两边会互相打乒乓。

#### 实测结果：可用（2026-10-20，iPhone 直连）

按「下一首」/「上一首」/「播放暂停」共 6 次，每次都在 20–70 ms 内拿到手机的 `200`，会话不断：

```
AP2: event channel up — remote commands have somewhere to go
UI: "下一首" pressed (running=true, protocol=AP2, ap2Remote=true, eventChannel=true)
AP2: command -> sendMediaRemoteCommand #1 body=727B {type=…, modernMediaRemoteCommand=4}
AP2: remote command nextitem (#4) result=SENT
AP2: events <- command reply RTSP/1.0 200 cseq=0 body=0B     ← 手机接受
```

手机对命令的应答就走事件通道本身：所有 `RTSP/` 开头的帧都是它对某条命令的响应，必须跳过而不是
回 200，否则两边会互相打乒乓。状态卡这时显示「手机已接受 · 200」。

#### 之前为什么会「按一下就断」：事件通道的密钥方向

早先同样的代码在真机上是这个结果——报文发出去了，iPhone 在 15–45 ms 后关掉整条控制连接，
音乐跟着停。当时误判成「授权不足」，实际原因在密钥：

AirPlay 2 每个连接都用同一份配对密钥派生一对 ChaCha20-Poly1305 密钥，只有 HKDF 的 salt 与
info 串不同。而**两条通道的方向不是镜像关系**——shairport-sync 的 `cipher_new(channel)`
（`pair_ap/pair_homekit.c`）给 accessory 用 channel 3 做控制连接、channel 4 做事件连接：

| 通道 | 我们**写**用 | 我们**读**用 |
| --- | --- | --- |
| control（ch3） | `Control-Read-Encryption-Key` | `Control-Write-Encryption-Key` |
| events（ch4） | `Events-Write-Encryption-Key` | `Events-Read-Encryption-Key` |

源码注释原话：*"events is opposite, probably because it is a reverse connection"*。

本机最初把 events 当成 control 的镜像（写用 `Events-Read-Encryption-Key`），于是 iPhone 收到
的每一帧都过不了 Poly1305 校验，它直接拆连接。表现和「拒绝执行」几乎一样，但性质完全不同：
**不是权限问题，是一个字节的方向问题**。

判据：control 方向本来就是对的，证据是 SETUP 回复里的 `eventPort`/`dataPort` 被 iPhone 正确
使用（它确实连上了那两个端口）。所以事件通道只有可能错在 keys，而不是会话不够"高级"。

现在这事有单测盯着（`ChannelKeysTest`），它断言的正是「events 不能是 control 换个 salt」。

#### 状态卡「AP2 反向控制」怎么读

车机常吞 Toast，所以每次按键的结果都留在这一行，点下去的瞬间先变成「正在发送：下一首」：

| 显示 | 缺什么 |
| --- | --- |
| `未开启` | 开关没开，或开了但服务没重启 |
| `已开启 · 手机还没打开命令通道` | iPhone 没连事件端口，命令无处可发 |
| `已就绪 · 命令通道已打开` | 可以按了 |
| `已送出 · 等手机应答` | 报文写进去了，还没等到应答 |
| `手机已接受 · 200` | 手机回了 `RTSP/1.0 200`，命令已被接受 |
| `发送失败 · …` | 后面说清是哪种：未开通道 / 写入出错 / 没有音频流 |

---

## Apple-Challenge / Apple-Response

发送端可以在**任意** RTSP 请求里带一个 `Apple-Challenge` 头（≤16 字节随机数），接收端必须签名后以
`Apple-Response` 回。缺失时发送端**会静默挂断** —— 表现就是"连上、一瞬间就断、从不发 ANNOUNCE"，
日志里只有 `Control connection from` 紧跟 `Control connection closed`，中间什么都没有。

签名内容（照 shairport-sync 的 `apple_challenge()`）：

```
challenge(≤16) || 本端 socket 地址(IPv4 4 字节 / IPv6 16 字节) || 6 字节硬件地址
→ 零填充到至少 32 字节 → RSA 私钥裸 PKCS#1 v1.5 运算 → Base64（去掉 `=`）
```

两个容易踩的点：

- **是"裸签名"，不做摘要**。JCA 里对应 `Cipher` + `ENCRYPT_MODE` + `PrivateKey`，等价于 OpenSSL 的
  `RSA_private_encrypt(..., RSA_PKCS1_PADDING)`。用 `Signature` 会多一层 hash，签出来的东西不对。
- **Base64 要去掉 `=`**。Apple 在协议里通篇用无填充 Base64。

那 6 字节"硬件地址"shairport-sync 取的是 `MD5(service_name)` 前 6 字节，本实现照做 —— 下游只验签名本身，
不看内容。

> 注意这条只在 **classic AirPlay（v1）** 下需要。AirPlay 2 的接收端（如 UxPlay）不处理它也能用，
> 因为 iOS 在 AP2 模式下走的是配对流程。本接收端刻意只广播 `_raop._tcp`，落在 v1 那一侧。

---

## 监听地址：一律绑通配（重要）

RTSP 控制端口和 SETUP 之后的三个 RTP 端口**全部绑 `0.0.0.0`**，不绑某个具体网卡地址。

这不是偷懒，是必需的。最常见的部署形态是**车机自己开热点、iPhone 去连**。而当一个 Android 设备是热点提供方时，
`wlan0` 处于 AP 模式，它**不在** `ConnectivityManager` 报告的网络列表里 —— 对系统来说 AP 不是一个"已连接的网络"。
于是任何"挑一个地址"的逻辑都会挑到蜂窝网（`rmnet0`，比如 `10.226.122.44`）。

绑在那个地址上的后果是：mDNS 广播的车机地址是 `192.168.43.1`（热点网关），而 TCP 5000 只在 `10.226.122.44` 上监听，
**iPhone 一点连接就被拒**。界面和日志看起来一切正常，实际连不上。

绑通配没有这个失效模式：所有接口都应答，iPhone 从哪张网卡过来都行，IP 中途变了也不用重建。

`LocalNetwork` 因此退化成**只用于显示**：状态卡的"监听地址"和"有没有网络可用"的判断。
它优先取 `wlan*` / `ap*` 接口的地址，这样热点场景下显示的是 `192.168.43.1` 而不是蜂窝地址。

---

## 编译

需要一个 JDK 17 与 Android SDK（`platforms;android-37.0`、`build-tools;37.0.0`、`platform-tools`）。

```bash
# 1) SDK 位置
cat > local.properties <<'EOF'
sdk.dir=/Users/you/Library/Android/sdk
EOF

# 2) Debug 包
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 3) Release 包（用你自己的签名，缺省变量时不签名，仅供本地调试）
export ANDROID_KEYSTORE_PATH=/path/to/keystore.jks
export ANDROID_KEYSTORE_PASSWORD=...
export ANDROID_KEY_ALIAS=...
export ANDROID_KEY_PASSWORD=...
./gradlew :app:assembleRelease
```

工具链版本：Gradle 9.5.0 wrapper、AGP 9.3.0（内置 Kotlin，**不需要** kotlin-android 插件）、compileSdk 37、minSdk 26。

### 用 `build.sh`

仓库根的 `build.sh` 把上面几条包了一层：会检查 JDK 17 与 `sdk.dir`（缺 `platforms;android-37.0` 时调 `sdkmanager` 自动装），
日志落到 `build/logs/<target>.log`，失败时把 `e:` / `FAILURE:` 行摘出来，**退出码就是 gradle 的退出码**。

| 命令 | 作用 |
| --- | --- |
| `./build.sh`　或　`./build.sh debug` | Debug APK → `app/build/outputs/apk/debug/app-debug.apk` |
| `./build.sh release` | Release APK；没有 `ANDROID_KEYSTORE_*` 时产出 `app-release-unsigned.apk`（不签名，不报错） |
| `./build.sh install` | 编译 Debug 并 `adb install -r`，最后打印启动命令 |
| `./build.sh test` | JVM 单测，报告在 `app/build/reports/tests/testDebugUnitTest/index.html` |
| `./build.sh clean` | 用 `:app:clean` 删 `app/build`，再删 `build/logs` |

`release` 找不到 keystore 时不会失败：`signingConfig` 为 `null`，AGP 直接产出 unsigned 包。
（早前版本把 `storeFile` 指向一个不存在的默认文件，`validateSigningRelease` 会让整个 release 构建失败，已改。）

`namespace` / `applicationId` 均为 `com.shilapi.airplaysink`。如果需要换成自己的包名，改 `app/build.gradle.kts` 里的 `namespace`、`applicationId` 以及 `app/src/main/java/com/shilapi/airplaysink/` 的目录名。

---

## 安装到车机

```bash
adb connect <车机IP>:5555        # 车机需先打开 ADB 调试
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

车机可能没开 ADB over Wi-Fi，那就用 U 盘拷 APK 到车机上装。

安装后建议顺手放开后台限制，否则车机厂商的省电策略可能把前台服务掐掉：

```
设置 → 应用 → AirPlay 车机 → 电池 → 不受限制 / 允许后台运行
```

---

## 使用

1. 车机连上 **iPhone 的个人热点**（iPhone：设置 → 个人热点 → 允许他人加入）。
2. 打开 **AirPlay 车机** App，把「接收服务」开关打开。授权通知权限（Android 13+），否则后台通知不显示。
3. 状态卡会显示监听地址、广播名称、控制端口。显示「运行中，等待 iPhone」即可。
4. iPhone 上打开控制中心 → 音频输出（或音乐 App 底部的 AirPlay 图标），在列表里选中本车机。
5. 播放音频，车机音响出声。切歌时状态卡会显示标题 / 艺术家 / 专辑与进度。
6. **AirPlay 1 会话**下，手机一连上状态卡的「反向控制」就会变成**可用**，界面上的「上一首 / 播放暂停 / 下一首」
   可以直接遥控 iPhone。同时应用开了一个 `MediaSession`，车机的媒体键也会转成 DACP 命令
   发给 iPhone。**AirPlay 2 会话**下这三条路都不通（协议限制），状态卡会写明原因，按任意一个键
   会弹出说明并给出一键切回 AirPlay 1 的选项。

开关状态会被记住：进程被系统杀掉后再打开 App，服务会自动恢复运行。

### 缓冲深度：可调的延迟

界面上的「缓冲深度」滑块同时控制两件事 —— **播放延迟**和**抗抖动余量**，二者不可分离：
音频被攒着不播，就是在等网络；攒得越多，越扛得住空口停顿，声音也越晚于手机。

| 设置 | 适合 |
| --- | --- |
| 250 ms（默认，与 AirPort Express 一致） | 链路好，想跟手一点 |
| 500~1000 ms | 热点信号一般，偶有顿挫 |
| 2000 ms | 链路很差的最后手段；车机只出声音、不对画面，加到这个量级完全可用 |

调整会同时放大配套参数：缺口等待窗口取余量的 3/4（200~900 ms），重传请求次数随窗口
自动加到最多 12 次，`AudioTrack` 水库、预缓冲超时、以及 RECORD 应答里的 `Audio-Latency`
头都按同一比例增长 —— iPhone 会按上报的延迟来对齐自己的进度条，所以调大只会"慢"，
不会丢包。改动要生效需按「保存并重启服务」并在 iPhone 上重选一次输出。

### 关于网络：不用管，服务自己会等

服务开启后**不关心车机连的是哪个网络，也不检查有没有连上 iPhone 热点**。逻辑只有一条：拿到任意一个可用的 IPv4 地址，就在它上面广播 —— Wi-Fi 优先，其它网络兜底。

| 情况 | 表现 |
| --- | --- |
| 车机已经连好热点 | 开会关立刻就能在 iPhone 上看到 |
| 车机当时没网（比如刚开机，Wi-Fi 还没起来） | 服务**不会关掉**，状态显示「等待网络…」，网络一可用就自动开始广播 |
| 网络中途换了（连到别的 Wi-Fi，或热点重连换到新网段） | 自动在新地址上重新广播 |

唯一的前提是**得有一个可用的 IPv4 地址** —— 一个都没有的时候 socket 绑不上，也就没地方把广播包发出去。这是 socket 的硬要求，跟"判断热点"无关。

---

## 方向盘按键：能到什么程度

先把结论说清楚，免得期待错位。AirPlay 1 走 **DACP**，AirPlay 2 走它自己的
`sendMediaRemoteCommand`（走事件通道），两条都能用；差别在于**凭据从哪来**，以及下表这点。

| 键 | 后台（导航在前台） | App 界面在前台 |
| --- | --- | --- |
| 播放 / 暂停 | ✅ 可以 | ✅ 可以 |
| 上一首 / 下一首 | ⚠️ 看车机 | ✅ 可以 |

原因不在 DACP，而在**车机怎么分发按键**：比亚迪把方向盘的 next/prev 交给**当前前台窗口**，
谁在前台谁收。播放/暂停是作为标准媒体键下发的，所以后台能收到；切歌键在 App 退到后台时就
收不到了。

这是 `MediaSession` 的固有限制。要在**后台**也拿到切歌键，只能再加一个**无障碍服务**
（`flagRequestFilterKeyEvents`，DiPlay 的 `WheelKeyService` 就是这么干的），代价是车机上要
手动开一次无障碍权限 —— 这一版**没有做**。

不管哪个键、走哪一代协议，最终都是同一条链路的两端之一：AP1 走 DACP，AP2 走
`sendMediaRemoteCommand` —— 所以界面上的三个按钮就是最好的验证：按钮能用，说明遥控通了。

### 长按上一首 / 下一首 = 快退 / 快进

方向盘切歌键按住 0.5 秒以上，改发扫描命令：

| 操作 | 发出的命令（AP2） | 命令号 | 发出的命令（AP1 / DACP） |
| --- | --- | --- | --- |
| 短按上一首 | `previousitem` | 5 | `previtem` |
| 短按下一首 | `nextitem` | 4 | `nextitem` |
| 按住上一首 | `beginrew` → 0.9 秒后 `endrew` | 10 → 11 | `beginrew` → 0.9 秒后 `playresume` |
| 按住下一首 | `beginff` → 0.9 秒后 `endff` | 8 → 9 | `beginff` → 0.9 秒后 `playresume` |

六点要注意：

- **比亚迪的长按是独立键码，不是"按住 87 不放"**。方向盘按住下一首/上一首时，车机根本不重复
  87/88，而是直接发 **302 / 303**（框架里叫 `KEYCODE_AUTO_MEDIA_NEXT_LP` / `..._PREVIOUS_LP`）。
  这是"长按没反应"的真因：旧版只认 87/88，302/303 被当成陌生键放行了。
- **302/303 是脉冲，不是按住状态**。实测 8 次，down 和 up 相隔 13–85ms，按多久都一样 —— 车机只
  告诉你"按了长按"，不告诉你按了多久。所以扫描时长由这边定（`BURST_MILLIS`）。
- **但松手事件不是直接丢掉，而是先量一下**。up 距 down 小于 `PULSE_RELEASE_MILLIS`（200ms）才算
  脉冲回声——手指还按着就到了，结束扫描的活儿交给定时器；超过这个间隔就只能是手指真的抬起来了，
  于是立刻停扫。这样无论车机是"发脉冲"还是"按住期间保持 down"，行为都对。
- **一次长按只跳一步，所以 burst 是 0.9 秒**。iPhone 的"快进"不是连续播放，而是**跳**：`beginff`
  之后约 80ms 跳第一次，然后每约 1.05 秒再跳一次 —— 之前 3 秒的 burst 正好凑出三次跳跃，一个手势
  把曲目拖了三段。0.9 秒落在第一次之后、第二次之前，一次长按就是干干净净的一步。
- **两代协议各有一套命令，互不相同**。AirPlay 2 用 8/9（快进）和 10/11（快退）。AirPlay 1 只有
  DACP：`beginff` / `beginrew` 开始，**`playresume`** 结束 —— 规范里它的说明就是 "play after fast
  forward or rewind"，DACP 里没有 `endff` / `endrew`（shairport-sync 的命令表和苹果公布的命令表
  都是这个结论），`playresume` 才是那缺失的另一半，两个方向共用同一个端点。AP2 还需要打开
  「AP2 反向控制」开关。
- **还有 20 秒硬上限**兜底，万一结束事件丢了，也不会一路扫到曲末。

三种键形都认：

| 车机怎么报 | 判定方式 |
| --- | --- |
| 87/88（`KEYCODE_MEDIA_NEXT/PREVIOUS`）按住重复 | 自己计时 0.5 秒，松手停 |
| 302/303（BYD 的长按键码） | 收到即扫，0.9 秒定时停；若 up 晚于 200ms 则 up 停 |
| 90/89（`KEYCODE_MEDIA_FAST_FORWARD/REWIND`） | 按下即扫，松开即停 |

同一个手势有时会**同时**从窗口和媒体会话两个入口进来（这台车机就是，相隔 40ms 左右）。扫描
按"方向"记身份而不是按键码，所以后到的那份会被认成重复并丢弃，不会叠出两条扫描。

日志前缀 `Wheel:`，一条完整链路长这样（DiLink 3 实测）：

```
Wheel: key 302 (KEYCODE_AUTO_MEDIA_NEXT_LP) down — a long press, scanning for 900ms (window)
Wheel: sending "beginff"
Remote control "beginff": sending over the AP2 channel (receiver=up)
Wheel: key 302 (KEYCODE_AUTO_MEDIA_NEXT_LP) up after 39ms (window)
Wheel: key 302 (KEYCODE_AUTO_MEDIA_NEXT_LP) burst over after 900ms — stopping the scan
Wheel: sending "endff"
```

`up after` 后面那个毫秒数就是判别式：**几十毫秒 = 脉冲型**（手指还按着，up 是回声，扫描由定时器
结束）；**几百毫秒以上 = 按住型**（车机保持 down 直到松手），这时紧跟一行 `released after …ms —
stopping the scan`，扫多久完全由手指决定。

短按则是：

```
Wheel: key 87 (KEYCODE_MEDIA_NEXT) down — watching for a hold (window)
Wheel: key 87 up after 19ms — a tap
Wheel: sending "nextitem"
```

#### 实测：8/9 会执行，但是"跳"而不是"连播"

`beginff`/`endff`（8/9）和 `beginrew`/`endrew`（10/11）在这台 iPhone 上是**真的会动**，只是动的
方式不像 shairport-sync 描述的那样连续：播放位置是**跳**的。一次 `beginff` 之后约 80ms 跳第一次，
之后每约 1.05 秒再跳一次，每跳一次发一条 `POST /audioMode {audioMode:"default"}`。

所以**判断有没有生效，数 `/audioMode` 条数**，不要只看 `200`：

| burst 时长 | `/audioMode` 条数 | 实际效果 |
| --- | --- | --- |
| 3 秒（旧） | 3 | 一个手势拖了三段 —— "长按一次跳三次" |
| 0.9 秒（现在） | 1 | 一步 |

接收端这边看不到任何"移动"的痕迹：包速率恒定在 125 包/秒、`flushed 0`、没有
`SETRATEANCHORTIME`。这不是没执行，而是跳变发生在播放器内部，AirPlay 只从 `/audioMode` 通知里
漏一点出来。`200` 只表示"收到"，`/audioMode` 才表示"照做了"。

哪一环缺了，就是哪一环的问题：一行 `Wheel:` 都没有 ⇒ 按键没到这个 App（界面不在前台，或车机
把键给了自带播放器）；有 `not a skip key` ⇒ 车机用的是别的键码，那行里写着真实键码。


---

## 排错

界面底部的**运行日志**是主要诊断手段，也可以「复制」出来。

| 现象 | 排查方向 |
| --- | --- |
| iPhone 的 AirPlay 列表里看不到车机 | ① 看日志有没有 `mDNS: published "..." as _raop._tcp on ...:5000`。**没有这一行** → 见下一行；② 看日志 `Locks: wifi=... multicast=...` 是否都是 `true`，`multicast=false` 就是组播被拦，排查权限；③ 日志里有 `mDNS: registration failed (code N)` → 系统 responder 拒绝了这次注册，本版会自动重试 4 次，仍失败会在状态卡上显示；④ 车机是否真的在 iPhone 热点网段内（状态卡"监听地址"应是 `172.20.10.x`）；⑤ 热点是否开了"客户端隔离" |
| 日志有 `mDNS: published` 但 iPhone 还是看不到 | 广播确实发出去了，问题在链路上。① 车机是否真在 iPhone 所在的那张网上（`adb shell ip -4 addr` 看 `wlan0`）；② 车机是否拦了入向组播（日志 `Locks: ... multicast=true` 必须为真）；③ 热点是否开了"客户端隔离" |
| **看得到、能点，但一连就断开**（最常见） | 端口绑错了网卡。`adb shell cat /proc/net/tcp6`，看 5000（`0x1388`）的本地地址是否为全零（通配）。若显示某个具体 IP —— 尤其是 `10.x` 的蜂窝地址（`rmnet0`）—— iPhone 连 `192.168.43.1:5000` 会被直接拒绝。**成因**：车机自己开热点时 `wlan0` 是 AP 接口，`ConnectivityManager` 不把它算作"已连接的网络"，于是地址选择落到蜂窝网。**当前版本所有监听一律绑通配，不会再出现** |
| 看得到但点不动 / 一连就断 | 见下一行。也看日志有没有 `Could not unwrap the AES session key`（RSA 解封失败，确认 `et=0,1` 是否生效） |
| **连上瞬间就断，日志只有 `Control connection from` 紧跟 `Control connection closed`** | 发送端在等 `Apple-Response`。看 `RTSP <-` 那行里有没有 `apple-challenge=`：**有** → 我们没回或回错了（本版已实现，`RTSP ->` 那行之后应能看到 `Apple-Response`）；**没有** → 那是别的原因，看下一行的完整头 |
| 日志里 `RTSP <-` 下一行的 `hdr:` 打出了完整请求头 | 这是诊断发送端行为的**主要依据**。把这两行截出来就能看出 iPhone 到底发了什么、我们回得对不对 |
| 连上了但完全没声音 | 日志有没有 `First audio packet received`；有包但无声 → 看 `Dropping undecodable … frame` 那行里的编码名（`ALAC` 还是 `AAC-LC`）。**若是 `AAC-LC` 而本机没解码器，就是这行**；现在两种都有解码器，所以这行出现本身就说明「SETUP 说的格式」和「实际收到的格式」对不上 |
| **有杂音 / 一片沙沙声**（AP2，Apple Music 常见） | 编码用错了：日志里 `AP2: buffered stream: … ct=4 audioFormat=0x00800000` 表示发送端给的是 **AAC-LC 48 kHz**，而解码器按 ALAC 走就会每帧报错、偶尔蒙对几帧变成杂音。正确状态是 `AP2: audio format AAC-LC 48000Hz 2ch` ＋ `AAC decoder ready: AAC-LC 48000Hz 2ch … raw access units, csd-0=1190`。另外 `payload starts …` 那行会告诉你包是裸 access unit 还是带 ADTS 头 |
| 声音断续 / 卡顿 | 看 `decoded N packets, skipped M, recovered R, concealed C, queued K, buffered Xms, underruns U`。① **`buffered` 是听感余量**（还没播出去的音频），也是抗抖动能力的全部来源 —— 它薄（几十毫秒）时任何停顿都会断；正常应稳定在 `AudioPipeline.TARGET_CUSHION_MS`（250 ms）附近。② **`underruns` 是 AudioTrack 自己数的欠载次数**：它在涨 = 卡顿属实且是饿死，不是拥塞。③ `recovered` 是"等重传等回来了"的包数，`skipped` 是等不到、直接放弃的包数 —— 前者远大于后者说明重传链路在正常工作。④ **`buffered` 只降不升说明余量在被跳号吃掉**：每跳一个包永久少 8 ms，而发送端按 1 倍速推流、不会补回来，实测 13 分钟里余量掉 114 ms 而 `skipped` 恰好 +14（×8 ms = 112 ms，误差 2%）—— 跳号是余量**唯一**的消耗项。⑤ `concealed` 是"放弃的缺口里补上了仿真音频"的包数：现在每个被放弃的包都会写一份前一帧的副本顶上，所以跳号**不再消耗余量**，`buffered` 应当长期平稳而不是缓慢下滑。副本不是原样重放：它以**说话人正握着的那个采样**开头（`DECLICK_FRAMES`、2 ms 内过渡掉），整段衰减到静音，后面的真实包再从静音淡入 2 ms —— 缺了这一步，重放的那 8 ms 就是一段满电平的方波接缝，听感就是一声爆音。⑥ 余量若因极端丢包被吃到 `REBUFFER_FLOOR_MS`（40 ms）以下，管线会暂停并重灌到目标余量，日志里是一条 `Cushion down to Xms; refilling` —— 代价是那一下约 250 ms 的静音，换来的是后面不再连续欠载。**这条日志正常应当只出现一次**：如果同一秒里连着刷出好几条（旧版在 FLUSH 后刷过 10 条），说明重灌逻辑在自激 —— 队列里已经攒够一整份余量时暂停毫无意义，等重灌完`primed` 又没被清掉，下一个包就会再触发一次。⑦ **`tooLate` 是"重传回来了、但播放已经走过去"的包数**，用来区分两种放弃：`tooLate` 跟 `skipped` 同步涨 = `GAP_WAIT_MS` 还短，加长能救回来；`tooLate` 长期为 0 = iPhone 压根没回包，再等也没用，只能靠隐藏。只统计**差 64 个包（512 ms）以内**的迟到：重传应答会把几秒前已经播过的包再发一遍，全算进来会让这个数虚高到 `skipped` 的三倍，那这个判据就白看了 |
| 偶尔「爆音／咔哒」 | 几乎总是**跳号**（`skipped` 在涨）—— 波形被硬接一刀。判断方法：听的时候盯 `skipped`，它每次 +1 就是一声。要减少它，得让缺口尽量被重传补回来：`RaopAudioTransport` 现在对同一个缺口最多问 4 次（间隔 45 ms，最后一次在 135 ms 左右发出，仍落在 `GAP_WAIT_MS` 200 ms 内），限流时**放回队列**而不是直接丢弃（旧代码丢弃过，等于压根没问发送端），且 `RESEND_BACKOFF_MS` 已从 40 ms 降到 12 ms —— 包间隔才 8 ms，一次突发会同时压出好几个缺口，40 ms 的节流等于把第 4 个缺口拖到 160 ms 才问出去，早就错过播放点。如果 `skipped` 仍在涨，看 `recovered : skipped` 的比例和 `tooLate`：比例高且有 `tooLate` → 链路在丢包但重传能救，继续加 `GAP_WAIT_MS`；比例低且 `tooLate` 为 0 → iPhone 在忽略重传请求，只能靠隐藏 |
| 息屏后停住 | 先看日志 `Locks: wifi=true multicast=true`（Wi-Fi 锁已持有，链路不会被挂起）；都正常还停，就是车机的省电策略把服务掐了，按上面的「不受限制」设置放开 |
| 状态卡一直显示「等待网络…」 | 车机还没拿到 IPv4。去车机 Wi-Fi 设置确认已连上 iPhone 热点。**不用重开 App** —— 连上后服务会自己开始广播 |
| **按暂停有效，再按播放没反应（音乐不回来）** | 症状是「播放」按下去状态卡还停在「已就绪」但手机不理。看日志里暂停那一刻有没有 `AP2: TEARDOWN plist: {streams:[…]}` 紧跟 `AP2: audio stream stopped; the session and its event channel stay up` —— 有就是对的路径（流停了、会话还在）。如果日志是 `AP2: TEARDOWN — the session ends here` 或 `TEARDOWN received; closing the session`，说明这条被当成会话结束处理了，控制连接随之消失，后续按键全部落在 `protocol=null` 上。 |
| **Apple Music 在 AirPlay 2 下不播（连上后点播放毫无反应；切到 AirPlay 1 又正常）** | 先分清是哪一种，日志会直接给出：**① 有 `AP2: SETUP (streams)` / `First audio packet received`，但几秒后 `AP2: TEARDOWN`** → 流被手机自己撤了，看 `POST /feedback` 那几行是不是空应答（正确应答是 `AP2: /feedback — playing stream type 96 at 44100 Hz; saying so`）。② **通篇没有 `SETUP (streams)`**（只有 `RECORD — session open` 和每 2 s 一次的 `/feedback`）→ 手机压根不愿意建流，`/feedback` 与它无关，看 `/info` 能力表和事件通道：`supportedFormats.bufferStream` 必须带 AAC-LC 位（`0xC40000`，否则 Apple Music 判本机没法放），并且事件通道连上后要发 `updateInfo`（日志 `AP2: updateInfo posted on the event channel -> SENT`）。这条查询只有 AirPlay 2 的会话会发，AP1 从来不暴露这些问题。 |
| **AirPlay 2 下按上一首 / 播放暂停 / 下一首音乐直接停了** | 先看日志结尾：如果是 `AP2: events <- command reply RTSP/1.0 200` 之后才断，那是手机随后自己结束了会话（它在切歌/暂停，不是报错）。如果是 `AP2: sender closed the encrypted channel without sending a frame` 紧跟 `The sender closed the control stream`，那是事件通道的加密帧手机解不开——检查 `ChannelKeys.EVENTS` 是不是被“顺手统一”成了 control 的镜像（写 `Events-Read…`）。这正是本机踩过的坑。 |
| **拖一次进度条就缺一段音频（每次 seek 都听到一个坑）** | 这是暂停窗口被当成了播放。AirPlay 2 **不发 sync 包**，「发送端在不在播」只有 `SETRATEANCHORTIME` 的 `rate` 字段说了算（`rate & 1` 为 1 才播）。看日志里每次暂停有没有 `AP2: SETRATEANCHORTIME rate=0 — the sender's clock is stopped` 和对应的 `rate=1 … running`；缺了 `rate=0` 那一行说明这个请求落进了兜底分支，答了 200 却没看 body，管线于是在暂停期间照播不误。伴随症状是 `Cushion down to 21ms; refilling` 和 `underruns` 上涨。当前版本已在 `RaopSession.setRateAnchorTime()` 里驱动 `AudioPipeline.setPlayEnabled()`，单测 `SetRateAnchorTest` 钉住这一位。 |
| AP2 下按了按钮，状态卡停在「已送出 · 等手机应答」 | 手机收下了但没回话，或回话到了但事件通道已断。看日志有没有 `AP2: events <- command reply`；没有就去确认 `ap2Remote=true`、`event channel up` 两行都在。 |
| **AP2 拖进度条之后整条音频流哑了**（统计行不再出现） | 统计行 `received N packets, …` 在 `FLUSHBUFFERED` 之后**彻底消失**，说明之后的包全被当成过时数据丢掉了。根因是端点宽度不匹配：音频块的计数器只有 23 位，而 `flushUntilSeq` 是 32 位，比较前必须先 `& 0x7fffff` 窄化（shairport-sync 的 `handle_flushbuffered()` 就是这么做的），并用模 23 算术比较。当前版本已在 `util/SeqNo.kt` 处理；日志会打出 `endpoint 10299533 narrowed to 1910925` 供核对。若这行缺失或窄化值可疑，说明回到旧逻辑了。 |
| 「反向控制」一直是"等待 iPhone 提供"，点按钮没反应 | 说明 `_dacp._tcp` 没解析出来。先确认没在 AP2 会话里（状态卡「当前协议」）；再看日志有没有 `Remote control offered: DACP-ID=... Active-Remote=...`（有 = iPhone 确实把凭据给了）；再看有没有 `DACP: "iTunes_Ctrl_..." at ...:port`（有 = 端口解析成功）。只有前者没有后者 → mDNS 应答被拦，或这台 iPhone 没广播这个服务 |

日志里这些行是**正常**的：

```
Locks: wifi=true multicast=true
mDNS: published "<HEX>@BYD AirPlay" as _raop._tcp on /172.20.10.2:5000
ANNOUNCE: codec=ALAC 44100Hz 16bit 2ch frame=352 ...
SETUP: sender control/timing ports 6000/6001, our data/control/timing ports 54321/54322/54323
RECORD: playback started
Audio output started: 44100Hz 2ch, AudioTrack buffer ... bytes
```

iPhone 一连上，还会多出这两行（DACP 是否可用就看它们）：

```
Remote control offered: DACP-ID=CFAE09D9DA7527E9 Active-Remote=3951361179
DACP: "iTunes_Ctrl_CFAE09D9DA7527E9" at 172.20.10.1:53111 (matched by DACP id)
```

---

## 已知限制

- **AP2 反向控制可用**，但只到这程度：界面三个按钮与（前台时的）媒体键都能驱动 iPhone。
  曾经「一按就断」的坑已解决——根因是事件通道的 HKDF 密钥方向写反（`Events-Read…` 当成了写密钥），
  报文本身没问题。`ChannelKeysTest` 盯着这个不对称，别为了"统一"把它改成循环派生。
- **AP2 反向控制只在手机打开了事件通道时可用**。日志里 `AP2: event channel up` 之前按按钮，
  只会在状态卡上留下「命令通道未打开」。
- **AirPlay 1 只支持 ALAC 和 PCM**（按 `cn=0,1` 协商）。**AirPlay 2 多一种 AAC-LC**，
  由系统 `MediaCodec` 解（`AacDecoder`）；AAC-ELD、Opus 仍然解不出来 —— 日志里会明说
  （`compression type N has no decoder here`）。
- 单客户端：同一时刻只有一个 iPhone 能连，新连接会顶掉旧连接（这是 AirTunes 语义）。
- 音频延迟按 RAOP 默认节奏走，没有做额外的时钟同步补偿。听感延迟**等于**流水线手里握着多少音频（`AudioPipeline.TARGET_CUSHION_MS`，当前 250 ms）—— 余量就是延迟，**1:1，只能同增同减**：想更抗卡就得更慢，想更快就更脆。
- ALAC 解码器与 iOS 实际码流的对照验证是在代码级完成的，**还没有在真机真流上长时间跑过**。
- DACP 的请求格式、端口发现、命令集都是照 shairport-sync 的 `dacp.c` 写的；AP1 会话里
  `Remote control offered: DACP-ID=` 与 `DACP: "iTunes_Ctrl_..."` 两行日志是判断「这台 iPhone 给不给凭据」的唯一依据。
- 方向盘 next/prev 在 App 退到后台时收不到（车机把键交给前台窗口）。要解决得加无障碍服务，
  本版未做。
- AP2 不参与 PTP 时钟对齐、不做多房间同步，也不支持 HomeKit / Siri —— 它就是一个单机的音频出口。
