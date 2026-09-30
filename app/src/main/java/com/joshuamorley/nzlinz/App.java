package com.joshuamorley.nzlinz;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import org.maplibre.android.MapLibre;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // Light-only app to match the light chart style.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        MapLibre.getInstance(this);
    }
}
