<a id="chinese"></a>

# ComposeMusicPlayer

**[中文]** · [English ↓](#english)

（纯Vibe Coding项目）

面向 **Windows 与 Linux** 的本地音乐播放器，基于 Compose Multiplatform（JVM）。需要 **JDK 21** 或更新版本。

![](C:/Users/RHP/AppData/Roaming/marktext/images/2026-09-29-22-10-11-image.png)

![](C:/Users/RHP/AppData/Roaming/marktext/images/2026-09-29-22-10-34-image.png)

## 功能

- **手动导入。** 逐个添加文件，或选一个文件夹递归扫描。导入的歌曲进入具名歌单。
- **一键检查失效条目。** 刷新按钮检查整个歌单、移除文件已不存在的歌曲并报告结果。只查文件在不在，不重读标签与封面，所以是一次快速核对而不是重新导入。
- **多个具名歌单**，在侧栏创建、重命名、删除，跨运行持久化。
- **按名称排序。** 中文标题按拼音排序（`稻香` → `七里香` → `晴天` → `星晴` → `夜曲`）而不是按 Unicode 码点；忽略大小写与前导英文冠词。
- **每轮每首恰好一次。** 开随机时为整个歌单生成一份全排列（当前曲目锚定不动），关随机时就是歌单的自然顺序。
- **独立的播放队列面板**（已播放 / 正在播放 / 接下来）——唯一能看到真实播放顺序的地方，因为主列表始终按名称展示。它是列表**旁边**的一列，而不是列表上的一层，所以在播放界面打开时同一个按钮照样能把它调出来。
- **一键回到当前曲目。** 列表右下角的圆形浮动按钮，只在正在播放的那首滚出可视范围时出现；点它把那一行送到屏幕中间并短暂高亮，随后按钮自己隐去。
- **三种循环模式**：关闭、列表循环（每轮重新打乱）、单曲循环。
- **记住播放位置。** 顺序播放时记下当前曲目与位置，下次启动摆回原处但不自动出声，按播放续接。随机播放只记住**是哪首歌**：进度归零、已播放清空、每次启动重新洗一轮。
- **全屏播放界面。** 点左下角封面/歌名/歌手打开，再点一次收起（左上角箭头或 `Esc` 同样返回）。大封面 + 歌名 + 歌手，衬以几何背景；播放控制留在底部播放条上，不在这一页重复一套。
- **深色与浅色主题**（手动切换，260ms 过渡）、**记住窗口是否最大化**、**进度定位**在所有支持的格式上都精确落点。
- **快捷键**：`空格` 播放/暂停（在搜索框里打字时空格仍是空格），`Esc` 收起播放界面。

支持的格式：MP3、FLAC、M4A/AAC、WAV/AIFF，另附带 OGG、Opus、ALAC、WMA、APE，以及随包解码器能理解的其它一切。

## 工作原理

音频由随包分发的 **ffmpeg** 子进程解码，再交给 JVM 自己的音频输出：

```
ffmpeg  ──PCM on stdout──▶  SourceDataLine
```

这样拆分正是进度定位变简单的关键：ffmpeg 的 `-ss` 精确且与格式无关——一次定位就是重启一个进程（约 150ms），不需要手写每个编解码器的索引换算。

队列是纯逻辑，不碰音频也不碰 UI，因此排序规则靠测试覆盖而不是靠点来点去验证。两条规则值得知道：

- **两套顺序。** *自然顺序*是歌单按名称排序的结果，*活动顺序*才是实际播放的。关随机时两者是同一份列表；开随机时活动顺序是它的一份全排列，"每轮每首一次"于是由数据结构本身保证。**「已播放」是一份显式清单**，而不是"当前曲目之前的那些"——后者只在从头顺放时成立，手动跳到中间会把跳过的歌全判成听过。
- **「上一首」走历史栈**，而不是列表里的相邻位置。随机模式下，上一次播放的曲目几乎不会是顺序里的前一项。

## 目录结构

```
commonMain/kotlin/com/rhp/mediaplayer/
  model/     Song、Playlist、排序与搜索      player/    AudioEngine（接口）、PlaybackQueue、PlayerController
  metadata/  AudioMetadata、MetadataReader（接口）        coverart/  CoverArtRepository（接口）
  library/   LibraryService（接口）          settings/  PlayerSettings、AppStorage（接口）
  ui/        界面、主题、手写图标、组件

jvmMain/kotlin/com/rhp/mediaplayer/
  audio/     FfmpegAudioEngine、FfmpegBinaries            metadata/  FfprobeMetadataReader
  coverart/  DesktopCoverArtRepository                   library/   MediaScanner、DesktopLibraryService
  platform/  AppPaths、JsonAppStorage、FilePicker、JvmTextCollator      Main.kt

assets/  图标源文件      packaging/icons/  安装包图标（生成物）
scripts/ 构建期脚本      third_party/ffmpeg/  暂存的解码器（不入库）
```

每一项平台能力都藏在 `commonMain` 声明的接口之后，因此 `PlayerController`——所有策略逻辑所在之处——与平台无关且可测。`commonMain` 里没有任何 `java.*` 引用。

## 开始使用

```shell
# 1. 备好解码器。推荐音频专用的最小构建：每平台两个自包含文件，约 1–2 MB
python scripts/build_minimal_ffmpeg.py

#    或者拉取官方通用构建（每平台约 160 MB，含全部视频编解码器）
python scripts/fetch_ffmpeg.py

# 2. 运行
./gradlew :composeApp:run            # macOS/Linux
.\gradlew.bat :composeApp:run        # Windows
```

二进制不入版本库；脚本把它们暂存到 `third_party/ffmpeg/<platform>/`，可重复执行。

### 不想用自己的音乐来试

```shell
python scripts/generate_demo_media.py
```

生成 8 个带真实标签与内嵌封面的文件，外加配套曲库与设置，并打印可直接使用的运行命令。它同时覆盖拼音排序、没有封面的文件、时长未知的情况。

### 用一次性配置目录运行

```shell
./gradlew :composeApp:run -Dcmp.config=path/to/config
```

不会动 `%APPDATA%\ComposeMusicPlayer`（或 `~/.config/ComposeMusicPlayer`）。旧名字下的曲库会在首次启动时自动搬过去——只在目标目录尚不存在时才搬，所以不会覆盖任何东西。

## 测试

```shell
./gradlew :composeApp:jvmTest
```

共 **67 个测试**，0 跳过：

- **`SongSortingTest`**(10) —— 名称归一化、前导冠词、并列时的兜底比较、降序。
- **`SongSearchTest`**(9) —— 按歌名或歌手匹配、忽略大小写、空查询放行全部、不误配。
- **`SongNamingTest`**(10) —— 从文件名推断标题与艺术家，Windows 与 POSIX 两种分隔符都覆盖。
- **`PlaybackQueueTest`**(28) —— 全排列、轮次边界、三种循环模式、历史栈、重排序时保持当前曲目、空队列，以及手动跳选（不把跳过的歌判成已播放、被跳过的歌仍在队列里）与恢复播放点。
- **`LibraryRescanTest`**(4) —— 真实文件系统上的失效检查：只报真正消失的、同名路径变成目录也算失效、其余原样保留；用"一读标签就报错"的桩固定住"只查存在性"这条契约。
- **`FfmpegAudioEngineIntegrationTest`**(6) —— 真 ffmpeg 与真输出设备的端到端：解码 WAV/MP3（CBR 与 VBR）/FLAC/M4A、暂停冻结时钟、定位落点、停止不误报完成、坏文件报错。音量置 0 静音跑；ffmpeg 未暂存或没有音频设备时**跳过自己**而不是失败。

## 打包

```shell
./gradlew :composeApp:packageMsi     # Windows
./gradlew :composeApp:packageDeb     # Linux（需 fakeroot）
./gradlew :composeApp:packageRpm     # Linux（需 rpmbuild）
```

随包解码器来自 `third_party/ffmpeg/<os>-<arch>/`，这个命名正好符合 Compose 对平台专属资源的约定。

### 在 Fedora 上从零打一个 `.rpm`

```shell
sudo dnf install java-25-openjdk-devel rpm-build patchelf           # 1. 工具链
python scripts/build_minimal_ffmpeg.py linux-x64                    # 2. 解码器
./gradlew :composeApp:packageRpm                                    # 3. 打包
```

Windows 版可以在同一台机器上交叉编译：`sudo dnf install mingw64-gcc mingw64-zlib`，再 `python scripts/build_minimal_ffmpeg.py windows-x64`。

产物在 `composeApp/build/compose/binaries/main/rpm/`，安装到 `/opt/ComposeMusicPlayer`。

**几个会踩到的点：**

- **第 2、3 步不要 `sudo`。** root 的 `PATH` 里没有 `.`，`sudo ./gradlew` 直接报"找不到命令"；就算绕过去，`build/` 与 `.gradle/` 也会变成 root 所有。只有第 1 步需要 root。
- **`gradlew` 要有执行位。** 从压缩包或某些同步方式拿到的副本可能是 `644`，报权限不够时 `chmod +x gradlew`。
- **两种解码器，取舍不同。** `build_minimal_ffmpeg.py` 自制音频专用**静态**构建——只留解码、探测与封面提取，每平台两个自包含文件、约 1–2 MB，不引用任何库，因此 Linux 上不需要 rpath。`fetch_ffmpeg.py` 拉 BtbN 官方**共享**构建，约 160 MB（含全部视频编解码器），Linux 上必须补 `patchelf --set-rpath '$ORIGIN' third_party/ffmpeg/linux-x64/{ffmpeg,ffprobe}` 才能找到同目录的 `libav*`。
  - Linux 端打 Deb 还需要 `fakeroot`。
- **`fetch_ffmpeg.py` 的资产名与 tag 绑死。** 它钉在日期化 tag 上，而 BtbN 的日期 tag 会把上游 commit 嵌进资产名（`ffmpeg-n8.1.3-2-g45e8e0a3ff-linux64-...`）；带 `latest` 字样的名字只存在于滚动的 `latest` release。改 `RELEASE_TAG` 必须同时改那两个名字，否则就是 404。
- **Fedora 自带的 JDK 不能用来打包。** Fedora 为了 crypto-policies 改过 `conf/security/java.security`，却没有更新镜像里记录的哈希，jlink 因此拒绝从这个"已修改"的 JDK 建运行时镜像，报 `Error: modified .../conf/security/java.security`——25 与 26 都一样，`createDistributable`/`package*` 全部失败。编译、测试与 `run` 不受影响。解法是把 `gradle.properties` 里的 `cmp.packaging.javaHome` 指向一个未被发行版改动的 JDK 25（留空则用 Gradle 自己的 JDK，其它发行版上本就正常）。
- **Linux 菜单图标需要绕开 Compose 的打包路径。** jpackage 从 app image 组装安装包时（Compose 一直这么做）不认 `--icon`：安装包图标要另从 `<name>.png` 找，而 Compose 不传 `--resource-dir`、DSL 也无此入口，于是悄悄回退成内置的 JavaApp.png——装完应用顶着 Java 吉祥物，构建过程却毫无提示。`composeApp/build.gradle.kts` 里对 deb/rpm 清空 `appImage` 并补上 `runtimeImage`/`appResourcesDir`，让 jpackage 自建镜像（那条路径认 `--icon`）；这两项必须一起补，否则安装包会丢掉随包解码器、或把整个 JDK 打进去。

### 打包前置检查

`verifyBundledDecoder` 跑在 8 个打包/镜像任务之前，检查的是**目标平台**的解码器：只暂存了 Windows 版却去打包 `.deb` 会立刻失败，而不是产出一个在目标机器上其实没有解码器的安装包。

它同时检查**可执行文件引用的库是否都在**：共享版构建如果只暂存了 `ffmpeg`/`ffprobe` 而漏掉 `libav*.so.62`，光看那两个文件是看不出来的——缺的并不是它找的那两个——于是问题一直拖到用户机器上才以"解码器起不来"的形式出现。库名是从可执行文件里读出来的，不是猜的。

该校验**故意没有挂到 `run` 上**：开发运行允许没有解码器，好让应用内的诊断界面能被走通。

### 另外四件必须知道的事

- **必须有 `jdk.localedata`**（`nativeDistributions { modules("jdk.localedata", "jdk.unsupported") }`）。少了它，裁剪后的运行时没有中文排序数据，会静默退化为比较码点——开发运行时正确，装出来就错。**任何依赖 Locale 的行为都要在打包产物上复验。**
- **改启动器（图标/名称）必须升 `packageVersion`。** Windows Installer 在传入文件的版本资源不变时会保留旧副本，用同一个版本号重打包再覆盖安装，可能把旧的 exe——连同旧图标——留在安装目录里。升版本号同时也把安装变成一次 major upgrade。
- **菜单项是"要了才建"。** Windows 要 `menu = true` 才写开始菜单项，默认不写；Linux 的 `.desktop` 由"配了自定义图标"触发，`menuGroup` 填的才是 `Categories=`（不是 `--linux-app-category`，那个进 deb 的 `Section`）。
- **图标**由 `scripts/generate_app_icon.py` 从 `assets/app-icon.svg` 生成，产物入库，只有改了美术才需要重跑。Windows 得到一个多尺寸 `.ico`，Linux 得到一个 128px 的 PNG（jpackage 只接受单张图片，会读它自身的像素尺寸并吸附到 16/22/32/48/64/128，给尺寸目录不会被读取）。运行时窗口图标是另一条路径：`composeResources` 里的 256px PNG。
  - **反色版**：源美术是深色圆盘 + 镂空音符，镂空让音符透出背景，深色任务栏上整枚会消失。脚本把矢量的两条子路径拆开分别填色（浅色圆盘 + 深色音符）。
  - **ICO 每个条目必须 32 位带 alpha**：低于 256px 存成"调色板 + 1 位掩码"就等于没有 alpha，抗锯齿被硬切成阶梯，缩放时掩码排除的像素按黑色参与平均，于是在浅色图标四周留下一圈深色毛刺。入口其实在 PNG——只有两种颜色的图会被 ImageMagick 写成调色板 PNG。脚本已在栅格化与写 ICO 两处强制真彩，加图标后核对 `bpp=32`。

## 已知限制

- **没有无缝播放。** 每首歌都是一个新解码进程，曲目之间有短暂空隙。用 ffmpeg 的 concat demuxer 可以消掉。
- **通用构建体积很大**（每平台解压后约 160 MB），因为它包含全部视频编解码器。音频专用的最小构建把两个平台都降到约 1–2 MB，代价是要在 Linux 上（交叉）编译一次，见「打包」一节。
- **一次会话的第一首歌**要付出加载解码库的冷启动代价（通用构建约 160 MB，最小构建几乎可以忽略）；引擎在启动时会于后台预热解码器来掩盖这一点。

---

<a id="english"></a>

# ComposeMusicPlayer (English)

**[中文 ↑](#chinese)** · **[English]**

（A completrly vibe coding project）

A local music player for **Windows and Linux**, built on Compose Multiplatform (JVM). Needs **JDK 21** or newer.

## What it does

- **Import by hand.** Add individual files, or point it at a folder to scan recursively. Imported songs go into a named playlist.
- **A one-click check for dead entries.** The refresh button checks the whole playlist, drops the songs whose file is gone and reports the outcome. It asks only whether the file is there — no tags or artwork are re-read — so it stays a quick check rather than a second import.
- **Several named playlists**, created, renamed and deleted from the sidebar, persisted across runs.
- **Sorting by name.** Chinese titles sort by pinyin (`稻香` → `七里香` → `晴天` → `星晴` → `夜曲`) rather than by code point, case and leading articles are ignored.
- **Each song exactly once per pass.** Turning shuffle on builds a permutation of the whole playlist with the current track pinned; with it off the active order is the playlist's own order.
- **A separate queue panel** (played / playing / up next) — the only place the real playing order is visible, because the main list always shows the playlist sorted by name. It is a column *beside* the list rather than a layer over it, so the same button brings it up while the now-playing screen is open.
- **One press back to the current track.** A round floating button in the list's bottom corner, shown only while the track that is playing has been scrolled out of sight. It brings the row to the middle of the view, lights it up briefly, and then takes itself away.
- **Three loop modes**: off, repeat all (re-shuffles each pass), repeat one.
- **Remembers where playback got to.** In order, the track and the position come back on the next launch — cued rather than playing. Shuffling remembers only **which track**: the position is zeroed, nothing is marked as heard, and a new round is dealt.
- **A full-window now-playing screen.** Click the cover, title or artist in the transport bar to open it, and click the same block again to put it away (the corner chevron or `Escape` do the same). A large sleeve, the title and the artist over drawn geometry; the transport controls stay on the bar below instead of being duplicated here.
- **Dark and light themes** (cross-faded over 260 ms), **remembers whether the window was maximised**, and **seeking** that lands where you asked in every supported format.
- **Shortcuts**: `space` toggles play/pause (it stays a space while you type in the search box), `Escape` dismisses the now-playing screen.

Formats: MP3, FLAC, M4A/AAC, WAV/AIFF, plus OGG, Opus, ALAC, WMA, APE and whatever else the bundled decoder understands.

## How it works

Audio is decoded by a bundled **ffmpeg** subprocess and played through the JVM's own audio output:

```
ffmpeg  ──PCM on stdout──▶  SourceDataLine
```

Splitting it this way is what makes seeking trivial: ffmpeg's `-ss` is accurate and format-agnostic — a seek is one process restart (~150 ms) instead of hand-written per-codec index arithmetic.

The queue is pure logic, touching neither audio nor UI, which is why the ordering rules are covered by tests rather than by clicking around. Two of them are worth knowing:

- **Two orderings.** The *natural* order is the playlist sorted by name; the *active* order is what actually plays. With shuffle off they are the same list; with it on the active order is a permutation of it, so "each song once per pass" falls out of the data structure. **The played list is recorded explicitly**, not derived as "everything ahead of the current track" — that is only ever right when playback walks the order from one end, and a jump into the middle would report everything it passed as heard.
- **The `previous` button follows history**, not list position. With shuffle on, the previously played track is almost never the previous entry in the order.

## Layout

```
commonMain/kotlin/com/rhp/mediaplayer/
  model/     Song, Playlist, sorting and matching rules
  player/    AudioEngine (interface), PlaybackQueue, PlayerController
  metadata/  AudioMetadata, MetadataReader (interface)     coverart/  CoverArtRepository (interface)
  library/   LibraryService (interface)                    settings/  PlayerSettings, AppStorage (interface)
  ui/        screens, theme, hand-drawn icons, components

jvmMain/kotlin/com/rhp/mediaplayer/
  audio/     FfmpegAudioEngine, FfmpegBinaries             metadata/  FfprobeMetadataReader
  coverart/  DesktopCoverArtRepository                    library/   MediaScanner, DesktopLibraryService
  platform/  AppPaths, JsonAppStorage, FilePicker, JvmTextCollator   Main.kt

assets/  icon source    packaging/icons/  installer icons (generated)
scripts/ build scripts  third_party/ffmpeg/  staged decoder (not committed)
```

Every platform capability sits behind an interface declared in `commonMain`, so `PlayerController` — where all the policy lives — is platform-independent and testable. There is no `java.*` reference anywhere in `commonMain`.

## Getting started

```shell
# 1. Get the decoder. An audio-only minimal build is recommended: two
#    self-contained files per platform, about 1-2 MB
python scripts/build_minimal_ffmpeg.py

#    ...or fetch the general build (~160 MB per platform, every video codec)
python scripts/fetch_ffmpeg.py

# 2. Run
./gradlew :composeApp:run            # macOS/Linux
.\gradlew.bat :composeApp:run        # Windows
```

The binaries are not committed; the scripts stage them into `third_party/ffmpeg/<platform>/` and are idempotent.

### Trying it without your own music

```shell
python scripts/generate_demo_media.py
```

Generates eight files with real tags and embedded covers, plus a matching library and settings, and prints the run command to use. It covers pinyin sorting, files with no cover, and an unknown duration.

### Running against a throwaway config directory

```shell
./gradlew :composeApp:run -Dcmp.config=path/to/config
```

Keeps `%APPDATA%\ComposeMusicPlayer` (or `~/.config/ComposeMusicPlayer`) untouched. A library left under the app's previous name is moved across on first launch — and only when the new directory does not exist yet, so it can never overwrite one.

## Tests

```shell
./gradlew :composeApp:jvmTest
```

**67 tests**, none skipped:

- **`SongSortingTest`** (10) — normalization, leading articles, tie-breaks, descending order.
- **`SongSearchTest`** (9) — matching on title or artist, case insensitivity, an empty query passing everything through, and not matching what it should not.
- **`SongNamingTest`** (10) — deriving title and artist from a file name, covering both Windows and POSIX separators.
- **`PlaybackQueueTest`** (28) — permutations, pass boundaries, all three loop modes, history, keeping the current track across a re-sort, empty queues, and jumping around the order (a jump must not record what it passed as played, and those tracks stay in the queue) plus restoring a playhead.
- **`LibraryRescanTest`** (4) — the stale-entry check against a real filesystem: only files that actually vanished are reported, a path that has become a directory counts as gone, everything else is left alone; a stub that fails on use pins down the "existence only" contract.
- **`FfmpegAudioEngineIntegrationTest`** (6) — end to end against a real ffmpeg and a real output line: decoding WAV/MP3 (CBR and VBR)/FLAC/M4A, pause freezing the clock, seek landing correctly, a stopped track not reporting completion, an unreadable file reporting an error. Volume is pinned to zero; it skips itself when ffmpeg is not staged or there is no audio device.

## Packaging

```shell
./gradlew :composeApp:packageMsi     # Windows
./gradlew :composeApp:packageDeb     # Linux (needs fakeroot)
./gradlew :composeApp:packageRpm     # Linux (needs rpmbuild)
```

The bundled decoder comes from `third_party/ffmpeg/<os>-<arch>/`, whose naming matches the convention Compose uses for platform-specific resources.

### Nothing to a `.rpm` on Fedora

```shell
sudo dnf install java-25-openjdk-devel rpm-build patchelf           # 1. toolchain
python scripts/build_minimal_ffmpeg.py linux-x64                    # 2. decoder
./gradlew :composeApp:packageRpm                                    # 3. package
```

The Windows build cross-compiles on the same machine: `sudo dnf install mingw64-gcc mingw64-zlib`, then `python scripts/build_minimal_ffmpeg.py windows-x64`.

The result lands in `composeApp/build/compose/binaries/main/rpm/` and installs into `/opt/ComposeMusicPlayer`.

**Things that bite:**

- **No `sudo` in steps 2 and 3.** Root's `PATH` has no `.` in it, so `sudo ./gradlew` fails with "command not found"; and even reached by absolute path it leaves `build/` and `.gradle/` owned by root. Only step 1 needs root.
- **`gradlew` needs its executable bit.** A copy that came out of an archive may be `644`; `chmod +x gradlew`.
- **Two decoders, different trades.** `build_minimal_ffmpeg.py` builds an audio-only **static** one — decoding, probing and cover extraction, two self-contained files per platform, about 1–2 MB, referencing nothing, so Linux needs no rpath. `fetch_ffmpeg.py` fetches BtbN's **shared** general build, ~160 MB (every video codec), and on Linux it must be given `patchelf --set-rpath '$ORIGIN' third_party/ffmpeg/linux-x64/{ffmpeg,ffprobe}` to find its `libav*` siblings.
  - A Deb also needs `fakeroot`.
- **The fetched build's asset names are welded to its tag.** `fetch_ffmpeg.py` is pinned to a dated tag, and BtbN's dated tags embed the upstream commit in the asset name (`ffmpeg-n8.1.3-2-g45e8e0a3ff-linux64-...`); names carrying a literal `latest` exist only in the rolling `latest` release. Changing `RELEASE_TAG` means changing those two names with it, or the download is a 404.
- **Fedora's own JDK cannot package.** Fedora patches `conf/security/java.security` for crypto policies without updating the hash recorded inside the image, so jlink refuses to build a runtime image from a JDK it considers modified: `Error: modified .../conf/security/java.security`, and every `createDistributable`/`package*` task fails with it. JDK 25 and 26 are alike. Compiling, testing and `run` are unaffected. Point `cmp.packaging.javaHome` in `gradle.properties` at an unpatched JDK 25; leave it unset to use the Gradle JVM, which is right on other distributions.
- **The Linux menu icon has to dodge Compose's packaging path.** When jpackage assembles an installer from an app image (which is how Compose always packages), it ignores `--icon`: the package icon is looked up separately as `<name>.png`, and Compose passes no `--resource-dir` and the DSL offers no way to add one, so jpackage silently falls back to its built-in JavaApp.png. Installed, the app wears the Java mascot and the build says nothing. `composeApp/build.gradle.kts` clears `appImage` for deb/rpm and supplies `runtimeImage` and `appResourcesDir` so jpackage builds the image itself, which is the path where `--icon` is honoured. Both of those have to come along: without them the installers lose the bundled decoder, or bundle the whole JDK.

### The check before packaging

`verifyBundledDecoder` runs before all eight packaging and image tasks and checks the decoder for **the platform being built**: staging only the Windows build and then packaging a `.deb` fails immediately instead of shipping an installer that turns out to have no decoder on the machine it targets.

It also checks that **the libraries the executables load are present**: a shared build staged without its `libav*.so.62` looks entirely healthy if the only question asked is whether `ffmpeg` and `ffprobe` exist — the file that is missing is not one of the files being checked — and the failure then surfaces on the user's machine as a decoder that will not start. The library names are read out of the executables rather than guessed at.

The check is deliberately **not** attached to `run`: a development run is allowed to start without a decoder, so the in-app diagnostic screen can be exercised.

### Four more things worth knowing

- **`jdk.localedata` is required** (`nativeDistributions { modules("jdk.localedata", "jdk.unsupported") }`). Without it the trimmed runtime has no Chinese collation and silently falls back to comparing code points — correct in a development run, wrong in the installer. **Any behaviour that depends on Locale has to be re-checked in the packaged build.**
- **Changing the launcher (icon or name) means raising `packageVersion`.** Windows Installer keeps the copy it already has when the incoming file carries the same version resource, so rebuilding at an unchanged version and installing over the previous copy can leave the old executable — and the old icon — sitting in the install directory. Raising it also turns the install into a major upgrade.
- **Menu entries are opt-in.** Windows writes a Start Menu entry only with `menu = true`; Linux's `.desktop` file is triggered by having a custom icon, and `menuGroup` is what fills its `Categories=` (not `--linux-app-category`, which goes into the deb's `Section`).
- **The icons** come from `assets/app-icon.svg` via `scripts/generate_app_icon.py`, and the results are committed, so the script only has to be rerun when the artwork changes. Windows gets a multi-size `.ico`; Linux gets a single 128px PNG (jpackage takes only one image there, reads the pixel size out of it and snaps it to 16/22/32/48/64/128, so a directory of sizes would never be consulted). The runtime window icon is a separate path: a 256px PNG in `composeResources`.
  - **The icons are the inverted variant.** The source artwork is a dark disc with the note knocked out of it, drawn for a light background; because the note is a hole it shows whatever is behind it, so on a dark taskbar the disc and the note vanish together. The script fills the vector's two subpaths — disc and note — with their own colours, giving a light disc with a dark note.
  - **Every `.ico` entry has to be 32-bit with an alpha channel.** Below 256px an entry stored as a palette plus a 1-bit mask has no alpha at all: the anti-aliased rim is quantised into hard steps, and when Windows scales such an icon the masked-out pixels count as black, leaving a dark ragged outline around a light icon. The trap is set earlier than it looks — a drawing with only two colours makes ImageMagick write a palettised PNG. The script forces true colour in both places; check the entries report `bpp=32`.

## Known limitations

- **No gapless playback.** Each track is a new decoder process, so there is a short gap between tracks. Using ffmpeg's concat demuxer would close it.
- **The general build is large** (~160 MB per platform uncompressed) because it includes every video codec. The audio-only minimal build brings both platforms down to about 1-2 MB, at the cost of one (cross-)compile on Linux — see Packaging.
- **The first track of a session** pays a cold-start cost for loading the decoder libraries (~160 MB for the general build, negligible for the minimal one); the engine warms the decoder in the background at startup to hide it.
