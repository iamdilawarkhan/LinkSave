package com.user.linksave;

import java.util.ArrayList;
import java.util.List;

/**
 * A resolved video: its metadata plus every downloadable format.
 * Moved out of MainActivity in v1.11 (kept in v1.12) as a
 * top-level model.
 */
public class Resolved {
    String platform, title, author, thumbnailUrl, sourceUrl;
    Integer durationSec;
    List<Format> formats = new ArrayList<>();
    boolean busy; // found, but no download format right now (service down)
}
