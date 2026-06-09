package top.zw.frpc;

import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

public class AboutFragment extends Fragment {

    private TextView tvTrafficIn;
    private TextView tvTrafficOut;
    private ConfigManager configManager;
    private Handler handler;
    private Runnable poller;

    // HTTP server controls
    private PulseRingView prvHttpStatus;
    private TextInputEditText etHttpPort;
    private TextView tvHttpRoot;
    private Spinner spBind;
    private MaterialButton btnPickDir;
    private MaterialButton btnHttpStart;
    private MaterialButton btnHttpStop;
    private TextView tvHttpStatus;
    private String selectedRootPath = null;
    private String[] bindLabels;
    private String[] bindValues;

    // Daily stats
    private TextView tvTodayRuntime;
    private TextView tvTodayTrafficIn;
    private TextView tvTodayTrafficOut;
    // Bind mode constants
    private static final int BIND_ALL = 0;
    private static final int BIND_LOOPBACK = 1;
    private static final int BIND_WIFI = 2;

    private final ActivityResultLauncher<Uri> dirPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri != null) {
                    String path = uri.getPath();
                    if (path != null) {
                        if (path.contains("primary%3A")) {
                            String encoded = path.substring(path.indexOf("primary%3A") + 9);
                            selectedRootPath = "/sdcard/" + java.net.URLDecoder.decode(encoded);
                        } else if (path.contains("primary:")) {
                            String encoded = path.substring(path.indexOf("primary:") + 8);
                            selectedRootPath = "/sdcard/" + java.net.URLDecoder.decode(encoded);
                        }
                        tvHttpRoot.setText("根目录: " + selectedRootPath);
                        try {
                            requireContext().getContentResolver().takePersistableUriPermission(
                                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception ignored) {}
                    }
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_about, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        configManager = new ConfigManager(requireContext());

        tvTrafficIn = view.findViewById(R.id.tv_traffic_in);
        tvTrafficOut = view.findViewById(R.id.tv_traffic_out);

        // Daily stats
        tvTodayRuntime = view.findViewById(R.id.tv_today_runtime);
        tvTodayTrafficIn = view.findViewById(R.id.tv_today_traffic_in);
        tvTodayTrafficOut = view.findViewById(R.id.tv_today_traffic_out);

        // Dynamic version info
        try {
            String verName = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
            ((TextView) view.findViewById(R.id.tv_app_version)).setText(verName);
        } catch (Exception ignored) {}
        ((TextView) view.findViewById(R.id.tv_frp_version)).setText("v0.68.0");

        // GitHub link
        TextView tvGithub = view.findViewById(R.id.tv_github);
        tvGithub.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/w2018/FrpAndroid"));
            startActivity(intent);
        });

        MaterialButton btnResetTraffic = view.findViewById(R.id.btn_reset_traffic);
        btnResetTraffic.setOnClickListener(v -> {
            configManager.resetTrafficStats();
            updateTrafficDisplay(0, 0);
            Snackbar.make(requireView(), "流量统计已重置", Snackbar.LENGTH_SHORT).show();
        });

        // HTTP server controls
        etHttpPort = view.findViewById(R.id.et_http_port);
        tvHttpRoot = view.findViewById(R.id.tv_http_root);
        spBind = view.findViewById(R.id.sp_http_bind);
        btnPickDir = view.findViewById(R.id.btn_pick_dir);
        btnHttpStart = view.findViewById(R.id.btn_http_start);
        btnHttpStop = view.findViewById(R.id.btn_http_stop);
        tvHttpStatus = view.findViewById(R.id.tv_http_status);
        prvHttpStatus = view.findViewById(R.id.prv_http_status);

        // Bind address spinner
        String wifiIp = getWifiIpAddress();
        bindLabels = new String[]{"0.0.0.0", "127.0.0.1", wifiIp};
        bindValues = new String[]{"0.0.0.0", "127.0.0.1", wifiIp};

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, bindLabels);
        spBind.setAdapter(adapter);

        // Restore saved HTTP config
        int savedPort = configManager.getHttpPort();
        String savedRoot = configManager.getEffectiveHttpRoot(requireContext());
        int savedBind = configManager.getHttpBindMode();

        etHttpPort.setText(String.valueOf(savedPort));
        selectedRootPath = savedRoot;
        tvHttpRoot.setText("根目录: " + savedRoot);
        spBind.setSelection(savedBind);

        // Ensure root directory exists with a sample index.html
        ensureRootDir();

        // File access permission (Android 11+)
        android.widget.Button btnFilePerm = view.findViewById(R.id.btn_file_perm);
        if (btnFilePerm != null) {
            btnFilePerm.setOnClickListener(v -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
                    startActivity(intent);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // For Android 6-10, request read storage at runtime
                    requestPermissions(new String[]{
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    }, 1001);
                }
            });
        }

        // Battery
        MaterialButton btnBattery = view.findViewById(R.id.btn_battery);
        btnBattery.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
                startActivity(intent);
            }
        });

        // Usage stats permission
        com.google.android.material.button.MaterialButton btnGrantUsage = view.findViewById(R.id.btn_grant_usage);
        if (btnGrantUsage != null) {
            btnGrantUsage.setOnClickListener(v -> {
                Intent intent = new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS);
                startActivity(intent);
            });
        }

        btnPickDir.setOnClickListener(v -> dirPicker.launch(null));

        btnHttpStart.setOnClickListener(v -> startHttpServer());
        btnHttpStop.setOnClickListener(v -> stopHttpServer());

        // Register callbacks
        FrpcService.setTrafficCallback((totalIn, totalOut) -> {
            configManager.saveTrafficStats(totalIn, totalOut);
            updateTrafficDisplay(totalIn, totalOut);
        });

        HttpFileService.setStatusCallback((isRunning, port, root) -> {
            updateHttpUi(isRunning);
            if (isRunning) {
                tvHttpRoot.setText("根目录: " + root);
            }
        });

        // Restore HTTP running state
        if (HttpFileService.isRunning()) {
            updateHttpUi(true);
        }

        handler = new Handler(Looper.getMainLooper());
        poller = () -> {
            long in = configManager.getTrafficTotalIn();
            long out = configManager.getTrafficTotalOut();
            updateTrafficDisplay(in, out);

            // Update daily stats
            updateDailyStats();

            handler.postDelayed(poller, 3000);
        };
    }

    @Override
    public void onResume() {
        super.onResume();
        long in = configManager.getTrafficTotalIn();
        long out = configManager.getTrafficTotalOut();
        updateTrafficDisplay(in, out);
        updateDailyStats();
        handler.postDelayed(poller, 3000);
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(poller);
    }

    private void startHttpServer() {
        String portStr = etHttpPort.getText().toString().trim();
        if (portStr.isEmpty()) {
            etHttpPort.setError("请输入端口号");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portStr);
            if (port < 1024 || port > 65535) {
                etHttpPort.setError("端口范围 1024-65535");
                return;
            }
        } catch (NumberFormatException e) {
            etHttpPort.setError("端口格式错误");
            return;
        }

        int bindMode = spBind.getSelectedItemPosition();
        String bindAddr = bindValues[bindMode];

        // Ensure root directory exists
        java.io.File rootDir = new java.io.File(selectedRootPath);
        if (!rootDir.exists()) {
            rootDir.mkdirs();
            java.io.File index = new java.io.File(rootDir, "index.html");
            if (!index.exists()) {
                try {
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(index);
                    fos.write(("<html><head><meta charset='utf-8'>" +
                        "<title>FRP HTTP Server</title>" +
                        "<style>body{font-family:sans-serif;margin:40px;text-align:center;}</style>" +
                        "</head><body>" +
                        "<h1>FRP HTTP 文件服务器</h1>" +
                        "<p>根目录: " + selectedRootPath + "</p>" +
                        "<p>端口: " + port + "</p>" +
                        "</body></html>").getBytes("UTF-8"));
                    fos.close();
                } catch (Exception ignored) {}
            }
        }

        // Save config
        configManager.saveHttpConfig(port, selectedRootPath, bindMode);

        Intent intent = new Intent(requireContext(), HttpFileService.class);
        intent.putExtra("port", port);
        intent.putExtra("root", selectedRootPath);
        intent.putExtra("bind", bindAddr);
        requireContext().startForegroundService(intent);
        updateHttpUi(true);
    }

    private void stopHttpServer() {
        requireContext().stopService(new Intent(requireContext(), HttpFileService.class));
        updateHttpUi(false);
    }

    private void updateHttpUi(boolean isRunning) {
        btnHttpStart.setEnabled(!isRunning);
        btnHttpStop.setEnabled(isRunning);
        tvHttpStatus.setText(isRunning ? "运行中" : "已停止");
        tvHttpStatus.setTextColor(isRunning ? 0xFF00BCD4 : 0xFFC62828);
        if (isRunning) {
            prvHttpStatus.startPulse();
        } else {
            prvHttpStatus.stopPulse();
        }
    }

    private void updateTrafficDisplay(long totalIn, long totalOut) {
        if (tvTrafficIn != null) tvTrafficIn.setText(formatBytes(totalIn));
        if (tvTrafficOut != null) tvTrafficOut.setText(formatBytes(totalOut));
    }

    /** Refresh the daily stats card */
    private void updateDailyStats() {
        if (tvTodayRuntime == null) return;
        // Duration
        long ms = configManager.getAppForegroundTimeToday(requireContext());
        tvTodayRuntime.setText(formatDuration(ms));

        // Traffic
        long din = configManager.getDailyTrafficIn();
        long dout = configManager.getDailyTrafficOut();
        tvTodayTrafficIn.setText(formatBytes(din));
        tvTodayTrafficOut.setText(formatBytes(dout));
    }

    private String formatDuration(long ms) {
        long totalSec = ms / 1000;
        long hours = totalSec / 3600;
        long mins = (totalSec % 3600) / 60;
        if (hours > 0) return hours + "小时" + mins + "分";
        return mins + "分钟";
    }

    private String getWifiIpAddress() {
        try {
            java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        // Common Android WiFi interface name
                        if (ni.getName().contains("wlan") || ni.getName().contains("eth")) {
                            return ip;
                        }
                        return ip; // fallback: return first non-loopback IPv4
                    }
                }
            }
        } catch (Exception ignored) {}
        return "192.168.1.xxx";
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private void ensureRootDir() {
        java.io.File rootDir = new java.io.File(selectedRootPath);
        if (!rootDir.exists()) {
            rootDir.mkdirs();
            // Create a sample index.html so users have something to start with
            java.io.File index = new java.io.File(rootDir, "index.html");
            if (!index.exists()) {
                try {
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(index);
                    String html = "<html><head><meta charset='utf-8'>" +
                        "<title>FRP HTTP Server</title>" +
                        "<style>body{font-family:sans-serif;margin:40px;text-align:center;}</style>" +
                        "</head><body>" +
                        "<h1>FRP HTTP \u6587\u4ef6\u670d\u52a1\u5668</h1>" +
                        "<p>\u6839\u76ee\u5f55: " + selectedRootPath + "</p>" +
                        "<p>\u5df2\u6b63\u5e38\u5de5\u4f5c</p>" +
                        "</body></html>";
                    fos.write(html.getBytes("UTF-8"));
                    fos.close();
                } catch (Exception ignored) {}
            }
        }
    }
}
