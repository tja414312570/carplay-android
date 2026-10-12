# 2026-10-12 09:30 后投屏日志分析

## 范围与结论

按 UTC+08:00 检查后管上传时间在 09:30 之后的所有 ZIP：16 份、16 个不同会话、1,331 条事件，事件文件哈希也全部不同。当前最新上传时间为 09:43:49；全部来自 vivo V2408A / Android 16，组件包版本 `8.8.4-carplay.0.27`。读取服务实际使用的 `H:\iccoa-license-manager\runtime\log-uploads`，没有采用仓库内旧日志目录。

先创建 Git 检查点 `9ab9074`。现有源码已由 `b929ff1` 提交，本次检查点没有新的源码差异。本轮新增此分析记录，没有修改运行代码、构建或安装应用。

系统录屏已实际发送 1,016 帧，与用户确认车机显示画面一致。原生宿主入口仍被组件版本／签名校验拒绝。成功投屏会话最终由程序的 Wi-Fi iAP2 同步超时结束；开启蜂窝后主控制连接继续有响应，尚不能把此故障直接归为车机 socket 被切到蜂窝。

## 全部会话

时间列是手机会话开始时间，不是上传时间。原生 4 场、系统录屏 12 场；最终 FAILED 14 场、手动 STOPPED 2 场。失败记录中有一场已成功发送画面。

| 开始时间 | 会话前缀 | 画面来源 | 已发送帧 | 最终结果 |
| --- | --- | --- | ---: | --- |
| 09:33:00.786 | 2f3d28ee | 原生宿主 | 0 | 蓝牙三个同步方案均无有效 SYN |
| 09:33:40.428 | 47919558 | 系统录屏 | 0 | RFCOMM read_failed |
| 09:33:58.036 | 3847a8bc | 系统录屏 | 0 | 用户停止；第二方案见到 SYN，但未完成同步 |
| 09:34:34.340 | b7b845e8 | 原生宿主 | 0 | RFCOMM read_failed |
| 09:35:05.590 | 96d89e0b | 原生宿主 | 0 | RFCOMM read_failed |
| 09:37:27.707 | e607a5d8 | 原生宿主 | 0 | WLAN、配对和 SETUP 后，查询宿主接口被校验拒绝 |
| 09:38:08.486 | 8eca3920 | 系统录屏 | 0 | RFCOMM read_failed |
| 09:38:42.768 | 8411e78a | 系统录屏 | 0 | 用户停止 |
| 09:39:04.714 | e36130a7 | 系统录屏 | 0 | RFCOMM read_failed |
| 09:39:33.632 | dd6ebf4a | 系统录屏 | 0 | 编码启动、视频 SETUP 后，固定 AAC 音频能力检查失败，尚未 RECORD |
| 09:40:07.249 | 1ee4de6a | 系统录屏 | 0 | 蓝牙三个同步方案均无有效 SYN |
| 09:40:42.967 | 0f67194a | 系统录屏 | 0 | RFCOMM read_failed |
| 09:41:04.086 | dab0d9a8 | 系统录屏 | 0 | WLAN 已获得；旧配对验证读超时，重新配对也读超时 |
| 09:42:03.325 | e8364ce7 | 系统录屏 | 0 | Wi-Fi 网络请求拒绝或超时，未获得可用 WLAN |
| 09:42:59.637 | d14769a3 | 系统录屏 | 0 | RFCOMM read_failed |
| 09:43:22.558 | 679574eb | 系统录屏 | 1,016 | 首帧、画面和反馈均运行，随后 Wi-Fi iAP2 同步超时 |

这些结果不能统一解释为网络路由问题：9 场停在蓝牙阶段，2 场用户主动停止，其余分别涉及宿主授权、音频能力、配对响应、WLAN 请求和 Wi-Fi 控制同步。

## 成功投屏后断开的精确时间线

会话 `679574eb`：1,016 帧、4,757,521 字节（约 4.54 MiB），丢帧计数 0、音频包 0。车机声明 `sourceVersion=450.14.1`，未提供 CarPlay SDK 版本。普通 WPA3 WLAN 成功，实际接口 `wlan0`、ifIndex 24，IPv4/IPv6 均存在；本次使用已认证配置下发的 IPv6 端点。

| 手机时间 | 相对时间 | 已记录事实 |
| --- | ---: | --- |
| 09:43:30.119 | 7,561 ms | WLAN 可用，固定选定 Network |
| 09:43:31.592 | 9,034 ms | type 130 无可用独立端口，采用 RTSP iAPSendMessage 路径 |
| 09:43:31.593 左右 | 9,035 ms 左右 | 开始 Wi-Fi iAP2 同步等待 |
| 09:43:31.850 | 9,292 ms | RECORD 返回 200 |
| 09:43:31.881 | 9,323 ms | 首帧发送，进入 STREAMING |
| 09:43:35.475 | 12,917 ms | 蜂窝请求的 onAvailable 到达 |
| 09:43:37.891、40.896、43.901 | 15,333 / 18,338 / 21,343 ms | 蜂窝出现之后，/feedback 继续返回 200 |
| 09:43:46.202 | 23,644 ms | /command 仍返回 200；期间帧数继续增长 |
| 09:43:46.594 | 24,036 ms | Wi-Fi iAP2 同步超时，程序关闭整个连接 |

源码 `WirelessConnection.kt:368` 对 `runtime.awaitReady(15000)` 失败抛错，并在控制工作线程 catch 中关闭会话。约 9,035 + 15,000 = 24,035 ms，与实际错误时间吻合。蜂窝出现后还有约 11.1 秒的控制应答和画面发送。

这是本场断开的直接触发原因。日志没有记录系统默认网络的变化、每个 socket 的网络标记或 Wi-Fi 请求 onLost 的独立事件，因此不能排除其他网络切换缺陷；但本场已证明蜂窝出现后主控制连接仍可用。蜂窝 onAvailable 本身也不等于默认网络已切换或外网已验证。

## 控制通道的问题与证据边界

`AirPlaySender.kt:125` 在 type 130 没有可用端口时使用 RTSP `POST /command` 发送 iAPSendMessage。该路径收到多次 200、正文长度 42 字节；这只能证明 RTSP 请求得到响应，不能证明 iAP2 SYN、识别和签名验证完成。

当前发送处理器没有解析这些响应正文；控制数据接收依赖车机主动发送的 iAPSendMessage，再由 `PhoneIapTunnel.offer()` 入队。本场未完成 Wi-Fi iAP2 同步，且缺少该通道的详细收包/解析计数。需要核实车机实际支持的交接方式，以及返回数据是在响应正文、事件连接还是独立数据流中。不能仅根据 42 字节长度猜测正文内容，也不能仅延长超时或去掉验证来宣称修复。

## 普通 Wi-Fi 与蜂窝的路由核对

用户提出的分流方式可用：车机通信固定在本次车机 Wi-Fi 的 `Network`，外网请求保持系统默认网络或显式蜂窝网络。不需要 P2P，也不应硬编码车机 IP、`wlan0` 名字或 ifIndex；使用实际 LinkProperties 和已认证配置中的地址，保留 IPv6 scope。

| 通道 | 当前源码 |
| --- | --- |
| RTSP、配对、事件、视频及有独立端口时的 type 130 TCP | 共用连接工厂，connect 前 `network.bindSocket(tcp)`，不回退到未绑定 socket |
| NTP 时钟与媒体音频 UDP | 共用绑定回调 `network.bindSocket(datagram)` |
| RTSP iAPSendMessage 和 /feedback | 复用已绑定的 RTSP socket |
| Bonjour 与车机控制邀请监听 | 绑定选定 Wi-Fi 本地地址；尚未提供统一 Network 绑定与切换证据，需补查覆盖 |
| 后管 HTTPS 日志上传 | 普通 URL.openConnection，保持默认网络，未强制走车机 Wi-Fi |

源码中没有 bindProcessToNetwork 或 setProcessDefaultNetwork。按 Android 的 [Network.bindSocket 文档](https://developer.android.com/reference/android/net/Network#bindSocket(java.net.Socket))，已绑定的 socket 使用所选 Network；默认网络变化不是它改走另一张网卡的依据。这里的“固定网卡”应是固定当前会话获得的 Wi-Fi Network，而非永久固定接口字符串。

需要补充默认网络回调、选定 Wi-Fi 的 capabilities/LinkProperties/onLost、各通道本地地址族与绑定 Network、Wi-Fi iAP2 收发字节/同步状态。默认蜂窝变化应是观察事件；选定车机 Wi-Fi 实际失效才触发相应连接失败。回调诊断应使用回调传来的属性，避免同步读取属性的竞态，见 [Android NetworkCallback 文档](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback)。

## 原生组件及其他独立问题

- `e607a5d8` 在 6,885 ms 被宿主拒绝，前面的配对/SETUP 已成功；失败点在读取原生投屏 Binder 描述符，早于 prepareCast。结合此前检查的宿主 ComponentVerifier，是组件版本／签名认可问题。手机日志不能区分登记签名、在线版本查询或缓存的具体拒绝分支；不能通过换 Wi-Fi/P2P 或改 Surface 参数解决。
- `dd6ebf4a` 未匹配到发送端固定的 AAC-LC 48 kHz 双声道能力。源码同时限定 type=100、audioOutputFormats 和一个固定位；应核对实际 /info 格式并进行协商，不能据此断言车机完全没有音频能力。
- 成功会话协商 30 fps，但实际发送采样大多约 60 fps，最高约 113.8 fps。初期高值可能包含队列排空；持续超出协商值需要检查编码/发送帧率约束。这是独立的性能与兼容问题，不是本场超时原因的证明。

建议下一轮同时对齐车机实际控制通道及双向数据解析，并完善 Wi-Fi Network 的统一传递、绑定覆盖与切换诊断。原生宿主接入按实际授权契约处理；音频和帧率另行协商优化。身份、签名、公钥绑定与故障清理都应保持有效。
