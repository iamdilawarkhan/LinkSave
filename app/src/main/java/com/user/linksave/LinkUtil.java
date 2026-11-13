package com.user.linksave;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java helpers for LinkSave — no Android dependencies, so they can be
 * unit-tested on a plain JVM. Every method is null-safe: bad input returns
 * null / a safe fallback, never throws.
 */
public final class LinkUtil {

    private LinkUtil() {}

    /**
     * First http(s) URL found anywhere in the text. The clipboard often
     * holds more than a bare link (a caption line, a trailing newline,
     * text around a shared link), and every parser below must still
     * find the URL inside it. Trailing punctuation the user did not
     * mean as part of the URL (".", ",", ")", ...) is stripped.
     */
    public static String extractUrl(String text) {
        if (text == null) return null;
        try {
            Matcher m = Pattern.compile("https?://[^\\s\"'<>]+").matcher(text);
            if (!m.find()) return null;
            String u = m.group();
            while (!u.isEmpty() && ".,;:!?)]}".indexOf(u.charAt(u.length() - 1)) >= 0) {
                u = u.substring(0, u.length() - 1);
            }
            return u.isEmpty() ? null : u;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** youtube / instagram / tiktok / facebook, or null for anything else. */
    public static String detectPlatform(String raw) {
        if (raw == null) return null;
        String url = extractUrl(raw);
        if (url == null) return null;
        try {
            URL u = new URL(url);
            String proto = u.getProtocol();
            if (!"http".equals(proto) && !"https".equals(proto)) return null;
            String host = u.getHost();
            if (host == null) return null;
            host = host.toLowerCase(Locale.US)
                    .replaceFirst("^www\\.", "").replaceFirst("^m\\.", "");
            if (host.equals("youtube.com") || host.equals("youtu.be")
                    || host.endsWith(".youtube.com")) return "youtube";
            if (host.equals("instagram.com") || host.endsWith(".instagram.com")
                    || host.equals("ig.me") || host.equals("instagr.am"))
                return "instagram";
            if (host.equals("tiktok.com") || host.endsWith(".tiktok.com")) return "tiktok";
            if (host.equals("facebook.com") || host.endsWith(".facebook.com")
                    || host.equals("fb.watch") || host.equals("fb.com")) return "facebook";
        } catch (Exception ignored) {}
        return null;
    }

    /** Video id from any YouTube URL shape (watch / shorts / youtu.be / embed / live). */
    public static String youtubeId(String raw) {
        if (raw == null) return null;
        String url = extractUrl(raw);
        if (url == null) return null;
        try {
            URL u = new URL(url);
            String host = u.getHost();
            if (host == null) return null;
            host = host.toLowerCase(Locale.US).replaceFirst("^www\\.", "");
            if (host.equals("youtu.be")) {
                String[] p = u.getPath().split("/");
                for (String s : p) if (s != null && !s.isEmpty()) return s;
                return null;
            }
            String q = u.getQuery();
            if (q != null) {
                for (String kv : q.split("&")) {
                    if (kv != null && kv.startsWith("v=") && kv.length() > 2) {
                        return kv.substring(2);
                    }
                }
            }
            String[] parts = u.getPath().split("/");
            List<String> segs = new ArrayList<>();
            for (String s : parts) if (s != null && !s.isEmpty()) segs.add(s);
            if (segs.size() >= 2 && ("shorts".equals(segs.get(0))
                    || "embed".equals(segs.get(0)) || "live".equals(segs.get(0)))) {
                return segs.get(1);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Shortcode from an Instagram reel / post / tv URL, or null. Handles
     * every shape users actually copy: /reel/, /reels/, /p/, /tv/,
     * /share/reel/, ?igsh= and utm query params, and links wrapped in
     * surrounding text or whitespace. ig.me / instagr.am short links
     * carry no shortcode — those need a redirect follow first (the
     * caller does that) and return null here.
     */
    public static String instagramShortcode(String raw) {
        if (raw == null) return null;
        String url = extractUrl(raw);
        if (url == null) return null;
        try {
            String[] parts = new URL(url).getPath().split("/");
            List<String> segs = new ArrayList<>();
            for (String s : parts) if (s != null && !s.isEmpty()) segs.add(s);
            for (int i = 0; i < segs.size(); i++) {
                String p = segs.get(i);
                if (("reel".equals(p) || "reels".equals(p) || "p".equals(p)
                        || "tv".equals(p)) && i + 1 < segs.size()) {
                    return segs.get(i + 1);
                }
            }
        } catch (Exception ignored) {}
        // Regex fallback on the raw string, for odd shapes the URL
        // parser did not split as expected.
        try {
            Matcher m = Pattern.compile(
                    "instagram\\.com/(?:share/)?(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)")
                    .matcher(url);
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Direct Instagram CDN video URL out of a curl-x page
     * (https://www.curl-x.com/instagram/{code}). The page HTML embeds
     * the scontent/fbcdn MP4 the post actually plays from; downloading
     * from the CDN (instead of from curl-x) keeps curl-x requests to
     * one per resolve, under its rate limit, and Range works there.
     * Returns null when the page has no video URL (image post, or the
     * service failed to extract). Never throws.
     */
    public static String extractCurlxVideoUrl(String html) {
        if (html == null) return null;
        try {
            // Unescape first: URLs appear with &amp;, \/ and \u0026 escapes.
            String flat = html.replace("\\/", "/").replace("\\u0026", "&")
                    .replace("&amp;", "&");
            Matcher m = Pattern.compile("https://[^\\s\"'<>]+?\\.mp4[^\\s\"'<>]*")
                    .matcher(flat);
            while (m.find()) {
                String u = m.group();
                if (!u.contains("cdninstagram.com") && !u.contains("fbcdn.net")) continue;
                while (u.endsWith("\\")) u = u.substring(0, u.length() - 1);
                if (u.startsWith("https://") && u.contains(".mp4")) return u;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** content="..." of a <meta property|name="..."> tag, either attribute order. */
    public static String metaContent(String html, String property) {
        if (html == null || property == null) return null;
        try {
            String[] patterns = {
                    "<meta[^>]+(?:property|name)=[\"']" + Pattern.quote(property)
                            + "[\"'][^>]+content=[\"']([^\"']+)[\"']",
                    "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"']"
                            + Pattern.quote(property) + "[\"']",
            };
            for (String p : patterns) {
                Matcher m = Pattern.compile(p, Pattern.CASE_INSENSITIVE).matcher(html);
                if (m.find()) return m.group(1).replace("&amp;", "&");
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static String fmtDuration(int sec) {
        if (sec < 0) sec = 0;
        return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(Locale.US) + s.substring(1);
    }

    // ---- v1.6 smart flow: what to do with a resolved video ----
    /** No usable option — show a one-line error, as before. */
    public static final int FLOW_ERROR = 0;
    /** Exactly 1 usable option — start the download at once, no asking. */
    public static final int FLOW_AUTO_DOWNLOAD = 1;
    /** 2+ usable options — show "Choose quality" above the pills. */
    public static final int FLOW_CHOOSE_QUALITY = 2;

    /**
     * v1.6 smart-flow decision from the number of usable download
     * options. The user's rule: when there are several options (HD,
     * SD, Audio) the app asks with a "Choose quality" label; when
     * there is only one (e.g. a lone "MP4" pill) asking is pointless
     * — the download just starts. 0 stays an error state. Never throws.
     */
    public static int flowForOptionCount(int validCount) {
        if (validCount <= 0) return FLOW_ERROR;
        if (validCount == 1) return FLOW_AUTO_DOWNLOAD;
        return FLOW_CHOOSE_QUALITY;
    }

    /**
     * v1.13 guard: the single usable option is audio-only (a lone MP3
     * pill). A video link that only resolves to audio must NOT be
     * silently auto-downloaded — the user asked for a video, and a
     * quiet audio download is exactly the Shorts bug he reported.
     * When this is true MainActivity shows the one MP3 pill instead
     * (no "Choose quality" label — one option is still not a choice),
     * so the user sees it is audio-only and taps only if he wants it.
     * Never throws.
     */
    public static boolean isAudioOnlySingle(List<Format> valid) {
        try {
            return valid != null && valid.size() == 1 && valid.get(0) != null
                    && "audio".equals(valid.get(0).kind);
        } catch (Exception ignored) {
            return false;
        }
    }

    // ---- v1.13: selective delete on the VIDEOS screen ----
    /**
     * Stable identity of one VIDEOS record for the tick-selection set:
     * uri-based records (merged YouTube files) by their content/file
     * URI, DownloadManager records by their download id. Never null.
     */
    public static String deleteKey(String uri, long dmId) {
        if (uri != null && !uri.isEmpty()) return "u:" + uri;
        return "d:" + dmId;
    }

    /**
     * The records left after deleting the selected ones: everything
     * whose deleteKey is NOT in the selection, in the original order.
     * An empty or null selection keeps everything (deleting with
     * nothing ticked is a no-op). Null records are dropped. Never
     * throws, never returns null.
     */
    public static List<JSONObject> removeSelected(List<JSONObject> list, Set<String> selected) {
        List<JSONObject> kept = new ArrayList<>();
        if (list == null) return kept;
        try {
            for (JSONObject o : list) {
                if (o == null) continue;
                String key = deleteKey(o.optString("uri", ""), o.optLong("dmId", -1));
                if (selected == null || !selected.contains(key)) kept.add(o);
            }
        } catch (Exception ignored) {}
        return kept;
    }

    // ---- v1.7: orb-tap decisions with parallel downloads ----
    /** Job states (shared with MainActivity.Job). */
    public static final int JOB_FETCHING = 0;
    public static final int JOB_CHOOSING = 1;
    public static final int JOB_DOWNLOADING = 2;

    /** Orb-tap actions. */
    public static final int TAP_START = 0;   // new link -> fetch it
    public static final int TAP_CANCEL = 1;  // same link, fetching/downloading -> cancel it
    public static final int TAP_IGNORE = 2;  // same link, choice on screen -> do nothing
    public static final int TAP_CAP = 3;     // new link but at the download cap -> one-line message

    /**
     * v1.7 orb-tap decision (the user's rule): while a download is
     * running, tapping the orb with NO new link copied cancels that
     * download; tapping with a NEW link copied starts it in parallel
     * and the first one keeps running. A link already fetching or
     * downloading is never started twice — the same-link tap cancels
     * it instead. A same-link tap while its quality choice is already
     * on screen does nothing. A new link at the download cap is
     * refused with a one-line message. Cancel always wins over the
     * cap: a user at the cap must still be able to cancel. Never throws.
     *
     * @param linkActive    the tapped link already has a job (fetching / choosing / downloading)
     * @param linkState     that job's state (JOB_FETCHING / JOB_CHOOSING / JOB_DOWNLOADING)
     * @param activeDownloads how many downloads are running right now
     * @param maxDownloads  concurrent-download cap (<= 0 means no cap)
     */
    public static int orbTapAction(boolean linkActive, int linkState,
                                   int activeDownloads, int maxDownloads) {
        if (linkActive) {
            if (linkState == JOB_CHOOSING) return TAP_IGNORE;
            return TAP_CANCEL; // fetching or downloading (or unknown state) -> cancel
        }
        if (maxDownloads > 0 && activeDownloads >= maxDownloads) return TAP_CAP;
        return TAP_START;
    }

    /**
     * True when nothing is left running — no downloads, no fetches,
     * no quality choice on screen. After cancelling the last download
     * this is true and the app goes back to idle; cancelling one of
     * several downloads leaves it false and the rest keep running.
     * Never throws.
     */
    public static boolean isIdle(int downloads, int fetches, boolean choosing) {
        return downloads <= 0 && fetches <= 0 && !choosing;
    }

    /** Aggregate progress across parallel downloads (v1.7). */
    public static final class Aggregate {
        /** 0-100, or -1 when no download has a known total yet. */
        public final int pct;
        /** Sum of bytes done across all downloads. */
        public final long done;
        /** Sum of KNOWN totals only (0 when none known). */
        public final long total;
        /** True when at least one total is known. */
        public final boolean known;
        Aggregate(int pct, long done, long total, boolean known) {
            this.pct = pct; this.done = done; this.total = total; this.known = known;
        }
    }

    /**
     * Aggregate progress across parallel downloads: the orb % and the
     * "Please wait... X / Y MB" line show the sum of bytes done over
     * the sum of KNOWN totals. A download whose total is unknown yet
     * still contributes its bytes-so-far to the numerator but nothing
     * to the denominator; when no total is known at all, pct is -1
     * and the UI shows the spinner with "X MB" instead. The percentage
     * is capped at 100 (done can exceed the known totals when an
     * unknown-total download has already written a lot). Null, empty
     * and mismatched arrays are safe (mismatched: the shorter length
     * wins). Never throws.
     */
    public static Aggregate aggregateProgress(long[] done, long[] total) {
        if (done == null || total == null) return new Aggregate(-1, 0, 0, false);
        int n = Math.min(done.length, total.length);
        long dSum = 0, tSum = 0;
        for (int i = 0; i < n; i++) {
            long d = done[i] < 0 ? 0 : done[i];
            dSum += d;
            if (total[i] > 0) tSum += total[i];
        }
        if (tSum <= 0) return new Aggregate(-1, dSum, 0, false);
        int pct = (int) Math.min(100, dSum * 100L / tSum);
        return new Aggregate(pct, dSum, tSum, true);
    }

    /**
     * Count of usable format URLs: non-null, non-empty strings. A null
     * list counts as 0. This is the same rule the pill UI uses to skip
     * entries, so the count matches exactly what the user would be
     * asked to choose from. Never throws.
     */
    public static int countValidUrls(List<String> urls) {
        int n = 0;
        if (urls == null) return 0;
        try {
            for (String u : urls) if (u != null && !u.isEmpty()) n++;
        } catch (Exception ignored) {}
        return n;
    }

    /** Bytes as megabytes with one decimal, e.g. 33456128 -> "31.9". */
    public static String formatMB(long bytes) {
        if (bytes < 0) bytes = 0;
        return String.format(Locale.US, "%.1f", bytes / 1048576.0);
    }

    /** Safe file-name stem: alphanumerics only, max 50 chars, never empty. */
    public static String safeFileName(String title) {
        String safe = (title == null ? "" : title).replaceAll("[^A-Za-z0-9._-]+", "_");
        if (safe.length() > 50) safe = safe.substring(0, 50);
        while (safe.endsWith("_") || safe.endsWith(".")) {
            safe = safe.substring(0, safe.length() - 1);
        }
        return safe.isEmpty() ? "video" : safe;
    }

    /**
     * Short pill label for a download format. Never null, never long.
     * kind: "video" / "audio". label: resolver quality string. note: e.g. "No watermark".
     */
    // ---------------- YouTube via Invidious ----------------

    /** Parsed Invidious streams for one video. */
    public static final class YtStreams {
        public String title;
        public String video1080, video720, video480, video360, video240, video144;
        public String direct1080, direct720, direct360; // progressive MP4 (audio+video, no merge)
        public String audioUrl;   // best audio/mp4 (itag 140 preferred)
        public boolean hasVideo() {
            return video1080 != null || video720 != null || video480 != null
                    || video360 != null || video240 != null || video144 != null;
        }
        /** A progressive (audio+video in one file) MP4 stream exists. */
        public boolean hasDirect() {
            return direct1080 != null || direct720 != null || direct360 != null;
        }
        /** Best video at or below 720p (what the HD pill downloads). */
        public String bestHd() {
            if (video720 != null) return video720;
            if (video480 != null) return video480;
            if (video1080 != null) return video1080;
            if (video360 != null) return video360;
            if (video240 != null) return video240;
            return video144;
        }
        /** 360p video, or the next closest (what the SD pill downloads). */
        public String bestSd() {
            if (video360 != null) return video360;
            if (video480 != null) return video480;
            if (video240 != null) return video240;
            if (video144 != null) return video144;
            return video720 != null ? video720 : video1080;
        }
        /** Best progressive stream (720p preferred, like bestHd). */
        public String bestDirect() {
            if (direct720 != null) return direct720;
            if (direct1080 != null) return direct1080;
            return direct360;
        }
        /** Pill label for the best progressive stream: HD (720p+) or SD. */
        public String directLabel() {
            if (direct720 != null || direct1080 != null) return "HD";
            return "SD";
        }
    }

    /**
     * Parse an Invidious /api/v1/videos/{id} response. Adaptive streams
     * are video-only (avc1 MP4) and audio-only (mp4a) googlevideo URLs,
     * downloaded and merged on-device with MediaMuxer. Some responses
     * also carry progressive formatStreams (itag 18/22: audio+video in
     * one MP4, no merge needed) — used only when no adaptive video is
     * found. Returns null when the response has no usable streams.
     * Never throws.
     *
     * v1.13 — Shorts fix: Invidious reports a vertical video's
     * resolution as its WIDTH ("256p/426p/640p/854p/1280p/1920p" for
     * a 9:16 Short), not its height, so matching on the old
     * "1080p/720p/480p/360p/144p" labels found no video at all for
     * Shorts and only the MP3 was offered. Video streams are now
     * slotted by ITAG first (137/136/135/134/133/160 — stable
     * across orientations), with the resolution label (standard
     * height labels AND vertical width labels) as the fallback for
     * unknown itags. Verified live 2026-11-13 on the user's own
     * Short 4ZGWpNrSIx8: itag 136 ("1280p") is the 720p stream,
     * itag 134 ("640p") is the 360p stream.
     *
     * VP9 (video/webm) and AV1 (video/mp4 av01) adaptive streams are
     * deliberately NOT accepted: the on-device merge uses MediaMuxer
     * with MPEG_4 output, which cannot mux VP9/AV1 video tracks —
     * shipping them would produce an unplayable file.
     */
    public static YtStreams parseInvidiousFormats(JSONObject json) {
        if (json == null) return null;
        try {
            YtStreams out = new YtStreams();
            String t = optJsonStr(json, "title");
            if (t != null) out.title = t;
            JSONArray ad = json.optJSONArray("adaptiveFormats");
            if (ad != null) {
                String audio140 = null, audio139 = null, audioAny = null;
                for (int i = 0; i < ad.length(); i++) {
                    JSONObject f = ad.optJSONObject(i);
                    if (f == null) continue;
                    String type = optJsonStr(f, "type");
                    String url = optJsonStr(f, "url");
                    if (type == null || url == null) continue;
                    String itag = String.valueOf(f.opt("itag"));
                    if (type.startsWith("video/mp4") && type.contains("avc1")) {
                        slotAdaptiveVideo(out, itag, optJsonStr(f, "resolution"), url);
                    } else if (type.startsWith("audio/mp4")) {
                        if ("140".equals(itag)) audio140 = url;
                        else if ("139".equals(itag)) audio139 = url;
                        else if (audioAny == null) audioAny = url;
                    }
                }
                out.audioUrl = audio140 != null ? audio140
                        : (audio139 != null ? audio139 : audioAny);
            }
            JSONArray fs = json.optJSONArray("formatStreams");
            if (fs != null) {
                for (int i = 0; i < fs.length(); i++) {
                    JSONObject f = fs.optJSONObject(i);
                    if (f == null) continue;
                    String type = optJsonStr(f, "type");
                    String url = optJsonStr(f, "url");
                    if (type == null || url == null) continue;
                    if (!type.startsWith("video/mp4")) continue; // no FLV/WebM
                    slotDirectVideo(out, String.valueOf(f.opt("itag")),
                            optJsonStr(f, "resolution"), url);
                }
            }
            if (!out.hasVideo() && !out.hasDirect() && out.audioUrl == null) return null;
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Slot one adaptive avc1 video stream: itag first, resolution label second. */
    private static void slotAdaptiveVideo(YtStreams out, String itag, String res, String url) {
        if ("137".equals(itag) || "138".equals(itag)) { out.video1080 = url; return; }
        if ("136".equals(itag)) { out.video720 = url; return; }
        if ("135".equals(itag)) { out.video480 = url; return; }
        if ("134".equals(itag)) { out.video360 = url; return; }
        if ("133".equals(itag)) { out.video240 = url; return; }
        if ("160".equals(itag)) { out.video144 = url; return; }
        // Unknown itag: fall back to the resolution label — standard
        // height labels for landscape, width labels for vertical video.
        if ("1080p".equals(res) || "1920p".equals(res)) out.video1080 = url;
        else if ("720p".equals(res) || "1280p".equals(res)) out.video720 = url;
        else if ("480p".equals(res) || "854p".equals(res)) out.video480 = url;
        else if ("360p".equals(res) || "640p".equals(res)) out.video360 = url;
        else if ("240p".equals(res) || "426p".equals(res)) out.video240 = url;
        else if ("144p".equals(res) || "256p".equals(res)) out.video144 = url;
    }

    /** Slot one progressive (audio+video) MP4 stream: itag first, resolution second. */
    private static void slotDirectVideo(YtStreams out, String itag, String res, String url) {
        if ("22".equals(itag)) { out.direct720 = url; return; }
        if ("18".equals(itag)) { out.direct360 = url; return; }
        if ("37".equals(itag)) { out.direct1080 = url; return; }
        if ("1080p".equals(res) || "1920p".equals(res)) out.direct1080 = url;
        else if ("720p".equals(res) || "1280p".equals(res)) out.direct720 = url;
        else if ("360p".equals(res) || "640p".equals(res)) out.direct360 = url;
    }

    private static String optJsonStr(JSONObject o, String key) {
        if (o == null || key == null) return null;
        try {
            Object v = o.opt(key);
            if (v == null || v == JSONObject.NULL) return null;
            String s = String.valueOf(v);
            return s.isEmpty() ? null : s;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String shortLabel(String label, String kind, String note) {
        if ("audio".equals(kind)) return "MP3";
        String n = note == null ? "" : note.toLowerCase(Locale.US);
        if (n.contains("with watermark")) return "WM";
        String l = label == null ? "" : label.toLowerCase(Locale.US);
        if (l.contains("2160") || l.contains("4k")) return "4K";
        if (l.contains("1080") || l.contains("720") || l.equals("hd")) return "HD";
        if (l.contains("480") || l.contains("360") || l.equals("sd")) return "SD";
        if (l.contains("mp4") || l.contains("video")) return "MP4";
        if (l.isEmpty()) return "MP4";
        String first = l.trim().split("\\s+")[0].toUpperCase(Locale.US);
        if (first.length() > 6) first = first.substring(0, 6);
        return first.isEmpty() ? "MP4" : first;
    }

    // ---- v1.8: idle-state hint under the orb ----
    /** Exact hint line shown under the orb in the idle state (the user's words). */
    public static final String IDLE_HINT =
            "Copy video link and then tap on ball to download video";
    /** Size that keeps the 54-char hint on ONE line on a ~400dp-wide phone. */
    public static final float IDLE_HINT_SIZE_SP = 11f;

    /**
     * Rough width estimate for a line of text, in dp: mixed-case Roboto
     * averages ~0.5em per character. Used to pick the hint size: at
     * 11sp the 54-char hint is ~297dp, comfortably inside the ~352dp
     * usable width (a ~400dp screen minus the centre block's 24dp
     * margins on each side); at the normal 13sp status size it would
     * be ~351dp — right at the edge, so it would wrap or clip. The UI
     * also shrinks it at runtime if a large font scale makes it too
     * wide anyway (see MainActivity.showIdleHint). Never throws.
     */
    public static float estimateTextWidthDp(String text, float sp) {
        if (text == null || sp <= 0) return 0;
        return text.length() * sp * 0.5f;
    }

    // ---- v1.9: custom orb photo (long-press the orb) ----
    /** Stored orb photo file name (app-private internal storage). */
    public static final String ORB_PHOTO_FILE = "orb_photo.jpg";
    /** SharedPreferences flag: a custom orb photo is set. */
    public static final String ORB_PHOTO_PREF = "orb_photo_set";
    /** Max stored photo dimension in px (square, centre-cropped). */
    public static final int ORB_PHOTO_MAX_PX = 512;
    /** JPEG quality for the stored photo. */
    public static final int ORB_PHOTO_JPEG_QUALITY = 85;

    /**
     * Centre square crop of a w x h image: {left, top, size}. The orb
     * is a circle, so the photo is cropped to the largest centred
     * square before it is drawn (and stored). Returns {0,0,0} for
     * non-positive dimensions. The rect always stays inside the
     * image. Never throws.
     */
    public static int[] centreCropRect(int w, int h) {
        if (w <= 0 || h <= 0) return new int[]{0, 0, 0};
        int size = Math.min(w, h);
        return new int[]{(w - size) / 2, (h - size) / 2, size};
    }

    /**
     * BitmapFactory inSampleSize (power of 2) for decoding an image of
     * w x h so its decoded size stays near maxPx on the long side: the
     * largest power of 2 that keeps both half-dimensions >= maxPx (the
     * standard Android recipe). A 4000x3000 camera photo with maxPx
     * 512 decodes at 1000x750 instead of full size, so picking a huge
     * photo can never OOM the app; the result is still downscaled to
     * maxPx afterwards. Returns 1 for small images or bad input.
     * Never throws.
     */
    public static int inSampleSize(int w, int h, int maxPx) {
        if (w <= 0 || h <= 0 || maxPx <= 0) return 1;
        int sample = 1;
        int halfW = w / 2, halfH = h / 2;
        while (halfW / sample >= maxPx && halfH / sample >= maxPx) sample *= 2;
        return sample;
    }

    /**
     * True when a custom orb photo is actually usable: the preference
     * says one is set AND the stored file still exists. If the file
     * was deleted (or never written), the app falls back silently to
     * the green orb instead of showing a broken photo. Never throws.
     */
    public static boolean hasOrbPhoto(boolean prefSet, boolean fileExists) {
        return prefSet && fileExists;
    }

    // ---- v1.10: video thumbnails in the VIDEOS list ----
    /** Thumbnail size in dp (square) at the start of each VIDEOS card. */
    public static final int THUMB_SIZE_DP = 56;
    /** Max thumbnails kept in the in-memory LRU cache. */
    public static final int THUMB_MEM_MAX = 32;
    /** Max thumbnails kept in the on-disk cache (oldest deleted first). */
    public static final int THUMB_DISK_MAX = 100;
    /** Disk cache subfolder inside the app cache dir. */
    public static final String THUMB_CACHE_DIR = "thumbs";

    /**
     * Stable, filename-safe cache key for a video thumbnail: a 64-bit
     * FNV-1a hash of "uri|size|lastModified", rendered as 16 hex
     * digits. The size and last-modified are part of the key so a
     * file replaced at the same URI (same path, new content) does not
     * keep showing the old video's thumbnail; when the size or
     * last-modified cannot be resolved the caller passes -1 and the
     * key still works (URI alone). Deterministic across runs — the
     * same input always gives the same key, so the disk cache survives
     * restarts. Never throws, never returns null.
     */
    public static String thumbCacheKey(String uri, long size, long lastModified) {
        try {
            String input = (uri == null ? "" : uri) + "|" + size + "|" + lastModified;
            long hash = 0xcbf29ce484222325L; // FNV-1a 64-bit offset basis
            for (int i = 0; i < input.length(); i++) {
                hash ^= input.charAt(i);
                hash *= 0x100000001b3L; // FNV-1a 64-bit prime
            }
            String hex = Long.toHexString(hash);
            while (hex.length() < 16) hex = "0" + hex;
            return hex;
        } catch (Exception ignored) {
            return "0000000000000000";
        }
    }

    /**
     * Which frame to extract for a thumbnail, in microseconds: 1
     * second into the video, or 10% of the duration when the video is
     * very short (under 2 seconds — a 1-second frame would be past
     * the end of a 1.5s clip). Unknown duration (<= 0) falls back to
     * 1 second; the caller falls back to frame 0 if that returns
     * nothing. The result is always >= 0. Never throws.
     */
    public static long thumbFrameTimeUs(long durationMs) {
        if (durationMs <= 0) return 1_000_000L;
        if (durationMs < 2000) return durationMs * 100L; // 10% in microseconds
        return 1_000_000L;
    }

    // ---- v1.12: the v1.11 "Auto download" floating icon is gone
    // (Play Protect blocked the v1.11 install) — its constants and
    // helpers (AUTO_DOWNLOAD_PREF, BUBBLE_*, isBubbleVideoLink,
    // formatRank) were removed with it. Only the concurrent
    // download cap stays: it is the orb flow's cap (v1.7).

    /** Max concurrent downloads in the orb flow (v1.7). */
    public static final int MAX_DOWNLOADS = 4;
}
