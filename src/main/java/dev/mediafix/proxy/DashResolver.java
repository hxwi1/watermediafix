package dev.mediafix.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mediafix.MediaFix;
import dev.mediafix.config.StreamConfig;
import dev.mediafix.ui.DownloadProgress;
import dev.polaris_light.bilibili_media.auth.BiliCookieStore;
import dev.polaris_light.bilibili_media.util.BilibiliMediaUtil;
import dev.polaris_light.bilibili_media.util.BilibiliPatch;
import dev.polaris_light.bilibili_media.util.BilibiliShortLinkMediaPlayResolver;
import org.watermedia.api.network.patchs.AbstractPatch;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.TreeMap;

/**
 * 高清(DASH)缓存播放：用 wbi 签名请求 DASH playurl，从返回的 dash.video / dash.audio
 * 中挑登录账号授权内、配置上限（{@link dev.mediafix.config.StreamConfig#maxQn}）内
 * 最高清晰度（4K/8K/HDR/杜比），视频与音频两条流先下载到前置 mod 的缓存目录
 * （BiliBiliMediaFiles），再以 9095 本地服务器 URL 交给播放器
 * （音频轨由 BasePlayerDiagMixin 的 input-slave 音频桥挂给 VLC）。
 *
 * <p>与前置 durl 链路(qn=116&platform=html5，封顶约1080p)不同，本链路：
 * <ul>
 *   <li>不做 {@code platform=html5}（否则封 1080p）；</li>
 *   <li>请求带 {@code fnval}=DASH 档位，从而能得到 DASH 音视频流；</li>
 *   <li>做 wbi 参数签名（B站 playurl 现在要求），并带登录 cookie 换取高清晰度。</li>
 * </ul>
 * 下载进度实时显示在 actionbar；已缓存文件命中后直接播放。
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
    public static AbstractPatch.Result resolve(BilibiliPatch self, URI uri) {
        try {
            URI longUri = uri;
            if (uri.toString().contains("b23.tv")) {
                longUri = BilibiliShortLinkMediaPlayResolver.expand(uri);
            }
            // 番剧/大会员内容：ep 链接走 pgc 接口
            Long epId = parseEpId(longUri.toString());
            if (epId != null) {
                return resolveBangumi(epId);
            }
            String bvid = BilibiliPatch.parseBvid(longUri.toString());
            if (bvid == null) {
                return null;
            }
            int page = BilibiliPatch.parsePage(longUri.toString());
            Long cid = self.getCid(bvid, page);
            if (cid == null || cid == 0L) {
                return null;
            }

            JsonObject data = requestDash(bvid, cid);
            if (data == null) {
                return null;
            }
            JsonObject dolby = data.has("dolby") && data.get("dolby").isJsonObject()
                    ? data.getAsJsonObject("dolby") : null;
            return finishResolve(data.getAsJsonObject("dash"), dolby,
                    "mediafix_" + bvid + "_" + cid);
        } catch (Throwable t) {
            MediaFix.LOGGER.warn("[mediafix] DASH 解析失败，回退 durl", t);
            DownloadProgress.message("DASH 解析异常，回退原生播放: " + t.getMessage());
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
            DownloadProgress.message("该番剧为DRM内容，无法本地缓存播放，回退原生");
            return null;
        }
        JsonObject dash = result.has("dash") && result.get("dash").isJsonObject()
                ? result.getAsJsonObject("dash") : null;
        if (dash == null) {
            DownloadProgress.message("番剧未返回DASH流(试看/无权限)，回退原生播放");
            MediaFix.LOGGER.warn("[mediafix] 番剧无DASH流 ep={} quality={} preview={}",
                    epId, intOf(result, "quality"), intOf(result, "is_preview"));
            return null;
        }
        // 杜比块在 pgc 接口里位于 dash 内，兜底再查根层
        JsonObject dolby = dash.has("dolby") && dash.get("dolby").isJsonObject()
                ? dash.getAsJsonObject("dolby") : null;
        if (dolby == null && result.has("dolby") && result.get("dolby").isJsonObject()) {
            dolby = result.getAsJsonObject("dolby");
        }
        MediaFix.LOGGER.info("[mediafix] 番剧DASH解析: ep={} quality={} preview={}",
                epId, intOf(result, "quality"), intOf(result, "is_preview"));
        return finishResolve(dash, dolby, "mediafix_ep" + epId);
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
        BiliCookieStore.withCookie(builder);
        HttpResponse<String> resp = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (root.get("code").getAsInt() == 0 && root.has("result")) {
            JsonObject result = root.getAsJsonObject("result");
            if (intOf(result, "is_preview") == 1) {
                DownloadProgress.message("番剧仅可试看(登录态/大会员校验未过)，回退原生播放");
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
     * 选流 + 下载 + 构造 Result 的公共流程（普通视频与番剧共用）。
     * @param dash  DASH 对象（dash.video / dash.audio）
     * @param dolby 杜比全景声块（可为 null）
     * @param base  缓存文件名前缀（如 mediafix_BVxx_cid / mediafix_ep123）
     */
    private static AbstractPatch.Result finishResolve(JsonObject dashObj, JsonObject dolby, String base) throws Exception {
        JsonElement videoEl = pickBest(dashObj == null ? null : dashObj.getAsJsonArray("video"));
        // 音频：优先取杜比全景声(dolby 块)；没有则用 dash.audio 里最高码率。
        // 记录普通音轨候选：杜比流下载失败时降级用它，尽量保证有声音。
        JsonObject audio = null;
        boolean audioFromDolby = false;
        if (dolby != null && dolby.has("audio") && dolby.get("audio").isJsonArray()
                && dolby.getAsJsonArray("audio").size() > 0) {
            JsonElement de = pickBestAudio(dolby.getAsJsonArray("audio"));
            audio = (de != null && de.isJsonObject()) ? de.getAsJsonObject() : null;
            audioFromDolby = audio != null;
        }
        if (audio == null) {
            JsonElement ae = pickBestAudio(dashObj == null ? null : dashObj.getAsJsonArray("audio"));
            audio = (ae != null && ae.isJsonObject()) ? ae.getAsJsonObject() : null;
        }
        if (videoEl == null || videoEl.isJsonNull() || !videoEl.isJsonObject()) {
            DownloadProgress.message("DASH 无可用视频流，回退原生播放");
            return null;
        }
        JsonObject video = videoEl.getAsJsonObject();
        // 杜比音轨的降级候选：普通 dash.audio 里码率最高的一条
        String altAudioUrl = null;
        String altAudioCodec = "";
        if (audioFromDolby) {
            JsonElement ae = pickBestAudio(dashObj.getAsJsonArray("audio"));
            if (ae != null && ae.isJsonObject()) {
                altAudioUrl = firstUrl(ae.getAsJsonObject());
                altAudioCodec = str(ae.getAsJsonObject(), "codecs");
            }
        }
        if (audio == null) {
            audio = new JsonObject(); // 无音轨时保持为空对象，避免下方判空
        }

        String videoCodec = str(video, "codecs");
        int qualityId = intOf(video, "id");
        String videoUrl = firstUrl(video);
        if (videoUrl == null) {
            DownloadProgress.message("DASH 视频直链缺失，回退原生播放");
            return null;
        }
        String audioCodec = str(audio, "codecs");
        String audioUrl = firstUrl(audio);
        if (audioUrl == null && dashObj != null) {
            // 诊断：打出 dash 结构与首条音轨字段名，定位接口字段差异
            JsonArray audioArr = dashObj.getAsJsonArray("audio");
            String firstAudioKeys = "-";
            if (audioArr != null && audioArr.size() > 0 && audioArr.get(0).isJsonObject()) {
                firstAudioKeys = String.valueOf(audioArr.get(0).getAsJsonObject().keySet());
            }
            MediaFix.LOGGER.warn("[mediafix] 未找到独立音频轨: dash.audio={}条 首条音轨键={} dash键={} dolby={}",
                    audioArr == null ? "缺失" : String.valueOf(audioArr.size()),
                    firstAudioKeys, dashObj.keySet(), String.valueOf(dolby != null));
        }

        // 先下载后播：视频/音频独立下载、独立重试，下载到前置 mod 的缓存目录
        // （9095 本地服务器直接伺服）。进度与每次失败/重试都提示玩家，不让干等。
        Path dir = BilibiliMediaUtil.getDownloadPath();
        Path vFile = dir.resolve(base + "_v.mp4");
        Path aFile = dir.resolve(base + "_a.m4a");

        boolean fullCache = isCached(vFile) && (audioUrl == null || isCached(aFile));
        if (fullCache) {
            MediaFix.LOGGER.info("[mediafix] 命中本地缓存: {} (qn={}, {})",
                    base, qualityId, describeQn(qualityId));
            DownloadProgress.message("命中本地缓存，直接播放 (" + describeQn(qualityId) + ")");
        } else {
            long t0 = System.currentTimeMillis();
            DownloadProgress.message("开始缓存 " + describeQn(qualityId)
                    + (audioUrl == null ? "" : " + " + audioCodec) + " ...");

            // 视频流：失败重试一次
            boolean videoOk = downloadWithRetry("视频流 " + describeQn(qualityId), videoUrl, vFile);

            // 音频流：失败重试一次；杜比失败再降级普通音轨
            boolean audioOk = audioUrl == null || downloadWithRetry(
                    "音频流 " + (audioFromDolby ? "杜比" : audioCodec), audioUrl, aFile);
            if (!audioOk && audioUrl != null && altAudioUrl != null && !altAudioUrl.equals(audioUrl)) {
                DownloadProgress.message("杜比音频下载失败，降级普通音轨");
                audioOk = downloadWithRetry("音频流(普通) " + altAudioCodec, altAudioUrl, aFile);
            }

            // 视频彻底失败：尽量保声音——仅音频也能播
            if (!videoOk) {
                if (audioOk && audioUrl != null) {
                    DownloadProgress.message("视频流下载失败，本次仅播放音频");
                    MediaFix.LOGGER.warn("[mediafix] 视频流下载失败，仅播放音频: {}", base);
                    return new AbstractPatch.Result(
                            BilibiliMediaUtil.getUri(aFile.getFileName().toString()), true, false);
                }
                DownloadProgress.message("视频与音频均下载失败，回退原生播放");
                MediaFix.LOGGER.warn("[mediafix] DASH 全部下载失败，回退原生: {}", base);
                return null;
            }
            if (!audioOk) {
                DownloadProgress.message("音频流下载失败，本次无声播放");
            }

            long size = Files.size(vFile) + (audioOk && audioUrl != null ? Files.size(aFile) : 0);
            DownloadProgress.message("缓存完成 " + describeQn(qualityId) + " 共 "
                    + (size / 1048576) + "MB，用时 " + ((System.currentTimeMillis() - t0) / 1000) + "s"
                    + (!audioOk && audioUrl != null ? "（无音频轨）" : ""));
            MediaFix.LOGGER.info("[mediafix] DASH 缓存完成: {} (qn={}, {}MB, audio={})",
                    base, qualityId, size / 1048576, audioOk);
        }

        // Result 构造参数顺序为 (uri, assumeVideo, assumeStream)：第2参必须 true，
        // ImageFetch 见 assumeVideo=true 会抛 VideoTypeException 切换到视频管线。
        URI videoLocal = BilibiliMediaUtil.getUri(vFile.getFileName().toString());
        AbstractPatch.Result result = new AbstractPatch.Result(videoLocal, true, false);
        if (audioUrl != null && isCached(aFile)) {
            // DASH 音视频分离：音频轨经 input-slave 挂给 VLC（见 BasePlayerDiagMixin 音频桥）
            result.audioUrl = BilibiliMediaUtil.getUri(aFile.getFileName().toString());
            MediaFix.LOGGER.info("[mediafix] DASH: 视频 qn={}({} , {})  音频(c={},{}kbps)",
                    qualityId, describeQn(qualityId), videoCodec,
                    audioCodec, audioBandwidth(audio) / 1000);
        } else {
            MediaFix.LOGGER.info("[mediafix] DASH: 视频 qn={}({} , {})  无独立音频轨",
                    qualityId, describeQn(qualityId), videoCodec);
        }
        return result;
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
        BiliCookieStore.withCookie(builder);
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
                // 与首次选取一致：优先杜比全景声块，否则 dash.audio 最高码率
                JsonObject dolby = data.has("dolby") && data.get("dolby").isJsonObject()
                        ? data.getAsJsonObject("dolby") : null;
                el = null;
                if (dolby != null && dolby.has("audio") && dolby.get("audio").isJsonArray()
                        && dolby.getAsJsonArray("audio").size() > 0) {
                    el = pickBestAudio(dolby.getAsJsonArray("audio"));
                }
                if (el == null) {
                    el = pickBestAudio(dashObj.getAsJsonArray("audio"));
                }
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
        BiliCookieStore.withCookie(builder);
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

    /** 从 dash.video 里挑 id(清晰度) 最高且不超过配置上限的一条，排除杜比视界。 */
    private static JsonElement pickBest(JsonArray videos) {
        if (videos == null || videos.size() == 0) {
            return null;
        }
        int cap = StreamConfig.maxQn > 0 ? StreamConfig.maxQn : 127; // 0 = 不限
        JsonElement best = null;
        int bestId = -1;
        for (JsonElement e : videos) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            if (isDolbyVision(o)) continue; // 不要杜比视界视频数据
            int id = intOf(o, "id");
            if (id > cap) continue; // 超过用户设定的清晰度上限
            if (id > bestId) {
                bestId = id;
                best = e;
            }
        }
        return best;
    }

    /** 缓存命中判定：文件存在且大于 1KB（下载走 .part 临时文件+原子改名，存在即完整）。 */
    private static boolean isCached(Path file) {
        try {
            return Files.exists(file) && Files.size(file) > 1024;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 带重试的下载（共尝试 2 次，间隔 1.5s）：已缓存直接成功；
     * 每次失败/重试都向玩家提示原因，不让玩家干等。任何异常不外抛。
     */
    private static boolean downloadWithRetry(String label, String url, Path target) {
        if (isCached(target)) {
            return true;
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                download(label, url, target);
                return true;
            } catch (Exception e) {
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (attempt < 2) {
                    DownloadProgress.message(label + " 下载失败(" + reason + ")，1.5s 后重试 (1/1)");
                    MediaFix.LOGGER.warn("[mediafix] {} 下载失败，重试: {}", label, reason);
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                } else {
                    DownloadProgress.message(label + " 下载失败: " + reason);
                    MediaFix.LOGGER.warn("[mediafix] {} 下载彻底失败: {}", label, reason, e);
                }
            }
        }
        return false;
    }

    /**
     * 下载一条 DASH 流到缓存目录（先写 .part 临时文件，完成后原子改名，
     * 保证"文件存在即下载完整"）。带 Referer/UA/Cookie 防 CDN 403，
     * 进度实时显示在 actionbar。
     */
    private static void download(String label, String url, Path target) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", CHROME_UA)
                .header("Referer", "https://www.bilibili.com/")
                .header("Origin", "https://www.bilibili.com")
                .header("Accept", "*/*");
        BiliCookieStore.withCookie(builder);
        HttpResponse<InputStream> resp = client.send(builder.GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200 && resp.statusCode() != 206) {
            throw new IllegalStateException("上游返回 " + resp.statusCode());
        }
        long total = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
        Path part = target.resolveSibling(target.getFileName() + ".part");
        long read = 0;
        try (InputStream in = resp.body();
             OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                read += n;
                DownloadProgress.progress(label, read, total);
            }
        } catch (Exception e) {
            // 中断/失败时清理半截的临时文件，保证"存在即完整"的缓存语义
            try {
                Files.deleteIfExists(part);
            } catch (Exception ignored) {
            }
            throw e;
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 判断是否为杜比视界视频流（id=126 或 codec 以 dvh 开头）。 */
    private static boolean isDolbyVision(JsonObject o) {
        if (intOf(o, "id") == 126) {
            return true;
        }
        String codec = str(o, "codecs").toLowerCase();
        return codec.startsWith("dvh") || codec.contains("dolby_v");
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