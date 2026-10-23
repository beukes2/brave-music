package com.space.braveplay;

/** One playable song/video. */
public final class Track {

    public final String id;
    public final String url;
    public final String title;
    public final String author;
    /** Length in seconds, 0 = live stream or unknown. */
    public final int durationSec;

    public Track(String id, String url, String title, String author, int durationSec) {
        this.id = id;
        this.url = url;
        this.title = title == null ? "" : title;
        this.author = author == null ? "" : author;
        this.durationSec = Math.max(0, durationSec);
    }

    public String displayTitle() {
        return title.isEmpty() ? id : title;
    }

    public String displayAuthor() {
        return author.isEmpty() ? "YouTube" : author;
    }
}
