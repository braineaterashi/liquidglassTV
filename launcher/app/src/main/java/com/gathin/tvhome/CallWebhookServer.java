package com.gathin.tvhome;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

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
 * Allows iOS Shortcuts, curl, or external automations to trigger or dismiss the Liquid Glass call banner.
 */
public final class CallWebhookServer {
    private static final String TAG = "CallWebhookServer";
    public static final int PORT = 8088;

    private static volatile CallWebhookServer instance;
    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
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
                serverSocket = new ServerSocket(PORT);
                Log.i(TAG, "CallWebhookServer listening on port " + PORT);
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

    private final ExecutorService clientExecutor = Executors.newCachedThreadPool();

    private void handleClient(Socket socket) {
        clientExecutor.execute(() -> {
            try (socket;
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 OutputStream out = socket.getOutputStream()) {

                String line = reader.readLine();
                if (line == null) return;

                String[] parts = line.split(" ");
                if (parts.length < 2) return;

                String method = parts[0];
                String path = parts[1];

                Uri uri = Uri.parse("http://localhost" + path);
                String route = uri.getPath();

                String title = uri.getQueryParameter("title");
                if (title == null) title = uri.getQueryParameter("name");
                if (title == null) title = uri.getQueryParameter("caller");

                String subtitle = uri.getQueryParameter("subtitle");
                if (subtitle == null) subtitle = uri.getQueryParameter("number");

                String responseBody = "{\"success\":true}";

                if ("/call".equalsIgnoreCase(route) || "/notify".equalsIgnoreCase(route)) {
                    if (title != null) {
                        try {
                            title = URLDecoder.decode(title, StandardCharsets.UTF_8.name());
                        } catch (Exception ignored) {}
                    }
                    if (subtitle != null) {
                        try {
                            subtitle = URLDecoder.decode(subtitle, StandardCharsets.UTF_8.name());
                        } catch (Exception ignored) {}
                    }
                    CallBannerManager.getInstance(context).showCall(title, subtitle);
                    responseBody = "{\"success\":true,\"event\":\"call_shown\"}";
                } else if ("/dismiss".equalsIgnoreCase(route)) {
                    CallBannerManager.getInstance(context).dismiss();
                    responseBody = "{\"success\":true,\"event\":\"dismissed\"}";
                } else if ("/media".equalsIgnoreCase(route) || "/nowplaying".equalsIgnoreCase(route)) {
                    String trackTitle = uri.getQueryParameter("title");
                    String artist = uri.getQueryParameter("artist");
                    String source = uri.getQueryParameter("source");
                    String playingParam = uri.getQueryParameter("playing");

                    if (trackTitle != null) {
                        try {
                            trackTitle = URLDecoder.decode(trackTitle, StandardCharsets.UTF_8.name());
                        } catch (Exception ignored) {}
                    }
                    if (artist != null) {
                        try {
                            artist = URLDecoder.decode(artist, StandardCharsets.UTF_8.name());
                        } catch (Exception ignored) {}
                    }
                    if (source != null) {
                        try {
                            source = URLDecoder.decode(source, StandardCharsets.UTF_8.name());
                        } catch (Exception ignored) {}
                    } else {
                        source = "iPhone";
                    }

                    boolean isPlaying = true;
                    if (playingParam != null) {
                        isPlaying = !"false".equalsIgnoreCase(playingParam) &&
                                    !"0".equals(playingParam) &&
                                    !"no".equalsIgnoreCase(playingParam);
                    }

                    if (trackTitle != null && !trackTitle.isEmpty()) {
                        MediaTrack remoteTrack = new MediaTrack(
                                trackTitle,
                                artist != null ? artist : "Remote Device",
                                source,
                                isPlaying,
                                false,
                                null,
                                null
                        );
                        NowPlayingManager.getInstance().updateRemoteTrack(remoteTrack);
                        responseBody = "{\"success\":true,\"event\":\"media_updated\",\"title\":\"" + escapeJson(trackTitle) + "\",\"playing\":" + isPlaying + "}";
                    } else {
                        responseBody = "{\"error\":\"Missing 'title' query parameter\"}";
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
                        responseBody = "{\"active\":true,\"title\":\"" + escapeJson(active.title) + "\",\"artist\":\"" + escapeJson(active.artist) + "\",\"source\":\"" + escapeJson(active.sourceName) + "\",\"isTv\":" + active.isTvSource + ",\"isPlaying\":" + active.isPlaying + "}";
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
