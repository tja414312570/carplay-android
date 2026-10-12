# 2026-10-10：车机未识别 CarPlay 的日志调查

## 20:38 首轮日志事实与限制

已读取现行后管 `https://zncz.zj2.natnps.cn` 保存的六份当日上传包，共五个会话。最新会话 `941c3814-84dc-47b8-ae1d-84b0494fa6b7` 开始于 20:38:07（UTC+8），上传时经过 11.179 秒，停在 `BLUETOOTH`，内容为“等待已选择车机的蓝牙连接”。

| 会话前缀 | 实际记录 |
| --- | --- |
| `27497e13`、`d9d0073d`、`fcfa25f2` | 组件 8.8.4-carplay.0.2 在启动前失败：尚未收到 Jovi 宿主调用；`d9d0073d` 上传两次 |
| `941c3814` | 蓝牙阶段的运行中快照，没有 iAP2 同步成功、身份识别或认证记录 |
| `1a6a0bdb` | 蓝牙阶段等待后，在 28.068 秒由用户停止 |

旧版没有记录 RFCOMM 接受成功与输入流就绪。因此最新快照不能区分“仍在监听”和“已经接受连接但等待 iAP2 同步”，也不能证明车机明确拒绝了手机。上述会话均未记录进入证书认证、Wi-Fi 或媒体阶段；不能将其归因为根证书或视频编码问题。

## 与小米参考包的已确认差异

对照文件为 `F:\BaiduNetdiskDownload\carplay-xiaomi-carlife-v11.apk`，定向反编译结果位于 `F:\BaiduNetdiskDownload\carplay-sender-reference-20261009`。

1. **漏掉主动连接。** `ConnectionService$onStartCommand$4.smali` 的 `Outbound accessory RFCOMM; secure SDP lookup` 分支调用 `createRfcommSocketToServiceRecord`，连接车机 UUID `00000000-deca-fade-deca-deafdecacaff`。其超时协程使用 `0x4e20`，即 20,000 毫秒。传输失败才转到手机 UUID `00000000-deca-fade-deca-deafdecacafe` 的监听。此前本项目只有手机监听。
2. **画面入口阻断蓝牙启动。** 本项目此前强制先收到 Jovi 启动参数，按钮才启动无线连接。日志直接记录了该阻断。蓝牙握手与宿主提供原生画面应分别等待。
3. **旧诊断缺少连接证据。** 服务启动的会话没有完整版本/入口信息，RFCOMM 连接及流状态回调没有接入日志，影响区分不同阶段。

参考包还包含 EIR/HFP 实验 Hook，但组件 provider 路径将 `eirIdentityExperiment` 和 `nativeHookModify` 设为 false；Hook 默认观察，并限制系统版本、指纹与库哈希。这不能证明“小米能识别”必然依赖这些 Hook。当前也没有同一车机下的参考包日志或蓝牙 HCI 抓包可作比较。

## 本次修复与行为保留

- 先安全连接选定且已配对车机的 `…caff`，20 秒失败后保留原 `…cafe` 手机监听、45 秒等待、地址与配对状态过滤。权限异常或用户取消不触发回退。
- 组件可先启动蓝牙。原生画面在媒体协商后最多等待真实宿主入口 60 秒；继续执行宿主签名、服务权限、回调 UID 检查，不制造投屏授权或宿主参数。
- 保留独立版系统录屏授权、CA 验证、公钥绑定、协商显示参数、音视频编码与清理；绑定和关闭串行，避免取消后才绑定宿主造成资源泄漏。
- 日志新增版本、包名、实际启动参数来源、蓝牙前置状态、主动连接结果、SDP 注册、接受连接、流就绪和 iAP2 等待状态。

连接超时通过关闭实际 socket 终止阻塞操作，依据 Android 的 [BluetoothSocket 文档](https://developer.android.com/reference/android/bluetooth/BluetoothSocket)。回归测试覆盖主动成功、IOException 回退、超时、取消竞争、权限失败和清理失败。

## 21:02：0.3 真机日志与下一步修复

后续三份上传包来自两个实际会话，版本均为 `8.8.4-carplay.0.3`：

| 会话 | 已证实的阶段 |
| --- | --- |
| `68eb382f-4597-4d7f-8435-489bb315473c` | 46 ms 发起主动连接，116 ms 成功（70 ms）；117 ms 确认 connected=true / bondState=12；119 ms 流就绪；10,123 ms iAP2 ready=false |
| `23f9eebe-2d5f-4c63-be7a-2ba5777b8f5e` | 54 ms 发起主动连接，386 ms 成功（332 ms）；387 ms 流就绪；10,389 ms iAP2 同步失败；自动/手动两份上传均为此会话 |

这证明主动连接修复已生效，当前阻塞在 iAP2 同步。0.3 只记录了 CSM 就绪结果，未记录检测/SYN 的实际收发；媒体帧/字节计数为零不能用来推断蓝牙没有传输数据。两个会话也都尚未收到原生画面宿主入口，但当前失败发生在画面准备之前。

定向分析 `Iap2Probe.java`，其主协程反编译失败时以 `Iap2Probe$run$2.smali` 为准；`BluetoothSyncExperiment.java` 与 `Iap2Codec.java` 提供同步参数。组件路径首先使用 accessory-first：等待车机检测，回复一次，等待车机 SYN，再回复 SYN+ACK。无检测期限为 `0x2ee0`（12,000 ms），整体协商期限为 `0x61a8`（25,000 ms）。本地控制 session=10，同步参数为 `01 05 08 00 05 dc 00 49 1e 03 0a 00 02`。

0.3 直接沿用 receiver 的参数立即发检测+SYN（window=4、maxLength=65535 等），等待 10 秒；它没有上述 responder 路径。0.4 新增两种明确的手机配置，最后保留原 controller 配置。收到有效 SYN 后采用车机实际声明的控制会话和窗口，核对同步确认号；无有效 SYN 的超时才关闭当前协议和 socket，重新连接后换方案。现有数据重传、队列、资源所有权、认证和接收端默认行为保留。

新诊断记录协议边界完成写入字节数、接收字节数、状态、有效 peer SYN 和真实协商参数，不记录控制载荷。14 项新增回归测试验证原始黄金输出、参考 SYN 参数、SYN+ACK、分包/直接 SYN、错误校验和、错误确认号、reset/无效协商、协商会话路由、计数、诊断回调故障及阶段期限。

## 构建记录与验收边界

新版为 `8.8.4-carplay.0.3` / `100013`，交付 `artifacts/carplay-vivo-carlife-8.8.4-carplay.0.3-debug.apk`。两个 app flavor 构建通过，87 项 JVM 测试全部通过，app 两个 flavor 与修改的两个库的 Lint 通过；APK 签名验证通过且证书不变。SHA-256：`84023dcd6bc15018cfbb364c24411eeb0e6065b3e70c333e9dfaac7b0e9e2211`。

构建、JVM 测试与 Lint 只能验证代码；需要安装后在同一车机旁复测，取得主动/监听连接结果、iAP2 同步、身份认证及实际显示记录，才能确认车机识别已恢复。本次 ADB 设备列表为空，未把新包安装到手机。

最新版本 `8.8.4-carplay.0.4` / `100014`：`artifacts/carplay-vivo-carlife-8.8.4-carplay.0.4-debug.apk`。两个 app flavor 构建、101 项 JVM 测试、两个 app flavor 与 core-iap2 的 Lint 通过；APK 签名验证通过，证书仍为原 debug 证书。SHA-256：`dc12ffcb8020ba930eae2dc7c052580f6ff6772dba30186ba2894da036177b71`。0.4 尚未安装或实车验证，不能把测试通过解释为车机已识别 CarPlay。
