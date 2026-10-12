# GM 车机完整提取文件分析

## 结论

后续已根据本次证据完成可实现的无线流程修复并构建 0.19，见[对齐记录](wireless-alignment-0.19.md)。USB 身份等待尚未通过车机运行日志确认或解决；本篇保留静态分析时的证据范围。

`K:\Download` 这批文件有直接用途：已找到此前缺失的真正 CarPlay 服务、系统框架和原生协议实现。目录清单包含 6,693 个文件；本次重点检查 CarPlay、iAP2、蓝牙投屏与无线配置链路，没有把清单扫描等同于全部文件已反编译。

最有价值的发现是：**车机要求带传输标识的分组 `4300`，会先确认能力、关闭第一轮 iAP2，再开启投屏用的第二轮。第二轮可能等待 USB 传输标识，尚不能仅凭手机日志证明这就是本次失败根因。**

## 可用材料

| 原始路径 | 实际用途 |
| --- | --- |
| `K:\Download\system\priv-app\DelayedWKSApp\DelayedWKSApp.apk` | 真正的 `com.gm.domain.server.delayed`，30,940,427B，版本 12/code 32。包含 `WirelessCarPlay`、`IAPManager`、`CarPlayAvailability`、`CarPlayStartSession` 与蓝牙投屏服务。不是资源覆盖包。 |
| `K:\Download\system\framework\info3.jar` | 包含 `gm.carplay.CarPlayServiceManager`、连接管理接口及 `gm.iap2.auth.IAPAuthenticator`；`system/etc/permissions/info3_library.xml` 明确把 `info3` 映射到此 JAR。 |
| `K:\Download\system\lib64\libNmeIAP.so` | ARM64 iAP2 消息解析和编码。已跟踪 `4300` 分组解析与 `4301` 编码函数。 |
| `K:\Download\system\lib64\libNmeCarPlay.so` | Cinemo CarPlay/AirPlay 原生实现，可确认实际插件分支与 AirPlay 版本。 |
| `K:\Download\system\lib64\libNmeSDK.so` | JNI 桥接，可核对 Java 参数名与原生结构字段，避免凭反编译变量名误判。 |
| `K:\Download\system\etc\init\init.carplay.rc` | 主要提供 USB/NCM/FFS 初始化线索；不是无线配置下发的核心实现。 |

原始文件保持原样。反编译文件、IDA 数据库与分析辅助脚本保存在忽略的 `artifacts/headunit-filesystem-20261011/`，不将整份厂商实现复制进项目代码。

## 实际版本证据

`libNmeCarPlay.so` 包含 `official/2024.w32-M28_update_CommPluginR16`，初始化函数 `0x6a4c0` 调用 `0x10679c`，并把返回值用于 `Create() CommPlugin R16 (AirPlay v%s)` 日志；该版本函数返回 **`450.14.1`**。

因此，可以明确称为 **Cinemo CommPlugin R16 / AirPlay 450.14.1**。这不等于已取得独立的 CarPlay SDK 版本声明；APK 的版本 12、Android 12、iAP2 会话 v2 也不能代替它。不能直接把手机的 `sourceVersion` 改成车机版本，双方角色与实现不同。

## `4300` 的实际接受条件

`libNmeIAP.so` 的 `0x24f170` 按根参数 **0=有线组、1=无线组** 读取嵌套参数，组内 **0=可用状态、1=传输标识字符串**。`0x24f3a0` 将解码后的结构交给上层回调。

服务的 [CarPlayAvailability.java](/D:/git/carplay-android/artifacts/headunit-filesystem-20261011/delayed/sources/com/gm/server/iap2/feature/CarPlayAvailability.java:36) 同时要求无线组 `Valid`、`Available`、`TransportIdentifierValid` 为真，才报告无线 CarPlay 可用。

| 手机声明 | 与此车机实现的对照 |
| --- | --- |
| 当前完整 37B：`group(1) { u8(0,1); string(1,手机蓝牙地址) }` | 结构及字段类型符合原生解析器；仍需确认车机实际回调状态。 |
| 0.13 扁平 33B：根 `u8(0,1)` 与根 `string(1,地址)` | 两个字段被当作组处理，不能按预期得到无线属性；这是确定的格式差异。 |
| 0.15 最小 15B：无线组只有可用状态 | 缺少标识，不能满足此服务的 `TransportIdentifierValid` 条件。 |
| 数值组件编号代替字符串标识 | 上层将标识按字符串读取，不能据此宣称兼容。 |

这为 0.13/0.15 不再被识别提供了实现依据；**不代表当前 37B 已完成全部无线启动条件。**

此外，[DeviceNotifications.java](/D:/git/carplay-android/artifacts/headunit-filesystem-20261011/delayed/sources/com/gm/server/iap2/feature/DeviceNotifications.java:65) 明确忽略旧 `WirelessCarPlayUpdate` 与 `DeviceTransportIdentifierNotification` 回调，也就是 `4E0D/4E0E`。继续调整这两个通知不能替代有效 `4300`。

## 为什么验证之后又重新检测

[WirelessCarPlay.java](/D:/git/carplay-android/artifacts/headunit-filesystem-20261011/delayed/sources/com/gm/server/wirelessprojection/WirelessCarPlay.java:375) 中，第一轮能力确认成功后会调用 `closeIAPConnection()`，进入 `DEVICE_CAPABLE`；随后 `doConnectWifi()` 创建新的 `IAPWorkThread(true)`。

`WCPBtTransport.close()` 只停止这一轮数据消费，不主动关闭 RFCOMM。因此，车机实现确实支持在同一物理蓝牙连接上关闭旧 iAP2、启动新实例。0.17 的重新检测和第二轮识别/签名验证与此流程吻合，不能一律解释为请求被拒绝。原有新实例须重新识别和认证的处理仍有依据。

## 第二轮等待身份属性：优先核实的阻塞点

这里有一条明确的静态分支：

1. 第一轮使用标志 **65**，不要求 USB 传输标识。
2. 第二轮若没有已保存的有效 USB 标识，使用标志 **67**，增加 `FLAG_DETECT_USB_SERIAL_NUM=2`，调用 `startAuth()`，投屏启用时其内部预算为 60 秒。
3. [IAPAuthenticator.java](/D:/git/carplay-android/artifacts/headunit-filesystem-20261011/info3/sources/gm/iap2/auth/IAPAuthenticator.java:22) 的通知要求认证结果已返回，且请求的属性齐备。缺少属性 2 时，回调不会唤醒等待者；具体停留时间受回调先后与等待状态影响。该控制流已用 JADX fallback 指令输出复核。
4. USB 标识由 `CarPlayAvailability` 的**有效、可用且带标识的有线组**提供；旧 `4E0E` 通知在此实现中被忽略。当前无线发送声明没有有线组。
5. 车机外层无线连接还有独立 **30 秒**超时；所以单独扩大手机等待时间并不保证解决问题。

**已确认的是上述等待分支存在；未确认的是这次会话是否缺少已保存标识并进入等待。** 需要车机同次日志中的 `startAuth():67`、`attrs isn't ready for :2`、`onUsbSerialNum`、`onAuthResult()` 与 `mAuthenticator.startSession(): Begin` 来判定。不能为了满足分支而编造 USB 标识或未实现的有线投屏能力。

## 无线配置何时发送

[CarPlayStartSession.java](/D:/git/carplay-android/artifacts/headunit-filesystem-20261011/delayed/sources/com/gm/server/iap2/feature/CarPlayStartSession.java:144) 要求已收到有效无线可用状态，且车机热点配置与热点接口 IP 已就绪，然后才调用 `sendCarPlayStartSession(true)` 发送 `4301`。热点 IP 来自接口的 IPv6 链路本地地址。

`4301` 原生编码与当前手机解析的字段映射相符：无线组含 SSID、口令、信道、IP 与安全类型，根 2 为端口、3 为设备标识、4 为公钥、5 为 sourceVersion。公钥与设备标识的结构偏移另外通过 `libNmeSDK.so` JNI getter 汇编复核，未发现这两个字段在手机端颠倒。发送失败、无线状态未就绪、热点配置或 IP 缺失都有不同日志分支。

蓝牙方向也已核对：车机监听 `…caff`，主动连接手机 `…cafe`；现有手机端的方向匹配。车机有蓝牙冲突处理，不能把新 socket 到达等同于可直接取代已认证通道。

## 对本项目的直接用途与验证范围

- 保持带真实手机地址的完整分组声明，现有默认已符合该结构；将扁平/最小方案继续视为实验选项，不能作为这台车机的推荐路径。
- 两轮独立识别和认证已有车机实现依据；旧认证状态、序列与队列不能带入第二轮。
- 下一定位重点是第二轮身份属性等待，以及车机是否真正调用 `startSession()`；若已经调用，再检查无线可用状态、热点配置和 IPv6 就绪分支。
- 手机只能报告自己收到的消息与状态，不能从一个 ACK 推断车机内部属性齐备、配置已发送或投屏成功。

本次完成 manifest、框架实现、关键 Java 回调、两份原生库交叉引用/反编译及 JNI 字段复核。`info3.jar` 全量 JADX 有三个错误；DelayedWKSApp 全量导出在 4,185/4,410 个任务处内存不足，关键上述类已导出，缺失的参数类另外单独导出。没有宣称全部方法反编译成功。

本次未修改手机协议或生成新版本 APK，未修改、安装或执行车机组件。结论来自静态实现与已有 0.17 手机会话对照，仍无这批文件对应的实时车机日志，不能宣称实车故障已修复。

## 输入 SHA-256

| 文件 | SHA-256 |
| --- | --- |
| DelayedWKSApp.apk | `1a9f29b99687ceae99527c8ee798f78ca7ab44a039f380e810226e8de6114748` |
| info3.jar | `3c45ee4ba00a48c86e0bd5ac2d5ea01ccdcb01b1e472c6471e6bf5d811ec8249` |
| libNmeIAP.so | `ee1917ad64adb0f3770d54d84e2be99f8e150417b01c08a3822cf0c87c61fffb` |
| libNmeCarPlay.so | `73c2fe574bb8b3c8e4a15e0f69568975111e5a7a3e8b9c2e2ebb60ae7c691394` |
| libNmeSDK.so | `53c4fe6dcba9caf1dcdb318c6fb0b1afb1c2cfdc8bbdbf6f5bb08e48cea88551` |
