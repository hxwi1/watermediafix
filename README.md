# MediaFix

> **本 Mod 是专门给服务器做的** —— 供连入固定服务器的玩家在客户端安装使用，用于把 B 站视频稳定地投放到服务器里的水帧（WaterFrames）观影屏幕上。
>
> ⚠️ **版本依赖固定**：本 Mod 依赖 **固定版本**的播放器前置 mod 与依赖库，请严格按下表版本安装，**勿随意升级**，否则 Mixin 注入目标会失配导致崩溃或黑屏。

---

## 说明

MediaFix 是一个**纯客户端**修复/增强 mod，通过 **Mixin 注入**修复并增强 **WaterFrames** + **Bilibili-Media-Mod** 在服务器观影场景下的若干问题，不改动原 mod 的任何源码。

- **只装客户端**：本 mod 与放视频的 WaterFrames 方块同装于玩家客户端，服务端无需任何前置。
- **给服务器用**：针对多人联机播放场景专门调优（随机端口避免冲突、服务端停止自动关闭代理等）。
- **登录安全**：沿用 bilibili_media 的 `/bilimedia login` 扫码登录来拿高清直链，本 mod 做 Cookie 权限收紧与防泄露提示。

本 Mod 是**纯客户端附属修复**，与服务器端逻辑无耦合，但为服务端观影体验而开发。

---

## 兼容环境

| 项目 | 值 |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.231 |
| Loader | loader_version_range=[1,) |

## 依赖（版本固定，勿改动）

依赖通过 `compileOnly fileTree('libs')` 引入编译，运行时所需前置：

| 前置 mod / 依赖库 | 固定版本 |
| --- | --- |
| WaterFrames | **v2.1.23**（NeoForge, MC 1.21.1） |
| Bilibili-Media-Mod | **2.3**（NeoForge） |
| WaterMedia（WaterFrames 前置） | **2.1.36** |
| WorldComment（可选，用于`/mediafix hide`） | 0.3.2 + 1.21.1 |

> 以上运行时依赖版本为硬性要求。更换版本需同步更新 `libs/` 目录下的依赖 jar 并重新编译，否则可能出现 Mixin 注入失败（`Scanned 0 target(s)`）× 或运行崩溃。

---

## 功能

- **B 站视频服务器观影**：服务器中的 WaterFrames 方块放置 B 站链接后，玩家客户端拉取解析并播放。
- **先下载后播放（稳）**：`DashResolver` 将 DASH 视频/音频流下载到缓存后经本地 HTTP 服务播放；失败时自动降级（视频挂了保音频、杜比降级普通音轨），全程玩家提示。
- **本地 HTTP 服务优化**：开启 Range 支持、扩容线程池、随机端口避免多人冲突。
- **贴图串流修复**：修复空 `VideoPlayer` 上传帧不恢复 GL 状态导致的皮肤/方块贴图串流污染。
- **进度纠偏**：修复 `Display.tick` 陈旧同步误 `seekTo(0)` 导致的视频随机跳回开头。
- **信箱化解黑边**：非 16:9 屏幕上按比例居中放大，黑边占位，不做拉伸变形（越界有防御兜底，不卡黑屏）。
- **全景声（WASAPI）**：强制 VLC `aout=mmdevice`(WASAPI) 输出多声道，并根据与方块距离**自动**在 WASAPI 独占 / 共享模式间切换，不打扰 MC 音效（800ms 防抖）。

## 指令

| 指令 | 作用 |
| --- | --- |
| `/mediafix help` | 查看全部子指令帮助 |
| `/mediafix hide [on\|off]` | 切换 WorldComment 评论全隐藏（默认切换） |
| `/mediafix seekguard` | 查看当前进度纠偏阈值 |
| `/mediafix seekguard <秒>` | 设置进度纠偏阈值（1~60 秒，忽略陈旧 seek 用） |
| `/mediafix-stream [on\|off]` | 切换 DASH 高清缓存播放 |
| `/mediafix-stream quality` | 设置清晰度 / qn |
| `/mediafix-stream cache` | 查看 / 清空视频缓存 |

配置文件（生成于游戏根目录）：`mediafix-stream.json`、`mediafix-seekguard.json`、`mediafix-comment.json` 等。

---

## 构建

```bash
gradlew build
```

构建产物位于 `build/libs/`。产物 jar 放入客户端 `mods/` 目录即可。

## 许可

本项目采用 **MIT License**。注意：`TEMPLATE_LICENSE.txt` 为 NeoForge MDK 模板自带许可，仅适用于模板文件本身。