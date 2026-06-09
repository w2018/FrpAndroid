package top.zw.frpc;

import java.util.ArrayList;
import java.util.List;

public class ProxyItem {
    public static final int STATUS_UNKNOWN = 0;
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_FAIL = 2;

    private String name;
    private String type;       // tcp, udp, http, https, tcpmux, stcp, sudp, xtcp
    private int localPort;
    private int remotePort;    // tcp/udp only
    private int status;
    private String localIP;    // default "127.0.0.1"

    // HTTP/HTTPS/tcpmux
    private String customDomains; // comma-separated
    private String subdomain;

    // HTTP only
    private String httpUser;
    private String httpPassword;
    private String hostHeaderRewrite;
    private String locations;  // comma-separated

    // stcp/sudp/xtcp/tcpmux
    private String secretKey;
    private String multiplexer; // "httpconnect" for tcpmux

    // Common optional
    private boolean useEncryption;
    private boolean useCompression;
    private String bandwidthLimit;
    private boolean healthCheck;
    private String healthCheckType; // "tcp" or "http"

    // Display helpers
    private static final String[] TYPE_LABELS = {"tcp", "udp", "http", "https", "tcpmux", "stcp", "sudp", "xtcp"};

    public static String[] getTypeLabels() { return TYPE_LABELS; }

    public ProxyItem() {
        this("", "tcp", 0, 0);
    }

    public ProxyItem(String name, String type, int localPort, int remotePort) {
        this.name = name;
        this.type = type;
        this.localPort = localPort;
        this.remotePort = remotePort;
        this.status = STATUS_UNKNOWN;
        this.localIP = "127.0.0.1";
    }

    // Getters and Setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public int getLocalPort() { return localPort; }
    public void setLocalPort(int localPort) { this.localPort = localPort; }

    public int getRemotePort() { return remotePort; }
    public void setRemotePort(int remotePort) { this.remotePort = remotePort; }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public String getLocalIP() { return localIP; }
    public void setLocalIP(String localIP) { this.localIP = localIP; }

    public String getCustomDomains() { return customDomains; }
    public void setCustomDomains(String customDomains) { this.customDomains = customDomains; }

    public String getSubdomain() { return subdomain; }
    public void setSubdomain(String subdomain) { this.subdomain = subdomain; }

    public String getHttpUser() { return httpUser; }
    public void setHttpUser(String httpUser) { this.httpUser = httpUser; }

    public String getHttpPassword() { return httpPassword; }
    public void setHttpPassword(String httpPassword) { this.httpPassword = httpPassword; }

    public String getHostHeaderRewrite() { return hostHeaderRewrite; }
    public void setHostHeaderRewrite(String hostHeaderRewrite) { this.hostHeaderRewrite = hostHeaderRewrite; }

    public String getLocations() { return locations; }
    public void setLocations(String locations) { this.locations = locations; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public String getMultiplexer() { return multiplexer; }
    public void setMultiplexer(String multiplexer) { this.multiplexer = multiplexer; }

    public boolean isUseEncryption() { return useEncryption; }
    public void setUseEncryption(boolean useEncryption) { this.useEncryption = useEncryption; }

    public boolean isUseCompression() { return useCompression; }
    public void setUseCompression(boolean useCompression) { this.useCompression = useCompression; }

    public String getBandwidthLimit() { return bandwidthLimit; }
    public void setBandwidthLimit(String bandwidthLimit) { this.bandwidthLimit = bandwidthLimit; }

    public boolean isHealthCheck() { return healthCheck; }
    public void setHealthCheck(boolean healthCheck) { this.healthCheck = healthCheck; }

    public String getHealthCheckType() { return healthCheckType; }
    public void setHealthCheckType(String healthCheckType) { this.healthCheckType = healthCheckType; }

    // --- UI helpers ---

    public String getStatusText() {
        if (status == STATUS_SUCCESS) return "\u2713 成功";
        if (status == STATUS_FAIL) return "\u2717 失败";
        return "- 未知";
    }

    public int getStatusColor() {
        if (status == STATUS_SUCCESS) return 0xFF2E7D32;
        if (status == STATUS_FAIL) return 0xFFC62828;
        return 0xFF9E9E9E;
    }

    public String toDetail() {
        StringBuilder sb = new StringBuilder();
        sb.append(type).append("  ").append(localPort);
        if (type.equals("tcp") || type.equals("udp")) {
            sb.append("\u2192").append(remotePort);
        }
        if (type.equals("http")) {
            if (customDomains != null && !customDomains.isEmpty()) {
                String first = customDomains.split(",")[0].trim();
                sb.append("  http://").append(first);
            } else if (subdomain != null && !subdomain.isEmpty()) {
                sb.append("  http://").append(subdomain).append(".<domain>");
            }
        } else if (type.equals("https")) {
            if (customDomains != null && !customDomains.isEmpty()) {
                String first = customDomains.split(",")[0].trim();
                sb.append("  https://").append(first);
            } else if (subdomain != null && !subdomain.isEmpty()) {
                sb.append("  https://").append(subdomain).append(".<domain>");
            }
        } else if (type.equals("tcpmux")) {
            if (customDomains != null && !customDomains.isEmpty()) {
                sb.append("  ").append(customDomains).append(":mux");
            }
        } else if (type.equals("stcp") || type.equals("sudp") || type.equals("xtcp")) {
            if (secretKey != null && !secretKey.isEmpty()) {
                sb.append("  [加密]");
            }
        }
        return sb.toString();
    }

    // --- Serialization ---

    public static ProxyItem fromLine(String line) {
        // Format: name,type,localPort,remotePort,status,localIP,customDomains,subdomain,
        //         httpUser,httpPassword,hostHeaderRewrite,locations,secretKey,multiplexer,
        //         useEncryption,useCompression,bandwidthLimit,healthCheck,healthCheckType
        String[] parts = line.split(",", 22); // max split to keep values intact
        if (parts.length < 4) return null;

        try {
            ProxyItem item = new ProxyItem(
                parts[0].trim(),
                parts[1].trim(),
                Integer.parseInt(parts[2].trim()),
                Integer.parseInt(parts[3].trim())
            );
            if (parts.length > 4 && !parts[4].isEmpty()) item.status = Integer.parseInt(parts[4].trim());
            if (parts.length > 5) item.localIP = parts[5].trim();
            if (parts.length > 6) item.customDomains = parts[6].trim();
            if (parts.length > 7) item.subdomain = parts[7].trim();
            if (parts.length > 8) item.httpUser = parts[8].trim();
            if (parts.length > 9) item.httpPassword = parts[9].trim();
            if (parts.length > 10) item.hostHeaderRewrite = parts[10].trim();
            if (parts.length > 11) item.locations = parts[11].trim();
            if (parts.length > 12) item.secretKey = parts[12].trim();
            if (parts.length > 13) item.multiplexer = parts[13].trim();
            if (parts.length > 14) item.useEncryption = "1".equals(parts[14].trim());
            if (parts.length > 15) item.useCompression = "1".equals(parts[15].trim());
            if (parts.length > 16) item.bandwidthLimit = parts[16].trim();
            if (parts.length > 17) item.healthCheck = "1".equals(parts[17].trim());
            if (parts.length > 18) item.healthCheckType = parts[18].trim();
            return item;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String toLine() {
        // Store every field with default values to maintain position
        List<String> fields = new ArrayList<>();
        fields.add(name);
        fields.add(type);
        fields.add(String.valueOf(localPort));
        fields.add(String.valueOf(remotePort));
        fields.add(String.valueOf(status));
        fields.add(localIP != null ? localIP : "");
        fields.add(customDomains != null ? customDomains : "");
        fields.add(subdomain != null ? subdomain : "");
        fields.add(httpUser != null ? httpUser : "");
        fields.add(httpPassword != null ? httpPassword : "");
        fields.add(hostHeaderRewrite != null ? hostHeaderRewrite : "");
        fields.add(locations != null ? locations : "");
        fields.add(secretKey != null ? secretKey : "");
        fields.add(multiplexer != null ? multiplexer : "");
        fields.add(useEncryption ? "1" : "0");
        fields.add(useCompression ? "1" : "0");
        fields.add(bandwidthLimit != null ? bandwidthLimit : "");
        fields.add(healthCheck ? "1" : "0");
        fields.add(healthCheckType != null ? healthCheckType : "");
        return String.join(",", fields);
    }
}
