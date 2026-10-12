# Jovi 原生车载界面取流研究

研究日期：2026-10-12（Asia/Taipei）。范围仅限 vivo V2408A / Android 16 上由 zncz 7.0.0.7 创建的车载界面；本次未修改应用代码、宿主、签名、安装状态或安全设置，也未启动 CarPlay 连接流程。

## 最新上传证据

| 上传时间 | 模式 | 拒绝事件时间 | 会话总时长 | 画面 / 音频 |
| --- | --- | --- | --- | --- |
| 16:39:48 | VERIFY_COMPONENT | +98 ms | 109 ms | 0 帧 / 0 回调 |
| 16:46:12 | VERIFY_COMPONENT | +94 ms | 107 ms | 0 帧 / 0 回调 |

两次 `session.json` 与 `events.jsonl` 一致，均在“查询宿主投屏接口”时失败。没有 `prepareCast` 成功、真实显示创建或首帧证据。日志中的 1280×720 是本地预览缓冲区的请求大小，不能据此认定宿主已创建该分辨率的车载画面。

“收到真实 Jovi 宿主入口”记录基于 `NativeComponentState.request`，该对象可以保留已接受的入口参数；它不能单独证明本次发生了新的宿主调用，更不能证明宿主已信任组件。

## 已安装组件的实际签名

从远程 ADB 的目标 vivo 提取当前已安装 APK 后，v2 完整验签通过：

- 包名 / 版本：`com.baidu.carlife.vivo` / `8.8.4-carplay.0.31`（100036）。
- 证书：Android Debug；MD5 `6b4deb7e178e3317d50e3785ed04d27a`。
- zncz 7.0.0.7 内置百度组件登记值：`e4cebec6e83e2817aaae40de3a2275cb`。

两个指纹不同。内置登记值与当前宿主的实时内存登记表需要区分；本次没有读取后者。筛选的近期宿主 logcat 未保留匹配拒绝记录，因此不声称取得了新的宿主原始拒绝日志。

## 原生画面的创建链路

1. `CarLifeMixingService.onTransact` 在 AIDL 分发前按调用 UID 解析包名并执行 `ComponentVerifier`。
2. 验证通过后，组件注册显示 / 音频回调并提交包含 Surface、尺寸和 DPI 的 `CastConfig`。
3. 宿主处理 `prepareCast`，准备渲染 Surface 并发布 `CarConnectedEv`。
4. 原生显示创建后，宿主在对应显示上启动 `com.vivo.carlauncher`，再通知组件显示 ID。
5. 组件的 Surface 收到实际图像缓冲区，才可计为取得原生画面。

当前失败发生在第 1 步。实机只读 `dumpsys display` 快照仅显示内置主屏（display 0），没有 Jovi 车载虚拟显示；这是检查时的状态，不代表此前从未创建过。绑定成功、入口参数和 MEDIA 阶段标签均不能替代上述画面证据。

## 后续研究边界

原版 CarLife 组件同样必须通过验签。其代码有“宿主 Surface → 编码 → 正常 CarLife 音视频传输”的链路，可作为原生画面的对照候选；尚未确认其正常会话允许同机接收，或向另一应用交付画面。现有 `iccoa-link/mobile` 启动器也只是启动入口，不能证明获取了原生图像或通过了宿主验签。

最小成功证据应包含：通过身份检查、成功准备投屏、真实显示 ID、Jovi 车载界面的实际首帧和连续帧。先证明这一链路，再研究音频与交互。当前尚未拿到原生车载界面，不以普通手机镜像或测试图代替。
