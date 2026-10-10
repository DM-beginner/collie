package com.tailscale.ipn;
public final class IPNReceiver extends android.content.BroadcastReceiver {
    @Override public void onReceive(android.content.Context context, android.content.Intent intent) {
        new ColdStartFixture.IPNReceiver().onReceive(context, intent);
    }
}
