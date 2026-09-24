# MediaFix 更新日志

> MediaFix：`WaterFrames + WaterMedia` 的**纯客户端**修复/增强附属。3.0.0 起播放链路整体换成**自研 FFmpeg DASH 流式引擎**，所有功能仍通过 Mixin 注入，**不改动任何前置 mod 源码**。

---

## v3.0.0 (2026-09-24) —— 自研 FFmpeg DASH 流式引擎

> 这一版把播放链路整个换掉：**VLC 只做外壳，解码 / 时钟 / 音频输出 / 换链全部自研**；同时把"先下载缓存再播放"整条移除，只保留合规的流式直连。

### 🎬 引擎（新增 `dev.mediafix.engine` / `dev.mediafix.ffmpeg`）
- `MediaEngine`：状态机（LOADING / PLAYING / PAUSED / BUFFERING / ENDED / ERROR）、自研时钟、看门狗、水位线、ABR 调度、直链续期、无缝换源
- `FfmpegVideoSource`：FFmpeg 解 DASH 视频，D3D11VA 硬解；GPU→CPU 搬运后多线程**分带 sws** 转 RGBA；帧环背压；就地快进
- `FfmpegAudioSource` + `AudioSink`：自研音频链与声卡输出（Java Sound），多声道协商、软件增益、欠载 / 截断 / 爆音计数
- `MediaClock`：以**音频可听位置**为主时钟（死区 40ms + 残差 EMA + 速率微调 ≤3%/s + 单次离群剔除 / 连续离群硬对齐）
- `PacketBuffer`：压缩流预读队列（视频 48MB / 音频 8MB），解复用与解码分离、互不阻塞
- `MediaEngines`：引擎注册表（WeakHashMap + ReferenceQueue 回收）；播放器对象被重建时**引擎转交**、孤儿宽限

### 🔊 音频
- 杜比全景声（E-AC-3 6ch）/ Hi-Res 无损（FLAC）/ 普通 AAC 按偏好自动选择，缺失自动回退，不会没声音
- 多声道按设备能力输出；源 5.1 遇 2ch 设备时按**标准下混矩阵**折立体声（不是丢声道）
- 新增 `/mediafix audio [dolby|hires|best]`

### 📶 清晰度
- ABR 自适应（默认 `auto`）：按实测下载吞吐在可用档位间升降（保守取 min(快/慢) 估计、下档 0.90 / 上档 0.70、带驻留时间约束）
- 档位去重 + 编码偏好（H.264 > HEVC > AV1），默认**避开 AV1**（无硬解时软解 4K 只有个位数帧率）
- 新增 `/mediafix streams`：列出每条流的编码 / 真实分辨率 / 码率，可辨别"标称 4K 实为 1080p"

### 🔁 稳定性（本版集中修掉的几类问题）
- **时钟不再被永久冻住**：修复"seek / 视频饥饿 pause 时钟后，状态被翻成 PLAYING 却没人 start 时钟"。该状态下 `update()` 只把冻结时间设成音频可听位置，时钟退化成**每批音频跳一次（~200ms）**，视频帧成批过期 —— 表现为起播后长时间只有 3~4fps、每秒丢 21 帧，且只有人工暂停/恢复才能救回
- **直链续期**：到期前自动重新解析并热切换（声音不断、位置不变）；新增 `/mediafix refresh [full]`
- **就地快进**：进度同步要求小幅前跳时在已预读数据里丢弃，不重开连接、不清空缓冲（消除"卡一下 → 倍速追赶 → 再卡"）
- **帧归还与数据源解耦**：帧自带产出它的那条源，换链 / 关闭后归还不再落空（此前实测取走 550 帧只归还 90 次）
- **纹理强制重分配**：播放器实例或纹理 id 变化时重建纹理存储，修掉"多次换视频 / 刷新后只有声音没画面"
- **停止 / 暂停优先**：看门狗兜底不再覆盖用户的暂停意图（此前按停止后 4 秒会被拽回播放）
- **原地 seek 过滤 + 陈旧同步拦截**：避免无谓的"重开连接 + 清空音频缓冲"
- **同源判定按流路径**：直链签名每次解析都变，也能认出"同一个视频"，播放器重建时不再重新缓冲

### 🗑️ 移除
- 整条"先下载到本地缓存再播放"路径（`MediaStreamProxy`、`MediaCache`、`SimpleFileServerMixin`、下载进度 UI）—— 下载到本地属于灰色地带，现在只做流式直连
- `/mediafix ffmpeg on|off|offset`（引擎常开）、`/mediafix-stream cache` 相关指令

### ⚙️ 配置与工具
- 新增 `mediafix-ffmpeg.json`（原生库路径 / 硬解 / 预缓冲 / 缓冲区）、`mediafix-bili.json`、`mediafix-log.json`
- `mediafix-stream.json` 增加 `audioPreference`、`avoidAv1`、`catchUpMaxMs`
- 新增 `deploy.ps1`：一键构建 + 部署到客户端实例（**只动 `mediafix-*` 文件**）

### ✅ 已离线验证
- 打包的 FFmpeg 原生库可加载（`av_version_info = 8.0.1`）
- 扫码登录全链路（生成二维码 → PNG → 解码比对 → 轮询 `qrcode_key`）
- 杜比音轨可解（`eac3 6ch 48000Hz`，80/80 帧解码成功）
- 音频 seek 落点误差 −22 ~ 0 ms；视频 seek 落点与首帧时间戳符合预期（第 0 帧 = 0.0ms）

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
