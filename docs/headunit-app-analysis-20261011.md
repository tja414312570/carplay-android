# GM 车机 CarPlay 应用分析

## 结论

后续 `K:\Download` 完整提取已补齐真正的服务、框架和原生库；见[完整提取分析](headunit-filesystem-analysis-20261011.md)。已确认分组 `4300` 接受条件、两轮 iAP2 生命周期，以及第二轮身份属性等待的可疑分支。以下保留最初三个 APK 的分析范围和证据。

最初三个车机 APK 有助于定位调用链和真正的系统服务，但没有包含该服务的连接协议实现。不能用文件名中的 `1.0` 或 `12` 推断 CarPlay SDK 版本，也不能从界面的通用失败回调判断是哪条无线消息被拒绝。

| 文件 | 包名和实际角色 | 已确认内容 |
| --- | --- | --- |
| `Apple CarPlay_1.0.apk` | `com.gm.hmi.applecarplay`，车机 HMI | 版本 1.0/code 1；一个 1,354,656B DEX，无打包的原生库；使用系统 UID；负责画面、焦点、导航卡片和系统状态回调 |
| `brand.cadillac.app.applecarplayservice_12.apk` | `brand.cadillac.app.applecarplayservice`，资源覆盖包 | 无 DEX、无原生库；overlay 目标为 **`com.gm.domain.server.delayed`**；主要是品牌图标、字体、颜色和尺寸资源 |
| `cadillac.vcd_cad_ff1.applecarplay.flow_12.apk` | `cadillac.vcd_cad_ff1.applecarplay.flow`，资源覆盖包 | 无 DEX、无原生库；overlay 目标为 `com.gm.hmi.applecarplay`；提供 Cadillac 界面和导航图形资源 |

两个覆盖包的 manifest 版本名称为 12、versionCode 为 32，与协议能力或 SDK 版本没有可据此建立的映射。部分资源引用显示 `unknown_ref: 20...`，表示缺少外部资源表，不能将这些引用解释为协议常量。

## 可追踪的调用链

```text
AppleCarPlayProjectionActivity / 界面广播接收器
  └─ Y.h（CarplayAppManager）
      ├─ gm.carplay.CarPlayServiceManager
      │   ├─ registerCarPlayServiceCallBack / registerCallback
      │   ├─ getVideoReadyStatus / playerInitPlayback
      │   └─ requestUI / sendTouchEvent
      └─ gm.connection.DeviceConnectionManager
          ├─ getCurrentProjectionDevice
          └─ isProjectionConsentedByUser
```

实际底层类型 `gm.carplay.CarPlayServiceManager`、`gm.carplay.ICarPlayListener`、`gm.connection.DeviceConnectionManager` 在此 DEX 中只有引用，没有类实现。服务资源覆盖包的目标进一步指向 `com.gm.domain.server.delayed`；下一步应读取真正的目标 APK，而不是另一个名称带 applecarplayservice 的覆盖包。

可直接复核的反编译位置（仅用于本地分析，输出在忽略的 artifacts 目录）：

- `applecarplay/sources/Y/h.java:80`：获取系统 CarPlayServiceManager 并注册服务回调。
- `applecarplay/sources/Y/h.java:99`：从服务取得 DeviceConnectionManager。
- `applecarplay/sources/Y/h.java:264`：显示设备要求投屏能力为 CARPLAY 且 `isProjecting()` 为真。
- `applecarplay/sources/Y/h.java:290`：用户投屏同意状态由系统连接管理器查询。
- `applecarplay/sources/Y/e.java:17`：转发连接状态；25 行转发显示错误码。
- `applecarplay/sources/com/gm/hmi/applecarplay/ui/activities/a.java:46`：监听 `gm.connection.DEVICE_CONNECTION_EVENT`，处理 `Connection fail`、`Connection success` 等事件。
- `applecarplay/sources/com/gm/hmi/applecarplay/ui/activities/AppleCarPlayProjectionActivity.java:444`：显示错误回调只是退出/返回原生界面，没有在此判断 iAP2 或 Wi-Fi 凭据。

从这批应用不能确认车机是否要求额外 SDP/EIR 条件、为何不发送 4301、内部连接错误码对应什么条件，或实际 SDK/sourceVersion。也没有发现可据此直接修改手机请求字节的实现证据。首次反编译有四个方法错误，关键上述调用点及两份 overlay manifest 可读；未把部分反编译失败当作完整底层代码已检查。

## 与最新手机日志合并

0.17 上传 `1791711904-carplay-76efaa53-29a8-46e2-860e-8b2220dfe8fa.zip`，会话 `2a26e051-ef4b-4400-a7a5-f7aa0e37a486`：

- 当前车机实际声明 General Motors / A2LL / SA8155-1.0，固件 `aegean_orange_n-user 12 SQBP5-39 67 release-keys`，与此前原始记录一致。
- 2,863 ms 重新检测；2,994 ms 手机回复检测；3,027 ms 收到新的 SYN；4,070 ms 第二轮签名验证通过。
- 4,909 ms 第二轮完整无线声明实际写入，4,998 ms 获 ACK110；30,801 ms 未收到 4301/5703，控制预算结束。
- 用户观察为先显示连接中，随后提示 Apple CarPlay 无法连接，没有等待用户确认的提示。

这证明同一 RFCOMM 上的一次新协议实例已经在实车完成，但没有证明无线会话成功，也没有确定最初重新检测的内部原因。仍未得到 CarPlay SDK 版本、车机 Wi-Fi 配置或媒体连接；不能把最后 ACK 解释为标准 CarPlay 全流程通过。

## 通用流程对照与下一份材料

[Apple 无线连接说明](https://developer.apple.com/videos/play/wwdc2017/717/)区分蓝牙服务发现、iAP2、无线凭据、Bonjour 和媒体连接；[较新连接流程说明](https://developer.apple.com/videos/play/wwdc2023/10150/)说明可通过 iAP2 交换 IP/端口。这些公开材料不是完整的底层认证规范，不能据此宣称本实现已符合所有 CarPlay 要求。

源码中仍可确定的一项差异是：0.17 主动连接车机成功时没有建立手机 cafe SDP 记录。工作区已补充提前注册及对所选已配对车机回连的独立验证路径，原主动通道在有效回连配置之前继续工作；这是一项接口补全，尚不是已证明的故障根因。SDP 注册也不等同于 Apple EIR 广告。[Android 文档](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices)确认注册、接受连接和关闭服务器与已接受 socket 的生命周期关系。

此前缺少的 **`com.gm.domain.server.delayed` 已在 `K:\Download\system\priv-app\DelayedWKSApp\DelayedWKSApp.apk` 找到**，外部框架为 `system/framework/info3.jar`，关键原生库包括 `system/lib64/libNmeIAP.so` 与 `libNmeCarPlay.so`。后续证据见完整提取分析。原目录的 `base(1).apk` 已核对为 `com.leting`，不是 GM 服务。

若车机已通过 ADB 连接，可用明确的车机序列号只读查询实际路径：

```powershell
adb -s <车机序列号> shell pm path com.gm.domain.server.delayed
```

对返回的每条 `package:` 路径使用 `adb -s <车机序列号> pull <实际APK路径> <本地目录>` 导出。不要把 vivo 手机序列号用于这个查询。未修改、安装或重新签名任何车机 APK，也未改变其权限或授权检查。

## 手机端独立补全与验证

0.18 已补全主动拨号前的真实手机 SDP 注册和已配对车机回连处理。主动通道继续收包，回连须独立同步、稳定身份检查及新挑战证明，并实际提供有效配置后才能接管；已有主动配置优先，选择和端点发布在同一锁下，防止混入旧通道迟到的配置。回连处理共用原 30 秒预算，未更改 0.16/0.17 的无线声明字节。这是对明确缺失入口的补全，不是从本批车机 APK 得到了底层拒绝条件。

210 项 JVM 测试、两个 flavor 构建与 Lint、core-iap2 Lint 通过；新增测试覆盖注册/拨号/回退次序、可见的注册失败、权限和取消、显式车机模式，以及主动配置到达时取消可选回连握手。APK 为 `artifacts/carplay-0.18.apk`，版本 `8.8.4-carplay.0.18` / `100028`，签名和组件入口保持，验签通过。SHA-256：`cc4629773f4e53f8fcba1488de10d2dd59375c50ca5de245ede15a372e4cb765`。目前没有新版本实车证据，不能声称已经获得 Wi-Fi 配置或完成投屏。

本批输入 SHA-256：

- Apple CarPlay：`30a39db1ca982635e08ebbeeed0e385fcc9f06bfd8e7ab6cfb9b24736c999b2d`
- service overlay：`709f5e14a658607ccd0f260d490d2ba4fb3e40f3d8654a1c25af3768f4c8b5d3`
- flow overlay：`c143fd883cbd6fb876ece6f60f5216309a6b9ce1251d3335dfc4b0b37e412376`
