package com.user.linksave;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FastDownload — pure-Java parallel file downloader, no Android
 * dependencies, so it can be unit-tested on a plain JVM.
 *
 * A file is split into CHUNKS ranges downloaded concurrently over
 * separate HTTP Range connections (4 by default) with 256 KB buffers.
 * Servers that do not support Range, or files smaller than 1 MB, fall
 * back to a single connection automatically. Every chunk is retried
 * once on failure, the final byte count is verified against
 * Content-Length, and the whole download is retried once (single
 * connection) if the parallel attempt fails. Never throws anything
 * except Exception subclasses with short messages.
 *
 * Cancellation (v1.7): every download takes a CancelToken. Calling
 * token.cancel() disconnects every live connection of that download
 * at once, so blocked reads fail immediately instead of waiting out
 * their timeouts, and every read loop checks the token, so the
 * download stops promptly. A cancelled download throws an Exception
 * whose message contains "Cancelled" and is never retried.
 */
public final class FastDownload {

    private FastDownload() {}

    public interface Progress {
        /** Called from worker threads; done/total are bytes (total -1 if unknown). */
        void onProgress(long done, long total);
    }

    /**
     * Cancellation for one download (v1.7). Every live connection of
     * the download registers itself here; cancel() disconnects them
     * all at once, which unblocks any read stuck in the network stack
     * (HttpURLConnection reads do not respond to thread interrupts,
     * so disconnect is what makes cancellation prompt). A connection
     * registered after cancel() is disconnected immediately, so a
     * cancelled download can never open a new connection and keep
     * going. Thread-safe; register/unregister never throw.
     */
    public static final class CancelToken {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final Set<HttpURLConnection> live =
                Collections.synchronizedSet(new HashSet<HttpURLConnection>());

        public boolean isCancelled() { return cancelled.get(); }

        public void cancel() {
            cancelled.set(true);
            disconnectAll();
        }

        public void register(HttpURLConnection c) {
            if (c == null) return;
            try {
                live.add(c);
                if (cancelled.get()) {
                    try { c.disconnect(); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }

        public void unregister(HttpURLConnection c) {
            if (c == null) return;
            try { live.remove(c); } catch (Throwable ignored) {}
        }

        private void disconnectAll() {
            HttpURLConnection[] copy;
            try {
                synchronized (live) { copy = live.toArray(new HttpURLConnection[0]); }
            } catch (Throwable ignored) {
                return;
            }
            for (HttpURLConnection c : copy) {
                try { if (c != null) c.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    /** True when this failure is a cancellation (never retried, never an error). */
    public static boolean isCancelled(Throwable e) {
        if (e == null) return false;
        if (e instanceof CancellationException) return true;
        String m = String.valueOf(e.getMessage());
        return m != null && m.contains("Cancelled");
    }

    private static void checkCancelled(CancelToken token) throws Exception {
        if (token != null && token.isCancelled()) throw new Exception("Cancelled");
        if (Thread.currentThread().isInterrupted()) throw new Exception("Cancelled");
    }

    public static final int CHUNKS = 4;
    public static final int BUFFER = 256 * 1024;          // 256 KB buffers
    public static final long PARALLEL_MIN = 1024 * 1024;  // < 1 MB: single conn
    public static final int CONNECT_TIMEOUT = 30000;
    public static final int READ_TIMEOUT = 60000;

    /** [start, end] inclusive byte ranges covering [0, total). Never throws. */
    public static long[][] splitRanges(long total, int chunks) {
        if (total <= 0) return new long[0][2];
        if (chunks <= 1) return new long[][]{{0, total - 1}};
        int n = (int) Math.min(chunks, total);
        long[][] out = new long[n][2];
        long base = total / n;
        long rem = total % n;
        long start = 0;
        for (int i = 0; i < n; i++) {
            long len = base + (i < rem ? 1 : 0);
            out[i][0] = start;
            out[i][1] = start + len - 1;
            start += len;
        }
        return out;
    }

    /**
     * Download a URL to a file. maxBytes <= 0 means no cap; otherwise a
     * file larger than maxBytes fails with "Too large" before any bytes
     * are written. Returns bytes written. Progress may be null.
     * CancelToken may be null (uncancellable, as before v1.7).
     */
    public static long download(String urlStr, File out, String userAgent,
                                long maxBytes, Progress progress) throws Exception {
        return download(urlStr, out, userAgent, maxBytes, progress, null);
    }

    public static long download(String urlStr, File out, String userAgent,
                                long maxBytes, Progress progress,
                                CancelToken token) throws Exception {
        if (urlStr == null || urlStr.isEmpty() || out == null) {
            throw new Exception("Download failed");
        }
        checkCancelled(token); // already cancelled before starting: fail fast
        // Attempt 1: parallel if the server allows it. Attempt 2 (only if
        // the first fails): plain single connection — some servers flake
        // on Range requests but serve a normal GET fine.
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                deleteQuietly(out);
                if (attempt == 0) return downloadOnce(urlStr, out, userAgent, maxBytes, true, progress, token);
                return downloadOnce(urlStr, out, userAgent, maxBytes, false, progress, token);
            } catch (Throwable e) {
                last = e instanceof Exception ? (Exception) e : new Exception(e);
                String m = String.valueOf(last.getMessage());
                if (m.contains("Too large")) throw last; // never retry a size guard
                if (isCancelled(last) || (token != null && token.isCancelled())) {
                    throw new Exception("Cancelled"); // never retry a cancellation
                }
                deleteQuietly(out);
            }
        }
        throw last == null ? new Exception("Download failed") : last;
    }

    private static void deleteQuietly(File f) {
        try { if (f != null) f.delete(); } catch (Throwable ignored) {}
    }

    /** One attempt: probe the server, then parallel or single download. */
    private static long downloadOnce(String urlStr, File out, String userAgent,
                                     long maxBytes, boolean allowParallel,
                                     Progress progress, CancelToken token) throws Exception {
        checkCancelled(token);
        Probe probe = probe(urlStr, userAgent, token);
        checkCancelled(token);
        long total = probe.total;
        if (maxBytes > 0 && total > 0 && total > maxBytes) {
            throw new Exception("Too large");
        }
        if (allowParallel && probe.ranges && total >= PARALLEL_MIN) {
            return downloadParallel(urlStr, out, userAgent, total, maxBytes, progress, token);
        }
        return downloadSingle(urlStr, out, userAgent, total, maxBytes, progress, token);
    }

    /** Result of probing a URL: total size (-1 unknown) + Range support. */
    private static final class Probe {
        long total = -1;
        boolean ranges = false;
    }

    /** HEAD first (cheap); if that gives nothing, a 1-byte Range GET. */
    private static Probe probe(String urlStr, String userAgent, CancelToken token) {
        Probe p = new Probe();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            if (token != null) token.register(c);
            c.setRequestMethod("HEAD");
            c.setRequestProperty("User-Agent", userAgent);
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(CONNECT_TIMEOUT);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                p.total = c.getContentLengthLong();
                String ar = c.getHeaderField("Accept-Ranges");
                p.ranges = ar != null && ar.toLowerCase().contains("bytes");
                if (p.total > 0) return p;
            }
        } catch (Throwable ignored) {
        } finally {
            if (token != null) token.unregister(c);
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) {} }
        }
        // HEAD gave nothing usable — ask for byte 0 only; a 206 answer
        // carries the total in Content-Range and proves Range works.
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            if (token != null) token.register(c);
            c.setRequestProperty("User-Agent", userAgent);
            c.setRequestProperty("Range", "bytes=0-0");
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(CONNECT_TIMEOUT);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code == 206) {
                p.ranges = true;
                String cr = c.getHeaderField("Content-Range"); // "bytes 0-0/12345"
                if (cr != null) {
                    int slash = cr.lastIndexOf('/');
                    if (slash >= 0) {
                        try { p.total = Long.parseLong(cr.substring(slash + 1).trim()); }
                        catch (NumberFormatException ignored) {}
                    }
                }
            } else if (code >= 200 && code < 300) {
                p.total = c.getContentLengthLong();
            }
            try {
                InputStream in = c.getInputStream();
                if (in != null) { try { in.close(); } catch (Throwable ignored) {} }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
        } finally {
            if (token != null) token.unregister(c);
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) {} }
        }
        return p;
    }

    /** Single-connection download with a 256 KB buffer. */
    private static long downloadSingle(String urlStr, File out, String userAgent,
                                       long total, long maxBytes,
                                       Progress progress, CancelToken token) throws Exception {
        HttpURLConnection c = null;
        long done = 0;
        long lastUi = 0;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            if (token != null) token.register(c);
            c.setRequestProperty("User-Agent", userAgent);
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            checkCancelled(token);
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            if (total <= 0) total = c.getContentLengthLong();
            if (maxBytes > 0 && total > 0 && total > maxBytes) {
                throw new Exception("Too large");
            }
            InputStream in = c.getInputStream();
            FileOutputStream fos = new FileOutputStream(out, false);
            try {
                byte[] buf = new byte[BUFFER];
                int n;
                while ((n = in.read(buf)) != -1) {
                    checkCancelled(token); // stop promptly when cancelled
                    fos.write(buf, 0, n);
                    done += n;
                    if (maxBytes > 0 && done > maxBytes) throw new Exception("Too large");
                    long now = System.currentTimeMillis();
                    if (progress != null && now - lastUi > 250) {
                        lastUi = now;
                        progress.onProgress(done, total);
                    }
                }
            } finally {
                try { fos.close(); } catch (Throwable ignored) {}
                try { in.close(); } catch (Throwable ignored) {}
            }
        } finally {
            if (token != null) token.unregister(c);
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) {} }
        }
        checkCancelled(token);
        if (total > 0 && done < total) {
            throw new Exception("Short download: " + done + " of " + total);
        }
        if (progress != null) progress.onProgress(done, total);
        return done;
    }

    /** 4-way parallel download with HTTP Range requests. */
    private static long downloadParallel(String urlStr, File out, String userAgent,
                                         long total, long maxBytes,
                                         Progress progress, CancelToken token) throws Exception {
        checkCancelled(token);
        long[][] ranges = splitRanges(total, CHUNKS);
        // Pre-size the file so every chunk can write at its own offset.
        RandomAccessFile raf = new RandomAccessFile(out, "rw");
        try { raf.setLength(total); } finally { try { raf.close(); } catch (Throwable ignored) {} }

        final AtomicLong done = new AtomicLong(0);
        final long[] lastUi = new long[]{0};
        Progress agg = (d, t) -> {
            if (progress == null) return;
            long now = System.currentTimeMillis();
            synchronized (lastUi) {
                if (now - lastUi[0] > 250) {
                    lastUi[0] = now;
                    progress.onProgress(done.get(), total);
                }
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(ranges.length);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (long[] range : ranges) {
                final long start = range[0], end = range[1];
                futures.add(pool.submit((Callable<Void>) () -> {
                    downloadChunk(urlStr, out, userAgent, start, end, done, agg, token);
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                try { f.get(); }
                catch (java.util.concurrent.ExecutionException ee) {
                    Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                    // One chunk failed (or was cancelled): stop the
                    // others at once instead of waiting them out.
                    for (Future<?> other : futures) {
                        try { other.cancel(true); } catch (Throwable ignored) {}
                    }
                    if (isCancelled(cause) || (token != null && token.isCancelled())) {
                        throw new Exception("Cancelled");
                    }
                    throw cause instanceof Exception ? (Exception) cause : new Exception(cause);
                }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    for (Future<?> other : futures) {
                        try { other.cancel(true); } catch (Throwable ignored) {}
                    }
                    throw new Exception("Cancelled");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        checkCancelled(token);
        if (out.length() != total) {
            throw new Exception("Short download: " + out.length() + " of " + total);
        }
        if (progress != null) progress.onProgress(total, total);
        return total;
    }

    /** One chunk: GET Range start-end, write at that offset, retry once. */
    private static void downloadChunk(String urlStr, File out, String userAgent,
                                      long start, long end, AtomicLong done,
                                      Progress agg, CancelToken token) throws Exception {
        long want = end - start + 1;
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            checkCancelled(token);
            HttpURLConnection c = null;
            long writtenThisAttempt = 0;
            try {
                c = (HttpURLConnection) new URL(urlStr).openConnection();
                if (token != null) token.register(c);
                c.setRequestProperty("User-Agent", userAgent);
                c.setRequestProperty("Range", "bytes=" + start + "-" + end);
                c.setConnectTimeout(CONNECT_TIMEOUT);
                c.setReadTimeout(READ_TIMEOUT);
                c.setInstanceFollowRedirects(true);
                int code = c.getResponseCode();
                checkCancelled(token);
                if (code == 200) {
                    // Server ignored Range and sent the whole file — the
                    // parallel attempt is invalid; fail so the caller
                    // retries single-connection.
                    throw new Exception("Range ignored");
                }
                if (code != 206) throw new Exception("HTTP " + code);
                InputStream in = c.getInputStream();
                RandomAccessFile raf = new RandomAccessFile(out, "rw");
                try {
                    raf.seek(start);
                    byte[] buf = new byte[BUFFER];
                    long written = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        checkCancelled(token); // stop promptly when cancelled
                        raf.write(buf, 0, n);
                        written += n;
                        writtenThisAttempt += n;
                        done.addAndGet(n);
                        if (agg != null) agg.onProgress(done.get(), -1);
                        if (written > want) throw new Exception("Chunk overflow");
                    }
                    if (written < want) {
                        throw new Exception("Short chunk: " + written + " of " + want);
                    }
                    return; // chunk complete
                } finally {
                    try { raf.close(); } catch (Throwable ignored) {}
                    try { in.close(); } catch (Throwable ignored) {}
                }
            } catch (Throwable e) {
                // A failed attempt's partial bytes are re-downloaded on
                // retry — take them back out of the progress counter.
                if (writtenThisAttempt > 0) done.addAndGet(-writtenThisAttempt);
                last = e instanceof Exception ? (Exception) e : new Exception(e);
                if (isCancelled(last) || (token != null && token.isCancelled())) {
                    throw new Exception("Cancelled"); // never retry a cancellation
                }
                if (last.getMessage() != null && last.getMessage().contains("Range ignored")) {
                    throw last; // retrying the chunk won't help
                }
            } finally {
                if (token != null) token.unregister(c);
                if (c != null) { try { c.disconnect(); } catch (Throwable ignored) {} }
            }
        }
        throw last == null ? new Exception("Download failed") : last;
    }
}
