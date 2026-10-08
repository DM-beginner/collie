package dev.dmbeginner.colliepocket;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Validates an explicitly chosen Collie origin; no credentials or pairing code enter settings. */
public final class ServerAddress {
    private ServerAddress() {}

    public static String normalize(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty() || value.length() > 2048) {
            throw new IllegalArgumentException("请输入电脑的 Collie 地址");
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i)) || value.charAt(i) == '\\') {
                throw new IllegalArgumentException("地址包含无效字符");
            }
        }
        if (!value.contains("://")) value = "http://" + value;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if ((!scheme.equals("http") && !scheme.equals("https")) || host == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IllegalArgumentException("请使用不带账号或配对码的 http:// 或 https:// 地址");
            }
            if (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/")) {
                throw new IllegalArgumentException("请填写服务器首页地址，不要带 /settings 等页面路径");
            }
            host = host.toLowerCase(Locale.ROOT);
            if (scheme.equals("http") && !isPrivateHost(host)) {
                throw new IllegalArgumentException("HTTP 仅用于 Tailscale 或局域网；公网地址请使用 HTTPS");
            }
            return new URI(scheme, null, host, uri.getPort(), "/", null, null).toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("地址格式不正确，请检查主机名和端口");
        }
    }

    private static boolean isPrivateHost(String host) {
        if (host.endsWith(".ts.net") || host.equals("localhost") || host.equals("[::1]")) return true;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] ip = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                if (parts[i].isEmpty() || parts[i].length() > 3 || !parts[i].matches("[0-9]+")) return false;
                ip[i] = Integer.parseInt(parts[i]);
                if (ip[i] > 255) return false;
            }
        } catch (NumberFormatException e) { return false; }
        return ip[0] == 10 || ip[0] == 127 || (ip[0] == 192 && ip[1] == 168)
                || (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31)
                || (ip[0] == 100 && ip[1] >= 64 && ip[1] <= 127);
    }

    public static boolean sameOrigin(String configured, String destination) {
        try {
            URI source = new URI(configured), target = new URI(destination);
            if (source.getHost() == null || target.getHost() == null || target.getRawUserInfo() != null) return false;
            return source.getScheme().equalsIgnoreCase(target.getScheme())
                    && source.getHost().equalsIgnoreCase(target.getHost()) && port(source) == port(target);
        } catch (URISyntaxException | NullPointerException e) { return false; }
    }

    private static int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
    }
}
