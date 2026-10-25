<p align="center">
  <img src="docs/assets/icon.png" width="96" height="96" alt="Echo icon">
</p>

<h1 align="center">Echo</h1>

<p align="center">
  <b>A local music player that learns from what you actually play.</b><br>
  Daily mixes from your own library. Listening reports. Never online.
</p>

<p align="center">
  <b>English</b> · <a href="README.zh-CN.md">简体中文</a>
</p>

<p align="center">
  <a href="https://github.com/LiDonghao1120/Echo/releases/latest"><img src="https://img.shields.io/github/v/release/LiDonghao1120/Echo?style=flat-square&color=ff5a28&labelColor=111111&label=release" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-ff5a28?style=flat-square&labelColor=111111" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-ff5a28?style=flat-square&labelColor=111111" alt="Kotlin · Jetpack Compose">
  <img src="https://img.shields.io/badge/network-none-f1ece2?style=flat-square&labelColor=111111" alt="No network access">
</p>

<p align="center">
  <img src="docs/assets/hero.png" alt="Echo screens" width="100%">
</p>

---

## Why Echo

Echo (回响, "echo") is for people who keep their music as files. It plays what's on your phone and nothing else, and it gets better the more you listen:

- **Local by design.** No account, no streaming, no internet permission. Your files, your playlists and your listening history stay on the device.
- **Personal, not generic.** Recommendations and reports are built from what you play on this phone, not from what everyone else is playing.
- **A look of its own.** The design is built around 回, a square inside a square, like a sound coming back. Ink, paper and vermilion, set in Anton and Space Grotesk.

## Features

### Mixes from your own library
- **Daily Mix**: 30 tracks picked each day from your listening history, weighted towards the artists, genres and albums you keep coming back to. At most three songs per artist and two per album. It stays the same all day and changes the next morning.
- Home also builds **genre picks**, **artist radio**, **Rediscover** (songs you've played at least three times but not in the last 30 days), **Right now** (what you usually play at this hour) and **New to you** (songs you've never played).
- Recently played, most played, top artists and recently added, all at a glance.

### Artist pages, your way
- A full-bleed poster with parallax and the artist's wordmark on top. As you scroll, the wordmark shrinks into the top bar.
- Set a **poster** and a transparent PNG **wordmark** for any artist, from the photo picker or from files.
- **Batch import**: choose a folder and Echo matches images to artists by filename. `Artist_Name.png` becomes the wordmark and `Artist_Name_poster.jpg` the poster.
- Artists without a wordmark get their name set in large type.
- Posters and wordmarks also appear on Home, in the artist grid, in search and in your listening report.

### Listening report
- Pick **7 days, 30 days, a year or all time**. See total listening time and how it compares with the previous period, plus plays, songs, artists and a 28-day activity strip.
- Eight chapters: your daily rhythm on a 24-hour dial, the trend over time, your week, top artists, songs on repeat, the album shelf, a genre spectrum, and habits such as streaks and completion rate.
- A play counts after 10 seconds. Reaching 85% or moving on to the next track naturally counts as a full listen.

### Now Playing
- Colours taken from the album art, a large title and a waveform-style scrubber.
- The mini player expands into Now Playing with a shared-element transition.
- In the queue, swipe a track away or move it up next. **Play next** and **Add to queue** are available from any song's menu.

### Lyrics
- **Synced LRC lyrics** that scroll with the song. Tap a line to jump to it.
- Echo looks for a `.lrc` file next to the song, then in a lyrics folder you choose, then for lyrics embedded in the file (ID3 USLT, FLAC `LYRICS`).
- **Bilingual lyrics**: the translation sits under the original line. Lines with the same timestamp, `/`-separated pairs and "English 中文" lines are all recognised.
- Handles multiple timestamps, offsets and word-timing tags, and detects UTF-8 and GB18030 encodings.
- Optionally keeps the screen on while lyrics are showing.

### Your library
- Songs (sort by title, artist, album, date added or duration), albums, artists, genres, folders, playlists and favourites, plus search.
- Tracks with several artists (`/`, `、`, `;`, `&`, `feat.`) show up under each of them.
- If Android doesn't report genres, Echo reads them from the file tags.
- **Scan folders**: add folders with the system picker, including ones Android's media index skips, and include or exclude any folder on the device. Ignore tracks shorter than a set length. Duplicates are removed.

### Playback
- Media3 ExoPlayer in a background service, with controls in the notification, on the lock screen and on Bluetooth or wired headsets.
- Pauses when headphones are unplugged and respects audio focus.
- Your queue and position are restored after a restart.
- Dark, light or follow the system.

## Screenshots

<p align="center">
  <img src="docs/assets/home.png" width="30%" alt="Home">
  <img src="docs/assets/daily-mix.png" width="30%" alt="Daily Mix">
  <img src="docs/assets/artist.png" width="30%" alt="Artist page">
</p>
<p align="center">
  <img src="docs/assets/now-playing.png" width="30%" alt="Now Playing">
  <img src="docs/assets/stats.png" width="30%" alt="Listening report">
  <img src="docs/assets/library.png" width="30%" alt="Library">
</p>

<p align="center"><sub>Artists, albums and artwork in the screenshots are fictional samples.</sub></p>

## Download

Get the latest APK from **[Releases](https://github.com/LiDonghao1120/Echo/releases/latest)**.

- Requires Android 8.0 (API 26) or later.
- Every release is signed with the same key, so you can install over an older version and keep your history, playlists and artist art.
- The interface is in Simplified Chinese.

## Privacy

Echo has no internet permission, so it cannot send anything anywhere. It reads the audio files on your device and keeps its own data (listening history, playlists, favourites, artist posters and wordmarks) in app storage. Nothing leaves your phone unless Android's own device backup is turned on, in which case the database is included like any other app's.

## Building from source

```bash
git clone https://github.com/LiDonghao1120/Echo.git
cd Echo
./gradlew :app:assembleRelease
```

You need JDK 17+ and the Android SDK (compileSdk 36, minSdk 26). Without a release keystore the build falls back to debug signing. Unit tests run with `./gradlew :app:testDebugUnitTest`, and `./gradlew :app:recordRoborazziDebug` renders the screenshots. Every push to `main` is built by GitHub Actions; pushing a `v*` tag publishes a release.

### Tech stack

Kotlin · Jetpack Compose · Material 3 · Media3 (ExoPlayer, MediaSession) · Room · DataStore · Coil · AndroidX Palette · Robolectric + Roborazzi screenshot tests

Fonts: [Anton](https://github.com/googlefonts/AntonFont) and [Space Grotesk](https://github.com/floriankarsten/space-grotesk), under the SIL Open Font License ([licenses/](licenses)).

---

<p align="center"><sub>Made for the music you actually own.</sub></p>
