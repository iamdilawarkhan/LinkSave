package com.user.linksave;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal FileProvider (v1.18, for the in-app updater). This project
 * builds against android.jar only — no androidx — so the real
 * androidx FileProvider is unavailable; this provider does the one
 * job the updater needs: hand the system installer a content:// URI
 * for the downloaded update APK (file:// URIs are blocked since
 * Android 7). It reads the same res/xml/file_paths.xml format
 * (cache-path / files-path / external-cache-path) and serves only
 * files under those roots — canonical-path containment is checked,
 * so no path traversal can escape a root.
 *
 * Declared in the manifest as .UpdateFileProvider with authority
 * com.user.linksave.fileprovider, exported=false,
 * grantUriPermissions=true. MainActivity grants read permission to
 * the installer with FLAG_GRANT_READ_URI_PERMISSION on the
 * ACTION_VIEW intent.
 */
public class UpdateFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.user.linksave.fileprovider";

    /** One <...-path> root from file_paths.xml. */
    private static final class Root {
        final String name; // the URI segment (the "name" attribute)
        final File dir;    // canonical root directory
        Root(String name, File dir) { this.name = name; this.dir = dir; }
    }

    // Roots depend only on the app (cache/files dirs are stable), so
    // they are parsed once and shared by the provider instance and by
    // the static getUriForFile() helper.
    private static List<Root> cachedRoots;

    private static synchronized List<Root> rootsFor(Context ctx) {
        if (cachedRoots != null) return cachedRoots;
        List<Root> roots = new ArrayList<>();
        try {
            ProviderInfo info = ctx.getPackageManager()
                    .resolveContentProvider(AUTHORITY, 0);
            if (info != null && info.metaData != null) {
                int resId = info.metaData.getInt(
                        "android.support.FILE_PROVIDER_PATHS", 0);
                if (resId != 0) parseRoots(ctx, resId, roots);
            }
        } catch (Throwable ignored) {
            // Fall through to the hardcoded fallback below.
        }
        if (roots.isEmpty()) {
            // Last resort (e.g. the XML is missing): serve the update
            // dir in the app cache, which is the only file the updater
            // ever shares. The provider still works.
            try {
                addRoot(roots, "update",
                        new File(ctx.getCacheDir(), UpdateChecker.UPDATE_DIR));
            } catch (Throwable ignored) {}
        }
        cachedRoots = roots;
        return roots;
    }

    /**
     * Parse the provider's meta-data (@xml/file_paths) the same way
     * androidx FileProvider does: each <cache-path>, <files-path> or
     * <external-cache-path> element maps a URI name to a directory
     * under the matching base dir.
     */
    private static void parseRoots(Context ctx, int resId, List<Root> roots) throws Exception {
        XmlResourceParser parser = ctx.getResources().getXml(resId);
        try {
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event != XmlPullParser.START_TAG) continue;
                String tag = parser.getName();
                String name = parser.getAttributeValue(null, "name");
                String path = parser.getAttributeValue(null, "path");
                if (name == null || name.isEmpty()) continue;
                File base = null;
                if ("cache-path".equals(tag)) base = ctx.getCacheDir();
                else if ("files-path".equals(tag)) base = ctx.getFilesDir();
                else if ("external-cache-path".equals(tag)) base = ctx.getExternalCacheDir();
                else continue;
                if (base == null) continue;
                File dir = (path == null || path.isEmpty())
                        ? base : new File(base, path);
                addRoot(roots, name, dir);
            }
        } finally {
            try { parser.close(); } catch (Throwable ignored) {}
        }
    }

    private static void addRoot(List<Root> roots, String name, File dir) {
        try {
            roots.add(new Root(name, dir.getCanonicalFile()));
        } catch (Throwable ignored) {}
    }

    @Override
    public boolean onCreate() {
        try { rootsFor(getContext()); } catch (Throwable ignored) {}
        return true;
    }

    /** Build the content:// URI for a file under one of the roots. */
    public static Uri getUriForFile(Context context, File file) {
        try {
            File canon = file.getCanonicalFile();
            // Longest matching root wins (same rule as FileProvider).
            Root best = null;
            String bestRel = null;
            for (Root r : rootsFor(context)) {
                String rel = relativePath(r.dir, canon);
                if (rel != null && (bestRel == null || rel.length() < bestRel.length())) {
                    best = r;
                    bestRel = rel;
                }
            }
            if (best == null) throw new IllegalArgumentException("File outside roots");
            return new Uri.Builder()
                    .scheme("content")
                    .authority(AUTHORITY)
                    .appendPath(best.name)
                    .appendEncodedPath(bestRel)
                    .build();
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalArgumentException("Cannot share file", e);
        }
    }

    /** Canonical relative path of file under root, or null if outside. */
    private static String relativePath(File root, File file) {
        try {
            String r = root.getCanonicalPath();
            String f = file.getCanonicalPath();
            if (f.equals(r)) return "";
            if (!f.startsWith(r + File.separator)) return null;
            return f.substring(r.length() + 1);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Resolve a content:// URI back to a file, enforcing containment. */
    private File fileForUri(Uri uri) throws FileNotFoundException {
        try {
            List<String> segs = uri.getPathSegments();
            if (segs == null || segs.isEmpty()) throw new FileNotFoundException("Bad URI");
            Root root = null;
            for (Root r : rootsFor(getContext())) {
                if (r.name.equals(segs.get(0))) { root = r; break; }
            }
            if (root == null) throw new FileNotFoundException("Unknown root");
            File f = root.dir;
            for (int i = 1; i < segs.size(); i++) f = new File(f, segs.get(i));
            File canon = f.getCanonicalFile();
            if (!canon.equals(root.dir) && relativePath(root.dir, canon) == null) {
                throw new FileNotFoundException("Outside root");
            }
            if (!canon.isFile()) throw new FileNotFoundException("Not a file");
            return canon;
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Throwable e) {
            throw new FileNotFoundException("Bad URI");
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        // Read-only: the installer only ever reads the APK.
        File f = fileForUri(uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        try {
            String p = uri == null ? null : uri.getPath();
            if (p != null && p.toLowerCase().endsWith(".apk")) {
                return "application/vnd.android.package-archive";
            }
        } catch (Throwable ignored) {}
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        try {
            File f = fileForUri(uri);
            String[] cols = projection != null && projection.length > 0
                    ? projection
                    : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor c = new MatrixCursor(cols, 1);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
                else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
                else row[i] = null;
            }
            c.addRow(row);
            return c;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only");
    }
}
