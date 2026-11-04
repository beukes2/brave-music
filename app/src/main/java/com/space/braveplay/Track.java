package com.space.braveplay;

/** One playable song/video. */
public final class Track {

    public final String id;
    public final String url;
    public final String title;
    public final String author;
    /** Length in seconds, 0 = live stream or unknown. */
    public final int durationSec;
    /** Normalised keys used for duplicate matching; computed once per track. */
    public final String titleKey;
    public final String authorKey;

    public Track(String id, String url, String title, String author, int durationSec) {
        this.id = id;
        this.url = url;
        this.title = title == null ? "" : title;
        this.author = author == null ? "" : author;
        this.durationSec = Math.max(0, durationSec);
        this.titleKey = Library.normalizeTitle(this.title);
        this.authorKey = Library.normalizeAuthor(this.author);
    }

    /** True when the only playable source is the saved file (there is no web URL). */
    public boolean isLocalOnly() {
        return url == null || url.isEmpty() || url.startsWith("local:");
    }

    public String displayTitle() {
        return title.isEmpty() ? id : title;
    }

    public String displayAuthor() {
        return author.isEmpty() ? "YouTube" : author;
    }
}
