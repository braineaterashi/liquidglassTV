package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.media.session.MediaController;

public class MediaTrack {
    public final String title;
    public final String artist;
    public final String sourceName; // e.g., "YouTube", "Apple Music", "iPhone", "TV"
    public final boolean isPlaying;
    public final boolean isTvSource;
    public final Bitmap albumArt;
    public final MediaController mediaController;
    public final long timestamp;

    public MediaTrack(String title, String artist, String sourceName, boolean isPlaying,
                      boolean isTvSource, Bitmap albumArt, MediaController mediaController) {
        this.title = title != null && !title.isEmpty() ? title : "Unknown Title";
        this.artist = artist != null && !artist.isEmpty() ? artist : "Unknown Artist";
        this.sourceName = sourceName != null && !sourceName.isEmpty() ? sourceName : (isTvSource ? "TV" : "Device");
        this.isPlaying = isPlaying;
        this.isTvSource = isTvSource;
        this.albumArt = albumArt;
        this.mediaController = mediaController;
        this.timestamp = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return "MediaTrack{" +
                "title='" + title + '\'' +
                ", artist='" + artist + '\'' +
                ", sourceName='" + sourceName + '\'' +
                ", isPlaying=" + isPlaying +
                ", isTvSource=" + isTvSource +
                '}';
    }
}
