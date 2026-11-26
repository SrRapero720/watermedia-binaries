# 📦 UPDATE 3.0.3
- 🐛 Fixed the missing icon in the NeoForge mod list on Minecraft 26.2 and newer
  - Mod developers no longer get a `logoFile` warning screen when the game starts

# 📦 UPDATE 3.0.2
- 🛠️ Loads on any Minecraft version from 1.18.2 up, any Forge, NeoForge or Fabric version and Java 17 or newer
  - New Minecraft versions, like 26.3, only need a WATERMeDIA update
- 🛠️ Now requires WATERMeDIA 3.0.1 or newer

# 📦 UPDATE 3.0.1
- 🛠️ Reworked bootstrap internals
- 🐛 Fixed JitPack builds failing to find WATERMeDIA

# 📦 RELEASE 3.0.0
- ⚙️ Native libraries load only through WATERMeDIA and never unload
- ⚙️ Required by WATERMeDIA on Minecraft clients, optional on dedicated servers
- ⚙️ Works only with WATERMeDIA 3.0.x; 3.0.0 beta builds are rejected
- ✨ New companion mod carrying the native libraries WATERMeDIA needs, split out of its jar
- ✨ Ships _FFmpeg_ 9.0.2 (LGPL build) and _JavaCPP_ 1.5.14
  - Plug and play on Windows (x64), macOS (Intel, Apple Silicon) and Linux (x64, ARM64)
  - Videos decode on the GPU and draw without passing through RAM
  - GPU video filters (Vulkan with _libplacebo_, Direct3D 12 and AMD AMF) handle colors, HDR and interlaced videos
  - Encodes H.265 (HEVC) videos with _Kvazaar_
- ✨ Downloads _yt-dlp_ and _BotGuard_ on demand for YouTube
  - Both are checked against their published checksums, and _BotGuard_ is pinned to the reviewed 0.1.2 release
- 🛠️ Requires Java 17 to 25
- 🛠️ Unsupported game or loader versions show a version mismatch instead of loading
- 🛠️ Licensed under the _Defensive Public Source (Non-Commercial) License v1.0.1_
