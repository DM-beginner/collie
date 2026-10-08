package dev.dmbeginner.colliepocket;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Uses the same identity and one-time-code checks as Collie's web pairing card. */
final class PairingClient {
    static String claim(String server, String code, String label) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(server + "api/pair").openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Origin", server.substring(0, server.length() - 1));
        connection.setDoOutput(true);
        byte[] body = new JSONObject().put("code", code).put("label", label)
                .toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try {
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                if (status == 429) throw new Exception("尝试太频繁，请稍后重试");
                if (status >= 300 && status < 400) throw new Exception("服务器重定向了请求，请在连接设置中填写最终地址");
                throw new Exception("配对被拒绝：请检查配对码是否过期，并使用新的设备名称（HTTP " + status + "）");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 65536) throw new Exception("服务器返回内容过大");
                    bytes.write(buffer, 0, count);
                }
            }
            String token = new JSONObject(bytes.toString("UTF-8")).optString("token", "");
            if (token.isEmpty() || token.length() > 4096) throw new Exception("服务器未返回有效的配对凭据");
            return token;
        } finally { connection.disconnect(); }
    }
}
