# ComposeMusicPlayer — 项目长期记忆

本地音乐播放器，Windows + Linux，Compose Multiplatform。
Compose `1.13.0-alpha01` / Kotlin `2.2.21` / 仅 `jvm()` target。
纯逻辑与 UI 在 `commonMain`，平台实现在 `jvmMain`；平台能力收进接口（`AudioEngine`/`LibraryService`/`AppStorage`/`CoverArtRepository`/`TextCollator`），所以 `PlayerController` 全部策略逻辑可测。
Kotlin 包名固定 `com.rhp.mediaplayer`（**不改**）；jpackage 包名/显示名 `ComposeMusicPlayer`（曾用 `ComposeMediaPlayer`）。

## 已定决策（勿再问）
1. **FFmpeg 子进程解码**（`ProcessBuilder` + stdout PCM），两平台随包分发。否决 VLCJ / JavaFX Media / 纯 Java Sound SPI。
2. 必支持 MP3、FLAC、M4A/AAC、WAV/AIFF（ffmpeg 顺带覆盖 ALAC/Opus/WMA/APE）。
3. 循环三态：关 / 列表循环 / 单曲循环。
4. 主列表恒为排序后的自然顺序；另设右侧可折叠「播放队列」面板（已播放/正在播放/接下来）。
5. 歌单：多个具名歌单 + JSON 持久化。

## 音频管线
- 解码：`ffmpeg -hide_banner -loglevel error -nostdin -ss <秒> -i "<文件>" -map 0:a:0 -vn -f s16le -ac 2 -ar 44100 -`
  `-ss` 在 `-i` 前（快速定位）；`-nostdin` 必加；`-map 0:a:0 -vn` 避开内嵌封面。
- 元数据：`ffprobe -v error -print_format json -show_format -show_streams "<文件>"`。
- 封面：`ffmpeg -i f -map 0:v:0 -an -c:v copy -f image2pipe -`（靠 `image2pipe` 避免猜图片格式）。
- 出声：Java 侧 `SourceDataLine`（Linux 仍依赖 ALSA）。读 8192 字节块，**必须按实际读取长度写入**（`read` 会返回短块）。进度 = 已写字节 /(采样率×声道×2)。暂停 = 停止读管道（ffmpeg 自然阻塞，CPU 0）。seek/切曲 = `destroyForcibly()` 后带新 `-ss` 重启（≈50ms）。音量在 Java 侧乘系数。
- 实测：seek 偏差 ≤1ms；首字节 134~181ms 且与位置无关；180s 音频约 200ms 解完。**Windows 无控制台窗口问题，勿再调研**：`javaw` 下普通 `ProcessBuilder` 起 ffmpeg.exe，顶层新增窗口数为 0。

## 播放队列模型
双层顺序：**自然顺序**（按名称排序，仅用于展示与洗牌）与**活动顺序**（随机时是 Fisher–Yates 全排列，天然保证一轮每首一次）。
- **「已播放」是显式列表，不是游标位置（重要，上一版 bug 的根源）**。曾经用 `active[0,cursor)` 表示已播放——只在从一端顺放时成立；手动跳到中间时，被跳过的歌全被判成已播放。现在 `playedThisPass`（`ArrayDeque`）按真实播放顺序记录，`playedInPass`/`upNext` = `active` 减去「已播放」与「当前」。`historyStack` 并行但答另一个问题：前者「队列还欠我什么」，后者「上一首去哪」。
- **手动选曲是「跳」不是「进」**：`select()` 只改 `playing`，不把跳过的记成已播放，也不移出队列 ⇒「接下来」= 列表去掉已听过的、去掉正在播的。被它打断的那首既不算听过也不丢，留在原位等下一轮。跳回已听过的歌 = 重播并把它从已播放里摘掉（同一首不会既在已播放又在正在播放）。
- **`assumePlayedUpTo(songId)` 是全文唯一的「假定」**：顺序播放恢复播放点时把该曲之前的歌视为已听，并同样铺好 `historyStack`（「上一首」能顺着退）。没有它，恢复后这一轮会把听过的再放一遍。**随机模式绝不能调用**。
- 一轮播完：循环关 → 暂停停在末尾；列表循环 → 重新洗牌开新一轮；单曲循环 → 重复当前曲、游标不前。随机关 ⇒ 活动顺序 = 自然顺序，播放不中断。
- 「上一首」走历史栈（真实播放顺序），不是下标减一。

## 持久化
- `settings.json`：主题、音量、排序方向、随机/循环、窗口最大化、上次导入目录、`lastSongId`/`lastPositionMs`。`library.json`：只有歌单 + `selectedPlaylistId`。**队列状态一律不存。**
- **两种模式都记住「是哪首歌」，只有顺序播放记住「听到哪」**（`noteResumePoint` 一处决定：随机关时把位置写成 0）。恢复走同一条路径（`queue.resumeAt(songId)`），差别只在随后是否调 `assumePlayedUpTo`。
  - 随机下位置无意义——排列每次重新洗，旧位置会落在一条无关顺序的中间，并让一串随机的歌被判成已播放；**但曲目本身有意义**：重启后继续听刚才那首、从 0 开始、已播放为空，用户报的「重启换歌」就是这么来的。
  - `resumeAt` 会把该曲**锚到新一轮的开头**（随机时重新 `permutedAround`），否则「下一首」会从排列中间往下走、末尾再绕回来。它同时清空 `playedThisPass` 与 `historyStack`（新会话什么都没听过），顺序模式接着用 `assumePlayedUpTo` 把前面的歌标成已听。
  - **不要用 `select` 恢复**：那会把排列里随机的第一首推进历史栈，让「上一首」跳到一首没人听过的歌。
  - `shuffleOrder`/`orderMoved`/`restoreOrder()` 已全部删除。
- **只记「是否最大化」，不记窗口尺寸/位置**（位置在显示器配置变化后无用，还可能把窗口丢到屏幕外）。
- 恢复播放点**不自动播放**（`playbackStarted` 保持 false），按播放才出声。
- 恢复位置**不进 Compose 状态、也不进 `settings` 对象**（会被 `updateSettings` 的整对象 copy 覆盖），用普通字段 + `currentSettings()` 叠加写入。
- 播放期间不写盘，`resumeMoved` 记「欠一次写」，由 `shutdown()` 补。**`shutdown` 必须刷写防抖中的保存而不是 cancel**（否则「最大化后立刻关窗」必丢状态）。
- 强杀进程会丢最后一次未落盘的播放点（可接受，已向用户说明）。
- **最小化时不能信 `WindowState.placement`**：已最大化的窗口最小化后 AWT 变 `ICONIFIED`、Compose 读成 `Floating`。只在 `extendedState and Frame.ICONIFIED == 0` 时记录。
- 恢复位置在末尾 5 秒内归零（`RESUME_TAIL_MARGIN_MS`），超过时长同样归零。

## UI 约定
- **性能**：LazyColumn 用稳定 `key`+`contentType`；「是否当前曲目」用 `derivedStateOf` 封装；播放位置/音量以 **lambda provider** 传入，只有读它的那一条重组；主题切换用单个 float 驱动整套配色 `lerp`（260ms），**不用 `Crossfade` 包整棵树**；封面后台解码+降采样+LRU；≥500 行关闭重排动画。禁用 `blur` 与大面积阴影；`Brush`/`Shape` 一律 `remember`。
- **面板分区靠色阶，不画分割线**：列表 `surface`、侧栏/队列 `surfaceContainer`、播放条 `surfaceContainerHigh`。1dp 线必然在某处硬性终止，比内容更抢眼；**不要试图 blur 一条细线**。
- **悬停提示是手写的，勿改用 M3 `TooltipBox`**：material3 1.9.0 的 `TooltipBox` 只响应长按、不响应悬停（实测+反汇编确认），`foundation.TooltipArea` 已移除。实现：共用的 `HoverTooltip(label) { dismiss -> ... }`（`hoverable` + `collectIsHoveredAsState` + 延迟 420ms + 自写 `PopupPositionProvider`，下方居中并夹紧窗口），`IconAction` 与 `FloatingIconAction` 都用它。气泡用 `inverseSurface`/`inverseOnSurface`（两主题自动成立）。`tooltip` 与 `contentDescription` 分开：前者扫一眼的短标签，后者供辅助技术朗读。**新增图标按钮必须补 `tooltip`**（唯一例外：搜索框里的清除「×」，它不是 `IconAction`）。
- **「定位到正在播放」浮动按钮**（`SongList` 内，`FloatingIconAction` = `SmallFloatingActionButton` 改 `CircleShape`，M3 默认是圆角方块）：只在当前曲目**滚出可视范围**时出现——视野里已有、被搜索过滤掉、**列表尚未完成首次布局**，三种情况都不显示（最后一条不加会在每次启动闪一下）。判定用 `derivedStateOf` 读 `listState.layoutInfo`，但**状态在 `SongList` 里建、只在 `LocateCurrentButton` 里读**——读在调用方会让整张列表在每次滚过当前行时重组。点击把该行 `animateScrollToItem(index, -((viewport−行高)/2))` 送到**屏幕中间**（贴顶的行与原有行分不清，正是要解决的问题），并置 `flashedId` 高亮 1100ms（90ms 进 / 520ms 出）。行底色 `lerp(常态色, primaryContainer, flash)` ⇒ 悬停仍瞬时，动画只属于高亮。
- **两个滑块共用 `ui/components/PlayerSlider.kt`**：清掉 `LocalMinimumInteractiveComponentSize`，用 15dp 高自绘手柄替换 Material 的 44dp（比轨道还高，像横在中间一堵墙）。**带 `thumb` 槽位的 `Slider` 重载是 `@ExperimentalMaterial3Api`，必须 `@OptIn`**。进度行同在 `ui/components/ProgressRow.kt` 共用（差异只有字号与间距）：**拖动时本地值优先、期间不订阅进度**这套逻辑只应存在一份。
- **播放界面 `ui/nowplaying/NowPlayingScreen.kt`**：点播放条左下角封面/歌名/歌手**打开或收起**（同一处再点一次收起，回调用 `toggleNowPlayingScreen`），左上角箭头或 **Esc** 也返回。**界面里没有进度条也没有播放按钮**——底部播放条一直在，重复一套等于同屏放两份同样的控件；这一页只负责播放条给不了的东西：一张大到值得看的封面。
  - 背景是 `geometricBackdrop()`：两团径向渐变光晕 + 一对同心圆环 + 一个旋转 18° 的方框轮廓，五种元素、三种线宽层次。全部取自主题色（M3 的 tonal 体系让深色下 `primary` 偏亮、浅色下偏深，**同一组 alpha 在两种主题下重量大致相当**），**不 blur、不动画、不读状态**，用 `drawWithCache` 保证渐变只在尺寸变化时构建。**两种主题都已实机截图确认。**
  - **覆盖范围只有「侧栏 + 歌曲列表」那个 Box**：`Box(weight(1f)) { Row { Box(weight(1f)){ 侧栏+列表; NowPlayingSlot }; AnimatedVisibility{ 队列 } } }`。**队列面板必须是与库槽位并列的兄弟列**——放进同一个 Box（哪怕叫「槽位」）就会被这一页盖住，「在播放界面按列表按钮」于是看起来毫无反应（用户报的）。此处**栽过两次**：第一次把 `NowPlayingSlot` 当外层 Row 的兄弟放进外层 Box，`fillMaxSize` 照样盖住队列。
  - 队列展开时播放界面**变窄**而非被遮住（内容居中、封面按可用空间算，自然适应；两种主题实机确认）。播放条不移动、返回路径永远同一处，关掉零成本。**`AnimatedVisibility` 有 `ColumnScope` 重载**，在布局列里内联会解析错，故抽成不带接收者的 `NowPlayingSlot`。
  - 封面尺寸按可用空间算：`min(宽×0.42, 高 − UNDER_THE_SLEEVE, 420dp)`——底下部分是固定高度，给封面「剩下的」比给比例更经得起窗口变化。进度条经 provider 传入，只有那一行随播放重组。
  - `largePx` 384 → **640**。`requestLarge` 只在 `startSong` 里调，所以「已就位但没按播放」时大图是空的——既有行为，不是 bug。
- **键盘快捷键都在 `Main.kt` 的窗口级回调里**，但两条走的是**不同的 pass**，这个区别是踩出来的：
  - `Esc` 走 `onPreviewKeyEvent`（自顶向下，先于获得焦点的组件）。它到处都表示「返回」，也不需要在搜索框里被输入，所以不该让别的组件先拿到。
  - `空格` 也走 `onPreviewKeyEvent`，**但它必须先问 `controller.searchFieldFocused`**。原因是实测出来的：本应用里一切可点的东西同时是可聚焦的，而获得焦点的组件收到空格会「激活自己」——第一次把空格放在普通 pass（`onKeyEvent`）里，点完歌曲行再按空格，结果是那一行被重新触发、而不是暂停。**所以：空格要在 preview pass 拦截，只在搜索框有焦点时放行。**
    - 代价：聚焦的按钮不能再被空格激活（`Enter` 仍然可以）。这是刻意的取舍：空格是播放器里最有价值的键。
    - `searchFieldFocused` 由 `SongList` → `SearchField` 通过 `onFocusChange` 上报（那个 `interactionSource` 本来就在跟踪焦点，只是拿去画聚焦描边了）。
    - 歌单重命名/新建的对话框是**独立窗口**，不受主窗口的键盘处理影响。
- 排序：`Collator.getInstance(Locale.CHINA)` 拼音排序，忽略大小写与前导 The，可升/降序。
- 搜索过滤的是**显示**不是队列（否则每敲一个字母都会重建队列、重排随机顺序）；匹配在 `model/SongSearch.kt`，只匹配歌名与歌手，不做模糊匹配。
- 点击空白处让文本框失焦：不能用「press 未被消费就 clearFocus」（LazyColumn 会吃掉整片 press），要比较 `boundsInRoot()`。
- 滚动条：`ui/components/ScrollableList.kt` 把 LazyColumn 与滚动条绑在一起；`AppVerticalScrollbar` 是 expect/actual；**必须加 `canScrollBackward || canScrollForward` 守卫**，否则内容不足一屏时滑块被拉成满高。
- `formatDuration` 只在 null 或负数时给 `--:--`，0 显示 `0:00`。
- 曲库检查：查**整个歌单**而非过滤后的可见行（失效条目恰是过滤会藏起来的）；只查存在性（`isFile`）不重读标签；批量 `removeSongs(Set)`；正在播放的失效曲先 `stopPlayback()` 再重排。

## 打包与分发
**细节见同目录 `PACKAGING.md`**（图标、ffmpeg 构建与暂存、jpackage 参数、Linux 的 rpath 与菜单项）。这里只留几条最容易忘的：
- **改启动器（图标/名称）必须升 `packageVersion`**：Windows Installer 在传入文件版本资源不变时保留旧副本，同版本号覆盖安装会留下旧 exe。升版本号会触发 major upgrade。
- **jlink 会裁掉 `jdk.localedata` 导致中文排序静默退化**（开发运行正常、装出来变码点序）。必须 `modules("jdk.localedata", "jdk.unsupported")` ⇒ **任何依赖 Locale 的行为都要在打包产物上复验。**
- `third_party/ffmpeg/<platform>/` 里两个可执行文件**必须带齐它们引用的库**；`verifyBundledDecoder` 会读出来核对（用 `-Dos.name=Linux` 复现该分支）。
- **打包用的 JDK 是机器本地的，绝不能写进项目的 `gradle.properties`**：那份文件会跟着项目复制到另一台机器，而 Windows 把 `/home/<用户>/…` 解析成「当前盘根目录」下的路径，于是每个打包任务在动手前就失败，只说 `property 'jdkHome' specifies directory '…' which doesn't exist`（Windows 把那条 Linux 路径解析成了 `H:\…\composeApp\home\rhp\.gradle\jdks\…` 这种落在项目里的怪路径）。**`~/.gradle/gradle.properties` 的优先级高于项目内那份（实测）**，机器专属的值放那里；`build.gradle.kts` 会忽略指向不存在目录的值并回落到 Gradle JVM。
- **WiX 是 Windows 打包的硬前提，由 Compose 插件自己下载**：GitHub `wix311-binaries.zip` → `~/.gradle/compose-jb/wix311.zip` → 解压到 `<root>/build/wix311`，再挂到 jpackage 的 PATH 上。两半缓存各自独立，清掉任一半都会重新去 GitHub 拉，而不通 GitHub 的机器就卡在那里（报 HttpClient 栈，看不出该做什么）。根 `build.gradle.kts` 在 `build/wix311` 已解压完整时跳过下载；想把 WiX 放在固定位置就设 `WIX_PATH` 环境变量。**系统里并没有单独安装 WiX**（`%ProgramFiles(x86)%` 与 PATH 里都没有），插件下载的那份就是全部。

## 开发与验证
- **Windows 侧**：JDK 21.0.10（`D:\StudyAndWorking\JDK`，含 jlink/jpackage）；Gradle 包装器 **9.4.1**（`~/.gradle/wrapper/dists` 里另有 8.14.3、8.6 两份旧分发）；`~/.gradle/jdks/` 下有 Gradle 自动配置的 JBR SDK 25（Windows/Linux 各一份，含 `jdk.jlink`/`jdk.jpackage`），但它**不是**打包默认值——不设 `javaHome` 时用的是 Gradle JVM。
- **Gradle 不读 `HTTP_PROXY` 环境变量**，只认 `gradle.properties` 里的 `systemProp.*proxyHost`。`~/.gradle/gradle.properties` 里配着一个没在跑的本地代理（`127.0.0.1:7897`）时，所有依赖解析都会失败，症状是 `Connect to 127.0.0.1:7897 … Connection refused`，且会被包装成「SSL misconfiguration」之类的误导性措辞。
- 运行：`./gradlew :composeApp:run -Dcmp.config=<配置目录>`（重定向配置目录，不碰真实 profile）；`build.gradle.kts` 把它映射到 `-Dcomposemusicplayer.config.dir`。
- 演示库：`python scripts/generate_demo_media.py --filler N`（8 首带标签与封面 + N 短曲；**80 个文件里只有 7 个带内嵌封面**）。
- **本机沙箱禁止 `wsl.exe` 与 `reg.exe`**（不可放行），须用户自行执行。PowerShell 工具不可用，改用 bash + tasklist。
- **UI 交互可无人值守验证**（本项目唯一能验证真实交互的手段）：
  - `prototype/`：`Click.java`（单/多点点击）、`ClickShot.java`（**同一进程内**滚轮 + 点击 + 截图，`--wheel x y 格数 --click x y --after ms`——每个工具各起一个 JVM 要半秒，抓不到 1 秒内的动画）、`Hover.java`（只悬停）、`Key.java`（按命名键，Esc 等）、`Shot.java`（截图）、`InteractionTest.java`（Robot 输入）、`WindowDriver.java`（JNA 直调 Win32 问窗口状态，另有 `close`/`changeAndQuit`）。
  - **坐标必须 1:1 裁剪后按占比换算**：`real = 裁剪起点 + 占比 × 裁剪尺寸`。按缩放图目测必错（多次踩坑，最近一次偏 30px 导致误判「提示没实现」）。数值法优于截图：验证持久化直接读 JSON 比对。
  - `WindowDriver` 里**写死了窗口标题**；改名后要同步并 `javac -d prototype/out -cp prototype/lib/*.jar prototype/WindowDriver.java`（`prototype/out` 是旧 class，改 `.java` 不自动生效，报错却是「window not found」）。
- **`taskkill /F` 不会触发 `shutdown()`**，强杀后配置不会落盘——验证持久化必须用 `WindowDriver close`（优雅关窗）。
- **打包产物里没有 `java.exe`**（jpackage 的 `runtime/` 只留 `bin/server/jvm.dll` 等，启动靠自带的 launcher exe），所以冒烟测试只能跑 `app/ComposeMusicPlayer/ComposeMusicPlayer.exe`。要重定向配置目录，往 `app/ComposeMusicPlayer.cfg` 的 `[JavaOptions]` 末尾追加一行 `java-options=-Dcomposemusicplayer.config.dir=…`（**CFG 是 CRLF 行尾**），测完还原——它是构建产物，`createDistributableImpl` 未必会因它变化而重跑。
- 一次性启动+验证+关闭必须在**同一条 bash 命令内**完成：工具会在命令结束时回收该命令拉起的子进程，分两次调用会看到「进程莫名消失」，误判成崩溃。
- 窗口按 1.5 倍缩放：窗口 rect 报的是物理像素（1280dp 窗口 → 1920x1230），而 Robot 截图与点击用的是虚拟化的逻辑像素（同一坐标系，1dp ≈ 1px），两者不要混用。

## 踩过的坑
- **路径分隔符**：`path.substringAfterLast("/\\")` 是**两字符字符串** `/\`，不是字符集，两平台都匹配不上 → 函数静默返回整条路径，无标签文件显示成完整路径并按路径排序（用户报的 `/home/rhp/音乐/Finders X`）。正确写法：`maxOf(lastIndexOf('/'), lastIndexOf('\\'))`。`SongNamingTest` 守着。
- 目录：`commonMain/…/{model,player,metadata,coverart,library,settings,ui/{theme,icons,components,library,queue,transport,playlist,nowplaying}}`，`jvmMain/…/{Main.kt,audio,metadata,coverart,library,platform}`。
- **测试 67 个，全绿（0 跳过）**：`SongSortingTest`(10)、`SongSearchTest`(9)、`SongNamingTest`(10)、`PlaybackQueueTest`(28)、`LibraryRescanTest`(4)、`FfmpegAudioEngineIntegrationTest`(6，真 ffmpeg 端到端，音量置 0 静音跑）。纯 UI 的部分（滚动定位、可见性判定、布局）没有覆盖——项目里没有 Compose 测试框架，只能靠实机截图验证。
