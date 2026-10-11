package dev.dmbeginner.colliepocket.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONObject;

/** Exercises a real cross-app activity launch and broadcast using an isolated emulator fixture. */
public final class TailscaleWakeSmoke extends Instrumentation {
    private String mode = "";
    @Override public void onCreate(Bundle args) {
        super.onCreate(args); if (args != null) mode = args.getString("mode", ""); start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        if (!mode.isEmpty()) {
            try { prepareOrRestore(); result.putString("result", "PASS: " + mode); finish(-1, result); }
            catch (Throwable error) { result.putString("result", "FAIL: " + error); finish(0, result); }
            return;
        }
        SharedPreferences prefs = getTargetContext().getSharedPreferences("connection", 0);
        boolean existed = prefs.contains("last_tailscale_wake"); long originalWake = prefs.getLong("last_tailscale_wake", 0);
        Activity activity = null;
        int resultCode = -1;
        try {
            Intent launch = new Intent().setClassName("dev.dmbeginner.colliepocket", "dev.dmbeginner.colliepocket.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(launch);
            Activity app = activity;
            Field server = app.getClass().getDeclaredField("server"); server.setAccessible(true);
            Method connect = app.getClass().getDeclaredMethod("connectTailscale"); connect.setAccessible(true);
            prefs.edit().putLong("last_tailscale_wake", 0).commit();
            runOnMainSync(() -> {
                try { server.set(app, "http://100.64.0.99:1/"); connect.invoke(app); }
                catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
            });
            long deadline = SystemClock.elapsedRealtime() + 20000;
            Bundle state;
            do { state = state(); if (state.getBoolean("after_wake")) break; Thread.sleep(100); }
            while (SystemClock.elapsedRealtime() < deadline);
            check(state.getInt("launches") == 1, "Tailscale was not woken exactly once");
            check(state.getInt("requests") >= 2 && state.getBoolean("after_wake"), "Connection was not sent again after foreground initialization");
            Field foreground = app.getClass().getDeclaredField("foreground"); foreground.setAccessible(true);
            boolean[] returned = {false};
            deadline = SystemClock.elapsedRealtime() + 5000;
            do {
                runOnMainSync(() -> {
                    try { returned[0] = foreground.getBoolean(app); }
                    catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
                });
                if (returned[0] && !state().getBoolean("visible")) break;
                Thread.sleep(100);
            } while (SystemClock.elapsedRealtime() < deadline);
            check(returned[0] && !state().getBoolean("visible"), "Pocket did not return automatically with singleTask Tailscale left in background");
            Thread.sleep(7000);
            check(state().getInt("launches") == 1, "Returning to offline computer caused another Tailscale jump");
            Method manual = app.getClass().getDeclaredMethod("openTailscale"); manual.setAccessible(true);
            runOnMainSync(() -> {
                try { manual.invoke(app); }
                catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
            });
            Thread.sleep(4500);
            check(state().getBoolean("visible"), "Manual Tailscale settings visit was interrupted by automatic return");
            result.putString("result", "PASS: cold receiver request; singleTask Tailscale wake; delayed connect and automatic return without Back; cooldown prevents loop; manual visit stays visible; fixture has no VPN or credentials.");

        } catch (Throwable error) {
            result.putString("result", "FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage()); resultCode = 0;
        } finally {
            SharedPreferences.Editor e = prefs.edit(); if (existed) e.putLong("last_tailscale_wake", originalWake); else e.remove("last_tailscale_wake"); e.commit();
        }
        finish(resultCode, result);
    }
    private void prepareOrRestore() throws Exception {
        SharedPreferences prefs = getTargetContext().getSharedPreferences("connection", 0);
        SharedPreferences backup = getTargetContext().getSharedPreferences("wake_smoke_backup", 0);
        String[] keys = {"computers", "active_computer", "server", "auto_tailscale", "last_tailscale_wake"};
        if ("prepare".equals(mode)) {
            check(!backup.contains("values"), "Previous standalone wake test needs restoration first");
            JSONObject values = new JSONObject();
            for (String key : keys) if (prefs.contains(key)) values.put(key, prefs.getAll().get(key));
            check(backup.edit().putString("values", values.toString()).commit(), "Unable to save original emulator settings");
            check(prefs.edit().putString("computers", "[{\"id\":\"wake-test\",\"name\":\"Wake Test\",\"address\":\"http://100.64.0.99:1/\"}]")
                    .putString("active_computer", "wake-test").putString("server", "http://100.64.0.99:1/")
                    .putBoolean("auto_tailscale", true).putLong("last_tailscale_wake", System.currentTimeMillis()).commit(), "Unable to prepare recent-wake test");
        } else if ("restore".equals(mode)) {
            if (!backup.contains("values")) return;
            JSONObject values = new JSONObject(backup.getString("values", "{}"));
            SharedPreferences.Editor e = prefs.edit();
            for (String key : keys) {
                if (!values.has(key)) e.remove(key);
                else {
                    Object v = values.get(key);
                    if (v instanceof Boolean) e.putBoolean(key, (Boolean)v);
                    else if (v instanceof Number) e.putLong(key, ((Number)v).longValue());
                    else e.putString(key, (String)v);
                }
            }
            check(e.commit(), "Unable to restore emulator settings"); backup.edit().clear().commit();
        } else throw new AssertionError("Unknown test mode");
    }
    private Bundle state() {
        return getTargetContext().getContentResolver().call(Uri.parse("content://dev.dmbeginner.collie.tests.tailscale-state"), "state", null, null);
    }
    private static void check(boolean c, String message) { if (!c) throw new AssertionError(message); }
}
