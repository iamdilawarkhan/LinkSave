import com.sun.net.httpserver.HttpServer;
import com.user.linksave.FastDownload;
import com.user.linksave.Format;
import com.user.linksave.LinkUtil;
import com.user.linksave.UpdateChecker;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** JVM harness for LinkUtil (run with android.jar on the classpath for org.json). */
public class LinkUtilTest {
    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) {
        if (ok) { passed++; }
        else { failed++; System.out.println("FAIL: " + name); }
    }

    static JSONObject load(String path) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(Paths.get(path)), "UTF-8"));
    }

    static JSONObject fmt(String itag, String type, String res, String url) throws Exception {
        JSONObject o = new JSONObject();
        o.put("itag", itag);
        o.put("type", type);
        if (res != null) o.put("resolution", res);
        o.put("url", url);
        return o;
    }

    public static void main(String[] args) throws Exception {
        // --- Invidious parsing on real saved responses ---
        LinkUtil.YtStreams s = LinkUtil.parseInvidiousFormats(load("/tmp/yt-test/inv_f61NRqRJlxo.json"));
        check("real f61: parsed", s != null);
        check("real f61: title", s != null && s.title != null && s.title.contains("Rainy Night"));
        check("real f61: hasVideo", s != null && s.hasVideo());
        check("real f61: video720", s != null && s.video720 != null && s.video720.contains("googlevideo.com"));
        check("real f61: video360", s != null && s.video360 != null);
        check("real f61: audio is itag140", s != null && s.audioUrl != null && s.audioUrl.contains("mime=audio"));
        check("real f61: bestHd=720", s != null && s.video720 != null && s.bestHd().equals(s.video720));
        check("real f61: bestSd=360", s != null && s.video360 != null && s.bestSd().equals(s.video360));

        LinkUtil.YtStreams t = LinkUtil.parseInvidiousFormats(load("/tmp/yt-test/inv-video.json"));
        check("real trailer: parsed", t != null);
        check("real trailer: title", t != null && t.title != null && t.title.contains("Maatrubhumi"));
        check("real trailer: hasVideo", t != null && t.hasVideo());
        check("real trailer: audioUrl", t != null && t.audioUrl != null);

        // --- null / empty / malformed input never throws ---
        check("null json -> null", LinkUtil.parseInvidiousFormats(null) == null);
        check("empty json -> null", LinkUtil.parseInvidiousFormats(new JSONObject()) == null);
        check("no adaptive -> null",
                LinkUtil.parseInvidiousFormats(new JSONObject("{\"title\":\"x\"}")) == null);
        check("empty adaptive -> null",
                LinkUtil.parseInvidiousFormats(new JSONObject("{\"adaptiveFormats\":[]}")) == null);

        // --- audio preference: 140 > 139 > any ---
        JSONObject j = new JSONObject();
        JSONArray a = new JSONArray();
        a.put(fmt("139", "audio/mp4; codecs=\"mp4a.40.5\"", null, "https://a/139"));
        a.put(fmt("140", "audio/mp4; codecs=\"mp4a.40.2\"", null, "https://a/140"));
        a.put(fmt("251", "audio/webm; codecs=\"opus\"", null, "https://a/251"));
        j.put("adaptiveFormats", a);
        LinkUtil.YtStreams au = LinkUtil.parseInvidiousFormats(j);
        check("audio prefers 140", au != null && "https://a/140".equals(au.audioUrl));
        check("audio-only hasVideo false", au != null && !au.hasVideo());

        JSONObject j2 = new JSONObject();
        JSONArray a2 = new JSONArray();
        a2.put(fmt("139", "audio/mp4; codecs=\"mp4a.40.5\"", null, "https://a/139"));
        j2.put("adaptiveFormats", a2);
        LinkUtil.YtStreams au2 = LinkUtil.parseInvidiousFormats(j2);
        check("audio falls back to 139", au2 != null && "https://a/139".equals(au2.audioUrl));

        // --- video selection: only avc1 mp4 counts; bestHd/bestSd fallbacks ---
        JSONObject j3 = new JSONObject();
        JSONArray a3 = new JSONArray();
        a3.put(fmt("137", "video/mp4; codecs=\"avc1.640028\"", "1080p", "https://v/1080"));
        a3.put(fmt("248", "video/webm; codecs=\"vp9\"", "1080p", "https://v/webm"));
        a3.put(fmt("313", "video/mp4; codecs=\"av01.0.08M.08\"", "2160p", "https://v/av1"));
        j3.put("adaptiveFormats", a3);
        LinkUtil.YtStreams v3 = LinkUtil.parseInvidiousFormats(j3);
        check("1080-only: hasVideo", v3 != null && v3.hasVideo());
        check("1080-only: bestHd=1080", v3 != null && "https://v/1080".equals(v3.bestHd()));
        check("1080-only: bestSd=1080", v3 != null && "https://v/1080".equals(v3.bestSd()));
        check("webm/av1 ignored", v3 != null && v3.video144 == null && v3.video360 == null);

        JSONObject j4 = new JSONObject();
        JSONArray a4 = new JSONArray();
        a4.put(fmt("160", "video/mp4; codecs=\"avc1.4D400C\"", "144p", "https://v/144"));
        j4.put("adaptiveFormats", a4);
        LinkUtil.YtStreams v4 = LinkUtil.parseInvidiousFormats(j4);
        check("144-only: bestHd=144", v4 != null && "https://v/144".equals(v4.bestHd()));
        check("144-only: bestSd=144", v4 != null && "https://v/144".equals(v4.bestSd()));

        // --- v1.13: real YouTube Short (the user's own 4ZGWpNrSIx8) ---
        // Vertical videos report WIDTH labels (256p/426p/640p/854p/
        // 1280p/1920p), not heights — the old label-only match found
        // no video at all for Shorts, so they silently downloaded as
        // MP3. Streams are now slotted by itag first.
        LinkUtil.YtStreams sh = LinkUtil.parseInvidiousFormats(load("/tmp/yt-test/inv_shorts_4ZGWpNrSIx8.json"));
        check("real Short: parsed", sh != null);
        check("real Short: title", sh != null && sh.title != null && sh.title.contains("Purple Yam"));
        check("real Short: hasVideo", sh != null && sh.hasVideo());
        check("real Short: video720 (itag 136)", sh != null && sh.video720 != null && sh.video720.contains("googlevideo.com"));
        check("real Short: video360 (itag 134)", sh != null && sh.video360 != null);
        check("real Short: bestHd=720", sh != null && sh.video720 != null && sh.bestHd().equals(sh.video720));
        check("real Short: bestSd=360", sh != null && sh.video360 != null && sh.bestSd().equals(sh.video360));
        check("real Short: audio itag 140", sh != null && sh.audioUrl != null);

        // --- v1.13: itag slotting beats width labels (synthetic Short) ---
        JSONObject js = new JSONObject();
        JSONArray as = new JSONArray();
        as.put(fmt("136", "video/mp4; codecs=\"avc1.64001F\"", "1280p", "https://v/s720"));
        as.put(fmt("134", "video/mp4; codecs=\"avc1.4D401E\"", "640p", "https://v/s360"));
        as.put(fmt("140", "audio/mp4; codecs=\"mp4a.40.2\"", null, "https://a/140"));
        js.put("adaptiveFormats", as);
        LinkUtil.YtStreams vs = LinkUtil.parseInvidiousFormats(js);
        check("width labels: hasVideo", vs != null && vs.hasVideo());
        check("width labels: 1280p->720", vs != null && "https://v/s720".equals(vs.video720));
        check("width labels: 640p->360", vs != null && "https://v/s360".equals(vs.video360));

        // Unknown itag + width label still slots by label.
        JSONObject ju = new JSONObject();
        JSONArray aun = new JSONArray();
        aun.put(fmt("999", "video/mp4; codecs=\"avc1.64001F\"", "1280p", "https://v/u720"));
        ju.put("adaptiveFormats", aun);
        LinkUtil.YtStreams vu = LinkUtil.parseInvidiousFormats(ju);
        check("unknown itag: width label slots", vu != null && "https://v/u720".equals(vu.video720));

        // --- v1.13: progressive formatStreams fallback ---
        JSONObject jp = new JSONObject();
        JSONArray ap = new JSONArray();
        ap.put(fmt("22", "video/mp4; codecs=\"avc1.64001F, mp4a.40.2\"", "720p", "https://v/p22"));
        ap.put(fmt("18", "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"", "360p", "https://v/p18"));
        jp.put("formatStreams", ap);
        LinkUtil.YtStreams vp = LinkUtil.parseInvidiousFormats(jp);
        check("progressive: parsed without adaptive", vp != null);
        check("progressive: hasDirect", vp != null && vp.hasDirect());
        check("progressive: no adaptive video", vp != null && !vp.hasVideo());
        check("progressive: bestDirect=720", vp != null && "https://v/p22".equals(vp.bestDirect()));
        check("progressive: label HD", vp != null && "HD".equals(vp.directLabel()));

        JSONObject jp2 = new JSONObject();
        JSONArray ap2 = new JSONArray();
        ap2.put(fmt("18", "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"", "360p", "https://v/p18"));
        jp2.put("formatStreams", ap2);
        LinkUtil.YtStreams vp2 = LinkUtil.parseInvidiousFormats(jp2);
        check("progressive 360-only: label SD", vp2 != null && "SD".equals(vp2.directLabel()));

        // formatStreams FLV/WebM are ignored.
        JSONObject jf = new JSONObject();
        JSONArray af = new JSONArray();
        af.put(fmt("5", "video/x-flv", "240p", "https://v/flv"));
        jf.put("formatStreams", af);
        check("progressive flv ignored -> null", LinkUtil.parseInvidiousFormats(jf) == null);

        // --- v1.13: audio-only guard (never silently auto-download MP3) ---
        check("audio guard: null", !LinkUtil.isAudioOnlySingle(null));
        check("audio guard: empty", !LinkUtil.isAudioOnlySingle(new ArrayList<Format>()));
        List<Format> oneAudio = new ArrayList<>();
        oneAudio.add(new Format("ytmp3", "MP3", "audio", "Audio only", "https://a/140"));
        check("audio guard: single MP3", LinkUtil.isAudioOnlySingle(oneAudio));
        List<Format> oneVideo = new ArrayList<>();
        oneVideo.add(new Format("ythd", "HD", "ytmerge", "Video + audio", "https://v/720"));
        check("audio guard: single video false", !LinkUtil.isAudioOnlySingle(oneVideo));
        List<Format> two = new ArrayList<>(oneVideo);
        two.add(new Format("ytmp3", "MP3", "audio", "Audio only", "https://a/140"));
        check("audio guard: two formats false", !LinkUtil.isAudioOnlySingle(two));

        // --- v1.13: selective delete helpers ---
        check("delKey uri wins", "u:content://x/1".equals(LinkUtil.deleteKey("content://x/1", 42)));
        check("delKey dm fallback", "d:42".equals(LinkUtil.deleteKey("", 42)));
        check("delKey null uri", "d:-1".equals(LinkUtil.deleteKey(null, -1)));
        List<JSONObject> recs = new ArrayList<>();
        recs.add(new JSONObject("{\"uri\":\"content://x/1\",\"dmId\":-1,\"title\":\"a\"}"));
        recs.add(new JSONObject("{\"uri\":\"\",\"dmId\":7,\"title\":\"b\"}"));
        recs.add(new JSONObject("{\"uri\":\"content://x/3\",\"dmId\":-1,\"title\":\"c\"}"));
        Set<String> sel = new HashSet<>();
        sel.add("u:content://x/1");
        sel.add("d:7");
        List<JSONObject> kept = LinkUtil.removeSelected(recs, sel);
        check("removeSelected: only selected gone", kept.size() == 1 && "c".equals(kept.get(0).optString("title")));
        check("removeSelected: empty selection keeps all", LinkUtil.removeSelected(recs, new HashSet<String>()).size() == 3);
        check("removeSelected: null selection keeps all", LinkUtil.removeSelected(recs, null).size() == 3);
        check("removeSelected: null list", LinkUtil.removeSelected(null, sel).isEmpty());

        // --- existing helpers (spot checks) ---
        check("detect youtube", "youtube".equals(LinkUtil.detectPlatform("https://youtu.be/fA8-gXDoAk8?si=x")));
        check("detect youtube watch", "youtube".equals(LinkUtil.detectPlatform("https://www.youtube.com/watch?v=f61NRqRJlxo")));
        check("detect tiktok", "tiktok".equals(LinkUtil.detectPlatform("https://www.tiktok.com/@x/video/123")));
        check("detect fb", "facebook".equals(LinkUtil.detectPlatform("https://www.facebook.com/watch/?v=1")));
        check("detect ig", "instagram".equals(LinkUtil.detectPlatform("https://www.instagram.com/reel/abc/")));
        check("detect null", LinkUtil.detectPlatform(null) == null);
        check("detect garbage", LinkUtil.detectPlatform("hello") == null);
        check("yt id youtu.be", "fA8-gXDoAk8".equals(LinkUtil.youtubeId("https://youtu.be/fA8-gXDoAk8?si=a_j5EmcTS")));
        check("yt id shorts", "abc123XYZ09".equals(LinkUtil.youtubeId("https://www.youtube.com/shorts/abc123XYZ09")));
        check("yt id null", LinkUtil.youtubeId(null) == null);
        check("safeFileName", "Rainy_Night".equals(LinkUtil.safeFileName("Rainy Night!")));
        check("safeFileName empty", "video".equals(LinkUtil.safeFileName("!!!")));
        check("shortLabel HD", "HD".equals(LinkUtil.shortLabel("HD", "video", null)));
        check("shortLabel MP3", "MP3".equals(LinkUtil.shortLabel("x", "audio", null)));
        check("shortLabel WM", "WM".equals(LinkUtil.shortLabel("SD", "video", "With watermark")));
        check("formatMB", "31.9".equals(LinkUtil.formatMB(33456128L)));
        check("metaContent", "https://x/v.mp4".equals(LinkUtil.metaContent(
                "<meta property=\"og:video\" content=\"https://x/v.mp4\">", "og:video")));
        check("metaContent null", LinkUtil.metaContent(null, "og:video") == null);
        check("ig shortcode", "ABC123".equals(LinkUtil.instagramShortcode("https://www.instagram.com/reel/ABC123/")));

        // --- v1.4: extractUrl (clipboard often has text around the link) ---
        check("extractUrl plain", "https://www.instagram.com/reel/ABC123/".equals(
                LinkUtil.extractUrl("https://www.instagram.com/reel/ABC123/")));
        check("extractUrl wrapped", "https://www.instagram.com/reel/ABC123/?igsh=xyz".equals(
                LinkUtil.extractUrl("Check this out!\nhttps://www.instagram.com/reel/ABC123/?igsh=xyz\nso good")));
        check("extractUrl trailing punct", "https://youtu.be/fA8-gXDoAk8".equals(
                LinkUtil.extractUrl("watch this: https://youtu.be/fA8-gXDoAk8.")));
        check("extractUrl parens", "https://www.instagram.com/p/AbC_12-3/".equals(
                LinkUtil.extractUrl("(https://www.instagram.com/p/AbC_12-3/)")));
        check("extractUrl none", LinkUtil.extractUrl("no link here at all") == null);
        check("extractUrl null", LinkUtil.extractUrl(null) == null);
        check("extractUrl first wins", "https://a.com/1".equals(
                LinkUtil.extractUrl("https://a.com/1 and https://b.com/2")));

        // --- v1.4: Instagram shortcode — every shape users actually copy ---
        check("ig reel", "DTQXTbvjp4y".equals(LinkUtil.instagramShortcode(
                "https://www.instagram.com/reel/DTQXTbvjp4y/")));
        check("ig reels", "DTQXTbvjp4y".equals(LinkUtil.instagramShortcode(
                "https://www.instagram.com/reels/DTQXTbvjp4y/")));
        check("ig p", "AbC_12-3".equals(LinkUtil.instagramShortcode(
                "https://www.instagram.com/p/AbC_12-3/")));
        check("ig tv", "AbC_12-3".equals(LinkUtil.instagramShortcode(
                "https://www.instagram.com/tv/AbC_12-3/")));
        check("ig share/reel", "DTQXTbvjp4y".equals(LinkUtil.instagramShortcode(
                "https://www.instagram.com/share/reel/DTQXTbvjp4y")));
        check("ig igsh params", "DTQXTbvjp4y".equals(LinkUtil.instagramShortcode(
                "https://www.instagram.com/reel/DTQXTbvjp4y/?igsh=MzRlODBiNWFlZA==")));
        check("ig utm params", "DTQXTbvjp4y".equals(LinkUtil.instagramShortcode(
                "https://www.instagram.com/reel/DTQXTbvjp4y/?utm_source=ig_web_copy_link&igsh=abc123")));
        check("ig no www", "DTQXTbvjp4y".equals(LinkUtil.instagramShortcode(
                "https://instagram.com/reel/DTQXTbvjp4y")));
        check("ig mobile host", "DTQXTbvjp4y".equals(LinkUtil.instagramShortcode(
                "https://m.instagram.com/reel/DTQXTbvjp4y/")));
        check("ig wrapped text", "DTQXTbvjp4y".equals(LinkUtil.instagramShortcode(
                "Look!\nhttps://www.instagram.com/reel/DTQXTbvjp4y/?igsh=xyz\n")));
        check("ig whitespace", "DTQXTbvjp4y".equals(LinkUtil.instagramShortcode(
                "  https://www.instagram.com/reel/DTQXTbvjp4y/ \n")));
        check("ig short link no code", LinkUtil.instagramShortcode("https://ig.me/j/abc") == null);
        check("ig null", LinkUtil.instagramShortcode(null) == null);
        check("ig non-ig", LinkUtil.instagramShortcode("https://www.tiktok.com/@x/video/123") == null);

        // --- v1.4: detectPlatform on short hosts + wrapped links ---
        check("detect ig.me", "instagram".equals(LinkUtil.detectPlatform("https://ig.me/j/abc123")));
        check("detect instagr.am", "instagram".equals(LinkUtil.detectPlatform("https://instagr.am/p/AbC_12-3/")));
        check("detect m.ig", "instagram".equals(LinkUtil.detectPlatform("https://m.instagram.com/reel/DTQXTbvjp4y/")));
        check("detect wrapped ig", "instagram".equals(LinkUtil.detectPlatform(
                "hi https://www.instagram.com/reel/DTQXTbvjp4y/ bye")));
        check("yt id wrapped", "fA8-gXDoAk8".equals(LinkUtil.youtubeId(
                "watch: https://youtu.be/fA8-gXDoAk8?si=x ok")));

        // --- v1.4: curl-x page parsing on the real saved page ---
        String cxPage = new String(Files.readAllBytes(
                Paths.get("/tmp/ig-test/cx_DTQXTbvjp4y.bin")), "UTF-8");
        String cdn = LinkUtil.extractCurlxVideoUrl(cxPage);
        check("curlx real page: cdn found", cdn != null);
        check("curlx real page: cdn host", cdn != null && cdn.contains("cdninstagram.com"));
        check("curlx real page: mp4", cdn != null && cdn.contains(".mp4"));
        check("curlx real page: unescaped", cdn != null && !cdn.contains("&amp;") && !cdn.contains("\\/"));
        check("curlx real page: https", cdn != null && cdn.startsWith("https://"));
        String cxPage2 = new String(Files.readAllBytes(
                Paths.get("/tmp/ig-test/cx_DXnnbbxk_hO.bin")), "UTF-8");
        String cdn2 = LinkUtil.extractCurlxVideoUrl(cxPage2);
        check("curlx 2nd reel: cdn found", cdn2 != null && cdn2.contains(".mp4"));
        check("curlx empty html", LinkUtil.extractCurlxVideoUrl("") == null);
        check("curlx no video html", LinkUtil.extractCurlxVideoUrl(
                "<html><head><title>hello</title></head><body>no video here</body></html>") == null);
        check("curlx null", LinkUtil.extractCurlxVideoUrl(null) == null);
        check("curlx escaped json", "https://scontent-x.cdninstagram.com/v/abc.mp4?a=1&b=2".equals(
                LinkUtil.extractCurlxVideoUrl(
                        "{\"url\":\"https:\\/\\/scontent-x.cdninstagram.com\\/v\\/abc.mp4?a=1\\u0026b=2\"}")));

        // --- v1.5: share-intent text — what ACTION_SEND EXTRA_TEXT looks like ---
        // YouTube shares "Title\nURL"; TikTok/IG share captions around the URL.
        check("share yt title+url", "https://youtu.be/fA8-gXDoAk8?si=a_j5EmcTS".equals(LinkUtil.extractUrl(
                "Maatrubhumi | Official Trailer\nhttps://youtu.be/fA8-gXDoAk8?si=a_j5EmcTS")));
        check("share yt platform", "youtube".equals(LinkUtil.detectPlatform(
                "Maatrubhumi | Official Trailer\nhttps://youtu.be/fA8-gXDoAk8?si=x")));
        check("share yt id", "fA8-gXDoAk8".equals(LinkUtil.youtubeId(
                "Maatrubhumi\nhttps://youtu.be/fA8-gXDoAk8?si=x")));
        check("share ig caption", "https://www.instagram.com/reel/DTQXTbvjp4y/?igsh=xyz".equals(
                LinkUtil.extractUrl("Watch this reel! https://www.instagram.com/reel/DTQXTbvjp4y/?igsh=xyz")));
        check("share ig shortcode", "DTQXTbvjp4y".equals(LinkUtil.instagramShortcode(
                "Watch this reel! https://www.instagram.com/reel/DTQXTbvjp4y/?igsh=xyz")));
        check("share tiktok", "tiktok".equals(LinkUtil.detectPlatform(
                "Check out this video! https://vm.tiktok.com/AbC123/ #fun")));
        check("share fb", "facebook".equals(LinkUtil.detectPlatform(
                "https://fb.watch/xyz123/")));
        check("share no url", LinkUtil.extractUrl("just some text, no link") == null);
        check("share empty", LinkUtil.extractUrl("") == null);

        // --- v1.4: vxinstagram og:video extraction (metaContent, both orders) ---
        String vxPage = new String(Files.readAllBytes(
                Paths.get("/tmp/ig-test/vxi.bin")), "UTF-8");
        check("vx og:video", "https://www.vxinstagram.com/offload/DTQXTbvjp4y/0.mp4".equals(
                LinkUtil.metaContent(vxPage, "og:video")));
        check("vx og:video:secure_url", "https://www.vxinstagram.com/offload/DTQXTbvjp4y/0.mp4".equals(
                LinkUtil.metaContent(vxPage, "og:video:secure_url")));

        // --- v1.6: smart-flow decision (0 -> error, 1 -> auto-download, 2+ -> choose) ---
        check("flow -1 -> error", LinkUtil.flowForOptionCount(-1) == LinkUtil.FLOW_ERROR);
        check("flow 0 -> error", LinkUtil.flowForOptionCount(0) == LinkUtil.FLOW_ERROR);
        check("flow 1 -> auto", LinkUtil.flowForOptionCount(1) == LinkUtil.FLOW_AUTO_DOWNLOAD);
        check("flow 2 -> choose", LinkUtil.flowForOptionCount(2) == LinkUtil.FLOW_CHOOSE_QUALITY);
        check("flow 3 -> choose", LinkUtil.flowForOptionCount(3) == LinkUtil.FLOW_CHOOSE_QUALITY);
        check("flow 10 -> choose", LinkUtil.flowForOptionCount(10) == LinkUtil.FLOW_CHOOSE_QUALITY);
        check("flow constants distinct",
                LinkUtil.FLOW_ERROR != LinkUtil.FLOW_AUTO_DOWNLOAD
                        && LinkUtil.FLOW_AUTO_DOWNLOAD != LinkUtil.FLOW_CHOOSE_QUALITY
                        && LinkUtil.FLOW_ERROR != LinkUtil.FLOW_CHOOSE_QUALITY);

        // --- v1.6: countValidUrls — same rule the pill UI uses to skip entries ---
        check("count null -> 0", LinkUtil.countValidUrls(null) == 0);
        check("count empty -> 0", LinkUtil.countValidUrls(new ArrayList<String>()) == 0);
        check("count all invalid -> 0",
                LinkUtil.countValidUrls(Arrays.asList(null, "", null, "")) == 0);
        check("count one valid -> 1",
                LinkUtil.countValidUrls(Arrays.asList("https://cdn/x.mp4")) == 1);
        check("count mixed -> 2",
                LinkUtil.countValidUrls(Arrays.asList(null, "", "https://cdn/hd.mp4",
                        "https://cdn/sd.mp4")) == 2);
        check("count three valid -> 3",
                LinkUtil.countValidUrls(Arrays.asList("https://a/1", "https://a/2", "https://a/3")) == 3);

        // --- v1.6: decision + count together, as showResult uses them ---
        List<String> single = Arrays.asList("https://cdninstagram.com/v/reel.mp4");
        check("single option -> auto",
                LinkUtil.flowForOptionCount(LinkUtil.countValidUrls(single))
                        == LinkUtil.FLOW_AUTO_DOWNLOAD);
        List<String> multi = Arrays.asList("https://cdn/hd.mp4", null, "", "https://cdn/sd.mp4",
                "https://cdn/audio.mp3");
        check("multi option -> choose",
                LinkUtil.flowForOptionCount(LinkUtil.countValidUrls(multi))
                        == LinkUtil.FLOW_CHOOSE_QUALITY);
        List<String> none = Arrays.asList(null, "");
        check("no usable option -> error",
                LinkUtil.flowForOptionCount(LinkUtil.countValidUrls(none))
                        == LinkUtil.FLOW_ERROR);

        // --- v1.7: orb-tap decisions (same link cancels, new link runs in parallel) ---
        check("tap same downloading -> cancel",
                LinkUtil.orbTapAction(true, LinkUtil.JOB_DOWNLOADING, 1, 4) == LinkUtil.TAP_CANCEL);
        check("tap same fetching -> cancel",
                LinkUtil.orbTapAction(true, LinkUtil.JOB_FETCHING, 0, 4) == LinkUtil.TAP_CANCEL);
        check("tap same choosing -> ignore",
                LinkUtil.orbTapAction(true, LinkUtil.JOB_CHOOSING, 1, 4) == LinkUtil.TAP_IGNORE);
        check("tap new link, idle -> start",
                LinkUtil.orbTapAction(false, -1, 0, 4) == LinkUtil.TAP_START);
        check("tap new link, 1 running -> start (parallel)",
                LinkUtil.orbTapAction(false, -1, 1, 4) == LinkUtil.TAP_START);
        check("tap new link, 3 running -> start (parallel)",
                LinkUtil.orbTapAction(false, -1, 3, 4) == LinkUtil.TAP_START);
        check("tap new link, at cap -> cap",
                LinkUtil.orbTapAction(false, -1, 4, 4) == LinkUtil.TAP_CAP);
        check("tap new link, over cap -> cap",
                LinkUtil.orbTapAction(false, -1, 5, 4) == LinkUtil.TAP_CAP);
        check("tap cancel wins over cap",
                LinkUtil.orbTapAction(true, LinkUtil.JOB_DOWNLOADING, 4, 4) == LinkUtil.TAP_CANCEL);
        check("tap no cap (0) -> start",
                LinkUtil.orbTapAction(false, -1, 9, 0) == LinkUtil.TAP_START);
        // Duplicate start prevented: an active link never returns TAP_START.
        check("tap active never starts (fetching)",
                LinkUtil.orbTapAction(true, LinkUtil.JOB_FETCHING, 0, 4) != LinkUtil.TAP_START);
        check("tap active never starts (choosing)",
                LinkUtil.orbTapAction(true, LinkUtil.JOB_CHOOSING, 0, 4) != LinkUtil.TAP_START);
        check("tap active never starts (downloading)",
                LinkUtil.orbTapAction(true, LinkUtil.JOB_DOWNLOADING, 0, 4) != LinkUtil.TAP_START);
        check("tap constants distinct",
                LinkUtil.TAP_START != LinkUtil.TAP_CANCEL
                        && LinkUtil.TAP_CANCEL != LinkUtil.TAP_IGNORE
                        && LinkUtil.TAP_IGNORE != LinkUtil.TAP_CAP
                        && LinkUtil.TAP_START != LinkUtil.TAP_CAP);

        // --- v1.7: isIdle — cancelling the last download goes back to idle ---
        check("idle empty", LinkUtil.isIdle(0, 0, false));
        check("not idle: 1 download", !LinkUtil.isIdle(1, 0, false));
        check("not idle: 2 downloads", !LinkUtil.isIdle(2, 0, false));
        check("not idle: 1 fetch", !LinkUtil.isIdle(0, 1, false));
        check("not idle: choosing", !LinkUtil.isIdle(0, 0, true));
        // Cancel the last of 2 downloads -> 1 left -> still busy.
        check("cancel 1 of 2 -> still busy", !LinkUtil.isIdle(2 - 1, 0, false));
        // Cancel the last download -> idle.
        check("cancel last download -> idle", LinkUtil.isIdle(1 - 1, 0, false));

        // --- v1.7: aggregate progress across parallel downloads ---
        LinkUtil.Aggregate agg;
        agg = LinkUtil.aggregateProgress(null, null);
        check("agg null -> unknown", agg.pct == -1 && !agg.known && agg.done == 0 && agg.total == 0);
        agg = LinkUtil.aggregateProgress(new long[0], new long[0]);
        check("agg empty -> unknown", agg.pct == -1 && !agg.known);
        agg = LinkUtil.aggregateProgress(new long[]{5}, new long[]{10});
        check("agg single 50%", agg.pct == 50 && agg.known && agg.done == 5 && agg.total == 10);
        agg = LinkUtil.aggregateProgress(new long[]{3, 7}, new long[]{10, 10});
        check("agg two 50%", agg.pct == 50 && agg.done == 10 && agg.total == 20);
        agg = LinkUtil.aggregateProgress(new long[]{5, 7}, new long[]{10, -1});
        check("agg one unknown total", agg.pct == 100 && agg.known && agg.done == 12 && agg.total == 10);
        agg = LinkUtil.aggregateProgress(new long[]{5, 7}, new long[]{-1, -1});
        check("agg all unknown", agg.pct == -1 && !agg.known && agg.done == 12 && agg.total == 0);
        agg = LinkUtil.aggregateProgress(new long[]{15}, new long[]{10});
        check("agg done>total caps at 100", agg.pct == 100);
        agg = LinkUtil.aggregateProgress(new long[]{-5, 5}, new long[]{10, 10});
        check("agg negative done clamped", agg.pct == 25 && agg.done == 5);
        agg = LinkUtil.aggregateProgress(new long[]{5, 5, 5}, new long[]{10, 10});
        check("agg mismatched lengths", agg.pct == 50 && agg.done == 10 && agg.total == 20);
        // MB-scale numbers, as the UI shows them (40 MB of 150 MB = 26%).
        agg = LinkUtil.aggregateProgress(new long[]{31457280L, 10485760L},
                new long[]{104857600L, 52428800L});
        check("agg MB math", agg.pct == 26 && agg.done == 41943040L && agg.total == 157286400L);

        // --- v1.7: FastDownload cancel token ---
        FastDownload.CancelToken tok = new FastDownload.CancelToken();
        check("token starts uncancelled", !tok.isCancelled());
        tok.cancel();
        check("token cancelled after cancel()", tok.isCancelled());
        // A pre-cancelled token fails fast, before any network.
        boolean threwCancelled = false;
        try {
            FastDownload.download("http://127.0.0.1:9/x.mp4",
                    new java.io.File("/tmp/yt-test/cancel_probe.tmp"),
                    "test", 0, null, tok);
        } catch (Exception e) {
            threwCancelled = FastDownload.isCancelled(e);
        }
        check("pre-cancelled download throws Cancelled", threwCancelled);
        check("isCancelled null false", !FastDownload.isCancelled(null));
        check("isCancelled plain error false",
                !FastDownload.isCancelled(new Exception("HTTP 404")));
        check("isCancelled Cancelled true",
                FastDownload.isCancelled(new Exception("Cancelled")));
        // Registering a connection after cancel() disconnects it at once
        // (so a cancelled download can never open a new connection) and
        // never throws; a second cancel() is harmless.
        FastDownload.CancelToken tok2 = new FastDownload.CancelToken();
        tok2.cancel();
        boolean regOk = true;
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL("http://127.0.0.1:9/").openConnection();
            tok2.register(c);
            tok2.unregister(c);
            tok2.cancel();
        } catch (Throwable tt) { regOk = false; }
        check("token register after cancel safe", regOk);

        // --- v1.8: idle hint under the orb (exact user wording, one line) ---
        check("hint exact text", "Copy video link and then tap on ball to download video"
                .equals(LinkUtil.IDLE_HINT));
        check("hint is 54 chars", LinkUtil.IDLE_HINT.length() == 54);
        check("hint has no newline", !LinkUtil.IDLE_HINT.contains("\n"));
        check("hint size is 11sp", LinkUtil.IDLE_HINT_SIZE_SP == 11f);
        // At 11sp the hint is ~297dp wide — fits the ~352dp usable width
        // (400dp screen minus 24dp margins each side) with room to spare.
        check("hint fits one line at 11sp",
                LinkUtil.estimateTextWidthDp(LinkUtil.IDLE_HINT, 11f) <= 320f);
        // At the normal 13sp status size it would be ~351dp — right at
        // the edge, which is why the hint uses 11sp.
        check("hint too wide at 13sp",
                LinkUtil.estimateTextWidthDp(LinkUtil.IDLE_HINT, 13f) > 320f);
        check("width estimate null safe", LinkUtil.estimateTextWidthDp(null, 11f) == 0);
        check("width estimate empty safe", LinkUtil.estimateTextWidthDp("", 11f) == 0);

        // --- v1.9: custom orb photo — centre-crop math, sample size, state ---
        check("crop landscape 800x600", Arrays.equals(new int[]{100, 0, 600},
                LinkUtil.centreCropRect(800, 600)));
        check("crop portrait 600x800", Arrays.equals(new int[]{0, 100, 600},
                LinkUtil.centreCropRect(600, 800)));
        check("crop square 512", Arrays.equals(new int[]{0, 0, 512},
                LinkUtil.centreCropRect(512, 512)));
        check("crop 1x1", Arrays.equals(new int[]{0, 0, 1},
                LinkUtil.centreCropRect(1, 1)));
        check("crop very wide 1000x100", Arrays.equals(new int[]{450, 0, 100},
                LinkUtil.centreCropRect(1000, 100)));
        check("crop odd width 801x600", Arrays.equals(new int[]{100, 0, 600},
                LinkUtil.centreCropRect(801, 600)));
        check("crop zero -> empty", Arrays.equals(new int[]{0, 0, 0},
                LinkUtil.centreCropRect(0, 0)));
        check("crop negative -> empty", Arrays.equals(new int[]{0, 0, 0},
                LinkUtil.centreCropRect(-5, 100)));
        // The crop rect always stays inside the image.
        int[] cr = LinkUtil.centreCropRect(1920, 1080);
        check("crop 1080p in bounds", cr[0] + cr[2] <= 1920 && cr[1] + cr[2] <= 1080
                && cr[2] == 1080);
        check("sample small -> 1", LinkUtil.inSampleSize(400, 300, 512) == 1);
        check("sample 1024x768 -> 1", LinkUtil.inSampleSize(1024, 768, 512) == 1);
        check("sample 4000x3000 -> 4", LinkUtil.inSampleSize(4000, 3000, 512) == 4);
        check("sample 2048 square -> 4", LinkUtil.inSampleSize(2048, 2048, 512) == 4);
        check("sample 512 square -> 1", LinkUtil.inSampleSize(512, 512, 512) == 1);
        check("sample bad input -> 1", LinkUtil.inSampleSize(0, 0, 512) == 1
                && LinkUtil.inSampleSize(-5, 100, 512) == 1
                && LinkUtil.inSampleSize(100, 100, 0) == 1);
        check("photo state set+file", LinkUtil.hasOrbPhoto(true, true));
        check("photo state set, no file", !LinkUtil.hasOrbPhoto(true, false));
        check("photo state file, no pref", !LinkUtil.hasOrbPhoto(false, true));
        check("photo state neither", !LinkUtil.hasOrbPhoto(false, false));
        check("photo file name", "orb_photo.jpg".equals(LinkUtil.ORB_PHOTO_FILE));
        check("photo max px 512", LinkUtil.ORB_PHOTO_MAX_PX == 512);

        // --- v1.10: VIDEOS thumbnails — cache key + frame-time selection ---
        check("thumb size 56dp", LinkUtil.THUMB_SIZE_DP == 56);
        check("thumb mem cap 32", LinkUtil.THUMB_MEM_MAX == 32);
        check("thumb disk cap 100", LinkUtil.THUMB_DISK_MAX == 100);
        check("thumb cache dir", "thumbs".equals(LinkUtil.THUMB_CACHE_DIR));
        String k1 = LinkUtil.thumbCacheKey("content://media/1", 12345, 999);
        String k2 = LinkUtil.thumbCacheKey("content://media/1", 12345, 999);
        check("thumb key deterministic", k1 != null && k1.equals(k2));
        check("thumb key 16 hex chars", k1 != null && k1.length() == 16
                && k1.matches("[0-9a-f]+"));
        check("thumb key uri matters",
                !k1.equals(LinkUtil.thumbCacheKey("content://media/2", 12345, 999)));
        check("thumb key size matters",
                !k1.equals(LinkUtil.thumbCacheKey("content://media/1", 54321, 999)));
        check("thumb key mtime matters",
                !k1.equals(LinkUtil.thumbCacheKey("content://media/1", 12345, 111)));
        check("thumb key null uri safe",
                LinkUtil.thumbCacheKey(null, -1, -1) != null
                        && LinkUtil.thumbCacheKey(null, -1, -1).length() == 16);
        check("thumb key unknown stats safe",
                LinkUtil.thumbCacheKey("content://media/1", -1, -1) != null);
        check("thumb frame unknown -> 1s",
                LinkUtil.thumbFrameTimeUs(-1) == 1_000_000L
                        && LinkUtil.thumbFrameTimeUs(0) == 1_000_000L);
        check("thumb frame short 500ms -> 10%",
                LinkUtil.thumbFrameTimeUs(500) == 50_000L);
        check("thumb frame short 1500ms -> 10%",
                LinkUtil.thumbFrameTimeUs(1500) == 150_000L);
        check("thumb frame 1999ms -> 10%",
                LinkUtil.thumbFrameTimeUs(1999) == 199_900L);
        check("thumb frame 2000ms -> 1s",
                LinkUtil.thumbFrameTimeUs(2000) == 1_000_000L);
        check("thumb frame 10s -> 1s",
                LinkUtil.thumbFrameTimeUs(10000) == 1_000_000L);
        check("thumb frame long -> 1s",
                LinkUtil.thumbFrameTimeUs(140400) == 1_000_000L);
        check("thumb frame never negative",
                LinkUtil.thumbFrameTimeUs(1) >= 0 && LinkUtil.thumbFrameTimeUs(-100) >= 0);

        // --- v1.12: the v1.11 "Auto download" floating icon is gone
        // (Play Protect blocked the v1.11 install) — its checks
        // (toggle default, bubble durations/label, bubble link
        // validation, format ranking) were removed with it. Only
        // the orb flow's 4-download cap stays (v1.7).
        check("max downloads 4", LinkUtil.MAX_DOWNLOADS == 4);

        // --- v1.18: in-app updater (UpdateChecker) ---
        check("update: default url is the GitHub raw feed",
                "https://raw.githubusercontent.com/iamdilawarkhan/LinkSave/main/version.json"
                        .equals(UpdateChecker.DEFAULT_URL));

        // parse: valid full JSON
        UpdateChecker.Info full = UpdateChecker.parse(
                "{\"versionCode\":19,\"versionName\":\"1.18\","
                + "\"apkUrl\":\"https://github.com/iamdilawarkhan/LinkSave/releases/latest/download/LinkSave.apk\","
                + "\"whatsNew\":\"notes\"}");
        check("update parse: full", full != null && full.versionCode == 19
                && "1.18".equals(full.versionName)
                && full.apkUrl.endsWith("releases/latest/download/LinkSave.apk")
                && "notes".equals(full.whatsNew));

        // parse: minimal (only the required fields) -> empty optionals
        UpdateChecker.Info min = UpdateChecker.parse(
                "{\"versionCode\":20,\"apkUrl\":\"https://x/y.apk\"}");
        check("update parse: minimal", min != null && min.versionCode == 20
                && "".equals(min.versionName) && "".equals(min.whatsNew));

        // parse: every bad input -> null, never throws
        check("update parse: null", UpdateChecker.parse(null) == null);
        check("update parse: empty", UpdateChecker.parse("") == null);
        check("update parse: blank", UpdateChecker.parse("   ") == null);
        check("update parse: garbage", UpdateChecker.parse("not json") == null);
        check("update parse: html page", UpdateChecker.parse("<html>404</html>") == null);
        check("update parse: empty obj", UpdateChecker.parse("{}") == null);
        check("update parse: no versionCode",
                UpdateChecker.parse("{\"apkUrl\":\"https://x/y.apk\"}") == null);
        check("update parse: versionCode 0",
                UpdateChecker.parse("{\"versionCode\":0,\"apkUrl\":\"https://x/y.apk\"}") == null);
        check("update parse: versionCode -1",
                UpdateChecker.parse("{\"versionCode\":-1,\"apkUrl\":\"https://x/y.apk\"}") == null);
        check("update parse: no apkUrl",
                UpdateChecker.parse("{\"versionCode\":19}") == null);
        check("update parse: empty apkUrl",
                UpdateChecker.parse("{\"versionCode\":19,\"apkUrl\":\"\"}") == null);
        check("update parse: ftp apkUrl rejected",
                UpdateChecker.parse("{\"versionCode\":19,\"apkUrl\":\"ftp://x/y.apk\"}") == null);
        check("update parse: relative apkUrl rejected",
                UpdateChecker.parse("{\"versionCode\":19,\"apkUrl\":\"/y.apk\"}") == null);
        check("update parse: http apkUrl allowed (local test server)",
                UpdateChecker.parse("{\"versionCode\":19,\"apkUrl\":\"http://127.0.0.1:9/y.apk\"}") != null);
        check("update parse: extra fields ignored",
                UpdateChecker.parse("{\"versionCode\":19,\"apkUrl\":\"https://x/y.apk\",\"zzz\":1}") != null);

        // isNewer
        check("update isNewer: 19 > 18", UpdateChecker.isNewer(full, 18));
        check("update isNewer: 19 == 19 false", !UpdateChecker.isNewer(full, 19));
        check("update isNewer: 19 > 20 false", !UpdateChecker.isNewer(full, 20));
        check("update isNewer: null false", !UpdateChecker.isNewer(null, 18));

        // shouldCheck: the 24h gate
        long nowMs = System.currentTimeMillis();
        long dayMs = 24L * 60 * 60 * 1000;
        check("update gate: never checked", UpdateChecker.shouldCheck(0, nowMs));
        check("update gate: just checked", !UpdateChecker.shouldCheck(nowMs, nowMs));
        check("update gate: 23h ago", !UpdateChecker.shouldCheck(nowMs - 23 * 3600_000L, nowMs));
        check("update gate: 25h ago", UpdateChecker.shouldCheck(nowMs - 25 * 3600_000L, nowMs));
        check("update gate: clock backwards", UpdateChecker.shouldCheck(nowMs + dayMs, nowMs));

        // looksLikeApk: ZIP magic, never throws
        check("update apk check: null false", !UpdateChecker.looksLikeApk(null));
        check("update apk check: missing false",
                !UpdateChecker.looksLikeApk(new File("/tmp/does-not-exist-xyz.apk")));
        File tmpApk = File.createTempFile("upd-", ".apk");
        File tmpTxt = File.createTempFile("upd-", ".txt");
        try (FileOutputStream o = new FileOutputStream(tmpApk)) {
            o.write(new byte[]{'P', 'K', 3, 4, 1, 2, 3});
        }
        Files.write(tmpTxt.toPath(), "<html>error</html>".getBytes("UTF-8"));
        check("update apk check: zip magic true", UpdateChecker.looksLikeApk(tmpApk));
        check("update apk check: html false", !UpdateChecker.looksLikeApk(tmpTxt));
        tmpApk.delete();
        tmpTxt.delete();

        // --- v1.18: fetch + end-to-end against a local server ---
        // Needs real TCP from the JVM. The build sandbox blocks all
        // JVM TCP (every socket gets a policy message instead of a
        // connection), so these run only with
        // -Dlinksave.network.tests=true on a machine where TCP works.
        // In this sandbox they are skipped — the same flow is verified
        // with curl + a parse check instead (see the v1.18 notes).
        if (Boolean.getBoolean("linksave.network.tests")) {
            runNetworkTests();
        } else {
            System.out.println("SKIP: update network tests "
                    + "(need -Dlinksave.network.tests=true where JVM TCP works)");
        }

        System.out.println("PASSED " + passed + " / FAILED " + failed);
        if (failed > 0) System.exit(1);
    }

    /**
     * v1.18 network tests — only with -Dlinksave.network.tests=true
     * on a machine where the JVM can open TCP connections. A fixture
     * version.json advertising versionCode 9999 plus a synthetic APK,
     * served locally: the same fetch -> compare -> download path the
     * app runs against GitHub, with the test-overridable URL. The
     * /latest redirect test covers the releases/latest/download URL
     * (a 302 to the real asset) that version.json points at.
     */
    static void runNetworkTests() throws Exception {
        byte[] fakeApk = new byte[300 * 1024];
        fakeApk[0] = 'P'; fakeApk[1] = 'K'; fakeApk[2] = 3; fakeApk[3] = 4;
        for (int i = 4; i < fakeApk.length; i++) fakeApk[i] = (byte) (i * 31);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;
        server.createContext("/version.json", ex -> {
            byte[] body = ("{\"versionCode\":9999,\"versionName\":\"9.99\","
                    + "\"apkUrl\":\"" + base + "/LinkSave.apk\","
                    + "\"whatsNew\":\"test\"}").getBytes("UTF-8");
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        server.createContext("/LinkSave.apk", ex -> {
            ex.getResponseHeaders().set("Content-Type",
                    "application/vnd.android.package-archive");
            ex.sendResponseHeaders(200, fakeApk.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(fakeApk); }
        });
        // The releases/latest/download pattern: a 302 to the asset.
        server.createContext("/latest", ex -> {
            ex.getResponseHeaders().set("Location", base + "/LinkSave.apk");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/garbage.json", ex -> {
            byte[] body = "this is not json".getBytes("UTF-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        server.createContext("/slow.json", ex -> {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            byte[] body = "{}".getBytes("UTF-8");
            try {
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(body); }
            } catch (Throwable ignored) {}
        });
        server.createContext("/huge.json", ex -> {
            byte[] body = new byte[100 * 1024];
            Arrays.fill(body, (byte) 'x');
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        java.util.concurrent.ExecutorService srvPool =
                java.util.concurrent.Executors.newCachedThreadPool();
        server.setExecutor(srvPool);
        server.start();
        try {
            UpdateChecker.Info live = UpdateChecker.fetch(base + "/version.json", 5000);
            check("update fetch: live local feed", live != null && live.versionCode == 9999
                    && "9.99".equals(live.versionName) && "test".equals(live.whatsNew));
            check("update fetch: 9999 newer than 19",
                    UpdateChecker.isNewer(live, 19));
            check("update fetch: 404 silent",
                    UpdateChecker.fetch(base + "/missing.json", 5000) == null);
            check("update fetch: garbage silent",
                    UpdateChecker.fetch(base + "/garbage.json", 5000) == null);
            check("update fetch: oversized silent",
                    UpdateChecker.fetch(base + "/huge.json", 5000) == null);
            check("update fetch: timeout silent",
                    UpdateChecker.fetch(base + "/slow.json", 500) == null);
            check("update fetch: unreachable silent",
                    UpdateChecker.fetch("http://127.0.0.1:1/x.json", 1000) == null);
            check("update fetch: null url silent",
                    UpdateChecker.fetch(null, 1000) == null);

            // End-to-end: fetch -> compare -> download -> verify bytes.
            File e2eOut = File.createTempFile("upd-e2e-", ".apk");
            long written = FastDownload.download(live.apkUrl, e2eOut, UpdateChecker.UA,
                    UpdateChecker.MAX_APK_BYTES, null, null);
            byte[] got = Files.readAllBytes(e2eOut.toPath());
            check("update e2e: downloaded size", written == fakeApk.length
                    && got.length == fakeApk.length);
            check("update e2e: bytes identical", Arrays.equals(got, fakeApk));
            check("update e2e: looks like an APK", UpdateChecker.looksLikeApk(e2eOut));
            e2eOut.delete();

            // Redirect: the downloader must follow the 302 that
            // releases/latest/download answers with.
            File redirOut = File.createTempFile("upd-redir-", ".apk");
            FastDownload.download(base + "/latest", redirOut, UpdateChecker.UA,
                    UpdateChecker.MAX_APK_BYTES, null, null);
            check("update e2e: redirect followed, bytes identical",
                    Arrays.equals(Files.readAllBytes(redirOut.toPath()), fakeApk));
            redirOut.delete();
        } finally {
            server.stop(0);
            srvPool.shutdownNow();
        }
    }
}
