# 皮友助手

皮皮虾（`com.sup.android.superb`）的 LSPosed 增强模块，当前重点适配 **皮皮虾 6.6.1**。

- **维护者**：Daniaode（本项目由我接手维护）
- **项目仓库**：<https://github.com/Daniaode/ppx-assistant>
- **问题反馈**：<https://github.com/Daniaode/ppx-assistant/issues>
- **下载 / 更新**：<https://github.com/Daniaode/ppx-assistant/releases>
- **许可证**：GPL-3.0

> 本项目基于社区开源项目继续维护，保留原有功能，并在此基础上做 6.6.x 适配与新增能力。原作者与前期维护者信息见文末「致谢」。

---

## 兼容范围

| 项目 | 说明 |
| --- | --- |
| 宿主版本 | 皮皮虾 6.6.1（同时兼容 6.6.0 / 6.5.x 的大部分功能） |
| 模块包名 | `io.github.vs2pk0.piyou` |
| 作用域 | `com.sup.android.superb` |
| 框架 | LSPosed 1.9.x（legacy Xposed API 93）／ LSPosed 2.x（modern libxposed API 101） |
| 运行环境 | Magisk / Zygisk Next + LSPosed，Android 9 以上 |

包名保持不变，**可直接覆盖安装升级**，设置不会丢失。

---

## 安装

1. 安装 APK。
2. 打开 LSPosed，启用「皮友助手」，勾选作用域 **皮皮虾**。
3. 安装或更新模块后，务必 **强制停止皮皮虾** 再重新打开，否则 Hook 不会加载。
4. 在模块首页按需开启功能开关。

---

## 我们做了什么（6.6.1 适配与新增）

### 1. 适配皮皮虾 6.6.1
6.6.1 大量类名保持可读、仅方法名混淆。本次按真实类名 + 真实方法签名重新对齐 Hook 点，修复了此前「钩子装上但不生效」的一批问题。

### 2. 首页右下角「红包 / 福利」悬浮窗彻底隐藏
首页的金币悬浮入口由三个业务管理器动态创建并 `setVisibility(VISIBLE)`：
`FeedMoneyEntranceManager`、`FeedDurationEntranceMgr`、`PetFloatingWidgetManager`，另有 `GoldLayoutWrapper` / `CircularView` / `i_duration.ui.widget.a` 等悬浮控件。

仅在这些控件构造时设为 `GONE` 会被业务代码重新覆盖，因此改为**全局兜底**：Hook `View.setVisibility(int)`，命中悬浮控件特征类名（`i_duration` / `GoldLayout` / `CircularView` / `PetFloating` / `MoneyExciting` / `CoinFloat` / `DragReward` / `redpacket`）即强制 `GONE`。策略是宁可错杀，优先保证界面清爽、视频正常播放。

### 3. 自动刷视频（双链路）
皮皮虾有两套完全不同的翻页实现，必须分别处理：

- **推荐页**：`DetailPagerFragment` 使用 `SwipeControlledViewPager`，Hook `onPageSelected` 后延时 `setCurrentItem(current + 1)`。
- **视频页（沉浸式信息流）**：`ImmersiveFeedListFragment` 等使用 `RecyclerView` + `VerticalImmersiveSnapHelper` 纵向翻页。

视频页此前一直不生效的根因已定位：翻页回调是 `SnapPageScrollListener.a(int)`，但**基类里是空方法**，真正实现由匿名内部类 `XxxFragment$mPagerScrollListener$1` 覆写。钩基类等于钩了一个永远不被调用的方法。

现改为 Hook 各沉浸页匿名监听器的 `a(int)` 覆写，并覆盖四个页面：
`ImmersiveFeedListFragment`、`CardImmersiveFeedFragment`、`SecondaryImmersiveListFragment`、`CollectionFeedFragment`。

RecyclerView 通过 `BaseFeedFragment.l()` 获取（字段 `f` 声明在父类，子类 `getDeclaredField` 取不到，这是另一个踩过的坑），取不到时依次兜底 `getMainRecyclerView()`、继承链字段、视图树查找。

切换使用 `smoothScrollToPosition(pos + 1)`；若目标项未进入可视区，自动退化为整页 `smoothScrollBy(0, height)`。

### 4. 视频播放完自动切下一条
Hook `TTVideoEnginePlayer.onCompletion(TTVideoEngine)`（`playbackState == 0` 表示播放结束）。

注意：`onCompletion(boolean)` 是 `SeekCompletionListener`，拖动进度条就会触发，**不能**用作播放结束信号。

仅在沉浸式视频页处于前台时响应，并做 1.2 秒防抖，避免预加载播放器误触发连续翻页。

### 5. 后台自动领金币 / 每日签到
Hook `MoneyExcitingService`：

- `acquireDailySignIn(...)` 触发每日签到，采用 5s / 13s / 21s / 29s 多次重试，覆盖启动慢、登录态未就绪的情况；
- `tryStartWatchVideoTask()` 启动看视频领奖任务；
- 签到弹窗结束回调 `notifySignInDialogFinish` 后补一次检查。

### 6. 检查更新指向本项目
模块「关于与支持」页的检查更新改用本项目 GitHub Releases（`api.github.com/repos/Daniaode/ppx-assistant/releases/latest`），版本号从 tag 解析，比较后提示新版本。

---

## 已有功能一览

### 净化与浏览
- 跳过开屏广告
- 信息流过滤：按关键词、用户屏蔽；屏蔽直播、官方号、推广内容
- 评论过滤：按关键词、用户屏蔽
- 隐藏首页红包 / 福利 / 时长悬浮控件（见上文）

### 下载与保存
- 视频、音频、图片保存
- 评论表情批量收藏
- 自定义表情

### 互动
- 自动点赞 / 自动点踩（可设置风格与频率保护）
- 自动评论（可设条件与内容、评论文字颜色）
- 自动「插眼」
- 解除违禁词限制
- 解除视频评论条数限制

### 界面与资料
- 双列布局样式
- 频道定制与默认频道
- 粉丝数 / 关注数 / 获赞数显示修改
- 消息未读 99+
- 时间格式定制（近期 / 精确）
- 资料自定义：用户名、简介、认证类型与描述、性别提示色
- 互动样式修改
- 弹幕解锁与发送者查询

### 播放
- 常规倍速 / 长按倍速
- 自动刷视频（推荐页 + 视频页）
- 播放完自动切下一条

### 自动化
- 每日签到领金币
- 看视频领奖任务

---

## 构建

```bash
./gradlew :app:assembleRelease
```

- JDK 17+（本机验证使用 OpenJDK 21）
- Gradle Wrapper 已内置，无需单独安装
- 签名配置从 `local.properties` / 环境变量读取（`PPX_KEYSTORE_FILE` 等），**keystore 不入库**

产物：`app/build/outputs/apk/release/app-release.apk`

---

## 说明

- 本项目仅供学习与个人使用，请勿用于商业用途或二次倒卖。
- 修改宿主行为存在被风控或封号的风险，请自行评估。
- 不同宿主版本、不同机型可能存在差异，欢迎通过 Issues 反馈。

---

## 致谢

本项目的思路与代码基础来自以下开源项目，感谢原作者与前期维护者的付出：

- **原创作者：Secack / Akari**
  原始项目：<https://github.com/Secack/ppx>
  提供了模块的整体架构、Hook 框架与绝大部分功能设计。

- **前期维护者：vs2pk0（我的馬眼會發光）**
  续作仓库：<https://github.com/vs2pk0/ppx-assistant>
  完成了现代 libxposed 入口、LSPosed legacy/modern 双线兼容、6.2.0–6.5.x 多个版本适配。

- 另感谢 **pyhelper** 模块在首页悬浮控件定位方面提供的思路参考。

如果没有以上项目，就不会有现在这个版本。请同样尊重并遵守原项目的开源许可证（GPL-3.0）。
