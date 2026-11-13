package com.user.linksave;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The VIDEOS list, stored as one JSON array ("list") in the
 * "linksave_downloads" preferences — the same store MainActivity has
 * used since v1.0. Moved into static helpers in v1.11 (kept in
 * v1.12 — the v1.11 bubble flow that shared it was removed, but
 * the refactor is only code organisation). All methods are
 * null-safe and never throw; concurrent writers are serialised
 * on a lock so two completions landing at the same moment can
 * never lose each other's record.
 */
public final class Records {

    private Records() {}

    /** The preferences file MainActivity has always used. */
    public static final String PREFS = "linksave_downloads";
    /** The key holding the JSON array of download records. */
    public static final String LIST_KEY = "list";

    private static final Object LOCK = new Object();

    public static List<JSONObject> load(Context ctx) {
        List<JSONObject> out = new ArrayList<>();
        if (ctx == null) return out;
        try {
            synchronized (LOCK) {
                SharedPreferences p = ctx.getSharedPreferences(
                        PREFS, Context.MODE_PRIVATE);
                if (p == null) return out;
                String json = p.getString(LIST_KEY, "[]");
                if (json == null) return out;
                JSONArray a = new JSONArray(json);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) out.add(o);
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static void save(Context ctx, List<JSONObject> list) {
        if (ctx == null) return;
        try {
            synchronized (LOCK) {
                JSONArray a = new JSONArray();
                if (list != null) {
                    for (JSONObject o : list) if (o != null) a.put(o);
                }
                SharedPreferences p = ctx.getSharedPreferences(
                        PREFS, Context.MODE_PRIVATE);
                if (p != null) p.edit().putString(LIST_KEY, a.toString()).apply();
            }
        } catch (Throwable ignored) {}
    }

    /** Prepend one record (newest first, as the list has always been). */
    public static void add(Context ctx, JSONObject rec) {
        if (ctx == null || rec == null) return;
        try {
            synchronized (LOCK) {
                List<JSONObject> list = load(ctx);
                list.add(0, rec);
                save(ctx, list);
            }
        } catch (Throwable ignored) {}
    }
}
