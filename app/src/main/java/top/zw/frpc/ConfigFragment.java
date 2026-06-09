package top.zw.frpc;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

public class ConfigFragment extends Fragment {

    private TextInputEditText etServerAddr;
    private TextInputEditText etServerPort;
    private TextInputEditText etToken;
    private RecyclerView rvProxies;
    private Button btnStart;
    private Button btnStop;
    private Button btnAddProxy;
    private Button btnHistory;
    private Button btnPreset;
    private Button btnExport;
    private TextView tvStatus;

    private PulseRingView prvFrpStatus;

    private List<ProxyItem> proxyList = new ArrayList<>();
    private ProxyAdapter proxyAdapter;
    private ConfigManager configManager;

    // Type descriptions
    private static final String[] TYPE_HINTS = {
        "TCP：将本地 TCP 服务映射到公网端口，适合 SSH、数据库等。必填：name + localPort + remotePort",
        "UDP：将本地 UDP 服务映射到公网端口。必填：name + localPort + remotePort",
        "HTTP：通过域名访问本地 Web 服务，不需要 remotePort。必填：name + localPort + customDomains 或 subdomain",
        "HTTPS：通过域名访问本地 HTTPS 服务。必填：name + localPort + customDomains 或 subdomain",
        "TCPMUX：多个代理共用一个 frps 端口，靠域名选路。必填：name + localPort + customDomains",
        "STCP：安全 TCP，frps 不暴露公网端口，只有持有密钥的客户端能访问。必填：name + localPort + secretKey",
        "SUDP：安全 UDP，stcp 的 UDP 版本。必填：name + localPort + secretKey",
        "XTCP：P2P 点对点直连，节省服务器带宽。必填：name + localPort + secretKey"
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_config, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        configManager = new ConfigManager(requireContext());

        etServerAddr = view.findViewById(R.id.et_server_addr);
        etServerPort = view.findViewById(R.id.et_server_port);
        etToken = view.findViewById(R.id.et_token);
        rvProxies = view.findViewById(R.id.rv_proxies);
        btnStart = view.findViewById(R.id.btn_start);
        btnStop = view.findViewById(R.id.btn_stop);
        btnAddProxy = view.findViewById(R.id.btn_add_proxy);
        btnHistory = view.findViewById(R.id.btn_show_history);
        btnPreset = view.findViewById(R.id.btn_preset);
        btnExport = view.findViewById(R.id.btn_export_config);
        tvStatus = view.findViewById(R.id.tv_status);
        prvFrpStatus = view.findViewById(R.id.prv_frp_status);

        // Load saved config
        etServerAddr.setText(configManager.getServerAddr());
        etServerPort.setText(String.valueOf(configManager.getServerPort()));
        etToken.setText(configManager.getToken());

        proxyList = configManager.loadProxies();

        rvProxies.setLayoutManager(new LinearLayoutManager(requireContext()));
        proxyAdapter = new ProxyAdapter(proxyList,
            position -> { // delete → save to history, then remove
                ProxyItem deleted = proxyList.get(position);
                configManager.saveDeletedProxy(deleted);
                proxyList.remove(position);
                proxyAdapter.notifyItemRemoved(position);
                saveProxies();
                Snackbar.make(requireView(), "已删除: " + deleted.getName() + "，可在历史代理中恢复", Snackbar.LENGTH_SHORT).show();
            },
            position -> { // double-click edit
                showEditProxyDialog(position);
            }
        );
        rvProxies.setAdapter(proxyAdapter);

        // Auto-start switch
        SwitchCompat swAutoStart = view.findViewById(R.id.sw_auto_start);
        swAutoStart.setChecked(configManager.isAutoStart());
        swAutoStart.setOnCheckedChangeListener((buttonView, isChecked) -> {
            configManager.setAutoStart(isChecked);
        });

        // Restore running state if service is active
        restoreRunningState();

        // Register service status callback
        FrpcService.setServiceStatusCallback(isRunning -> {
            updateUiState(isRunning);
        });

        // Load saved proxy statuses from SharedPreferences
        for (ProxyItem p : proxyList) {
            int saved = configManager.loadProxyStatus(p.getName());
            if (saved != ProxyItem.STATUS_UNKNOWN) {
                p.setStatus(saved);
            }
        }
        proxyAdapter.notifyDataSetChanged();

        btnAddProxy.setOnClickListener(v -> showAddProxyDialog());
        btnHistory.setOnClickListener(v -> showHistoryDialog());
        btnStart.setOnClickListener(v -> startFrpc());
        btnStop.setOnClickListener(v -> stopFrpc());

        // Collapsible server config card
        LinearLayout llServerHeader = view.findViewById(R.id.ll_server_header);
        LinearLayout llServerBody = view.findViewById(R.id.ll_server_body);
        TextView tvCollapseIcon = view.findViewById(R.id.tv_collapse_icon);
        final boolean[] isCollapsed = {true};
        llServerBody.setVisibility(View.GONE);
        tvCollapseIcon.setText("\u25BC");

        // Long-press server address to pick from history
        etServerAddr.setOnLongClickListener(v -> {
            List<String[]> history = configManager.loadServerHistory();
            if (history.isEmpty()) {
                Snackbar.make(requireView(), "暂无历史服务器", Snackbar.LENGTH_SHORT).show();
                return true;
            }
            String[] items = new String[history.size()];
            for (int i = 0; i < history.size(); i++) {
                String[] h = history.get(i);
                String tok = h.length >= 3 && !h[2].isEmpty() ? " (已认证)" : "";
                items[i] = h[0] + ":" + h[1] + tok;
            }
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("历史服务器")
                    .setItems(items, (dialog, which) -> {
                        String[] h = history.get(which);
                        etServerAddr.setText(h[0]);
                        etServerPort.setText(h[1]);
                        // Always set or clear token — no leaks from previous selection
                        etToken.setText(h.length >= 3 ? h[2] : "");
                        saveServerConfig();
                        Snackbar.make(requireView(), "已切换服务器", Snackbar.LENGTH_SHORT).show();
                    })
                    .setPositiveButton("关闭", null)
                    .show();
            return true;
        });

        llServerHeader.setOnClickListener(v -> {
            isCollapsed[0] = !isCollapsed[0];
            llServerBody.setVisibility(isCollapsed[0] ? View.GONE : View.VISIBLE);
            tvCollapseIcon.setText(isCollapsed[0] ? "\u25BC" : "\u25B2");
        });

        // Export config button
        btnExport.setOnClickListener(v -> {
            String addr = etServerAddr.getText().toString().trim();
            String port = etServerPort.getText().toString().trim();
            String token = etToken.getText().toString().trim();

            StringBuilder sb = new StringBuilder();
            sb.append("# FRP Client Configuration\n");
            sb.append("# Generated by FRP \u7a7f\u900f v2.0.0\n\n");
            sb.append("serverAddr = \"").append(tml(addr)).append("\"\n");
            sb.append("serverPort = ").append(port).append("\n");
            if (!token.isEmpty()) {
                sb.append("auth.token = \"").append(tml(token)).append("\"\n");
            }
            sb.append("dnsServer = \"8.8.8.8\"\n\n");

            for (ProxyItem p : proxyList) {
                sb.append("[[proxies]]\n");
                sb.append("name = \"").append(tml(p.getName())).append("\"\n");
                sb.append("type = \"").append(tml(p.getType())).append("\"\n");
                sb.append("localIP = \"").append(tml(p.getLocalIP())).append("\"\n");
                sb.append("localPort = ").append(p.getLocalPort()).append("\n");

                String tp = p.getType();
                if (tp.equals("tcp") || tp.equals("udp")) {
                    if (p.getRemotePort() > 0)
                        sb.append("remotePort = ").append(p.getRemotePort()).append("\n");
                } else if (tp.equals("http") || tp.equals("https") || tp.equals("tcpmux")) {
                    String d = p.getCustomDomains();
                    if (d != null && !d.isEmpty()) {
                        if (d.contains(","))
                            sb.append("customDomains = [\"").append(d.replace(",", "\", \"")).append("\"]\n");
                        else
                            sb.append("customDomains = [\"").append(tml(d)).append("\"]\n");
                    }
                    String sd = p.getSubdomain();
                    if (sd != null && !sd.isEmpty())
                        sb.append("subdomain = \"").append(tml(sd)).append("\"\n");
                }
                if (tp.equals("tcpmux"))
                    sb.append("multiplexer = \"httpconnect\"\n");
                if (tp.equals("http")) {
                    if (p.getHttpUser() != null && !p.getHttpUser().isEmpty())
                        sb.append("httpUser = \"").append(tml(p.getHttpUser())).append("\"\n");
                    if (p.getHttpPassword() != null && !p.getHttpPassword().isEmpty())
                        sb.append("httpPassword = \"").append(tml(p.getHttpPassword())).append("\"\n");
                    if (p.getHostHeaderRewrite() != null && !p.getHostHeaderRewrite().isEmpty())
                        sb.append("hostHeaderRewrite = \"").append(tml(p.getHostHeaderRewrite())).append("\"\n");
                    if (p.getLocations() != null && !p.getLocations().isEmpty()) {
                        if (p.getLocations().contains(","))
                            sb.append("locations = [\"").append(p.getLocations().replace(",", "\", \"")).append("\"]\n");
                        else
                            sb.append("locations = [\"").append(tml(p.getLocations())).append("\"]\n");
                    }
                }
                if (tp.equals("stcp") || tp.equals("sudp") || tp.equals("xtcp")) {
                    if (p.getSecretKey() != null && !p.getSecretKey().isEmpty())
                        sb.append("secretKey = \"").append(tml(p.getSecretKey())).append("\"\n");
                }
                if (p.isUseEncryption()) sb.append("transport.useEncryption = true\n");
                if (p.isUseCompression()) sb.append("transport.useCompression = true\n");
                if (p.getBandwidthLimit() != null && !p.getBandwidthLimit().isEmpty())
                    sb.append("transport.bandwidthLimit = \"").append(tml(p.getBandwidthLimit())).append("\"\n");
                if (p.isHealthCheck()) {
                    String hc = p.getHealthCheckType();
                    if (hc == null || hc.isEmpty()) hc = "tcp";
                    sb.append("healthCheck.type = \"").append(tml(hc)).append("\"\n");
                    sb.append("healthCheck.timeoutSeconds = 3\n");
                    sb.append("healthCheck.maxFailed = 3\n");
                    sb.append("healthCheck.intervalSeconds = 10\n");
                }
                sb.append("\n");
            }

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, sb.toString());
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "FRP Config - " + addr);
            startActivity(Intent.createChooser(shareIntent, "\u5bfc\u51fa\u914d\u7f6e"));
        });

        // Preset server config button
        btnPreset.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("选择预设服务器")
                    .setItems(new String[]{
                            "public.freefrp.org:8000 (免费公共)",
                            "us.afrp.net:7000 (美国节点)",
                            "frp.freefrp.net:7000 (免费frp)"
                    }, (dialog, which) -> {
                        if (which == 0) {
                            etServerAddr.setText("public.freefrp.org");
                            etServerPort.setText("8000");
                            etToken.setText("public.freefrp.com");
                        } else if (which == 1) {
                            etServerAddr.setText("us.afrp.net");
                            etServerPort.setText("7000");
                            etToken.setText("afrp.net");
                        } else {
                            etServerAddr.setText("frp.freefrp.net");
                            etServerPort.setText("7000");
                            etToken.setText("freefrp.net");
                        }
                        saveServerConfig();
                        Snackbar.make(requireView(), "已切换服务器配置", Snackbar.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // Restore state each time the fragment becomes visible
        restoreRunningState();

        if (getActivity() != null) {
            LogFragment logFrag = (LogFragment) getActivity()
                    .getSupportFragmentManager()
                    .findFragmentByTag("android:switcher:" + R.id.view_pager + ":1");
            if (logFrag != null) {
                FrpcService.setLogCallback(logFrag::appendLog);
            }
            FrpcService.setProxyStatusCallback((proxyName, status) -> {
                for (int i = 0; i < proxyList.size(); i++) {
                    if (proxyList.get(i).getName().equals(proxyName)) {
                        proxyList.get(i).setStatus(status);
                        proxyAdapter.notifyItemChanged(i);
                        saveProxies();
                        break;
                    }
                }
            });

            // Restore proxy statuses from persistent storage on resume
            for (ProxyItem p : proxyList) {
                int saved = configManager.loadProxyStatus(p.getName());
                if (saved != ProxyItem.STATUS_UNKNOWN && p.getStatus() == ProxyItem.STATUS_UNKNOWN) {
                    p.setStatus(saved);
                }
            }
            proxyAdapter.notifyDataSetChanged();
        }
    }

    private void showAddProxyDialog() {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_add_proxy, null);

        Spinner spType = dialogView.findViewById(R.id.dialog_sp_type);
        TextView tvTypeHint = dialogView.findViewById(R.id.dialog_tv_type_hint);
        TextInputEditText etName = dialogView.findViewById(R.id.dialog_et_name);
        TextInputEditText etLocalIP = dialogView.findViewById(R.id.dialog_et_local_ip);
        TextInputEditText etLocal = dialogView.findViewById(R.id.dialog_et_local_port);
        TextInputEditText etRemote = dialogView.findViewById(R.id.dialog_et_remote_port);
        TextInputEditText etDomains = dialogView.findViewById(R.id.dialog_et_domains);
        TextInputEditText etSubdomain = dialogView.findViewById(R.id.dialog_et_subdomain);
        TextInputEditText etSecret = dialogView.findViewById(R.id.dialog_et_secret);
        TextInputEditText etHttpUser = dialogView.findViewById(R.id.dialog_et_http_user);
        TextInputEditText etHttpPass = dialogView.findViewById(R.id.dialog_et_http_pass);
        TextInputEditText etHostRewrite = dialogView.findViewById(R.id.dialog_et_host_rewrite);
        TextInputEditText etLocations = dialogView.findViewById(R.id.dialog_et_locations);
        SwitchCompat swEncrypt = dialogView.findViewById(R.id.dialog_sw_encrypt);
        SwitchCompat swCompress = dialogView.findViewById(R.id.dialog_sw_compress);
        TextInputEditText etBandwidth = dialogView.findViewById(R.id.dialog_et_bandwidth);
        SwitchCompat swHealth = dialogView.findViewById(R.id.dialog_sw_health);
        TextInputEditText etHealthType = dialogView.findViewById(R.id.dialog_et_health_type);

        // Reference to all toggleable containers
        TextInputLayout tilRemote = dialogView.findViewById(R.id.dialog_til_remote_port);
        TextInputLayout tilDomains = dialogView.findViewById(R.id.dialog_til_domains);
        TextInputLayout tilSubdomain = dialogView.findViewById(R.id.dialog_til_subdomain);
        TextInputLayout tilSecret = dialogView.findViewById(R.id.dialog_til_secret);
        TextInputLayout tilHttpUser = dialogView.findViewById(R.id.dialog_til_http_user);
        TextInputLayout tilHttpPass = dialogView.findViewById(R.id.dialog_til_http_pass);
        TextInputLayout tilHostRewrite = dialogView.findViewById(R.id.dialog_til_host_rewrite);
        TextInputLayout tilLocations = dialogView.findViewById(R.id.dialog_til_locations);

        // Setup type dropdown
        String[] typeLabels = ProxyItem.getTypeLabels();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, typeLabels);
        spType.setAdapter(adapter);

        // Default to tcp
        spType.setSelection(0);
        tvTypeHint.setText(TYPE_HINTS[0]);
        updateFieldVisibility("tcp", tilRemote, tilDomains, tilSubdomain, tilSecret,
                tilHttpUser, tilHttpPass, tilHostRewrite, tilLocations);

        spType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                String type = typeLabels[pos];
                tvTypeHint.setText(TYPE_HINTS[pos]);
                updateFieldVisibility(type, tilRemote, tilDomains, tilSubdomain, tilSecret,
                        tilHttpUser, tilHttpPass, tilHostRewrite, tilLocations);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogView)
                .setPositiveButton("添加", (dialog, which) -> {
                    String name = etName.getText().toString().trim();
                    int typePos = spType.getSelectedItemPosition();
                    String type = typeLabels[typePos];
                    String localIP = etLocalIP.getText().toString().trim();
                    if (localIP.isEmpty()) localIP = "127.0.0.1";
                    String localStr = etLocal.getText().toString().trim();
                    String remoteStr = etRemote.getText().toString().trim();

                    if (name.isEmpty() || localStr.isEmpty()) {
                        Snackbar.make(requireView(), "请填写名称和本地端口", Snackbar.LENGTH_SHORT).show();
                        return;
                    }

                    try {
                        int localPort = Integer.parseInt(localStr);
                        int remotePort = 0;
                        if (!remoteStr.isEmpty()) {
                            remotePort = Integer.parseInt(remoteStr);
                        }

                        ProxyItem item = new ProxyItem(name, type, localPort, remotePort);
                        item.setLocalIP(localIP);

                        // Type-specific fields
                        String domains = etDomains.getText().toString().trim();
                        if (!domains.isEmpty()) item.setCustomDomains(domains);

                        String subdomain = etSubdomain.getText().toString().trim();
                        if (!subdomain.isEmpty()) item.setSubdomain(subdomain);

                        String secret = etSecret.getText().toString().trim();
                        if (!secret.isEmpty()) item.setSecretKey(secret);

                        // HTTP fields
                        String httpUser = etHttpUser.getText().toString().trim();
                        if (!httpUser.isEmpty()) item.setHttpUser(httpUser);
                        String httpPass = etHttpPass.getText().toString().trim();
                        if (!httpPass.isEmpty()) item.setHttpPassword(httpPass);
                        String hostRewrite = etHostRewrite.getText().toString().trim();
                        if (!hostRewrite.isEmpty()) item.setHostHeaderRewrite(hostRewrite);
                        String locs = etLocations.getText().toString().trim();
                        if (!locs.isEmpty()) item.setLocations(locs);

                        // Optional common fields
                        item.setUseEncryption(swEncrypt.isChecked());
                        item.setUseCompression(swCompress.isChecked());
                        String bw = etBandwidth.getText().toString().trim();
                        if (!bw.isEmpty()) item.setBandwidthLimit(bw);
                        item.setHealthCheck(swHealth.isChecked());
                        String ht = etHealthType.getText().toString().trim();
                        if (!ht.isEmpty()) item.setHealthCheckType(ht);

                        // Set multiplexer for tcpmux
                        if ("tcpmux".equals(type)) {
                            item.setMultiplexer("httpconnect");
                        }

                        proxyList.add(item);
                        proxyAdapter.notifyItemInserted(proxyList.size() - 1);
                        saveProxies();
                    } catch (NumberFormatException e) {
                        Snackbar.make(requireView(), "端口格式错误", Snackbar.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateFieldVisibility(String type,
                                         TextInputLayout tilRemote,
                                         TextInputLayout tilDomains,
                                         TextInputLayout tilSubdomain,
                                         TextInputLayout tilSecret,
                                         TextInputLayout tilHttpUser,
                                         TextInputLayout tilHttpPass,
                                         TextInputLayout tilHostRewrite,
                                         TextInputLayout tilLocations) {
        int vis = View.VISIBLE;
        int gone = View.GONE;
        boolean isTcpUdp = type.equals("tcp") || type.equals("udp");
        boolean isHttp = type.equals("http");
        boolean isHttps = type.equals("https");
        boolean isTcpmux = type.equals("tcpmux");
        boolean isSecret = type.equals("stcp") || type.equals("sudp") || type.equals("xtcp");

        tilRemote.setVisibility(isTcpUdp ? vis : gone);
        tilDomains.setVisibility((isHttp || isHttps || isTcpmux) ? vis : gone);
        tilSubdomain.setVisibility((isHttp || isHttps) ? vis : gone);
        tilSecret.setVisibility(isSecret ? vis : gone);
        tilHttpUser.setVisibility(isHttp ? vis : gone);
        tilHttpPass.setVisibility(isHttp ? vis : gone);
        tilHostRewrite.setVisibility(isHttp ? vis : gone);
        tilLocations.setVisibility(isHttp ? vis : gone);
    }

    private void showHistoryDialog() {
        if (FrpcService.isRunning()) {
            Snackbar.make(requireView(), "服务运行中，请先停止后再操作", Snackbar.LENGTH_SHORT).show();
            return;
        }
        List<ProxyItem> history = configManager.loadDeletedProxies();
        if (history.isEmpty()) {
            if (FrpcService.isRunning()) {
                Snackbar.make(requireView(), "服务运行中，请先停止", Snackbar.LENGTH_SHORT).show();
            } else {
                Snackbar.make(requireView(), "暂无历史代理", Snackbar.LENGTH_SHORT).show();
            }
            return;
        }

        LinearLayout listLayout = new LinearLayout(requireContext());
        listLayout.setOrientation(LinearLayout.VERTICAL);
        listLayout.setPadding(24, 8, 24, 8);

        for (int i = 0; i < history.size(); i++) {
            ProxyItem item = history.get(i);
            final int index = i;

            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 12, 0, 12);

            LinearLayout textCol = new LinearLayout(requireContext());
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            TextView tvName = new TextView(requireContext());
            tvName.setText(item.getName());
            tvName.setTextSize(15);
            tvName.setTextColor(0xFF1C1B1F);
            tvName.setTypeface(null, Typeface.BOLD);

            TextView tvDetail = new TextView(requireContext());
            tvDetail.setText(item.toDetail());
            tvDetail.setTextSize(13);
            tvDetail.setTextColor(0xFF49454F);

            textCol.addView(tvName);
            textCol.addView(tvDetail);

            Button btnLoad = new Button(requireContext());
            btnLoad.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, 80));
            btnLoad.setPadding(24, 12, 24, 12);
            btnLoad.setText("恢复");
            btnLoad.setTextSize(13);
            btnLoad.setOnClickListener(v -> {
                if (FrpcService.isRunning()) {
                    Snackbar.make(requireView(), "服务运行中，无法恢复代理", Snackbar.LENGTH_SHORT).show();
                    return;
                }
                ProxyItem restored = history.get(index);
                ProxyItem copy = new ProxyItem(restored.getName(), restored.getType(),
                        restored.getLocalPort(), restored.getRemotePort());
                copy.setLocalIP(restored.getLocalIP());
                copy.setCustomDomains(restored.getCustomDomains());
                copy.setSubdomain(restored.getSubdomain());
                copy.setSecretKey(restored.getSecretKey());
                copy.setHttpUser(restored.getHttpUser());
                copy.setHttpPassword(restored.getHttpPassword());
                copy.setHostHeaderRewrite(restored.getHostHeaderRewrite());
                copy.setLocations(restored.getLocations());
                copy.setUseEncryption(restored.isUseEncryption());
                copy.setUseCompression(restored.isUseCompression());
                copy.setBandwidthLimit(restored.getBandwidthLimit());
                copy.setHealthCheck(restored.isHealthCheck());
                copy.setHealthCheckType(restored.getHealthCheckType());
                if ("tcpmux".equals(restored.getType())) copy.setMultiplexer("httpconnect");

                proxyList.add(copy);
                proxyAdapter.notifyItemInserted(proxyList.size() - 1);
                saveProxies();
                configManager.deleteDeletedProxy(index);
                Snackbar.make(requireView(), "已恢复: " + copy.getName(), Snackbar.LENGTH_SHORT).show();
            });

            Button btnDel = new Button(requireContext());
            btnDel.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, 80));
            btnDel.setPadding(24, 12, 24, 12);
            btnDel.setText("删除");
            btnDel.setTextSize(13);
            btnDel.setTextColor(0xFFFF1744);
            btnDel.setOnClickListener(v -> {
                if (FrpcService.isRunning()) {
                    Snackbar.make(requireView(), "服务运行中，无法操作", Snackbar.LENGTH_SHORT).show();
                    return;
                }
                configManager.deleteDeletedProxy(index);
                Snackbar.make(requireView(), "已从历史移除: " + history.get(index).getName(), Snackbar.LENGTH_SHORT).show();
            });

            row.addView(textCol);
            row.addView(btnLoad);
            row.addView(btnDel);

            listLayout.addView(row);

            View divider = new View(requireContext());
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
            divider.setBackgroundColor(0xFFE0E0E0);
            listLayout.addView(divider);
        }

        ScrollView scrollView = new ScrollView(requireContext());
        scrollView.addView(listLayout);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("历史代理")
                .setView(scrollView)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showEditProxyDialog(int position) {
        if (position < 0 || position >= proxyList.size()) return;
        ProxyItem target = proxyList.get(position);

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_add_proxy, null);

        // Bind all controls
        Spinner spType = dialogView.findViewById(R.id.dialog_sp_type);
        TextView tvTypeHint = dialogView.findViewById(R.id.dialog_tv_type_hint);
        TextInputEditText etName = dialogView.findViewById(R.id.dialog_et_name);
        TextInputEditText etLocalIP = dialogView.findViewById(R.id.dialog_et_local_ip);
        TextInputEditText etLocal = dialogView.findViewById(R.id.dialog_et_local_port);
        TextInputEditText etRemote = dialogView.findViewById(R.id.dialog_et_remote_port);
        TextInputEditText etDomains = dialogView.findViewById(R.id.dialog_et_domains);
        TextInputEditText etSubdomain = dialogView.findViewById(R.id.dialog_et_subdomain);
        TextInputEditText etSecret = dialogView.findViewById(R.id.dialog_et_secret);
        TextInputEditText etHttpUser = dialogView.findViewById(R.id.dialog_et_http_user);
        TextInputEditText etHttpPass = dialogView.findViewById(R.id.dialog_et_http_pass);
        TextInputEditText etHostRewrite = dialogView.findViewById(R.id.dialog_et_host_rewrite);
        TextInputEditText etLocations = dialogView.findViewById(R.id.dialog_et_locations);
        SwitchCompat swEncrypt = dialogView.findViewById(R.id.dialog_sw_encrypt);
        SwitchCompat swCompress = dialogView.findViewById(R.id.dialog_sw_compress);
        TextInputEditText etBandwidth = dialogView.findViewById(R.id.dialog_et_bandwidth);
        SwitchCompat swHealth = dialogView.findViewById(R.id.dialog_sw_health);
        TextInputEditText etHealthType = dialogView.findViewById(R.id.dialog_et_health_type);

        TextInputLayout tilRemote = dialogView.findViewById(R.id.dialog_til_remote_port);
        TextInputLayout tilDomains = dialogView.findViewById(R.id.dialog_til_domains);
        TextInputLayout tilSubdomain = dialogView.findViewById(R.id.dialog_til_subdomain);
        TextInputLayout tilSecret = dialogView.findViewById(R.id.dialog_til_secret);
        TextInputLayout tilHttpUser = dialogView.findViewById(R.id.dialog_til_http_user);
        TextInputLayout tilHttpPass = dialogView.findViewById(R.id.dialog_til_http_pass);
        TextInputLayout tilHostRewrite = dialogView.findViewById(R.id.dialog_til_host_rewrite);
        TextInputLayout tilLocations = dialogView.findViewById(R.id.dialog_til_locations);

        // Setup type dropdown
        String[] typeLabels = ProxyItem.getTypeLabels();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, typeLabels);
        spType.setAdapter(adapter);

        // Find type index
        int typeIndex = 0;
        for (int i = 0; i < typeLabels.length; i++) {
            if (typeLabels[i].equals(target.getType())) {
                typeIndex = i;
                break;
            }
        }
        spType.setSelection(typeIndex);
        tvTypeHint.setText(TYPE_HINTS[typeIndex]);
        updateFieldVisibility(target.getType(), tilRemote, tilDomains, tilSubdomain, tilSecret,
                tilHttpUser, tilHttpPass, tilHostRewrite, tilLocations);

        spType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                String type = typeLabels[pos];
                tvTypeHint.setText(TYPE_HINTS[pos]);
                updateFieldVisibility(type, tilRemote, tilDomains, tilSubdomain, tilSecret,
                        tilHttpUser, tilHttpPass, tilHostRewrite, tilLocations);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Pre-fill values from target
        etName.setText(target.getName());
        etLocalIP.setText(target.getLocalIP());
        etLocal.setText(String.valueOf(target.getLocalPort()));
        if (target.getRemotePort() > 0) etRemote.setText(String.valueOf(target.getRemotePort()));
        if (target.getCustomDomains() != null) etDomains.setText(target.getCustomDomains());
        if (target.getSubdomain() != null) etSubdomain.setText(target.getSubdomain());
        if (target.getSecretKey() != null) etSecret.setText(target.getSecretKey());
        if (target.getHttpUser() != null) etHttpUser.setText(target.getHttpUser());
        if (target.getHttpPassword() != null) etHttpPass.setText(target.getHttpPassword());
        if (target.getHostHeaderRewrite() != null) etHostRewrite.setText(target.getHostHeaderRewrite());
        if (target.getLocations() != null) etLocations.setText(target.getLocations());
        swEncrypt.setChecked(target.isUseEncryption());
        swCompress.setChecked(target.isUseCompression());
        if (target.getBandwidthLimit() != null) etBandwidth.setText(target.getBandwidthLimit());
        swHealth.setChecked(target.isHealthCheck());
        if (target.getHealthCheckType() != null) etHealthType.setText(target.getHealthCheckType());

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("编辑代理")
                .setView(dialogView)
                .setPositiveButton("保存", (dialog, which) -> {
                    String name = etName.getText().toString().trim();
                    int typePos = spType.getSelectedItemPosition();
                    String type = typeLabels[typePos];
                    String localIP = etLocalIP.getText().toString().trim();
                    if (localIP.isEmpty()) localIP = "127.0.0.1";
                    String localStr = etLocal.getText().toString().trim();
                    String remoteStr = etRemote.getText().toString().trim();

                    try {
                        int localPort = Integer.parseInt(localStr);
                        int remotePort = remoteStr.isEmpty() ? 0 : Integer.parseInt(remoteStr);

                        target.setName(name);
                        target.setType(type);
                        target.setLocalIP(localIP);
                        target.setLocalPort(localPort);
                        target.setRemotePort(remotePort);
                        target.setCustomDomains(etDomains.getText().toString().trim());
                        target.setSubdomain(etSubdomain.getText().toString().trim());
                        target.setSecretKey(etSecret.getText().toString().trim());
                        target.setHttpUser(etHttpUser.getText().toString().trim());
                        target.setHttpPassword(etHttpPass.getText().toString().trim());
                        target.setHostHeaderRewrite(etHostRewrite.getText().toString().trim());
                        target.setLocations(etLocations.getText().toString().trim());
                        target.setUseEncryption(swEncrypt.isChecked());
                        target.setUseCompression(swCompress.isChecked());
                        target.setBandwidthLimit(etBandwidth.getText().toString().trim());
                        target.setHealthCheck(swHealth.isChecked());
                        target.setHealthCheckType(etHealthType.getText().toString().trim());
                        if ("tcpmux".equals(type)) target.setMultiplexer("httpconnect");

                        proxyAdapter.notifyItemChanged(position);
                        saveProxies();
                        Snackbar.make(requireView(), "已更新: " + name, Snackbar.LENGTH_SHORT).show();
                    } catch (NumberFormatException e) {
                        Snackbar.make(requireView(), "端口格式错误", Snackbar.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void saveServerConfig() {
        String addr = etServerAddr.getText().toString().trim();
        int port = Integer.parseInt(etServerPort.getText().toString().trim());
        String token = etToken.getText().toString().trim();
        configManager.saveServerConfig(addr, port, token);
    }

    private void saveProxies() {
        configManager.saveProxies(proxyList);
    }

    private void startFrpc() {
        String serverAddr = etServerAddr.getText().toString().trim();
        String serverPortStr = etServerPort.getText().toString().trim();
        String token = etToken.getText().toString().trim();

        // Save to server history
        int srvPort;
        try { srvPort = Integer.parseInt(serverPortStr); } catch (Exception e) { srvPort = 0; }
        configManager.saveServerHistory(serverAddr, srvPort, token);

        if (serverAddr.isEmpty() || serverPortStr.isEmpty()) {
            Snackbar.make(requireView(), "请填写服务器地址和端口", Snackbar.LENGTH_SHORT).show();
            return;
        }

        int serverPort;
        try {
            serverPort = Integer.parseInt(serverPortStr);
        } catch (NumberFormatException e) {
            Snackbar.make(requireView(), "端口格式错误", Snackbar.LENGTH_SHORT).show();
            return;
        }

        if (proxyList.isEmpty()) {
            Snackbar.make(requireView(), "请至少添加一个代理", Snackbar.LENGTH_SHORT).show();
            return;
        }

        saveServerConfig();
        saveProxies();

        Intent intent = new Intent(requireContext(), FrpcService.class);
        intent.putExtra("server_addr", serverAddr);
        intent.putExtra("server_port", serverPort);
        intent.putExtra("token", token);

        ArrayList<String> proxyLines = new ArrayList<>();
        for (ProxyItem p : proxyList) {
            proxyLines.add(p.toLine());
        }
        intent.putStringArrayListExtra("proxies", proxyLines);

        requireContext().startForegroundService(intent);
        configManager.setRunning(true);
        updateUiState(true);
    }

    private void stopFrpc() {
        requireContext().stopService(new Intent(requireContext(), FrpcService.class));
        configManager.setRunning(false);
        updateUiState(false);
    }

    private void restoreRunningState() {
        boolean shouldBeRunning = FrpcService.isRunning();
        if (!shouldBeRunning && configManager.isRunning()) {
            configManager.setRunning(false);
        }
        updateUiState(shouldBeRunning);
    }

    private void updateUiState(boolean running) {
        btnStart.setEnabled(!running);
        btnStop.setEnabled(running);
        btnAddProxy.setEnabled(!running);
        btnHistory.setEnabled(!running);
        btnPreset.setEnabled(!running);
        btnExport.setEnabled(!running);
        tvStatus.setText(running ? "运行中" : "已停止");
        tvStatus.setTextColor(running ? 0xFF00BCD4 : 0xFFC62828);
        // Pass running state to adapter so it disables edit/delete
        proxyAdapter.setRunning(running);
        // Pulse ring animation
        if (running) {
            prvFrpStatus.startPulse();
        } else {
            prvFrpStatus.stopPulse();
        }
    }

    private String tml(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
