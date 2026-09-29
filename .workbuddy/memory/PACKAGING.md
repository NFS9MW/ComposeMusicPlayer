# 打包与分发（从 MEMORY.md 拆出）

只在真正打包或改图标/解码器时需要读；日常工作看 MEMORY.md。

## 打包与分发
- **图标**由 `scripts/generate_app_icon.py` 从 `assets/app-icon.svg` 生成，产物入库。手写 `ImageVector`（`ui/icons/AppIcons.kt`），不用被钉死在 1.7.3 的 `materialIconsExtended`。
  - **反色版**：源美术是深色圆盘 + 镂空音符，镂空令音符透出背景 ⇒ 深色任务栏上整枚消失。脚本在 `<path>` 第二个 `M` 处切开分别填色 → 浅色圆盘 `#F1F0F6` + 深色音符 `#272636`（浅色下圆盘近乎隐形但可读，刻意取舍）。
  - **ICO 每个条目必须 32 位带 alpha**（症状「黑边 + 毛刺」）：低于 256px 可存「调色板 + 1 位掩码」，**没有 alpha**。入口在 PNG——只有两种颜色的图会被 IM 写成 `PaletteAlpha`(PNG8)。修复：栅格化用 `PNG32:`、写 ICO 时 `-alpha on -type TrueColorAlpha`；核对条目头第 14 字节 bitCount=32（或半透明像素数，修复前恒为 0）。
  - 三条路径：`composeResources/drawable/app_icon.png`(256px，窗口图标，需 `import ...resources.app_icon`)；`packaging/icons/windows/app.ico`(7 档)；`packaging/icons/linux/app.png`(128px——jpackage 只接受单张图并读其像素尺寸吸附到 16/22/32/48/64/128)。验证嵌入：解析 `.ico` 目录项后在 exe 里做子串查找。
- **`scripts/build_minimal_ffmpeg.py`：自制音频专用静态构建**（`--disable-everything` + 只开所需 decoder/demuxer/parser/encoder/muxer/bsf/protocol，`--enable-small`，关掉 avfilter/swscale/avdevice/postproc；Windows 用 mingw-w64 交叉编译）。每平台两个自包含文件、约 1–2 MB（对比 160 MB）。**必须 `--enable-zlib`**（PNG 封面依赖）；**静态而非共享**（不依赖加载器找 `libav*`——那正是会静默出错的地方）；无 nasm 时自动 `--disable-x86asm`。**尚未在真机跑过**（本机是 Windows，只验证了语法与报错路径）。
- `scripts/fetch_ffmpeg.py`：官方 BtbN **共享版**，钉日期化标签（非 `latest`），只取 `bin/` 与 LICENSE。二进制不入 git。Windows 解压后 **160 MiB**，`avcodec-62.dll` 一个 91 MB。
- **踩过：`third_party/ffmpeg/linux-x64/` 曾只有两个共享版启动器（510KB/219KB）而没暂存它们需要的 `libav*.so.62`** ⇒ 打出的 `.deb` 里解码器起不来，开发机上看不出来。现在 `verifyBundledDecoder` 会**从可执行文件里读出引用的库名**（`lib(av|sw)*.so.数字` / `(av|sw)*-数字.dll`）核对同目录是否存在，缺了就报错。**只认带版本号的 soname**（裸 `libavcodec.so` 可能只是静态二进制里的文档字符串）。用 `-Dos.name=Linux` 复现该分支。
- `verifyBundledDecoder`：独立任务（无 outputs ⇒ 永不 up-to-date），由 8 个打包/镜像任务 `dependsOn`。**不要挂 `prepareAppResources`**（NO-SOURCE 被跳过时 action 不执行，恰绕过最该拦的场景）；**不要挂 `run`**。`doFirst/doLast` 里不能引用 `rootProject`（会放弃配置缓存），`os.name`/`os.arch` 用 `providers.systemProperty`。
- `appResourcesRootDir` 指向 `third_party/ffmpeg`；子目录名 `windows-x64`/`linux-x64` 符合 Compose 的 `<os>-<arch>` 约定，**jpackage 会把它拍平**到 `app/resources/`。
- Linux 需 `patchelf --set-rpath '$ORIGIN'`（**只有共享版需要**，静态版不需要）。Deb 需 `fakeroot`。
- **菜单/桌面项要了才建**：Windows 只在传了 `--win-menu` 时才写开始菜单项（`windows { menu = true }`；`shortcut` 故意不开）。Linux 的 `withDesktopFile` 条件含「配了自定义图标」，桌面项本来就会生成；`menuGroup`（= `--linux-menu-group`，**不是** `--linux-app-category`）才是 `.desktop` 的 `Categories=` 来源。Linux 启动器图标走**绝对路径**，不走图标主题。
- **改名会搬配置目录**：`AppPaths` 在新建目录前先看旧名目录是否存在，存在就 `Files.move` 过去，只在目标不存在时搬，所以不会覆盖真实曲库。
- **jlink 会裁掉 `jdk.localedata` 导致中文排序静默退化**（开发运行正常、装出来变码点序）。必须 `modules("jdk.localedata", "jdk.unsupported")`。⇒ **任何依赖 Locale 的行为都要在打包产物上复验。**
- **改启动器（图标/名称）必须升 `packageVersion`**：Windows Installer 在传入文件版本资源不变时保留旧副本，同版本号重打包覆盖安装会留下旧 exe。升版本号会触发 major upgrade（`RemoveExistingProducts Before="CostInitialize"`）。

## Windows 打包：三处「只在一台机器上成立」的东西

项目在两台机器之间复制，任何写死的机器专属值都会在另一台爆炸。以下是已经踩过的三个。

### 1. 打包用的 JDK（`cmp.packaging.javaHome`）

- Fedora 需要它：发行版 OpenJDK 被补丁过，jlink 拒绝从「已修改」的 JDK 构建镜像（`Error: modified conf/security/java.security`）。Windows 不需要，Gradle JVM（Oracle JDK 21）本来就好用。
- **它是机器本地路径，不能放在项目的 `gradle.properties` 里**——那份文件会跟着项目走。`**~/.gradle/gradle.properties` 的优先级高于项目内那份**（实测，`./gradlew properties` 可看生效值），机器专属值放用户级文件。
- 放错位置在 Windows 上的症状：Windows 把 `/home/rhp/…` 当成「当前盘根」的相对路径，解析出 `H:\…\composeApp\home\rhp\.gradle\jdks\jetbrains_s_r_o_-25-amd64-linux.2` 这种落在构建树里的怪路径，于是**每个打包任务在动手之前**就被输入校验拦下：
  ```
  property 'jdkHome' specifies directory 'H:\...\composeApp\home\rhp\.gradle\jdks\...' which doesn't exist
  ```
- `composeApp/build.gradle.kts` 现在会**忽略指向不存在目录的值**并回落到 Gradle JVM（`Application.javaHome` 的默认值就是 `java.home`），只打一行 lifecycle 说明。⇒ 两台机器都不必改这个文件。

### 2. WiX（Windows 独有前提，由插件自己下载）

- `downloadWix` 取 GitHub release 的 `wix311-binaries.zip` 存到 `~/.gradle/compose-jb/wix311.zip`；`unzipWix` 解压到 **`<root>/build/wix311`**；Windows 打包任务把该目录挂到 jpackage 的 PATH 上（`AbstractJPackageTask.jvmToolEnvironment()`）。
- **系统里没有单独安装 WiX**（`%ProgramFiles(x86)%`、PATH、winget 都没有），`build/wix311` 那份就是全部。
- 两半缓存各自独立：zip 被清而解压目录还在时，插件照样重新下载；不通 GitHub 的机器就卡在 `CONNECT refused by proxy: 502 Bad Gateway` / `HttpClient` 栈里，而旁边明明躺着一份可用的工具链。根 `build.gradle.kts` 现在在 `build/wix311` 同时含 `candle.exe`、`light.exe`、`darice.cub` 时跳过这两个任务。
- 想把 WiX 放在固定位置：设 `WIX_PATH` 环境变量指向该目录。插件读到它就**不再创建**这两个任务（`wixToolset.kt` 里第一件事就是查这个变量）。
- `configureWix()` 只在 `currentOS == Windows` 时调用 ⇒ Linux 上这两个任务根本不存在，根脚本里的 `tasks.matching` 自然是空集。

### 3. Gradle 的代理

Gradle **不读 `HTTP_PROXY` 环境变量**，只认 `gradle.properties` 里的 `systemProp.http(s).proxyHost/Port`。`~/.gradle/gradle.properties` 里配着一个没在跑的代理时，所有依赖解析都会失败，而且被包装成误导性的措辞（`Got socket exception during request. It might be caused by SSL misconfiguration`），顺着那句话查会查错方向。直线距离最近的一句是更下面的 `Caused by: java.net.ConnectException: Connection refused`。

### 验证（Windows）

- `./gradlew :composeApp:packageMsi` → `composeApp/build/compose/binaries/main/msi/ComposeMusicPlayer-<版本>.msi`（1.1.1 时 142,767,621 字节）。
- 应用镜像：`app/ComposeMusicPlayer/{ComposeMusicPlayer.exe, app/, runtime/}`。
  - **`app/resources/` 是被拍平的**：`ffmpeg.exe`、`ffprobe.exe` 与 7 个 `*.dll` 直接躺在里面，没有 `windows-x64/` 这一层。`FfmpegBinaries.rootCandidates()` 同时接受拍平与一层嵌套，所以两种布局都能找到。
  - `runtime/release` 的 `MODULES=` 里必须有 `jdk.localedata`（否则中文排序静默退化成码点序）；`runtime/` 约 99 MB（对比完整 JDK 的数百 MB）。
- 冒烟测试见 MEMORY.md「开发与验证」：**产物里没有 `java.exe`**（jpackage 只留 `bin/server/jvm.dll` 那一套），只能跑 launcher exe，配置目录靠改 `app/ComposeMusicPlayer.cfg` 的 `[JavaOptions]` 注入。
