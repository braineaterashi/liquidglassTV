package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ArtworkHelper {
    private static final String TAG = "ArtworkHelper";
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final LruCache<String, YouTubeTrackInfo> trackCache = new LruCache<>(30);

    private static final Pattern CLEAN_PATTERN = Pattern.compile("(?i)\\s*[\\[\\(](official|music|video|lyrics|hd|4k|audio|feat|ft\\.?|remix|extended).*?[\\]\\)]", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("\"videoId\":\"([a-zA-Z0-9_-]{11})\"");
    private static final Pattern URI_VIDEO_ID_PATTERN = Pattern.compile("(?:vi(?:_webp)?|s_p|embed|shorts)/([a-zA-Z0-9_-]{11})|(?:[?&]v=)([a-zA-Z0-9_-]{11})|(?:youtu\\.be/)([a-zA-Z0-9_-]{11})");

    public static class YouTubeTrackInfo {
        public final String videoId;
        public final Bitmap bitmap;

        public YouTubeTrackInfo(String videoId, Bitmap bitmap) {
            this.videoId = videoId;
            this.bitmap = bitmap;
        }
    }

    public interface YouTubeTrackCallback {
        void onTrackResolved(YouTubeTrackInfo info);
    }

    public interface Callback {
        void onArtworkLoaded(Bitmap bitmap);
    }

    private ArtworkHelper() {}

    public static String extractVideoId(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher m = URI_VIDEO_ID_PATTERN.matcher(text);
        if (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                if (m.group(i) != null) return m.group(i);
            }
        }
        return null;
    }

    public static void loadArtwork(String title, String artist, String artworkParam, Callback callback) {
        if (callback == null) return;
        resolveYouTubeTrack(title, artist, artworkParam, null, info -> {
            if (info != null && info.bitmap != null) {
                callback.onArtworkLoaded(info.bitmap);
            }
        });
    }

    public static void resolveYouTubeTrack(String title, String artist, String directArtUri,
                                           String knownVideoId, YouTubeTrackCallback callback) {
        if (callback == null) return;

        final String cleanT = cleanTitle(title);
        final String cleanA = (artist != null && !artist.equalsIgnoreCase("YouTube") && !artist.equalsIgnoreCase("TV")) ? artist.trim() : "";
        final String cacheKey = cleanT.toLowerCase() + "|" + cleanA.toLowerCase() + "|" +
                (knownVideoId != null ? knownVideoId : "") + "|" +
                (directArtUri != null ? directArtUri.hashCode() : 0);

        synchronized (trackCache) {
            YouTubeTrackInfo cached = trackCache.get(cacheKey);
            if (cached != null) {
                callback.onTrackResolved(cached);
                return;
            }
        }

        executor.execute(() -> {
            try {
                String resolvedVideoId = knownVideoId;
                Bitmap bmp = null;

                // 1. Direct Base64 data if supplied
                if (directArtUri != null && directArtUri.length() > 50 && !directArtUri.startsWith("http")) {
                    try {
                        String b64 = directArtUri;
                        int comma = b64.indexOf(",");
                        if (comma > 0) b64 = b64.substring(comma + 1);
                        byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                        bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    } catch (Exception ignored) {}
                }

                // 2. Direct Artwork URI
                if (directArtUri != null && (directArtUri.startsWith("http://") || directArtUri.startsWith("https://"))) {
                    if (resolvedVideoId == null) {
                        resolvedVideoId = extractVideoId(directArtUri);
                    }
                    if (bmp == null) {
                        bmp = downloadBitmap(directArtUri);
                    }
                }

                // 3. Known Video ID thumbnail
                if (resolvedVideoId != null && bmp == null) {
                    bmp = downloadBitmap("https://i.ytimg.com/vi/" + resolvedVideoId + "/maxresdefault.jpg");
                    if (bmp == null) {
                        bmp = downloadBitmap("https://i.ytimg.com/vi/" + resolvedVideoId + "/hqdefault.jpg");
                    }
                }

                // 4. If Video ID is still missing, search YouTube
                if (resolvedVideoId == null && !cleanT.isEmpty() && !cleanT.equalsIgnoreCase("Playing Media")) {
                    String query = cleanT;
                    if (!cleanA.isEmpty()) {
                        query += " " + cleanA;
                    }
                    String searchUrl = "https://www.youtube.com/results?search_query="
                            + URLEncoder.encode(query, StandardCharsets.UTF_8.name());

                    String html = fetchString(searchUrl);
                    if (html != null) {
                        Matcher m = VIDEO_ID_PATTERN.matcher(html);
                        if (m.find()) {
                            resolvedVideoId = m.group(1);
                            if (bmp == null) {
                                bmp = downloadBitmap("https://i.ytimg.com/vi/" + resolvedVideoId + "/hqdefault.jpg");
                            }
                        }
                    }
                }

                if (resolvedVideoId != null || bmp != null) {
                    YouTubeTrackInfo info = new YouTubeTrackInfo(resolvedVideoId, bmp);
                    synchronized (trackCache) {
                        trackCache.put(cacheKey, info);
                    }
                    callback.onTrackResolved(info);
                }
            } catch (Exception e) {
                Log.w(TAG, "Error resolving YouTube track for " + title, e);
            }
        });
    }

    private static String cleanTitle(String title) {
        if (title == null) return "";
        String clean = CLEAN_PATTERN.matcher(title).replaceAll("").trim();
        return clean.isEmpty() ? title.trim() : clean;
    }

    private static String fetchString(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            conn.setConnectTimeout(4500);
            conn.setReadTimeout(4500);
            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    int count = 0;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                        count++;
                        // The videoId appears early in YouTube's initial data; stop reading once found
                        if (line.contains("\"videoId\":") || count > 250) {
                            break;
                        }
                    }
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
            conn.setRequestProperty("User-Agent", "CleanYourTV/1.0 (YouTube Music TV)");
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
