package com.gathin.tvhome;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
                controls.pause();
            } else {
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
