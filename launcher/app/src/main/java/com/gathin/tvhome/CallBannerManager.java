package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Manages the floating Liquid Glass incoming call banner on the TV screen.
 * Displays over any running app when SYSTEM_ALERT_WINDOW is granted, or falls back to in-app overlay on Homescreen.
 */
public final class CallBannerManager {
    private static final String TAG = "CallBannerManager";
    private static volatile CallBannerManager instance;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View bannerView;
    private TextView titleView;
    private TextView subtitleView;
    private FrameLayout iconBadge;
    private ObjectAnimator pulseAnimator;
    private boolean isShowingOverlay;

    private boolean isHomescreenVisible = true;
    private boolean isCallActive = false;
    private String currentTitle;
    private String currentSubtitle;

    private final Runnable dismissRunnable = this::dismiss;

    public interface OnCallBannerListener {
        void onShowBanner(String title, String subtitle);
        void onDismissBanner();
    }
    private OnCallBannerListener inAppListener;

    private CallBannerManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static CallBannerManager getInstance(Context context) {
        if (instance == null) {
            synchronized (CallBannerManager.class) {
                if (instance == null) {
                    instance = new CallBannerManager(context);
                }
            }
        }
        return instance;
    }

    public void setHomescreenVisible(boolean visible) {
        handler.post(() -> {
            this.isHomescreenVisible = visible;
            Log.i(TAG, "Homescreen visibility set to: " + visible + " (callActive=" + isCallActive + ")");
            if (isCallActive) {
                if (visible) {
                    // Switched to homescreen while ringing: hide floating system overlay and show in-app banner
                    hideSystemOverlay(false);
                    if (inAppListener != null) {
                        inAppListener.onShowBanner(currentTitle, currentSubtitle);
                    }
                } else {
                    // Left homescreen to an app while ringing: hide in-app banner and show floating system overlay
                    if (inAppListener != null) {
                        inAppListener.onDismissBanner();
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(context)) {
                        showSystemOverlay(currentTitle, currentSubtitle);
                    }
                }
            }
        });
    }

    public void setInAppListener(OnCallBannerListener listener) {
        handler.post(() -> {
            this.inAppListener = listener;
            if (listener != null && isCallActive && isHomescreenVisible) {
                listener.onShowBanner(currentTitle, currentSubtitle);
            }
        });
    }

    public void showCall(String callerTitle, String callerSubtitle) {
        handler.post(() -> {
            String title = TextUtils.isEmpty(callerTitle) ? "Incoming Call" : callerTitle;
            String subtitle = TextUtils.isEmpty(callerSubtitle) ? "iPhone • Ringing..." : callerSubtitle;

            isCallActive = true;
            currentTitle = title;
            currentSubtitle = subtitle;

            Log.i(TAG, "Showing call banner: " + title + " - " + subtitle + " (homescreen=" + isHomescreenVisible + ")");

            if (isHomescreenVisible && inAppListener != null) {
                // If homescreen is currently in front, present the native inside-app card
                hideSystemOverlay(false);
                inAppListener.onShowBanner(title, subtitle);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(context)) {
                // If inside another app (Netflix, YouTube, PS5, etc.), display the system floating overlay
                if (inAppListener != null) inAppListener.onDismissBanner();
                showSystemOverlay(title, subtitle);
            } else if (inAppListener != null) {
                hideSystemOverlay(false);
                inAppListener.onShowBanner(title, subtitle);
            } else {
                Log.w(TAG, "Cannot show overlay: SYSTEM_ALERT_WINDOW not granted and no inAppListener attached");
            }

            handler.removeCallbacks(dismissRunnable);
            handler.postDelayed(dismissRunnable, 30_000);
        });
    }

    private void showSystemOverlay(String title, String subtitle) {
        if (windowManager == null) {
            windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        }
        if (windowManager == null) return;

        if (bannerView == null) {
            LayoutInflater inflater = LayoutInflater.from(context);
            bannerView = inflater.inflate(R.layout.view_call_banner, null);
            titleView = bannerView.findViewById(R.id.callTitle);
            subtitleView = bannerView.findViewById(R.id.callSubtitle);
            iconBadge = bannerView.findViewById(R.id.callIconBadge);
        }

        titleView.setText(title);
        subtitleView.setText(subtitle);

        if (!isShowingOverlay) {
            int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.END;
            float density = context.getResources().getDisplayMetrics().density;
            params.x = (int) (36 * density);
            params.y = (int) (32 * density);

            bannerView.setTranslationX(400 * density);
            bannerView.setAlpha(0f);

            try {
                windowManager.addView(bannerView, params);
                isShowingOverlay = true;

                bannerView.animate()
                        .translationX(0)
                        .alpha(1f)
                        .setDuration(350)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();

                if (pulseAnimator != null) pulseAnimator.cancel();
                pulseAnimator = ObjectAnimator.ofFloat(iconBadge, "alpha", 1f, 0.45f, 1f);
                pulseAnimator.setDuration(1100);
                pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
                pulseAnimator.start();
            } catch (Exception e) {
                Log.e(TAG, "Failed to add system overlay view", e);
                isShowingOverlay = false;
                if (inAppListener != null) inAppListener.onShowBanner(title, subtitle);
            }
        }
    }

    private void hideSystemOverlay(boolean animated) {
        if (!isShowingOverlay || bannerView == null || windowManager == null) return;

        if (!animated) {
            try {
                bannerView.animate().cancel();
                windowManager.removeView(bannerView);
            } catch (Exception ignored) {}
            isShowingOverlay = false;
            if (pulseAnimator != null) {
                pulseAnimator.cancel();
                pulseAnimator = null;
            }
            return;
        }

        float density = context.getResources().getDisplayMetrics().density;
        bannerView.animate()
                .translationX(400 * density)
                .alpha(0f)
                .setDuration(280)
                .withEndAction(() -> {
                    try {
                        if (isShowingOverlay && bannerView != null && windowManager != null) {
                            windowManager.removeView(bannerView);
                        }
                    } catch (Exception ignored) {}
                    isShowingOverlay = false;
                    if (pulseAnimator != null) {
                        pulseAnimator.cancel();
                        pulseAnimator = null;
                    }
                })
                .start();
    }

    public void dismiss() {
        handler.post(() -> {
            handler.removeCallbacks(dismissRunnable);
            isCallActive = false;
            currentTitle = null;
            currentSubtitle = null;

            hideSystemOverlay(true);

            if (inAppListener != null) {
                inAppListener.onDismissBanner();
            }
        });
    }
}
