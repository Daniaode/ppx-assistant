# 皮皮虾 6.5.5 适配记录

测试日期：2026-09-28。助手版本：0.0.6（20260923 + 6.5.5 适配改动）。宿主版本：6.5.5（655），2026-09-28 从小米 13 Ultra（Android 16 / API 36、SukiSU Ultra、LSPosed 2.1.0-it API 102）实际安装包拉取分析。

## 静态核对（基于真机 6.5.5 dex）

对模块引用的全部宿主类名、字段名、方法名与 6.5.5 真实 dex（35 个 classes.dex，SDK dexdump 逐一核对）做了比对：

- 既有硬编码混淆引用（`utils.cd/ce/br`、`video.g/f/d`、`video.g$b` 字段 `f`、`manager.b`、`m_chooser.impl.c`、`m_comment.util.helper.a/g/j`、`m_danmaku.widget.j`、`m_sharecontroller.ui.e`、`mi.feed.repo.utils.b$a` 的 `P/D/ay`、`module.publish.publish.c`、`i_message.i`、`detail.util.j`、`feedui.repo.a`、`feedui.util.ai/m`、`m_feedui_common.util.a`、`video.controllerlayer.i`、`video.viewholder.a`、`i_sharecontroller.model.c`）在 6.5.5 中全部未改名，成员签名一致。
- 唯一失效点：`com.sup.android.module.usercenter.core.e`（弹幕发送者查询的资料兜底查询）在 6.5.5 移到 `com.sup.android.module.usercenter.b.e`（Kotlin 元数据显示原 `core` 包整体重排为 `usercenter.b`；`a(J, mi.usercenter.a): UserInfo` 与无参构造均保留）。已在 `DanmakuHook` 改为先试旧名、再试新名的回退查找。
- 结构签名 finder 自动适配的例子：防误触的目标从 `controllerlayer.i` 变为 `controllerlayer.b.c`（ProgressBar×1、ImageView×2、TextView×2 的签名特征一致），运行时由 finder 自动定位，无需改码。
- jdk 反编译时 jadx 会按 Kotlin 元数据恢复类名（如 `ClickSoundManager`、`MemoryAuthorInfoHandler`），与运行时 dex 名不同；本次核对一律以 dex 名为准。

## 运行时验证

- 安装修改后的 0.0.6 包，皮皮虾作用域已勾选；冷启动主进程与 `:push` 进程均加载 modern API 102。
- `Init` 缓存重建 62 项全部解析成功（与 6.5.0 相同数量）；`findVideoSaveClass` 定位 `video.g#a / video.f#a / a / a / b`，`myTabList=m_mine.utils.h#a`、`myTabView=subview.f#b`。
- Hook 扫描 57 项，5 个自动 Hook 按策略跳过，日志无任何 `init failed`；默认配置下 Toast 显示“皮友助手 0.0.6 加载成功; hooks=16”。
- `IntegrityDialogHook` 成功挂接 `com.sup.android.base.network.d#a`（11303 非官方包弹窗抑制，6.5.5 依然存在 `NetworkResultCodeChecker$showCode3Dialog$1`）；`PrivacyDialogHook` 依赖的 `MainActivity.registerPrivacyDialog` 在 6.5.5 存在。
- `ShareActionsHook`：分享面板构造器拦截、`ItemFeedCell` 捕获、转发按钮注入（`ShareActions attached cell=ItemFeedCell`）均正常。
- 助手关于页显示“助手 0.0.6 · 皮皮虾 6.5.5；已适配皮皮虾 6.5.5、6.5.0、6.2.0”；首页信息流正常渲染。
- `testDebugUnitTest` 30 项通过；`assembleRelease` 成功。

## 本轮改动

- `DanmakuHook`：资料兜底查询类名增加 `module.usercenter.b.e` 回退（6.5.5）。
- `VersionChecker`：支持版本列表加入 6.5.5（关于页同步显示）。
- README 适配版本说明同步；并入此前工作区中已有的 6.5.5 相关改动（`WelfareEntryHook` 隐藏福利入口、`PrivacyDialogHook` 关闭个人信息保护指引、`IntegrityDialogHook` 非官方包弹窗抑制、设置修订号提示）。

## 26.10.11 追加：三个隐藏开关

新增"隐藏游戏入口 / 隐藏话题栏位 / 隐藏消息栏位"（0.0.7 代码内）。实现要点：

- 首页右上角小游戏图标仅由 `MiniGameHelper`（dex `feedui.util.aa`，6.5.0/6.5.5 同名）创建，构造后隐藏其 ViewStub 与图标。
- 底栏由 `MainBottomBarController` 构建后又会被服务端配置按位置替换指示器，直接过滤初始标签列表会和替换逻辑冲突（实测出现"我的被换成话题"），因此改为在指示器唯一工厂 `bottom.b.a(Context,String,String,int)` 上按 tag/文案隐藏创建出的视图，兼容两条路径。
- 注意点：Hook 参数里的 `FragmentActivity` 必须以字符串形式传给宿主 classloader 解析，模块自带的 androidx 类对象来自不同 classloader 会导致 NoSuchMethodException。

真机验证（REDMI K90，皮皮虾 6.5.0）：三个开关全开后顶栏只剩搜索图标，底栏为 [首页, +, 我的]，"我的"页可正常打开；模块 0 失败（hooks=47）。开关关闭后恢复原状未单独复测（实现仅为视图 GONE，不改变宿主数据）。6.5.5 侧由同名结构核对支撑，未重复页面验证。

## 尚未在 6.5.5 完整重复验证的场景

- 视频/音频/图片保存的实际下载、GIF 动图、楼中楼视频上传、红色评论端到端、评论颜色发布等深流程仅验证到 Hook 挂接与签名一致，未做逐项线上复测。
- 自动操作沿用既有处理：16 个设置隐藏、5 个自动 Hook 停用。
- 长按倍速、双列布局、全部过滤规则线上样本、弹幕实际发送未逐项页面验证。
