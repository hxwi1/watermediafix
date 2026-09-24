package dev.mediafix.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mediafix.MediaFix;
import dev.mediafix.abr.AbrController;
import dev.mediafix.abr.AbrLadder;
import dev.mediafix.config.FfmpegConfig;
import dev.mediafix.config.StreamConfig;
import dev.mediafix.ffmpeg.FfmpegRuntime;
import dev.mediafix.bili.BiliAuth;
import dev.mediafix.bili.BiliUrls;
import dev.mediafix.ui.Notice;
import org.watermedia.api.network.patchs.AbstractPatch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.TreeMap;

/**
 * 高清(DASH)缓存播放：用 wbi 签名请求 DASH playurl，从返回的 dash.video / dash.audio
 * 中挑登录账号授权内、配置上限（{@link dev.mediafix.config.StreamConfig#maxQn}）内
 * 最高清晰度（4K/8K/HDR/杜比），音视频两条链直接交给自研引擎边下边播
 * （BiliBiliMediaFiles），再以 9095 本地服务器 URL 交给播放器
 * （音频轨由 BasePlayerDiagMixin 的 input-slave 音频桥挂给 VLC）。
 *
 * <p>与前置 durl 链路(qn=116&platform=html5，封顶约1080p)不同，本链路：
 * <ul>
 *   <li>不做 {@code platform=html5}（否则封 1080p）；</li>
 *   <li>请求带 {@code fnval}=DASH 档位，从而能得到 DASH 音视频流；</li>
 *   <li>做 wbi 参数签名（B站 playurl 现在要求），并带登录 cookie 换取高清晰度。</li>
 * </ul>
 * 不落盘：整条「先下载到本地缓存再播」的路径已移除。
 * 调用失败返回 {@code null}，由上层回退到前置原生逻辑，保证始终可播。
 */
public final class DashResolver {

    /** B站 wbi 重排表（固定算法）。 */
    private static final int[] MIXIN_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52};
    private static final String CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final HttpClient client = HttpClient.newHttpClient();

    /** 缓存的 wbi 密钥。 */
    private static String mixinKey;

    private DashResolver() {
    }

    /**
     * 为给定 B站链接解析 DASH 高清流。支持普通视频(BV号)与番剧/大会员内容
     * (bangumi/play/epXXX)。
     * @return 成功返回 Result(本地缓存视频 + audioUrl=本地缓存音频)；失败/无权限返回 null。
     */
    /** 最近一次解析的原始页面 URI（直链过期后据此重新解析）。 */
    private static volatile URI lastResolvedUri;

    /** 最近一次选中的音频轨摘要，供 /mediafix audio 展示（编码/声道/码率）。 */
    private static volatile String lastAudioSummary = "尚未解析";

    public static String lastAudioSummary() {
        return lastAudioSummary;
    }

    /** 重新解析时是否保持当前档位（true = 只换 URL，不重置 ABR 档位）。 */
    private static volatile boolean refreshingSession;

    public static AbstractPatch.Result resolve(URI uri) {
        lastResolvedUri = uri;
        try {
            URI longUri = BiliUrls.expandShortLink(uri);
            // 番剧/大会员内容：ep 链接走 pgc 接口
            Long epId = parseEpId(longUri.toString());
            if (epId != null) {
                return resolveBangumi(epId);
            }
            String bvid = BiliUrls.bvid(longUri.toString());
            if (bvid == null) {
                return null;
            }
            int page = BiliUrls.page(longUri.toString());
            Long cid = fetchCid(bvid, page);
            if (cid == null || cid == 0L) {
                return null;
            }

            JsonObject data = requestDash(bvid, cid);
            if (data == null) {
                return null;
            }
            // 注意：dolby / flac 在 dash 里面（data.dash.dolby），不是 data 的直接子节点
            return finishResolve(data, data.getAsJsonObject("dash"));
        } catch (Throwable t) {
            MediaFix.LOGGER.warn("[mediafix] DASH 解析失败，回退 durl", t);
            Notice.message("DASH 解析异常，回退原生播放: " + t.getMessage());
            return null;
        }
    }

    /**
     * 自己调 {@code x/web-interface/view} 拿 cid（支持分P）。
     * 这是原先由 bilibili_media 的 {@code BilibiliPatch#getCid} 提供的最后一块能力。
     */
    public static Long fetchCid(String bvid, int page) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(
                            "https://api.bilibili.com/x/web-interface/view?bvid=" + bvid))
                    .header("User-Agent", BiliUrls.userAgent())
                    .header("Referer", "https://www.bilibili.com/")
                    .header("Accept", "application/json")
                    .timeout(java.time.Duration.ofSeconds(15));
            BiliAuth.withCookie(builder);
            HttpResponse<String> resp = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (root.get("code").getAsInt() != 0) {
                MediaFix.LOGGER.warn("[mediafix] view 接口返回 code={}", root.get("code").getAsInt());
                return null;
            }
            JsonObject data = root.getAsJsonObject("data");
            JsonArray pages = data.getAsJsonArray("pages");
            Long cid;
            if (pages != null && pages.size() > 1) {
                int idx = Math.max(0, Math.min(page - 1, pages.size() - 1));
                cid = pages.get(idx).getAsJsonObject().get("cid").getAsLong();
            } else {
                cid = data.get("cid").getAsLong();
            }
            MediaFix.LOGGER.info("[mediafix] 解析 {} 第{}P -> cid={}", bvid, page, cid);
            return cid;
        } catch (Throwable t) {
            MediaFix.LOGGER.warn("[mediafix] 获取 cid 失败: {}", bvid, t);
            return null;
        }
    }

    /** 从番剧/ep 链接提取 ep 号；非番剧链接返回 null。 */
    public static Long parseEpId(String url) {
        int i = url.indexOf("bangumi/play/ep");
        if (i < 0) {
            return null;
        }
        int start = i + "bangumi/play/ep".length();
        int end = start;
        while (end < url.length() && Character.isDigit(url.charAt(end))) {
            end++;
        }
        if (end == start) {
            return null;
        }
        try {
            return Long.parseLong(url.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 番剧/大会员内容解析：pgc/player/web/playurl 接口直接吃 ep_id 并返回 DASH
     * （结构与普通视频一致：dash.video / dash.audio / dolby），带登录 Cookie 换取
     * 会员清晰度与杜比。仅返回试看(未登录/无权限)或 DRM 内容时回退原生逻辑。
     */
    private static AbstractPatch.Result resolveBangumi(long epId) throws Exception {
        JsonObject result = null;
        try {
            result = requestPgc(epId);
        } catch (Exception e) {
            MediaFix.LOGGER.warn("[mediafix] 番剧 playurl 请求失败 ep={}", epId, e);
        }
        if (result == null) {
            return null; // 已在 requestPgc 里给玩家提示
        }
        if (intOf(result, "is_drm") == 1) {
            Notice.message("该番剧为 DRM 内容，无法流式播放，回退原生");
            return null;
        }
        JsonObject dash = result.has("dash") && result.get("dash").isJsonObject()
                ? result.getAsJsonObject("dash") : null;
        if (dash == null) {
            Notice.message("番剧未返回DASH流(试看/无权限)，回退原生播放");
            MediaFix.LOGGER.warn("[mediafix] 番剧无DASH流 ep={} quality={} preview={}",
                    epId, intOf(result, "quality"), intOf(result, "is_preview"));
            return null;
        }
        MediaFix.LOGGER.info("[mediafix] 番剧DASH解析: ep={} quality={} preview={}",
                epId, intOf(result, "quality"), intOf(result, "is_preview"));
        return finishResolve(result, dash);
    }

    /** 请求番剧 playurl（pgc 接口，无需 wbi 签名），返回 result JSON；试看态返回 null。 */
    private static JsonObject requestPgc(long epId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("https://api.bilibili.com/pgc/player/web/playurl"
                        + "?ep_id=" + epId + "&fnval=4048&fourk=1&platform=pc&qn=0"))
                .header("User-Agent", CHROME_UA)
                .header("Referer", "https://www.bilibili.com/")
                .header("Origin", "https://www.bilibili.com")
                .header("Accept", "application/json");
        BiliAuth.withCookie(builder);
        HttpResponse<String> resp = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (root.get("code").getAsInt() == 0 && root.has("result")) {
            JsonObject result = root.getAsJsonObject("result");
            if (intOf(result, "is_preview") == 1) {
                Notice.message("番剧仅可试看(登录态/大会员校验未过)，回退原生播放");
                MediaFix.LOGGER.warn("[mediafix] 番剧返回试看流 ep={} has_paid={}",
                        epId, intOf(result, "has_paid"));
                return null;
            }
            return result;
        }
        MediaFix.LOGGER.warn("[mediafix] 番剧 playurl 返回异常 code={}: {}",
                root.has("code") ? root.get("code").getAsInt() : -1,
                resp.body().length() > 300 ? resp.body().substring(0, 300) : resp.body());
        return null;
    }

    /**
     * 选流 + 构造 Result 的公共流程（普通视频与番剧共用）。
     *
     * <p><b>只做流式直连、绝不落盘</b>：B 站的 DASH 没有 MPD 清单，就是两条独立 fMP4 直链。
     * 以前这里还有一条"先下载到本地缓存再播"的路径，已经整条移除 ——
     * 把 B 站视频下载到本地属于灰色地带，本模组只做边下边播的流式播放。
     *
     * @param dashObj DASH 对象（dash.video / dash.audio）
     * @param data  playurl 的 data（pgc 接口传 result）
     * @param dashObj DASH 对象（dash.video / dash.audio / dash.dolby / dash.flac）
     */
    private static AbstractPatch.Result finishResolve(JsonObject data, JsonObject dashObj) throws Exception {
        JsonElement videoEl = pickBest(dashObj == null ? null : dashObj.getAsJsonArray("video"));
        AudioPick pick = pickAudioTrack(data, dashObj);
        JsonObject audio = pick == null ? null : pick.obj();
        boolean audioFromDolby = pick != null && pick.kind().equals("dolby");
        if (videoEl == null || videoEl.isJsonNull() || !videoEl.isJsonObject()) {
            Notice.message("DASH 无可用视频流，回退原生播放");
            return null;
        }
        JsonObject video = videoEl.getAsJsonObject();
        if (audio == null) {
            audio = new JsonObject(); // 无音轨时保持为空对象，避免下方判空
        }

        String videoCodec = str(video, "codecs");
        int qualityId = intOf(video, "id");
        MediaFix.LOGGER.info("[mediafix] 选中视频流: qn={}({}) {}x{} {}kbps codec={}",
                qualityId, describeQn(qualityId), intOf(video, "width"), intOf(video, "height"),
                longOf(video, "bandwidth") / 1000, videoCodec);
        String videoUrl = firstUrl(video);
        if (videoUrl == null) {
            Notice.message("DASH 视频直链缺失，回退原生播放");
            return null;
        }
        String audioCodec = str(audio, "codecs");
        String audioUrl = firstUrl(audio);
        // 音频【永远取最高音质】：优先杜比全景声块，否则 dash.audio 里码率最高的一条。
        // 它不属于 ABR —— 清晰度自适应只作用于视频链，音频不参与换挡。
        if (audioUrl != null) {
            MediaFix.LOGGER.info("[mediafix] 音频链: {} {}kbps (id={}{})",
                    pick == null ? "普通音轨" : pick.label(),
                    audioBandwidth(audio) / 1000, intOf(audio, "id"),
                    audioCodec.isEmpty() ? "" : ", " + audioCodec);
            lastAudioSummary = (pick == null ? "普通音轨" : pick.label()) + " "
                    + intOf(audio, "id") + " " + (audioCodec.isEmpty() ? "?" : audioCodec)
                    + " " + (audioBandwidth(audio) / 1000) + "kbps";
        }
        if (audioUrl == null && dashObj != null) {
            // 诊断：打出 dash 结构与首条音轨字段名，定位接口字段差异
            JsonArray audioArr = dashObj.getAsJsonArray("audio");
            String firstAudioKeys = "-";
            if (audioArr != null && audioArr.size() > 0 && audioArr.get(0).isJsonObject()) {
                firstAudioKeys = String.valueOf(audioArr.get(0).getAsJsonObject().keySet());
            }
            MediaFix.LOGGER.warn("[mediafix] 未找到独立音频轨: dash.audio={}条 首条音轨键={} dash键={}",
                    audioArr == null ? "缺失" : String.valueOf(audioArr.size()),
                    firstAudioKeys, dashObj.keySet());
        }

        // ---- 流式直连（不下载）----
        // 视频交给 FFmpeg 解、音频交给自研引擎，两边各自边下边播。
        // 要求：开关打开 + FFmpeg 已启用且原生库可用 + 音频链存在。
        if (StreamConfig.dashStreaming && audioUrl != null && FfmpegRuntime.available()) {
            DashHandoff.set(videoUrl, audioUrl);
            MediaFix.LOGGER.info("[mediafix] DASH 流式直连: 音视频均交自研引擎 (qn={}, {})",
                    qualityId, describeQn(qualityId));
            if (!refreshingSession) Notice.message("DASH 流式直连(不下载): " + describeQn(qualityId));
            // 主 URI 给音频链（VLC 侧只当参考）；video 链由 DashHandoff 的全局表按主 URI 反查。
            // result.audioUrl 也填上，这样 watermedia 缓存命中时仍然能拿到两条链。
            AbstractPatch.Result result = new AbstractPatch.Result(URI.create(audioUrl), true, false);
            result.audioUrl = URI.create(audioUrl);
            return result;
        }

        /*
         * 走到这里说明流式直连不可用：要么 /mediafix-stream streaming 关着，
         * 要么 FFmpeg 引擎/原生库不可用，要么这条视频没有独立音频链。
         *
         * 以前这里会退到"先下载到本地缓存再播"，那条路径已整条移除 ——
         * 把 B 站视频下载到本地属于灰色地带，本模组只做流式播放。
         * 所以这里直接交回给前置模组，按它自己的方式处理。
         */
        MediaFix.LOGGER.warn("[mediafix] DASH 流式直连不可用（开关={} 原生库={} 有音频链={}），交回前置模组",
                StreamConfig.dashStreaming, FfmpegRuntime.available(), audioUrl != null);
        return null;
    }

    /** 请求 DASH playurl 并返回其中的 data JSON；签名被风控(-412/v_voucher)时刷新 wbi key 重试一次。 */
    private static JsonObject requestDash(String bvid, long cid) throws Exception {
        JsonObject data = tryRequestDash(bvid, cid);
        if (data == null) {
            // wbi key 每日更替，跨天缓存的 key 会失效：刷新后重试一次
            MediaFix.LOGGER.info("[mediafix] playurl 请求失败，刷新 wbi key 后重试");
            mixinKey = null;
            data = tryRequestDash(bvid, cid);
        }
        return data;
    }

    private static JsonObject tryRequestDash(String bvid, long cid) throws Exception {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("bvid", bvid);
        params.put("cid", String.valueOf(cid));
        params.put("qn", "0");
        params.put("fnver", "0");
        params.put("fnval", "4048"); // DASH + 4K/HDR/Dolby 档位
        params.put("fourk", "1");
        params.put("platform", "pc");

        signWbi(params);
        // wbi 密钥只参与签名，最终请求用排好序且 URL 编码后的参数
        StringBuilder sb = new StringBuilder("https://api.bilibili.com/x/player/wbi/playurl?");
        for (var e : params.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('&');
        }
        sb.setLength(sb.length() - 1);
        String url = sb.toString();

        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url))
                .header("User-Agent", CHROME_UA)
                .header("Referer", "https://www.bilibili.com/")
                .header("Origin", "https://www.bilibili.com")
                .header("Accept", "application/json");
        BiliAuth.withCookie(builder);
        HttpResponse<String> resp = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (root.get("code").getAsInt() == 0 && root.has("data")) {
            return root.getAsJsonObject("data");
        }
        // -412 / v_voucher = wbi 签名被风控拒绝（key 过期等）
        MediaFix.LOGGER.warn("[mediafix] playurl 返回异常 code={}: {}", root.get("code").getAsInt(),
                resp.body().length() > 500 ? resp.body().substring(0, 500) : resp.body());
        return null;
    }

    /** CDN 直链过期后的重解析：重新请求 playurl，按与首次相同的规则挑视频/音频流 URL。 */
    private static String refreshUrl(String bvid, long cid, boolean audio) {
        try {
            JsonObject data = requestDash(bvid, cid);
            if (data == null) {
                return null;
            }
            JsonObject dashObj = data.getAsJsonObject("dash");
            if (dashObj == null) {
                return null;
            }
            JsonElement el;
            if (audio) {
                // 与首次选取严格同一套规则（否则直链过期换链后音频会悄悄降级回 AAC）
                AudioPick pick = pickAudioTrack(data, dashObj);
                el = pick == null ? null : pick.obj();
            } else {
                el = pickBest(dashObj.getAsJsonArray("video"));
            }
            if (el == null || !el.isJsonObject()) {
                return null;
            }
            return firstUrl(el.getAsJsonObject());
        } catch (Exception e) {
            MediaFix.LOGGER.warn("[mediafix] 重解析直链失败 bvid={} cid={}", bvid, cid, e);
            return null;
        }
    }

    /** wbi 签名：加 wts，按 key 排序后对(编码拼接+mixinKey)取 MD5，追加 w_rid。 */
    private static String signWbi(TreeMap<String, String> params) throws Exception {
        long wts = System.currentTimeMillis() / 1000;
        params.put("wts", String.valueOf(wts));
        String key = getMixinKey();

        StringBuilder raw = new StringBuilder();
        for (var e : params.entrySet()) {
            raw.append(e.getKey()).append('=').append(encode(e.getValue())).append('&');
        }
        raw.setLength(raw.length() - 1);

        String toHash = raw + key;
        String wrid = md5Hex(toHash);
        // 把已编码的值回填（供最终 URL 使用），并追加 w_rid
        // params 里当前已是原始值，这里统一改成编码值以组成最终 URL
        for (var e : params.entrySet()) {
            e.setValue(encode(e.getValue()));
        }
        params.put("w_rid", wrid);
        return wrid;
    }

    /**
     * 从直链里取出 CDN 给的有效期（毫秒时间戳，0 = 未知）。
     * B 站直链的查询串里带 {@code deadline=<秒>}，过期后请求会被拒。
     */
    public static long urlDeadlineMs(String url) {
        if (url == null) return 0L;
        int i = url.indexOf("deadline=");
        if (i < 0) return 0L;
        int end = i + 9;
        while (end < url.length() && Character.isDigit(url.charAt(end))) end++;
        try {
            long sec = Long.parseLong(url.substring(i + 9, end));
            return sec > 0 ? sec * 1000L : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 超长会话续期：重新解析当前媒体（保持当前清晰度档位），拿到新鲜的两条直链。
     *
     * <p>为什么需要：B 站直链带 {@code deadline}，通常两小时左右失效；失效后拉流会 403，
     * 表现为画面/声音突然停住。这里在到期前主动换链（无缝换链见 MediaEngine.reloadVideoSource），
     * 播放不会被察觉地中断。
     *
     * @return {@code {videoUrl, audioUrl}}；失败返回 null
     */
    public static String[] reResolveUrls() {
        URI orig = lastResolvedUri;
        if (orig == null) return null;
        try {
            refreshingSession = true;
            AbstractPatch.Result result = resolve(orig);
            String video = DashHandoff.lastVideoUrl();
            String audio = result != null && result.audioUrl != null ? result.audioUrl.toString() : null;
            if (video == null || audio == null) {
                MediaFix.LOGGER.warn("[mediafix] 直链续期：重新解析没拿到两条链（video={} audio={}）",
                        video != null, audio != null);
                return null;
            }
            MediaFix.LOGGER.info("[mediafix] 直链续期：已拿到新鲜的两条链");
            return new String[]{video, audio};
        } catch (Throwable t) {
            MediaFix.LOGGER.warn("[mediafix] 直链续期失败", t);
            return null;
        } finally {
            refreshingSession = false;
        }
    }

    /** B站 wbi 参数编码：URLEncoder 后转 %20，B站按此计算与实际发送一致。 */
    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%7E", "~");
    }

    private static String getMixinKey() throws Exception {
        if (mixinKey != null) {
            return mixinKey;
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("https://api.bilibili.com/x/web-interface/nav"))
                .header("User-Agent", CHROME_UA)
                .header("Referer", "https://www.bilibili.com/");
        BiliAuth.withCookie(builder);
        HttpResponse<String> resp = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        // nav 未登录也能返回 wbi_img
        JsonObject wbi = root.getAsJsonObject("data").getAsJsonObject("wbi_img");
        String imgKey = baseName(wbi.get("img_url").getAsString());
        String subKey = baseName(wbi.get("sub_url").getAsString());
        mixinKey = mixinKey(imgKey, subKey);
        MediaFix.LOGGER.debug("[mediafix] wbi mixinKey 已缓存");
        return mixinKey;
    }

    /** 从 URL 取文件名去扩展名。 */
    private static String baseName(String url) {
        int slash = url.lastIndexOf('/');
        String file = slash >= 0 ? url.substring(slash + 1) : url;
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    /** 按 B站公开的重排表生成 32 位 mixinKey。 */
    private static String mixinKey(String imgKey, String subKey) {
        String s = imgKey + subKey;
        StringBuilder out = new StringBuilder();
        for (int i : MIXIN_TAB) {
            out.append(s.charAt(i));
        }
        return out.substring(0, 32);
    }

    private static String md5Hex(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * 从 dash.video 里挑一条流。
     *
     * <p>{@code StreamConfig.maxQn == -1}（即 /mediafix-stream quality auto）时走 <b>ABR</b>：
     * 把数组里所有清晰度收成阶梯存进 {@link AbrLadder}，并按"保守起步"的原则选初始档
     * （有历史测速就按 带宽×0.7 挑，没有就从中档起步）—— 之后由 {@link AbrController}
     * 依据实测带宽与缓冲水位升降档。
     * 其余情况仍按固定上限挑最高档（手动指定清晰度时不去猜）。
     */
    private static JsonElement pickBest(JsonArray videos) {
        if (videos == null || videos.size() == 0) {
            return null;
        }
        if (StreamConfig.maxQn < 0) {
            return pickAuto(videos);
        }
        int cap = StreamConfig.maxQn > 0 ? StreamConfig.maxQn : 127; // 0 = 不限
        JsonElement best = null;
        int bestId = -1;
        int bestRank = Integer.MAX_VALUE;
        for (JsonElement e : videos) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            if (isDolbyVision(o)) continue; // 不要杜比视界视频数据
            int id = intOf(o, "id");
            if (id > cap) continue; // 超过用户设定的清晰度上限
            int rank = codecRank(o);
            // 清晰度优先；同一清晰度内按编码优先级挑（默认避开 AV1 —— 4K AV1 只能软解，跑不动）
            if (id > bestId || (id == bestId && rank < bestRank)) {
                bestId = id;
                bestRank = rank;
                best = e;
            }
        }
        return best;
    }

    /** ABR：收集阶梯 + 保守选初始档。 */
    private static JsonElement pickAuto(JsonArray videos) {
        /*
         * 同一个清晰度会有多条编码（H.264 / HEVC / AV1），必须先去重再建阶梯：
         * 曾经把 18 条全当独立档位放进阶梯（日志里出现 qn16/qn16/qn32/qn32…），
         * 结果"档位"名不副实、换档频繁，而且某个 4K 档恰好挑中 AV1 —— 显卡没 AV1 硬解，
         * 4K AV1 只能软解，画面掉到 3~4fps。这里按清晰度归并，同一清晰度内按编码优先级取一条。
         */
        java.util.List<AbrLadder.Candidate> list = new java.util.ArrayList<>();
        java.util.Map<Integer, JsonObject> byQn = new java.util.LinkedHashMap<>();
        java.util.Map<Integer, Integer> rankByQn = new java.util.HashMap<>();
        for (JsonElement e : videos) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            if (isDolbyVision(o)) continue;
            String url = firstUrl(o);
            if (url == null) continue;
            int id = intOf(o, "id");
            long bw = longOf(o, "bandwidth");
            if (bw <= 0) continue;
            Integer prevRank = rankByQn.get(id);
            int rank = codecRank(o);
            if (prevRank != null && prevRank <= rank) continue;   // 已有更优编码
            rankByQn.put(id, rank);
            byQn.put(id, o);
        }
        for (var en : byQn.entrySet()) {
            JsonObject o = en.getValue();
            String url = firstUrl(o);
            int w = intOf(o, "width");
            int h = intOf(o, "height");
            list.add(new AbrLadder.Candidate(en.getKey(), longOf(o, "bandwidth"), url,
                    describeQn(en.getKey()) + "(" + codecShort(o) + ")", w, h));
        }
        if (list.isEmpty()) return null;
        list.sort(java.util.Comparator.comparingLong(AbrLadder.Candidate::bandwidthBps));

        // 起步档：续期刷新时保持当前档位（只换 URL，不动 ABR 状态）；
        // 首次解析则保守起步 —— 有历史测速按 0.7 挑，否则从中间档开始靠 ABR 爬。
        long est = AbrController.estimateBps();
        int start = -1;
        AbrLadder.Candidate keep = refreshingSession ? AbrLadder.current() : null;
        if (keep != null) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).qn() == keep.qn()) {
                    start = i;
                    break;
                }
            }
        }
        if (start < 0) {
            start = est > 0 ? AbrLadder.pickForBandwidth(est, 0.7, 0, list.size() - 1)
                    : list.size() / 2;
        }
        AbrLadder.Candidate chosen = list.get(start);
        AbrLadder.set(list, start);
        MediaFix.LOGGER.info("[mediafix] ABR 模式：{} 档可选 {}，起步 {}（{}kbps {}）{}",
                list.size(),
                list.stream().map(AbrLadder.Candidate::label).collect(java.util.stream.Collectors.joining("/")),
                chosen.label(), chosen.bandwidthBps() / 1000, chosen.resolution(),
                est > 0 ? "，历史带宽估计 " + (est / 1000) + "kbps" : "，暂无带宽估计");
        // 顺手点出"标称 4K 但分辨率不到 2160p"的假 4K，免得又靠肉眼看
        for (AbrLadder.Candidate c : list) {
            if (c.qn() >= 120 && c.height() > 0 && c.height() < 2000) {
                MediaFix.LOGGER.warn("[mediafix] 注意：这条流标称 {} 但真实分辨率只有 {}（假 4K）",
                        c.label(), c.resolution());
            }
        }
        return byQn.get(chosen.qn());
    }

    // 本地缓存/下载相关的方法（isCached / downloadWithRetry / download）已整条移除：
    // 本模组只做流式播放，不把 B 站视频落到本地。

    /**
     * 编码优先级（越小越优先）：H.264/其它 1 < HEVC 2 < AV1 3。
     * AV1 排在最后是因为多数显卡无法硬解它，4K 软解根本跑不动；
     * {@link StreamConfig#avoidAv1} 打开时 AV1 直接排除。
     */
    private static int codecRank(JsonObject o) {
        boolean av1 = isAv1(o);
        if (av1) return StreamConfig.avoidAv1 ? Integer.MAX_VALUE : 3;
        int cid = intOf(o, "codecid");
        String codec = str(o, "codecs").toLowerCase();
        if (cid == 12 || codec.startsWith("hev") || codec.startsWith("hvc")) return 2;
        return 1;
    }

    private static boolean isAv1(JsonObject o) {
        if (intOf(o, "codecid") == 13) return true;
        return str(o, "codecs").toLowerCase().startsWith("av01");
    }

    private static String codecShort(JsonObject o) {
        int cid = intOf(o, "codecid");
        if (cid == 13 || isAv1(o)) return "av1";
        if (cid == 12) return "hevc";
        if (cid == 7) return "h264";
        String c = str(o, "codecs");
        if (c.isEmpty()) return "?";
        int dot = c.indexOf('.');
        return dot > 0 ? c.substring(0, dot) : c;
    }

    /** 判断是否为杜比视界视频流（id=126 或 codec 以 dvh 开头）。 */
    private static boolean isDolbyVision(JsonObject o) {
        if (intOf(o, "id") == 126) {
            return true;
        }
        String codec = str(o, "codecs").toLowerCase();
        return codec.startsWith("dvh") || codec.contains("dolby_v");
    }

    /** 选中的音频轨：对象 + 来源类别。 */
    private record AudioPick(JsonObject obj, String kind, String label) {
    }

    private static String kindLabel(String kind) {
        return switch (kind) {
            case "dolby" -> "杜比全景声";
            case "flac" -> "Hi-Res 无损";
            default -> "普通音轨";
        };
    }

    /**
     * 取 {@code dolby} / {@code flac} 这两个专用块里的音轨数组。
     *
     * <p><b>层级是关键</b>：B 站 playurl 把这两个块放在 {@code data.dash} 里面
     * （即 {@code data.dash.dolby.audio}），{@code data} 的直接子节点里<b>没有</b> dolby。
     * 早先按 {@code data.dolby} 取永远拿到 null，于是杜比全景声一直选不中、悄悄回退到
     * 192K 的 AAC —— 这就是"没有杜比全景声"的真正原因。pgc(番剧) 两种层级都出现过，所以两层都查。
     */
    private static JsonArray specialAudio(JsonObject data, JsonObject dash, String key) {
        for (JsonObject o : new JsonObject[]{dash, data}) {
            if (o == null || !o.has(key) || !o.get(key).isJsonObject()) {
                continue;
            }
            JsonObject block = o.getAsJsonObject(key);
            if (block.has("audio") && block.get("audio").isJsonArray()
                    && block.getAsJsonArray("audio").size() > 0) {
                return block.getAsJsonArray("audio");
            }
        }
        return null;
    }

    /** 专用块的存在状态（诊断用）：无 / 有音频N条 / 有但audio为空 / 有但audio=null。 */
    private static String specialState(JsonObject data, JsonObject dash, String key) {
        for (JsonObject o : new JsonObject[]{dash, data}) {
            if (o == null || !o.has(key) || !o.get(key).isJsonObject()) {
                continue;
            }
            JsonObject block = o.getAsJsonObject(key);
            if (!block.has("audio")) {
                return "有(无audio字段)";
            }
            JsonElement a = block.get("audio");
            if (a.isJsonArray()) {
                return "有(" + a.getAsJsonArray().size() + "条)";
            }
            if (a.isJsonNull()) {
                return "有(audio=null，账号/视频无此档)";
            }
            return "有(audio非数组)";
        }
        return "无";
    }

    /**
     * 按 {@link StreamConfig#audioPreference} 选音频轨。
     * 默认顺序：杜比全景声 → Hi-Res 无损 → 普通音轨(码率最高)。
     * 任何一档缺失都自动回退，保证始终有声音。
     */
    private static AudioPick pickAudioTrack(JsonObject data, JsonObject dash) {
        JsonArray dolby = specialAudio(data, dash, "dolby");
        JsonArray flac = specialAudio(data, dash, "flac");
        JsonArray normal = dash == null ? null : dash.getAsJsonArray("audio");

        String pref = StreamConfig.audioPreference == null ? "dolby"
                : StreamConfig.audioPreference.trim().toLowerCase(java.util.Locale.ROOT);
        String[] order = switch (pref) {
            case "best" -> new String[]{"normal", "dolby", "flac"};
            case "hires" -> new String[]{"flac", "dolby", "normal"};
            default -> new String[]{"dolby", "flac", "normal"};
        };

        AudioPick pick = null;
        for (String kind : order) {
            JsonArray arr = switch (kind) {
                case "dolby" -> dolby;
                case "flac" -> flac;
                default -> normal;
            };
            JsonElement e = pickBestAudio(arr);
            if (e != null && e.isJsonObject()) {
                pick = new AudioPick(e.getAsJsonObject(), kind, kindLabel(kind));
                break;
            }
        }
        MediaFix.LOGGER.info("[mediafix] 音频块: 杜比={} Hi-Res={} 普通={}条 | 偏好={} → {}",
                specialState(data, dash, "dolby"), specialState(data, dash, "flac"),
                normal == null ? 0 : normal.size(), pref,
                pick == null ? "无可用音轨" : pick.label());
        return pick;
    }

    /** 从 dash.audio 里挑 bandWidth 最高的那条。 */
    private static JsonElement pickBestAudio(JsonArray audios) {
        if (audios == null || audios.size() == 0) {
            return null;
        }
        JsonElement best = null;
        int bestBw = -1;
        for (JsonElement e : audios) {
            if (!e.isJsonObject()) continue;
            // B站 DASH JSON 码率字段是小写 "bandwidth"，兼容两种写法
            JsonObject o = e.getAsJsonObject();
            int bw = intOf(o, "bandwidth");
            if (bw == 0) {
                bw = intOf(o, "bandWidth");
            }
            if (bw > bestBw) {
                bestBw = bw;
                best = e;
            }
        }
        return best;
    }

    /** 取流地址：兼容 baseUrl/base_url(字符串) 与 backupUrl/backup_url(数组) 四种命名
     *  （普通视频接口用驼峰，番剧 pgc 接口两种并存）。 */
    private static String firstUrl(JsonObject o) {
        for (String key : new String[]{"baseUrl", "base_url"}) {
            if (o.has(key) && o.get(key).isJsonPrimitive()) {
                return o.get(key).getAsString();
            }
        }
        for (String key : new String[]{"backupUrl", "backup_url"}) {
            if (o.has(key) && o.get(key).isJsonArray()) {
                JsonArray arr = o.getAsJsonArray(key);
                if (arr.size() > 0 && arr.get(0).isJsonPrimitive()) {
                    return arr.get(0).getAsString();
                }
            }
        }
        return null;
    }

    /** 清晰度 id -> 人类可读。 */
    private static String describeQn(int id) {
        return switch (id) {
            case 127 -> "8K";
            case 126 -> "杜比视界";
            case 125 -> "HDR";
            case 120 -> "4K";
            case 112 -> "1080P60";
            case 116 -> "1080P60+";
            case 80 -> "1080P";
            case 74 -> "720P60";
            case 64 -> "720P";
            default -> "qn" + id;
        };
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static long longOf(JsonObject o, String key) {
        try {
            return o.has(key) ? o.get(key).getAsLong() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static int intOf(JsonObject o, String key) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 音频码率：B站字段为小写 bandwidth，兼容 bandWidth。 */
    private static int audioBandwidth(JsonObject o) {
        int bw = intOf(o, "bandwidth");
        return bw != 0 ? bw : intOf(o, "bandWidth");
    }
}