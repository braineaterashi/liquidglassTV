package com.gathin.tvhome;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import androidx.annotation.Nullable;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages active media playback state for YouTube, YouTube Music, and connected sources.
 * Retains YouTube playback on homescreen so background audio and mini player stay active.
 */
public final class NowPlayingManager {
    private static final String TAG = "NowPlayingManager";
    private static volatile NowPlayingManager instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<OnTrackChangeListener> listeners = new CopyOnWriteArrayList<>();

    private MediaTrack tvTrack;
    private MediaTrack remoteTrack;

    private MediaController lastTvController;
    private String lastTvPackage;
    private boolean wasTvPlaying;
    private boolean userExplicitlyPaused;

    public interface OnTrackChangeListener {
        void onTrackUpdated(@Nullable MediaTrack track);
    }

    private NowPlayingManager() {}

    public static NowPlayingManager getInstance() {
        if (instance == null) {
            synchronized (NowPlayingManager.class) {
                if (instance == null) {
                    instance = new NowPlayingManager();
                }
            }
        }
        return instance;
    }

    public void addListener(OnTrackChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
            listener.onTrackUpdated(getActiveTrack());
        }
    }

    public void removeListener(OnTrackChangeListener listener) {
        listeners.remove(listener);
    }

    public synchronized void updateTvTrack(@Nullable MediaTrack track) {
        if (track == null) {
            if (tvTrack != null && isYouTubeTrack(tvTrack) && wasTvPlaying && !userExplicitlyPaused) {
                // Retain YouTube / YouTube Music track active for background homescreen playback
                Log.d(TAG, "Retaining YouTube track for background homescreen display: " + tvTrack.title);
                return;
            }
        }

        this.tvTrack = track;
        if (track != null && track.isTvSource) {
            if (track.mediaController != null) {
                this.lastTvController = track.mediaController;
                this.lastTvPackage = track.mediaController.getPackageName();
            }
            if (track.isPlaying) {
                this.wasTvPlaying = true;
                this.userExplicitlyPaused = false;
            }
        }
        dispatchUpdate();
    }

    public synchronized void updateRemoteTrack(@Nullable MediaTrack track) {
        this.remoteTrack = track;
        dispatchUpdate();
    }

    public synchronized void clearRemoteTrack() {
        this.remoteTrack = null;
        dispatchUpdate();
    }

    public synchronized void clearAllTracks() {
        this.tvTrack = null;
        this.remoteTrack = null;
        this.wasTvPlaying = false;
        this.userExplicitlyPaused = true;
        BackgroundYouTubeAudioPlayer.getInstance().stop();
        dispatchUpdate();
    }

    public synchronized @Nullable MediaTrack getRemoteTrack() {
        return remoteTrack;
    }

    public synchronized @Nullable MediaTrack getTvTrack() {
        return tvTrack;
    }

    private boolean isYouTubeTrack(MediaTrack track) {
        if (track == null) return false;
        String src = track.sourceName != null ? track.sourceName.toLowerCase() : "";
        if (src.contains("youtube") || src.contains("music")) return true;
        if (lastTvPackage != null && lastTvPackage.contains("youtube")) return true;
        return false;
    }

    public synchronized void setLastTvSession(MediaController controller, String packageName, boolean isPlaying) {
        if (controller != null) {
            this.lastTvController = controller;
        }
        if (packageName != null) {
            this.lastTvPackage = packageName;
        }
        if (isPlaying) {
            this.wasTvPlaying = true;
            this.userExplicitlyPaused = false;
        }
    }

    public synchronized boolean isYouTubePlaybackActiveOrPaused() {
        if (userExplicitlyPaused) return false;
        if (tvTrack != null && isYouTubeTrack(tvTrack)) {
            return wasTvPlaying || tvTrack.isPlaying;
        }
        if (lastTvPackage != null && lastTvPackage.contains("youtube")) {
            return wasTvPlaying;
        }
        return false;
    }

    public synchronized void resumeTvPlaybackIfYouTube(Context context) {
        if (userExplicitlyPaused) return;
        if (!isYouTubePlaybackActiveOrPaused()) return;

        Log.i(TAG, "Resuming YouTube background playback on homescreen");
        if (lastTvController != null) {
            try {
                lastTvController.getTransportControls().play();
            } catch (Exception e) {
                Log.w(TAG, "Controller play failed", e);
            }
        }

        if (context != null && tvTrack != null) {
            try {
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    long now = SystemClock.uptimeMillis();
                    am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
                    am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
                }
            } catch (Exception ignored) {}

            long startSec = tvTrack.getCurrentPositionSeconds();
            BackgroundYouTubeAudioPlayer.getInstance().play(context, tvTrack.title, tvTrack.artist, tvTrack.videoId, startSec);
        }
    }

    public synchronized void pauseTvPlayback() {
        BackgroundYouTubeAudioPlayer.getInstance().stop();
        if (lastTvController != null) {
            try {
                lastTvController.getTransportControls().pause();
            } catch (Exception ignored) {}
        }
        if (tvTrack != null && tvTrack.mediaController != null && tvTrack.mediaController != lastTvController) {
            try {
                tvTrack.mediaController.getTransportControls().pause();
            } catch (Exception ignored) {}
        }
        userExplicitlyPaused = true;
        wasTvPlaying = false;
        if (tvTrack != null) {
            tvTrack = new MediaTrack(
                    tvTrack.title, tvTrack.artist, tvTrack.sourceName, false,
                    true, tvTrack.albumArt, tvTrack.mediaController,
                    tvTrack.videoId, tvTrack.getCurrentPositionMs()
            );
            dispatchUpdate();
        }
    }

    /**
     * Priority Rule:
     * 1. If TV is currently playing (YouTube / YouTube Music), TV takes top priority.
     * 2. If TV is idle/paused and remote is playing, show remote device.
     * 3. If TV is paused, show TV.
     * 4. Else show remote.
     */
    @Nullable
    public synchronized MediaTrack getActiveTrack() {
        if (tvTrack != null && tvTrack.isPlaying) {
            return tvTrack;
        }
        if (remoteTrack != null && remoteTrack.isPlaying) {
            return remoteTrack;
        }
        if (tvTrack != null) {
            return tvTrack;
        }
        return remoteTrack;
    }

    private void dispatchUpdate() {
        MediaTrack active = getActiveTrack();
        handler.post(() -> {
            for (OnTrackChangeListener listener : listeners) {
                try {
                    listener.onTrackUpdated(active);
                } catch (Exception e) {
                    Log.e(TAG, "Error notifying track change listener", e);
                }
            }
        });
    }

    public void togglePlayPause() {
        MediaTrack active = getActiveTrack();
        if (active == null) return;

        if (active.isTvSource) {
            boolean willPlay = !active.isPlaying;
            if (active.mediaController != null) {
                try {
                    if (willPlay) active.mediaController.getTransportControls().play();
                    else active.mediaController.getTransportControls().pause();
                } catch (Exception ignored) {}
            }
            if (BackgroundYouTubeAudioPlayer.getInstance().isPlaying() && !willPlay) {
                BackgroundYouTubeAudioPlayer.getInstance().pause();
            } else if (willPlay) {
                BackgroundYouTubeAudioPlayer.getInstance().resume();
            }
            userExplicitlyPaused = !willPlay;
            wasTvPlaying = willPlay;
            this.tvTrack = new MediaTrack(
                    active.title, active.artist, active.sourceName, willPlay,
                    true, active.albumArt, active.mediaController,
                    active.videoId, active.getCurrentPositionMs()
            );
            dispatchUpdate();
        } else {
            // Remote track: toggle local state
            boolean newState = !active.isPlaying;
            updateRemoteTrack(new MediaTrack(
                    active.title, active.artist, active.sourceName, newState,
                    false, active.albumArt, null
            ));
        }
    }

    public void next() {
        MediaTrack active = getActiveTrack();
        if (active != null && active.isTvSource && active.mediaController != null) {
            active.mediaController.getTransportControls().skipToNext();
        }
    }

    public void previous() {
        MediaTrack active = getActiveTrack();
        if (active != null && active.isTvSource && active.mediaController != null) {
            active.mediaController.getTransportControls().skipToPrevious();
        }
    }

    public void openApp(Context context) {
        BackgroundYouTubeAudioPlayer.getInstance().stop();
        MediaTrack active = getActiveTrack();
        if (active == null || context == null) return;

        long targetSec = active.getCurrentPositionSeconds();
        String videoId = active.videoId;
        boolean isYouTube = isYouTubeTrack(active);

        if (isYouTube) {
            try {
                Intent intent = null;
                if (videoId != null && !videoId.isEmpty()) {
                    String url = "https://www.youtube.com/watch?v=" + videoId + "&t=" + targetSec + "s";
                    intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                    intent.setPackage("com.google.android.youtube.tv");
                }
                if (intent == null) {
                    intent = context.getPackageManager().getLeanbackLaunchIntentForPackage("com.google.android.youtube.tv");
                    if (intent == null) {
                        intent = context.getPackageManager().getLaunchIntentForPackage("com.google.android.youtube.tv");
                    }
                }
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to launch YouTube with timestamp", e);
            }
        }

        if (active.isTvSource && active.mediaController != null) {
            PendingIntent pi = active.mediaController.getSessionActivity();
            if (pi != null) {
                try {
                    pi.send();
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "Failed to launch session activity", e);
                }
            }
        }

        // Open YouTube app on TV fallback
        try {
            Intent intent = context.getPackageManager().getLeanbackLaunchIntentForPackage("com.google.android.youtube.tv");
            if (intent == null) {
                intent = context.getPackageManager().getLaunchIntentForPackage("com.google.android.youtube.tv");
            }
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to launch YouTube", e);
        }
    }
}
