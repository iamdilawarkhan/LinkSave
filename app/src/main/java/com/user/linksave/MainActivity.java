package com.user.linksave;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LinkSave 1.18 — interface copied one-to-one from the Green Hole reference
 * (Play Store screenshot 02/04): pure black, letter-spaced title with one
 * small credit line ("Designed by Dilawar Khan") directly under it, one
 * huge animated glowing green orb in the centre, one grey status line,
 * VIDEOS at the bottom. The credit line and the idle hint are the only
 * extra text on screen, both added at the user's request. No link field:
 * the orb reads the
 * clipboard on tap and the URL is never displayed. Downloads are fast:
 * every file downloads in 4 parallel Range chunks (FastDownload), and
 * YouTube video+audio parts download concurrently before the on-device
 * MediaMuxer merge.
 *
 * Flow: copy a link -> tap the orb (auto-paste) -> if the video has
 * several quality options, a small "Choose quality" label appears above
 * the pills and the user picks one; if it has exactly one option (e.g.
 * a lone "MP4" pill) the download starts immediately, no asking (v1.6
 * smart flow — the user's rule: one option is not a choice). The orb
 * then shows live % and "Please wait... X / Y MB". Or share a link
 * straight to LinkSave from YouTube / Instagram / TikTok / Facebook
 * (ACTION_SEND) — no clipboard read, so Android's "pasted from your
 * clipboard" toast never appears (v1.5).
 *
 * v1.18: in-app updater (the user's request — the app is shared
 * free on GitHub, so users need to hear about new versions inside
 * the app). On launch the app fetches version.json from the public
 * LinkSave repo (at most once per 24 hours, background thread, ~5s
 * timeout); if its versionCode is newer, a small dark pop-up says
 * "New version available" with a Download / Later choice. Download
 * fetches the APK in the background (FastDownload, same parallel
 * engine as videos) with progress on the orb — only while no video
 * download is running, so the two never fight over the orb — and
 * when it finishes, the system install prompt opens (ACTION_VIEW
 * with a FileProvider content URI). First time, Android asks the
 * user to allow "install unknown apps" for LinkSave; the app opens
 * that Settings screen itself and retries the install when the
 * user comes back (see pendingUpdateFile / onResume). Any failure
 * — offline, bad JSON, bad APK, install refused — is silent or
 * one line, never a crash; a tampered APK cannot install over
 * LinkSave anyway (Android requires the same signing key).
 * The check/parse/compare rules live in UpdateChecker (pure Java,
 * unit-tested); the only Android parts here are the dialog, the
 * download trigger and the install intent.
 *
 * v1.7: parallel downloads + cancel by orb tap (the user's rule).
 * While a download is running, tapping the orb with NO new link copied
 * cancels that download; tapping with a NEW link copied starts it in
 * parallel and the first one keeps running. The orb % and the
 * "Please wait... X / Y MB" line show aggregate progress across all
 * active downloads (sum of bytes done / sum of known totals — see
 * LinkUtil.aggregateProgress, unit-tested). A link already fetching
 * or downloading is never started twice: the same-link tap cancels
 * it instead (that covers the fetch/resolve phase too), and a
 * same-link tap while its quality choice is already on screen does
 * nothing. If multi-option pills are showing and the orb is tapped
 * with a new link, the pending choice is replaced by the new fetch.
 * Concurrent downloads are capped at 4 — a 5th shows one line,
 * "4 downloads max". When one download finishes while others run,
 * the progress state stays; when ALL finish, "Saved ✓" + PLAY VIDEO
 * appears for the most recently finished file, and every finished
 * file lands in VIDEOS. Cancellation is real: the job's
 * FastDownload.CancelToken disconnects every live connection at
 * once (probe, chunk threads, YouTube's concurrent video+audio
 * parts), the merge loop checks the token, the partial/temp files
 * are deleted, and a cancelled download stops quietly — no error
 * popup. All job state lives in the `jobs` map (keyed by source
 * URL), touched only on the main thread; worker threads only read
 * the job's volatile flags and report progress through post().
 *
 * v1.8: idle hint under the orb (the user's request): when the app
 * opens, the status line reads "Copy video link and then tap on ball
 * to download video" — exactly one line. The 54-char line is shown at
 * 11sp (~297dp wide, inside the ~352dp usable width; at the normal
 * 13sp it would be ~351dp and clip), maxLines=1, in the full-width
 * MATCH_PARENT status TextView with no letter-spacing — the narrow
 * WRAP_CONTENT + letter-spacing pattern clipped "VIDEOS" to "VI" on
 * this user's phone in v1.1-v1.4, so it is never used for text that
 * must not clip. A runtime fit check shrinks the hint (never below
 * 8sp) if a large font scale makes it too wide anyway. The hint shows
 * ONLY in the idle state: fetching, downloading, "Saved ✓", "Choose
 * quality", and one-line errors all replace it, and it comes back
 * when the app returns to idle (e.g. after cancelling the last
 * download) — see showIdleHint / setStatus.
 *
 * v1.9: custom orb photo (the user's request). Long-press the orb and
 * a small popup offers "Change photo" (system gallery picker) and
 * "Remove photo" (only when a photo is set). The photo is centre-
 * cropped to a square, stored at most 512px in app-private storage
 * (it never leaves the device — no upload, no network), and drawn
 * inside the orb as a circle; the photo itself stays upright — a
 * rotating face would flip upside down — while the rings keep
 * rotating around it. During a download a dark disc sits behind the
 * % so it stays readable over any photo. Long-press returns true,
 * so it never triggers the tap action (download / cancel). See
 * showOrbPhotoMenu / saveOrbPhoto / OrbView.setPhoto.
 *
 * v1.10: thumbnails in the VIDEOS list (the user's request — with
 * only truncated titles, similar videos were impossible to tell
 * apart). Each card is now [thumbnail] [title...] [OPEN]: a 56dp
 * square with rounded corners at the start of the card. Frames are
 * extracted on-device with MediaMetadataRetriever (~1s in, or 10%
 * of the duration for very short clips), asynchronously off the UI
 * thread, with a small green play-glyph placeholder meanwhile
 * (a music-note placeholder for audio-only files, which have no
 * video frame — their embedded album art is used when present).
 * Thumbnails are cached in memory (bounded LRU) and on disk in the
 * app cache dir, keyed by URI + size + last-modified (see
 * LinkUtil.thumbCacheKey, unit-tested), so scrolling and reopening
 * the list never re-extracts frames. A missing, corrupt or
 * audio-only file shows the placeholder — never a crash, never a
 * blank hole, never an error popup. See loadThumb /
 * extractVideoThumb / thumbCacheKey.
 *
 * v1.11 added an "Auto download" floating icon (accessibility
 * service + overlay bubble + background service) — v1.12 REMOVED
 * it entirely at the user's request: Google Play Protect blocked
 * the v1.11 install ("App blocked to protect your device...
 * sensitive data") because of the accessibility service, and the
 * user wants an app he can share without that warning. The
 * toggle, the service, the bubble, the overlay permission and
 * the boot receiver are all gone; the v1.11 Engine refactor
 * (resolve + download moved out of this class into Engine) is
 * kept — it is only code organisation, and this class keeps the
 * UI and the orb-flow orchestration. User-visible behaviour is
 * v1.10's: orb tap, Share intent, cancel / parallel downloads.
 *
 * v1.6: smart quality flow in showResult (see LinkUtil.flowForOptionCount
 * — unit-tested): 0 usable options -> error state as before, 1 -> auto-
 * download, 2+ -> "Choose quality" label + pills. Applies to all four
 * platforms and to both entry points (orb tap and Share intent), because
 * both funnel through startFetch -> showResult.
 *
 * v1.5 fixes: (1) the VIDEOS label is MATCH_PARENT wide — in v1.1-v1.4
 * it was a WRAP_CONTENT TextView with letterSpacing inside a
 * WRAP_CONTENT column whose only other child is the 28dp dots icon,
 * and on the user's phone the letter-spaced text was measured too
 * narrow, so the label was laid out in a ~20dp-wide box and everything
 * after "VI" was clipped; the bottom margin was never the problem
 * (the text was vertically complete with space below it, and the same
 * clipping survived the v1.1->v1.2 layout restructure because the
 * TextView setup was unchanged). (2) The credit line is brighter
 * (#A8BFB5, 11sp). (3) ACTION_SEND share-intent support (see
 * handleSharedLink).
 *
 * Resolvers:
 *  - YouTube: Invidious adaptive streams (video-only MP4 + audio-only
 *    MP4 from googlevideo, verified live 2026-11-12) merged on-device
 *    with MediaMuxer into one MP4 with sound. Direct youtubei clients
 *    are PO-token gated and every free third-party YouTube API tested
 *    (Cobalt incl. its broken 0-byte tunnels, Piped, Siputzx, Vreden,
 *    Tiklydown, FabDL, Delirius, Ryzendesu, y2mate, yt1s, loader.to,
 *    savefrom, SnapCDN, Vevioz) is dead, blocked, or key-gated.
 *  - TikTok: TikWM API (HD/SD no-watermark, with-watermark, MP3).
 *  - Facebook: Siputzx resolver (720p HD / 360p SD).
 *  - Instagram (v1.4 chain): curl-x page parsed for the direct
 *    cdninstagram.com MP4 (curl-x changed: /instagram/{code} used to
 *    stream the MP4, now it returns an HTML page embedding the CDN
 *    URL — v1.3 probed it with HEAD, saw text/html and gave up, which
 *    is why Instagram broke on the user's phone), then vxinstagram
 *    embed (og:video -> offload MP4, 302 to fbcdn), then Siputzx and
 *    ddinstagram (both down at last check, kept as fallbacks).
 *    "Private video" only when a service positively says the post is
 *    deleted/private (HTTP 404); service outages show "Server busy",
 *    and a post with no video at all shows "No video".
 *
 * Clipboard note: Android 12+ shows a system "pasted from your
 * clipboard" toast every time an app reads clipboard data — that toast
 * is drawn by Android, not by LinkSave, and no app can resize or hide
 * it. The app reads the clip description (no toast for that) and only
 * calls getPrimaryClip() when the clipboard actually changed, so the
 * toast appears at most once per newly copied link. v1.5 adds the
 * toast-free path: share a link straight to LinkSave (ACTION_SEND)
 * and no clipboard read happens at all — see handleSharedLink.
 *
 * Error rule: the app must never crash or hang. Every failure path shows
 * one short message instead. All click handlers and background work are
 * guarded; all network calls have timeouts; all cursors/streams close.
 */
public class MainActivity extends Activity {

    // ---- Green Hole palette: pure black, one glowing emerald ----
    private static final int BG       = 0xFF000000;
    private static final int CARD     = 0xFF14141F;
    private static final int GREEN    = 0xFF10B981;
    private static final int GREEN_LT = 0xFF6EE7B7;
    private static final int GREEN_DK = 0xFF059669;
    private static final int TEAL     = 0xFF2DD4BF;
    private static final int TEXT     = 0xFFFFFFFF;
    private static final int TITLE    = 0xFFE5E7EB;
    private static final int MUTED    = 0xFF9CA3AF;
    private static final int TRACK    = 0xFF2E2E2E;

    private static final String PREFS = "linksave_downloads";

    // Cached pool, not a single thread: v1.7 runs fetches and downloads
    // in parallel (up to MAX_DOWNLOADS at once), so one long resolve
    // must never block the others. All job-state changes happen on the
    // main thread; worker threads only read volatile job flags.
    private final ExecutorService bg = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout mainScreen;
    private LinearLayout videosScreen;
    private OrbView orb;
    private TextView statusText;
    private TextView qualityLabel; // v1.6: "Choose quality", above the pills (2+ options only)
    private LinearLayout qualityBox;
    private LinearLayout videosList;

    // ---------------- selective delete (v1.13) ----------------
    /** VIDEOS screen is in tick-selection mode (trash tapped once). */
    private boolean deleteSelecting = false;
    /** Delete keys (LinkUtil.deleteKey) of the ticked videos. */
    private final Set<String> deleteSelected = new HashSet<>();

    private volatile boolean destroyed = false;

    // ---------------- in-app updater (v1.18) ----------------
    /** SharedPreferences key for the last successful update check. */
    private static final String PREF_UPDATE_LAST_CHECK = "update_last_check";
    /** An update APK download is in progress (one at a time). */
    private volatile boolean updateDownloadRunning = false;
    /** Update APK downloaded, waiting for the user to allow
     * "install unknown apps" in Settings — retried in onResume
     * when the user comes back from that screen. */
    private File pendingUpdateFile;

    // ---------------- custom orb photo (v1.9) ----------------
    private static final int REQ_PICK_ORB_PHOTO = 1001;
    /** The decoded custom orb photo (square, <= 512px), if one is set. */
    private Bitmap orbPhotoBitmap;

    // ---------------- VIDEOS thumbnails (v1.10) ----------------
    /**
     * In-memory thumbnail cache: bounded access-order LRU (32
     * entries — a 56dp thumbnail is ~50KB, so ~1.6MB total).
     * Synchronized: background extractors write, the UI thread
     * reads. Evicted bitmaps are NOT recycled — an ImageView may
     * still be displaying one, and recycling a displayed bitmap
     * crashes drawing; the GC reclaims them once no view holds one.
     */
    private final Map<String, Bitmap> thumbMemCache = Collections.synchronizedMap(
            new LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) {
                    return size() > LinkUtil.THUMB_MEM_MAX;
                }
            });
    /** Lazily built placeholder bitmaps (play glyph / music note). */
    private Bitmap thumbPlaceholderVideo, thumbPlaceholderAudio;

    // ---------------- parallel downloads (v1.7) ----------------

    /**
     * One job per source URL, from orb tap (or Share) through fetch,
     * quality choice, download and finish. The map is the single
     * source of truth for what is active and is touched ONLY on the
     * main thread. Worker threads read only the volatile fields
     * (cancelled, done, total) and the cancel token, and report back
     * through post(). States are LinkUtil.JOB_FETCHING /
     * JOB_CHOOSING / JOB_DOWNLOADING.
     */
    private static final class Job {
        final long id;              // unique per job
        final String url;           // source URL, the map key
        int state = LinkUtil.JOB_FETCHING;
        Resolved resolved;          // set once the fetch returns
        Future<?> future;           // the fetch or download task
        final FastDownload.CancelToken token = new FastDownload.CancelToken();
        volatile boolean cancelled;
        // Progress, written by worker threads, read on the main thread.
        // total is -1 until the size is known.
        volatile long done, total = -1;
        volatile boolean merging;   // download done, MediaMuxer running

        Job(long id, String url) { this.id = id; this.url = url; }
    }

    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final AtomicLong jobSeq = new AtomicLong(0);

    private long lastProgressUi = 0; // throttles aggregate progress updates

    private int activeDownloadCount() {
        int n = 0;
        for (Job j : jobs.values()) {
            if (j != null && j.state == LinkUtil.JOB_DOWNLOADING) n++;
        }
        return n;
    }

    private int activeFetchCount() {
        int n = 0;
        for (Job j : jobs.values()) {
            if (j != null && j.state == LinkUtil.JOB_FETCHING) n++;
        }
        return n;
    }

    private Job choosingJob() {
        for (Job j : jobs.values()) {
            if (j != null && j.state == LinkUtil.JOB_CHOOSING) return j;
        }
        return null;
    }

    private boolean anyDownloading() {
        return activeDownloadCount() > 0;
    }

    private boolean isIdle() {
        return LinkUtil.isIdle(activeDownloadCount(), activeFetchCount(),
                choosingJob() != null);
    }

    // ---------------- glowing green orb ----------------

    /**
     * The Green Hole orb, alive: emerald radial-gradient sphere with a
     * shimmering highlight that slowly orbits, a breathing glow, a
     * continuously rotating dashed teal ring, counter-rotating inner
     * arcs, and a smoothly animated progress ring. Idle shows two mint
     * arc segments (screenshot 01); downloading shows the live % in the
     * middle with a progress ring (screenshot 02).
     */
    static class OrbView extends View {
        static final int IDLE = 0, SPIN = 1, PROGRESS = 2;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Handler anim = new Handler(Looper.getMainLooper());
        private int mode = IDLE;
        private int pct = -1;
        private float shownPct = 0f;      // eased towards pct for smooth ring
        private float dashAngle = 0f;
        private float innerAngle = 0f;
        private float spinAngle = 0f;
        private float phase = 0f;         // 0..1 breathing / shimmer cycle
        private boolean animating = false;

        // v1.9: the user's photo inside the orb (a square bitmap, drawn
        // as a circle through a BitmapShader). The photo itself stays
        // upright — a rotating face would flip upside down — while the
        // rings keep rotating around it. The shader is created once in
        // setPhoto; only its matrix is updated per frame (the sphere
        // breathes, so its radius changes every frame). The bitmap is
        // owned by MainActivity (which recycles it); this view only
        // holds the reference and guards against a recycled bitmap.
        private Bitmap photo;
        private BitmapShader photoShader;
        private final Matrix photoMatrix = new Matrix();
        private int photoSize;            // photo width in px (square)

        private final Runnable ticker = new Runnable() {
            @Override public void run() {
                try {
                    if (!animating) return;
                    dashAngle += 1.1f;              // dashed ring, clearly turning
                    innerAngle -= 0.7f;             // inner arcs drift the other way
                    phase += 0.008f;                // ~4s breathing cycle
                    if (phase >= 1f) phase -= 1f;
                    if (mode == SPIN) spinAngle += 11f;
                    // ease the progress ring towards the real value
                    if (mode == PROGRESS && pct >= 0) {
                        float diff = pct - shownPct;
                        shownPct += diff * 0.18f;
                        if (Math.abs(diff) < 0.5f) shownPct = pct;
                    } else if (mode != PROGRESS) {
                        shownPct = 0f;
                    }
                    invalidate();
                    anim.postDelayed(this, 33);     // ~30 fps, smooth but cheap
                } catch (Throwable ignored) {}
            }
        };

        OrbView(Context ctx) { super(ctx); }

        void setIdle() { mode = IDLE; pct = -1; shownPct = 0f; invalidate(); }
        void setSpin() { mode = SPIN; pct = -1; shownPct = 0f; invalidate(); }
        void setProgress(int p) {
            mode = PROGRESS;
            pct = Math.max(0, Math.min(100, p));
            invalidate();
        }

        /** v1.9: show the user's photo inside the orb (upright, circular). */
        void setPhoto(Bitmap b) {
            try {
                if (b != null && !b.isRecycled() && b.getWidth() > 0) {
                    photo = b;
                    photoSize = b.getWidth();
                    photoShader = new BitmapShader(b,
                            Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                } else {
                    photo = null; photoSize = 0; photoShader = null;
                }
            } catch (Throwable ignored) {
                photo = null; photoSize = 0; photoShader = null;
            }
            invalidate();
        }

        /** v1.9: back to the green gradient orb. */
        void clearPhoto() {
            photo = null; photoSize = 0; photoShader = null;
            invalidate();
        }

        boolean hasPhoto() {
            return photo != null && !photo.isRecycled() && photoShader != null;
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            animating = true;
            anim.post(ticker);
        }

        @Override protected void onDetachedFromWindow() {
            animating = false;
            try { anim.removeCallbacks(ticker); } catch (Throwable ignored) {}
            super.onDetachedFromWindow();
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            try {
                float cx = getWidth() / 2f, cy = getHeight() / 2f;
                float r = Math.min(cx, cy);
                if (r <= 0) return;
                float d = getResources().getDisplayMetrics().density;

                // breathing: glow + sphere gently swell and fade, ~4s cycle
                float breathe = (float) (0.5 + 0.5 * Math.sin(phase * 2 * Math.PI));
                float pulse = 0.94f + 0.06f * breathe;

                // soft green halo behind everything (pulsing)
                paint.setStyle(Paint.Style.FILL);
                int haloA = 0x28 + (int) (0x22 * breathe);
                RadialGradient halo = new RadialGradient(cx, cy, r * pulse,
                        new int[]{(haloA << 24) | 0x22C55E, 0x1422C55E, 0x0022C55E},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
                paint.setShader(halo);
                c.drawCircle(cx, cy, r * pulse, paint);
                paint.setShader(null);

                RectF outer = new RectF(cx - r * 0.94f, cy - r * 0.94f,
                        cx + r * 0.94f, cy + r * 0.94f);

                if (mode == IDLE) {
                    // two thick mint arc segments (reference screenshot 01),
                    // slowly rotating with the ring
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(6 * d);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    paint.setColor(0xFF6EE7B7);
                    c.save();
                    c.rotate(dashAngle * 0.35f, cx, cy);
                    c.drawArc(outer, -55, 105, false, paint);
                    c.drawArc(outer, 125, 100, false, paint);
                    c.restore();
                } else if (mode == SPIN) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(5 * d);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    paint.setColor(TRACK);
                    c.drawArc(outer, 0, 360, false, paint);
                    paint.setColor(0xFF34D399);
                    c.drawArc(outer, spinAngle, 120, false, paint);
                } else {
                    // progress ring (reference screenshot 02), eased smooth
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(7 * d);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    paint.setColor(TRACK);
                    c.drawArc(outer, 0, 360, false, paint);
                    if (shownPct > 0) {
                        paint.setColor(0xFF34D399);
                        c.drawArc(outer, -90, shownPct * 3.6f, false, paint);
                    }
                }

                // dashed teal ring, continuously rotating
                RectF dash = new RectF(cx - r * 0.86f, cy - r * 0.86f,
                        cx + r * 0.86f, cy + r * 0.86f);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(4 * d);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(0xB32DD4BF);
                paint.setPathEffect(new DashPathEffect(new float[]{10 * d, 9 * d}, 0));
                c.save();
                c.rotate(dashAngle, cx, cy);
                c.drawArc(dash, 0, 360, false, paint);
                c.restore();
                paint.setPathEffect(null);

                // inner glowing arc segments, counter-rotating
                RectF inner = new RectF(cx - r * 0.75f, cy - r * 0.75f,
                        cx + r * 0.75f, cy + r * 0.75f);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3 * d);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(0xFF6EE7B7);
                c.save();
                c.rotate(innerAngle, cx, cy);
                c.drawArc(inner, -150, 105, false, paint);
                c.drawArc(inner, 30, 105, false, paint);
                c.restore();

                // the sphere: either the user's photo (v1.9, upright —
                // a rotating face would flip upside down) or the green
                // gradient. The rings around it keep rotating either way.
                if (photoShader != null && photo != null && !photo.isRecycled()
                        && photoSize > 0) {
                    float pr = r * 0.66f * pulse;
                    photoMatrix.setScale(pr * 2f / photoSize, pr * 2f / photoSize);
                    photoMatrix.postTranslate(cx - pr, cy - pr);
                    photoShader.setLocalMatrix(photoMatrix);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setShader(photoShader);
                    c.drawCircle(cx, cy, pr, paint);
                    paint.setShader(null);
                    // thin green rim, so the photo still reads as the orb
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(2 * d);
                    paint.setColor(0x8034D399);
                    c.drawCircle(cx, cy, pr, paint);
                } else {
                    // bright mint centre fading to deep emerald edge.
                    // The highlight shimmers: its centre slowly orbits
                    // the sphere.
                    float hx = (float) (Math.cos(phase * 2 * Math.PI) * r * 0.12);
                    float hy = (float) (Math.sin(phase * 2 * Math.PI) * r * 0.12);
                    RadialGradient g = new RadialGradient(
                            cx - r * 0.10f + hx, cy - r * 0.24f + hy, r * 0.72f,
                            new int[]{0xFFA7F3D0, 0xFF34D399, 0xFF059669, 0xFF064E3B},
                            new float[]{0f, 0.35f, 0.70f, 1f}, Shader.TileMode.CLAMP);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setShader(g);
                    c.drawCircle(cx, cy, r * 0.66f * pulse, paint);
                    paint.setShader(null);

                    // small bright specular dot, gliding over the sphere
                    float sx = cx - r * 0.22f + (float) (Math.cos(phase * 2 * Math.PI) * r * 0.10);
                    float sy = cy - r * 0.30f + (float) (Math.sin(phase * 2 * Math.PI) * r * 0.10);
                    paint.setColor(0xBFFFFFFF);
                    c.drawCircle(sx, sy, r * 0.055f * pulse, paint);
                }

                // live % in the middle while downloading
                if (mode == PROGRESS && pct >= 0) {
                    if (photoShader != null) {
                        // dark disc behind the % so it stays readable
                        // over any photo (v1.9)
                        paint.setStyle(Paint.Style.FILL);
                        paint.setColor(0x99000000);
                        c.drawCircle(cx, cy, r * 0.34f, paint);
                    }
                    paint.setColor(Color.WHITE);
                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setTypeface(Typeface.DEFAULT_BOLD);
                    paint.setTextSize(r * 0.26f);
                    Paint.FontMetrics fm = paint.getFontMetrics();
                    c.drawText(Math.round(shownPct) + "%", cx,
                            cy - (fm.ascent + fm.descent) / 2f, paint);
                }
            } catch (Throwable ignored) { /* never crash on draw */ }
        }
    }

    /** 3x3 green dots — the VIDEOS button icon (reference screenshot 02). */
    static class DotsView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        DotsView(Context ctx) { super(ctx); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            try {
                float d = getResources().getDisplayMetrics().density;
                float step = getWidth() / 3f;
                paint.setColor(GREEN);
                paint.setStyle(Paint.Style.FILL);
                for (int row = 0; row < 3; row++) {
                    for (int col = 0; col < 3; col++) {
                        c.drawCircle(step * (col + 0.5f), step * (row + 0.5f), 2.2f * d, paint);
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    /** Minimal trash-can glyph (Clear All on the VIDEOS screen). */
    static class TrashView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        TrashView(Context ctx) { super(ctx); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            try {
                float d = getResources().getDisplayMetrics().density;
                float w = getWidth(), h = getHeight();
                paint.setColor(0xFF6B7280);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.6f * d);
                paint.setStrokeCap(Paint.Cap.ROUND);
                // lid + handle
                c.drawLine(w * 0.28f, h * 0.28f, w * 0.72f, h * 0.28f, paint);
                c.drawLine(w * 0.42f, h * 0.28f, w * 0.42f, h * 0.18f, paint);
                c.drawLine(w * 0.58f, h * 0.28f, w * 0.58f, h * 0.18f, paint);
                c.drawLine(w * 0.42f, h * 0.18f, w * 0.58f, h * 0.18f, paint);
                // body
                c.drawLine(w * 0.32f, h * 0.36f, w * 0.36f, h * 0.82f, paint);
                c.drawLine(w * 0.68f, h * 0.36f, w * 0.64f, h * 0.82f, paint);
                c.drawLine(w * 0.36f, h * 0.82f, w * 0.64f, h * 0.82f, paint);
                // inner lines
                c.drawLine(w * 0.45f, h * 0.44f, w * 0.46f, h * 0.74f, paint);
                c.drawLine(w * 0.55f, h * 0.44f, w * 0.54f, h * 0.74f, paint);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * v1.13: tick box at the start of a VIDEOS card in
     * delete-selection mode — an empty grey circle, or a green filled
     * circle with a white tick when the video is ticked for deletion.
     */
    static class TickView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean checked;
        TickView(Context ctx) { super(ctx); }
        void setChecked(boolean c) { checked = c; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            try {
                float d = getResources().getDisplayMetrics().density;
                float cx = getWidth() / 2f, cy = getHeight() / 2f;
                float r = Math.min(getWidth(), getHeight()) / 2f - 2 * d;
                if (r <= 0) return;
                if (checked) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(GREEN);
                    c.drawCircle(cx, cy, r, paint);
                    paint.setColor(Color.WHITE);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(2f * d);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    Path tick = new Path();
                    tick.moveTo(cx - r * 0.45f, cy);
                    tick.lineTo(cx - r * 0.1f, cy + r * 0.4f);
                    tick.lineTo(cx + r * 0.5f, cy - r * 0.4f);
                    c.drawPath(tick, paint);
                } else {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(1.6f * d);
                    paint.setColor(0xFF6B7280);
                    c.drawCircle(cx, cy, r, paint);
                }
            } catch (Throwable ignored) {}
        }
    }

    // ---------------- lifecycle / UI ----------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try { getWindow().setStatusBarColor(BG); } catch (Throwable ignored) {}
        try { getWindow().setNavigationBarColor(BG); } catch (Throwable ignored) {}

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        mainScreen = buildMainScreen();
        videosScreen = buildVideosScreen();
        videosScreen.setVisibility(View.GONE);

        root.addView(mainScreen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(videosScreen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        renderDownloads();
        loadOrbPhoto(); // v1.9: a custom orb photo survives restarts
        handleSharedLink(getIntent());
        maybeCheckForUpdate(); // v1.18: silent unless a newer version exists
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSharedLink(intent);
    }

    /**
     * Share-intent entry (v1.5). YouTube / Instagram / TikTok /
     * Facebook all offer "Share" — pick LinkSave and the shared text
     * (which carries the video link) arrives here as ACTION_SEND
     * text/plain. The link goes straight into the same fetch flow as
     * an orb tap, with NO clipboard read at all — so Android's
     * "pasted from your clipboard" toast never appears. (That toast
     * is drawn by Android 12+ itself and no app can resize it; the
     * share path is how the user avoids it entirely.) The orb-tap
     * clipboard flow stays as the fallback. The intent is consumed
     * (replaced with a plain MAIN intent) so a later recreate does
     * not re-fetch the same link.
     */
    private void handleSharedLink(Intent intent) {
        try {
            if (intent == null) return;
            if (!Intent.ACTION_SEND.equals(intent.getAction())) return;
            String type = intent.getType();
            if (type == null || !type.startsWith("text/")) return;
            String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (shared == null || shared.isEmpty()) {
                shared = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            }
            // extractUrl finds the link even when the shared text has a
            // title/caption around it (YouTube shares "Title\nURL").
            String url = LinkUtil.extractUrl(shared);
            setIntent(new Intent(Intent.ACTION_MAIN)); // consume: no re-fetch
            if (url == null) {
                showMessage("Invalid link");
                return;
            }
            showMainScreen();
            // Same rules as an orb tap (v1.7): a new link starts in
            // parallel with anything already running, a same-link
            // share cancels that link's download.
            onLinkTap(url);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Nothing to pre-fill: there is no link field on screen (v1.2).
        // The orb reads the clipboard itself when tapped.
        // v1.18: the user may be back from the "install unknown apps"
        // Settings screen — if they allowed it, open the install
        // prompt for the already-downloaded update APK now.
        try {
            if (pendingUpdateFile != null && pendingUpdateFile.exists()
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && getPackageManager().canRequestPackageInstalls()) {
                File f = pendingUpdateFile;
                pendingUpdateFile = null;
                promptInstall(f);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        // Stop every active job's network work at once (disconnects
        // live connections) — the pool interrupt alone would not
        // unblock a read stuck in the network stack.
        try {
            for (Job j : jobs.values()) {
                if (j != null) j.token.cancel();
            }
        } catch (Throwable ignored) {}
        try { bg.shutdownNow(); } catch (Throwable ignored) {}
        // v1.9: the orb photo bitmap is owned here — free it. The orb
        // guards against a recycled bitmap and falls back to green.
        try {
            if (orbPhotoBitmap != null && !orbPhotoBitmap.isRecycled()) {
                orbPhotoBitmap.recycle();
            }
            orbPhotoBitmap = null;
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    /**
     * v1.9: the gallery picker result. A cancelled picker (or an empty
     * result) is quiet — nothing happens, no error. A picked image
     * goes to saveOrbPhoto on a worker thread.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        try {
            if (requestCode != REQ_PICK_ORB_PHOTO) return;
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            saveOrbPhoto(data.getData());
        } catch (Throwable ignored) {}
    }

    @Override
    public void onBackPressed() {
        try {
            if (videosScreen != null && videosScreen.getVisibility() == View.VISIBLE) {
                // v1.13: back first leaves tick-selection mode (if on)
                // instead of leaving the screen.
                if (deleteSelecting) {
                    deleteSelecting = false;
                    deleteSelected.clear();
                    renderDownloads();
                    return;
                }
                showMainScreen();
                return;
            }
        } catch (Throwable ignored) {}
        super.onBackPressed();
    }

    private void showMainScreen() {
        try {
            videosScreen.setVisibility(View.GONE);
            mainScreen.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) {}
    }

    private void showVideosScreen() {
        try {
            hideKeyboard();
            // v1.13: always enter the list fresh — never stuck in
            // tick-selection mode from a previous visit.
            deleteSelecting = false;
            deleteSelected.clear();
            renderDownloads();
            mainScreen.setVisibility(View.GONE);
            videosScreen.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) {}
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            View v = getCurrentFocus();
            if (imm != null && v != null) {
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {}
    }

    // ---------------- in-app updater (v1.18) ----------------
    //
    // The user's request: when a new version is uploaded to GitHub,
    // the user opening the app sees a pop-up ("New version
    // available" + Download), the APK downloads in the background on
    // tap, and the system install prompt appears when it finishes.
    // The check runs at most once per 24 hours (last successful
    // check in SharedPreferences) on a background thread; every
    // failure is silent — offline, 404, bad JSON and timeouts all
    // just mean "no pop-up this launch". "Later" dismisses until
    // the next daily check. No extra text appears on the main
    // screen: the pop-up only exists when an update is available.

    private void maybeCheckForUpdate() {
        try {
            long last = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getLong(PREF_UPDATE_LAST_CHECK, 0);
            if (!UpdateChecker.shouldCheck(last, System.currentTimeMillis())) return;
            final int installed = installedVersionCode();
            if (installed <= 0) return; // can't tell: stay silent
            bg.execute(() -> {
                try {
                    UpdateChecker.Info info =
                            UpdateChecker.fetch(UpdateChecker.DEFAULT_URL, 5000);
                    if (info == null) return; // silent: offline / 404 / bad JSON
                    // A valid feed was read: the 24h clock starts now,
                    // whether or not an update is available — so a
                    // "Later" dismissal is not re-asked every launch.
                    setUpdateLastCheck(System.currentTimeMillis());
                    if (!UpdateChecker.isNewer(info, installed)) return;
                    post(() -> showUpdateDialog(info));
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private int installedVersionCode() {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                return (int) getPackageManager()
                        .getPackageInfo(getPackageName(), 0).getLongVersionCode();
            }
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void setUpdateLastCheck(long nowMs) {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong(PREF_UPDATE_LAST_CHECK, nowMs).apply();
        } catch (Throwable ignored) {}
    }

    private void showUpdateDialog(final UpdateChecker.Info info) {
        try {
            if (info == null || updateDownloadRunning) return;
            String title = "New version available";
            if (info.versionName != null && !info.versionName.isEmpty()) {
                title += " — v" + info.versionName;
            }
            String msg = (info.whatsNew != null && !info.whatsNew.isEmpty())
                    ? info.whatsNew
                    : "A new version of LinkSave is available.";
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(msg)
                    .setPositiveButton("Download", (d, which) -> safe(() -> startUpdateDownload(info)))
                    .setNegativeButton("Later", null)
                    .setCancelable(true)
                    .show();
        } catch (Throwable ignored) {}
    }

    private File updateApkFile() {
        return new File(new File(getCacheDir(), UpdateChecker.UPDATE_DIR),
                UpdateChecker.UPDATE_APK_NAME);
    }

    private void startUpdateDownload(final UpdateChecker.Info info) {
        if (updateDownloadRunning) return;
        updateDownloadRunning = true;
        final File apk = updateApkFile();
        bg.execute(() -> {
            try {
                File dir = apk.getParentFile();
                if (dir != null) dir.mkdirs();
                FastDownload.download(info.apkUrl, apk, UpdateChecker.UA,
                        UpdateChecker.MAX_APK_BYTES,
                        (done, total) -> post(() -> showUpdateProgress(done, total)),
                        null);
                if (!UpdateChecker.looksLikeApk(apk)) {
                    throw new Exception("Bad update file");
                }
                post(() -> {
                    updateDownloadRunning = false;
                    restoreIdleUi();
                    promptInstall(apk);
                });
            } catch (Throwable e) {
                try { if (apk != null) apk.delete(); } catch (Throwable ignored) {}
                post(() -> {
                    updateDownloadRunning = false;
                    restoreIdleUi();
                    showMessage("Update failed");
                });
            }
        });
    }

    /**
     * Update-download progress on the orb ("Please wait... X / Y MB",
     * same line as video downloads) — but ONLY while no video job is
     * active: a running video download owns the orb, and two writers
     * would fight over it. While videos run, the update still
     * downloads silently in the background and the install prompt
     * appears when it finishes.
     */
    private void showUpdateProgress(long done, long total) {
        try {
            if (!jobs.isEmpty()) return;
            if (total > 0) {
                orb.setProgress((int) Math.min(100, done * 100 / total));
                setStatus("Please wait...  " + LinkUtil.formatMB(done)
                        + " / " + LinkUtil.formatMB(total) + " MB");
            } else {
                orb.setSpin();
                setStatus("Please wait...  " + LinkUtil.formatMB(done) + " MB");
            }
        } catch (Throwable ignored) {}
    }

    /** Back to the idle orb + hint after an update download — but only
     * when nothing else is running; a video download owns the UI. */
    private void restoreIdleUi() {
        try {
            if (!isIdle()) return;
            if (orb != null) orb.setIdle();
            showIdleHint();
        } catch (Throwable ignored) {}
    }

    /**
     * Open the system install prompt for a downloaded update APK. On
     * Android 8+ the user must first allow "install unknown apps" for
     * LinkSave (once): if not allowed yet, a one-line toast explains
     * it and the app opens that Settings screen itself; onResume
     * retries the install when the user comes back.
     */
    private void promptInstall(File apk) {
        try {
            if (apk == null || !apk.exists()) {
                showMessage("Update failed");
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && !getPackageManager().canRequestPackageInstalls()) {
                pendingUpdateFile = apk;
                toast("Allow LinkSave to install updates");
                Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivity(s);
                return;
            }
            pendingUpdateFile = null;
            Uri uri = UpdateFileProvider.getUriForFile(this, apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable e) {
            showMessage("Can't install update");
        }
    }

    // ---------------- main screen (Green Hole 02/04) ----------------
    //
    // FrameLayout, not a weighted LinearLayout: the top bar is pinned to
    // the top, VIDEOS is pinned to the bottom with a margin that clears
    // the system navigation bar, and the orb block is centred between
    // them. (v1.5 note: the "VI" clipping blamed on the v1.1 weighted
    // column in earlier comments was actually the label TextView being
    // measured too narrow — see the v1.5 note on the VIDEOS block below.
    // The label is now MATCH_PARENT wide, so it cannot clip.)
    // There is NO link field: the orb reads the clipboard on tap and the
    // URL is never displayed.

    private FrameLayout buildMainScreen() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        // Top bar: LINK-SAVE letter-spaced centre, and the credit line
        // directly under the title (user's request, v1.4 — it used to
        // sit at the bottom of the screen). v1.13: the (i) info button
        // is gone (it only showed the version toast), so the title is
        // full-width and centred on its own.
        LinearLayout topWrap = new LinearLayout(this);
        topWrap.setOrientation(LinearLayout.VERTICAL);
        topWrap.setPadding(dp(24), dp(16), dp(24), 0);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("LINK-SAVE");
        title.setTextSize(19);
        title.setTextColor(TITLE);
        title.setLetterSpacing(0.45f);
        try {
            title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        } catch (Throwable ignored) {}
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleLp);
        top.addView(title);
        topWrap.addView(top);

        // Credit line (user's request, the only extra text on screen):
        // small, letter-spaced, centred directly under the title.
        // v1.5: brightened (#A8BFB5, 11sp) — the old #5B6B64 at 10sp
        // was too dim to read on the user's phone.
        TextView credit = new TextView(this);
        credit.setText("Designed by Dilawar Khan");
        credit.setTextSize(11);
        credit.setTextColor(0xFFA8BFB5);
        credit.setLetterSpacing(0.22f);
        credit.setGravity(Gravity.CENTER);
        credit.setPadding(0, dp(5), 0, 0);
        topWrap.addView(credit);

        // v1.12: the v1.11 "Auto download" toggle row is gone with the
        // whole auto-download feature (Play Protect blocked v1.11).

        root.addView(topWrap, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // Centre block: orb + one grey status line + pills (when needed)
        LinearLayout centre = new LinearLayout(this);
        centre.setOrientation(LinearLayout.VERTICAL);
        centre.setGravity(Gravity.CENTER);

        orb = new OrbView(this);
        LinearLayout.LayoutParams orbLp = new LinearLayout.LayoutParams(dp(252), dp(252));
        orbLp.gravity = Gravity.CENTER;
        orb.setLayoutParams(orbLp);
        orb.setOnClickListener(v -> safe(this::onOrbTap));
        // v1.9: long-press opens the orb-photo menu (Change / Remove
        // photo). Returning true consumes the gesture, so the tap
        // action (download / cancel, v1.7) never fires after a
        // long-press.
        orb.setOnLongClickListener(v -> { safe(this::showOrbPhotoMenu); return true; });
        centre.addView(orb);

        // One grey status line directly under the orb. MATCH_PARENT wide
        // with centred text and NO letter-spacing — the hint (v1.8) and
        // every status message must fit on one line, and the narrow
        // WRAP_CONTENT + letter-spacing pattern clipped "VIDEOS" to
        // "VI" on this user's phone in v1.1-v1.4. maxLines=1 so the
        // hint can never wrap onto a second line.
        statusText = new TextView(this);
        statusText.setTextSize(13);
        statusText.setTextColor(MUTED);
        statusText.setGravity(Gravity.CENTER);
        statusText.setMaxLines(1);
        statusText.setMinHeight(dp(20));
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(24);
        centre.addView(statusText, stLp);
        showIdleHint(); // app opens in the idle state: show the hint

        // v1.6: "Choose quality" label, shown only when the video has 2+
        // quality options. Same small muted style as the status line;
        // GONE the rest of the time so it takes no space (single-option
        // videos auto-download and never show it).
        qualityLabel = new TextView(this);
        qualityLabel.setText("Choose quality");
        qualityLabel.setTextSize(13);
        qualityLabel.setTextColor(MUTED);
        qualityLabel.setGravity(Gravity.CENTER);
        qualityLabel.setVisibility(View.GONE);
        LinearLayout.LayoutParams qlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qlLp.topMargin = dp(12);
        centre.addView(qualityLabel, qlLp);

        qualityBox = new LinearLayout(this);
        qualityBox.setOrientation(LinearLayout.HORIZONTAL);
        qualityBox.setGravity(Gravity.CENTER);
        qualityBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qLp.topMargin = dp(12);
        centre.addView(qualityBox, qLp);

        FrameLayout.LayoutParams centreLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        centreLp.leftMargin = dp(24);
        centreLp.rightMargin = dp(24);
        root.addView(centre, centreLp);

        // Bottom: dots icon + VIDEOS, pinned above the navigation bar.
        //
        // v1.5 ROOT-CAUSE FIX — the label showed only "VI" on the
        // user's phone in v1.1, v1.2 AND v1.4, even after v1.2 rebuilt
        // the screen as a FrameLayout with a 64dp bottom margin. The
        // margin was never the problem: in the user's v1.4 screenshot
        // the dots render complete and centred, the "VI" is vertically
        // complete with empty space below it, and only the label's
        // width is wrong. What the three broken versions shared was
        // the label itself: a WRAP_CONTENT TextView with letterSpacing
        // (0.35) inside a WRAP_CONTENT vertical column whose only
        // other child is the 28dp dots icon. On the user's phone the
        // letter-spaced text is measured too narrow, so the label was
        // laid out in a ~20dp-wide box and everything after "VI" was
        // clipped — while the title ("LINK-SAVE", letterSpacing 0.45)
        // and the credit line render fully, because their TextViews
        // have wide fixed widths that no measurement quirk can shrink.
        //
        // So the label no longer depends on wrap_content measurement
        // at all: the whole bottom bar is MATCH_PARENT wide, the label
        // is MATCH_PARENT wide with centred text, and the dots stay
        // centred by gravity. "VIDEOS" (~65dp at 13sp / 0.30 spacing)
        // cannot be clipped inside a ~400dp-wide TextView on any
        // device, font scale, or OEM skin. Letter spacing eased
        // 0.35 -> 0.30 for good measure. The 64dp bottom margin stays:
        // on a 1080x2400 phone (~823dp tall) the bar occupies
        // y ~= 689-759dp, clear of the centred orb block (ends
        // ~589dp even with the PLAY VIDEO pill showing) and 16dp
        // above the ~48dp navigation bar.
        LinearLayout vids = new LinearLayout(this);
        vids.setOrientation(LinearLayout.VERTICAL);
        vids.setGravity(Gravity.CENTER);
        DotsView dots = new DotsView(this);
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        dotsLp.gravity = Gravity.CENTER;
        dots.setLayoutParams(dotsLp);
        vids.addView(dots);
        TextView vidsLabel = new TextView(this);
        vidsLabel.setText("VIDEOS");
        vidsLabel.setTextSize(13);
        vidsLabel.setTextColor(GREEN);
        vidsLabel.setLetterSpacing(0.30f);
        vidsLabel.setGravity(Gravity.CENTER);
        vidsLabel.setPadding(0, dp(8), 0, 0);
        vidsLabel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        vids.addView(vidsLabel);
        vids.setPadding(dp(24), dp(8), dp(24), dp(8));
        vids.setOnClickListener(v -> safe(this::showVideosScreen));
        FrameLayout.LayoutParams vidsLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        vidsLp.bottomMargin = dp(64);
        root.addView(vids, vidsLp);

        return root;
    }

    // ---------------- videos screen (Green Hole 04/04) ----------------

    private LinearLayout buildVideosScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(24), dp(16), dp(24), dp(72));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = new TextView(this);
        h.setText("VIDEOS");
        h.setTextSize(20);
        h.setTextColor(GREEN);
        h.setLetterSpacing(0.3f);
        try {
            h.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        } catch (Throwable ignored) {}
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        h.setLayoutParams(hLp);
        head.addView(h);

        TrashView trash = new TrashView(this);
        trash.setLayoutParams(new LinearLayout.LayoutParams(dp(26), dp(26)));
        trash.setPadding(dp(4), dp(4), dp(4), dp(4));
        // v1.13: trash no longer deletes everything — first tap enters
        // tick-selection mode, second tap deletes only the ticked ones.
        trash.setOnClickListener(v -> safe(this::onTrashTap));
        head.addView(trash);
        root.addView(head);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        sLp.topMargin = dp(20);
        videosList = new LinearLayout(this);
        videosList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(videosList, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, sLp);
        return root;
    }

    // ---------------- UI helpers ----------------

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Run a UI action with a safety net: never crash, show one short message. */
    private void safe(Runnable r) {
        try { r.run(); } catch (Throwable t) { setStatus("Try again"); }
    }

    private void setStatus(String s) {
        try {
            if (statusText != null) {
                // Every non-hint state uses the normal 13sp size; the
                // hint is 11sp (see showIdleHint), so any status change
                // restores 13sp first — otherwise a status message
                // shown after the hint would inherit the small size.
                statusText.setTextSize(13);
                statusText.setText(s == null ? "" : s);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * v1.8 idle hint (the user's request): "Copy video link and then
     * tap on ball to download video", exactly one line, directly under
     * the orb, in the same position and muted style as the status line.
     * Shown ONLY in the idle state — on launch and when the app returns
     * to idle (e.g. after cancelling the last download). Every other
     * state replaces it through setStatus, which restores 13sp.
     *
     * One-line sizing: the 54-char hint at 11sp is ~297dp wide (see
     * LinkUtil.estimateTextWidthDp), inside the ~352dp usable width
     * of the full-width status TextView on a ~400dp phone; at 13sp it
     * would be ~351dp and clip. The posted fit check is a safety net
     * for large font scales / narrower screens: it measures the real
     * rendered width and shrinks the size (never below 8sp) until the
     * hint fits — and only if the hint is still the text on screen, so
     * a status change in between is never resized by mistake.
     */
    private void showIdleHint() {
        try {
            if (statusText == null) return;
            statusText.setTextSize(LinkUtil.IDLE_HINT_SIZE_SP);
            statusText.setText(LinkUtil.IDLE_HINT);
            statusText.post(() -> {
                try {
                    if (statusText == null) return;
                    if (!LinkUtil.IDLE_HINT.equals(String.valueOf(statusText.getText()))) {
                        return; // a status change replaced the hint already
                    }
                    int w = statusText.getWidth();
                    if (w <= 0) return;
                    float density = getResources().getDisplayMetrics().scaledDensity;
                    float size = LinkUtil.IDLE_HINT_SIZE_SP;
                    Paint p = new Paint();
                    p.setTypeface(statusText.getTypeface());
                    p.setTextSize(size * density);
                    while (p.measureText(LinkUtil.IDLE_HINT) > w && size > 8f) {
                        size -= 0.5f;
                        p.setTextSize(size * density);
                    }
                    if (size != LinkUtil.IDLE_HINT_SIZE_SP) statusText.setTextSize(size);
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private void toast(String msg) {
        try { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }

    private GradientDrawable greenPill() {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF10B981, 0xFF059669});
        d.setCornerRadius(dp(24));
        return d;
    }

    private TextView pill(String text, boolean darkText) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(darkText ? 0xFF052E16 : Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setBackground(greenPill());
        b.setPadding(dp(22), dp(11), dp(22), dp(11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(5), dp(4), dp(5), dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    private void showQualityPills(Job job, Resolved r) {
        try {
            qualityBox.removeAllViews();
            if (r == null || r.formats == null) { qualityBox.setVisibility(View.GONE); return; }
            for (Format f : r.formats) {
                if (f == null || f.url == null || f.url.isEmpty()) continue;
                TextView p = pill(LinkUtil.shortLabel(f.label, f.kind, f.note), false);
                p.setOnClickListener(v -> safe(() -> startDownload(job, r, f)));
                qualityBox.addView(p);
            }
            qualityBox.setVisibility(qualityBox.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        } catch (Throwable t) {
            qualityBox.setVisibility(View.GONE);
        }
    }

    private void showSinglePill(String text, boolean darkText, Runnable action) {
        try {
            showQualityLabel(false); // Try Again / PLAY VIDEO never need the label
            qualityBox.removeAllViews();
            TextView p = pill(text, darkText);
            p.setOnClickListener(v -> safe(action));
            qualityBox.addView(p);
            qualityBox.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) {}
    }

    private void hidePills() {
        try {
            qualityBox.removeAllViews();
            qualityBox.setVisibility(View.GONE);
            showQualityLabel(false);
        } catch (Throwable ignored) {}
    }

    /** v1.6: show/hide the small "Choose quality" label above the pills. */
    private void showQualityLabel(boolean show) {
        try {
            if (qualityLabel != null) {
                qualityLabel.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        } catch (Throwable ignored) {}
    }

    // ---------------- clipboard / orb ----------------

    // Last clipboard content + its timestamp, so the orb only reads the
    // clipboard when it actually changed (see clipboardText()).
    private long lastClipTs = -1;
    private String lastClipText;

    /**
     * Clipboard text, read at most once per copied link. Android 12+
     * shows a system "pasted from your clipboard" toast EVERY time an
     * app reads clipboard data — that toast is drawn by Android, not
     * by LinkSave, and no app can resize or hide it. Reading the clip
     * *description* (which carries a timestamp) does NOT trigger the
     * toast, so we only call getPrimaryClip() when the clipboard
     * actually changed: the toast then appears at most once per newly
     * copied link instead of on every orb tap. (getTimestamp() needs
     * API 26+; older phones don't show the toast at all, so they just
     * read directly.)
     */
    private String clipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    ClipDescription desc = cm.getPrimaryClipDescription();
                    long ts = desc == null ? 0 : desc.getTimestamp();
                    if (ts != 0 && ts == lastClipTs && lastClipText != null) {
                        return lastClipText; // unchanged — no re-read, no toast
                    }
                    ClipData clip = cm.getPrimaryClip();
                    if (clip != null && clip.getItemCount() > 0) {
                        CharSequence t = clip.getItemAt(0).getText();
                        lastClipTs = ts;
                        lastClipText = t == null ? null : t.toString().trim();
                        return lastClipText;
                    }
                    return null;
                } catch (Throwable ignored) { /* fall through to direct read */ }
            }
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence t = clip.getItemAt(0).getText();
                return t == null ? null : t.toString().trim();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void onOrbTap() {
        // extractUrl finds the link even when the clipboard holds extra
        // text around it (a caption line, a trailing newline, ...).
        // The URL is kept in memory only — never shown on screen.
        String url = LinkUtil.extractUrl(clipboardText());
        if (url == null) {
            showMessage("No link copied");
            return;
        }
        onLinkTap(url);
    }

    /**
     * The v1.7 orb-tap rules (the user's rule), shared by the orb tap
     * and the Share intent — see LinkUtil.orbTapAction (unit-tested):
     * same link while fetching/downloading -> cancel it; same link
     * while its choice is on screen -> do nothing; new link -> start
     * it in parallel (first download keeps running); new link at the
     * 4-download cap -> one line, "4 downloads max".
     */
    private void onLinkTap(String url) {
        if (url == null || url.isEmpty()) return;
        Job existing = jobs.get(url);
        int state = existing == null ? -1 : existing.state;
        int action = LinkUtil.orbTapAction(existing != null, state,
                activeDownloadCount(), LinkUtil.MAX_DOWNLOADS);
        switch (action) {
            case LinkUtil.TAP_CANCEL:
                cancelJob(existing);
                break;
            case LinkUtil.TAP_IGNORE:
                break; // choice already on screen — nothing to do
            case LinkUtil.TAP_CAP:
                toast("4 downloads max");
                break;
            case LinkUtil.TAP_START:
            default:
                startFetch(url);
                break;
        }
    }

    /**
     * Cancel one job for real (v1.7): the token disconnects every live
     * connection at once (fetch connections, FastDownload probe/chunk
     * threads, YouTube's concurrent video+audio parts), the task
     * itself is interrupted, and the download task's finally block
     * deletes the partial/temp files. A cancelled download stops
     * quietly — no error popup. If it was the only active work the
     * app goes back to idle; if other downloads are still running,
     * progress continues with the remaining ones.
     */
    private void cancelJob(Job job) {
        if (job == null) return;
        jobs.remove(job.url);
        job.cancelled = true;
        try { job.token.cancel(); } catch (Throwable ignored) {}
        try {
            if (job.future != null) job.future.cancel(true);
        } catch (Throwable ignored) {}
        if (isIdle()) {
            orb.setIdle();
            showIdleHint(); // back to idle: the hint comes back (v1.8)
            hidePills();
        } else if (anyDownloading()) {
            refreshProgressUI(); // aggregate without the cancelled job
        } else if (activeFetchCount() > 0) {
            orb.setSpin();
            setStatus("Please wait...");
        } else {
            // Only a quality choice is left on screen: back to the idle
            // orb and an empty status line, but keep the pills — they
            // belong to the choosing job, not the cancelled one.
            orb.setIdle();
            setStatus("");
        }
    }

    /**
     * One short message, without clobbering the progress line: while
     * anything is active it goes to a toast; when idle it goes to the
     * status line, as before.
     */
    private void showMessage(String msg) {
        if (jobs.isEmpty()) setStatus(msg);
        else toast(msg);
    }

    // ---------------- custom orb photo (v1.9) ----------------
    //
    // The user's request: long-press the orb, pick a photo, and it
    // sits inside the ball (centre-cropped to a circle) while the
    // rings keep rotating around it. The photo itself stays upright
    // — a rotating face would flip upside down. The photo is copied
    // into app-private internal storage (at most 512px, JPEG ~85),
    // so it persists across restarts and survives the gallery photo
    // being moved or deleted; it never leaves the device — no upload,
    // no network. The picker is ACTION_GET_CONTENT, so no storage
    // permission is needed on any API level. No button or text is
    // added to the main screen: the menu only exists while open.

    private File orbPhotoFile() {
        return new File(getFilesDir(), LinkUtil.ORB_PHOTO_FILE);
    }

    private boolean orbPhotoPrefSet() {
        try {
            return getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean(LinkUtil.ORB_PHOTO_PREF, false);
        } catch (Throwable ignored) { return false; }
    }

    private void setOrbPhotoPref(boolean set) {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(LinkUtil.ORB_PHOTO_PREF, set).apply();
        } catch (Throwable ignored) {}
    }

    /** A custom orb photo is set AND its stored file still exists. */
    private boolean hasCustomPhoto() {
        return LinkUtil.hasOrbPhoto(orbPhotoPrefSet(), orbPhotoFile().exists());
    }

    /**
     * The long-press menu: "Change photo" (always) and "Remove photo"
     * (only when a custom photo is set). Dark popup, matching the
     * app's black/green theme (the app theme is Theme.Material, so
     * the popup renders dark).
     */
    private void showOrbPhotoMenu() {
        try {
            PopupMenu menu = new PopupMenu(this, orb);
            menu.getMenu().add(0, 1, 0, "Change photo");
            if (hasCustomPhoto()) {
                menu.getMenu().add(0, 2, 0, "Remove photo");
            }
            menu.setOnMenuItemClickListener(item -> {
                try {
                    if (item.getItemId() == 1) openOrbPhotoPicker();
                    else if (item.getItemId() == 2) removeOrbPhoto();
                } catch (Throwable ignored) {}
                return true;
            });
            menu.show();
        } catch (Throwable ignored) {}
    }

    /** System gallery picker — no storage permission needed. */
    private void openOrbPhotoPicker() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, REQ_PICK_ORB_PHOTO);
        } catch (Throwable t) {
            showMessage("Try again");
        }
    }

    /** Remove the custom orb photo: the green orb is back at once. */
    private void removeOrbPhoto() {
        try {
            setOrbPhotoPref(false);
            try { orbPhotoFile().delete(); } catch (Throwable ignored) {}
            setOrbPhotoBitmap(null);
        } catch (Throwable ignored) {}
    }

    /**
     * Load the stored orb photo on launch. If the file is missing or
     * corrupt, fall back silently to the green orb — the preference
     * is cleared so the app does not keep trying a broken file.
     */
    private void loadOrbPhoto() {
        try {
            if (!hasCustomPhoto()) return;
            Bitmap b = decodeOrbPhotoFile(orbPhotoFile());
            if (b == null) {
                try { orbPhotoFile().delete(); } catch (Throwable ignored) {}
                setOrbPhotoPref(false);
                return;
            }
            setOrbPhotoBitmap(b);
        } catch (Throwable ignored) {}
    }

    /** Swap the bitmap the orb draws (recycles the previous one). Main thread only. */
    private void setOrbPhotoBitmap(Bitmap b) {
        try {
            Bitmap old = orbPhotoBitmap;
            orbPhotoBitmap = b;
            if (orb != null) {
                if (b != null) orb.setPhoto(b);
                else orb.clearPhoto();
            }
            if (old != null && old != b && !old.isRecycled()) {
                try { old.recycle(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Decode the stored photo file into a square bitmap (<= 512px).
     * The stored file is already a square, but it is cropped and
     * downscaled again anyway, so a hand-placed file can never break
     * the orb. Returns null for anything unreadable. Never throws.
     */
    private Bitmap decodeOrbPhotoFile(File f) {
        try {
            if (f == null || !f.exists() || f.length() <= 0) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            o.inJustDecodeBounds = false;
            o.inSampleSize = LinkUtil.inSampleSize(o.outWidth, o.outHeight,
                    LinkUtil.ORB_PHOTO_MAX_PX);
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (b == null) return null;
            return squareBitmap(b, LinkUtil.ORB_PHOTO_MAX_PX);
        } catch (Throwable ignored) { // includes OutOfMemoryError
            return null;
        }
    }

    /**
     * Save the picked photo as the orb photo, on a worker thread:
     * decode with inSampleSize (a 4000x3000 camera photo decodes at
     * 1000x750, never full-size), apply EXIF rotation so phone photos
     * are upright, centre-crop to a square, downscale to at most
     * 512px, and write JPEG ~85 to app-private internal storage.
     * Written to a temp file and renamed, so a crash mid-write never
     * leaves a half-written photo. Any failure shows one short
     * message, never a crash.
     */
    private void saveOrbPhoto(final Uri uri) {
        if (uri == null) return;
        bg.submit(() -> {
            try {
                Bitmap square = decodePickedPhoto(uri);
                if (square == null) {
                    post(() -> showMessage("Try again"));
                    return;
                }
                File tmp = new File(getFilesDir(), LinkUtil.ORB_PHOTO_FILE + ".tmp");
                File dst = orbPhotoFile();
                boolean ok = false;
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    ok = square.compress(Bitmap.CompressFormat.JPEG,
                            LinkUtil.ORB_PHOTO_JPEG_QUALITY, out);
                } catch (Throwable ignored) { ok = false; }
                if (!ok || !tmp.exists() || tmp.length() <= 0) {
                    try { tmp.delete(); } catch (Throwable ignored) {}
                    try { square.recycle(); } catch (Throwable ignored) {}
                    post(() -> showMessage("Try again"));
                    return;
                }
                // Atomic swap: rename over the old photo (delete first
                // — renameTo fails if the destination already exists).
                try { dst.delete(); } catch (Throwable ignored) {}
                if (!tmp.renameTo(dst)) {
                    try { tmp.delete(); } catch (Throwable ignored) {}
                    try { square.recycle(); } catch (Throwable ignored) {}
                    post(() -> showMessage("Try again"));
                    return;
                }
                setOrbPhotoPref(true);
                post(() -> setOrbPhotoBitmap(square));
            } catch (Throwable t) {
                post(() -> showMessage("Try again"));
            }
        });
    }

    /**
     * Decode a picked image into a square bitmap (<= 512px) for the
     * orb: bounds first, inSampleSize so huge photos decode small,
     * EXIF rotation applied (phone photos are often stored rotated),
     * then a centre square crop + downscale. Returns null for
     * anything unreadable. Never throws.
     */
    private Bitmap decodePickedPhoto(Uri uri) {
        try {
            ContentResolver cr = getContentResolver();
            if (cr == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, o);
            }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            o.inJustDecodeBounds = false;
            o.inSampleSize = LinkUtil.inSampleSize(o.outWidth, o.outHeight,
                    LinkUtil.ORB_PHOTO_MAX_PX);
            Bitmap decoded;
            try (InputStream in = cr.openInputStream(uri)) {
                if (in == null) return null;
                decoded = BitmapFactory.decodeStream(in, null, o);
            }
            if (decoded == null) return null;
            // EXIF rotation: camera photos are often stored sideways
            // with an orientation tag. Read from a fresh stream —
            // decodeStream already consumed the previous one.
            int rotation = 0;
            try (InputStream in = cr.openInputStream(uri)) {
                if (in != null) {
                    ExifInterface exif = new ExifInterface(in);
                    int orient = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL);
                    if (orient == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
                    else if (orient == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
                    else if (orient == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
                }
            } catch (Throwable ignored) {}
            Bitmap upright = decoded;
            if (rotation != 0) {
                try {
                    Matrix m = new Matrix();
                    m.postRotate(rotation);
                    Bitmap r = Bitmap.createBitmap(decoded, 0, 0,
                            decoded.getWidth(), decoded.getHeight(), m, true);
                    if (r != null) {
                        upright = r;
                        try { decoded.recycle(); } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
            return squareBitmap(upright, LinkUtil.ORB_PHOTO_MAX_PX);
        } catch (Throwable ignored) { // includes OutOfMemoryError
            return null;
        }
    }

    /**
     * Centre-crop a bitmap to a square and downscale it to at most
     * maxPx (never upscale). The input bitmap is recycled when a new
     * bitmap is produced. Returns null for bad input. Never throws.
     */
    private static Bitmap squareBitmap(Bitmap src, int maxPx) {
        try {
            if (src == null || src.isRecycled()) return null;
            int w = src.getWidth(), h = src.getHeight();
            if (w <= 0 || h <= 0 || maxPx <= 0) {
                try { src.recycle(); } catch (Throwable ignored) {}
                return null;
            }
            int[] crop = LinkUtil.centreCropRect(w, h);
            int size = crop[2];
            Bitmap sq = src;
            if (crop[0] != 0 || crop[1] != 0 || size != w || size != h) {
                sq = Bitmap.createBitmap(src, crop[0], crop[1], size, size);
                if (sq == null) {
                    try { src.recycle(); } catch (Throwable ignored) {}
                    return null;
                }
                try { src.recycle(); } catch (Throwable ignored) {}
            }
            if (size > maxPx) {
                Bitmap scaled = Bitmap.createScaledBitmap(sq, maxPx, maxPx, true);
                if (scaled == null) return sq;
                if (scaled != sq) {
                    try { sq.recycle(); } catch (Throwable ignored) {}
                }
                return scaled;
            }
            return sq;
        } catch (Throwable ignored) { // includes OutOfMemoryError
            return null;
        }
    }

    // ---------------- fetch flow ----------------

    private void startFetch(final String rawUrl) {
        if (rawUrl == null) return;
        final String raw = rawUrl.trim();
        if (jobs.containsKey(raw)) return; // already fetching/choosing/downloading
        String platform = LinkUtil.detectPlatform(raw);
        if (platform == null) {
            showMessage("Invalid link");
            return;
        }
        hideKeyboard();
        // A pending quality choice is replaced by the new fetch (v1.7
        // rule 5): drop the choosing job, its pills go away with it.
        Job choosing = choosingJob();
        if (choosing != null) {
            jobs.remove(choosing.url);
            choosing.cancelled = true;
        }
        final Job job = new Job(jobSeq.incrementAndGet(), raw);
        jobs.put(raw, job);
        hidePills(); // also clears a "Saved ✓" + PLAY VIDEO state
        orb.setSpin();
        setStatus("Please wait...");
        job.future = bg.submit(() -> {
            Engine.setCurrentToken(job.token);
            try {
                Resolved r = Engine.resolveByPlatform(platform, raw);
                post(() -> {
                    if (job.cancelled || jobs.get(job.url) != job) return;
                    if (r == null) {
                        onFetchFailed(job, "Can't fetch video");
                        return;
                    }
                    showResult(job, r);
                });
            } catch (Throwable e) {
                post(() -> {
                    if (job.cancelled || jobs.get(job.url) != job) return;
                    onFetchFailed(job, Engine.classifyFetchError(e));
                });
            } finally {
                Engine.clearCurrentToken();
            }
        });
    }

    /** Fetch failed (or returned nothing) for one job. */
    private void onFetchFailed(Job job, String msg) {
        jobs.remove(job.url);
        if (isIdle()) {
            orb.setIdle();
            setStatus(msg);
        } else {
            // Other work is running — don't touch its UI, just say so.
            toast(msg);
            if (anyDownloading()) refreshProgressUI();
            else if (activeFetchCount() > 0) {
                orb.setSpin();
                setStatus("Please wait...");
            } else {
                // Only a quality choice is left: idle orb, empty status,
                // pills untouched.
                orb.setIdle();
                setStatus("");
            }
        }
    }

    /**
     * Aggregate progress across all active downloads (v1.7): the orb
     * % and the "Please wait... X / Y MB" line show the sum of bytes
     * done over the sum of known totals. When no total is known yet,
     * the spinner shows with "X MB" instead. Main thread only.
     */
    private void refreshProgressUI() {
        try {
            List<Job> dls = new ArrayList<>();
            for (Job j : jobs.values()) {
                if (j != null && j.state == LinkUtil.JOB_DOWNLOADING) dls.add(j);
            }
            if (dls.isEmpty()) return;
            // If every active download is in the YouTube merge step (no
            // bytes left to count), show the spinner as before instead
            // of a stuck 100%.
            boolean allMerging = true;
            for (Job j : dls) {
                if (!j.merging) { allMerging = false; break; }
            }
            if (allMerging) {
                orb.setSpin();
                setStatus("Please wait...");
                return;
            }
            long[] done = new long[dls.size()];
            long[] total = new long[dls.size()];
            for (int i = 0; i < dls.size(); i++) {
                done[i] = dls.get(i).done;
                total[i] = dls.get(i).total;
            }
            LinkUtil.Aggregate agg = LinkUtil.aggregateProgress(done, total);
            if (agg.known) {
                orb.setProgress(agg.pct);
                setStatus("Please wait...  " + LinkUtil.formatMB(agg.done)
                        + " / " + LinkUtil.formatMB(agg.total) + " MB");
            } else {
                orb.setSpin();
                setStatus("Please wait...  " + LinkUtil.formatMB(agg.done) + " MB");
            }
        } catch (Throwable ignored) {}
    }

    /** Progress callbacks from worker threads funnel through here. */
    private void requestProgressRefresh() {
        post(() -> {
            long now = System.currentTimeMillis();
            if (now - lastProgressUi < 200) return; // throttle UI updates
            lastProgressUi = now;
            if (anyDownloading()) refreshProgressUI();
        });
    }

    /** Post to the main thread only if the activity is still alive. */
    private void post(Runnable r) {
        try {
            main.post(() -> {
                if (destroyed || isFinishing()) return;
                try { r.run(); } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    /**
     * v1.6 smart flow (the user's rule, applies to all four platforms
     * and to both entry points — orb tap and Share intent both funnel
     * here through startFetch): 0 usable options -> error state as
     * before; exactly 1 -> the download starts immediately, no asking
     * (one option is not a choice); 2+ -> a small "Choose quality"
     * label above the pills and the user picks one. The decision itself
     * lives in LinkUtil.flowForOptionCount (unit-tested).
     *
     * v1.7: the result belongs to one job. While other downloads are
     * running, the orb and status line keep showing their aggregate
     * progress — a new result only adds its pills below, and an error
     * for this link is a toast instead of replacing the progress.
     * The one exception is the cap race: if the 4-download cap filled
     * up while this link was fetching, a single option is kept as a
     * tappable pill (no "Choose quality" label — one option is still
     * not a choice) so the user can tap it once a slot frees up.
     */
    private void showResult(Job job, Resolved r) {
        if (job == null || job.cancelled || jobs.get(job.url) != job) return;
        job.resolved = r;
        if (r == null) {
            onFetchFailed(job, "Can't fetch video");
            return;
        }
        List<Format> valid = Engine.validFormats(r);
        int flow = LinkUtil.flowForOptionCount(valid.size());
        if (r.busy || flow == LinkUtil.FLOW_ERROR) {
            jobs.remove(job.url);
            if (isIdle()) {
                orb.setIdle();
                setStatus("Server busy");
                final String url = job.url;
                showSinglePill("Try Again", false, () -> startFetch(url));
            } else {
                toast("Server busy");
            }
            return;
        }
        if (!anyDownloading()) {
            orb.setIdle();
            setStatus("");
        }
        if (flow == LinkUtil.FLOW_AUTO_DOWNLOAD) {
            // v1.13: a lone audio-only option is NOT auto-downloaded —
            // the user asked for a video, so show the one MP3 pill
            // (no "Choose quality" label — one option is still not a
            // choice) and let him tap it only if he wants the audio.
            if (LinkUtil.isAudioOnlySingle(valid)) {
                dropOtherChoosing(job);
                job.state = LinkUtil.JOB_CHOOSING;
                showQualityLabel(false);
                showQualityPills(job, r);
                return;
            }
            // Single option: download it straight away — straight to
            // the progress state (orb % + "Please wait... X / Y MB").
            if (activeDownloadCount() < LinkUtil.MAX_DOWNLOADS) {
                startDownload(job, r, valid.get(0));
            } else {
                dropOtherChoosing(job);
                job.state = LinkUtil.JOB_CHOOSING;
                showQualityPills(job, r); // single tappable pill, no label
                toast("4 downloads max");
            }
            return;
        }
        dropOtherChoosing(job);
        job.state = LinkUtil.JOB_CHOOSING;
        showQualityLabel(true);
        showQualityPills(job, r);
    }

    /**
     * At most one quality choice can be on screen: when a job becomes
     * CHOOSING, any other choosing job loses its choice (its pills are
     * replaced by this job's). This covers two fetches finishing out
     * of order — startFetch already drops the choosing job when a NEW
     * fetch starts, but a fetch that started earlier can still finish
     * after another job became choosing.
     */
    private void dropOtherChoosing(Job keep) {
        Job other = choosingJob();
        if (other != null && other != keep) {
            jobs.remove(other.url);
            other.cancelled = true;
        }
    }

    /**
     * Start one job's download (from a quality pill or the v1.6
     * auto-download). The cap is checked again here — it can fill up
     * between the fetch and the pill tap — and a capped tap just says
     * so and keeps the pills, so the user can tap again once a slot
     * frees up.
     */
    private void startDownload(Job job, Resolved r, Format f) {
        if (job == null || jobs.get(job.url) != job) return;
        if (r == null || f == null || f.url == null || f.url.isEmpty()) {
            showMessage("Download failed");
            return;
        }
        if (activeDownloadCount() >= LinkUtil.MAX_DOWNLOADS) {
            toast("4 downloads max");
            return;
        }
        job.state = LinkUtil.JOB_DOWNLOADING;
        job.resolved = r;
        hidePills(); // the choice is made; the pills belong to this job
        if (activeDownloadCount() == 1) {
            // First active download: respond at once; with others
            // already running the aggregate progress just keeps going.
            orb.setSpin();
            setStatus("Please wait...");
        }
        startEngineDownload(job, r, f);
    }

    /**
     * One job's download, through the shared Engine (v1.11 refactor,
     * kept in v1.12): the single/merged download, with this job's
     * progress feeding the orb's aggregate. Behaviour is unchanged
     * from the old startSingleDownload / startYouTubeMerged — the
     * Engine runs the same code, this wrapper only reports progress
     * and completion back to the UI.
     */
    private void startEngineDownload(final Job job, final Resolved r, final Format f) {
        job.future = bg.submit(() -> {
            try {
                Engine.Result res = Engine.downloadToDownloads(this, r, f,
                        new Engine.Listener() {
                            @Override public void onProgress(long done, long total) {
                                job.done = done;
                                job.total = total;
                                requestProgressRefresh();
                            }
                            @Override public void onMerging() {
                                job.merging = true;
                                requestProgressRefresh();
                            }
                        }, job.token);
                if (job.cancelled) return; // cancelled — stop quietly
                JSONObject rec = Engine.makeRecord(res.uri, r, f, res.fileName);
                String kind = f.isMerge() ? "video" : f.kind;
                post(() -> onDownloadFinished(job, res.uri, kind, rec));
            } catch (Throwable e) {
                post(() -> onDownloadFailed(job, e));
            }
        });
    }

    /** One download finished: record it, then update the shared UI. */
    private void onDownloadFinished(Job job, Uri saved, String kind, JSONObject rec) {
        if (job == null || job.cancelled || jobs.get(job.url) != job) return;
        jobs.remove(job.url);
        // Records are saved on the main thread, so parallel completions
        // can never interleave a load/add/save and lose a record.
        try {
            if (rec != null) {
                List<JSONObject> list = loadRecords();
                list.add(0, rec);
                saveRecords(list);
            }
        } catch (Throwable ignored) {}
        renderDownloads();
        if (anyDownloading()) {
            // Others still running: stay in the progress state.
            refreshProgressUI();
            return;
        }
        if (activeFetchCount() > 0) {
            orb.setSpin();
            setStatus("Please wait...");
            return;
        }
        Job choosing = choosingJob();
        if (choosing != null) {
            // A quality choice is on screen — keep its pills, just say
            // the file saved; it is in VIDEOS either way.
            orb.setIdle();
            setStatus("Saved ✓");
            return;
        }
        // ALL downloads finished: PLAY VIDEO plays the most recently
        // finished file (v1.7).
        orb.setIdle();
        setStatus("Saved ✓");
        final Uri uri = saved;
        final String k = kind;
        showSinglePill("▶  PLAY VIDEO", true, () -> playUri(uri, k));
    }

    /** One download failed: one line, without disturbing other work. */
    private void onDownloadFailed(Job job, Throwable e) {
        if (job == null || job.cancelled || jobs.get(job.url) != job) return;
        jobs.remove(job.url);
        String msg = Engine.classifyDownloadError(e);
        if (isIdle()) {
            orb.setIdle();
            setStatus(msg);
        } else {
            toast(msg);
            if (anyDownloading()) refreshProgressUI();
            else if (activeFetchCount() > 0) {
                orb.setSpin();
                setStatus("Please wait...");
            } else {
                // Only a quality choice is left: idle orb, empty status,
                // pills untouched.
                orb.setIdle();
                setStatus("");
            }
        }
    }

    // ---------------- saved videos ----------------

    private List<JSONObject> loadRecords() {
        return Records.load(this);
    }

    private void saveRecords(List<JSONObject> list) {
        Records.save(this, list);
    }

    private void renderDownloads() {
        try {
            if (videosList == null) return;
            videosList.removeAllViews();
            List<JSONObject> list = loadRecords();
            for (JSONObject rec : list) {
                if (rec == null) continue;
                final long dmId = rec.optLong("dmId", -1);
                final String kind = rec.optString("kind", "video");
                final String uriStr = rec.optString("uri", "");
                final boolean isUri = uriStr != null && !uriStr.isEmpty();
                String title = rec.optString("title", "Video");
                if (title == null || title.isEmpty()) title = "Video";
                if (title.length() > 30) title = title.substring(0, 30) + "…";

                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(CARD);
                bg.setCornerRadius(dp(18));
                card.setBackground(bg);
                card.setPadding(dp(20), dp(18), dp(12), dp(18));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(12);
                card.setLayoutParams(lp);

                // v1.13: in tick-selection mode each card starts with
                // a tick box (tapping the card ticks/unticks it
                // instead of playing it). Outside the mode the card
                // looks exactly as before — no tick box at all.
                final String delKey = LinkUtil.deleteKey(uriStr, dmId);
                final TickView tick;
                if (deleteSelecting) {
                    tick = new TickView(this);
                    int tickPx = dp(24);
                    LinearLayout.LayoutParams tickLp = new LinearLayout.LayoutParams(
                            tickPx, tickPx);
                    tickLp.rightMargin = dp(12);
                    tick.setLayoutParams(tickLp);
                    tick.setChecked(deleteSelected.contains(delKey));
                    card.addView(tick);
                } else {
                    tick = null;
                }

                // v1.10: small thumbnail at the start of the card —
                // [thumbnail] [title...] [OPEN]. Placeholder first;
                // the real frame loads asynchronously (see loadThumb).
                ImageView thumb = new ImageView(this);
                int thumbPx = dp(LinkUtil.THUMB_SIZE_DP);
                LinearLayout.LayoutParams thLp = new LinearLayout.LayoutParams(
                        thumbPx, thumbPx);
                thLp.rightMargin = dp(12);
                thumb.setLayoutParams(thLp);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setImageBitmap(placeholderBitmap("audio".equals(kind)));
                card.addView(thumb);
                loadThumb(thumb, rec, kind);

                TextView t = new TextView(this);
                t.setText(title);
                t.setTextSize(16);
                t.setTextColor(TEXT);
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                t.setLayoutParams(tLp);
                card.addView(t);

                TextView open = new TextView(this);
                open.setText("OPEN");
                open.setTextSize(14);
                open.setTypeface(Typeface.DEFAULT_BOLD);
                open.setTextColor(Color.WHITE);
                open.setGravity(Gravity.CENTER);
                open.setBackground(greenPill());
                open.setPadding(dp(26), dp(13), dp(26), dp(13));
                open.setOnClickListener(v -> safe(() -> {
                    if (isUri) playUri(Uri.parse(uriStr), kind);
                    else playDownload(dmId, kind);
                }));
                card.addView(open);

                card.setOnClickListener(v -> safe(() -> {
                    if (deleteSelecting) {
                        if (deleteSelected.contains(delKey)) deleteSelected.remove(delKey);
                        else deleteSelected.add(delKey);
                        if (tick != null) tick.setChecked(deleteSelected.contains(delKey));
                        return;
                    }
                    if (isUri) playUri(Uri.parse(uriStr), kind);
                    else playDownload(dmId, kind);
                }));
                card.setOnLongClickListener(v -> {
                    safe(() -> {
                        if (isUri) showUriCardMenu(rec);
                        else showCardMenu(dmId, kind);
                    });
                    return true;
                });
                videosList.addView(card);
            }
        } catch (Throwable ignored) {}
    }

    // ---------------- VIDEOS thumbnails (v1.10) ----------------

    /**
     * Load a thumbnail into a card's ImageView, asynchronously off
     * the UI thread. The placeholder set by renderDownloads stays
     * until the real frame arrives; if anything fails (missing or
     * corrupt file, audio-only file with no embedded art) the
     * placeholder simply stays — never a crash, never a blank hole,
     * never an error popup. The view is tagged with the record
     * identity, so a late result never lands in a view that
     * renderDownloads has since rebuilt for another record.
     * Lookup order: memory LRU -> disk cache -> MediaMetadataRetriever
     * frame extraction (saved to both caches on success).
     */
    private void loadThumb(final ImageView iv, final JSONObject rec, final String kind) {
        if (iv == null || rec == null) return;
        final boolean audio = "audio".equals(kind);
        final String tag = rec.optString("uri", "") + "|" + rec.optLong("dmId", -1);
        iv.setTag(tag);
        bg.submit(() -> {
            try {
                Uri uri = thumbTargetUri(rec);
                if (uri == null) return; // placeholder stays
                long[] stats = thumbFileStats(uri);
                final String key = LinkUtil.thumbCacheKey(
                        uri.toString(), stats[0], stats[1]);
                Bitmap cached = thumbMemCache.get(key);
                if (cached == null) cached = thumbFromDisk(key);
                if (cached != null) {
                    thumbMemCache.put(key, cached);
                    final Bitmap done = cached;
                    post(() -> {
                        if (tag.equals(iv.getTag())) iv.setImageBitmap(done);
                    });
                    return;
                }
                int thumbPx = dp(LinkUtil.THUMB_SIZE_DP);
                Bitmap thumb = extractVideoThumb(uri, thumbPx, audio);
                if (thumb == null) return; // placeholder stays
                thumbMemCache.put(key, thumb);
                saveThumbToDisk(key, thumb);
                post(() -> {
                    if (tag.equals(iv.getTag())) iv.setImageBitmap(thumb);
                });
            } catch (Throwable ignored) { // includes OutOfMemoryError
            }
        });
    }

    /** The playable URI for a record: its stored URI, or the legacy DownloadManager URI. */
    private Uri thumbTargetUri(JSONObject rec) {
        try {
            String uriStr = rec.optString("uri", "");
            if (uriStr != null && !uriStr.isEmpty()) return Uri.parse(uriStr);
            long dmId = rec.optLong("dmId", -1);
            if (dmId >= 0) return downloadUri(dmId);
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * {size, lastModified} for a URI, best-effort (-1 when unknown):
     * file URIs read the File directly; content URIs ask the
     * resolver for the asset length (no last-modified there, so it
     * stays -1). Both feed the cache key, so a file replaced at the
     * same URI does not keep the old video's thumbnail.
     */
    private long[] thumbFileStats(Uri uri) {
        long[] out = new long[]{-1, -1};
        try {
            if (uri == null) return out;
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                File f = new File(uri.getPath());
                if (f.exists()) {
                    out[0] = f.length();
                    out[1] = f.lastModified();
                }
                return out;
            }
            ContentResolver cr = getContentResolver();
            if (cr == null) return out;
            try (AssetFileDescriptor fd = cr.openAssetFileDescriptor(uri, "r")) {
                if (fd != null) out[0] = fd.getLength();
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return out;
    }

    private File thumbCacheDir() {
        try {
            File dir = new File(getCacheDir(), LinkUtil.THUMB_CACHE_DIR);
            if (!dir.exists()) dir.mkdirs();
            return dir.exists() ? dir : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private File thumbCacheFile(String key) {
        File dir = thumbCacheDir();
        return dir == null ? null : new File(dir, key + ".jpg");
    }

    /** Decode a disk-cached thumbnail (already rounded, already thumb-sized). */
    private Bitmap thumbFromDisk(String key) {
        try {
            File f = thumbCacheFile(key);
            if (f == null || !f.exists() || f.length() <= 0) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int thumbPx = dp(LinkUtil.THUMB_SIZE_DP);
            o.inJustDecodeBounds = false;
            o.inSampleSize = LinkUtil.inSampleSize(o.outWidth, o.outHeight, thumbPx);
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (b == null) return null;
            if (b.getWidth() == thumbPx && b.getHeight() == thumbPx) return b;
            // A hand-placed or stale file: re-square and re-round it
            // so a wrong-sized cache entry can never break the card.
            Bitmap sq = squareBitmap(b, thumbPx);
            if (sq == null) return null;
            return roundCorners(sq, dp(10));
        } catch (Throwable ignored) { // includes OutOfMemoryError
            return null;
        }
    }

    /** Save a thumbnail to the disk cache (temp file + rename, like the orb photo). */
    private void saveThumbToDisk(String key, Bitmap b) {
        try {
            if (key == null || b == null || b.isRecycled()) return;
            File dir = thumbCacheDir();
            if (dir == null) return;
            File tmp = new File(dir, key + ".tmp");
            File dst = new File(dir, key + ".jpg");
            boolean ok = false;
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                ok = b.compress(Bitmap.CompressFormat.JPEG, 80, out);
            } catch (Throwable ignored) { ok = false; }
            if (!ok || !tmp.exists() || tmp.length() <= 0) {
                try { tmp.delete(); } catch (Throwable ignored) {}
                return;
            }
            try { dst.delete(); } catch (Throwable ignored) {}
            if (!tmp.renameTo(dst)) {
                try { tmp.delete(); } catch (Throwable ignored) {}
                return;
            }
            pruneThumbCache();
        } catch (Throwable ignored) {}
    }

    /** Keep the disk cache bounded: delete the oldest files past 100. */
    private void pruneThumbCache() {
        try {
            File dir = thumbCacheDir();
            if (dir == null) return;
            File[] files = dir.listFiles(
                    (d, name) -> name != null && name.endsWith(".jpg"));
            if (files == null || files.length <= LinkUtil.THUMB_DISK_MAX) return;
            Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < files.length - LinkUtil.THUMB_DISK_MAX; i++) {
                try { files[i].delete(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Extract a thumbnail frame from a video file with
     * MediaMetadataRetriever: ~1s in (or 10% of the duration for a
     * very short clip — see LinkUtil.thumbFrameTimeUs), falling back
     * to frame 0. Audio-only files have no video frame, so their
     * embedded album art is used when present. The result is a
     * square, thumb-sized bitmap with rounded corners, or null when
     * nothing usable could be extracted (the caller keeps the
     * placeholder). The full-size frame is recycled once the small
     * square is made — only the thumbnail itself is kept. Never throws.
     */
    private Bitmap extractVideoThumb(Uri uri, int thumbPx, boolean audio) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                r.setDataSource(uri.getPath());
            } else {
                r.setDataSource(this, uri);
            }
            Bitmap frame = null;
            if (!audio) {
                long durMs = -1;
                try {
                    String d = r.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (d != null) durMs = Long.parseLong(d);
                } catch (Throwable ignored) {}
                long t = LinkUtil.thumbFrameTimeUs(durMs);
                try {
                    frame = r.getFrameAtTime(t,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                } catch (Throwable ignored) {}
                if (frame == null) {
                    try {
                        frame = r.getFrameAtTime(0,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    } catch (Throwable ignored) {}
                }
            }
            if (frame == null) {
                // No video frame (audio-only file, or a video whose
                // frames would not decode): try embedded album art.
                try {
                    byte[] art = r.getEmbeddedPicture();
                    if (art != null && art.length > 0) {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(art, 0, art.length, o);
                        if (o.outWidth > 0 && o.outHeight > 0) {
                            o.inJustDecodeBounds = false;
                            o.inSampleSize = LinkUtil.inSampleSize(
                                    o.outWidth, o.outHeight, thumbPx);
                            frame = BitmapFactory.decodeByteArray(art, 0, art.length, o);
                        }
                    }
                } catch (Throwable ignored) {}
            }
            if (frame == null) return null;
            Bitmap sq = squareBitmap(frame, thumbPx);
            if (sq == null) return null;
            return roundCorners(sq, dp(10));
        } catch (Throwable ignored) { // includes OutOfMemoryError
            return null;
        } finally {
            try { r.release(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Round the corners of a bitmap (radius in px). The input bitmap
     * is recycled when a new one is produced. Returns the input
     * unchanged when rounding is not possible. Never throws.
     */
    private static Bitmap roundCorners(Bitmap src, int radiusPx) {
        try {
            if (src == null || src.isRecycled()) return null;
            int w = src.getWidth(), h = src.getHeight();
            if (w <= 0 || h <= 0 || radiusPx <= 0) return src;
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            if (out == null) return src;
            Canvas c = new Canvas(out);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            Path path = new Path();
            path.addRoundRect(new RectF(0, 0, w, h), radiusPx, radiusPx,
                    Path.Direction.CW);
            c.clipPath(path);
            c.drawBitmap(src, 0, 0, p);
            try { src.recycle(); } catch (Throwable ignored) {}
            return out;
        } catch (Throwable ignored) { // includes OutOfMemoryError
            return src;
        }
    }

    /**
     * Placeholder tile shown while a thumbnail loads, and kept when
     * no frame can be extracted: a rounded dark tile (a shade lighter
     * than the card) with a green glyph in the centre — a play
     * triangle for videos, a music note for audio-only files. Built
     * once per kind and reused for every card. Never throws.
     */
    private Bitmap placeholderBitmap(boolean audio) {
        try {
            if (audio && thumbPlaceholderAudio != null) return thumbPlaceholderAudio;
            if (!audio && thumbPlaceholderVideo != null) return thumbPlaceholderVideo;
            int px = dp(LinkUtil.THUMB_SIZE_DP);
            Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            if (b == null) return null;
            Canvas c = new Canvas(b);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFF1E2A25);
            c.drawRoundRect(new RectF(0, 0, px, px), dp(10), dp(10), p);
            p.setColor(GREEN);
            float cx = px / 2f, cy = px / 2f;
            float s = px / 56f; // glyph sized for the 56dp tile
            if (audio) {
                // Eighth note: filled head + stem + flag.
                c.drawCircle(cx - 4 * s, cy + 8 * s, 6 * s, p);
                c.drawRect(cx + 1 * s, cy - 12 * s, cx + 4 * s, cy + 8 * s, p);
                Path flag = new Path();
                flag.moveTo(cx + 4 * s, cy - 12 * s);
                flag.quadTo(cx + 14 * s, cy - 8 * s, cx + 11 * s, cy + 1 * s);
                flag.quadTo(cx + 8 * s, cy - 4 * s, cx + 4 * s, cy - 5 * s);
                flag.close();
                c.drawPath(flag, p);
            } else {
                Path tri = new Path();
                tri.moveTo(cx - 6 * s, cy - 9 * s);
                tri.lineTo(cx + 10 * s, cy);
                tri.lineTo(cx - 6 * s, cy + 9 * s);
                tri.close();
                c.drawPath(tri, p);
            }
            if (audio) thumbPlaceholderAudio = b; else thumbPlaceholderVideo = b;
            return b;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void showCardMenu(final long dmId, final String kind) {
        try {
            new AlertDialog.Builder(this)
                    .setItems(new String[]{"Share", "Delete"}, (d, which) -> {
                        try {
                            if (which == 0) shareDownload(dmId, kind);
                            else deleteDownload(dmId);
                        } catch (Throwable ignored) {}
                    })
                    .show();
        } catch (Throwable ignored) {}
    }

    // ---- uri-based records (merged YouTube files saved via MediaStore) ----

    private void showUriCardMenu(final JSONObject rec) {
        try {
            new AlertDialog.Builder(this)
                    .setItems(new String[]{"Share", "Delete"}, (d, which) -> {
                        try {
                            String uriStr = rec == null ? null : rec.optString("uri", null);
                            String kind = rec == null ? "video" : rec.optString("kind", "video");
                            if (uriStr == null || uriStr.isEmpty()) return;
                            Uri uri = Uri.parse(uriStr);
                            if (which == 0) shareUri(uri, kind);
                            else deleteUriRecord(rec);
                        } catch (Throwable ignored) {}
                    })
                    .show();
        } catch (Throwable ignored) {}
    }

    private void playUri(Uri uri, String kind) {
        if (uri == null) {
            toast("File gone");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "audio".equals(kind) ? "audio/mpeg" : "video/mp4");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Throwable e) {
            toast("No video player");
        }
    }

    private void shareUri(Uri uri, String kind) {
        if (uri == null) {
            toast("File gone");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("audio".equals(kind) ? "audio/mpeg" : "video/mp4");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share"));
        } catch (Throwable e) {
            toast("Can't share");
        }
    }

    private void deleteUriRecord(JSONObject rec) {
        try {
            String uriStr = rec == null ? null : rec.optString("uri", null);
            if (uriStr != null && !uriStr.isEmpty()) {
                Uri uri = Uri.parse(uriStr);
                if ("content".equals(uri.getScheme())) {
                    try {
                        getContentResolver().delete(uri, null, null);
                    } catch (Throwable ignored) {}
                } else if ("file".equals(uri.getScheme())) {
                    try {
                        if (uri.getPath() != null) new File(uri.getPath()).delete();
                    } catch (Throwable ignored) {}
                }
            }
            List<JSONObject> list = loadRecords();
            List<JSONObject> kept = new ArrayList<>();
            for (JSONObject o : list) {
                if (o == null) continue;
                String u = o.optString("uri", "");
                if (uriStr == null || !uriStr.equals(u)) kept.add(o);
            }
            saveRecords(kept);
            renderDownloads();
            toast("Deleted");
        } catch (Throwable ignored) {}
    }

    private Uri downloadUri(long dmId) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) return null;
            return dm.getUriForDownloadedFile(dmId);
        } catch (Throwable e) {
            return null;
        }
    }

    private void playDownload(long dmId, String kind) {
        Uri uri = downloadUri(dmId);
        if (uri == null) {
            toast("File gone");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "audio".equals(kind) ? "audio/mpeg" : "video/mp4");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Throwable e) {
            toast("No video player");
        }
    }

    private void shareDownload(long dmId, String kind) {
        Uri uri = downloadUri(dmId);
        if (uri == null) {
            toast("File gone");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("audio".equals(kind) ? "audio/mpeg" : "video/mp4");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share"));
        } catch (Throwable e) {
            toast("Can't share");
        }
    }

    private void deleteDownload(long dmId) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm != null) dm.remove(dmId);
        } catch (Throwable ignored) {}
        try {
            List<JSONObject> list = loadRecords();
            List<JSONObject> kept = new ArrayList<>();
            for (JSONObject o : list) {
                if (o != null && o.optLong("dmId", -1) != dmId) kept.add(o);
            }
            saveRecords(kept);
            renderDownloads();
            toast("Deleted");
        } catch (Throwable ignored) {}
    }

    /**
     * v1.13: the trash icon no longer deletes everything at once.
     * First tap enters tick-selection mode (every card shows a tick
     * box); tapping a card ticks/unticks it. Second tap deletes ONLY
     * the ticked videos and leaves the mode. Tapping trash with
     * nothing ticked just leaves the mode. The per-item long-press
     * Share/Delete menu still works as before, in both modes.
     */
    private void onTrashTap() {
        try {
            if (!deleteSelecting) {
                if (loadRecords().isEmpty()) return;
                deleteSelecting = true;
                deleteSelected.clear();
                renderDownloads();
                toast("Select videos to delete");
                return;
            }
            if (deleteSelected.isEmpty()) {
                deleteSelecting = false;
                renderDownloads();
                return;
            }
            deleteSelectedRecords();
        } catch (Throwable ignored) {}
    }

    /** Delete the ticked videos — each file plus its in-app record. */
    private void deleteSelectedRecords() {
        try {
            List<JSONObject> list = loadRecords();
            for (JSONObject o : list) {
                if (o == null) continue;
                String key = LinkUtil.deleteKey(
                        o.optString("uri", ""), o.optLong("dmId", -1));
                if (deleteSelected.contains(key)) deleteFileForRecord(o);
            }
            saveRecords(LinkUtil.removeSelected(list, deleteSelected));
            deleteSelected.clear();
            deleteSelecting = false;
            renderDownloads();
            toast("Deleted");
        } catch (Throwable ignored) {}
    }

    /**
     * Delete one record's file — DownloadManager entry, MediaStore
     * content URI, or plain file — so the video leaves the gallery
     * as well as the app. Removing the record itself is the
     * caller's job. Never throws.
     */
    private void deleteFileForRecord(JSONObject o) {
        try {
            if (o == null) return;
            long dmId = o.optLong("dmId", -1);
            if (dmId >= 0) {
                try {
                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (dm != null) dm.remove(dmId);
                } catch (Throwable ignored) {}
            }
            String uriStr = o.optString("uri", "");
            if (uriStr != null && !uriStr.isEmpty()) {
                try {
                    Uri uri = Uri.parse(uriStr);
                    if ("content".equals(uri.getScheme())) {
                        getContentResolver().delete(uri, null, null);
                    } else if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                        new File(uri.getPath()).delete();
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }
}
