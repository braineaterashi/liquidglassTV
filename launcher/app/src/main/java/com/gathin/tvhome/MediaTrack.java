package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.media.session.MediaController;

public class MediaTrack {
    public final String title;
    public final String artist;
    public final String sourceName; // e.g., "YouTube", "YouTube Music", "TV", "iPhone"
    public final boolean isPlaying;
    public final boolean isTvSource;
    public final Bitmap albumArt;
    public final MediaController mediaController;
    public final long timestamp;
    public final String videoId;
    public final long playbackPositionMs;

    public MediaTrack(String title, String artist, String sourceName, boolean isPlaying,
                      boolean isTvSource, Bitmap albumArt, MediaController mediaController) {
        this(title, artist, sourceName, isPlaying, isTvSource, albumArt, mediaController, null, 0L);
    }

    public MediaTrack(String title, String artist, String sourceName, boolean isPlaying,
                      boolean isTvSource, Bitmap albumArt, MediaController mediaController,
                      String videoId, long playbackPositionMs) {
        this.title = title != null && !title.isEmpty() ? title : "Unknown Title";
        this.artist = artist != null && !artist.isEmpty() ? artist : "Unknown Artist";
        this.sourceName = sourceName != null && !sourceName.isEmpty() ? sourceName : (isTvSource ? "TV" : "Device");
        this.isPlaying = isPlaying;
        this.isTvSource = isTvSource;
        this.albumArt = albumArt;
        this.mediaController = mediaController;
        this.videoId = videoId;
        this.playbackPositionMs = Math.max(0L, playbackPositionMs);
        this.timestamp = System.currentTimeMillis();
    }

    public long getCurrentPositionMs() {
        if (!isPlaying) return playbackPositionMs;
        long elapsed = System.currentTimeMillis() - timestamp;
        return playbackPositionMs + elapsed;
    }

    public long getCurrentPositionSeconds() {
        return Math.max(0L, getCurrentPositionMs() / 1000L);
    }

    @Override
    public String toString() {
        return "MediaTrack{" +
                "title='" + title + '\'' +
                ", artist='" + artist + '\'' +
                ", sourceName='" + sourceName + '\'' +
                ", isPlaying=" + isPlaying +
                ", isTvSource=" + isTvSource +
                ", videoId='" + videoId + '\'' +
                ", position=" + playbackPositionMs + "ms" +
                '}';
    }
}
