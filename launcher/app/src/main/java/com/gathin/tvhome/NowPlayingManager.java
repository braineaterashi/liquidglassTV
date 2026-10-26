package com.gathin.tvhome;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import androidx.annotation.Nullable;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages active media playback state from both the TV (YouTube, Spotify, etc.) and remote devices (iPhone).
 * Strictly prioritizes TV playback when both TV and remote devices are active.
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

    public synchronized @Nullable MediaTrack getRemoteTrack() {
        return remoteTrack;
    }

    public synchronized @Nullable MediaTrack getTvTrack() {
        return tvTrack;
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
        if (lastTvPackage != null && lastTvPackage.contains("youtube")) {
            return wasTvPlaying;
        }
        if (tvTrack != null && tvTrack.sourceName != null && tvTrack.sourceName.toLowerCase().contains("youtube")) {
            return wasTvPlaying || tvTrack.isPlaying;
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

        if (context != null) {
            try {
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    long now = SystemClock.uptimeMillis();
                    am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
                    am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
                }
            } catch (Exception ignored) {}
        }
    }

    public synchronized void pauseTvPlayback() {
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
    }

    /**
     * Priority Rule:
     * 1. If TV is currently playing, TV takes top priority.
     * 2. If TV is idle/paused and remote device is playing, show remote device.
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

        if (active.isTvSource && active.mediaController != null) {
            MediaController.TransportControls controls = active.mediaController.getTransportControls();
            if (active.isPlaying) {
                userExplicitlyPaused = true;
                wasTvPlaying = false;
                controls.pause();
            } else {
                userExplicitlyPaused = false;
                wasTvPlaying = true;
                controls.play();
            }
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
        MediaTrack active = getActiveTrack();
        if (active == null) return;
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
            String pkg = active.mediaController.getPackageName();
            if (pkg != null && context != null) {
                try {
                    Intent intent = context.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                    if (intent == null) {
                        intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
                    }
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to launch package " + pkg, e);
                }
            }
        }
    }
}
