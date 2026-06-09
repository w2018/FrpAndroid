package top.zw.frpc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.core.app.NotificationCompat;

import android.net.TrafficStats;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class FrpcService extends Service {

    private static final String CHANNEL_ID = "frpc_channel";
    private static final int NOTIFY_ID = 1001;
    private static final int RESTART_DELAY_MS = 15000;

    private Process frpcProcess;
    private Thread trafficThread;
    private Handler mainHandler;
    private int proxyCount = 0;
    private String currentServerAddr = "";

    // Cached config for auto-restart
    private String cachedServerAddr;
    private int cachedServerPort;
    private String cachedToken;
    private ArrayList<String> cachedProxyLines;
    private int restartAttempts = 0;

    private static boolean running = false;
    private static long serviceStartTime = 0;
    private static long lastTrafficIn = 0;
    private static long lastTrafficOut = 0;

    public interface LogCallback { void onLog(String line); }
    public interface ProxyStatusCallback { void onStatus(String proxyName, int status); }
    public interface TrafficCallback { void onTrafficUpdate(long totalIn, long totalOut); }
    public interface ServiceStatusCallback { void onServiceStatus(boolean isRunning); }

    private static LogCallback callback;
    private static ProxyStatusCallback statusCallback;
    private static TrafficCallback trafficCallback;
    private static ServiceStatusCallback serviceStatusCallback;

    public static void setLogCallback(LogCallback cb) { callback = cb; }
    public static void setProxyStatusCallback(ProxyStatusCallback cb) { statusCallback = cb; }
    public static void setTrafficCallback(TrafficCallback cb) { trafficCallback = cb; }
    public static void setServiceStatusCallback(ServiceStatusCallback cb) { serviceStatusCallback = cb; }

    public static boolean isRunning() { return running; }
    public static long getServiceDurationMs() {
        if (!running || serviceStartTime == 0) return 0;
        return System.currentTimeMillis() - serviceStartTime;
    }
    public static long getLastTrafficIn() { return lastTrafficIn; }
    public static long getLastTrafficOut() { return lastTrafficOut; }

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        cachedServerAddr = intent.getStringExtra("server_addr");
        cachedServerPort = intent.getIntExtra("server_port", 8000);
        cachedToken = intent.getStringExtra("token");
        cachedProxyLines = intent.getStringArrayListExtra("proxies");
        restartAttempts = 0;

        startServiceLoop();

        return START_STICKY; // Keep service alive if killed
    }

    private void startServiceLoop() {
        proxyCount = cachedProxyLines != null ? cachedProxyLines.size() : 0;
        currentServerAddr = cachedServerAddr + ":" + cachedServerPort;

        running = true;
        serviceStartTime = System.currentTimeMillis();
        new ConfigManager(this).setRunning(true);
        notifyServiceStatus(true);

        startForeground(NOTIFY_ID, buildNotification("\u2705 正在启动...", 0));

        // Start a timer to periodically refresh notification with updated duration
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (running) {
                    updateNotification("\u2705 运行中", proxyCount);
                    mainHandler.postDelayed(this, 30000); // every 30 seconds
                }
            }
        }, 30000);

        new Thread(() -> {
            try {
                File binFile = extractBinary();
                if (binFile == null) {
                    log("错误: 无法提取 frpc 二进制");
                    stopServiceAndCleanup();
                    return;
                }

                File configFile = generateConfig(cachedServerAddr, cachedServerPort, cachedToken, cachedProxyLines);
                if (configFile == null) {
                    log("错误: 无法生成配置文件");
                    stopServiceAndCleanup();
                    return;
                }

                log("正在启动 frpc...");
                log("服务器: " + cachedServerAddr + ":" + cachedServerPort);
                log("代理数: " + proxyCount);

                ProcessBuilder pb = new ProcessBuilder(
                        binFile.getAbsolutePath(),
                        "-c", configFile.getAbsolutePath()
                );
                pb.directory(binFile.getParentFile());
                pb.environment().put("HOME", binFile.getParentFile().getAbsolutePath());

                frpcProcess = pb.start();
                restartAttempts = 0; // Reset on successful launch

                updateNotification("\u2705 运行中", proxyCount);

                startTrafficPolling();

                Thread stdoutThread = new Thread(() -> {
                    try {
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(frpcProcess.getInputStream()));
                        String line;
                        while ((line = reader.readLine()) != null) {
                            log(line);
                        }
                    } catch (IOException ignored) {}
                });
                stdoutThread.start();

                Thread stderrThread = new Thread(() -> {
                    try {
                        BufferedReader errorReader = new BufferedReader(
                                new InputStreamReader(frpcProcess.getErrorStream()));
                        String line;
                        while ((line = errorReader.readLine()) != null) {
                            log("[ERR] " + line);
                        }
                    } catch (IOException ignored) {}
                });
                stderrThread.start();

                int exitCode = frpcProcess.waitFor();
                log("frpc \u5df2\u9000\u51fa\uff0c\u9000\u51fa\u7801: " + exitCode);

                // Auto-restart on abnormal exit
                if (exitCode != 0 && running) {
                    handleCrashAndRestart();
                }

            } catch (Exception e) {
                log("异常: " + e.getMessage());
                if (running) handleCrashAndRestart();
            } finally {
                if (!running || !shouldRestart()) {
                    stopServiceAndCleanup();
                }
            }
        }).start();
    }

    private boolean shouldRestart() {
        return running;
    }

    private void handleCrashAndRestart() {
        restartAttempts++;
        log("frpc \u5f02\u5e38\u9000\u51fa\uff0c" + RESTART_DELAY_MS/1000 + "\u79d2\u540e\u91cd\u542f (#" + restartAttempts + ")");
        try { Thread.sleep(RESTART_DELAY_MS); } catch (InterruptedException ignored) {}
        if (running) {
            startServiceLoop();
        }
    }

    private void stopServiceAndCleanup() {
        running = false;
        serviceStartTime = 0;
        ConfigManager cfg = new ConfigManager(this);
        cfg.setRunning(false);
        cfg.clearAllProxyStatus();
        notifyServiceStatus(false);
        stopTrafficPolling();
        if (frpcProcess != null) {
            frpcProcess.destroy();
            frpcProcess = null;
        }
        updateNotification("\u274c \u5df2\u505c\u6b62", 0);
        stopSelf();
    }

    private void notifyServiceStatus(boolean isRunning) {
        ConfigManager cfg = new ConfigManager(this);
        cfg.setRunning(isRunning);
        mainHandler.post(() -> {
            if (serviceStatusCallback != null) serviceStatusCallback.onServiceStatus(isRunning);
        });
    }

    private void startTrafficPolling() {
        final int uid = android.os.Process.myUid();
        trafficThread = new Thread(() -> {
            ConfigManager cfg = new ConfigManager(FrpcService.this);
            long prevRx = 0, prevTx = 0;

            while (!Thread.interrupted()) {
                try {
                    Thread.sleep(3000);

                    long curRx = TrafficStats.getUidRxBytes(uid);
                    long curTx = TrafficStats.getUidTxBytes(uid);

                    // If UNSUPPORTED, try NetworkStatsManager as fallback
                    if (curRx == TrafficStats.UNSUPPORTED || curTx == TrafficStats.UNSUPPORTED) {
                        long[] ns = fetchNetworkStats(uid);
                        if (ns != null) {
                            long totalRx = cfg.getTrafficTotalIn();
                            long totalTx = cfg.getTrafficTotalOut();
                            if (prevRx > 0 && ns[0] >= prevRx) {
                                long dIn = ns[0] - prevRx;
                                long dOut = ns[1] - prevTx;
                                if (dIn > 0) totalRx += dIn;
                                if (dOut > 0) totalTx += dOut;
                                if (dIn > 0 || dOut > 0) {
                                    cfg.addDailyTraffic(Math.max(0, dIn), Math.max(0, dOut));
                                }
                            }
                            // First poll: just establish baseline
                            prevRx = ns[0];
                            prevTx = ns[1];
                            lastTrafficIn = totalRx;
                            lastTrafficOut = totalTx;
                            cfg.saveTrafficStats(totalRx, totalTx);
                            final long fRx = totalRx, fTx = totalTx;
                            mainHandler.post(() -> {
                                if (trafficCallback != null) trafficCallback.onTrafficUpdate(fRx, fTx);
                            });
                        }
                        continue;
                    }

                    // Normal TrafficStats approach (delta accumulation)
                    long totalRx = cfg.getTrafficTotalIn();
                    long totalTx = cfg.getTrafficTotalOut();

                    long deltaRx = 0, deltaTx = 0;

                    if (prevRx > 0 && curRx >= prevRx) {
                        deltaRx = curRx - prevRx;
                    }
                    if (prevTx > 0 && curTx >= prevTx) {
                        deltaTx = curTx - prevTx;
                    }

                    // First poll: just establish baseline, don't add to cumulative
                    if (prevRx > 0) {
                        if (deltaRx > 0) totalRx += deltaRx;
                        if (deltaTx > 0) totalTx += deltaTx;
                        if (deltaRx > 0 || deltaTx > 0) {
                            cfg.addDailyTraffic(deltaRx, deltaTx);
                        }
                    }
                    prevRx = curRx;
                    prevTx = curTx;

                    lastTrafficIn = totalRx;
                    lastTrafficOut = totalTx;
                    cfg.saveTrafficStats(totalRx, totalTx);

                    final long fRx = totalRx, fTx = totalTx;
                    mainHandler.post(() -> {
                        if (trafficCallback != null) trafficCallback.onTrafficUpdate(fRx, fTx);
                    });
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        trafficThread.start();
    }

    private long[] fetchNetworkStats(int uid) {
        try {
            android.app.usage.NetworkStatsManager nsm = (android.app.usage.NetworkStatsManager)
                    getSystemService(NETWORK_STATS_SERVICE);
            if (nsm == null) return null;

            long now = System.currentTimeMillis();
            // Query from a fixed anchor point to get semi-cumulative values
            // Use a long window (10 min) so delta between polls works
            long start = now - 600000;
            long totalRx = 0, totalTx = 0;
            android.app.usage.NetworkStats.Bucket bucket = new android.app.usage.NetworkStats.Bucket();

            try (android.app.usage.NetworkStats wifiStats = nsm.queryDetailsForUid(1, null, start, now, uid)) {
                if (wifiStats != null) {
                    while (wifiStats.hasNextBucket()) {
                        wifiStats.getNextBucket(bucket);
                        totalRx += bucket.getRxBytes();
                        totalTx += bucket.getTxBytes();
                    }
                }
            }

            try (android.app.usage.NetworkStats mobileStats = nsm.queryDetailsForUid(0, null, start, now, uid)) {
                if (mobileStats != null) {
                    while (mobileStats.hasNextBucket()) {
                        mobileStats.getNextBucket(bucket);
                        totalRx += bucket.getRxBytes();
                        totalTx += bucket.getTxBytes();
                    }
                }
            }

            return new long[]{totalRx, totalTx};
        } catch (SecurityException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private void stopTrafficPolling() {
        if (trafficThread != null) {
            trafficThread.interrupt();
            trafficThread = null;
        }
    }

    private File extractBinary() throws IOException {
        String nativeLibDir = getApplicationInfo().nativeLibraryDir;
        File target = new File(nativeLibDir, "libfrp_native.so");
        if (target.exists()) {
            log("frpc 二进制: " + target.getAbsolutePath());
            return target;
        }

        File legacyTarget = new File("/data/data/" + getPackageName() + "/lib/libfrp_native.so");
        if (legacyTarget.exists()) return legacyTarget;

        log("从 APK 提取 frpc 二进制...");
        File targetDir = new File(getFilesDir(), "frpc-bin");
        targetDir.mkdirs();
        File targetFile = new File(targetDir, "frpc");

        try (ZipFile zip = new ZipFile(getApplicationInfo().sourceDir)) {
            ZipEntry entry = zip.getEntry("lib/arm64-v8a/libfrp_native.so");
            if (entry == null) {
                log("错误: APK 中未找到 libfrp_native.so");
                return null;
            }
            try (java.io.InputStream is = zip.getInputStream(entry);
                 FileOutputStream os = new FileOutputStream(targetFile)) {
                byte[] buffer = new byte[65536];
                int len;
                while ((len = is.read(buffer)) != -1) { os.write(buffer, 0, len); }
            }
        }

        targetFile.setExecutable(true, false);
        if (targetFile.exists() && targetFile.canExecute()) return targetFile;

        File altDir = new File(getFilesDir(), "exec");
        altDir.mkdirs();
        File altFile = new File(altDir, "frpc");
        java.nio.file.Files.copy(targetFile.toPath(), altFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        altFile.setExecutable(true, false);
        if (altFile.canExecute()) return altFile;

        log("错误: 无法获取可执行 frpc 二进制");
        return null;
    }

    private File generateConfig(String serverAddr, int serverPort, String token,
                                 ArrayList<String> proxyLines) {
        try {
            File dir = new File(getFilesDir(), "frpc-config");
            dir.mkdirs();
            File configFile = new File(dir, "frpc.toml");

            StringBuilder sb = new StringBuilder();
            sb.append("serverAddr = \"").append(escapeToml(serverAddr)).append("\"\n");
            sb.append("serverPort = ").append(serverPort).append("\n");
            if (token != null && !token.isEmpty()) {
                sb.append("auth.token = \"").append(escapeToml(token)).append("\"\n");
            }
            sb.append("dnsServer = \"8.8.8.8\"\n\n");

            if (proxyLines != null) {
                for (String rawLine : proxyLines) {
                    ProxyItem item = ProxyItem.fromLine(rawLine);
                    if (item == null) continue;
                    String type = item.getType();
                    sb.append("[[proxies]]\n");
                    sb.append("name = \"").append(escapeToml(item.getName())).append("\"\n");
                    sb.append("type = \"").append(escapeToml(type)).append("\"\n");
                    sb.append("localIP = \"").append(escapeToml(item.getLocalIP())).append("\"\n");
                    sb.append("localPort = ").append(item.getLocalPort()).append("\n");

                    if (type.equals("tcp") || type.equals("udp")) {
                        if (item.getRemotePort() > 0) sb.append("remotePort = ").append(item.getRemotePort()).append("\n");
                    } else if (type.equals("http") || type.equals("https") || type.equals("tcpmux")) {
                        String domains = item.getCustomDomains();
                        if (domains != null && !domains.isEmpty()) {
                            if (domains.contains(",")) sb.append("customDomains = [\"").append(domains.replace(",", "\", \"")).append("\"]\n");
                            else sb.append("customDomains = [\"").append(escapeToml(domains)).append("\"]\n");
                        }
                        String sd = item.getSubdomain();
                        if (sd != null && !sd.isEmpty()) sb.append("subdomain = \"").append(escapeToml(sd)).append("\"\n");
                    }
                    if (type.equals("tcpmux")) sb.append("multiplexer = \"httpconnect\"\n");
                    if (type.equals("http")) {
                        if (item.getHttpUser() != null && !item.getHttpUser().isEmpty())
                            sb.append("httpUser = \"").append(escapeToml(item.getHttpUser())).append("\"\n");
                        if (item.getHttpPassword() != null && !item.getHttpPassword().isEmpty())
                            sb.append("httpPassword = \"").append(escapeToml(item.getHttpPassword())).append("\"\n");
                        if (item.getHostHeaderRewrite() != null && !item.getHostHeaderRewrite().isEmpty())
                            sb.append("hostHeaderRewrite = \"").append(escapeToml(item.getHostHeaderRewrite())).append("\"\n");
                        if (item.getLocations() != null && !item.getLocations().isEmpty()) {
                            if (item.getLocations().contains(","))
                                sb.append("locations = [\"").append(item.getLocations().replace(",", "\", \"")).append("\"]\n");
                            else sb.append("locations = [\"").append(escapeToml(item.getLocations())).append("\"]\n");
                        }
                    }
                    if (type.equals("stcp") || type.equals("sudp") || type.equals("xtcp")) {
                        if (item.getSecretKey() != null && !item.getSecretKey().isEmpty())
                            sb.append("secretKey = \"").append(escapeToml(item.getSecretKey())).append("\"\n");
                    }
                    if (item.isUseEncryption()) sb.append("transport.useEncryption = true\n");
                    if (item.isUseCompression()) sb.append("transport.useCompression = true\n");
                    if (item.getBandwidthLimit() != null && !item.getBandwidthLimit().isEmpty())
                        sb.append("transport.bandwidthLimit = \"").append(escapeToml(item.getBandwidthLimit())).append("\"\n");
                    if (item.isHealthCheck()) {
                        String hc = item.getHealthCheckType();
                        if (hc == null || hc.isEmpty()) hc = "tcp";
                        sb.append("healthCheck.type = \"").append(escapeToml(hc)).append("\"\n");
                        sb.append("healthCheck.timeoutSeconds = 3\nhealthCheck.maxFailed = 3\nhealthCheck.intervalSeconds = 10\n");
                    }
                    sb.append("\n");
                }
            }

            try (FileOutputStream fos = new FileOutputStream(configFile)) {
                fos.write(sb.toString().getBytes("UTF-8"));
            }
            return configFile;
        } catch (Exception e) {
            log("配置生成失败: " + e.getMessage());
            return null;
        }
    }

    private String escapeToml(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private void log(final String line) {
        if (line != null) {
            String body = line;
            boolean isErr = false;
            if (line.startsWith("[ERR] ")) { body = line.substring(6); isErr = true; }

            // Match: "[代理名] start proxy success"
            if (body.contains("] start proxy success")) {
                // Walk backwards from "]" before " start proxy success" to find "["
                int successIdx = body.indexOf("] start proxy success");
                int bracketStart = body.lastIndexOf("[", successIdx);
                if (bracketStart >= 0 && successIdx > bracketStart) {
                    String proxyName = body.substring(bracketStart + 1, successIdx).trim();
                    if (!proxyName.isEmpty()) {
                        proxyStatusCallback(proxyName, ProxyItem.STATUS_SUCCESS);
                    }
                }
            }
            // Match: "[代理名] start error: ..."
            if (body.contains("] start error")) {
                int errorIdx = body.indexOf("] start error");
                int bracketStart = body.lastIndexOf("[", errorIdx);
                if (bracketStart >= 0 && errorIdx > bracketStart) {
                    String proxyName = body.substring(bracketStart + 1, errorIdx).trim();
                    if (!proxyName.isEmpty()) {
                        proxyStatusCallback(proxyName, ProxyItem.STATUS_FAIL);
                    }
                }
            }
        }
        mainHandler.post(() -> { if (callback != null) callback.onLog(line); });
    }

    private void proxyStatusCallback(String name, int status) {
        new ConfigManager(this).saveProxyStatus(name, status);
        mainHandler.post(() -> {
            if (statusCallback != null) statusCallback.onStatus(name, status);
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "FRP 服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("FRP 客户端前台服务通知");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String status, int count) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String serverInfo = currentServerAddr.isEmpty() ? "" : currentServerAddr;

        // status already has 🟢 / 🔴 prefix
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("FRP 穿透")
                .setContentText(status + " · " + serverInfo)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(pi)
                .setOngoing(true);

        NotificationCompat.BigTextStyle bigStyle = new NotificationCompat.BigTextStyle();
        StringBuilder bigText = new StringBuilder();
        bigText.append(status).append("\n");
        bigText.append("服务器: ").append(serverInfo).append("\n");
        long durMs = getServiceDurationMs();
        if (durMs > 0) {
            long h = durMs / 3600000;
            long m = (durMs % 3600000) / 60000;
            bigText.append("已运行: ").append(h > 0 ? h + "小时" + m + "分" : m + "分钟").append("\n");
        }
        bigText.append("代理: ").append(count > 0 ? count + " 个" : "0 个");
        bigStyle.bigText(bigText.toString());
        builder.setStyle(bigStyle);

        return builder.build();
    }

    private void updateNotification(String status, int count) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFY_ID, buildNotification(status, count));
    }

    @Override
    public void onDestroy() {
        running = false;
        serviceStartTime = 0;
        ConfigManager destroyCfg = new ConfigManager(this);
        destroyCfg.setRunning(false);
        destroyCfg.clearAllProxyStatus();
        notifyServiceStatus(false);
        if (frpcProcess != null) { frpcProcess.destroy(); frpcProcess = null; }
        stopTrafficPolling();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
