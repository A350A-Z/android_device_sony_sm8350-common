package com.sony.powershare;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UEventObserver;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

import vendor.lineage.powershare.IPowerShare;

public class PowerShareService extends Service {

    private static final String TAG = "PowerShareService";

    public static final String ACTION_RELOAD =
            "com.sony.powershare.action.RELOAD";

    private static final String ACTION_CONNECTION_TIMEOUT =
            "com.sony.powershare.action.CONNECTION_TIMEOUT";

    private static final String ACTION_TIME_LIMIT =
            "com.sony.powershare.action.TIME_LIMIT";

    private static final String PREFS_NAME =
            "powershare_settings";

    private static final String KEY_DISCONNECT_ENABLED =
            "powershare_disconnect_enabled";

    private static final String KEY_DISCONNECT_TIMEOUT =
            "powershare_disconnect_timeout";

    private static final String KEY_TIME_LIMIT =
            "powershare_time_limit";

    private static final String KEY_START_TIME =
            "powershare_start_time";

    private static final String DEVPATH =
            "/devices/virtual/wlc_switch/wireless_chg";

    private static final String STATUS_PATH =
            "/sys/devices/virtual/wlc_switch/wireless_chg/"
                    + "wireless_rvschg_status";

    private static final String RVSCHG_STATUS =
            "RVSCHG_STATUS";

    private static final String RVSCHG_REASON =
            "RVSCHG_REASON";

    private static final int STATUS_NOT_SHARING = 0;
    private static final int STATUS_SHARING = 1;

    private static final int REASON_UNKNOWN = 0;
    private static final int REASON_FULL_CHARGED = 1;
    private static final int REASON_DEVICE_TEMPERATURE = 2;
    private static final int REASON_USB_OTG = 3;
    private static final int REASON_ERROR = 100;

    private static final int REQUEST_CONNECTION_TIMEOUT = 1001;
    private static final int REQUEST_TIME_LIMIT = 1002;

    private final Handler mHandler =
            new Handler(Looper.getMainLooper());

    private AlarmManager mAlarmManager;
    private IPowerShare mPowerShare;

    /*
     * Actual kernel reverse-charging state.
     *
     * This is different from IPowerShare.isEnabled().
     */
    private boolean mIsSharing = false;

    private final UEventObserver mBatteryShareEventObserver =
            new UEventObserver() {
                @Override
                public void onUEvent(UEvent event) {
                    if (event == null) {
                        return;
                    }

                    final String devPath =
                            event.get("DEVPATH");

                    if (!DEVPATH.equals(devPath)) {
                        return;
                    }

                    final String status =
                            event.get(RVSCHG_STATUS);

                    final String reason =
                            event.get(RVSCHG_REASON);

                    Log.i(TAG,
                            "PowerShare uevent: "
                                    + event.toString());

                    mHandler.post(() ->
                            handleWirelessEvent(
                                    status,
                                    reason));
                }
            };

    private final BroadcastReceiver mBatteryReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(
                        Context context,
                        Intent intent) {

                    if (!Intent.ACTION_BATTERY_CHANGED.equals(
                            intent.getAction())) {
                        return;
                    }

                    if (!mIsSharing) {
                        return;
                    }

                    final int level =
                            intent.getIntExtra(
                                    BatteryManager.EXTRA_LEVEL,
                                    -1);

                    if (level < 0) {
                        return;
                    }

                    final int minBattery =
                            getMinBattery();

                    if (minBattery >= 0
                            && level <= minBattery) {

                        Log.i(TAG,
                                "Minimum battery reached: "
                                        + level + "% <= "
                                        + minBattery + "%");

                        disablePowerShare(
                                "minimum battery reached");
                    }
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();

        mAlarmManager =
                (AlarmManager) getSystemService(
                        Context.ALARM_SERVICE);

        mPowerShare = getPowerShare();

        final IntentFilter batteryFilter =
                new IntentFilter(
                        Intent.ACTION_BATTERY_CHANGED);

        registerReceiver(
                mBatteryReceiver,
                batteryFilter,
                Context.RECEIVER_NOT_EXPORTED);

        /*
         * Restore the actual hardware state before processing
         * configuration.
         */
        mIsSharing = readWirelessShareState();

        mBatteryShareEventObserver.startObserving(
                "DEVPATH=" + DEVPATH);

        reconcileState();

        Log.i(TAG,
                "PowerShareService started, sharing="
                        + mIsSharing);
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId) {

        final String action =
                intent != null
                        ? intent.getAction()
                        : null;

        if (ACTION_RELOAD.equals(action)) {

            Log.i(TAG,
                    "Received ACTION_RELOAD");

            reconcileState();

        } else if (ACTION_CONNECTION_TIMEOUT.equals(action)) {

            Log.i(TAG,
                    "Received ACTION_CONNECTION_TIMEOUT");

            handleConnectionTimeout();

        } else if (ACTION_TIME_LIMIT.equals(action)) {

            Log.i(TAG,
                    "Received ACTION_TIME_LIMIT");

            handleTimeLimit();

        } else {

            reconcileState();
        }

        return START_STICKY;
    }

    private IPowerShare getPowerShare() {
        try {
            final IBinder binder =
                    ServiceManager.getService(
                            IPowerShare.DESCRIPTOR
                                    + "/default");

            if (binder == null) {
                Log.w(TAG,
                        "PowerShare AIDL service unavailable");
                return null;
            }

            return IPowerShare.Stub.asInterface(binder);

        } catch (Exception e) {
            Log.e(TAG,
                    "Failed to get PowerShare AIDL service",
                    e);
            return null;
        }
    }

    private boolean isEnabled() {

        if (mPowerShare == null) {
            mPowerShare = getPowerShare();
        }

        if (mPowerShare == null) {
            return false;
        }

        try {
            return mPowerShare.isEnabled();

        } catch (Exception e) {
            Log.e(TAG,
                    "Failed to query PowerShare enabled state",
                    e);

            return false;
        }
    }

    /*
     * Read the current kernel reverse-charging state.
     *
     * The driver allocates somc_bcext_dev with devm_kzalloc(),
     * so wireless_rvschg_status starts at 0 and is changed to
     * 1/0 by WIRELESS_RVSCHG_START/END.
     */
    private boolean readWirelessShareState() {

        try (BufferedReader reader =
                     new BufferedReader(
                             new FileReader(STATUS_PATH))) {

            final String value =
                    reader.readLine();

            final int status =
                    parseInt(value, -1);

            if (status == STATUS_SHARING) {

                Log.i(TAG,
                        "Initial kernel state: sharing");

                return true;
            }

            if (status == STATUS_NOT_SHARING) {

                Log.i(TAG,
                        "Initial kernel state: not sharing");

                return false;
            }

            Log.w(TAG,
                    "Unknown kernel PowerShare state: "
                            + value);

        } catch (IOException e) {

            Log.w(TAG,
                    "Failed to read kernel PowerShare state",
                    e);
        }

        /*
         * The node is expected to exist by BOOT_COMPLETED.
         * If it is temporarily unavailable, do not pretend
         * that reverse charging is active.
         */
        return false;
    }

    private void reconcileState() {

        mPowerShare = getPowerShare();

        if (mPowerShare == null) {

            Log.w(TAG,
                    "Cannot reconcile state: "
                            + "AIDL unavailable");

            return;
        }

        final boolean enabled = isEnabled();

        if (!enabled) {

            Log.i(TAG,
                    "PowerShare is disabled");

            mIsSharing = false;

            cancelConnectionTimeout();
            cancelTimeLimit();
            clearStartTime();

            stopSelf();

            return;
        }

        if (mIsSharing) {

            /*
             * We are already actually sharing.
             *
             * Therefore there must not be a disconnect timeout.
             */
            cancelConnectionTimeout();

            /*
             * Recalculate the time limit so changes made in
             * Settings take effect immediately.
             */
            scheduleTimeLimit();

            Log.i(TAG,
                    "PowerShare enabled and currently sharing");

        } else {

            /*
             * No active receiver is currently detected.
             */
            cancelTimeLimit();
            clearStartTime();

            startDisconnectTimer();

            Log.i(TAG,
                    "PowerShare enabled; waiting for "
                            + "RVSCHG_STATUS=1");
        }
    }

    private void handleWirelessEvent(
            String status,
            String reason) {

        if (reason != null) {
            handleStopReason(reason);
        }

        if (status != null) {
            handleStatus(status);
        }
    }

    private void handleStatus(String status) {

        final int statusCode =
                parseInt(status, -1);

        switch (statusCode) {

            case STATUS_SHARING:

                if (!mIsSharing) {

                    mIsSharing = true;

                    Log.i(TAG,
                            "PowerShare sharing started");

                    cancelConnectionTimeout();

                    final long now =
                            SystemClock.elapsedRealtime();

                    final long startTime =
                            getStartTime();

                    /*
                     * If the stored value belongs to an
                     * earlier boot or is otherwise invalid,
                     * create a new session baseline.
                     */
                    if (startTime <= 0
                            || startTime > now) {

                        saveStartTime(now);
                    }
                }

                scheduleTimeLimit();

                break;

            case STATUS_NOT_SHARING:

                if (mIsSharing) {

                    mIsSharing = false;

                    Log.i(TAG,
                            "PowerShare sharing ended");

                    clearStartTime();
                    cancelTimeLimit();

                    startDisconnectTimer();
                }

                break;

            default:

                Log.w(TAG,
                        "Unknown RVSCHG_STATUS: "
                                + status);

                break;
        }
    }

    private void handleStopReason(String reason) {

        final int reasonCode =
                parseInt(
                        reason,
                        REASON_UNKNOWN);

        switch (reasonCode) {

            case REASON_UNKNOWN:

                Log.i(TAG,
                        "PowerShare stop reason: UNKNOWN");
                break;

            case REASON_FULL_CHARGED:

                Log.i(TAG,
                        "PowerShare stop reason: "
                                + "BATTERY_STATUS_FULL");
                break;

            case REASON_DEVICE_TEMPERATURE:

                Log.i(TAG,
                        "PowerShare stop reason: "
                                + "THERMAL_MITIGATION");
                break;

            case REASON_USB_OTG:

                Log.i(TAG,
                        "PowerShare stop reason: EXT");
                break;

            case REASON_ERROR:

                Log.i(TAG,
                        "PowerShare stop reason: ERROR");
                break;

            default:

                Log.w(TAG,
                        "Unknown PowerShare stop reason: "
                                + reasonCode);
                break;
        }
    }

    private void startDisconnectTimer() {

        if (mAlarmManager == null) {
            return;
        }

        final android.content.SharedPreferences prefs =
                getPreferences();

        final boolean enabled =
                prefs.getBoolean(
                        KEY_DISCONNECT_ENABLED,
                        true);

        /*
         * Always remove a previous timeout before deciding
         * whether a new one should exist.
         */
        cancelConnectionTimeout();

        if (!enabled) {

            Log.i(TAG,
                    "Disconnect timeout disabled");

            return;
        }

        final int timeoutMinutes =
                getDisconnectTimeoutMinutes();

        if (timeoutMinutes <= 0) {

            Log.i(TAG,
                    "Disconnect timeout disabled by value");

            return;
        }

        final Intent intent =
                new Intent(
                        this,
                        PowerShareService.class);

        intent.setAction(
                ACTION_CONNECTION_TIMEOUT);

        final PendingIntent pendingIntent =
                PendingIntent.getService(
                        this,
                        REQUEST_CONNECTION_TIMEOUT,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE);

        final long triggerAt =
                SystemClock.elapsedRealtime()
                        + timeoutMinutes * 60_000L;

        mAlarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent);

        Log.i(TAG,
                "Disconnect timeout scheduled: "
                        + timeoutMinutes
                        + " minute(s)");
    }

    private void cancelConnectionTimeout() {

        if (mAlarmManager == null) {
            return;
        }

        final Intent intent =
                new Intent(
                        this,
                        PowerShareService.class);

        intent.setAction(
                ACTION_CONNECTION_TIMEOUT);

        final PendingIntent pendingIntent =
                PendingIntent.getService(
                        this,
                        REQUEST_CONNECTION_TIMEOUT,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE);

        mAlarmManager.cancel(pendingIntent);
    }

    private void handleConnectionTimeout() {

        /*
         * Protect against an old alarm that survived a preference
         * change.
         */
        if (!getPreferences().getBoolean(
                KEY_DISCONNECT_ENABLED,
                true)) {

            Log.i(TAG,
                    "Connection timeout ignored: "
                            + "disabled in Settings");

            return;
        }

        final int timeoutMinutes =
                getDisconnectTimeoutMinutes();

        if (timeoutMinutes <= 0) {
            return;
        }

        if (!isEnabled()) {
            return;
        }

        if (mIsSharing) {

            Log.i(TAG,
                    "Connection timeout ignored: "
                            + "PowerShare is sharing again");

            return;
        }

        Log.i(TAG,
                "Connection timeout reached");

        disablePowerShare(
                "connection timeout");
    }

    private void scheduleTimeLimit() {

        /*
         * Always cancel the previous timer first.
         *
         * This is important when the user changes the time
         * limit while reverse charging is active.
         */
        cancelTimeLimit();

        if (!mIsSharing) {
            return;
        }

        final int timeLimitMinutes =
                getTimeLimitMinutes();

        /*
         * 0 means "never".
         */
        if (timeLimitMinutes <= 0) {

            Log.i(TAG,
                    "PowerShare time limit disabled");

            return;
        }

        long startTime =
                getStartTime();

        final long now =
                SystemClock.elapsedRealtime();

        if (startTime <= 0
                || startTime > now) {

            startTime = now;

            saveStartTime(startTime);
        }

        final long limitMillis =
                timeLimitMinutes * 60_000L;

        final long elapsed =
                now - startTime;

        final long remaining =
                limitMillis - elapsed;

        if (remaining <= 0) {

            Log.i(TAG,
                    "PowerShare time limit already reached");

            disablePowerShare(
                    "time limit reached");

            return;
        }

        final Intent intent =
                new Intent(
                        this,
                        PowerShareService.class);

        intent.setAction(
                ACTION_TIME_LIMIT);

        final PendingIntent pendingIntent =
                PendingIntent.getService(
                        this,
                        REQUEST_TIME_LIMIT,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE);

        final long triggerAt =
                now + remaining;

        mAlarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent);

        Log.i(TAG,
                "Time limit scheduled: "
                        + timeLimitMinutes
                        + " minute(s)");
    }

    private void cancelTimeLimit() {

        if (mAlarmManager == null) {
            return;
        }

        final Intent intent =
                new Intent(
                        this,
                        PowerShareService.class);

        intent.setAction(ACTION_TIME_LIMIT);

        final PendingIntent pendingIntent =
                PendingIntent.getService(
                        this,
                        REQUEST_TIME_LIMIT,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE);

        mAlarmManager.cancel(pendingIntent);
    }

    private void handleTimeLimit() {

        if (!isEnabled()) {
            return;
        }

        if (!mIsSharing) {

            Log.i(TAG,
                    "Time limit ignored: "
                            + "PowerShare is not sharing");

            return;
        }

        scheduleTimeLimit();
    }

    private void disablePowerShare(
            String reason) {

        Log.i(TAG,
                "Disabling PowerShare: "
                        + reason);

        if (mPowerShare == null) {
            mPowerShare = getPowerShare();
        }

        if (mPowerShare != null) {

            try {

                mPowerShare.setEnabled(false);

            } catch (Exception e) {

                Log.e(TAG,
                        "Failed to disable PowerShare "
                                + "through AIDL",
                        e);
            }

        } else {

            Log.e(TAG,
                    "Cannot disable PowerShare: "
                            + "AIDL service unavailable");
        }

        /*
         * Do not change mIsSharing here.
         *
         * The kernel's RVSCHG_STATUS remains authoritative.
         */
        cancelConnectionTimeout();
        cancelTimeLimit();
        clearStartTime();
    }

    private android.content.SharedPreferences getPreferences() {

        return getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE);
    }

    private int getDisconnectTimeoutMinutes() {

        final String value =
                getPreferences().getString(
                        KEY_DISCONNECT_TIMEOUT,
                        "1");

        try {

            return Integer.parseInt(value);

        } catch (NumberFormatException e) {

            Log.w(TAG,
                    "Invalid disconnect timeout value: "
                            + value);

            return 1;
        }
    }

    private int getTimeLimitMinutes() {

        final String value =
                getPreferences().getString(
                        KEY_TIME_LIMIT,
                        "60");

        try {

            return Integer.parseInt(value);

        } catch (NumberFormatException e) {

            Log.w(TAG,
                    "Invalid time limit value: "
                            + value);

            return 60;
        }
    }

    private int getMinBattery() {

        if (mPowerShare == null) {
            mPowerShare = getPowerShare();
        }

        if (mPowerShare == null) {

            Log.e(TAG,
                    "PowerShare AIDL unavailable");

            return -1;
        }

        try {

            return mPowerShare.getMinBattery();

        } catch (Exception e) {

            Log.e(TAG,
                    "Failed to read minimum battery",
                    e);

            return -1;
        }
    }

    private long getStartTime() {

        return getPreferences().getLong(
                KEY_START_TIME,
                0L);
    }

    private void saveStartTime(long time) {

        getPreferences()
                .edit()
                .putLong(KEY_START_TIME, time)
                .apply();
    }

    private void clearStartTime() {

        getPreferences()
                .edit()
                .remove(KEY_START_TIME)
                .apply();
    }

    private int parseInt(
            String value,
            int defaultValue) {

        if (value == null) {
            return defaultValue;
        }

        try {

            return Integer.parseInt(value);

        } catch (NumberFormatException e) {

            return defaultValue;
        }
    }

    @Override
    public void onDestroy() {

        mBatteryShareEventObserver.stopObserving();

        mHandler.removeCallbacksAndMessages(null);

        try {

            unregisterReceiver(
                    mBatteryReceiver);

        } catch (IllegalArgumentException e) {

            Log.w(TAG,
                    "Battery receiver already unregistered");
        }

        cancelConnectionTimeout();
        cancelTimeLimit();

        super.onDestroy();

        Log.i(TAG,
                "PowerShareService destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
