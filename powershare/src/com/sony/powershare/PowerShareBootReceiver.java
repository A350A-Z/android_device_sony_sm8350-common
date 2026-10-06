package com.sony.powershare;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class PowerShareBootReceiver extends BroadcastReceiver {

    private static final String TAG = "PowerShareBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        Log.d(TAG, "Boot completed, starting PowerShare service");

        final Intent serviceIntent =
                new Intent(context, PowerShareService.class);
        serviceIntent.setAction(PowerShareService.ACTION_RELOAD);

        try {
            context.startService(serviceIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start PowerShare service", e);
        }
    }
}
