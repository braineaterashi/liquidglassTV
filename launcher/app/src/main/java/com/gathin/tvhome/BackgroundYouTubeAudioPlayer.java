package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Headless background audio player for YouTube & YouTube Music.
 * Runs silently in the launcher process so that when the user minimizes YouTube
 * to the homescreen, audio keeps playing seamlessly in the background without stopping.
 */
public final class BackgroundYouTubeAudioPlayer {
    private static final String TAG = "BgYouTubePlayer";
    private static volatile BackgroundYouTubeAudioPlayer instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private boolean isPlaying;
    private String currentTrack;

    private BackgroundYouTubeAudioPlayer() {}

    public static BackgroundYouTubeAudioPlayer getInstance() {
        if (instance == null) {
            synchronized (BackgroundYouTubeAudioPlayer.class) {
                if (instance == null) {
                    instance = new BackgroundYouTubeAudioPlayer();
                }
            }
        }
        return instance;
    }

    public void init(Context context) {
        handler.post(() -> {
            if (webView != null) return;
            try {
                webView = new WebView(context.getApplicationContext());
                WebSettings s = webView.getSettings();
                s.setJavaScriptEnabled(true);
                s.setMediaPlaybackRequiresUserGesture(false);
                s.setDomStorageEnabled(true);
                s.setDatabaseEnabled(true);
                s.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Smart TV Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                webView.setWebViewClient(new WebViewClient());
                webView.setWebChromeClient(new WebChromeClient());
                Log.i(TAG, "Background YouTube audio WebView initialized");
            } catch (Exception e) {
                Log.w(TAG, "Failed to init background WebView", e);
            }
        });
    }

    public void play(Context context, String title, String artist) {
        if (title == null || title.trim().isEmpty()) return;
        final String trackKey = title.trim().toLowerCase() + "|" + (artist != null ? artist.trim().toLowerCase() : "");
        if (trackKey.equals(currentTrack) && isPlaying) return;
        currentTrack = trackKey;
        isPlaying = true;

        handler.post(() -> {
            if (webView == null && context != null) {
                init(context);
            }
            if (webView == null) return;
            try {
                String query = title.trim();
                if (artist != null && !artist.trim().isEmpty() && !artist.equalsIgnoreCase("YouTube") && !artist.equalsIgnoreCase("TV")) {
                    query += " " + artist.trim();
                }
                String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
                String embedUrl = "https://www.youtube.com/embed?listType=search&list=" + encoded + "&autoplay=1";
                Log.i(TAG, "Starting background YouTube audio for: " + query);
                webView.loadUrl(embedUrl);
            } catch (Exception e) {
                Log.w(TAG, "Failed to start background audio", e);
            }
        });
    }

    public void pause() {
        isPlaying = false;
        handler.post(() -> {
            if (webView == null) return;
            try {
                webView.evaluateJavascript("var v=document.querySelector('video'); if(v) v.pause();", null);
            } catch (Exception ignored) {}
        });
    }

    public void resume() {
        isPlaying = true;
        handler.post(() -> {
            if (webView == null) return;
            try {
                webView.evaluateJavascript("var v=document.querySelector('video'); if(v) v.play();", null);
            } catch (Exception ignored) {}
        });
    }

    public void stop() {
        isPlaying = false;
        currentTrack = null;
        handler.post(() -> {
            if (webView == null) return;
            try {
                webView.loadUrl("about:blank");
            } catch (Exception ignored) {}
        });
    }

    public boolean isPlaying() {
        return isPlaying;
    }
}
