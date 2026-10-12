# 亿连原生采集通道调查

日期：2026-10-12（Asia/Taipei）。仅进行本地 APK、DEX、签名和清单分析，以及已授权真机的只读包查询；未安装组件、改包名、修改宿主、发起投屏或调用厂商服务端接口。

## 结论

代码已确认：**亿连的正常 SDK 启动流程也校验组件 APK 签名，检查发生在绑定组件之前。** 在宿主内置的 Carbit 非空签名登记下，使用不同证书签名的替代组件不能通过这道检查。原版组件确有 Surface、显示生命周期和音频采集能力，但换用亿连通道不会自动解决当前组件的签名身份问题。

## 真机宿主内置的原版组件

来源为已验证的 zncz 7.0.0.7 APK，父 APK SHA-256：`97212a97d1df0b5de48aaa56744ac6a600cef2744dc347933505fd648ef6c37e`。

| 项目 | 实际值 |
| --- | --- |
| 内置文件 | `assets/carman_692.apk` |
| 包名 | `net.easyconn.carman.neutral.vp` |
| 版本 | `6.9.2` / versionCode `692` |
| 文件大小 | 5,684,293 字节 |
| APK SHA-256 | `3c3f9d1c908248a060002ecfb09893e7bc6ebdc55d2e752882c520322f2f523c` |
| 证书 MD5 | `58818019ab26532cc6f026d0d078a9cf` |
| 证书 SHA-256 | `0da52846a917db45fb2c9abddce99303f28e409cdef7fbce739f45b21b601454` |

签名验证通过；工具另报告部分 META-INF 构建元数据不受签名保护，不能将这些元数据当作额外身份凭据。SDK 默认登记的 Carbit 包名及签名与此 APK 一致，登记最低版本为 666；正常无线启动另检查 Carbit versionCode 至少 672。版本兼容与签名认证是两道不同检查。

原版 APK 和反编译结果保存在 `C:/Users/Administrator/AppData/Local/Temp/carplay-host-7007-20261012/`，没有加入仓库。

## 已确认的启动和采集流程

1. 宿主 `of.g`（`CarbitBoot`）检查用户服务授权、组件相关权限，读取 Carbit 的 `ScreenCpInfo`，交给 `mf.a`（`CarModelApp`）。
2. `CarModelApp` 建立组件信息，并按连接类型分派；无线连接经 `rf.i`、`pf.a`（`ComponentManager`）检查安装与版本兼容性。`tc.c.v/B/g` 的已检查分支属于包登记和版本判断，没有进行证书 MD5 比对。
3. 原版组件公开 `net.easyconn.carman.ecsdk.VivoDisplayService`，继承 Jovi SDK 的 `CastScreenService`；在 `onCreate` 初始化自己的 `CastScreenManager` 和 launcher manager。
4. `RomSystemImageAvailableListener` 创建 ImageReader，取得 Surface，并将尺寸、密度和 Surface 存入显示配置。`VivoDisplayService` 等待 SDK 就绪，通过 `CarEventCallbackProxy.onCarConnectFinish` 提交 `CarConfig`，随后处理宿主创建的 Display 和开始投屏事件。
5. 原版 `net.easyconn.carman.sdk.e` 的 `onReceiveAudio` 将音频数据及格式信息交给组件处理；销毁流程清理连接和显示状态。这是原生采集链路的代码证据，不只是导航、音乐应用的车载适配页面。

这里确认的是原版组件内部如何采集和处理画面、音频，尚未确认它提供接口让另一个普通 APK 读取这些数据。不得把原版组件的采集能力等同于允许向第三方转发。

## 身份校验：已知与未证实

以下保留首次调查的范围；绑定前的实际签名门槛已在下一节定位。

- 宿主明确登记 Carbit 的固定证书指纹；这证明存在预期身份，但单凭登记字段不能证明某个调用点必定执行验签。
- 已检查的亿连启动链路没有直接进入 CarLifeMixingService，也没有复现其描述符查询阶段的 ComponentVerifier 异常。不能宣称两条通道的拒绝条件相同。
- 原版组件的服务要求调用方持有 `com.vivo.car.networking.CAR_PERMISSION`。在这个方向上，**宿主绑定组件的服务**；权限约束服务调用方，不足以单独证明组件自身必须使用 vivo 的签名。普通应用直接绑定宿主同名 SDK 服务则是另一种调用方向。
- 完整的宿主回调授权、SDK 登记/许可条件及原版运行基线仍未完成核对。未使用任何第三方私钥、凭据，也未构建绕过校验的组件。

ADB 对手机主用户（user 0）的查询未列出已安装的 `net.easyconn` 组件。默认用户查询被设备以 user 666 访问权限拒绝，因此不对其他用户的安装情况作结论。本轮没有亿连的实际显示、音频或自签名接受证据。

## 绑定前签名门槛：代码已确认

深入分析继续使用同一份已验证宿主 APK；重新计算父 APK SHA-256 与上文一致。六个 DEX 的类索引用于定位真实混淆类，避免把旧反编译副本的类名用于 7.0.0.7。实际调用链如下：

| 阶段 | 真实代码 | 行为 |
| --- | --- | --- |
| 进入组件连接 | `CarProxyService.G()` | 创建或复用 CarConnection，并调用 `L()`。 |
| 启动 SDK 连接 | `ds.e.L()` / `CarConnection` | 将绑定任务交给 `DisplayConnectionSelector`，不直接绑定。 |
| 准备并验证 | `es.b.c()` 及其后台任务 | 检查当前设备事件、选择当前连接包，准备 launcher 后调用父类 `d(runnable)`。 |
| 读取认可签名 | `es.a.d()` / `AbsConnectionSelector` | 从 `ac.c.x().J(currentPackage)` 获取 `ScreenCpInfo.sign`，调用 `ComponentVerifier.g.j(...)`。 |
| 比较已安装证书 | `g.j()` / `g.d()` | 对 Carbit 的非空登记签名，将 Package Manager 中实际安装证书的 MD5 与登记值比较。 |
| 认证成功 | `es.a$a.a()` | 记录认证成功并调度绑定任务；任务再进入 `gc.a` / `gc.c` 执行服务绑定。 |
| 认证失败 | `es.a$a.b()` | 记录认证失败和 `pkg verify fail!`，**不调度这次绑定任务**。 |
| 绑定后注册 | `ds.e$b.excute()` | 取得 `ICarManager`，注册 `ds.o` / `CarEventCallbackImpl`；原版组件保存回调并通知 SDK 就绪。 |
| 提交采集输出 | 原版 `CarEventCallbackProxy` → `ds.o.Ac()` | 提交带 Surface 的 CarConfig；宿主调整显示配置，发布连接事件并进入显示创建。 |

这补齐了之前仅检查 CarbitBoot、ComponentManager 版本判断时遗漏的父类门槛。亿连不是在 CarLifeMixingService 的首笔 Binder 事务处拒绝，但使用了相同的 ComponentVerifier 签名比较逻辑。

签名比较的结果在代码层即可确定，不需要运行日志才能解释：

| 当前期望签名 | 实际安装 APK 的证书 | 正常 SDK 签名检查 |
| --- | --- | --- |
| 内置 Carbit 登记值 `58818019ab26532cc6f026d0d078a9cf` | 原版 6.9.2 的相同证书 | 通过此门槛；后续仍有连接、配置和显示条件。 |
| 同一登记值 | 使用当前项目调试证书或其他不同证书的替代组件 | 失败，不进入这次正常绑定流程。 |

这一检查不读取组件提交的 Surface、接口描述符或自报证书作为认证依据。增加版本号、调整 Binder 调用顺序、改变 Surface 配置或关闭网络，都不能改变上述**非空登记签名**的直接比较结果。若厂商更新运行时登记表，期望值以实时表为准；这不改变检查算法和成功／失败分支。

### 源码与原始 DEX 交叉核对

- [CarProxyService 进入组件连接流程](C:/Users/Administrator/AppData/Local/Temp/carplay-host-7007-20261012/proxy-service/sources/com/vivo/vivo_car_service/CarProxyService.java:692)。
- [CarConnection.L / 绑定任务注册](C:/Users/Administrator/AppData/Local/Temp/carplay-host-7007-20261012/carbit-host-connection/sources/ds/e.java:350)。
- [DisplayConnectionSelector 的准备与校验任务](C:/Users/Administrator/AppData/Local/Temp/carplay-host-7007-20261012/carbit-host-verify-state/sources/es/b.java:29)。
- [AbsConnectionSelector：读取 sign 并调用验证器](C:/Users/Administrator/AppData/Local/Temp/carplay-host-7007-20261012/carbit-host-prebind-verifier/sources/es/a.java:57)。
- [失败回调不调度绑定任务](C:/Users/Administrator/AppData/Local/Temp/carplay-host-7007-20261012/carbit-host-prebind-verifier/sources/es/a.java:37)。
- [ComponentVerifier 的实际证书 MD5 与比较](C:/Users/Administrator/AppData/Local/Temp/carplay-host-7007-20261012/verifier/sources/com/vivo/car/networking/base/util/g.java:178)。
- [PackageUtil：从 Package Manager 读取签名并对证书字节计算 MD5](C:/Users/Administrator/AppData/Local/Temp/carplay-host-7007-20261012/signer-package-helper/sources/q9/i0.java:342)。

原始指令另保存在 `carbit-prebind-verifier.dex.txt`。它确认父类 `d` 调用 `ScreenCpInfo.getSign` 和 `g.j`；成功回调具有调度调用，失败回调只有状态和日志调用。显示完成方法的普通 Java 反编译有缺失，已读取其原始 DEX 调用与分支并保留 `carbit-on-connect-finish.dex.txt`，没有把工具生成的占位异常当作真实行为。

`CarEventCallback` 的 AIDL Stub 在已检查的回调路径上只处理接口和参数分发，没有 CarLife 那种每次 UID 变化时执行验证器的前置钩子；**认证已在绑定前完成，回调中不重复认证不等于整个通道免认证**。本轮没有执行这些接口，也没有改变认证分支。

## 对当前项目的影响

亿连路线需要独立的 SDK 采集适配，涉及宿主绑定方向、回调 ABI、显示配置、Surface 所有权、音频格式、触控、断开和销毁。现有 VivoHostSurface 的 CarLife AIDL 调用不能通过改一个包名迁移到这套接口。

在正常接入流程下，应先让厂商认可本项目自身的组件身份和证书，再依据 SDK 契约实现采集适配。现有无线 CarPlay 发送链路可以保留；成功标准仍是实际宿主就绪、非零真实画面和正确资源清理，不能以服务绑定成功替代。仅实现 SDK 接口或更换包名不能使不同证书满足已确认的签名门槛。

## 公开资料

[vivo 官方 CastKit](https://developers.vivo.com/product/d/castKit) 列出亿连驾驶助手的 Jovi InCar SDK 合作案例。[亿连官方方案](https://www.carbit.com.cn/solution) 提供 SDK/APP 交付说明，但没有在该页面公开面向第三方 CarPlay 发送端的原生画面授权契约。Context7 查询未找到对应的详细接入文档。

搜索到的 [carbit/wrc-api-android](https://github.com/carbit/wrc-api-android) 是方控硬件接入 SDK，不能作为这套原生投屏 SDK 的替代。
