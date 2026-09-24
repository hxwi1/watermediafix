# MediaFix 更新日志

> MediaFix：`WaterFrames + WaterMedia` 的**纯客户端**修复/增强附属。3.0.0 起播放链路整体换成**自研 FFmpeg DASH 流式引擎**，所有功能仍通过 Mixin 注入，**不改动任何前置 mod 源码**。

---

## v3.0.0-hotfix (2026-09-24) —— 播放稳定性修补

> 3.0.0 上线当天实测暴露的几类问题，全部集中在**状态机与同步**上（不动解码 / 时钟 / 帧管线）。

### 🐛 修复
- **画面全黑：引擎被误关** —— waterframes 换播放器时会 `release()` 掉旧对象，5 秒后旧对象被 GC，它当初留在回收表里的弱引用**又给引擎减了一次计数**，把"已经转交给新播放器、正在出画面"的引擎判成孤儿，20 秒后被回收线程关掉。引擎一关 `video/audio` 源置空 → `isBroken()=true` → `readyForDisplay()=false` → waterframes 的 `canRender()` 恒 false → 画面再也不画（日志里 `canRender=false … 引擎状态=PLAYING 引擎就绪可显示=false` 一直刷）。现在：只有**没有任何活着的播放器持有**时才挂孤儿；到点回收前再核一遍，仍被持有就顺延；显式回收过的播放器不再重复减计数；`of()` 不再把已关闭的引擎当活引擎回答状态
- **片尾死锁：播完一直显示"正在加载"** —— `checkEnded()` 原本只在音频投喂循环"取不到下一块"时调用，而视频预读见底时的 `rebufferHold` 会让整个循环跳过；视频播到片尾预读必然见底且再也回填不上（流已读完），于是片尾判定一次都跑不到：引擎既进不了 ENDED 也不停，被 4 秒兜底在 BUFFERING ⇄ PLAYING 之间来回拽（实测 11:18:59~11:19:12 十几秒，取帧计数一动不动）。现在兜底线程也做片尾判定；并补一条保守判据：**位置已抵住片尾 + 连续 8 秒出不了帧 + 压缩预读为 0** 也算播完（视频源在片尾卡"等网络"时 `ended` 永远为 false）。日志新增 `播放结束（时长 …ms）`
- **播完后重新请求视频要手动刷新** —— 引擎停在片尾时，waterframes 新建播放器=重新播放，而转交过去的引擎还站在片尾（帧环空、出不了帧）。现在转交时若引擎已 ENDED 就自动回起点
- **在服务器播放时进度对不上** —— 两处：① 进度守卫把"服务端下发的往回跳"也当成陈旧同步拦掉（实测连续拦下 `目标 2059150 / 823650 / 0ms，当前 2562500ms`）；② 更下游一层：seek 之后声卡的"已播出位置"要等缓冲回填并真正播出去才跳（实测 6 秒），而视频 1 秒内就落到新位置，脱节自愈于是"按时钟纠正视频"，把刚对齐好的位置又拽回旧位置（`11:06:36 自愈 → 视频 seek 回 66303ms`）。现在：新增 `TimePacket` 打点识别"服务端明确下发的位置"并放行；守卫只拦"没有下发、又跳回 ≈0"的那种；seek 过渡期内不做脱节自愈，改为等音频时钟跟上
- **暂停状态下换源 = 全黑** —— waterframes 的 `switchVideoMode()` 会把 `data.paused` 直接套给新播放器，于是新引擎一出生就是暂停；我们以前连解码一起停掉，一帧都没有 → 黑屏（看起来像"直播坏了"，实测整场 20 秒一直是 PAUSED）。现在暂停时仍会把"当前这一帧"解出来并显示（VLC 的行为），补完立刻冻回去；渲染侧只在"这个播放器还没上传过任何一帧"时才要这一帧，不会每帧重复上传 4K 图
- **拼接处的音频爆音** —— 采样级爆音检测只看我们自己的数据块边界，而三类接缝在数据里是**连续的**、检测器根本看不见：设备缓冲写空（欠载）后声卡已经播出一段数据里没有的静音、`flush()` 把波形中途硬切（seek / 就地快进）、暂停恢复时的线路 stop/start。实测 3.5 小时会话里 `爆音=0 去咔=0`，但耳朵能听见。现在这三类接缝一律强制做 3ms 余弦淡入去咔（原来只在检测到跳变时做，且只有 1ms）

### 📋 新增诊断
- `waterframes 要求暂停/播放：方块数据 paused=… active=… tick=…`（谁让我们暂停的一目了然）
- `跟随服务端进度（…）：目标 …ms，当前 …ms` / `seek 过渡期：视频已在目标 …ms，等音频时钟跟上`
- `播放结束（时长 …ms）`；`无人接管，关闭孤儿引擎` 之前会先核对是否真的无人接管

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

### 📺 B 站直播（新增）
- **链接识别**：`live.bilibili.com/<房间号>`（兼容 `/blanc/`、`/h5/`、`?room_id=`）
- **新增 `bili/BiliLive`**：调 `xlive/web-room/v2/index/getRoomPlayInfo`（`protocol=0&format=0&codec=0&qn=10000`）拼出 FLV 直链；未开播 / 付费加密 / 无可用组合一律给提示并回退前置模组
- **音视频同一条流**：直播 FLV 里 aac 与 h264 复用同一根时间轴，所以 video = audio = 同一个直链；固定原画，直播不参与 ABR
- **系统时间锚定时间轴**：直播时间戳是服务器侧固定偏移，实测与系统时间 1:1（8 秒窗口漂移约 6ppm，换算即本地时间）。用它把原始 PTS 折算成"开播以来"的位置并算出**实时延迟**；断流重连沿用同一锚点
- **seek 全部忽略**（含进度同步与暂停同步，留一条日志）、**换源不做定位**（直播打开即在边缘）
- **断流自动重连**：CDN 掐流 / 主播重推 / URL 到期（直播直链带 `expires=`）自动重新取链并热切换；下播每 5 秒重试，重开自动接上
- `DashHandoff` 的直播标记同时进线程内交接棒与按主 URI 的全局表（解析在 ImageFetch 线程、引擎在播放器线程创建，只存线程内会丢）
- **顺带修掉两个直播专属 bug**：① `readyForDisplay()` 的"等时长"约束把直播（duration = −1）永远判为未就绪 → waterframes 的 `canRender()` 恒 false → 画面永远不渲染（表现为"只有声音没画面"）；② 直播标记没跨线程 → `seek` 守卫失效，直播被 `seekTo(0)` 打断（落点偏后十几秒）

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
- **直播接口与流**：可用组合、JSON 层级逐级核对（`playurl_info.playurl.stream[].format[].codec[].url_info[]`）；打包的 FFmpeg 直接打开直播 FLV（590ms，流内 `aac 2ch 48k` + `h264 1080p60`，连读 200 包正常；HLS 打开要 4.3s，故固定用 FLV）
- **直播时间轴**：`PTS − 系统时间` 为恒定值（8 秒窗口 ±50ms ≈ 6ppm），换算结果与本地时间吻合；断流重新取链后两段流该偏移只差 0.45s → 复用同一锚点即可无缝续播

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
