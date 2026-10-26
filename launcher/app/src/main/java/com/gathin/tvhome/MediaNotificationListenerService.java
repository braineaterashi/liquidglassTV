package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.List;

public class MediaNotificationListenerService extends NotificationListenerService {
    private static final String TAG = "MediaListenerService";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaSessionManager mediaSessionManager;
    private MediaController currentController;

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            updateFromCurrentController();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            updateFromCurrentController();
        }

        @Override
        public void onSessionDestroyed() {
            if (currentController != null) {
                currentController.unregisterCallback(controllerCallback);
                currentController = null;
            }
            refreshActiveSessions();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    processActiveSessions(controllers);
                }
            };

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "MediaNotificationListenerService connected");
        mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (mediaSessionManager != null) {
            ComponentName comp = new ComponentName(this, MediaNotificationListenerService.class);
            try {
                mediaSessionManager.addOnActiveSessionsChangedListener(sessionsListener, comp, handler);
                List<MediaController> active = mediaSessionManager.getActiveSessions(comp);
                processActiveSessions(active);
            } catch (SecurityException e) {
                Log.w(TAG, "SecurityException registering session listener", e);
            }
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        if (mediaSessionManager != null) {
            try {
                mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (Exception ignored) {}
        }
        if (currentController != null) {
            currentController.unregisterCallback(controllerCallback);
            currentController = null;
        }
    }

    private void refreshActiveSessions() {
        if (mediaSessionManager == null) return;
        try {
            ComponentName comp = new ComponentName(this, MediaNotificationListenerService.class);
            List<MediaController> active = mediaSessionManager.getActiveSessions(comp);
            processActiveSessions(active);
        } catch (Exception e) {
            Log.w(TAG, "Error refreshing sessions", e);
        }
    }

    private void processActiveSessions(List<MediaController> controllers) {
        if (controllers == null || controllers.isEmpty()) {
            if (currentController != null) {
                currentController.unregisterCallback(controllerCallback);
                currentController = null;
            }
            NowPlayingManager.getInstance().updateTvTrack(null);
            return;
        }

        MediaController bestController = null;
        // Prioritize actively playing controller
        for (MediaController c : controllers) {
            PlaybackState state = c.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                bestController = c;
                break;
            }
        }
        if (bestController == null && !controllers.isEmpty()) {
            bestController = controllers.get(0);
        }

        if (bestController != currentController) {
            if (currentController != null) {
                currentController.unregisterCallback(controllerCallback);
            }
            currentController = bestController;
            if (currentController != null) {
                currentController.registerCallback(controllerCallback, handler);
            }
        }

        updateFromCurrentController();
    }

    private void updateFromCurrentController() {
        if (currentController == null) {
            NowPlayingManager.getInstance().updateTvTrack(null);
            return;
        }

        PlaybackState state = currentController.getPlaybackState();
        MediaMetadata metadata = currentController.getMetadata();

        boolean isPlaying = state != null && state.getState() == PlaybackState.STATE_PLAYING;

        String title = null;
        String artist = null;
        Bitmap art = null;

        if (metadata != null) {
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (title == null) title = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);

            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null) artist = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            if (artist == null) artist = metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR);

            art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        }

        String pkg = currentController.getPackageName();
        String appName = getAppLabel(pkg);

        if (title == null || title.isEmpty()) {
            // If title is missing and not playing, omit
            if (!isPlaying) {
                NowPlayingManager.getInstance().updateTvTrack(null);
                return;
            }
            title = "Playing Media";
        }

        MediaTrack track = new MediaTrack(
                title,
                artist != null ? artist : appName,
                appName,
                isPlaying,
                true,
                art,
                currentController
        );

        NowPlayingManager.getInstance().updateTvTrack(track);
    }

    private String getAppLabel(String packageName) {
        if (packageName == null) return "TV";
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(info);
            return label != null ? label.toString() : packageName;
        } catch (Exception e) {
            if (packageName.contains("youtube")) return "YouTube";
            if (packageName.contains("spotify")) return "Spotify";
            if (packageName.contains("netflix")) return "Netflix";
            return "TV";
        }
    }
}
