package top.zw.frpc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        ConfigManager config = new ConfigManager(context);
        if (!config.isAutoStart()) return;

        // Restore saved proxy list and start frpc
        String serverAddr = config.getServerAddr();
        int serverPort = config.getServerPort();
        String token = config.getToken();

        java.util.List<ProxyItem> proxies = config.loadProxies();
        if (proxies.isEmpty()) return;

        Intent svc = new Intent(context, FrpcService.class);
        svc.putExtra("server_addr", serverAddr);
        svc.putExtra("server_port", serverPort);
        svc.putExtra("token", token);

        java.util.ArrayList<String> proxyLines = new java.util.ArrayList<>();
        for (ProxyItem p : proxies) {
            proxyLines.add(p.toLine());
        }
        svc.putStringArrayListExtra("proxies", proxyLines);

        // Use startForegroundService for Android 8+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(svc);
        } else {
            context.startService(svc);
        }
    }
}
