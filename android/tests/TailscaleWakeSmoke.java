package dev.dmbeginner.colliepocket.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Exercises a real cross-app activity launch and broadcast using an isolated emulator fixture. */
public final class TailscaleWakeSmoke extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
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
            check(state.getInt("requests") == 2 && state.getBoolean("after_wake"), "Connection was not sent again after foreground initialization");
            long t = SystemClock.uptimeMillis();
            getUiAutomation().injectInputEvent(new KeyEvent(t,t,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,0), true);
            getUiAutomation().injectInputEvent(new KeyEvent(t,t,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK,0), true);
            Thread.sleep(7000);
            check(state().getInt("launches") == 1, "Returning to offline computer caused another Tailscale jump");
            result.putString("result", "PASS: cold receiver request; external Tailscale activity wake; delayed connection after initialization; cooldown prevents return loop; fixture has no VPN or credentials.");

        } catch (Throwable error) {
            result.putString("result", "FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage()); resultCode = 0;
        } finally {
            SharedPreferences.Editor e = prefs.edit(); if (existed) e.putLong("last_tailscale_wake", originalWake); else e.remove("last_tailscale_wake"); e.commit();
        }
        finish(resultCode, result);
    }
    private Bundle state() {
        return getTargetContext().getContentResolver().call(Uri.parse("content://dev.dmbeginner.collie.tests.tailscale-state"), "state", null, null);
    }
    private static void check(boolean c, String message) { if (!c) throw new AssertionError(message); }
}
