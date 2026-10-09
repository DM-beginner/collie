package dev.dmbeginner.colliepocket.tests;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import dev.dmbeginner.colliepocket.TailscaleConnector;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Tests connection lifecycle without toggling any real VPN or sending pane input. */
public final class TailscaleSmoke extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            testHosts();
            testReachability();
            testAlreadyReachable();
            testConnectAndRecover();
            testMissingReceiver();
            testStopBeforeRequest();
            testStopWhileWaiting();
            testTimeout();
            result.putString("result", "PASS: Tailscale addresses; read-only health probe; already-online skip; one request and recovery; missing receiver; foreground cancellation; bounded timeout without reconnect loops.");
            finish(-1, result);
        } catch (Throwable failure) {
            result.putString("result", "FAIL: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            finish(0, result);
        }
    }
    private void testHosts() {
        String[] yes = {"https://computer.tail-example.ts.net/", "http://100.64.0.1:8787/", "http://100.127.255.254/", "https://[fd7a:115c:a1e0::1]/"};
        String[] no = {"", "http://127.0.0.1/", "http://192.168.1.2/", "http://100.63.0.1/", "http://100.128.0.1/", "https://host.ts.net.evil.example/", "https://example.com/"};
        for (String s : yes) check(TailscaleConnector.isTailscaleAddress(s), "Tailscale host not recognized");
        for (String s : no) check(!TailscaleConnector.isTailscaleAddress(s), "Non-Tailscale host triggered VPN");
    }
    private void testReachability() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
            AtomicReference<String> request = new AtomicReference<>();
            Thread fixture = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder head = new StringBuilder(); String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) head.append(line).append("\n");
                    request.set(head.toString());
                    socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                } catch (Exception error) { request.set("fixture error"); }
            });
            fixture.start();
            Class<?> type = Class.forName("dev.dmbeginner.colliepocket.TailscaleConnector$AndroidBackend");
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            TailscaleConnector.Backend backend = (TailscaleConnector.Backend) constructor.newInstance(getTargetContext());
            check(backend.reachable("http://127.0.0.1:" + server.getLocalPort() + "/settings?pair=never-send-this#x"), "HTTP auth response misidentified as VPN outage");
            fixture.join(5000);
            String head = request.get();
            check(head != null && head.startsWith("GET /api/health HTTP/"), "Health probe has wrong path or method");
            check(!head.contains("never-send-this") && !head.toLowerCase().contains("authorization:"), "Health probe leaked pairing information");
        }
    }
    private static final class Backend implements TailscaleConnector.Backend {
        final AtomicInteger checks = new AtomicInteger(), requests = new AtomicInteger();
        volatile int readyAfter = Integer.MAX_VALUE;
        volatile boolean installed = true;
        volatile CountDownLatch entered, release;
        @Override public boolean reachable(String server) {
            int n = checks.incrementAndGet();
            if (entered != null) entered.countDown();
            if (release != null) { try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {} }
            return n >= readyAfter;
        }
        @Override public boolean requestConnection() { requests.incrementAndGet(); return installed; }
    }
    private static final class Listener implements TailscaleConnector.Listener {
        final CountDownLatch connecting = new CountDownLatch(1), done = new CountDownLatch(1);
        final AtomicInteger callbacks = new AtomicInteger();
        volatile boolean ready, reconnected; volatile String reason;
        @Override public void onConnecting() { callbacks.incrementAndGet(); connecting.countDown(); }
        @Override public void onReady(boolean r) { callbacks.incrementAndGet(); ready = true; reconnected = r; done.countDown(); }
        @Override public void onUnavailable(String r) { callbacks.incrementAndGet(); reason = r; done.countDown(); }
    }
    private TailscaleConnector start(Backend backend, Listener listener) {
        AtomicReference<TailscaleConnector> ref = new AtomicReference<>();
        runOnMainSync(() -> { TailscaleConnector c = new TailscaleConnector(backend); ref.set(c); c.start("http://test.tail-example.ts.net/", listener); });
        return ref.get();
    }
    private void testAlreadyReachable() throws Exception {
        Backend b = new Backend(); b.readyAfter = 1; Listener l = new Listener(); TailscaleConnector c = start(b, l);
        try { await(l.done, 5); check(l.ready && !l.reconnected && b.requests.get() == 0, "Already-connected VPN was requested again"); }
        finally { runOnMainSync(c::close); }
    }
    private void testConnectAndRecover() throws Exception {
        Backend b = new Backend(); b.readyAfter = 4; Listener l = new Listener(); TailscaleConnector c = start(b, l);
        try { await(l.connecting, 5); await(l.done, 8); check(l.ready && l.reconnected && b.requests.get() == 1, "Recovery did not complete with exactly one connection request"); }
        finally { runOnMainSync(c::close); }
    }
    private void testMissingReceiver() throws Exception {
        Backend b = new Backend(); b.installed = false; Listener l = new Listener(); TailscaleConnector c = start(b, l);
        try { await(l.done, 5); check(!l.ready && l.reason != null && l.connecting.getCount() == 1, "Missing Tailscale caused an endless waiting state"); }
        finally { runOnMainSync(c::close); }
    }
    private void testStopBeforeRequest() throws Exception {
        Backend b = new Backend(); b.entered = new CountDownLatch(1); b.release = new CountDownLatch(1);
        Listener l = new Listener(); TailscaleConnector c = start(b, l);
        try {
            await(b.entered, 5); runOnMainSync(c::stop); b.release.countDown();
            Thread.sleep(200); waitForIdleSync();
            check(b.requests.get() == 0 && l.callbacks.get() == 0, "Leaving foreground sent a late VPN request");
        } finally { b.release.countDown(); runOnMainSync(c::close); }
    }
    private void testStopWhileWaiting() throws Exception {
        Backend b = new Backend(); Listener l = new Listener(); TailscaleConnector c = start(b, l);
        try {
            await(l.connecting, 5); runOnMainSync(c::stop); b.readyAfter = 1;
            Thread.sleep(1300); waitForIdleSync();
            check(b.requests.get() == 1 && l.callbacks.get() == 1, "Background polling changed the page or kept reconnecting");
        } finally { runOnMainSync(c::close); }
    }
    private void testTimeout() throws Exception {
        Backend b = new Backend(); Listener l = new Listener(); TailscaleConnector c = start(b, l);
        try { await(l.done, 25); check(!l.ready && l.reason != null && b.requests.get() == 1, "Timeout retriggered VPN or reported false success"); }
        finally { runOnMainSync(c::close); }
    }
    private static void await(CountDownLatch latch, int seconds) throws Exception {
        check(latch.await(seconds, TimeUnit.SECONDS), "Timed out waiting for test callback");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
