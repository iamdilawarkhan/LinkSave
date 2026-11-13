package com.user.linksave;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Engine — the resolve + download engine used by the orb flow
 * (MainActivity). Moved out of MainActivity verbatim in v1.11;
 * the v1.11 bubble flow that shared it was removed in v1.12
 * (Play Protect blocked the v1.11 install), but the refactor
 * stays — it is only code organisation.
 *
 * Everything here is static and context-free except the two methods
 * that touch storage (downloadToDownloads / saveToDownloads, which
 * take a Context for the cache dir and MediaStore). No UI, no
 * activity state: worker threads call resolveByPlatform and
 * downloadToDownloads and report back through callbacks.
 *
 * Resolvers (unchanged from v1.10):
 *  - YouTube: Invidious adaptive streams merged on-device with
 *    MediaMuxer. Direct youtubei clients are PO-token gated and
 *    every free third-party YouTube API tested is dead, blocked,
 *    or key-gated.
 *  - TikTok: TikWM API (HD/SD no-watermark, with-watermark, MP3).
 *  - Facebook: Siputzx resolver (720p HD / 360p SD).
 *  - Instagram (v1.4 chain): curl-x page parsed for the direct
 *    cdninstagram.com MP4, then vxinstagram embed, then Siputzx
 *    and ddinstagram. "Private video" only on a positive 404;
 *    service outages show "Server busy", image posts "No video".
 */
public final class Engine {

    private Engine() {}

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
    private static final int MAX_BODY = 5 * 1024 * 1024; // 5 MB response cap
    // googlevideo parts are capped at 500 MB total so a huge video
    // can't fill the cache (v1.2).
    private static final long YT_MAX_TOTAL = 500L * 1024 * 1024;

    /** Unique temp-file names across parallel downloads (v1.7). */
    private static final AtomicLong tmpSeq = new AtomicLong(0);

    // ---------------- cancellation ----------------

    /**
     * The cancel token of the resolve running on this thread.
     * MainActivity's fetch tasks set it before resolving and
     * clear it after; the HTTP helpers register every
     * connection with it, so cancelling a fetch disconnects
     * its live connections at once (v1.7). Download tasks use
     * FastDownload, which registers its own connections.
     */
    private static final ThreadLocal<FastDownload.CancelToken> currentToken =
            new ThreadLocal<>();

    public static void setCurrentToken(FastDownload.CancelToken token) {
        try { currentToken.set(token); } catch (Throwable ignored) {}
    }

    public static void clearCurrentToken() {
        try { currentToken.remove(); } catch (Throwable ignored) {}
    }

    private static void trackConnection(HttpURLConnection c) {
        try {
            FastDownload.CancelToken t = currentToken.get();
            if (t != null && c != null) t.register(c);
        } catch (Throwable ignored) {}
    }

    private static void untrackConnection(HttpURLConnection c) {
        try {
            FastDownload.CancelToken t = currentToken.get();
            if (t != null && c != null) t.unregister(c);
        } catch (Throwable ignored) {}
    }

    private static void checkCancelled(FastDownload.CancelToken token) throws Exception {
        if (token != null && token.isCancelled()) throw new Exception("Cancelled");
        if (Thread.currentThread().isInterrupted()) throw new Exception("Cancelled");
    }

    // ---------------- platform resolution ----------------

    public static Resolved resolveByPlatform(String platform, String raw) throws Exception {
        if (platform == null) throw new Exception("Unsupported platform");
        switch (platform) {
            case "youtube": return resolveYouTube(raw);
            case "tiktok": return resolveTikTok(raw);
            case "facebook": return resolveFacebook(raw);
            case "instagram": return resolveInstagram(raw);
            default: throw new Exception("Unsupported platform");
        }
    }

    // ---------------- format selection ----------------

    /**
     * Formats with a real URL — exactly the ones the pill UI would
     * show (showQualityPills skips the same entries). Never null.
     */
    public static List<Format> validFormats(Resolved r) {
        List<Format> out = new ArrayList<>();
        if (r == null || r.formats == null) return out;
        for (Format f : r.formats) {
            if (f != null && f.url != null && !f.url.isEmpty()) out.add(f);
        }
        return out;
    }

    // ---------------- records ----------------

    /**
     * The VIDEOS record for a finished download — the same fields
     * the orb flow has always written (dmId -1 for uri-based
     * records, title, platform, quality pill label, kind, file
     * name, timestamp). A merged YouTube file records kind
     * "video", not "ytmerge". Never throws; returns null only
     * when the URI is null.
     */
    public static JSONObject makeRecord(Uri saved, Resolved r, Format f, String fileName) {
        try {
            if (saved == null) return null;
            JSONObject rec = new JSONObject();
            rec.put("dmId", -1);
            rec.put("uri", saved.toString());
            rec.put("title", r == null || r.title == null ? "Video" : r.title);
            rec.put("platform", r == null || r.platform == null ? "" : r.platform);
            rec.put("quality", f == null ? "MP4"
                    : LinkUtil.shortLabel(f.label, f.kind, f.note));
            String kind = "video";
            if (f != null && !f.isMerge() && f.kind != null) kind = f.kind;
            rec.put("kind", kind);
            rec.put("file", fileName == null ? "" : fileName);
            rec.put("ts", System.currentTimeMillis());
            return rec;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------------- error classification ----------------

    /** One-line message for a fetch failure (the orb flow's rules). */
    public static String classifyFetchError(Throwable e) {
        try {
            if (e instanceof java.net.UnknownHostException
                    || e instanceof java.net.ConnectException
                    || e instanceof java.net.SocketTimeoutException) {
                return "No internet";
            }
            String m = String.valueOf(e == null ? "" : e.getMessage()).toLowerCase(Locale.US);
            if (m.contains("refused") || m.contains("private") || m.contains("no formats")) {
                return "Private video";
            }
            if (m.contains("no video")) {
                return "No video"; // post exists but has no video (image post)
            }
        } catch (Throwable ignored) {}
        return "Can't fetch video";
    }

    /** One-line message for a download failure (the orb flow's rules). */
    public static String classifyDownloadError(Throwable e) {
        try {
            String m = String.valueOf(e == null ? "" : e.getMessage());
            if (m.contains("Too large")) return "Too large";
            if (e instanceof java.net.UnknownHostException
                    || e instanceof java.net.ConnectException
                    || e instanceof java.net.SocketTimeoutException) return "No internet";
        } catch (Throwable ignored) {}
        return "Download failed";
    }

    // ---------------- HTTP helpers ----------------

    private static String httpGet(String urlStr, int timeoutMs) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            trackConnection(c);
            c.setRequestProperty("User-Agent", UA);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            return readAll(c.getInputStream());
        } finally {
            untrackConnection(c);
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) {} }
        }
    }

    private static String httpPostForm(String urlStr, String body, int timeoutMs) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            trackConnection(c);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            OutputStream out = c.getOutputStream();
            try {
                out.write(body.getBytes("UTF-8"));
                out.flush();
            } finally {
                try { out.close(); } catch (Throwable ignored) {}
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            return readAll(c.getInputStream());
        } finally {
            untrackConnection(c);
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) {} }
        }
    }

    private static String readAll(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        try {
            while ((n = r.read(buf)) != -1) {
                sb.append(buf, 0, n);
                if (sb.length() > MAX_BODY) break; // never let a huge body OOM us
            }
        } finally {
            try { r.close(); } catch (Throwable ignored) {}
        }
        return sb.toString();
    }

    private static String enc(String s) throws Exception {
        return java.net.URLEncoder.encode(s, "UTF-8");
    }

    /** Small HTTP result: status code + body (null when there is no body). */
    private static final class HttpResult {
        final int code;
        final String body;
        HttpResult(int code, String body) { this.code = code; this.body = body; }
    }

    /**
     * GET that returns the status code instead of throwing on HTTP
     * errors — resolvers need to tell 404 (deleted/private) apart from
     * 429 (rate-limited) and 5xx (service down). Only network failures
     * throw. Never returns a null result.
     */
    private static HttpResult httpGetResult(String urlStr, int timeoutMs) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            trackConnection(c);
            c.setRequestProperty("User-Agent", UA);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            String body = null;
            if (code >= 200 && code < 300) {
                body = readAll(c.getInputStream());
            } else {
                try {
                    InputStream es = c.getErrorStream();
                    if (es != null) es.close();
                } catch (Throwable ignored) {}
            }
            return new HttpResult(code, body);
        } finally {
            untrackConnection(c);
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) {} }
        }
    }

    /**
     * Follow redirects for a short link (ig.me / instagr.am carry no
     * shortcode) and return the final URL, or null. Only the headers
     * are read — the body is never downloaded.
     */
    private static String resolveRedirect(String urlStr) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            trackConnection(c);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", UA);
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.getResponseCode();
            URL fin = c.getURL();
            return fin == null ? null : fin.toString();
        } catch (Throwable ignored) {
            return null;
        } finally {
            untrackConnection(c);
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) {} }
        }
    }

    // ---------------- generic JSON media walk ----------------

    /** Pairs media URLs with a nearby quality label in any JSON shape. */
    private static void collectMedia(Object node, List<Format> out, int depth) {
        if (depth > 6 || node == null || out == null) return;
        try {
            if (node instanceof JSONArray) {
                JSONArray a = (JSONArray) node;
                for (int i = 0; i < a.length(); i++) collectMedia(a.opt(i), out, depth + 1);
                return;
            }
            if (!(node instanceof JSONObject)) return;
            JSONObject rec = (JSONObject) node;
            String url = optStr(rec, "url");
            if (url == null) url = optStr(rec, "link");
            if (url == null) url = optStr(rec, "download_url");
            if (url != null && looksLikeMedia(url)) {
                String quality = optStr(rec, "quality");
                if (quality == null) quality = optStr(rec, "resolution");
                if (quality == null) quality = optStr(rec, "label");
                if (quality == null) quality = optStr(rec, "format");
                boolean dup = false;
                for (Format f : out) {
                    if (f != null && url.equals(f.url)) { dup = true; break; }
                }
                if (!dup) out.add(new Format("f" + out.size(),
                        quality == null ? "Video MP4" : quality, "video", null, url));
            }
            JSONArray names = rec.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    collectMedia(rec.opt(names.optString(i)), out, depth + 1);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static boolean looksLikeMedia(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.US);
        return u.contains(".mp4") || u.contains("videoplayback") || u.contains("mime=video")
                || u.contains("snapcdn.app") || u.contains("cdninstagram.com")
                || u.contains(".akamaized.net");
    }

    private static String optStr(JSONObject o, String key) {
        if (o == null || key == null) return null;
        try {
            Object v = o.opt(key);
            if (v == null || v == JSONObject.NULL) return null;
            String s = String.valueOf(v);
            return s.isEmpty() ? null : s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------------- YouTube ----------------

    // Invidious instances tried in order for stream URLs. Only the first
    // is verified working as of 2026-11-12 (api-enabled, up); the rest are
    // tried because instance policies change often.
    private static final String[] INVIDIOUS_HOSTS = {
            "https://yt.chocolatemoo53.com",
            "https://inv.nadeko.net",
            "https://invidious.nerdvpn.de",
            "https://yewtu.be",
            "https://invidious.tiekoetter.com",
            "https://invidious.f5.si",
    };

    /**
     * YouTube via Invidious streams. Normally adaptive video-only
     * avc1 MP4 + audio-only mp4a googlevideo URLs: the app downloads
     * both parts and merges them on-device with MediaMuxer (see
     * downloadToDownloads), so the saved file is a normal MP4 with
     * sound. Verified live 2026-11-12: f61NRqRJlxo 360p (9,122,568
     * bytes) + itag 140 audio (2,273,758 bytes) merged into a playable
     * 11.4 MB MP4 (h264 + AAC, 140.4s).
     *
     * v1.13: when no adaptive video is found, fall back to a
     * progressive formatStreams MP4 (itag 18/22 — audio+video in one
     * file, downloaded directly, no merge). And Shorts now resolve
     * as video at all, because parseInvidiousFormats slots adaptive
     * streams by itag (vertical videos report width labels, not
     * height labels — see LinkUtil).
     */
    private static Resolved resolveYouTube(String raw) throws Exception {
        String id = LinkUtil.youtubeId(raw);
        if (id == null) throw new Exception("No video id in this YouTube link.");
        String canonical = "https://www.youtube.com/watch?v=" + id;
        Resolved r = new Resolved();
        r.platform = "youtube";
        r.sourceUrl = raw;
        r.title = "YouTube video";
        r.thumbnailUrl = "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";

        LinkUtil.YtStreams s = null;
        for (String host : INVIDIOUS_HOSTS) {
            try {
                JSONObject json = new JSONObject(httpGet(
                        host + "/api/v1/videos/" + id, 15000));
                s = LinkUtil.parseInvidiousFormats(json);
                if (s != null && (s.hasVideo() || s.hasDirect() || s.audioUrl != null)) break;
                s = null;
            } catch (Throwable ignored) { /* try the next instance */ }
        }
        if (s != null) {
            if (s.title != null) r.title = s.title;
            if (s.hasVideo() && s.audioUrl != null) {
                String hd = s.bestHd();
                String sd = s.bestSd();
                if (hd != null) {
                    Format f = new Format("ythd", "HD", "ytmerge",
                            "Video + audio", hd);
                    f.audioUrl = s.audioUrl;
                    r.formats.add(f);
                }
                if (sd != null && !sd.equals(hd)) {
                    Format f = new Format("ytsd", "SD", "ytmerge",
                            "Video + audio", sd);
                    f.audioUrl = s.audioUrl;
                    r.formats.add(f);
                }
            } else if (s.hasDirect()) {
                // v1.13: no adaptive video (or no audio to merge it
                // with) — download the progressive MP4 directly. It
                // already has audio+video in one file, so kind is a
                // plain "video": no merge, saved as a normal .mp4.
                r.formats.add(new Format("ytdirect", s.directLabel(),
                        "video", "Video + audio", s.bestDirect()));
            }
            if (s.audioUrl != null) {
                Format f = new Format("ytmp3", "MP3", "audio", "Audio only", s.audioUrl);
                f.ext = "m4a"; // AAC in MP4 container
                r.formats.add(f);
            }
        }
        if (r.formats.isEmpty()) {
            // No streams: fall back to oEmbed for a real title, then show
            // the honest busy state (Try Again) — never a fake link.
            try {
                JSONObject meta = new JSONObject(httpGet(
                        "https://www.youtube.com/oembed?url=" + enc(canonical)
                                + "&format=json", 20000));
                String t = optStr(meta, "title");
                if (t != null) r.title = t;
                r.author = optStr(meta, "author_name");
                String th = optStr(meta, "thumbnail_url");
                if (th != null) r.thumbnailUrl = th;
            } catch (Throwable ignored) { /* metadata is best-effort */ }
        }
        r.busy = r.formats.isEmpty();
        return r;
    }

    // ---------------- TikTok ----------------

    private static JSONObject tikwmFetch(String videoUrl) throws Exception {
        String body = "url=" + enc(videoUrl) + "&hd=1";
        JSONObject json = new JSONObject(httpPostForm("https://www.tikwm.com/api/", body, 30000));
        JSONObject data = json.optJSONObject("data");
        if (json.optInt("code", -1) != 0 || data == null) {
            throw new Exception("TikTok resolver refused this link.");
        }
        return data;
    }

    private static Resolved resolveTikTok(String raw) throws Exception {
        JSONObject data;
        try {
            data = tikwmFetch(raw);
        } catch (Throwable e) {
            try { Thread.sleep(1200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            data = tikwmFetch(raw); // free tier is 1 req/sec — retry once
        }
        Resolved r = new Resolved();
        r.platform = "tiktok";
        r.sourceUrl = raw;
        r.title = optStr(data, "title");
        if (r.title == null) r.title = "TikTok video";
        JSONObject author = data.optJSONObject("author");
        if (author != null) {
            r.author = optStr(author, "nickname");
            if (r.author == null && optStr(author, "unique_id") != null) {
                r.author = "@" + optStr(author, "unique_id");
            }
        }
        r.thumbnailUrl = optStr(data, "origin_cover");
        if (r.thumbnailUrl == null) r.thumbnailUrl = optStr(data, "cover");
        if (data.has("duration")) r.durationSec = data.optInt("duration");

        String hd = optStr(data, "hdplay");
        String play = optStr(data, "play");
        String wm = optStr(data, "wmplay");
        String music = optStr(data, "music");
        if (hd != null) r.formats.add(new Format("hd", "HD", "video", "No watermark", hd));
        if (play != null && !play.equals(hd)) {
            r.formats.add(new Format("sd", "SD", "video", "No watermark", play));
        }
        if (wm != null && !wm.equals(play)) {
            r.formats.add(new Format("wm", "SD", "video", "With watermark", wm));
        }
        if (music != null) r.formats.add(new Format("mp3", "MP3", "audio", "Audio only", music));
        if (r.formats.isEmpty()) throw new Exception("TikTok resolver returned no formats.");
        return r;
    }

    // ---------------- Facebook ----------------

    private static Resolved resolveFacebook(String raw) throws Exception {
        JSONObject json = new JSONObject(httpGet(
                "https://api.siputzx.my.id/api/d/facebook?url=" + enc(raw), 30000));
        JSONObject data = json.optJSONObject("data");
        if (!json.optBoolean("status", false) || data == null) {
            throw new Exception("Facebook resolver refused this link.");
        }
        Resolved r = new Resolved();
        r.platform = "facebook";
        r.sourceUrl = raw;
        r.title = optStr(data, "title");
        if (r.title == null) r.title = "Facebook video";
        r.thumbnailUrl = optStr(data, "thumbnail");
        JSONArray downloads = data.optJSONArray("downloads");
        if (downloads != null) {
            for (int i = 0; i < downloads.length(); i++) {
                JSONObject item = downloads.optJSONObject(i);
                if (item == null) continue;
                String mediaUrl = optStr(item, "url");
                if (mediaUrl == null) continue;
                String q = optStr(item, "quality");
                String type = optStr(item, "type");
                r.formats.add(new Format("fb" + i, q == null ? "Video MP4" : q,
                        "audio".equals(type) ? "audio" : "video", null, mediaUrl));
            }
        }
        if (r.formats.isEmpty()) collectMedia(data, r.formats, 0);
        if (r.formats.isEmpty()) throw new Exception("Facebook resolver returned no formats.");
        return r;
    }

    // ---------------- Instagram ----------------

    /**
     * HEAD a URL: 200 with a video content-type means a video is there,
     * 404 means deleted/private, anything else (429 even after one
     * retry, 5xx, network error) means service trouble. Returns the
     * HTTP code, or -1 on a network failure. Never throws.
     */
    private static int curlxStatus(String urlStr) {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(urlStr).openConnection();
                trackConnection(c);
                c.setRequestMethod("HEAD");
                c.setRequestProperty("User-Agent", UA);
                c.setConnectTimeout(20000);
                c.setReadTimeout(20000);
                c.setInstanceFollowRedirects(true);
                int rc = c.getResponseCode();
                if (rc == 429 && attempt == 0) {
                    // Rate-limited: wait and retry once instead of
                    // failing immediately.
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue;
                }
                if (rc == 200) {
                    String ct = c.getContentType();
                    if (ct != null && ct.toLowerCase(Locale.US).contains("video")) return 200;
                    return -1; // 200 but not a video
                }
                return rc;
            } catch (Throwable ignored) {
                return -1;
            } finally {
                untrackConnection(c);
                if (c != null) { try { c.disconnect(); } catch (Throwable ignored) {} }
            }
        }
        return -1;
    }

    /**
     * curl-x path. Returns 200 when a format was added, 404 when the
     * post is deleted/private, -2 when the page loaded but has no video
     * at all (image post), anything else for service trouble (429 even
     * after retry, 5xx, network error). Never throws.
     *
     * WHY THIS EXISTS (v1.4 diagnosis): curl-x changed —
     * /instagram/{code} used to stream the MP4 directly, now it
     * returns an HTML page that embeds the direct cdninstagram.com
     * MP4. v1.3 probed the page URL with HEAD, saw text/html, and gave
     * up; then Siputzx (404) and ddinstagram (unreachable) failed, and
     * ddinstagram's "page loaded but no video" was wrongly treated as
     * proof the post was private — so public reels showed
     * "Private video" on the user's phone. We now fetch the page once
     * and pull the CDN URL out of it, then download from the CDN
     * (Range works there, and it keeps curl-x requests to one per
     * resolve, under its rate limit).
     */
    private static int curlxResolve(String code, Resolved r) {
        String pageUrl = "https://www.curl-x.com/instagram/" + code;
        HttpResult page = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                page = httpGetResult(pageUrl, 25000);
                if (page.code != 429) break;
                if (attempt == 0) {
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            } catch (Throwable ignored) {
                return -1;
            }
        }
        if (page == null) return -1;
        if (page.code == 404) return 404;
        if (page.code != 200 || page.body == null) return -1;

        // The page embeds the direct Instagram CDN MP4 — prefer it.
        String cdn = LinkUtil.extractCurlxVideoUrl(page.body);
        if (cdn != null) {
            r.formats.add(new Format("ig0", "Video MP4", "video", null, cdn));
            if ("Instagram video".equals(r.title)) {
                String t = LinkUtil.metaContent(page.body, "og:title");
                if (t != null) r.title = t;
            }
            if (r.thumbnailUrl == null) r.thumbnailUrl = LinkUtil.metaContent(page.body, "og:image");
            return 200;
        }
        // No CDN URL in the page — try the preview file directly.
        String preview = "https://www.curl-x.com/instagram/" + code + "/preview.mp4";
        int pv = curlxStatus(preview);
        if (pv == 200) {
            r.formats.add(new Format("ig0", "Video MP4", "video", null, preview));
            return 200;
        }
        if (pv == 404) return -2; // post exists (page loaded) but has no video
        return -1;
    }

    /**
     * Instagram, four paths in order, all live-tested 2026-11-12:
     *  1. curl-x page parsed for the direct cdninstagram.com MP4
     *     (verified: NASA reel DTQXTbvjp4y and DXnnbbxk_hO — real
     *     MP4 bytes, Range works).
     *  2. vxinstagram embed — og:video points at an offload MP4 that
     *     302-redirects to fbcdn (verified: same two reels, real
     *     MP4 bytes; fake shortcode gives a clean 404).
     *  3. Siputzx resolver (HTTP 404 "Route not found on upstream
     *     nodes" at last check — kept in case it comes back).
     *  4. ddinstagram embed (unreachable from our tests — kept).
     *
     * Honest errors: "Private video" ONLY when a service positively
     * says the post is deleted/private (HTTP 404). An embed page that
     * loads without a video does NOT mean private — it means the
     * embed service failed to fetch (v1.3 wrongly treated that as
     * private, which is what the user saw). Service outages show
     * "Server busy" + Try Again, and a post with no video at all
     * (image post) shows "No video".
     */
    private static Resolved resolveInstagram(String raw) throws Exception {
        Resolved r = new Resolved();
        r.platform = "instagram";
        r.sourceUrl = raw;
        r.title = "Instagram video";
        boolean notFound = false;   // a service positively said: deleted/private
        boolean serviceError = false;
        boolean noVideo = false;    // post loaded fine but contains no video

        String code = LinkUtil.instagramShortcode(raw);
        // ig.me / instagr.am short links carry no shortcode — follow
        // the redirect to the real instagram.com URL first.
        if (code == null) {
            String finalUrl = resolveRedirect(raw);
            if (finalUrl != null) code = LinkUtil.instagramShortcode(finalUrl);
        }

        // Path 1: curl-x page parsed for the direct CDN MP4.
        if (code != null) {
            int cx = curlxResolve(code, r);
            if (cx == 404) {
                notFound = true;
            } else if (cx == -2) {
                noVideo = true;
            } else if (cx != 200) {
                serviceError = true;
            }
        }

        // Path 2: vxinstagram embed (og:video -> offload MP4 -> fbcdn).
        if (r.formats.isEmpty() && code != null) {
            try {
                HttpResult vx = httpGetResult(
                        "https://www.vxinstagram.com/reel/" + code, 25000);
                if (vx.code == 404) {
                    notFound = true;
                } else if (vx.code == 200 && vx.body != null) {
                    String video = LinkUtil.metaContent(vx.body, "og:video");
                    if (video == null) video = LinkUtil.metaContent(vx.body, "og:video:secure_url");
                    if (video != null) {
                        r.formats.add(new Format("ig0", "Video MP4", "video", null, video));
                    } else {
                        // Page loaded but no video — the embed service
                        // failed to fetch, NOT proof the post is private.
                        serviceError = true;
                    }
                    if ("Instagram video".equals(r.title)) {
                        String t = LinkUtil.metaContent(vx.body, "og:title");
                        if (t != null) r.title = t;
                    }
                    if (r.thumbnailUrl == null) r.thumbnailUrl = LinkUtil.metaContent(vx.body, "og:image");
                } else {
                    serviceError = true;
                }
            } catch (Throwable ignored) {
                serviceError = true;
            }
        }

        // Path 3: Siputzx resolver (down at last check, kept as fallback).
        if (r.formats.isEmpty()) {
            try {
                JSONObject json = new JSONObject(httpGet(
                        "https://api.siputzx.my.id/api/d/instagram?url=" + enc(raw), 30000));
                collectMedia(json, r.formats, 0);
                JSONObject data = json.optJSONObject("data");
                if (data != null) {
                    String t = optStr(data, "title");
                    if (t == null) t = optStr(data, "caption");
                    if (t != null) r.title = t;
                    r.thumbnailUrl = optStr(data, "thumbnail");
                    if (r.thumbnailUrl == null) r.thumbnailUrl = optStr(data, "cover");
                }
                if (r.formats.isEmpty() && !json.optBoolean("status", false)) {
                    String m = String.valueOf(json.opt("message"));
                    m = m == null ? "" : m.toLowerCase(Locale.US);
                    if (m.contains("private") || m.contains("not found")) {
                        notFound = true;
                    } else {
                        // "login" and anything else mean the service
                        // itself could not fetch — not proof of private.
                        serviceError = true;
                    }
                }
            } catch (Throwable ignored) {
                serviceError = true;
            }
        }

        // Path 4: ddinstagram embed fallback (unreachable at last check).
        if (r.formats.isEmpty() && code != null) {
            try {
                String html = httpGet("https://www.ddinstagram.com/reel/" + code, 25000);
                String video = LinkUtil.metaContent(html, "og:video:secure_url");
                if (video == null) video = LinkUtil.metaContent(html, "og:video");
                if (video == null) video = LinkUtil.metaContent(html, "twitter:player:stream");
                if (video != null) {
                    r.formats.add(new Format("ig0", "Video MP4", "video", null, video));
                } else {
                    // Page loaded but contains no video — the embed
                    // service failed to fetch, NOT proof of private.
                    serviceError = true;
                }
                if ("Instagram video".equals(r.title)) {
                    String t = LinkUtil.metaContent(html, "og:title");
                    if (t != null) r.title = t;
                }
                if (r.thumbnailUrl == null) r.thumbnailUrl = LinkUtil.metaContent(html, "og:image");
            } catch (Throwable e) {
                String m = String.valueOf(e == null ? "" : e.getMessage());
                if (m.contains("HTTP 404")) notFound = true;
                else serviceError = true;
            }
        }

        if (!r.formats.isEmpty()) return r;
        if (notFound) throw new Exception("Private video");
        if (noVideo) throw new Exception("No video");
        // Every path failed with a service error — say so honestly
        // instead of blaming the video.
        r.busy = true;
        return r;
    }

    // ---------------- downloads ----------------

    /** Progress callbacks from downloadToDownloads (worker threads). */
    public interface Listener {
        /** Bytes done / total (-1 while the total is still unknown). */
        void onProgress(long done, long total);
        /** The YouTube merge step started (no more bytes to count). */
        void onMerging();
    }

    /** A finished download: where it was saved + its file name. */
    public static final class Result {
        public final Uri uri;
        public final String fileName;
        Result(Uri uri, String fileName) { this.uri = uri; this.fileName = fileName; }
    }

    /**
     * Download one resolved format into Downloads/LinkSave and return
     * where it landed. The single path and the YouTube merged path
     * from MainActivity (v1.2/v1.3), unified: a normal format
     * downloads in 4 parallel Range chunks via FastDownload; a
     * ytmerge format downloads its video-only and audio-only parts
     * concurrently, then remuxes them into one MP4 with MediaMuxer
     * (no re-encode). Temp files are unique per call and deleted
     * afterwards; a cancelled download throws an Exception whose
     * message contains "Cancelled" (never retried, never recorded).
     * Runs on the caller's thread — call it from a worker thread.
     */
    public static Result downloadToDownloads(Context ctx, Resolved r, Format f,
            Listener listener, FastDownload.CancelToken token) throws Exception {
        if (ctx == null || r == null || f == null || f.url == null || f.url.isEmpty()) {
            throw new Exception("Download failed");
        }
        checkCancelled(token);
        File cache = ctx.getCacheDir();
        if (cache == null) throw new Exception("No cache");
        long n = tmpSeq.incrementAndGet();
        if (f.isMerge()) {
            return downloadMerged(ctx, cache, n, r, f, listener, token);
        }
        String ext = f.ext != null ? f.ext : ("audio".equals(f.kind) ? "mp3" : "mp4");
        String fileName = LinkUtil.safeFileName(r.title) + "_"
                + System.currentTimeMillis() + "." + ext;
        File tmp = new File(cache, "ls_dl_" + n + "." + ext);
        try {
            deleteQuietly(tmp);
            FastDownload.download(f.url, tmp, UA, YT_MAX_TOTAL,
                    (done, total) -> {
                        if (listener != null) {
                            try { listener.onProgress(done, total); } catch (Throwable ignored) {}
                        }
                    }, token);
            if (token != null && token.isCancelled()) throw new Exception("Cancelled");
            if (!tmp.exists() || tmp.length() <= 0) throw new Exception("Empty file");
            String mime = "audio".equals(f.kind) ? "audio/mpeg" : "video/mp4";
            Uri saved = saveToDownloads(ctx, tmp, fileName, mime);
            if (saved == null) throw new Exception("Save failed");
            if (token != null && token.isCancelled()) throw new Exception("Cancelled");
            if (listener != null) {
                try { listener.onProgress(tmp.length(), tmp.length()); } catch (Throwable ignored) {}
            }
            return new Result(saved, fileName);
        } finally {
            deleteQuietly(tmp);
        }
    }

    /** YouTube merged path: concurrent video+audio parts, then mux. */
    private static Result downloadMerged(Context ctx, File cache, long n, Resolved r,
            Format f, Listener listener, FastDownload.CancelToken token) throws Exception {
        File tmpVideo = new File(cache, "ls_video_" + n + ".mp4");
        File tmpAudio = new File(cache, "ls_audio_" + n + ".m4a");
        File tmpMerged = new File(cache, "ls_merged_" + n + ".mp4");
        deleteQuietly(tmpVideo);
        deleteQuietly(tmpAudio);
        deleteQuietly(tmpMerged);
        ExecutorService pair = null;
        try {
            // Video + audio download CONCURRENTLY (v1.3), each part in
            // 4 parallel Range chunks via FastDownload. Both parts
            // share the cancel token, so cancelling disconnects both
            // at once. Progress aggregates both parts: the total is
            // known only when BOTH parts' totals are known, otherwise
            // -1 (the aggregate counts only known totals — see
            // LinkUtil.aggregateProgress).
            final long[] parts = new long[]{0, -1, 0, -1}; // vDone, vTotal, aDone, aTotal
            FastDownload.Progress vProg = (d, t) -> {
                synchronized (parts) {
                    parts[0] = d; parts[1] = t;
                    reportParts(parts, listener);
                }
            };
            FastDownload.Progress aProg = (d, t) -> {
                synchronized (parts) {
                    parts[2] = d; parts[3] = t;
                    reportParts(parts, listener);
                }
            };
            pair = Executors.newFixedThreadPool(2);
            Future<Long> vf = pair.submit(() ->
                    FastDownload.download(f.url, tmpVideo, UA, YT_MAX_TOTAL, vProg, token));
            Future<Long> af = pair.submit(() ->
                    FastDownload.download(f.audioUrl, tmpAudio, UA, YT_MAX_TOTAL, aProg, token));
            long vTotal, aTotal;
            try {
                vTotal = vf.get();
                aTotal = af.get();
            } catch (java.util.concurrent.ExecutionException ee) {
                Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                throw cause instanceof Exception ? (Exception) cause : new Exception(cause);
            }
            if (token != null && token.isCancelled()) throw new Exception("Cancelled");
            if (vTotal <= 0) throw new Exception("Empty video part");
            if (aTotal <= 0) throw new Exception("Empty audio part");
            if (vTotal + aTotal > YT_MAX_TOTAL) throw new Exception("Too large");

            if (listener != null) {
                try { listener.onMerging(); } catch (Throwable ignored) {}
            }
            muxMp4(tmpVideo, tmpAudio, tmpMerged, token);
            if (token != null && token.isCancelled()) throw new Exception("Cancelled");
            if (!tmpMerged.exists() || tmpMerged.length() <= 0) {
                throw new Exception("Merge produced nothing");
            }

            String fileName = LinkUtil.safeFileName(r.title) + "_"
                    + System.currentTimeMillis() + ".mp4";
            Uri saved = saveToDownloads(ctx, tmpMerged, fileName, "video/mp4");
            if (saved == null) throw new Exception("Save failed");
            if (token != null && token.isCancelled()) throw new Exception("Cancelled");
            return new Result(saved, fileName);
        } finally {
            if (pair != null) {
                try { pair.shutdownNow(); } catch (Throwable ignored) {}
            }
            deleteQuietly(tmpVideo);
            deleteQuietly(tmpAudio);
            deleteQuietly(tmpMerged);
        }
    }

    /** Aggregate the two YouTube parts into one (done, total) report. */
    private static void reportParts(long[] parts, Listener listener) {
        if (listener == null) return;
        try {
            long done = parts[0] + parts[2];
            long total = (parts[1] > 0 && parts[3] > 0) ? parts[1] + parts[3] : -1;
            listener.onProgress(done, total);
        } catch (Throwable ignored) {}
    }

    private static void deleteQuietly(File f) {
        try { if (f != null) f.delete(); } catch (Throwable ignored) {}
    }

    /**
     * Remux one video-only MP4 and one audio-only MP4 into a single MP4
     * with MediaMuxer — no re-encoding, just a container rewrite. Both
     * inputs are fMP4 (DASH) files from googlevideo; MediaExtractor
     * reads them, timestamps are normalised per track so the muxer
     * never sees a backwards timestamp. The cancel token (v1.7) is
     * checked between sample batches, so cancelling during the merge
     * stops it promptly instead of finishing a file nobody wants.
     */
    private static void muxMp4(File video, File audio, File out,
                               FastDownload.CancelToken token) throws Exception {
        if (token != null && token.isCancelled()) throw new Exception("Cancelled");
        MediaMuxer muxer = null;
        MediaExtractor vEx = null, aEx = null;
        try {
            muxer = new MediaMuxer(out.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vTrack = -1, aTrack = -1;
            vEx = new MediaExtractor();
            vEx.setDataSource(video.getAbsolutePath());
            vTrack = addTrackFrom(vEx, muxer, "video/");
            aEx = new MediaExtractor();
            aEx.setDataSource(audio.getAbsolutePath());
            aTrack = addTrackFrom(aEx, muxer, "audio/");
            if (vTrack < 0) throw new Exception("No video track");
            if (aTrack < 0) throw new Exception("No audio track");
            muxer.start();
            int vSamples = copyTrackSamples(vEx, muxer, vTrack, token);
            int aSamples = copyTrackSamples(aEx, muxer, aTrack, token);
            if (vSamples <= 0 || aSamples <= 0) {
                throw new Exception("Empty track in merge");
            }
            muxer.stop();
        } finally {
            if (muxer != null) {
                try { muxer.release(); } catch (Throwable ignored) {}
            }
            if (vEx != null) {
                try { vEx.release(); } catch (Throwable ignored) {}
            }
            if (aEx != null) {
                try { aEx.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private static int addTrackFrom(MediaExtractor ex, MediaMuxer muxer, String mimePrefix)
            throws Exception {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat fmt = ex.getTrackFormat(i);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) {
                ex.selectTrack(i);
                return muxer.addTrack(fmt);
            }
        }
        return -1;
    }

    /** Copies one track's samples into the muxer; returns samples written. */
    private static int copyTrackSamples(MediaExtractor ex, MediaMuxer muxer, int track,
                                        FastDownload.CancelToken token)
            throws Exception {
        int maxSize = 1024 * 1024;
        try {
            int sel = ex.getSampleTrackIndex();
            if (sel >= 0) {
                MediaFormat fmt = ex.getTrackFormat(sel);
                if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxSize = Math.max(maxSize,
                            fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                }
            }
        } catch (Throwable ignored) {}
        ByteBuffer buf = ByteBuffer.allocate(maxSize);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long firstPts = -1;
        int count = 0;
        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        while (true) {
            // Check every 100 samples — often enough to stop promptly,
            // rarely enough to cost nothing.
            if ((count & 0x63) == 0 && token != null && token.isCancelled()) {
                throw new Exception("Cancelled");
            }
            int n = ex.readSampleData(buf, 0);
            if (n < 0) break;
            long pts = ex.getSampleTime();
            if (firstPts < 0) firstPts = Math.max(0, pts);
            info.offset = 0;
            info.size = n;
            info.presentationTimeUs = Math.max(0, pts - firstPts);
            info.flags = ex.getSampleFlags();
            muxer.writeSampleData(track, buf, info);
            ex.advance();
            count++;
        }
        return count;
    }

    /**
     * Save a finished file into Downloads/LinkSave. API 29+ goes through
     * MediaStore (scoped storage); older devices write the file directly.
     * Returns the content/file Uri, or null on failure.
     */
    public static Uri saveToDownloads(Context ctx, File src, String fileName, String mime) {
        if (ctx == null || src == null || !src.exists()) return null;
        if (mime == null || mime.isEmpty()) mime = "video/mp4";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = ctx.getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/LinkSave");
                v.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return null;
                OutputStream os = null;
                FileInputStream fis = null;
                try {
                    os = cr.openOutputStream(uri);
                    if (os == null) return null;
                    fis = new FileInputStream(src);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = fis.read(buf)) != -1) os.write(buf, 0, n);
                    os.flush();
                } finally {
                    try { if (fis != null) fis.close(); } catch (Throwable ignored) {}
                    try { if (os != null) os.close(); } catch (Throwable ignored) {}
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Downloads.IS_PENDING, 0);
                cr.update(uri, done, null, null);
                return uri;
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "LinkSave");
                if (!dir.exists() && !dir.mkdirs()) return null;
                File dst = new File(dir, fileName);
                FileInputStream fis = null;
                FileOutputStream fos = null;
                try {
                    fis = new FileInputStream(src);
                    fos = new FileOutputStream(dst);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
                    fos.flush();
                } finally {
                    try { if (fis != null) fis.close(); } catch (Throwable ignored) {}
                    try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
                }
                return Uri.fromFile(dst);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }
}
