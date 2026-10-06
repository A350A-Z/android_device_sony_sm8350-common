package com.sony.powershare;

import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;

import androidx.preference.ListPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import vendor.lineage.powershare.IPowerShare;

public class PowerShareSettingsFragment
        extends PreferenceFragmentCompat {

    private static final String TAG =
            "PowerShareSettings";

    private static final String PREFS_NAME =
            "powershare_settings";

    private static final String KEY_ENABLED =
            "powershare_enabled";

    private static final String KEY_DISCONNECT_ENABLED =
            "powershare_disconnect_enabled";

    private static final String KEY_DISCONNECT_TIMEOUT =
            "powershare_disconnect_timeout";

    private static final String KEY_BATTERY_LIMIT =
            "powershare_battery_limit";

    private static final String KEY_TIME_LIMIT =
            "powershare_time_limit";

    private IPowerShare mPowerShare;

    @Override
    public void onCreatePreferences(
            Bundle savedInstanceState,
            String rootKey) {

        getPreferenceManager()
                .setSharedPreferencesName(PREFS_NAME);

        setPreferencesFromResource(
                R.xml.powershare_settings,
                rootKey);

        mPowerShare = getPowerShare();

        setupEnabledPreference();
        setupDisconnectPreference();
        setupDisconnectTimeoutPreference();
        setupBatteryPreference();
        setupTimePreference();
    }

    private void setupEnabledPreference() {

        final SwitchPreferenceCompat preference =
                findPreference(KEY_ENABLED);

        if (preference == null) {
            return;
        }

        preference.setChecked(
                isPowerShareEnabled());

        preference.setOnPreferenceChangeListener(
                (p, newValue) -> {

                    final boolean value =
                            (Boolean) newValue;

                    if (!setPowerShareEnabled(value)) {
                        return false;
                    }

                    startService(
                            PowerShareService.ACTION_RELOAD);

                    return true;
                });
    }

    private void setupDisconnectPreference() {

        final SwitchPreferenceCompat preference =
                findPreference(KEY_DISCONNECT_ENABLED);

        if (preference != null) {

            preference.setOnPreferenceChangeListener(
                    (p, newValue) -> {

                        startService(
                                PowerShareService.ACTION_RELOAD);

                        return true;
                    });
        }
    }

    private void setupDisconnectTimeoutPreference() {

        final ListPreference preference =
                findPreference(KEY_DISCONNECT_TIMEOUT);

        if (preference != null) {

            preference.setOnPreferenceChangeListener(
                    (p, newValue) -> {

                        startService(
                                PowerShareService.ACTION_RELOAD);

                        return true;
                    });
        }
    }

    private void setupBatteryPreference() {

        final ListPreference preference =
                findPreference(KEY_BATTERY_LIMIT);

        if (preference == null) {
            return;
        }

        if (mPowerShare != null) {

            try {

                final int current =
                        mPowerShare.getMinBattery();

                final String value =
                        Integer.toString(current);

                if (preference.findIndexOfValue(value) >= 0) {
                    preference.setValue(value);
                }

            } catch (Exception e) {

                Log.e(TAG,
                        "Failed to read minimum battery",
                        e);
            }
        }

        preference.setOnPreferenceChangeListener(
                (p, newValue) -> {

                    if (mPowerShare == null) {
                        mPowerShare = getPowerShare();
                    }

                    if (mPowerShare == null) {
                        return false;
                    }

                    try {

                        final int value =
                                Integer.parseInt(
                                        (String) newValue);

                        mPowerShare.setMinBattery(value);

                        startService(
                                PowerShareService.ACTION_RELOAD);

                        return true;

                    } catch (Exception e) {

                        Log.e(TAG,
                                "Failed to set minimum battery",
                                e);

                        return false;
                    }
                });
    }

    private void setupTimePreference() {

        final ListPreference preference =
                findPreference(KEY_TIME_LIMIT);

        if (preference != null) {

            preference.setOnPreferenceChangeListener(
                    (p, newValue) -> {

                        startService(
                                PowerShareService.ACTION_RELOAD);

                        return true;
                    });
        }
    }

    private boolean isPowerShareEnabled() {

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
                    "Failed to read PowerShare state",
                    e);

            return false;
        }
    }

    private boolean setPowerShareEnabled(
            boolean enabled) {

        if (mPowerShare == null) {
            mPowerShare = getPowerShare();
        }

        if (mPowerShare == null) {
            return false;
        }

        try {

            mPowerShare.setEnabled(enabled);

            return true;

        } catch (Exception e) {

            Log.e(TAG,
                    "Failed to change PowerShare state",
                    e);

            return false;
        }
    }

    private IPowerShare getPowerShare() {

        try {

            final IBinder binder =
                    ServiceManager.getService(
                            IPowerShare.DESCRIPTOR
                                    + "/default");

            if (binder == null) {

                Log.e(TAG,
                        "PowerShare service is unavailable");

                return null;
            }

            return IPowerShare.Stub.asInterface(binder);

        } catch (Exception e) {

            Log.e(TAG,
                    "Failed to get PowerShare service",
                    e);

            return null;
        }
    }

    private void startService(String action) {

        try {

            final Intent intent =
                    new Intent(
                            requireContext(),
                            PowerShareService.class);

            intent.setAction(action);

            requireContext().startService(intent);

        } catch (Exception e) {

            Log.e(TAG,
                    "Failed to start PowerShare service",
                    e);
        }
    }

    @Override
    public void onResume() {

        super.onResume();

        final SwitchPreferenceCompat enabled =
                findPreference(KEY_ENABLED);

        if (enabled != null) {
            enabled.setChecked(
                    isPowerShareEnabled());
        }
    }
}
