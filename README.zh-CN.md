<p align="center">
  <img src="docs/assets/icon.png" width="96" height="96" alt="回响 图标">
</p>

<h1 align="center">回响</h1>

<p align="center">
  <b>越听越懂你的本地音乐播放器。</b><br>
  每日推荐 · 收听报告 · 永不联网
</p>

<p align="center">
  <a href="README.md">English</a> · <b>简体中文</b>
</p>

<p align="center">
  <a href="https://github.com/LiDonghao1120/Echo/releases/latest"><img src="https://img.shields.io/github/v/release/LiDonghao1120/Echo?style=flat-square&color=ff5a28&labelColor=111111&label=release" alt="最新版本"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-ff5a28?style=flat-square&labelColor=111111" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-ff5a28?style=flat-square&labelColor=111111" alt="Kotlin · Jetpack Compose">
  <img src="https://img.shields.io/badge/network-none-f1ece2?style=flat-square&labelColor=111111" alt="无网络权限">
</p>

<p align="center">
  <img src="docs/assets/hero.png" alt="回响 界面" width="100%">
</p>

---

## 为什么是「回响」

回响写给习惯把音乐存成文件的人。它只播放手机里的歌，听得越多，越合你的口味：

- **纯本地**：没有账号、没有在线曲库、没有网络权限。音乐文件、歌单和收听记录都留在手机上。
- **只属于你**：推荐和报告都来自你在这台手机上的真实收听，而不是大众榜单。
- **有自己的样子**：设计语言源自汉字「回」—— 方框套着方框，像声音的回响。墨、纸、朱三色，Anton 与 Space Grotesk 字体。

## 功能

### 从你自己的曲库里推荐
- **每日推荐**：每天从你的收听记录里挑 30 首，偏向你常听的歌手、流派和专辑；同一歌手最多 3 首、同一专辑最多 2 首。当天不变，第二天换新。
- 首页还有**流派精选**、**歌手电台**、**重温**（听过至少 3 次、但 30 天没听的歌）、**此刻常听**（你通常在这个时段听的歌）和**新发现**（从没播放过的歌）。
- 最近播放、最常播放、常听歌手、最近添加，一眼看全。

### 自定义歌手页
- 全幅海报带视差，歌手标志居中压在海报上；向上滚动时，标志缩小并停到顶栏。
- 可为每位歌手设置**海报**和透明 PNG **名称标志**，从相册或文件中选择。
- **批量导入**：选一个文件夹，回响按文件名自动匹配歌手：`Artist_Name.png` 作为标志，`Artist_Name_poster.jpg` 作为海报。
- 没有标志的歌手，用大号字体排出名字。
- 海报和标志也会出现在首页、曲库歌手网格、搜索和收听报告里。

### 收听报告
- 可选 **7 天、30 天、一年、全部**：总收听时长及与上一周期的对比，播放次数、歌曲数、歌手数，以及近 28 天的活跃方块条。
- 八个章节：24 小时日晷上的收听节律、起伏趋势、一周分布、最爱歌手、单曲循环、唱片架、流派光谱，以及连续收听天数、完整播放率等习惯数据。
- 播放满 10 秒才计入记录；播放到 85% 或自然切到下一首，算作完整播放。

### 正在播放
- 背景配色取自专辑封面，大号标题，波形刻度式进度条。
- 迷你播放器展开为全屏播放页，封面与标题平滑衔接。
- 播放队列里可以左右滑动移除歌曲，或把歌曲移到下一首；任意歌曲的菜单里都有「下一首播放」和「添加到播放队列」。
- **定时关闭**：15、30、45、60、90 分钟，自定义任意时长，或播完当前歌曲。倒计时显示在播放页，最后 30 秒音量渐弱，关掉应用也照常计时。

### 歌词
- **LRC 滚动歌词**，跟随播放自动居中，点按某一行即可跳转。
- **逐字歌词**：当前行按字从左到右点亮，前沿是从纸色过渡到朱色的柔和擦除，正在唱的字微微上浮发光。文件带逐字时间时按真实时间走（增强 LRC 的 `<mm:ss.xx>` 标签、行内 `[mm:ss.xx]字` 写法、QRC/KRC 逐字时间、Apple Music 式 `.ttml`），没有的行按音节在整行时长内估算。可在歌词界面或设置里切换逐行 / 逐字。
- 依次查找：歌曲同目录下的 `.ttml` 或 `.lrc` 文件 → 你指定的歌词文件夹 → 文件内嵌歌词（ID3 USLT、FLAC `LYRICS`）。
- **双语歌词**：译文显示在原文下方。同一时间戳的两行、用 `/` 分隔的写法、「原文 译文」同一行的写法（英文、日文、韩文、日英混排、德文等原文后接中文译文）都能识别。
- 文件开头「歌名 - 歌手」这样的信息行保持完整，不会被误拆成原文和译文。
- **逐首调整歌词时间**：在歌词界面以 0.1 秒为一档让歌词提前或延后，按歌曲分别保存。
- **为歌曲指定歌词文件**（`.lrc`、`.ttml` 或 `.txt`）：跟随歌曲保存，文件移动后依然有效，并包含在备份里。
- 支持多时间戳和 offset，自动识别 UTF-8 与 GB18030 编码。
- 可选显示歌词时保持亮屏。

### 曲库
- 歌曲（按标题、歌手、专辑、添加时间或时长排序）、专辑、歌手、流派、文件夹、歌单和我喜欢的音乐。
- **拼音搜索**：歌名、歌手、专辑都能用全拼、首字母或汉字混拼来找（`zhoujielun`、`zjl`、`周jl`），完全离线；常见多音字两种读音都能搜到。
- 歌曲和歌手列表右侧有 **A–Z 索引**，按拼音排序、中英文混排，手指滑过即可跳到对应字母。
- 多位歌手合作的歌曲（`/`、`、`、`;`、`&`、`feat.`）会分别归到每位歌手名下。
- 系统没有提供流派信息时，回响会直接读取文件标签。
- 能读取系统读不到的标签：**APEv2**（APE 文件，以及用 APEv2 标注的 MP3）和 **WMA/ASF** 元数据，包括封面。
- 打开即是曲库：上次扫描的结果保存在本机，新增或删除的文件在后台自动同步。
- **扫描文件夹**：通过系统选择器添加文件夹（包括系统媒体库漏掉的目录），也可以勾选或排除设备上的任意文件夹；可忽略短于指定时长的音频；重复的歌曲会自动去除。
- 我喜欢、歌单和收听记录跟着文件走：按文件路径记录，歌曲被移动或改名后，会按标签重新找回。

### 备份与诊断
- **备份**：把我喜欢、歌单、收听记录、设置、扫描文件夹规则、歌词时间调整与指定的歌词文件，以及歌手海报和标志导出为一个文件，存到你想放的地方。导入时可选择与手机上的数据合并，或直接替换。
- **诊断日志**：回响在本机保留一份小体积日志（播放自动恢复、扫描耗时、错误和崩溃信息），可在设置中导出后附在问题反馈里，不会自动发送到任何地方。

### 音效
- **均衡器**：十种预设，也可以直接拖动曲线自定义。预设按倍频程频段设计，自动适配手机提供的频段数。
- **低音增强**（设备支持时）。
- **音量平衡（ReplayGain）**：按单曲或专辑读取 `REPLAYGAIN_*` 标签（ID3、Vorbis 注释、MP4、Opus R128）统一响度，可调前置增益并防止削波；无缝专辑依旧无缝。
- **淡入淡出**：播放、暂停和切歌时短暂淡入淡出，可关闭。
- 入口在 设置 → 音效，或播放页右上角菜单。

### 桌面小部件
- 封面、歌名、歌手和上一首 / 播放 / 下一首，延续墨、纸、朱的配色；可从一行拉伸到两行，跟随浅色与深色模式。
- 关掉应用或重启手机后仍显示上一首歌，点播放即从上次停下的地方继续。

### 播放
- 基于 Media3 ExoPlayer 的后台播放服务，支持通知栏、锁屏、蓝牙和线控耳机控制。
- 拔出耳机自动暂停，正确处理音频焦点。
- 重启后恢复上次的播放队列和进度。
- 播放卡住或解码出错时，从停下的位置自动重新开始；同一首反复出错则跳到下一首。
- 深色、浅色或跟随系统。

### 中英双语
- 整个界面都有简体中文和英文两套文案，从收听报告到桌面小部件和通知栏；英文按单复数和英文日期格式显示。
- 默认跟随手机语言，也可以在 设置 → 语言 里选择跟随系统、简体中文或 English；Android 13 及以上还可以在系统的“应用语言”里单独设置。
- 切换后立即生效，不会打断正在播放的音乐。

## 截图

<p align="center">
  <img src="docs/assets/home.png" width="30%" alt="首页">
  <img src="docs/assets/daily-mix.png" width="30%" alt="每日推荐">
  <img src="docs/assets/artist.png" width="30%" alt="歌手页">
</p>
<p align="center">
  <img src="docs/assets/now-playing.png" width="30%" alt="正在播放">
  <img src="docs/assets/stats.png" width="30%" alt="收听报告">
  <img src="docs/assets/library.png" width="30%" alt="曲库">
</p>

<p align="center"><sub>截图中的歌手、专辑和封面均为虚构示例。</sub></p>

## 下载

前往 **[Releases](https://github.com/LiDonghao1120/Echo/releases/latest)** 下载最新 APK。

- 需要 Android 8.0（API 26）及以上。
- 所有版本使用同一签名，可直接覆盖安装，收听记录、歌单和歌手形象都会保留。
- 界面支持简体中文和英文。

## 隐私说明

回响没有网络权限，因此无法向任何地方发送数据。它只读取手机上的音频文件，自己的数据（收听记录、歌单、我喜欢、歌手海报与标志）保存在应用私有目录中。除非你自己导出备份或诊断日志，或开启了 Android 系统自带的设备备份（届时数据库会像其他应用一样被备份），否则数据不会离开手机。

## 从源码构建

```bash
git clone https://github.com/LiDonghao1120/Echo.git
cd Echo
./gradlew :app:assembleRelease
```

需要 JDK 17+ 与 Android SDK（compileSdk 36，minSdk 26）。没有 release 签名文件时会自动使用 debug 签名。单元测试：`./gradlew :app:testDebugUnitTest`；`./gradlew :app:recordRoborazziDebug` 可生成截图。每次推送到 `main` 都会由 GitHub Actions 构建；推送 `v*` 标签会发布 Release。

### 技术栈

Kotlin · Jetpack Compose · Material 3 · Media3（ExoPlayer、MediaSession）· Room · DataStore · Coil · AndroidX Palette · Robolectric + Roborazzi 截图测试

字体：[Anton](https://github.com/googlefonts/AntonFont) 与 [Space Grotesk](https://github.com/floriankarsten/space-grotesk)，采用 SIL Open Font License。拼音数据来自 [pinyin-data](https://github.com/mozillazg/pinyin-data)（MIT），由 `tools/gen_pinyin.py` 打包。见 [licenses/](licenses)。

---

<p align="center"><sub>为你真正拥有的音乐而做。</sub></p>
