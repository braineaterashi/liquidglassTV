package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public final class ArtworkHelper {
    private static final String TAG = "ArtworkHelper";
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final LruCache<String, Bitmap> artCache = new LruCache<>(25);

    private static final Pattern CLEAN_PATTERN = Pattern.compile("(?i)\\s*[\\[\\(](official|music|video|lyrics|hd|4k|audio|feat|ft\\.?|remix|extended).*?[\\]\\)]", Pattern.CASE_INSENSITIVE);

    public interface Callback {
        void onArtworkLoaded(Bitmap bitmap);
    }

    private ArtworkHelper() {}

    public static void loadArtwork(String title, String artist, String artworkParam, Callback callback) {
        if (callback == null) return;

        final String cacheKey = (title != null ? title.trim().toLowerCase() : "") + "|" +
                                (artist != null ? artist.trim().toLowerCase() : "") + "|" +
                                (artworkParam != null ? artworkParam.hashCode() : 0);

        synchronized (artCache) {
            Bitmap cached = artCache.get(cacheKey);
            if (cached != null) {
                callback.onArtworkLoaded(cached);
                return;
            }
        }

        executor.execute(() -> {
            try {
                // 1. Direct artwork URL or Base64 data
                if (artworkParam != null && !artworkParam.isEmpty() && !artworkParam.equalsIgnoreCase("null")) {
                    if (artworkParam.startsWith("http://") || artworkParam.startsWith("https://")) {
                        Bitmap bmp = downloadBitmap(artworkParam);
                        if (bmp != null) {
                            synchronized (artCache) { artCache.put(cacheKey, bmp); }
                            callback.onArtworkLoaded(bmp);
                            return;
                        }
                    } else if (artworkParam.length() > 50) {
                        try {
                            String b64 = artworkParam;
                            int comma = b64.indexOf(",");
                            if (comma > 0) b64 = b64.substring(comma + 1);
                            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                            if (bmp != null) {
                                synchronized (artCache) { artCache.put(cacheKey, bmp); }
                                callback.onArtworkLoaded(bmp);
                                return;
                            }
                        } catch (Exception ignored) {}
                    }
                }

                // 2. Fetch official artwork from Apple's iTunes Search API
                if (title != null && !title.isEmpty() && !title.equalsIgnoreCase("[Title]") && !title.equalsIgnoreCase("Title")) {
                    String cleanTitle = cleanTitle(title);
                    String cleanArtist = (artist != null && !artist.equalsIgnoreCase("[Artist]") && !artist.equalsIgnoreCase("Artist")) ? artist : "";
                    
                    String query = cleanTitle;
                    if (!cleanArtist.isEmpty() && !cleanArtist.equalsIgnoreCase("Remote Device") && !cleanArtist.equalsIgnoreCase("TV")) {
                        query += " " + cleanArtist;
                    }

                    String searchUrl = "https://itunes.apple.com/search?term="
                            + URLEncoder.encode(query, StandardCharsets.UTF_8.name())
                            + "&entity=song&limit=1";

                    String jsonStr = fetchString(searchUrl);
                    if (jsonStr != null) {
                        JSONObject root = new JSONObject(jsonStr);
                        JSONArray results = root.optJSONArray("results");
                        if (results != null && results.length() > 0) {
                            JSONObject first = results.getJSONObject(0);
                            String artUrl = first.optString("artworkUrl100");
                            if (artUrl != null && !artUrl.isEmpty()) {
                                // Request high-resolution 600x600 artwork
                                String highResUrl = artUrl.replace("100x100bb", "600x600bb");
                                Bitmap bmp = downloadBitmap(highResUrl);
                                if (bmp == null) {
                                    bmp = downloadBitmap(artUrl.replace("100x100bb", "300x300bb"));
                                }
                                if (bmp == null) {
                                    bmp = downloadBitmap(artUrl);
                                }
                                if (bmp != null) {
                                    synchronized (artCache) { artCache.put(cacheKey, bmp); }
                                    callback.onArtworkLoaded(bmp);
                                    return;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error loading artwork for " + title, e);
            }
        });
    }

    private static String cleanTitle(String title) {
        if (title == null) return "";
        String clean = CLEAN_PATTERN.matcher(title).replaceAll("").trim();
        return clean.isEmpty() ? title : clean;
    }

    private static String fetchString(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "CleanYourTV/1.0 (Apple Music/WebHook)");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    return sb.toString();
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static Bitmap downloadBitmap(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "CleanYourTV/1.0 (Apple Music/WebHook)");
            conn.setConnectTimeout(4500);
            conn.setReadTimeout(4500);
            if (conn.getResponseCode() == 200) {
                try (InputStream in = conn.getInputStream()) {
                    return BitmapFactory.decodeStream(in);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }
}
