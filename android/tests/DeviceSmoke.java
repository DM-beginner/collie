package dev.dmbeginner.colliepocket.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.graphics.Rect;
import android.webkit.WebView;
import android.widget.TextView;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import dev.dmbeginner.colliepocket.ComputerProfiles;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/** Opt-in test against an already paired emulator. Never targets a real pane. */
public final class DeviceSmoke extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            testProfiles();
            Intent launch = new Intent().setClassName("dev.dmbeginner.colliepocket", "dev.dmbeginner.colliepocket.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity activity = startActivitySync(launch);
            AtomicReference<WebView> reference = new AtomicReference<>();
            runOnMainSync(() -> reference.set(findWeb(activity.getWindow().getDecorView())));
            WebView web = reference.get();
            if (web == null) throw new AssertionError("WebView missing");
            long deadline = System.currentTimeMillis() + 30000;
            while (!"true".equals(evaluate(web, "!!(location.protocol.indexOf('http')===0 && document.readyState==='complete')"))) {
                if (System.currentTimeMillis() > deadline) throw new AssertionError("Page load timeout");
                Thread.sleep(250);
            }
            // The synthetic ID cannot name a herdr pane. No real agent receives input.
            evaluate(web, "window.__colliePocketSmoke=null;(async function(){try{"
                    + "var t=localStorage.getItem('collie:device-token');"
                    + "var p='/api/pane/__collie_android_smoke_nonexistent__/keys';"
                    + "var body=JSON.stringify({keys:['Escape']});"
                    + "var a=await fetch(p,{method:'POST',headers:{'Content-Type':'application/json'},body:body});"
                    + "var b=await fetch(p,{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+t},body:body});"
                    + "var text=await b.text();window.__colliePocketSmoke={hasToken:!!t,plain:a.status,paired:b.status,missingPane:text.indexOf('pane_not_found')>=0};"
                    + "}catch(e){window.__colliePocketSmoke={error:'network failed'};}})();true;");
            JSONObject facts = null;
            deadline = System.currentTimeMillis() + 30000;
            while (System.currentTimeMillis() < deadline) {
                String value = evaluate(web, "JSON.stringify(window.__colliePocketSmoke)");
                Object decoded = new JSONTokener(value).nextValue();
                if (decoded instanceof String && !"null".equals(decoded)) {
                    facts = new JSONObject((String) decoded); break;
                }
                Thread.sleep(250);
            }
            if (facts == null || !facts.optBoolean("hasToken") || facts.optInt("plain") != 403
                    || facts.optInt("paired") != 200 || !facts.optBoolean("missingPane")) {
                throw new AssertionError("Pairing gate failed: " + facts);
            }
            runOnMainSync(() -> {
                View decor = activity.getWindow().getDecorView();
                if (findSettings(decor) != null) throw new AssertionError("Native home connection footer remains");
                if (hasTitle(decor)) throw new AssertionError("Persistent app toolbar remains");
            });
            testSettingsEntry(activity, web);
            // Agent rows are accessible buttons, rather than links; wait for the router's first render.
            String paneButton = "Array.from(document.querySelectorAll('button')).find(function(b){return b.querySelector('[aria-label$=\" logo\"]');})";
            waitFor(web, "!!(" + paneButton + ")");
            String navigation = evaluate(web, "(function(){var b=" + paneButton + ";if(!b)return false;b.click();return true;})()");
            if (!"true".equals(navigation)) throw new AssertionError("No session button on home");
            waitFor(web, "location.pathname.indexOf('/pane/')===0");
            runOnMainSync(() -> {
                if (findSettings(activity.getWindow().getDecorView()) != null) throw new AssertionError("Connection controls occupy session space");
                View root = activity.findViewById(android.R.id.content);
                int[] rootPosition = new int[2], webPosition = new int[2];
                root.getLocationOnScreen(rootPosition); web.getLocationOnScreen(webPosition);
                int safeTop = ((ViewGroup)root).getChildAt(0).getPaddingTop();
                if (webPosition[1] != rootPosition[1] + safeTop) throw new AssertionError("A top toolbar gap remains");
                activity.onBackPressed();
            });
            waitFor(web, "location.pathname==='/'");
            testSwitching(activity);
            result.putString("result", "PASS: Settings gear exposes computer controls; native entry requires a user tap; original settings and persistent Tailscale toggle preserved; home footer removed; profile persistence and origin isolation; original pairing retained; auth gate enforced; session uses full height.");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("result", "FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void testSettingsEntry(Activity activity, WebView web) throws Exception {
        String gear = "Array.from(document.querySelectorAll('header button')).find(function(b){return ['Settings','设置'].indexOf(b.getAttribute('aria-label'))>=0;})";
        waitFor(web, "!!(" + gear + ")");
        evaluate(web, "(" + gear + ").click();true");
        waitFor(web, "location.pathname==='/settings'");
        String entry = "document.querySelector('a[role=\"button\"][aria-label=\"电脑连接\"]')";
        waitFor(web, "!!(" + entry + ")");
        if (!"true".equals(evaluate(web, "document.querySelectorAll('main button').length>=4")))
            throw new AssertionError("Original Settings sections disappeared");
        evaluate(web, "(" + entry + ").click();true");
        waitForIdleSync();
        if (accessibleText("切换 / 管理电脑") != null) throw new AssertionError("Scripted navigation opened native controls without a user gesture");
        tapWeb(web, entry);
        waitForText("切换 / 管理电脑");
        testTailscaleSetting(activity, web, entry);
        tapText("切换 / 管理电脑");
        waitForText("我的电脑");
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitForIdleSync();
        runOnMainSync(activity::onBackPressed);
        waitFor(web, "location.pathname==='/'");
        waitFor(web, "!(" + entry + ")");
        // The root holds only the page, with no second footer below the WebView.
        runOnMainSync(() -> {
            ViewGroup root = (ViewGroup)((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);
            if (root.getChildCount() != 1) throw new AssertionError("Extra native home UI consumes page height");
        });
    }
    private void testTailscaleSetting(Activity activity, WebView web, String entry) throws Exception {
        SharedPreferences prefs = getTargetContext().getSharedPreferences("connection", 0);
        boolean existed = prefs.contains("auto_tailscale"), original = prefs.getBoolean("auto_tailscale", true);
        try {
            tapText("Tailscale 自动连接");
            waitForText("打开 App 时自动连接 Tailscale");
            AccessibilityNodeInfo toggle = accessibleText("打开 App 时自动连接 Tailscale");
            if (toggle == null || !toggle.isCheckable() || toggle.isChecked() != original)
                throw new AssertionError("Automatic connection setting differs from saved preference");
            tapText("打开 App 时自动连接 Tailscale");
            if (prefs.getBoolean("auto_tailscale", original) == original) throw new AssertionError("Automatic connection toggle not saved");
            tapText("关闭");
            tapWeb(web, entry); waitForText("Tailscale 自动连接"); tapText("Tailscale 自动连接");
            waitForText("打开 App 时自动连接 Tailscale");
            toggle = accessibleText("打开 App 时自动连接 Tailscale");
            if (toggle == null || !toggle.isCheckable() || toggle.isChecked() == original)
                throw new AssertionError("Automatic connection setting lost on reopening");
            tapText("打开 App 时自动连接 Tailscale");
            tapText("关闭");
            tapWeb(web, entry); waitForText("切换 / 管理电脑");
        } finally {
            SharedPreferences.Editor edit = prefs.edit();
            if (existed) edit.putBoolean("auto_tailscale", original); else edit.remove("auto_tailscale");
            edit.commit();
        }
    }
    private void tapWeb(WebView web, String element) throws Exception {
        String value = evaluate(web, "(function(){var e=" + element + ";e.scrollIntoView({block:'center'});var r=e.getBoundingClientRect();return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2,scale:devicePixelRatio});})()");
        JSONObject point = new JSONObject((String)new JSONTokener(value).nextValue());
        int[] position = new int[2];
        runOnMainSync(() -> web.getLocationOnScreen(position));
        tap((float)(position[0] + point.getDouble("x")*point.getDouble("scale")),
                (float)(position[1] + point.getDouble("y")*point.getDouble("scale")));
    }
    private void tap(float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now+50, MotionEvent.ACTION_UP, x, y, 0);
        sendPointerSync(down); sendPointerSync(up);
        down.recycle(); up.recycle();
        waitForIdleSync();
    }
    private AccessibilityNodeInfo accessibleText(String text) {
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        if (root == null) return null;
        java.util.List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        return nodes.isEmpty() ? null : nodes.get(0);
    }
    private void waitForText(String text) throws Exception {
        long deadline = System.currentTimeMillis()+10000;
        while (accessibleText(text) == null) {
            if (System.currentTimeMillis()>deadline) throw new AssertionError("Missing control: " + text);
            Thread.sleep(100);
        }
    }
    private void tapText(String text) {
        AccessibilityNodeInfo node = accessibleText(text);
        if (node == null) throw new AssertionError("Missing control: " + text);
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        tap(bounds.exactCenterX(), bounds.exactCenterY());
    }
    private void testProfiles() {
        SharedPreferences preferences = getTargetContext().getSharedPreferences("profiles-smoke", 0);
        preferences.edit().clear().putString("server", "http://127.0.0.1:8787/").commit();
        try {
            ComputerProfiles profiles = new ComputerProfiles(preferences, "");
            if (profiles.list().size() != 1 || !profiles.active().address.equals("http://127.0.0.1:8787/"))
                throw new AssertionError("Previous connection did not migrate");
            String original = profiles.active().id;
            ComputerProfiles.Computer mac = profiles.put(null, "Mac", "http://mac.tail123.ts.net:8787");
            profiles = new ComputerProfiles(preferences, "");
            if (profiles.list().size() != 2 || !profiles.active().id.equals(mac.id))
                throw new AssertionError("Profiles or active selection did not persist");
            try { profiles.put(null, "Duplicate", mac.address); throw new AssertionError("Duplicate accepted"); }
            catch (IllegalArgumentException expected) { }
            try { profiles.put(null, " ", "http://localhost:8000"); throw new AssertionError("Empty name accepted"); }
            catch (IllegalArgumentException expected) { }
            try { profiles.put(null, "Unsafe", "http://example.com"); throw new AssertionError("Public HTTP accepted"); }
            catch (IllegalArgumentException expected) { }
            profiles.put(mac.id, "Mac Studio", mac.address);
            profiles.select(original);
            if (!new ComputerProfiles(preferences, "").active().id.equals(original))
                throw new AssertionError("Selected computer did not persist");
            profiles.remove(original);
            if (!profiles.active().name.equals("Mac Studio")) throw new AssertionError("Removal fallback failed");
            profiles.remove(mac.id);
            if (new ComputerProfiles(preferences, "http://localhost:8787").active() != null)
                throw new AssertionError("Deleted profile resurrected from default");
            String defaults = "[{\"name\":\"MacBook Pro\",\"address\":\"https://mac.tail123.ts.net/\"}]";
            profiles = new ComputerProfiles(preferences, "", defaults);
            if (!profiles.active().name.equals("MacBook Pro")) throw new AssertionError("Named build defaults missing");
            profiles.remove(profiles.active().id);
            if (new ComputerProfiles(preferences, "", defaults).active() != null)
                throw new AssertionError("Removed build default resurrected");
        } finally { preferences.edit().clear().commit(); }
    }
    private void testSwitching(Activity activity) throws Exception {
        java.lang.reflect.Field field = activity.getClass().getDeclaredField("computers");
        field.setAccessible(true);
        ComputerProfiles profiles = (ComputerProfiles) field.get(activity);
        String original = profiles.active().id;
        java.lang.reflect.Method activate = activity.getClass().getDeclaredMethod("activateComputer");
        activate.setAccessible(true);
        AtomicReference<String> firstId = new AtomicReference<>(), secondId = new AtomicReference<>();
        try (Fixture first = new Fixture(); Fixture second = new Fixture()) {
            runOnMainSync(() -> {
                firstId.set(profiles.put(null, "Test Windows", first.address()).id);
                secondId.set(profiles.put(null, "Test Mac", second.address()).id);
                profiles.select(firstId.get());
                invoke(activate, activity);
            });
            WebView a = currentWeb(activity);
            waitFor(a, "document.title==='Profile fixture'");
            evaluate(a, "localStorage.setItem('collie:pocket-profile-smoke','Windows');true");
            runOnMainSync(() -> {
                profiles.select(secondId.get()); invoke(activate, activity);
            });
            WebView b = currentWeb(activity);
            waitFor(b, "document.title==='Profile fixture'");
            if (!"true".equals(evaluate(b, "localStorage.getItem('collie:pocket-profile-smoke')===null && localStorage.getItem('collie:device-token')===null")))
                throw new AssertionError("Storage crossed computer origins");
            evaluate(b, "localStorage.setItem('collie:pocket-profile-smoke','Mac');true");
            runOnMainSync(() -> {
                if (b.canGoBack()) throw new AssertionError("Previous computer remains in Back history");
                profiles.select(firstId.get()); invoke(activate, activity);
            });
            WebView again = currentWeb(activity);
            waitFor(again, "document.title==='Profile fixture'");
            if (!"true".equals(evaluate(again, "localStorage.getItem('collie:pocket-profile-smoke')==='Windows'")))
                throw new AssertionError("Original computer storage lost on switch");
            evaluate(again, "localStorage.removeItem('collie:pocket-profile-smoke');true");
        } finally {
            runOnMainSync(() -> {
                if (firstId.get() != null) profiles.remove(firstId.get());
                if (secondId.get() != null) profiles.remove(secondId.get());
                profiles.select(original); invoke(activate, activity);
            });
        }
        WebView returned = currentWeb(activity);
        waitFor(returned, "location.protocol.indexOf('http')===0 && document.readyState==='complete'");
        if (!"true".equals(evaluate(returned, "!!localStorage.getItem('collie:device-token')")))
            throw new AssertionError("Existing pairing lost when returning to original computer");
    }
    private void invoke(java.lang.reflect.Method method, Activity activity) {
        try { method.invoke(activity); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private WebView currentWeb(Activity activity) {
        AtomicReference<WebView> value = new AtomicReference<>();
        runOnMainSync(() -> value.set(findWeb(activity.getWindow().getDecorView())));
        return value.get();
    }
    private static final class Fixture implements AutoCloseable {
        final ServerSocket server;
        Fixture() throws Exception {
            server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
            Thread responder = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(2000);
                        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                        String line;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                        byte[] body = "<!doctype html><title>Profile fixture</title><p>Computer connection test</p>".getBytes(StandardCharsets.UTF_8);
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().write(body);
                    } catch (java.io.IOException ignored) { /* Socket closes at the end of the test. */ }
                }
            }, "Collie-profile-fixture");
            responder.setDaemon(true); responder.start();
        }
        String address() { return "http://127.0.0.1:" + server.getLocalPort() + "/"; }
        @Override public void close() throws java.io.IOException { server.close(); }
    }
    private String evaluate(WebView web, String javascript) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        runOnMainSync(() -> web.evaluateJavascript(javascript, value -> { result.set(value); done.countDown(); }));
        if (!done.await(5, TimeUnit.SECONDS)) throw new AssertionError("JavaScript evaluation timed out");
        return result.get();
    }
    private WebView findWeb(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) for (int i=0; i<((ViewGroup)view).getChildCount(); i++) {
            WebView found = findWeb(((ViewGroup)view).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
    private void waitFor(WebView web, String condition) throws Exception {
        long deadline = System.currentTimeMillis() + 15000;
        while (!"true".equals(evaluate(web, condition))) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("Navigation timed out: " + condition);
            Thread.sleep(100);
        }
        waitForIdleSync();
    }
    private View findSettings(View view) {
        if ("App 设置".contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i=0; i<((ViewGroup)view).getChildCount(); i++) {
            View found = findSettings(((ViewGroup)view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    private boolean hasTitle(View view) {
        if (view instanceof TextView && "Collie Pocket".contentEquals(((TextView)view).getText())) return true;
        if (view instanceof ViewGroup) for (int i=0; i<((ViewGroup)view).getChildCount(); i++) {
            if (hasTitle(((ViewGroup)view).getChildAt(i))) return true;
        }
        return false;
    }
}
