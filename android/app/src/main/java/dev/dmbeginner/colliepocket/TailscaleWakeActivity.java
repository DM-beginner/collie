package dev.dmbeginner.colliepocket;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Private, short-lived handoff. A fresh activity keeps the return within Android's grace period. */
public final class TailscaleWakeActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean launched;
    private long deadline;
    private final Runnable connectAndReturn = () -> {
        if (isFinishing() || isDestroyed()) return;
        new TailscaleConnector.AndroidBackend(this).requestConnection();
        try {
            startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_TAILSCALE_RETURN, true).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (ActivityNotFoundException | SecurityException denied) {
            // OEM restrictions may still require pressing Back; never retry a background launch.
        }
        finish();
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.rgb(16, 24, 23)));
        if (state != null) {
            launched = state.getBoolean("launched"); deadline = state.getLong("deadline");
            if (launched) handler.postDelayed(connectAndReturn, Math.max(0, deadline - SystemClock.elapsedRealtime()));
        }
    }
    @Override public void onResume() {
        super.onResume();
        if (launched) { handler.removeCallbacks(connectAndReturn); finish(); return; }
        Intent launch = getPackageManager().getLaunchIntentForPackage(TailscaleConnector.PACKAGE);
        if (launch == null) { finish(); return; }
        launched = true;
        try {
            startActivity(launch);
            deadline = SystemClock.elapsedRealtime() + 3000;
            handler.postDelayed(connectAndReturn, 3000);
        } catch (ActivityNotFoundException | SecurityException denied) { finish(); }
    }
    @Override public void onSaveInstanceState(Bundle state) {
        state.putBoolean("launched", launched); state.putLong("deadline", deadline); super.onSaveInstanceState(state);
    }
    @Override public void onDestroy() { handler.removeCallbacks(connectAndReturn); super.onDestroy(); }
}
