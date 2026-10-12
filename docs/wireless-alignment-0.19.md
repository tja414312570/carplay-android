# 无线发送流程对齐：0.19

## 交付与范围

组件 APK：[carplay-0.19.apk](/D:/git/carplay-android/artifacts/carplay-0.19.apk)。包名 `com.baidu.carlife.vivo`，版本 `8.8.4-carplay.0.19` / `100029`。目标仍为手机主动发起无线连接；本次未增加有线 CarPlay，也未声明不存在的 USB 身份或有线可用状态。

本次修复依据是用户提取的 GM 服务与原生库，详见[车机文件分析](headunit-filesystem-analysis-20261011.md)。实现和本地回归已完成，实车连接仍未证明成功；GM 的条件性 USB 身份等待是尚未确认是否实际触发的兼容阻塞点。

## 已对齐的流程

| 环节 | 0.19 行为与原有行为的对应 |
| --- | --- |
| 手机主动连接 | 保留安全 `…caff` 拨号、手机 `…cafe` 注册、选定已配对车机过滤、同步静默回退和原清理策略。 |
| 蓝牙识别与认证 | 保留根证书/配对公钥绑定、每轮新挑战和签名验证。同一 RFCOMM 的一次 GM 新协议实例仍共用原 30 秒预算，身份变化与重复重新检测仍失败。 |
| 无线声明 | 新默认 `MODERN_WIRELESS` 使用完整 37B 分组 `4300` 与真实手机蓝牙地址。可选通知按接收能力发送，不再强制发送此车机已忽略的 `4E0D/4E0E`。旧参考方案继续保留。 |
| 设置升级 | 组件设置一次升级到修订 19，使用手机主动/完整无线默认；蓝牙地址、选定车机、身份、配对、信任、后管地址和日志配置保留。之后手动选择继续生效。 |
| Wi-Fi 安全类型 | 仅对已观察的 GM A2LL 固件按其实际热点映射处理：2=WPA2、3=WPA3-SAE、4=过渡模式。SAE 仅模式不降级；过渡模式依据手机真实 SAE 能力选择。其他车机保留原映射。 |
| IP 与发现 | 有有效 `4301` 地址/端口时直接连接，不强制等待 Bonjour。IPv6 链路本地地址使用手机实际接口 scope；不将车机接口名当作手机接口。保留手机控制服务发布、连接邀请及旧版 Bonjour 发现。多地址共用原 10 秒连接预算，成功地址用于后续所有通道。 |
| AirPlay 配对 | 保留真正的配对和验证、公钥变更拒绝、控制加密与旧版 MFiSAP 路径，没有改写为固定成功。 |
| Wi-Fi iAP2 | GM 的 `WIFIAccessory` 不提供另一个 `CarPlayStartSession`。现在只重新识别同一稳定身份、验证新挑战并发送支持的通知；不重复等待 `4301` 或编造端点。 |
| 交接与媒体门槛 | 新 readiness future 由真实 Wi-Fi 控制认证完成驱动，并等待认证完成通知的链路 ACK。之后清理蓝牙控制实例。媒体等待沿用最多 15 秒同步加 30 秒认证的预算；取消和认证失败会中止启动。ACK 只证明链路收包，不等于应用接受或画面显示。 |
| 投屏来源 | 使用协商的宽、高、帧率，真实准备并启动已授权来源，再建立媒体与 `RECORD`；早期编码输出使用原有有界队列。Surface、系统录屏同意、宿主调用验证、编码器与音频清理保持。 |
| 媒体与输入 | 保留真实 NTP、协商端口、加密密钥、streamConnectionID、视频/音频队列、资源转移、触控/按键和反馈。计数来自实际发送；不把通道建立当作首帧显示。 |

HTTP 控制邀请现在按 HTTP 回应，RTSP 请求仍按 RTSP 回应。记录在日志中的进度区分识别、验证、端点、独立 Wi-Fi 认证、来源准备和实际发送；使用现有手机仪表盘、事件日志及签名上传路径。

## 仍需确认的 GM 分支

第二轮若没有保存的 USB 传输标识，GM 可能以标志 67 等待属性 2；纯无线声明不提供有线可用标识。此次没有通过虚假有线能力、随机串号、空串或认证跳过来满足它，也没有延长蓝牙预算假装解决。

下一次运行应结合车机同次日志中的 `startAuth():67`、`attrs isn't ready for :2`、`onUsbSerialNum`、`mAuthenticator.startSession(): Begin`。手机日志只能证明自己的识别、签名与收包，不能替代这些内部状态。若该分支实际触发，严格仅无线声明与此车机实现存在兼容缺口，本版本不能据此保证连接成功。

## 验证

```powershell
.\gradlew.bat --console=plain -q --max-workers=1 "-Pkotlin.incremental=false" `
  :app:testStandaloneDebugUnitTest :core-iap2:testDebugUnitTest :core-airplay:testDebugUnitTest `
  :app:assembleVivoComponentDebug :app:assembleStandaloneDebug `
  :app:lintVivoComponentDebug :app:lintStandaloneDebug :core-iap2:lintDebug :core-airplay:lintDebug
```

本轮指定三个测试任务共 **229 项、32 个套件，失败/错误/跳过均为 0**。两个 flavor 构建与四项 Lint 通过，存在非阻断警告。统计不包含旧目录残留的报告。

新增本地串联测试使用新生成的测试证书、真实签名、原始配对响应器、实际 loopback socket、原始 iAP2/tunnel/video/audio 解码器，覆盖蓝牙信任绑定与 `4301`、AirPlay 配对、Wi-Fi 新证明、认证 ACK、`RECORD`、视频解密、UDP 音频及资源转移。另覆盖失败证明、取消、来源准备失败时禁止媒体启动，HTTP/RTSP、IPv6 scope、Wi-Fi 安全选择和设置保留。这些是隔离的协议测试，不是车机运行或画面渲染证明。

APK v2 验签通过，签名证书 SHA-256 保持 `fe393e473787ae190e270a3971a46647b7eba6ecb394237ad5e2bca24b766194`。通过 manifest XML 树核对版本、包名、CarLife 活动/服务及 `com.vivo.car.CAR_SERVICE`；旧 `aapt dump badging` 对 Android 框架图标引用有解析错误，0.18 同样存在，未把它当成新的打包失败。

APK SHA-256：`3d20d3c5a89664d1b647044582aa1685474517df8948d06d7824804d428a370a`。

当前 ADB 无设备，未安装到 vivo，也没有 0.19 实车或新增手机日志上传验证。本次保留现有后管、日志字段与上传授权方式。

API 核对先查询 Context7，但未匹配对应条目，再使用 Android 官方 [Wi-Fi 请求接口](https://developer.android.com/reference/android/net/wifi/WifiNetworkSpecifier.Builder)、[Network.bindSocket](https://developer.android.com/reference/android/net/Network) 与 [IPv6 scope](https://developer.android.com/reference/java/net/Inet6Address) 文档；协议接受条件以本地提取实现为依据。
