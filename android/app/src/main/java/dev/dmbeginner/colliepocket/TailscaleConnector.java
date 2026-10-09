package dev.dmbeginner.colliepocket;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** One connection request per foreground visit; bounded, read-only reachability checks. */
public final class TailscaleConnector {
    public static final String PACKAGE = "com.tailscale.ipn";
    public static final String CONNECT = "com.tailscale.ipn.CONNECT_VPN";
    public interface Backend {
        boolean reachable(String server);
        boolean requestConnection();
    }
    public interface Listener {
        void onConnecting();
        void onReady(boolean reconnected);
        void onUnavailable(String reason);
    }
    private final Backend backend;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService checks = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> pending;
    private volatile int generation;
    private boolean running;
    private static final long WAIT_MS = 20000;

    public TailscaleConnector(Context context) { this(new AndroidBackend(context)); }
    public TailscaleConnector(Backend backend) { this.backend = backend; }

    /** Caller starts only while visible and stops before leaving the foreground. */
    public void start(String server, Listener listener) {
        stop();
        running = true;
        int visit = generation;
        pending = checks.schedule(() -> {
            boolean reachable = backend.reachable(server);
            deliver(visit, () -> {
                if (reachable) { running = false; listener.onReady(false); return; }
                // Sending is on the foreground/main thread, never from a lingering worker.
                if (!backend.requestConnection()) {
                    running = false;
                    listener.onUnavailable("请先安装、登录 Tailscale，并允许它建立 VPN 连接。");
                    return;
                }
                listener.onConnecting();
                poll(visit, server, SystemClock.elapsedRealtime() + WAIT_MS, listener);
            });
        }, 0, TimeUnit.MILLISECONDS);
    }

    private void poll(int visit, String server, long deadline, Listener listener) {
        pending = checks.schedule(() -> {
            if (visit != generation) return;
            boolean reachable = backend.reachable(server);
            deliver(visit, () -> {
                if (reachable) { running = false; listener.onReady(true); }
                else if (SystemClock.elapsedRealtime() >= deadline) {
                    running = false;
                    listener.onUnavailable("自动连接后仍无法访问电脑。请打开 Tailscale 确认已连接；若系统要求 VPN 授权，请点允许，再返回重试。也请确认电脑开机、Collie 正在运行。");
                } else poll(visit, server, deadline, listener);
            });
        }, 1, TimeUnit.SECONDS);
    }

    private void deliver(int visit, Runnable action) {
        main.post(() -> { if (visit == generation) action.run(); });
    }
    public boolean isRunning() { return running; }
    public void stop() {
        generation++;
        running = false;
        if (pending != null) { pending.cancel(true); pending = null; }
    }
    public void close() { stop(); checks.shutdownNow(); }

    public static boolean isTailscaleAddress(String address) {
        String host = Uri.parse(address).getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".ts.net")) return true;
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.startsWith("fd7a:115c:a1e0:")) return true;
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        try {
            for (String part : parts) {
                if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) return false;
            }
            int second = Integer.parseInt(parts[1]);
            return Integer.parseInt(parts[0]) == 100 && second >= 64 && second <= 127;
        } catch (NumberFormatException invalid) { return false; }
    }

    static final class AndroidBackend implements Backend {
        private final Context context;
        AndroidBackend(Context context) { this.context = context; }
        @Override public boolean requestConnection() {
            ComponentName receiver = new ComponentName(PACKAGE, PACKAGE + ".IPNReceiver");
            try {
                android.content.pm.ActivityInfo info = context.getPackageManager().getReceiverInfo(receiver, 0);
                if (!info.enabled || !info.exported || !info.applicationInfo.enabled) return false;
                Intent request = new Intent(CONNECT).setComponent(receiver);
                // This allows a previously force-stopped Tailscale to receive the explicit request.
                request.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                context.sendBroadcast(request);
                return true; // A sent request is not proof that the VPN connected.
            } catch (PackageManager.NameNotFoundException | SecurityException error) { return false; }
        }
        @Override public boolean reachable(String server) {
            HttpURLConnection connection = null;
            try {
                String health = Uri.parse(server).buildUpon().encodedPath("/api/health")
                        .clearQuery().fragment(null).build().toString();
                connection = (HttpURLConnection) new URL(health).openConnection();
                connection.setConnectTimeout(1500);
                connection.setReadTimeout(1500);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestMethod("GET");
                // Any HTTP response proves transport is up. The WebView reports server/auth errors.
                int status = connection.getResponseCode();
                return status >= 200 && status <= 599;
            } catch (java.io.IOException error) { return false; }
            finally { if (connection != null) connection.disconnect(); }
        }
    }
}
