package top.zw.frpc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;

public class HttpFileService extends Service {

    private static final String CHANNEL_ID = "http_file_channel";
    private static final int NOTIFY_ID = 1002;

    private ServerSocket serverSocket;
    private Thread serverThread;
    private int port;
    private String rootPath;
    private String bindAddr;

    private static boolean running = false;
    private static StatusCallback statusCb;

    public interface StatusCallback {
        void onStatus(boolean isRunning, int port, String root);
    }

    public static void setStatusCallback(StatusCallback cb) {
        statusCb = cb;
    }

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        port = intent.getIntExtra("port", 8080);
        rootPath = intent.getStringExtra("root");
        bindAddr = intent.getStringExtra("bind");
        if (rootPath == null || rootPath.isEmpty()) {
            // Fallback to app-private directory
            java.io.File dir = getExternalFilesDir("www");
            if (dir == null) dir = new java.io.File(getFilesDir(), "www");
            rootPath = dir.getAbsolutePath();
        }
        if (bindAddr == null || bindAddr.isEmpty()) {
            bindAddr = "0.0.0.0";
        }

        new File(rootPath).mkdirs();

        String bindInfo = "http://" + bindAddr + ":" + port;
        startForeground(NOTIFY_ID, buildNotification("正在启动 " + bindInfo + "  " + rootPath));

        serverThread = new Thread(() -> {
            try {
                InetAddress bindInet = InetAddress.getByName(bindAddr);
                serverSocket = new ServerSocket(port, 50, bindInet);
                running = true;
                notifyStatus(true);
                updateNotification("运行中 " + bindInfo + "  " + rootPath);

                while (!Thread.interrupted()) {
                    try {
                        Socket client = serverSocket.accept();
                        new Thread(new HttpClientHandler(client, rootPath)).start();
                    } catch (IOException e) {
                        break;
                    }
                }
            } catch (Exception e) {
                running = false;
                notifyStatus(false);
                stopSelf();
            }
        });
        serverThread.start();

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        notifyStatus(false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
            serverSocket = null;
        }
        if (serverThread != null) {
            serverThread.interrupt();
            serverThread = null;
        }
        super.onDestroy();
    }

    private void notifyStatus(boolean isRunning) {
        if (statusCb != null) {
            statusCb.onStatus(isRunning, port, rootPath);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "HTTP 文件服务器", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Parse text: "运行中 0.0.0.0:8080  /sdcard/www"
        // Split into lines for big style
        String title = "HTTP 文件服务器";
        String addrLine = text;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(addrLine)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(pi)
                .setOngoing(true);

        // Big text style for expanded notification
        String[] parts = text.split("  ", 2);
        NotificationCompat.BigTextStyle bigStyle = new NotificationCompat.BigTextStyle();
        StringBuilder bigText = new StringBuilder();
        if (parts.length >= 2) {
            bigText.append("状态: ").append(parts[0]).append("\n");
            bigText.append("根目录: ").append(parts[1]);
        } else {
            bigText.append(text);
        }
        bigStyle.bigText(bigText.toString());
        builder.setStyle(bigStyle);

        return builder.build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFY_ID, buildNotification(text));
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- HTTP Request Handler ----

    static class HttpClientHandler implements Runnable {
        private final Socket socket;
        private final String rootPath;

        HttpClientHandler(Socket socket, String root) {
            this.socket = socket;
            this.rootPath = root;
        }

        @Override
        public void run() {
            try {
                // Read request line
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String requestLine = reader.readLine();
                if (requestLine == null || requestLine.isEmpty()) {
                    socket.close();
                    return;
                }

                // Parse GET /path HTTP/1.1
                String[] parts = requestLine.split(" ");
                String method = parts.length > 0 ? parts[0] : "";
                String rawPath = parts.length > 1 ? parts[1] : "/";

                // Skip remaining request headers
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {}

                if (!"GET".equals(method)) {
                    sendResponse(405, "Method Not Allowed", "text/plain");
                    return;
                }

                // Decode and sanitize path
                rawPath = URLDecoder.decode(rawPath, "UTF-8");
                if (rawPath.contains("..") || rawPath.contains("~")) {
                    sendResponse(403, "Forbidden", "text/plain");
                    return;
                }

                // Build absolute file path: rootPath + requested path
                // Ensure rootPath doesn't end with /
                String base = rootPath.endsWith("/") ? rootPath.substring(0, rootPath.length() - 1) : rootPath;
                // Remove leading / from requested path for concatenation
                String reqPath = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
                String absPath = base + "/" + reqPath;
                // Normalize: remove trailing /
                if (absPath.endsWith("/") && absPath.length() > 1) {
                    absPath = absPath.substring(0, absPath.length() - 1);
                }

                File file = new File(absPath);

                // Security: ensure we're still inside rootPath
                if (!absPath.startsWith(base)) {
                    sendResponse(403, "Forbidden", "text/plain");
                    return;
                }

                if (!file.exists()) {
                    // If path doesn't exist, check if it's a directory path missing index.html
                    // or try appending .html
                    File asDir = new File(absPath);
                    if (asDir.isDirectory()) {
                        sendDirectoryListing(asDir, rawPath);
                        return;
                    }
                    File withHtml = new File(absPath + ".html");
                    if (withHtml.exists()) {
                        file = withHtml;
                    } else {
                        sendResponse(404, "Not Found", "text/plain");
                        return;
                    }
                }

                if (file.isDirectory()) {
                    sendDirectoryListing(file, rawPath);
                    return;
                }

                // Serve the file
                String mime = getMimeType(file.getName());
                long fileLen = file.length();
                sendFileResponse(file, mime);

            } catch (Exception e) {
                try {
                    sendResponse(500, "Error: " + e.getMessage(), "text/plain");
                } catch (Exception ignored) {}
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {}
            }
        }

        private void sendDirectoryListing(File dir, String uriPath) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'>");
            sb.append("<title>").append(uriPath).append("</title>");
            sb.append("<style>");
            sb.append("body{font-family:sans-serif;margin:24px;background:#f5f5f5;}");
            sb.append("h1{font-size:18px;color:#1565C0;}");
            sb.append("a{display:block;padding:6px 10px;color:#1565C0;text-decoration:none;border-bottom:1px solid #e0e0e0;}");
            sb.append("a:hover{background:#e3f2fd;}");
            sb.append("</style></head><body>");
            sb.append("<h1>").append(uriPath).append("</h1>");

            if (!"/".equals(uriPath)) {
                String p = uriPath.endsWith("/") ? uriPath.substring(0, uriPath.length()-1) : uriPath;
                int lastSlash = p.lastIndexOf('/');
                String up = lastSlash > 0 ? p.substring(0, lastSlash) : "/";
                sb.append("<a href=\"").append(up).append("\">..</a>");
            }

            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    String href = (uriPath.endsWith("/") ? uriPath : uriPath + "/") + name;
                    if (f.isDirectory()) {
                        sb.append("<a href=\"").append(href).append("/\">").append(name).append("/</a>");
                    } else {
                        sb.append("<a href=\"").append(href).append("\">").append(name).append(" (").append(formatLen(f.length())).append(")</a>");
                    }
                }
            }

            sb.append("</body></html>");
            sendResponse(200, sb.toString(), "text/html; charset=utf-8");
        }

        private String formatLen(long len) {
            if (len < 1024) return len + " B";
            if (len < 1024*1024) return String.format("%.1f KB", len/1024.0);
            return String.format("%.1f MB", len/(1024.0*1024.0));
        }

        private String getMimeType(String name) {
            String lower = name.toLowerCase();
            if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
            if (lower.endsWith(".css")) return "text/css; charset=utf-8";
            if (lower.endsWith(".js")) return "application/javascript";
            if (lower.endsWith(".json")) return "application/json";
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".gif")) return "image/gif";
            if (lower.endsWith(".svg")) return "image/svg+xml";
            if (lower.endsWith(".ico")) return "image/x-icon";
            if (lower.endsWith(".pdf")) return "application/pdf";
            if (lower.endsWith(".mp4") || lower.endsWith(".webm")) return "video/mp4";
            return "application/octet-stream";
        }

        private void sendResponse(int code, String body, String mime) throws IOException {
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            sendResponse(code, data, mime);
        }

        private void sendResponse(int code, byte[] data, String mime) throws IOException {
            OutputStream os = socket.getOutputStream();
            String status = code == 200 ? "OK" : code == 404 ? "Not Found" : code == 403 ? "Forbidden" : "Error";
            os.write(("HTTP/1.1 " + code + " " + status + "\r\n").getBytes());
            os.write(("Content-Type: " + mime + "\r\n").getBytes());
            os.write(("Content-Length: " + data.length + "\r\n").getBytes());
            os.write("Connection: close\r\n".getBytes());
            os.write("\r\n".getBytes());
            os.write(data);
            os.flush();
        }

        private void sendFileResponse(File file, String mime) throws IOException {
            OutputStream os = socket.getOutputStream();
            os.write("HTTP/1.1 200 OK\r\n".getBytes());
            os.write(("Content-Type: " + mime + "\r\n").getBytes());
            os.write(("Content-Length: " + file.length() + "\r\n").getBytes());
            os.write("Connection: close\r\n".getBytes());
            os.write("\r\n".getBytes());
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[65536];
                int len;
                while ((len = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                }
            }
            os.flush();
        }
    }
}
