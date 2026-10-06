package com.sony.powershare;

import android.os.Bundle;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

public class PowerShareSettingsActivity
        extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(
                            com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new PowerShareSettingsFragment())
                    .commit();
        }
    }
}
