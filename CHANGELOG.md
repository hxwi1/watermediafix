# MediaFix 更新日志

> MediaFix：`WaterFrames + Bilibili-Media-Mod` 纯客户端修复附属，所有功能通过 Mixin 注入，**不改动任何前置 mod 源码**。

---

## v2.0.0 (2026-09-21)

### 🎉 重大功能

**DASH 高清缓存播放**（`DashResolver.java`）
- 通过 wbi 签名 + `fnval=4048` 请求 B 站 DASH playurl，绕过前置 durl 链路的 `platform=html5` 限制
- 支持 4K / 1080p60 / 720p 等全档位，最高码率自动挑选
- 杜比全景声优先选择（dash.dolby 块），下载失败自动降级普通音轨
- 视频与音频分轨下载（`.part` 临时文件 → 原子改名，缓存命中直接复用）
- 下载进度实时显示在 actionbar，完成/失败走聊天栏提示

**番剧 / 大会员内容支持**
- 新增 `BilibiliBangumiPatchMixin`：拦截 bilibili_media 的番剧独立解析器（`BilibiliBangumiPatch`），ep 链接转发给 DashResolver
- 走 `pgc/player/web/playurl` 接口（无需 wbi 签名），带 Cookie 换大会员清晰度
- 自动处理试看（`is_preview=1`）→ 回退原生，DRM（`is_drm=1`）→ 跳过
- 缓存文件命名：`mediafix_ep<epId>_v.mp4` / `_a.m4a`
- 番剧 DASH 音轨字段兼容（`baseUrl/base_url/backupUrl/backup_url` 四种键）

**input-slave 音频桥**（`BasePlayerDiagMixin.java`）
- watermedia 2.1.36 的 `BasePlayer` 完全忽略 `Result.audioUrl`，DASH 独立音轨无法自动挂载
- 在 `play()` 调用处，从视频缓存 URL 直接推导出音频缓存 URL，注入 VLC `:input-slave` 选项实现分轨挂载

### 🛠️ 画面修复

**GL 纹理污染修复**（`VideoPlayerMixin.java`）
- watermedia 的 `VideoPlayer.lambda$display$0()` 每帧在"当前活动纹理单元"上传视频帧后，不恢复 `GL_ACTIVE_TEXTURE` 和 `GL_TEXTURE_BINDING_2D`
- 修复：在 HEAD/TAIL 保存并恢复 GL 状态，避免后续渲染误采样到上一个输入的玩家皮肤贴图 → 画面串成玩家皮肤

**信箱化自适应（不拉伸、允许黑边）**
- `DisplayAspectMixin`：每帧捕获屏幕格宽高比 → `LetterboxState`
- `VideoPlayerMixin`：用 `@Redirect` 重定向 `RenderAPI.uploadBuffer` 调用到 `Letterbox.upload`
- `Letterbox.java`：按屏幕比例居中拷贝视频帧到黑色画布后上传，视频不拉伸变形
- 修复：首次实现误把 Canvas 内部类写在 mixin 包 → `IllegalClassLoadError`；现已搬到普通类
- 修复：`uploadBuffer` 实际调用点在 `lambda$display$0` 而非 `preRender`，注入点写错导致 `Scanned 0 target(s)` → 类加载硬崩
- 修复：逐行拷贝时帧缓冲实际大小不足（`newPosition > limit`）→ semaphore 永远不 release → 1 秒超时 → 播放器挂死黑屏；现已加越界防御，异常时整幅透传

### 🔧 多人同步修复

**进度纠偏阈值**（`WaterframesSeekGuardMixin.java`）
- waterframes `Display.tick()` 在多人同步时双闸条件 `|本地时间-远端时间|>TH && |本地时间-上次时间|>TH` 触发 `seekTo(0)`
- 硬编码阈值 2 秒太敏感 → 偶尔把已播了一段的视频拉回开头
- **v2.0.0 改动**：阈值默认改为 **5000ms（5 秒）**，通过 `SeekGuardConfig` 可配置
- 新增 `/mediafix seekguard [秒]` 指令（1~60 秒范围），**实时生效无需重进游戏**
- 仅拦截"目标为 0 且已播超阈值且不在片尾"的陈旧同步，正常循环/重播（片尾触发）和玩家手动拖动全部放行

### 🔊 音频

**全景声 WASAPI 强制输出**（`PanoramicAudioMixin.java`）
- VLC 默认直通系统音频；部分环境下被 Windows 音频栈强制下混立体声
- 强制 VLC 使用 Windows WASAPI（`--aout mmdevice`），5.1/7.1 多声道视频按原生声道数直出
- 通过 Mixin 注入 `require=1` 确保前置 BasePlayer 的选项配置点存在

**视频音量跟随 Minecraft 主音量**（`VideoPlayerVolumeMixin.java`）
- watermedia 的 `setVolume` 定义在父类 `BasePlayer`，Mixin 必须瞄准 `org.watermedia.api.player.videolan.BasePlayer` 而非 `VideoPlayer`

### 👁️ WorldComment 评论隐藏

- `CommentHideMixin` + `CommentHide` 配置类
- `/mediafix hide [on|off]` —— 切换全隐藏，配置存 `mediafix-comment.json`
- 可选依赖 WorldComment，缺失时静默跳过

### 📋 指令一览（v2.0.0 新增 help）

| 指令 | 说明 |
|------|------|
| `/mediafix help` | 显示本帮助（**v2.0.0 新增**） |
| `/mediafix hide` | 切换 WorldComment 评论隐藏（无参 = 切换） |
| `/mediafix hide on/off` | 开启/关闭 |
| `/mediafix seekguard` | 查看当前进度纠偏阈值（默认 5 秒，**v2.0.0 改动**） |
| `/mediafix seekguard <秒>` | 设置阈值（1~60），实时生效并写盘 |
| `/mediafix-stream` | 无参查看当前配置 |
| `/mediafix-stream on/off` | 开启/关闭 DASH 高清缓存播放 |
| `/mediafix-stream quality <模式>` | 设置清晰度（360p / 480p / 720p / 720p60 / 1080p / 1080p60 / 4k / auto） |
| `/mediafix-stream cache` | 查看缓存占用统计 |
| `/mediafix-stream cache clear` | 清空所有视频缓存文件 |

### ⚙️ 配置文件（游戏目录）

| 文件 | 键 | 默认值 | 说明 |
|------|---|--------|------|
| `mediafix-stream.json` | `stream` / `maxQn` / `highres` | true / 116 / true | DASH 缓存开关、清晰度上限（qn）、高分辨率开关 |
| `mediafix-seekguard.json` | `seekGuardMs` | 5000 | 进度纠偏阈值（毫秒） |
| `mediafix-comment.json` | `enabled` | false | WorldComment 评论隐藏开关 |

### ⚠️ 已知限制

- **纯客户端**：本 mod 仅客户端侧生效，服务端加载时静默跳过
- **依赖前置 mod**：watermedia 2.1.36 + waterframes 2.1.23 + bilibili_media 2.3；缺少任一 DASH 链路自动回退原生
- **DRM / 试看番剧**：pgc 接口返回 `is_drm=1` 或 `is_preview=1` 时自动回退前置原生播放
- **缓存管理**：DASH 缓存文件存 `BiliBiliMediaFiles` 目录，需手动清理或用 `/mediafix-stream cache clear`
- **VLC 日志**：`VlcLogMixin` 需 waterframes 的 VLC 日志配置点存在（静态字段非 private），否则注入静默失败

### 📦 前置依赖

```
bilibili_media  → 2.3       (compileOnly, 可选依赖)
watermedia      → 2.1.36    (compileOnly, 可选依赖)
waterframes     → 2.1.23    (compileOnly, 可选依赖)
WorldComment    → 任意      (compileOnly, 可选依赖)
```

---

## 历史版本（摘要）

### v1.1.x ~ v1.2.x（迭代期，功能逐步稳定）
- 先下载后播策略定型（放弃流式代理，解决"连接拒绝"问题）
- VLC 媒体探针线程曾导致 JNA "Invalid memory access" 硬崩 → 已删除
- waterframes 缓存 FORGOTTEN 重建 Display 时新旧 VideoPlayer 短暂并存 → GL_INVALID_OPERATION 刷屏（暂未证实可致崩）

### v1.0.x（初始版本）
- 修复多人播放黑屏（扩大 HTTP 线程池 + 随机端口 + Range 分片）
- 修复画面串成玩家皮肤贴图（GL 状态保存/恢复）
- 修复进度自动跳回开头（seek 纠偏逻辑）
- 全景声 WASAPI 强制输出 + 音量跟随主音量
