package dev.mediafix.proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mediafix.MediaFix;
import dev.polaris_light.bilibili_media.auth.BiliCookieStore;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本机流式代理：把 B站 CDN 直链封装成 {@code http://127.0.0.1:随机端口/?t=token}，
 * 交给播放器（libvlc/waterframes）像视频文件一样拉取。播放器按 Range 请求，本代理
 * 透传 Range/响应头并逐字节转发上游 body，实现"边播边拉"，全程不落盘。
 *
 * <p>安全（防盗号/防盗链/防开放代理）：
 * <ul>
 *   <li>随机端口 + 每条直链一次性随机 token，仅携带有效 token 的请求才会被转发，
 *       其它一律 403，避免它人借本代理任意访问（SSRF 开放代理漏洞）；</li>
 *   <li>注意：必须绑定通配地址而非仅回环——用户客户端环境会拦截仅绑定 127.0.0.1
 *       的监听 socket（见 {@link #start()}），token 门禁承担访问控制；</li>
 *   <li>转发时带上 bilibili UA / Referer / Cookie，保证登录清晰度；Cookie 仅在本进程内
 *       使用，绝不写入日志或外发。</li>
 * </ul>
 */
public final class MediaStreamProxy {
    private static final int IDLE_EXPIRE_MS = 10 * 60 * 1000; // token 闲置 10 分钟即失效
    private static final int BUFFER = 32 * 1024;
    private static final String CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final SecureRandom RAND = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private static final Map<String, Upstream> TOKENS = new ConcurrentHashMap<>();

    private static HttpServer server;
    private static ExecutorService executor;
    private static boolean started;

    /** 上游直链解析器：返回新的 CDN 直链，用于 URL 过期(120min)后重解析。 */
    public interface Resolver {
        String resolve();
    }

    private static final class Upstream {
        volatile String url;
        final Resolver refresh;
        volatile long lastAccess;

        Upstream(String url, Resolver refresh) {
            this.url = url;
            this.refresh = refresh;
        }
    }

    private MediaStreamProxy() {
    }

    private static synchronized void start() {
        if (started) {
            return;
        }
        // 诊断发现：create+start 成功但端口连接被拒（可能是端口被系统/安全软件在
        // 绑定后立刻回收，或 dispatcher 未存活）。这里启动后立即自测，失败则换端口重建。
        for (int attempt = 1; attempt <= 3 && !started; attempt++) {
            HttpServer candidate = null;
            try {
                // 关键：必须绑定通配地址(0.0.0.0)而非 getLoopbackAddress()。
                // 实测用户客户端环境（含网络过滤驱动）会拦截"仅绑定 127.0.0.1"的
                // 监听 socket（连接一律 refused），而通配绑定的回环访问正常
                // （bilibili_media 的 9095 服务器即通配绑定，工作正常）。
                // 安全性由 token 门禁保证（无效 token 一律 403），与前置 mod 同等暴露面。
                candidate = HttpServer.create(new InetSocketAddress(0), 0);
                candidate.createContext("/", MediaStreamProxy::handle);
                ExecutorService pool = Executors.newCachedThreadPool(r -> {
                    Thread t = new Thread(r, "mediafix-stream-proxy");
                    t.setDaemon(true);
                    return t;
                });
                candidate.setExecutor(pool);
                candidate.start();
                int port = candidate.getAddress().getPort();
                // 启动后自测连接（100ms 后），确认真的在监听
                if (selfTest(port, 100)) {
                    server = candidate;
                    executor = pool;
                    started = true;
                    MediaFix.LOGGER.info("[mediafix] 流式代理已启动并通过自测: http://127.0.0.1:{} (通配绑定, token 门禁)", port);
                    return;
                }
                MediaFix.LOGGER.warn("[mediafix] 流式代理第{}次启动后自测失败(端口{}), 换端口重试", attempt, port);
                candidate.stop(0);
                pool.shutdownNow();
                // 二次确认：偶尔是启动竞态，多等一会再测一次
                if (selfTest(port, 800)) {
                    // 端口其实活着但 candidate 已 stop——只能重建，继续循环
                    MediaFix.LOGGER.warn("[mediafix] 端口{}迟来的连接成功——疑似启动竞态", port);
                }
            } catch (Exception e) {
                MediaFix.LOGGER.error("[mediafix] 流式代理启动失败(第{}次)", attempt, e);
                if (candidate != null) {
                    candidate.stop(0);
                }
            }
        }
        MediaFix.LOGGER.error("[mediafix] 流式代理多次启动失败，本次会话将回退直链模式");
    }

    /** 连接自测：确认端口真的在监听（不需要合法 token，能收到 403 也算通）。 */
    private static boolean selfTest(int port, long delayMs) {
        try {
            Thread.sleep(delayMs);
            HttpURLConnection conn = (HttpURLConnection) URI
                    .create("http://127.0.0.1:" + port + "/selftest").toURL().openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode(); // 403 也说明 TCP 通了
            conn.disconnect();
            MediaFix.LOGGER.info("[mediafix] 代理启动自测: code={} (端口{})", code, port);
            return code > 0;
        } catch (Exception e) {
            MediaFix.LOGGER.warn("[mediafix] 代理启动自测失败(端口{}): {}", port, String.valueOf(e));
            // 对照组：探测前置 mod 自己的本地服务器(9095)，区分"游戏内回环全坏"还是"仅本代理异常"
            try {
                HttpURLConnection ctl = (HttpURLConnection) URI
                        .create("http://127.0.0.1:9095/").toURL().openConnection();
                ctl.setConnectTimeout(2000);
                ctl.setReadTimeout(2000);
                MediaFix.LOGGER.warn("[mediafix] 对照探针(9095): code={}", ctl.getResponseCode());
                ctl.disconnect();
            } catch (Exception ce) {
                MediaFix.LOGGER.warn("[mediafix] 对照探针(9095)也失败: {}", String.valueOf(ce));
            }
            return false;
        }
    }

    public static void stop() {
        if (!started) {
            return;
        }
        // 记录调用栈：诊断是否有非预期的调用方关闭服务器
        MediaFix.LOGGER.info("[mediafix] 流式代理被关闭, 调用方:",
                new Exception("stacktrace"));
        started = false;
        TOKENS.clear();
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** 为一段直立流颁发代理地址：token 放路径而非 query（部分播放器会丢弃/改写 query），并带 .mp4 扩展名便于识别。 */
    public static URI open(URI directUri) {
        return open(directUri, null);
    }

    /** 同 {@link #open(URI)}，额外携带"过期重解析器"：上游 403 时调用它拿新直链重试。 */
    public static URI open(URI directUri, Resolver refresh) {
        start();
        if (!started) {
            // 代理起不来就回退：直接把直链交给播放器，能否播取决于 B站策略
            return directUri;
        }
        purgeExpired();
        String token = newToken();
        TOKENS.put(token, new Upstream(directUri.toString(), refresh));
        int port = server.getAddress().getPort();
        return URI.create("http://127.0.0.1:" + port + "/" + token + "/video.mp4");
    }

    public static boolean isStarted() {
        return started;
    }

    private static String newToken() {
        byte[] b = new byte[24];
        RAND.nextBytes(b);
        return B64.encodeToString(b);
    }

    private static void purgeExpired() {
        long now = System.currentTimeMillis();
        TOKENS.entrySet().removeIf(e -> now - e.getValue().lastAccess > IDLE_EXPIRE_MS);
    }

    private static void handle(HttpExchange ex) {
        try {
            String raw = ex.getRequestURI().toString();
            // 入口即打日志：任何到达代理的请求都可见，便于判断播放器是否真的来取流
            MediaFix.LOGGER.info("[mediafix][proxy] HTTP {} {}",
                    ex.getRequestMethod(), raw.length() > 80 ? raw.substring(0, 80) + "..." : raw);

            // token 从路径解析: /<token>/video.mp4
            String path = ex.getRequestURI().getPath();
            if (path == null || path.length() <= 1) {
                sendError(ex, 403);
                return;
            }
            String[] seg = path.split("/");
            String token = seg.length >= 2 ? seg[1] : null;
            if (token == null || token.isBlank()) {
                sendError(ex, 403);
                return;
            }
            Upstream up = TOKENS.get(token);
            if (up == null) {
                MediaFix.LOGGER.warn("[mediafix][proxy] 未知 token 被拒绝: {}", token);
                sendError(ex, 403);
                return;
            }
            up.lastAccess = System.currentTimeMillis();
            forward(ex, up);
        } catch (Exception e) {
            MediaFix.LOGGER.debug("[mediafix] 流式转发异常", e);
            sendErrorQuietly(ex);
        } finally {
            ex.close();
        }
    }

    private static void forward(HttpExchange ex, Upstream up) {
        forward(ex, up, true);
    }

    /** @param allowRefresh 上游 403/410 时是否允许重解析直链后重试一次。 */
    private static void forward(HttpExchange ex, Upstream up, boolean allowRefresh) {
        HttpURLConnection conn = null;
        String upstreamUrl = up.url;
        try {
            URL url = URI.create(upstreamUrl).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(0); // 长拉流不超时
            conn.setInstanceFollowRedirects(true); // 302/303 中转自动跟（且不丢失头部）
            conn.setRequestProperty("User-Agent", CHROME_UA);
            conn.setRequestProperty("Referer", "https://www.bilibili.com/");
            conn.setRequestProperty("Origin", "https://www.bilibili.com");
            conn.setRequestProperty("Accept", "*/*");
            String cookie = effectiveCookie();
            if (cookie != null && !cookie.isBlank()) {
                conn.setRequestProperty("Cookie", cookie);
            }
            String range = ex.getRequestHeaders().getFirst("Range");
            if (range != null) {
                conn.setRequestProperty("Range", range);
            }
            MediaFix.LOGGER.info("[mediafix][proxy] 收到请求 Range={} host={}",
                    range == null ? "(无)" : range, URI.create(upstreamUrl).getHost());

            int code = conn.getResponseCode();
            // 上游 403/410 = CDN 直链过期（B站直链有效期约 120 分钟）：重解析一次再试
            if ((code == 403 || code == 410) && allowRefresh && up.refresh != null) {
                conn.disconnect();
                conn = null;
                try {
                    String fresh = up.refresh.resolve();
                    if (fresh != null && !fresh.isBlank() && !fresh.equals(upstreamUrl)) {
                        MediaFix.LOGGER.info("[mediafix][proxy] 直链已过期(code={})，重解析成功，重试", code);
                        up.url = fresh;
                        forward(ex, up, false);
                        return;
                    }
                    MediaFix.LOGGER.warn("[mediafix][proxy] 直链过期但重解析未取得新链接");
                } catch (Exception re) {
                    MediaFix.LOGGER.warn("[mediafix][proxy] 直链过期且重解析失败", re);
                }
                sendError(ex, 403);
                return;
            }
            long len = contentLength(conn, code);
            MediaFix.LOGGER.info("[mediafix][proxy] 上游响应 code={} len={} type={}",
                    code, len, conn.getHeaderField("Content-Type"));
            if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
                copyHeader(ex, conn, "Content-Type");
                copyHeader(ex, conn, "Content-Range");
                copyHeader(ex, conn, "Accept-Ranges");
                // 必须用"定长"响应下发：sendResponseHeaders 的第2参是正数代表固定长度，
                // 传入 0 则启用 chunked，会让 libvlc 按 Content-Length 定界却读到 chunk 帧字节，导致解码空白。
                if (len > 0) {
                    ex.sendResponseHeaders(code, len);
                } else {
                    // 未知长度时退避 chunked（极少见；B站 CDN 正常会回 Content-Length/Content-Range）
                    ex.sendResponseHeaders(code, 0);
                }
                try (InputStream in = conn.getInputStream();
                     OutputStream out = ex.getResponseBody()) {
                    byte[] buf = new byte[BUFFER];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                }
            } else {
                sendError(ex, code);
            }
        } catch (Exception e) {
            MediaFix.LOGGER.debug("[mediafix] 上游转发失败: {}", upstreamUrl, e);
            sendErrorQuietly(ex);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 复用前置 bilibili_media 已登录的账号：优先读其内存缓存；若暂为 null（前置可能
     * 尚未完成初始化），则从磁盘 cookie 文件重读一次，保证能取到已落盘的登录态。
     */
    private static String effectiveCookie() {
        String cookie = BiliCookieStore.getCookie();
        if (cookie == null || cookie.isBlank()) {
            try {
                BiliCookieStore.init(); // 从磁盘重新加载，补一次未及时初始化的情况
                cookie = BiliCookieStore.getCookie();
            } catch (Throwable t) {
                MediaFix.LOGGER.debug("[mediafix] 重读前置B站Cookie失败", t);
            }
        }
        return cookie;
    }

    private static void copyHeader(HttpExchange ex, HttpURLConnection conn, String name) {
        String v = conn.getHeaderField(name);
        if (v != null) {
            ex.getResponseHeaders().set(name, v);
        }
    }

    /**
     * 计算响应的真实 body 长度（用于定长响应）：
     * 206 → 从 Content-Range(bytes s-e/total) 解出 e-s+1；200 → Content-Length。
     * 取不到返回 -1。
     */
    private static long contentLength(HttpURLConnection conn, int code) {
        if (code == HttpURLConnection.HTTP_PARTIAL) {
            String cr = conn.getHeaderField("Content-Range");
            if (cr != null) {
                try {
                    String r = cr.trim();
                    int s = r.indexOf('-');
                    int slash = r.indexOf('/');
                    if (s > 0 && slash > s) {
                        long end = Long.parseLong(r.substring(s + 1, slash));
                        long start = Long.parseLong(r.substring(r.indexOf(' ') + 1, s));
                        return end - start + 1;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        String cl = conn.getHeaderField("Content-Length");
        if (cl != null) {
            try {
                return Long.parseLong(cl.trim());
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) {
                continue;
            }
            if (pair.substring(0, i).equals(key)) {
                return pair.substring(i + 1);
            }
        }
        return null;
    }

    private static void sendError(HttpExchange ex, int code) throws java.io.IOException {
        ex.sendResponseHeaders(code, -1);
    }

    private static void sendErrorQuietly(HttpExchange ex) {
        try {
            ex.sendResponseHeaders(500, -1);
        } catch (Exception ignored) {
        }
    }
}