package top.zw.frpc;

import android.app.usage.UsageStatsManager;
import android.app.usage.UsageEvents;
import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class ConfigManager {
    private static final String PREF_NAME = "frp_config";
    private static final String KEY_SERVER_ADDR = "server_addr";
    private static final String KEY_SERVER_PORT = "server_port";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_PROXY_COUNT = "proxy_count";
    private static final String KEY_PROXY_PREFIX = "proxy_";
    private static final String KEY_HISTORY = "config_history";
    private static final String KEY_IS_RUNNING = "is_running";
    private static final String KEY_TRAFFIC_IN = "traffic_total_in";
    private static final String KEY_TRAFFIC_OUT = "traffic_total_out";
    private static final String KEY_AUTO_START = "auto_start";
    private static final String KEY_HTTP_PORT = "http_port";
    private static final String KEY_HTTP_ROOT = "http_root";
    private static final String KEY_HTTP_BIND = "http_bind";

    private final SharedPreferences prefs;

    public ConfigManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void saveServerConfig(String addr, int port, String token) {
        prefs.edit()
            .putString(KEY_SERVER_ADDR, addr)
            .putInt(KEY_SERVER_PORT, port)
            .putString(KEY_TOKEN, token)
            .apply();
    }

    public String getServerAddr() {
        return prefs.getString(KEY_SERVER_ADDR, "public.freefrp.org");
    }

    public int getServerPort() {
        return prefs.getInt(KEY_SERVER_PORT, 8000);
    }

    public String getToken() {
        return prefs.getString(KEY_TOKEN, "public.freefrp.com");
    }

    // ---- Running state ----

    public void setRunning(boolean running) {
        prefs.edit().putBoolean(KEY_IS_RUNNING, running).apply();
    }

    public boolean isRunning() {
        return prefs.getBoolean(KEY_IS_RUNNING, false);
    }

    // ---- Server history ----
    private static final String KEY_SRVHIST_COUNT = "srvhist_count";
    private static final String KEY_SRVHIST_PREFIX = "srvhist_";

    public void saveServerHistory(String addr, int port, String token) {
        List<String> items = new ArrayList<>();
        int count = prefs.getInt(KEY_SRVHIST_COUNT, 0);
        for (int i = 0; i < count; i++) {
            String item = prefs.getString(KEY_SRVHIST_PREFIX + i, null);
            // Filter out any entry with same addr+port (regardless of token)
            if (item != null && !item.startsWith(addr + "|" + port + "|")) {
                items.add(item);
            }
        }
        // Build entry: addr|port|token
        String entry = addr + "|" + port + "|" + (token != null ? token : "");
        items.add(0, entry); // newest first
        if (items.size() > 20) items = items.subList(0, 20);

        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(KEY_SRVHIST_COUNT, items.size());
        for (int i = 0; i < items.size(); i++) {
            editor.putString(KEY_SRVHIST_PREFIX + i, items.get(i));
        }
        editor.apply();
    }

    public List<String[]> loadServerHistory() {
        List<String[]> list = new ArrayList<>();
        int count = prefs.getInt(KEY_SRVHIST_COUNT, 0);
        for (int i = 0; i < count; i++) {
            String raw = prefs.getString(KEY_SRVHIST_PREFIX + i, null);
            if (raw != null) {
                String[] parts = raw.split("\\|", 3);
                if (parts.length >= 2) {
                    list.add(parts);
                }
            }
        }
        return list;
    }

    // ---- Proxy status persistence ----
    private static final String KEY_PROXY_STATUS_PREFIX = "pstatus_";

    public void saveProxyStatus(String proxyName, int status) {
        prefs.edit().putInt(KEY_PROXY_STATUS_PREFIX + proxyName, status).apply();
    }

    public int loadProxyStatus(String proxyName) {
        return prefs.getInt(KEY_PROXY_STATUS_PREFIX + proxyName, ProxyItem.STATUS_UNKNOWN);
    }

    public void clearAllProxyStatus() {
        SharedPreferences.Editor editor = prefs.edit();
        java.util.Map<String, ?> all = prefs.getAll();
        for (String key : all.keySet()) {
            if (key.startsWith(KEY_PROXY_STATUS_PREFIX)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    // ---- Daily stats (today's traffic + runtime) ----
    private static final String KEY_DAILY_DATE = "daily_date";
    private static final String KEY_DAILY_TRAFFIC_IN = "daily_traffic_in";
    private static final String KEY_DAILY_TRAFFIC_OUT = "daily_traffic_out";

    public void checkAndResetDaily() {
        String today = getTodayDateStr();
        String saved = prefs.getString(KEY_DAILY_DATE, "");
        if (!today.equals(saved)) {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(KEY_DAILY_DATE, today);
            editor.putLong(KEY_DAILY_TRAFFIC_IN, 0);
            editor.putLong(KEY_DAILY_TRAFFIC_OUT, 0);
            editor.apply();
        }
    }

    public void addDailyTraffic(long inBytes, long outBytes) {
        checkAndResetDaily();
        long in = prefs.getLong(KEY_DAILY_TRAFFIC_IN, 0);
        long out = prefs.getLong(KEY_DAILY_TRAFFIC_OUT, 0);
        prefs.edit()
                .putLong(KEY_DAILY_TRAFFIC_IN, in + inBytes)
                .putLong(KEY_DAILY_TRAFFIC_OUT, out + outBytes)
                .apply();
    }

    public long getDailyTrafficIn() {
        checkAndResetDaily();
        return prefs.getLong(KEY_DAILY_TRAFFIC_IN, 0);
    }

    public long getDailyTrafficOut() {
        checkAndResetDaily();
        return prefs.getLong(KEY_DAILY_TRAFFIC_OUT, 0);
    }

    /** Get today's foreground duration for our app in milliseconds */
    public long getAppForegroundTimeToday(Context context) {
        try {
            UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return 0;

            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            long startOfDay = cal.getTimeInMillis();
            long now = System.currentTimeMillis();

            UsageEvents events = usm.queryEvents(startOfDay, now);
            String ourPkg = context.getPackageName();
            long totalForeground = 0;
            long lastEventTime = 0;

            while (events.hasNextEvent()) {
                UsageEvents.Event event = new UsageEvents.Event();
                events.getNextEvent(event);
                if (!ourPkg.equals(event.getPackageName())) continue;

                if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    lastEventTime = event.getTimeStamp();
                } else if (event.getEventType() == UsageEvents.Event.MOVE_TO_BACKGROUND ||
                           event.getEventType() == UsageEvents.Event.ACTIVITY_STOPPED) {
                    if (lastEventTime > 0) {
                        totalForeground += event.getTimeStamp() - lastEventTime;
                        lastEventTime = 0;
                    }
                }
            }
            // If still in foreground, count until now
            if (lastEventTime > 0) {
                totalForeground += now - lastEventTime;
            }
            return totalForeground;
        } catch (Exception e) {
            return 0;
        }
    }

    private String getTodayDateStr() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
    }

    // ---- Auto start ----

    public void setAutoStart(boolean auto) {
        prefs.edit().putBoolean(KEY_AUTO_START, auto).apply();
    }

    public boolean isAutoStart() {
        return prefs.getBoolean(KEY_AUTO_START, false);
    }

    // ---- Proxies ----

    public void saveProxies(List<ProxyItem> proxies) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(KEY_PROXY_COUNT, proxies.size());
        for (int i = 0; i < proxies.size(); i++) {
            editor.putString(KEY_PROXY_PREFIX + i, proxies.get(i).toLine());
        }
        editor.apply();
    }

    public List<ProxyItem> loadProxies() {
        int count = prefs.getInt(KEY_PROXY_COUNT, 1);
        List<ProxyItem> list = new ArrayList<>();
        if (count > 0 && prefs.contains(KEY_PROXY_PREFIX + "0")) {
            for (int i = 0; i < count; i++) {
                String line = prefs.getString(KEY_PROXY_PREFIX + i, "");
                ProxyItem item = ProxyItem.fromLine(line);
                if (item != null) list.add(item);
            }
        } else {
            list.add(new ProxyItem("ssh", "tcp", 22, 15555));
        }
        return list;
    }

    // ---- Traffic stats ----

    public void saveTrafficStats(long totalIn, long totalOut) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putLong(KEY_TRAFFIC_IN, totalIn);
        editor.putLong(KEY_TRAFFIC_OUT, totalOut);
        editor.apply();
    }

    public long getTrafficTotalIn() {
        return prefs.getLong(KEY_TRAFFIC_IN, 0);
    }

    public long getTrafficTotalOut() {
        return prefs.getLong(KEY_TRAFFIC_OUT, 0);
    }

    public void resetTrafficStats() {
        prefs.edit()
            .putLong(KEY_TRAFFIC_IN, 0)
            .putLong(KEY_TRAFFIC_OUT, 0)
            .apply();
    }

    public void resetDailyStats() {
        prefs.edit()
            .putLong(KEY_DAILY_TRAFFIC_IN, 0)
            .putLong(KEY_DAILY_TRAFFIC_OUT, 0)
            .apply();
    }

    // ---- HTTP server config ----

    public void saveHttpConfig(int port, String root, int bindMode) {
        prefs.edit()
            .putInt(KEY_HTTP_PORT, port)
            .putString(KEY_HTTP_ROOT, root)
            .putInt(KEY_HTTP_BIND, bindMode)
            .apply();
    }

    public int getHttpPort() {
        return prefs.getInt(KEY_HTTP_PORT, 8080);
    }

    public String getHttpRoot() {
        return prefs.getString(KEY_HTTP_ROOT, "");
    }

    public int getHttpBindMode() {
        return prefs.getInt(KEY_HTTP_BIND, 0);
    }

    public String getEffectiveHttpRoot(Context context) {
        String saved = prefs.getString(KEY_HTTP_ROOT, "");
        if (saved == null || saved.isEmpty()) {
            // Default to app-private directory (no Scoped Storage issues)
            java.io.File dir = context.getExternalFilesDir("www");
            if (dir == null) dir = new java.io.File(context.getFilesDir(), "www");
            return dir.getAbsolutePath();
        }
        return saved;
    }

    // ---- Deleted proxy history (individual items) ----
    private static final String KEY_DELETED_COUNT = "deleted_count";
    private static final String KEY_DELETED_ITEM = "deleted_";

    public void saveDeletedProxy(ProxyItem proxy) {
        List<String> items = new ArrayList<>();
        int count = prefs.getInt(KEY_DELETED_COUNT, 0);
        for (int i = 0; i < count; i++) {
            String item = prefs.getString(KEY_DELETED_ITEM + i, null);
            if (item != null) items.add(item);
        }
        // Limit to 50 entries
        if (items.size() >= 50) items.remove(0);
        items.add(proxy.toLine());

        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(KEY_DELETED_COUNT, items.size());
        for (int i = 0; i < items.size(); i++) {
            editor.putString(KEY_DELETED_ITEM + i, items.get(i));
        }
        editor.apply();
    }

    public List<ProxyItem> loadDeletedProxies() {
        List<ProxyItem> list = new ArrayList<>();
        int count = prefs.getInt(KEY_DELETED_COUNT, 0);
        for (int i = 0; i < count; i++) {
            String entry = prefs.getString(KEY_DELETED_ITEM + i, null);
            if (entry != null) {
                ProxyItem item = ProxyItem.fromLine(entry);
                if (item != null) list.add(item);
            }
        }
        return list;
    }

    public void deleteDeletedProxy(int index) {
        List<String> items = new ArrayList<>();
        int count = prefs.getInt(KEY_DELETED_COUNT, 0);
        for (int i = 0; i < count; i++) {
            String entry = prefs.getString(KEY_DELETED_ITEM + i, null);
            if (entry != null) items.add(entry);
        }
        if (index >= 0 && index < items.size()) {
            items.remove(index);
        }
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(KEY_DELETED_COUNT, items.size());
        for (int i = 0; i < items.size(); i++) {
            editor.putString(KEY_DELETED_ITEM + i, items.get(i));
        }
        editor.commit();
    }
}
