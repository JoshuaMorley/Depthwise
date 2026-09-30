package com.joshuamorley.nzlinz;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.joshuamorley.nzlinz.charts.ChartRepository;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean popup = getResources().getBoolean(R.bool.is_tablet);
        if (!popup) EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        if (popup) {
            // Tablet: a fixed-size card over the map rather than a full-screen page.
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int width = Math.min((int) (560 * dm.density), (int) (dm.widthPixels * 0.9));
            int height = (int) (dm.heightPixels * 0.85);
            getWindow().setLayout(width, height);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(ContextCompat.getColor(this, R.color.surface));
            bg.setCornerRadius(28 * dm.density);
            getWindow().setBackgroundDrawable(bg);
            View root = findViewById(R.id.settingsRoot);
            root.setBackground(bg);
            root.setClipToOutline(true);
        } else {
            ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsRoot), (v, insets) -> {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsetsCompat.CONSUMED;
            });
        }
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settingsContainer, new SettingsFragment())
                    .commit();
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);

            metresField(Prefs.DRAFT_M, "Not set. Shallow warnings are off.");
            metresField(Prefs.SAFETY_MARGIN_M, "0.5 m");

            // Gap/jump options only matter in auto mode.
            Preference mode = findPreference(Prefs.TRACK_MODE);
            if (mode != null) {
                showAutoOptions(new Prefs(requireContext()).trackMode());
                mode.setOnPreferenceChangeListener((p, value) -> {
                    showAutoOptions(value.toString());
                    return true;
                });
            }

            Preference folder = findPreference("charts_folder");
            if (folder != null) {
                folder.setSummary("Copy .pmtiles files here, or add them from the Charts panel:\n"
                        + ChartRepository.get(requireContext()).chartsDir().getAbsolutePath());
            }
        }

        private void showAutoOptions(String mode) {
            boolean auto = Prefs.TRACK_AUTO.equals(mode);
            Preference gap = findPreference(Prefs.AUTO_GAP_MIN), jump = findPreference(Prefs.AUTO_JUMP_M);
            if (gap != null) gap.setVisible(auto);
            if (jump != null) jump.setVisible(auto);
        }

        private void metresField(String key, String emptySummary) {
            EditTextPreference p = findPreference(key);
            if (p == null) return;
            p.setOnBindEditTextListener(e -> {
                e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                e.setSelection(e.getText().length());
            });
            p.setSummaryProvider(pref -> {
                String v = ((EditTextPreference) pref).getText();
                if (v == null || v.trim().isEmpty()) return emptySummary;
                return v.trim() + " m";
            });
            p.setOnPreferenceChangeListener((pref, value) -> {
                String s = value.toString().trim().replace(',', '.');
                if (s.isEmpty()) return true;
                try {
                    double d = Double.parseDouble(s);
                    return d >= 0 && d < 30;
                } catch (NumberFormatException ex) {
                    return false;
                }
            });
        }
    }
}
