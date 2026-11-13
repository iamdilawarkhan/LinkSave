package com.user.linksave;

/**
 * One downloadable format for a resolved video. Moved out of
 * MainActivity in v1.11 (kept in v1.12) as a top-level model.
 */
public class Format {
    String id, label, kind, note, url;
    String audioUrl; // YouTube only: audio stream to merge with url
    String ext;      // file extension override (e.g. "m4a" for YT audio)
    public Format(String id, String label, String kind, String note, String url) {
        this.id = id; this.label = label; this.kind = kind;
        this.note = note; this.url = url;
    }
    boolean isMerge() { return "ytmerge".equals(kind) && audioUrl != null; }
}
