package com.tailscale.ipn;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

/** Emulator-only fixture; does not create a VPN or access a real Tailscale account. */
public final class ColdStartFixture extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences p = getSharedPreferences("probe", 0);
        p.edit().putBoolean("awake", true).putInt("launches", p.getInt("launches", 0) + 1).commit();
        TextView text = new TextView(this); text.setText("Tailscale cold-start fixture"); setContentView(text);
    }
    public static final class IPNReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            SharedPreferences p = context.getSharedPreferences("probe", 0);
            int requests = p.getInt("requests", 0) + 1;
            p.edit().putInt("requests", requests).putBoolean("after_wake", p.getBoolean("after_wake", false) || p.getBoolean("awake", false)).commit();
        }
    }
    public static final class Probe extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            SharedPreferences p = getContext().getSharedPreferences("probe", 0);
            Bundle b = new Bundle();
            b.putInt("launches", p.getInt("launches", 0)); b.putInt("requests", p.getInt("requests", 0));
            b.putBoolean("after_wake", p.getBoolean("after_wake", false)); return b;
        }
        @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
        @Override public String getType(Uri u) { return null; }
        @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    }
}
