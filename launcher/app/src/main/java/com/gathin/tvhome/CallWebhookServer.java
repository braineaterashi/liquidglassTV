package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight local LAN HTTP webhook server listening on port 8088.
 * Supports iOS Shortcuts, Apple Music, curl, or external automations to trigger
 * the Liquid Glass Mini Player and Call Banner.
 */
public final class CallWebhookServer {
    private static final String TAG = "CallWebhookServer";
    public static final int PORT = 8088;

    private static volatile CallWebhookServer instance;
    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService clientExecutor = Executors.newCachedThreadPool();
    private ServerSocket serverSocket;
    private volatile boolean running;

    private CallWebhookServer(Context context) {
        this.context = context.getApplicationContext();
    }

    public static CallWebhookServer getInstance(Context context) {
        if (instance == null) {
            synchronized (CallWebhookServer.class) {
                if (instance == null) {
                    instance = new CallWebhookServer(context);
                }
            }
        }
        return instance;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        executor.execute(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new java.net.InetSocketAddress("0.0.0.0", PORT));
                Log.i(TAG, "CallWebhookServer listening on 0.0.0.0:" + PORT);
                while (running && !serverSocket.isClosed()) {
                    Socket client = serverSocket.accept();
                    handleClient(client);
                }
            } catch (Exception e) {
                if (running) {
                    Log.e(TAG, "Server error on port " + PORT, e);
                }
            }
        });
    }

    private void handleClient(Socket socket) {
        clientExecutor.execute(() -> {
            try (socket;
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 OutputStream out = socket.getOutputStream()) {

                socket.setSoTimeout(3500);

                String requestLine = reader.readLine();
                if (requestLine == null) return;
                Log.i(TAG, "Incoming HTTP request: " + requestLine);

                String[] parts = requestLine.split(" ");
                if (parts.length < 2) return;

                String method = parts[0];
                String rawPath = parts[1];

                // Read HTTP Headers
                int contentLength = 0;
                String headerLine;
                while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                    String lower = headerLine.toLowerCase();
                    if (lower.startsWith("content-length:")) {
                        try {
                            contentLength = Integer.parseInt(headerLine.substring(15).trim());
                        } catch (Exception ignored) {}
                    }
                }

                // Read Request Body (if POST with body)
                String body = "";
                if (contentLength > 0) {
                    char[] buffer = new char[contentLength];
                    int totalRead = 0;
                    while (totalRead < contentLength) {
                        int read = reader.read(buffer, totalRead, contentLength - totalRead);
                        if (read == -1) break;
                        totalRead += read;
                    }
                    body = new String(buffer, 0, totalRead);
                }

                JSONObject bodyJson = null;
                if (!body.isEmpty() && body.trim().startsWith("{")) {
                    try {
                        bodyJson = new JSONObject(body);
                    } catch (Exception ignored) {}
                }

                Uri uri = Uri.parse("http://localhost" + rawPath);
                String route = uri.getPath();

                String responseBody = "{\"success\":true}";

                if ("/call".equalsIgnoreCase(route) || "/notify".equalsIgnoreCase(route)) {
                    String title = bodyJson != null ? bodyJson.optString("title", null) : null;
                    if (title == null) title = uri.getQueryParameter("title");
                    if (title == null) title = uri.getQueryParameter("name");
                    if (title == null) title = uri.getQueryParameter("caller");

                    String subtitle = bodyJson != null ? bodyJson.optString("subtitle", null) : null;
                    if (subtitle == null) subtitle = uri.getQueryParameter("subtitle");
                    if (subtitle == null) subtitle = uri.getQueryParameter("number");

                    if (title != null) {
                        try { title = URLDecoder.decode(title, StandardCharsets.UTF_8.name()); } catch (Exception ignored) {}
                    }
                    if (subtitle != null) {
                        try { subtitle = URLDecoder.decode(subtitle, StandardCharsets.UTF_8.name()); } catch (Exception ignored) {}
                    }
                    CallBannerManager.getInstance(context).showCall(title, subtitle);
                    responseBody = "{\"success\":true,\"event\":\"call_shown\"}";

                } else if ("/dismiss".equalsIgnoreCase(route)) {
                    CallBannerManager.getInstance(context).dismiss();
                    responseBody = "{\"success\":true,\"event\":\"dismissed\"}";

                } else if ("/media".equalsIgnoreCase(route) || "/nowplaying".equalsIgnoreCase(route)) {
                    // Extract Title
                    String trackTitle = null;
                    if (bodyJson != null) {
                        trackTitle = bodyJson.optString("title", null);
                        if (trackTitle == null) trackTitle = bodyJson.optString("song", null);
                        if (trackTitle == null) trackTitle = bodyJson.optString("track", null);
                        if (trackTitle == null) trackTitle = bodyJson.optString("name", null);
                    }
                    if (trackTitle == null) trackTitle = uri.getQueryParameter("title");
                    if (trackTitle == null) trackTitle = uri.getQueryParameter("song");
                    if (trackTitle == null) trackTitle = uri.getQueryParameter("track");
                    if (trackTitle == null) trackTitle = uri.getQueryParameter("name");

                    // Extract Artist
                    String artist = null;
                    if (bodyJson != null) {
                        artist = bodyJson.optString("artist", null);
                        if (artist == null) artist = bodyJson.optString("author", null);
                        if (artist == null) artist = bodyJson.optString("albumArtist", null);
                    }
                    if (artist == null) artist = uri.getQueryParameter("artist");
                    if (artist == null) artist = uri.getQueryParameter("author");

                    // Extract Source
                    String source = null;
                    if (bodyJson != null) source = bodyJson.optString("source", null);
                    if (source == null) source = uri.getQueryParameter("source");
                    if (source == null || source.isEmpty()) source = "YouTube Music";

                    // Extract Playing
                    boolean isPlaying = true;
                    if (bodyJson != null && bodyJson.has("playing")) {
                        isPlaying = bodyJson.optBoolean("playing", true);
                    } else {
                        String playingParam = uri.getQueryParameter("playing");
                        if (playingParam != null) {
                            isPlaying = !"false".equalsIgnoreCase(playingParam) &&
                                        !"0".equals(playingParam) &&
                                        !"no".equalsIgnoreCase(playingParam);
                        }
                    }

                    // Extract Artwork (URL or Base64)
                    String artwork = null;
                    if (bodyJson != null) {
                        artwork = bodyJson.optString("artwork", null);
                        if (artwork == null) artwork = bodyJson.optString("art", null);
                        if (artwork == null) artwork = bodyJson.optString("image", null);
                    }
                    if (artwork == null) artwork = uri.getQueryParameter("artwork");
                    if (artwork == null) artwork = uri.getQueryParameter("art");

                    if (trackTitle != null) {
                        try { trackTitle = URLDecoder.decode(trackTitle, StandardCharsets.UTF_8.name()); } catch (Exception ignored) {}
                        trackTitle = trackTitle.trim();
                    }
                    if (artist != null) {
                        try { artist = URLDecoder.decode(artist, StandardCharsets.UTF_8.name()); } catch (Exception ignored) {}
                        artist = artist.trim();
                    }

                    if (isPlaceholder(trackTitle)) {
                        responseBody = "{\"error\":\"Placeholder title ignored\",\"hint\":\"Make sure to select the actual Song Title variable in iOS Shortcuts rather than typing text [Title].\"}";
                    } else {
                        if (isPlaceholder(artist)) {
                            artist = source;
                        }

                        final String finalTitle = trackTitle;
                        final String finalArtist = (artist != null && !artist.isEmpty()) ? artist : source;
                        final String finalSource = source;
                        final boolean finalPlaying = isPlaying;
                        final String finalArtwork = artwork;

                        // 1. Instantly update UI with title and artist
                        MediaTrack remoteTrack = new MediaTrack(
                                finalTitle,
                                finalArtist,
                                finalSource,
                                finalPlaying,
                                false,
                                null,
                                null
                        );
                        NowPlayingManager.getInstance().updateRemoteTrack(remoteTrack);

                        // 2. Fetch or decode album artwork asynchronously
                        ArtworkHelper.loadArtwork(finalTitle, finalArtist, finalArtwork, bmp -> {
                            if (bmp != null) {
                                MediaTrack current = NowPlayingManager.getInstance().getRemoteTrack();
                                if (current != null && finalTitle.equalsIgnoreCase(current.title)) {
                                    MediaTrack withArt = new MediaTrack(
                                            current.title,
                                            current.artist,
                                            current.sourceName,
                                            current.isPlaying,
                                            false,
                                            bmp,
                                            null
                                    );
                                    NowPlayingManager.getInstance().updateRemoteTrack(withArt);
                                }
                            }
                        });

                        responseBody = "{\"success\":true,\"event\":\"media_updated\",\"title\":\"" + escapeJson(finalTitle) + "\",\"artist\":\"" + escapeJson(finalArtist) + "\",\"playing\":" + finalPlaying + "}";
                    }

                } else if ("/media/clear".equalsIgnoreCase(route)) {
                    NowPlayingManager.getInstance().clearRemoteTrack();
                    responseBody = "{\"success\":true,\"event\":\"media_cleared\"}";

                } else if ("/media/toggle".equalsIgnoreCase(route)) {
                    NowPlayingManager.getInstance().togglePlayPause();
                    responseBody = "{\"success\":true,\"event\":\"media_toggled\"}";

                } else if ("/media/next".equalsIgnoreCase(route)) {
                    NowPlayingManager.getInstance().next();
                    responseBody = "{\"success\":true,\"event\":\"media_next\"}";

                } else if ("/media/prev".equalsIgnoreCase(route) || "/media/previous".equalsIgnoreCase(route)) {
                    NowPlayingManager.getInstance().previous();
                    responseBody = "{\"success\":true,\"event\":\"media_prev\"}";

                } else if ("/media/status".equalsIgnoreCase(route)) {
                    MediaTrack active = NowPlayingManager.getInstance().getActiveTrack();
                    if (active != null) {
                        responseBody = "{\"active\":true,\"title\":\"" + escapeJson(active.title) + "\",\"artist\":\"" + escapeJson(active.artist) + "\",\"source\":\"" + escapeJson(active.sourceName) + "\",\"isTv\":" + active.isTvSource + ",\"isPlaying\":" + active.isPlaying + ",\"hasArt\":" + (active.albumArt != null) + "}";
                    } else {
                        responseBody = "{\"active\":false}";
                    }

                } else if ("/health".equalsIgnoreCase(route) || "/status".equalsIgnoreCase(route)) {
                    responseBody = "{\"status\":\"ok\",\"service\":\"CleanYourTV-CallBanner\",\"port\":" + PORT + "}";
                }

                byte[] bodyBytes = responseBody.getBytes(StandardCharsets.UTF_8);
                String responseHeader = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: application/json; charset=utf-8\r\n"
                        + "Access-Control-Allow-Origin: *\r\n"
                        + "Content-Length: " + bodyBytes.length + "\r\n"
                        + "Connection: close\r\n\r\n";

                out.write(responseHeader.getBytes(StandardCharsets.UTF_8));
                out.write(bodyBytes);
                out.flush();
            } catch (Exception e) {
                Log.w(TAG, "Error handling client request", e);
            }
        });
    }

    private static boolean isPlaceholder(String s) {
        if (s == null) return true;
        String clean = s.trim();
        if (clean.isEmpty()) return true;
        if (clean.startsWith("[") && clean.endsWith("]")) return true;
        if (clean.equalsIgnoreCase("title") || clean.equalsIgnoreCase("artist") ||
            clean.equalsIgnoreCase("song") || clean.equalsIgnoreCase("track")) return true;
        return false;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {}
    }
}
