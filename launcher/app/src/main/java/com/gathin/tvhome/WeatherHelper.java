package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;

public final class WeatherHelper {
    private static final String PREFS_NAME = "cyt_weather";
    private static final String KEY_WEATHER = "last_weather_text";
    private static final String KEY_ICON = "last_weather_icon";
    private static final String KEY_TIME = "last_weather_time";
    // Kolkata, West Bengal coordinates: 22.5726° N, 88.3639° E
    private static final String API_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=22.5726&longitude=88.3639&current=temperature_2m,weather_code,is_day&timezone=Asia/Kolkata";

    private WeatherHelper() {}

    public static void bind(Context context, TextView weatherView, TextView iconView) {
        if (context == null) return;
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String cachedText = sp.getString(KEY_WEATHER, "30°C  PARTLY CLOUDY  •  KOLKATA");
        String cachedIcon = sp.getString(KEY_ICON, "⛅");
        if (weatherView != null) weatherView.setText(cachedText);
        if (iconView != null) iconView.setText(cachedIcon);

        long lastTime = sp.getLong(KEY_TIME, 0);
        long now = System.currentTimeMillis();
        // Refresh every 20 minutes
        if (now - lastTime > 20 * 60 * 1000L) {
            refresh(context.getApplicationContext(), weatherView, iconView);
        }
    }

    public static void refresh(Context appContext, TextView weatherView, TextView iconView) {
        Executors.newSingleThreadExecutor().execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(API_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "CleanYourTV-Launcher/1.0");

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JSONObject json = new JSONObject(sb.toString());
                    JSONObject current = json.getJSONObject("current");
                    double temp = current.getDouble("temperature_2m");
                    int code = current.getInt("weather_code");
                    int isDay = current.optInt("is_day", 1);
                    int tempRound = (int) Math.round(temp);
                    String icon = getWeatherIcon(code, isDay);
                    String desc = getWeatherDescription(code, isDay);
                    String weatherText = tempRound + "°C  " + desc + "  •  KOLKATA";

                    SharedPreferences sp = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    sp.edit()
                            .putString(KEY_WEATHER, weatherText)
                            .putString(KEY_ICON, icon)
                            .putLong(KEY_TIME, System.currentTimeMillis())
                            .apply();

                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (weatherView != null) weatherView.setText(weatherText);
                        if (iconView != null) iconView.setText(icon);
                    });
                }
            } catch (Exception ignored) {
                // Keep cached text on network glitch or offline
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static String getWeatherIcon(int code, int isDay) {
        boolean day = isDay != 0;
        switch (code) {
            case 0: return day ? "☀️" : "🌙";
            case 1: return day ? "🌤️" : "🌙";
            case 2: return day ? "⛅" : "☁️";
            case 3: return "☁️";
            case 45:
            case 48: return "🌫️";
            case 51:
            case 53:
            case 55: return "🌦️";
            case 56:
            case 57: return "🌨️";
            case 61:
            case 63:
            case 65: return "🌧️";
            case 66:
            case 67: return "🌨️";
            case 71:
            case 73:
            case 75:
            case 77: return "❄️";
            case 80:
            case 81:
            case 82: return "🌧️";
            case 85:
            case 86: return "🌨️";
            case 95:
            case 96:
            case 99: return "⛈️";
            default: return day ? "☀️" : "🌙";
        }
    }

    private static String getWeatherDescription(int code, int isDay) {
        boolean day = isDay != 0;
        switch (code) {
            case 0: return day ? "SUNNY" : "CLEAR NIGHT";
            case 1: return "MAINLY CLEAR";
            case 2: return "PARTLY CLOUDY";
            case 3: return "OVERCAST";
            case 45:
            case 48: return "FOG";
            case 51:
            case 53:
            case 55: return "DRIZZLE";
            case 56:
            case 57: return "FREEZING DRIZZLE";
            case 61:
            case 63:
            case 65: return "RAIN";
            case 66:
            case 67: return "FREEZING RAIN";
            case 71:
            case 73:
            case 75:
            case 77: return "SNOW";
            case 80:
            case 81:
            case 82: return "RAIN SHOWERS";
            case 85:
            case 86: return "SNOW SHOWERS";
            case 95:
            case 96:
            case 99: return "THUNDERSTORM";
            default: return day ? "CLEAR" : "CLEAR NIGHT";
        }
    }
}
