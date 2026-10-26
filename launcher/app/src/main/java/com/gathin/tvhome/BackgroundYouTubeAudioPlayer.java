package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.Locale;

/**
 * Headless background audio player for YouTube & YouTube Music.
 * Runs silently in the launcher process so that when the user minimizes YouTube
 * to the homescreen, audio keeps playing seamlessly in the background on the TV speakers.
 */
public final class BackgroundYouTubeAudioPlayer {
    private static final String TAG = "BgYouTubePlayer";
    private static volatile BackgroundYouTubeAudioPlayer instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private AudioManager audioManager;
    private boolean isPlaying;
    private String currentTrack;
    private long currentStartSeconds;

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

    public void init(Activity activity, ViewGroup root) {
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            if (audioManager == null) {
                audioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
            }
            if (webView != null) {
                if (webView.getParent() == null && root != null) {
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(1, 1);
                    root.addView(webView, lp);
                }
                return;
            }
            try {
                webView = new WebView(activity);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(1, 1);
                webView.setAlpha(0.01f);
                webView.setVisibility(View.VISIBLE);
                if (root != null) {
                    root.addView(webView, lp);
                }

                WebSettings s = webView.getSettings();
                s.setJavaScriptEnabled(true);
                s.setMediaPlaybackRequiresUserGesture(false);
                s.setDomStorageEnabled(true);
                s.setDatabaseEnabled(true);
                s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                s.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Smart TV Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        Log.i(TAG, "WebView finished loading: " + url);
                        view.evaluateJavascript(
                                "var v=document.querySelector('video'); if(v){ v.muted=false; v.play(); } " +
                                "var b=document.querySelector('.ytp-play-button')||document.querySelector('.player-control-play'); if(b) b.click();",
                                null
                        );
                    }
                });

                webView.setWebChromeClient(new WebChromeClient() {
                    @Override
                    public boolean onConsoleMessage(ConsoleMessage cm) {
                        Log.d(TAG, "WebView Console: " + cm.message());
                        return true;
                    }
                });

                webView.onResume();
                webView.resumeTimers();
                Log.i(TAG, "Background YouTube audio WebView initialized and attached to root");
            } catch (Exception e) {
                Log.w(TAG, "Failed to init background WebView", e);
            }
        });
    }

    public void init(Context context) {
        if (context instanceof Activity) {
            init((Activity) context, null);
        }
    }

    public void play(Context context, String title, String artist) {
        play(context, title, artist, null, 0L);
    }

    public void play(Context context, String title, String artist, String videoId, long startSeconds) {
        if (title == null || title.trim().isEmpty()) return;
        final String trackKey = title.trim().toLowerCase() + "|" + (artist != null ? artist.trim().toLowerCase() : "") + "|" + (videoId != null ? videoId : "");
        if (trackKey.equals(currentTrack) && isPlaying) return;

        currentTrack = trackKey;
        currentStartSeconds = Math.max(0L, startSeconds);
        isPlaying = true;

        handler.post(() -> {
            if (audioManager == null && context != null) {
                audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            }
            if (audioManager != null) {
                try {
                    audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to request audio focus", e);
                }
            }

            if (webView == null && context instanceof Activity) {
                init((Activity) context, null);
            }
            if (webView == null) return;

            if (videoId != null && !videoId.isEmpty()) {
                loadVideoEmbed(videoId, currentStartSeconds);
            } else {
                Log.i(TAG, "Resolving video ID before playback for: " + title);
                ArtworkHelper.resolveYouTubeTrack(title, artist, null, null, info -> {
                    if (info != null && info.videoId != null) {
                        handler.post(() -> {
                            if (isPlaying && trackKey.equals(currentTrack)) {
                                loadVideoEmbed(info.videoId, currentStartSeconds);
                            }
                        });
                    } else {
                        // Fallback search URL
                        handler.post(() -> {
                            if (webView != null && isPlaying) {
                                String q = title.trim() + (artist != null && !artist.isEmpty() ? " " + artist.trim() : "");
                                webView.loadUrl("https://m.youtube.com/results?search_query=" + q);
                            }
                        });
                    }
                });
            }
        });
    }

    private void loadVideoEmbed(String videoId, long startSeconds) {
        if (webView == null) return;
        Log.i(TAG, "Starting background YouTube audio for video ID: " + videoId + " at " + startSeconds + "s");

        String html = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width, initial-scale=1.0'>"
                + "<style>html,body{margin:0;padding:0;background:#000;overflow:hidden;width:100%;height:100%;}</style></head>"
                + "<body><div id='player'></div><script>"
                + "var tag = document.createElement('script');"
                + "tag.src = 'https://www.youtube.com/iframe_api';"
                + "var firstScriptTag = document.getElementsByTagName('script')[0];"
                + "firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);"
                + "var player;"
                + "function onYouTubeIframeAPIReady() {"
                + "  player = new YT.Player('player', {"
                + "    height: '100%',"
                + "    width: '100%',"
                + "    videoId: '" + videoId + "',"
                + "    playerVars: {"
                + "      'autoplay': 1,"
                + "      'playsinline': 1,"
                + "      'controls': 0,"
                + "      'start': " + startSeconds + ","
                + "      'rel': 0,"
                + "      'enablejsapi': 1,"
                + "      'origin': 'https://www.youtube.com'"
                + "    },"
                + "    events: {"
                + "      'onReady': function(e) {"
                + "        e.target.setVolume(100);"
                + "        e.target.playVideo();"
                + "      },"
                + "      'onStateChange': function(e) {"
                + "        if (e.data === 2 || e.data === -1) {"
                + "          e.target.playVideo();"
                + "        }"
                + "      },"
                + "      'onError': function(e) {"
                + "        console.log('Player error ' + e.data + ', switching to mobile stream');"
                + "        window.location.href = 'https://m.youtube.com/watch?v=" + videoId + "&t=" + startSeconds + "s';"
                + "      }"
                + "    }"
                + "  });"
                + "}"
                + "setTimeout(function() {"
                + "  var v = document.querySelector('video');"
                + "  if (v) { v.muted = false; v.play(); }"
                + "}, 1500);"
                + "</script></body></html>";

        webView.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "UTF-8", "https://www.youtube.com");
        webView.onResume();
        webView.resumeTimers();
    }

    public void pause() {
        isPlaying = false;
        handler.post(() -> {
            if (webView == null) return;
            try {
                webView.evaluateJavascript(
                        "if(typeof player !== 'undefined' && player.pauseVideo) player.pauseVideo(); " +
                        "var v=document.querySelector('video'); if(v) v.pause();",
                        null
                );
            } catch (Exception ignored) {}
        });
    }

    public void resume() {
        isPlaying = true;
        handler.post(() -> {
            if (audioManager != null) {
                try {
                    audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                } catch (Exception ignored) {}
            }
            if (webView == null) return;
            try {
                webView.onResume();
                webView.resumeTimers();
                webView.evaluateJavascript(
                        "if(typeof player !== 'undefined' && player.playVideo) player.playVideo(); " +
                        "var v=document.querySelector('video'); if(v) v.play();",
                        null
                );
            } catch (Exception ignored) {}
        });
    }

    public void stop() {
        isPlaying = false;
        currentTrack = null;
        handler.post(() -> {
            if (audioManager != null) {
                try {
                    audioManager.abandonAudioFocus(null);
                } catch (Exception ignored) {}
            }
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
