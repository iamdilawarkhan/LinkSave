package com.user.linksave;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * In-app updater (v1.18). On launch MainActivity fetches version.json
 * from the public LinkSave GitHub repo (at most once per 24 hours);
 * when its versionCode is newer than the installed one, the app shows
 * a small "New version available" pop-up, downloads the APK in the
 * background on tap, and opens the system install prompt.
 *
 * Pure Java (no Android APIs) so every rule is unit-testable on the
 * JVM: parsing, version comparison, the daily gate, the silent
 * failure paths, and the APK sanity check. The only Android parts
 * (dialog, download trigger, install intent) live in MainActivity.
 *
 * Safety notes:
 *  - fetch() never throws and returns null on ANY failure (offline,
 *    404, timeout, bad JSON, oversized body): the caller stays
 *    completely silent — no pop-up, no error, no extra text.
 *  - The update URL is a parameter of fetch(), so tests point it at
 *    a local server. The shipped app always passes DEFAULT_URL.
 *  - apkUrl may be http or https (http exists only for the local
 *    test server). A swapped APK cannot hurt: Android refuses to
 *    install an update over LinkSave unless it is signed with the
 *    same key, so a tampered file simply fails to install.
 *  - looksLikeApk() is a cheap sanity check (ZIP magic) before the
 *    install prompt, so an HTML error page saved as ".apk" never
 *    reaches the installer.
 */
public final class UpdateChecker {

    private UpdateChecker() {}

    /** The live update feed. The shipped app always uses this URL. */
    public static final String DEFAULT_URL =
            "https://raw.githubusercontent.com/iamdilawarkhan/LinkSave/main/version.json";

    /** Update checks happen at most this often (24 hours). */
    public static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;

    /** version.json is a few hundred bytes; anything bigger is not ours. */
    public static final int MAX_JSON_BYTES = 64 * 1024;

    /** The downloaded update APK lives in the app cache under this dir. */
    public static final String UPDATE_DIR = "update";
    public static final String UPDATE_APK_NAME = "LinkSave-update.apk";

    /** An update APK is ~0.5 MB; refuse anything absurdly large. */
    public static final long MAX_APK_BYTES = 64L * 1024 * 1024;

    /** User-Agent for the version check (GitHub serves any UA). */
    public static final String UA = "LinkSave";

    /** One parsed version.json. versionName/whatsNew may be empty. */
    public static final class Info {
        public final int versionCode;
        public final String versionName;
        public final String apkUrl;
        public final String whatsNew;

        public Info(int versionCode, String versionName, String apkUrl, String whatsNew) {
            this.versionCode = versionCode;
            this.versionName = versionName == null ? "" : versionName;
            this.apkUrl = apkUrl == null ? "" : apkUrl;
            this.whatsNew = whatsNew == null ? "" : whatsNew;
        }
    }

    /**
     * Parse version.json text. Returns null on any problem: null /
     * empty / malformed JSON, a missing or non-positive versionCode,
     * or a missing / non-http(s) apkUrl. Never throws.
     */
    public static Info parse(String text) {
        try {
            if (text == null || text.trim().isEmpty()) return null;
            JSONObject o = new JSONObject(text);
            int code = o.optInt("versionCode", -1);
            if (code <= 0) return null;
            String apkUrl = o.optString("apkUrl", null);
            if (apkUrl == null) return null;
            apkUrl = apkUrl.trim();
            if (!apkUrl.startsWith("http://") && !apkUrl.startsWith("https://")) return null;
            return new Info(code,
                    o.optString("versionName", ""),
                    apkUrl,
                    o.optString("whatsNew", ""));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** True when the feed advertises a newer build than installed. */
    public static boolean isNewer(Info info, int installedVersionCode) {
        return info != null && info.versionCode > installedVersionCode;
    }

    /**
     * True when a check is due: never checked, 24h passed since the
     * last successful check, or the clock moved backwards (a skew is
     * no reason to skip checks forever).
     */
    public static boolean shouldCheck(long lastCheckMs, long nowMs) {
        if (lastCheckMs <= 0) return true;
        if (nowMs < lastCheckMs) return true;
        return nowMs - lastCheckMs >= CHECK_INTERVAL_MS;
    }

    /**
     * GET a version.json URL and parse it. Returns null on any
     * failure (offline, non-200, timeout, bad JSON, oversized body).
     * Never throws. The URL is a parameter so tests can point it at
     * a local server; the shipped app passes DEFAULT_URL.
     */
    public static Info fetch(String urlStr, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            if (urlStr == null || urlStr.isEmpty()) return null;
            URL url = new URL(urlStr);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json");
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > MAX_JSON_BYTES) return null;
            }
            return parse(out.toString("UTF-8"));
        } catch (Throwable ignored) {
            return null;
        } finally {
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Cheap sanity check before the install prompt: a real APK is a
     * ZIP, so it must start with the PK\003\004 magic. Never throws.
     */
    public static boolean looksLikeApk(File f) {
        try {
            if (f == null || !f.isFile() || f.length() < 4) return false;
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] magic = new byte[4];
                if (in.read(magic) != 4) return false;
                return magic[0] == 'P' && magic[1] == 'K'
                        && magic[2] == 3 && magic[3] == 4;
            } finally {
                try { in.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
            return false;
        }
    }
}
