package com.joshuamorley.nzlinz;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.gson.JsonElement;
import com.joshuamorley.nzlinz.charts.ChartPack;
import com.joshuamorley.nzlinz.charts.ChartRepository;
import com.joshuamorley.nzlinz.charts.ChartSource;
import com.joshuamorley.nzlinz.charts.ChartStyler;
import com.joshuamorley.nzlinz.charts.NearestFinder;
import com.joshuamorley.nzlinz.data.Mark;
import com.joshuamorley.nzlinz.data.MarkStore;
import com.joshuamorley.nzlinz.data.Palette;
import com.joshuamorley.nzlinz.data.Track;
import com.joshuamorley.nzlinz.data.TrackStore;
import com.joshuamorley.nzlinz.map.MapOverlays;
import com.joshuamorley.nzlinz.map.ShallowWatch;
import com.joshuamorley.nzlinz.map.SpriteLoader;
import com.joshuamorley.nzlinz.tracking.TrackRecorder;
import com.joshuamorley.nzlinz.util.FeatureText;
import com.joshuamorley.nzlinz.util.Geo;

import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.location.LocationComponent;
import org.maplibre.android.location.LocationComponentActivationOptions;
import org.maplibre.android.location.LocationComponentOptions;
import org.maplibre.android.location.OnCameraTrackingChangedListener;
import org.maplibre.android.location.modes.CameraMode;
import org.maplibre.android.location.modes.RenderMode;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.maps.UiSettings;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.geojson.Feature;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements
        TrackStore.Listener, MarkStore.Listener, ChartRepository.Listener, TrackRecorder.Listener {

    private static final String PREF_CAMERA = "camera";
    private static final long SHALLOW_CHECK_MS = 1000;
    private static final long ACTIVE_TRACK_REDRAW_MS = 1000;
    private static final long ALARM_REPEAT_MS = 15_000;
    private static final long ALARM_SILENCE_MS = 120_000;

    private MapView mapView;
    private MapLibreMap map;
    private Style style;
    private Prefs prefs;
    private final MapOverlays overlays = new MapOverlays();
    private final ShallowWatch shallowWatch = new ShallowWatch();
    private ChartStyler.Result chartStyle;
    private String currentStyleJson;

    private TrackStore trackStore;
    private MarkStore markStore;
    private ChartRepository charts;
    private TrackRecorder recorder;

    private LocationManager locationManager;
    private Location lastLocation;
    /** Elapsed-realtime of the newest GPS-quality / network-only fix (see isGpsQuality). */
    private long lastGpsFixAt, lastNetworkFixAt;
    private long locationStartedAt;
    private int satsUsed, satsVisible;
    private View gpsStatus;
    private TextView gpsStatusText, gpsStatusDetail;
    private final GnssStatus.Callback gnssCallback = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
            int used = 0;
            for (int i = 0; i < status.getSatelliteCount(); i++) if (status.usedInFix(i)) used++;
            satsUsed = used;
            satsVisible = status.getSatelliteCount();
        }

        @Override
        public void onStopped() {
            satsUsed = satsVisible = 0;
        }
    };
    private final Runnable gpsTicker = new Runnable() {
        @Override
        public void run() {
            updateGpsStatus();
            updateAccuracy(); // goes back to "--" when fixes stop
            gpsStatus.postDelayed(this, 2000);
        }
    };
    private boolean locationComponentReady;
    private boolean following;

    private boolean measuring, measureCrosshairMode;
    private final List<LatLng> measurePoints = new ArrayList<>();

    private long lastShallowCheck, lastActiveRedraw, lastAlarmAt, silencedUntil, lastDepthLookup;
    private Location lastDepthLookupAt;
    private boolean alarmActive, boatDepthShallow;
    private ToneGenerator tone;

    private View hud, warning, measureBar, loading;
    private TextView hudSpeed, hudCourse, hudDepth, hudAccuracy, warningText, measureTotal, measureDetail, loadingText;

    /** Set once the chart has drawn; releases the splash screen. */
    private volatile boolean firstFrameReady;

    // What's still loading; the chip shows the first one that's true.
    private boolean chartsBusy, styleBusy = true, iconsBusy, tilesBusy;
    private final Runnable showLoading = () -> loading.setVisibility(View.VISIBLE);
    private FloatingActionButton btnLocation;
    private ExtendedFloatingActionButton btnRecord;

    /** Refreshes the currently open list sheet, if any. */
    private Runnable openSheetRefresh;

    private final LocationListener locationListener = this::onLocation;

    private final ActivityResultLauncher<String[]> locationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (hasLocationPermission()) {
                    startLocationUpdates();
                    applyTrackMode();
                    if (style != null) enableLocationComponent(style);
                } else {
                    Toast.makeText(this, R.string.location_permission_needed, Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (Prefs.TRACK_AUTO.equals(prefs.trackMode())) recorder.startAuto();
                else recorder.start();
            });
    private boolean askedNotificationForAuto;

    private final ActivityResultLauncher<String[]> pickFile = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onFilePicked);

    private final ActivityResultLauncher<Uri> pickFolder = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), this::onFolderPicked);

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (sp, key) -> {
        if (Prefs.DISTANCE_UNITS.equals(key)) updateRecordButton();
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Splash (with the pinging arrow) stays until the chart has drawn, or 3.5 s at most;
        // after that the "Loading…" chip takes over.
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        long splashStart = SystemClock.elapsedRealtime();
        splash.setKeepOnScreenCondition(() ->
                !firstFrameReady && SystemClock.elapsedRealtime() - splashStart < 3500);
        splash.setOnExitAnimationListener(view -> view.getView().animate()
                .alpha(0f)
                .setDuration(250)
                .withEndAction(view::remove)
                .start());
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        trackStore = TrackStore.get(this);
        markStore = MarkStore.get(this);
        charts = ChartRepository.get(this);
        recorder = TrackRecorder.get(this);
        locationManager = getSystemService(LocationManager.class);

        bindViews();

        mapView = findViewById(R.id.map);
        mapView.onCreate(savedInstanceState);
        mapView.addOnDidBecomeIdleListener(() -> {
            if (style != null) firstFrameReady = true;
            if (tilesBusy) {
                tilesBusy = false;
                updateLoading();
            }
        });
        mapView.addOnDidFailLoadingMapListener(error -> {
            firstFrameReady = true;
            styleBusy = tilesBusy = false;
            updateLoading();
            Toast.makeText(this, getString(R.string.map_load_failed, error), Toast.LENGTH_LONG).show();
        });
        mapView.getMapAsync(this::onMapReady);
        updateLoading();

        trackStore.load();
        markStore.load();
        charts.refresh();

        if (!hasLocationPermission()) {
            locationPermission.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
        }
    }

    private void bindViews() {
        hud = findViewById(R.id.hud);
        hudSpeed = findViewById(R.id.hudSpeed);
        hudCourse = findViewById(R.id.hudCourse);
        hudDepth = findViewById(R.id.hudDepth);
        hudAccuracy = findViewById(R.id.hudAccuracy);
        warning = findViewById(R.id.warning);
        warningText = findViewById(R.id.warningText);
        measureBar = findViewById(R.id.measureBar);
        measureTotal = findViewById(R.id.measureTotal);
        measureDetail = findViewById(R.id.measureDetail);
        gpsStatus = findViewById(R.id.gpsStatus);
        gpsStatusText = findViewById(R.id.gpsStatusText);
        gpsStatusDetail = findViewById(R.id.gpsStatusDetail);
        loading = findViewById(R.id.loading);
        loadingText = findViewById(R.id.loadingText);
        btnLocation = findViewById(R.id.btnLocation);
        btnRecord = findViewById(R.id.btnRecord);

        View overlay = findViewById(R.id.overlay);
        ViewCompat.setOnApplyWindowInsetsListener(overlay, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            if (map != null) placeCompass(bars);
            return insets;
        });

        findViewById(R.id.btnCharts).setOnClickListener(v -> showChartsSheet());
        findViewById(R.id.btnTracks).setOnClickListener(v -> showTracksSheet());
        findViewById(R.id.btnMarks).setOnClickListener(v -> showMarksSheet());
        findViewById(R.id.btnMeasure).setOnClickListener(v -> setMeasuring(!measuring));
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.measureUndo).setOnClickListener(v -> {
            if (!measurePoints.isEmpty()) measurePoints.remove(measurePoints.size() - 1);
            updateMeasure();
        });
        findViewById(R.id.measureClose).setOnClickListener(v -> setMeasuring(false));
        findViewById(R.id.measureAdd).setOnClickListener(v -> {
            LatLng c = crosshairPoint();
            if (c == null) return;
            measurePoints.add(c);
            updateMeasure();
        });
        btnLocation.setOnClickListener(v -> onLocationButton());
        btnRecord.setOnClickListener(v -> onRecordButton());
        warning.setOnClickListener(v -> {
            silencedUntil = SystemClock.elapsedRealtime() + ALARM_SILENCE_MS;
            Toast.makeText(this, R.string.alarm_silenced, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();
        trackStore.addListener(this);
        markStore.addListener(this);
        charts.addListener(this);
        chartsBusy = charts.isBusy(); // a scan may have started before we were listening
        updateLoading();
        recorder.addListener(this);
        prefs.raw().registerOnSharedPreferenceChangeListener(prefListener);
        if (hasLocationPermission()) startLocationUpdates();
        updateRecordButton();
        gpsStatus.post(gpsTicker);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        if (prefs.keepScreenOn()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyHudPrefs();
        applyTrackMode();
        if (lastLocation != null) lookUpBoatDepth(lastLocation, true);
        if (map != null) {
            rebuildStyle(); // settings may have changed draft / labels
            if (following) startFollowing(); // course-up may have changed
        }
        if (lastLocation != null) runShallowCheck(lastLocation, true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
        saveCamera();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mapView.onStop();
        trackStore.removeListener(this);
        markStore.removeListener(this);
        charts.removeListener(this);
        recorder.removeListener(this);
        prefs.raw().unregisterOnSharedPreferenceChangeListener(prefListener);
        locationManager.removeUpdates(locationListener);
        locationManager.unregisterGnssStatusCallback(gnssCallback);
        gpsStatus.removeCallbacks(gpsTicker);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tone != null) tone.release();
        mapView.onDestroy();
    }

    // ------------------------------------------------------------------ map setup

    private void onMapReady(MapLibreMap m) {
        map = m;
        UiSettings ui = map.getUiSettings();
        ui.setLogoEnabled(false);
        ui.setAttributionEnabled(false);
        ui.setTiltGesturesEnabled(false);
        ui.setCompassFadeFacingNorth(true);
        ViewCompat.requestApplyInsets(findViewById(R.id.overlay));
        map.setPrefetchesTiles(true);
        map.setMaxZoomPreference(20);
        restoreCamera();

        map.addOnMapClickListener(this::onMapClick);
        // Crosshair measuring: the provisional leg follows the map as it's dragged.
        map.addOnCameraMoveListener(() -> {
            if (measuring && measureCrosshairMode && !measurePoints.isEmpty()) updateMeasure();
        });
        map.addOnMapLongClickListener(point -> {
            if (measuring) return false;
            showMarkDialog(null, point);
            return true;
        });
        rebuildStyle();
    }

    private void placeCompass(Insets bars) {
        float d = getResources().getDisplayMetrics().density;
        map.getUiSettings().setCompassGravity(Gravity.BOTTOM | Gravity.END);
        map.getUiSettings().setCompassMargins(0, 0, bars.right + (int) (24 * d), bars.bottom + (int) (92 * d));
    }

    /** Rebuilds the style from enabled charts and settings; no-op when nothing changed. */
    private void rebuildStyle() {
        if (map == null) return;
        chartStyle = ChartStyler.build(charts.sources(), prefs.minSafeDepthM(), prefs.showShallow(),
                prefs.depthLabels(), charts::isGroupVisible);
        if (chartStyle.styleJson.equals(currentStyleJson)) return;
        currentStyleJson = chartStyle.styleJson;
        ChartStyler.Result forStyle = chartStyle;
        overlays.clear();
        style = null;
        styleBusy = true;
        iconsBusy = false;
        updateLoading();
        map.setStyle(new Style.Builder().fromJson(forStyle.styleJson), s -> onStyleLoaded(s, forStyle));
    }

    private void onStyleLoaded(Style s, ChartStyler.Result result) {
        style = s;
        styleBusy = false;
        tilesBusy = true; // cleared when the map goes idle
        iconsBusy = !result.sprites.isEmpty();
        updateLoading();
        overlays.install(s, prefs.depthLabels());
        pushOverlays();
        shallowWatch.setAlarms(result.alarms);
        if (lastLocation != null) lookUpBoatDepth(lastLocation, true);

        if (!result.sprites.isEmpty()) {
            SpriteLoader.load(result.sprites, getResources().getDisplayMetrics().density, icons -> {
                if (style != s || !s.isFullyLoaded()) return;
                iconsBusy = false;
                updateLoading();
                if (!icons.normal.isEmpty()) s.addImages(icons.normal);
                if (!icons.sdf.isEmpty()) s.addImages(icons.sdf, true);
            });
        }


        if (hasLocationPermission()) enableLocationComponent(s);
    }

    private void pushOverlays() {
        if (!overlays.isInstalled()) return;
        Track active = recorder.active();
        overlays.setTracks(trackStore.snapshot(), active != null ? active.id : null);
        overlays.setActive(active);
        overlays.setMarks(markStore.snapshot());
        overlays.setMeasure(measurePoints, measureLegLabels());
    }

    @SuppressLint("MissingPermission")
    private void enableLocationComponent(Style s) {
        if (locationComponentReady) return; // the component re-adds its layers on style changes itself
        LocationComponent lc = map.getLocationComponent();
        LocationComponentOptions options = LocationComponentOptions.builder(this)
                .accuracyAlpha(0.12f)
                .accuracyColor(ContextCompat.getColor(this, R.color.ocean_primary))
                .build();
        lc.activateLocationComponent(LocationComponentActivationOptions.builder(this, s)
                .locationComponentOptions(options)
                .useDefaultLocationEngine(false) // we feed it our own GPS fixes
                .build());
        lc.setLocationComponentEnabled(true);
        lc.setRenderMode(RenderMode.GPS);
        lc.addOnCameraTrackingChangedListener(new OnCameraTrackingChangedListener() {
            @Override
            public void onCameraTrackingDismissed() {
                following = false;
                updateLocationButton();
            }

            @Override
            public void onCameraTrackingChanged(int currentMode) {
            }
        });
        locationComponentReady = true;
        if (lastLocation != null) lc.forceLocationUpdate(lastLocation);
    }

    // ------------------------------------------------------------------ location

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        if (locationStartedAt == 0) locationStartedAt = SystemClock.elapsedRealtime();
        try {
            locationManager.removeUpdates(locationListener);
            // Raw GPS, plus the fused provider (GPS + Wi-Fi + sensors), which keeps giving smooth
            // fixes with speed/course when raw GPS drops out indoors. Network is the last resort.
            // No minimum distance, so fixes keep coming while stationary.
            requestIfEnabled(LocationManager.GPS_PROVIDER, 1000);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) requestIfEnabled(LocationManager.FUSED_PROVIDER, 1000);
            requestIfEnabled(LocationManager.NETWORK_PROVIDER, 5000);
            locationManager.registerGnssStatusCallback(ContextCompat.getMainExecutor(this), gnssCallback);
            if (lastLocation == null) {
                // Show where we last were straight away; it only counts as a fix if it's recent.
                Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (last == null) last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (last != null) onLocation(last);
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
    }

    @SuppressLint("MissingPermission")
    private void requestIfEnabled(String provider, long intervalMs) {
        if (!locationManager.isProviderEnabled(provider)) return;
        locationManager.requestLocationUpdates(provider, intervalMs, 0, locationListener, getMainLooper());
    }

    /** How old a fix is, from its own timestamp. */
    private static long ageMs(Location loc) {
        return (SystemClock.elapsedRealtimeNanos() - loc.getElapsedRealtimeNanos()) / 1_000_000;
    }

    /** GPS-quality: raw GPS, or a fused fix accurate enough that it must be GPS-backed. */
    private static boolean isGpsQuality(Location loc) {
        String p = loc.getProvider();
        if (LocationManager.GPS_PROVIDER.equals(p)) return true;
        return "fused".equals(p) && loc.hasAccuracy() && loc.getAccuracy() <= 25;
    }

    private void onLocation(@NonNull Location loc) {
        long age = ageMs(loc);
        if (age > 30_000 && lastLocation != null) return; // stale cached fix
        long fixAt = SystemClock.elapsedRealtime() - Math.max(0, age);
        if (isGpsQuality(loc)) lastGpsFixAt = Math.max(lastGpsFixAt, fixAt);
        else lastNetworkFixAt = Math.max(lastNetworkFixAt, fixAt);
        // Keep the current fix if it's still fresh and clearly more accurate than this one.
        if (lastLocation != null && ageMs(lastLocation) < 5_000 && lastLocation.hasAccuracy() && loc.hasAccuracy()
                && loc.getAccuracy() > lastLocation.getAccuracy() * 2 + 5) {
            return;
        }
        lastLocation = loc;
        if (locationComponentReady) map.getLocationComponent().forceLocationUpdate(loc);
        updateHud(loc);
        runShallowCheck(loc, false);
    }

    /** Explains a missing or poor position; hidden when GPS is fresh and accurate. */
    private void updateGpsStatus() {
        long now = SystemClock.elapsedRealtime();
        int title, detailRes = 0;
        String detail = null;
        View.OnClickListener onClick = null;
        if (!hasLocationPermission()) {
            title = R.string.gps_no_permission;
            detailRes = R.string.gps_no_permission_detail;
            onClick = v -> locationPermission.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
        } else if (!locationManager.isLocationEnabled()) {
            title = R.string.gps_off;
            detailRes = R.string.gps_off_detail;
            onClick = v -> startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        } else if (lastGpsFixAt > 0 && now - lastGpsFixAt < 10_000) {
            if (lastLocation == null || !lastLocation.hasAccuracy() || lastLocation.getAccuracy() <= 50) {
                gpsStatus.setVisibility(View.GONE);
                return;
            }
            title = R.string.gps_weak;
            detail = getString(R.string.gps_weak_detail, Math.round(lastLocation.getAccuracy()));
        } else if (lastLocation != null && lastNetworkFixAt > 0 && now - lastNetworkFixAt < 30_000) {
            title = R.string.gps_approx;
            detail = getString(R.string.gps_approx_detail, Math.round(lastLocation.getAccuracy()));
        } else if (lastGpsFixAt > 0) {
            title = R.string.gps_lost;
            detail = getString(R.string.gps_lost_detail, Geo.formatDuration(now - lastGpsFixAt));
        } else if (now - locationStartedAt < 15_000) {
            // A fix normally arrives within a few seconds of opening the app; don't alarm before then.
            title = R.string.gps_starting;
        } else {
            title = R.string.gps_searching;
            detail = satsVisible > 0 ? getString(R.string.gps_searching_detail, satsUsed, satsVisible)
                    : getString(R.string.gps_searching_nosats);
        }
        gpsStatusText.setText(title);
        if (detailRes != 0) detail = getString(detailRes);
        gpsStatusDetail.setText(detail);
        gpsStatusDetail.setVisibility(detail == null ? View.GONE : View.VISIBLE);
        gpsStatus.setOnClickListener(onClick);
        gpsStatus.setClickable(onClick != null);
        gpsStatus.setVisibility(View.VISIBLE);
    }

    private void updateHud(Location loc) {
        boolean moving = loc.hasSpeed() && loc.getSpeed() > 0.3f;
        hudSpeed.setText(loc.hasSpeed() ? Geo.formatSpeed(loc.getSpeed(), prefs.speedUnits()) : "--");
        hudCourse.setText(moving && loc.hasBearing() ? Geo.formatBearing(loc.getBearing()) + "T" : "--");
        updateAccuracy();
        lookUpBoatDepth(loc, false);
    }

    /** GPS cell: ±accuracy of the current fix, coloured by quality; "--" when there's no fresh fix. */
    private void updateAccuracy() {
        if (lastLocation == null || !lastLocation.hasAccuracy() || ageMs(lastLocation) > 10_000) {
            hudAccuracy.setText("--");
            hudAccuracy.setTextColor(ContextCompat.getColor(this, R.color.ocean_on_container));
            return;
        }
        float acc = lastLocation.getAccuracy();
        hudAccuracy.setText(acc < 10 ? String.format(Locale.US, "±%.0f m", acc)
                : acc < 1000 ? String.format(Locale.US, "±%d m", Math.round(acc))
                : String.format(Locale.US, "±%.1f km", acc / 1000));
        int color = acc <= 10 ? R.color.good_green : acc <= 30 ? R.color.warning_amber : R.color.recording;
        hudAccuracy.setTextColor(ContextCompat.getColor(this, color));
    }

    /**
     * DEPTH readout: the nearest charted sounding to the boat, read from the chart at full
     * detail. Refreshed every few seconds or after moving a little.
     */
    private void lookUpBoatDepth(Location loc, boolean force) {
        if (!prefs.showDepth() || chartStyle == null) return;
        List<ChartStyler.Nearest> specs = new ArrayList<>();
        for (ChartStyler.Nearest n : chartStyle.nearest) if (n.rule.depthField != null) specs.add(n);
        if (specs.isEmpty()) {
            setBoatDepth(Double.NaN);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        boolean moved = lastDepthLookupAt == null || Geo.distanceM(lastDepthLookupAt.getLatitude(),
                lastDepthLookupAt.getLongitude(), loc.getLatitude(), loc.getLongitude()) > 10;
        if (!force && !moved && now - lastDepthLookup < 5000) return;
        if (!force && now - lastDepthLookup < 2000) return;
        lastDepthLookup = now;
        lastDepthLookupAt = loc;
        NearestFinder.find(specs, loc.getLatitude(), loc.getLongitude(), hits -> {
            double best = Double.NaN, bestD = Double.MAX_VALUE;
            for (NearestFinder.Hit h : hits) {
                if (!Double.isNaN(h.depthM) && h.distanceM < bestD) {
                    bestD = h.distanceM;
                    best = h.depthM;
                }
            }
            setBoatDepth(best);
        });
    }

    private void setBoatDepth(double depthM) {
        if (Double.isNaN(depthM)) {
            hudDepth.setText("--");
        } else if (depthM < 0) {
            hudDepth.setText(String.format(Locale.US, "dries %.1f m", -depthM));
        } else {
            hudDepth.setText(String.format(Locale.US, "%.1f m", depthM));
        }
        double safe = prefs.minSafeDepthM();
        boatDepthShallow = !Double.isNaN(depthM) && safe > 0 && depthM < safe;
        updateDepthColor();
    }

    private void updateDepthColor() {
        hudDepth.setTextColor(ContextCompat.getColor(this,
                boatDepthShallow || alarmActive ? R.color.recording : R.color.ocean_on_container));
    }

    /** Width of an instrument cell's widest normal reading, measured in its own text style. */
    private int cellWidth(View box) {
        String sample;
        int id = box.getId();
        if (id == R.id.hudSpeedBox) sample = "kmh".equals(prefs.speedUnits()) ? "88.8 km/h" : "88.8 kn";
        else if (id == R.id.hudCourseBox) sample = "888°T";
        else if (id == R.id.hudDepthBox) sample = "88.8 m";
        else sample = "±888 m";
        TextView value = (TextView) ((ViewGroup) box).getChildAt(1);
        return (int) Math.ceil(value.getPaint().measureText(sample)) + value.getPaddingStart() + value.getPaddingEnd();
    }

    /** Shows the instrument cells chosen in settings; hides the card if none are. */
    private void applyHudPrefs() {
        View[] boxes = {findViewById(R.id.hudSpeedBox), findViewById(R.id.hudCourseBox),
                findViewById(R.id.hudDepthBox), findViewById(R.id.hudAccuracyBox)};
        boolean[] show = {prefs.showSog(), prefs.showCog(), prefs.showDepth(), prefs.showAccuracy()};
        LinearLayout row1 = findViewById(R.id.hudRow1), row2 = findViewById(R.id.hudRow2);
        List<View> visible = new ArrayList<>();
        for (int i = 0; i < boxes.length; i++) {
            ((ViewGroup) boxes[i].getParent()).removeView(boxes[i]);
            boxes[i].setVisibility(show[i] ? View.VISIBLE : View.GONE);
            if (show[i]) visible.add(boxes[i]);
            else row1.addView(boxes[i]); // keep hidden cells attached so findViewById still works
        }
        // Phones can't fit four cells across, so use a 2x2 grid there.
        boolean grid = !getResources().getBoolean(R.bool.is_tablet) && visible.size() > 3;
        int perRow = grid ? 2 : visible.size();

        // Fixed cell widths, sized for the widest normal reading, so the card doesn't
        // resize as values change (e.g. "--" -> "150°T"). Grid columns share a width.
        int[] widths = new int[visible.size()];
        for (int i = 0; i < visible.size(); i++) widths[i] = cellWidth(visible.get(i));
        if (grid) {
            for (int col = 0; col < perRow; col++) {
                int w = 0;
                for (int i = col; i < visible.size(); i += perRow) w = Math.max(w, widths[i]);
                for (int i = col; i < visible.size(); i += perRow) widths[i] = w;
            }
        }
        for (int i = 0; i < visible.size(); i++) {
            View box = visible.get(i);
            boolean firstInRow = i % Math.max(1, perRow) == 0;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(firstInRow ? 0 : dp(18));
            box.setMinimumWidth(widths[i]);
            (i < perRow ? row1 : row2).addView(box, lp);
        }
        row2.setVisibility(grid ? View.VISIBLE : View.GONE);
        hud.setVisibility(visible.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void onLocationButton() {
        if (!hasLocationPermission()) {
            locationPermission.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
            return;
        }
        if (following) stopFollowing();
        else startFollowing();
    }

    private void startFollowing() {
        if (!locationComponentReady) return;
        following = true;
        LocationComponent lc = map.getLocationComponent();
        double zoom = map.getCameraPosition().zoom;
        boolean courseUp = prefs.courseUp();
        lc.setCameraMode(courseUp ? CameraMode.TRACKING_GPS : CameraMode.TRACKING, 600L,
                zoom < 11 ? 14.0 : null, courseUp ? null : 0.0, null, null);
        if (lastLocation == null) Toast.makeText(this, R.string.waiting_for_fix, Toast.LENGTH_SHORT).show();
        updateLocationButton();
    }

    private void stopFollowing() {
        following = false;
        if (locationComponentReady) map.getLocationComponent().setCameraMode(CameraMode.NONE);
        updateLocationButton();
    }

    private void updateLocationButton() {
        int primary = ContextCompat.getColor(this, R.color.ocean_primary);
        int white = ContextCompat.getColor(this, R.color.white);
        btnLocation.setImageResource(following ? R.drawable.ic_navigation : R.drawable.ic_my_location);
        btnLocation.setBackgroundTintList(ColorStateList.valueOf(following ? primary : white));
        btnLocation.setImageTintList(ColorStateList.valueOf(following ? white : primary));
    }

    // ------------------------------------------------------------------ shallow water

    private void runShallowCheck(Location loc, boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastShallowCheck < SHALLOW_CHECK_MS) return;
        lastShallowCheck = now;

        double safe = prefs.minSafeDepthM();
        if (!prefs.shallowAlarm() || safe <= 0 || style == null || !shallowWatch.hasRules()) {
            showWarning(null);
            overlays.setAhead(null, null, false);
            return;
        }

        boolean moving = loc.hasSpeed() && loc.getSpeed() > 0.5f && loc.hasBearing();
        double aheadM = moving ? loc.getSpeed() * prefs.lookAheadMin() * 60 : 0;
        ShallowWatch.Result r = shallowWatch.check(style, loc.getLatitude(), loc.getLongitude(),
                loc.getBearing(), aheadM, safe, prefs.alarmRadiusM());

        if (aheadM > 0) {
            double[] end = Geo.destination(loc.getLatitude(), loc.getLongitude(), loc.getBearing(), aheadM);
            overlays.setAhead(new LatLng(loc.getLatitude(), loc.getLongitude()), new LatLng(end[0], end[1]),
                    r.ahead != null);
        } else {
            overlays.setAhead(null, null, false);
        }

        String units = prefs.distanceUnits();
        if (r.now != null) {
            showWarning(getString(R.string.shallow_now, ShallowWatch.describe(r.now, units)));
        } else if (r.ahead != null) {
            showWarning(getString(R.string.shallow_ahead, ShallowWatch.describe(r.ahead, units)));
        } else {
            showWarning(null);
        }
    }

    private void showWarning(String text) {
        boolean active = text != null;
        warning.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) warningText.setText(text);
        long now = SystemClock.elapsedRealtime();
        if (active && now > silencedUntil && (!alarmActive || now - lastAlarmAt > ALARM_REPEAT_MS)) {
            lastAlarmAt = now;
            soundAlarm();
        }
        alarmActive = active;
        updateDepthColor();
    }

    private void soundAlarm() {
        if (prefs.alarmVibrate()) {
            Vibrator v = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? getSystemService(VibratorManager.class).getDefaultVibrator()
                    : getSystemService(Vibrator.class);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createWaveform(new long[]{0, 400, 200, 400}, -1));
            }
        }
        if (prefs.alarmSound()) {
            try {
                if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_ALARM, 90);
                tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1200);
            } catch (RuntimeException ignored) {
                // Some devices refuse a ToneGenerator; vibration still works.
            }
        }
    }

    // ------------------------------------------------------------------ taps

    private boolean onMapClick(@NonNull LatLng point) {
        if (measuring) {
            if (measureCrosshairMode) return true; // points come from the Add button
            measurePoints.add(point);
            updateMeasure();
            return true;
        }
        if (style == null) return false;
        PointF p = map.getProjection().toScreenLocation(point);
        float r = 12 * getResources().getDisplayMetrics().density;
        RectF box = new RectF(p.x - r, p.y - r, p.x + r, p.y + r);

        List<Feature> marks = map.queryRenderedFeatures(box, MapOverlays.MARKS_LAYER);
        if (!marks.isEmpty()) {
            Mark m = markStore.find(FeatureText.string(marks.get(0), "id"));
            if (m != null) {
                showMarkDialog(m, null);
                return true;
            }
        }

        // Query each chart layer separately so we know which layer a feature came from.
        List<String[]> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String layerId : chartStyle.queryableLayerIds) {
            Layer layer = style.getLayer(layerId);
            if (layer == null || Property.NONE.equals(layer.getVisibility().getValue())) continue;
            for (Feature f : map.queryRenderedFeatures(box, layerId)) {
                String key = layerId + f.properties();
                if (!seen.add(key) || rows.size() >= 30) continue;
                rows.add(describeFeature(layerId, f));
            }
        }
        showFeatureSheet(point, rows);
        return true;
    }

    /** {title, subtitle} for a tapped feature, following the pack's inspect rules. */
    private String[] describeFeature(String layerId, Feature f) {
        ChartPack.InspectRule rule = chartStyle.inspect.get(layerId);
        String heading = chartStyle.layerTitles.get(layerId);
        String title = rule != null ? FeatureText.first(rule.title, f) : null;
        if (title == null) title = heading != null ? heading : layerId;
        StringBuilder sb = new StringBuilder();
        String subtitle = rule != null ? FeatureText.first(rule.subtitle, f) : null;
        if (subtitle != null) sb.append(subtitle);
        if (rule != null && !rule.fields.isEmpty()) {
            for (Map.Entry<String, String> e : rule.fields.entrySet()) {
                String v = FeatureText.string(f, e.getKey());
                if (v == null) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(e.getValue()).append(": ").append(v);
            }
        } else if (f.properties() != null) {
            for (Map.Entry<String, JsonElement> e : f.properties().entrySet()) {
                if (rule != null && rule.hide.contains(e.getKey())) continue;
                String v = FeatureText.string(f, e.getKey());
                if (v == null) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(e.getKey()).append(": ").append(v);
            }
        }
        if (rule != null && heading != null && !title.equals(heading)) {
            sb.append(sb.length() > 0 ? "\n" : "").append(heading);
        }
        return new String[]{title, sb.toString()};
    }

    private void showFeatureSheet(LatLng point, List<String[]> rows) {
        BottomSheetDialog sheet = newSheet(getString(R.string.features_here));
        View content = sheet.findViewById(R.id.sheetRows);
        TextView empty = sheet.findViewById(R.id.sheetEmpty);
        LinearLayout header = sheet.findViewById(R.id.sheetHeader);
        LinearLayout actions = sheet.findViewById(R.id.sheetActions);

        TextView coords = new TextView(this);
        coords.setText(Geo.formatLatLon(point.getLatitude(), point.getLongitude()) + distanceFromBoat(
                point.getLatitude(), point.getLongitude()));
        coords.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        coords.setTextIsSelectable(true);
        coords.setPadding(dp(24), 0, dp(24), dp(8));
        header.addView(coords);

        actions.addView(textButton(getString(R.string.mark_here), v -> {
            sheet.dismiss();
            showMarkDialog(null, point);
        }));

        // Nearest sounding etc., read from the chart at full detail in the background.
        List<ChartStyler.Nearest> nearest = chartStyle != null ? chartStyle.nearest
                : new ArrayList<>();
        List<TextView[]> nearestRows = new ArrayList<>();
        for (ChartStyler.Nearest n : nearest) {
            View row = newRow((ViewGroup) content, 0xFF0B5A8C, n.rule.title, getString(R.string.searching), null, null);
            row.setClickable(false);
            nearestRows.add(new TextView[]{row.findViewById(R.id.rowTitle), row.findViewById(R.id.rowSubtitle)});
        }
        if (!nearest.isEmpty()) {
            NearestFinder.find(nearest, point.getLatitude(), point.getLongitude(), hits -> {
                if (!sheet.isShowing()) return;
                NearestFinder.Hit closest = null;
                for (int i = 0; i < nearest.size(); i++) {
                    NearestFinder.Hit hit = null;
                    for (NearestFinder.Hit h : hits) if (h.rule == nearest.get(i).rule) hit = h;
                    TextView[] tv = nearestRows.get(i);
                    if (hit == null) {
                        tv[1].setText(getString(R.string.none_within,
                                Geo.formatDistance(nearest.get(i).rule.maxDistanceM, prefs.distanceUnits())));
                        continue;
                    }
                    if (hit.text != null) tv[0].setText(hit.rule.title + ": " + hit.text);
                    tv[1].setText(getString(R.string.nearest_where,
                            Geo.formatDistance(hit.distanceM, prefs.distanceUnits()),
                            Geo.formatBearing(hit.bearingDeg)));
                    if (closest == null || hit.distanceM < closest.distanceM) closest = hit;
                }
                if (closest != null) overlays.setProbe(point, new LatLng(closest.lat, closest.lon));
            });
        }

        if (rows.isEmpty() && nearest.isEmpty()) {
            empty.setText(R.string.no_features);
            empty.setVisibility(View.VISIBLE);
        }
        for (String[] r : rows) {
            View row = newRow((ViewGroup) content, 0, r[0], r[1], null, null);
            row.setClickable(false);
        }
        overlays.setProbe(point, null);
        sheet.setOnDismissListener(d -> {
            openSheetRefresh = null;
            overlays.setProbe(null, null);
        });
        sheet.setOnShowListener(d -> keepAboveSheet(point, sheet));
        sheet.show();
    }

    /** If the sheet covers the tapped point, slide the map up so the crosshair stays visible. */
    private void keepAboveSheet(LatLng point, BottomSheetDialog sheet) {
        if (following || map == null) return;
        View panel = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (panel == null || panel.getHeight() == 0) return;
        // The sheet is still sliding in, so use its final height rather than its current position.
        float sheetTop = mapView.getHeight() - panel.getHeight();
        PointF p = map.getProjection().toScreenLocation(point);
        float wantedY = sheetTop - dp(72);
        if (p.y <= wantedY) return;
        // Move the camera centre down by the overlap, which moves the point up.
        float cx = mapView.getWidth() / 2f, cy = mapView.getHeight() / 2f;
        LatLng target = map.getProjection().fromScreenLocation(new PointF(cx, cy + (p.y - wantedY)));
        map.animateCamera(CameraUpdateFactory.newLatLng(target), 300);
    }

    private String distanceFromBoat(double lat, double lon) {
        if (lastLocation == null) return "";
        double d = Geo.distanceM(lastLocation.getLatitude(), lastLocation.getLongitude(), lat, lon);
        double b = Geo.bearingDeg(lastLocation.getLatitude(), lastLocation.getLongitude(), lat, lon);
        return "\n" + Geo.formatDistance(d, prefs.distanceUnits()) + " at " + Geo.formatBearing(b) + "T from you";
    }

    // ------------------------------------------------------------------ measuring

    private void setMeasuring(boolean on) {
        measuring = on;
        measurePoints.clear();
        boolean crosshair = on && prefs.measureCrosshair();
        measureCrosshairMode = crosshair;
        findViewById(R.id.measureCrosshair).setVisibility(crosshair ? View.VISIBLE : View.GONE);
        findViewById(R.id.measureAdd).setVisibility(crosshair ? View.VISIBLE : View.GONE);
        if (crosshair) stopFollowing(); // following would drag the crosshair with the boat
        measureBar.setVisibility(on ? View.VISIBLE : View.GONE);
        updateRecordButton();
        FloatingActionButton b = findViewById(R.id.btnMeasure);
        int primary = ContextCompat.getColor(this, R.color.ocean_primary);
        int white = ContextCompat.getColor(this, R.color.white);
        b.setBackgroundTintList(ColorStateList.valueOf(on ? primary : white));
        b.setImageTintList(ColorStateList.valueOf(on ? white : primary));
        updateMeasure();
    }

    /** The map position under the centre crosshair. */
    private LatLng crosshairPoint() {
        if (map == null || mapView.getWidth() == 0) return null;
        return map.getProjection().fromScreenLocation(
                new PointF(mapView.getWidth() / 2f, mapView.getHeight() / 2f));
    }

    /** Placed points, plus the live crosshair position as a provisional last point in crosshair mode. */
    private List<LatLng> measureLine() {
        List<LatLng> line = new ArrayList<>(measurePoints);
        if (measureCrosshairMode && !measurePoints.isEmpty()) {
            LatLng c = crosshairPoint();
            if (c != null) line.add(c);
        }
        return line;
    }

    /** Distance of each leg of {@code line}, in the chosen units. */
    private List<String> measureLegLabels(List<LatLng> line) {
        List<String> labels = new ArrayList<>();
        String units = prefs.distanceUnits();
        for (int i = 1; i < line.size(); i++) {
            LatLng a = line.get(i - 1), b = line.get(i);
            labels.add(Geo.formatDistance(
                    Geo.distanceM(a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude()), units));
        }
        return labels;
    }

    private List<String> measureLegLabels() {
        return measureLegLabels(measureLine());
    }

    private void updateMeasure() {
        List<LatLng> line = measureLine();
        overlays.setMeasure(line, measureLegLabels(line));
        String units = prefs.distanceUnits();
        if (line.size() < 2) {
            measureTotal.setText(measureCrosshairMode ? R.string.measure_hint_crosshair : R.string.measure_hint);
            measureDetail.setVisibility(View.GONE);
            return;
        }
        double total = 0;
        for (int i = 1; i < line.size(); i++) {
            LatLng a = line.get(i - 1), b = line.get(i);
            total += Geo.distanceM(a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude());
        }
        LatLng a = line.get(line.size() - 2), b = line.get(line.size() - 1);
        double leg = Geo.distanceM(a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude());
        double brg = Geo.bearingDeg(a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude());
        measureTotal.setText(Geo.formatDistance(total, units));
        String other = "km".equals(units) ? Geo.formatDistance(total, "nm") : Geo.formatDistance(total, "km");
        measureDetail.setText(String.format(Locale.US, "%s · last leg %s at %sT · %d pts",
                other, Geo.formatDistance(leg, units), Geo.formatBearing(brg), measurePoints.size()));
        measureDetail.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ recording

    private void onRecordButton() {
        if (recorder.isRecording()) {
            recorder.stop();
            return;
        }
        if (!hasLocationPermission()) {
            locationPermission.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        recorder.start();
    }

    /** Track mode from settings: auto keeps recording, off stops, manual leaves it to the button. */
    private void applyTrackMode() {
        String mode = prefs.trackMode();
        if (Prefs.TRACK_AUTO.equals(mode)) {
            if (!recorder.isRecording() && hasLocationPermission()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedNotificationForAuto
                        && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    askedNotificationForAuto = true;
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
                    return; // the result callback starts recording
                }
                recorder.startAuto();
            }
        } else if (Prefs.TRACK_OFF.equals(mode)) {
            recorder.stop();
        }
        updateRecordButton();
    }

    private void updateRecordButton() {
        btnRecord.setVisibility(Prefs.TRACK_MANUAL.equals(prefs.trackMode()) && !measuring
                ? View.VISIBLE : View.GONE);
        Track t = recorder.active();
        if (t == null) {
            btnRecord.setText(R.string.record_track);
            btnRecord.setIconResource(R.drawable.ic_record);
        } else {
            btnRecord.setText(getString(R.string.recording_since, Geo.formatDistance(t.lengthM(), prefs.distanceUnits())));
            btnRecord.setIconResource(R.drawable.ic_stop);
        }
    }

    @Override
    public void onRecordingChanged(boolean recording, Track track) {
        updateRecordButton();
        pushOverlays();
        if (openSheetRefresh != null) openSheetRefresh.run();
    }

    @Override
    public void onTrackPoint(Track track) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastActiveRedraw < ACTIVE_TRACK_REDRAW_MS) return;
        lastActiveRedraw = now;
        overlays.setActive(track);
        updateRecordButton();
    }

    // ------------------------------------------------------------------ store callbacks

    @Override
    public void onTracksChanged(List<Track> tracks) {
        Track active = recorder.active();
        overlays.setTracks(tracks, active != null ? active.id : null);
        if (openSheetRefresh != null) openSheetRefresh.run();
    }

    @Override
    public void onMarksChanged(List<Mark> marks) {
        overlays.setMarks(marks);
        if (openSheetRefresh != null) openSheetRefresh.run();
    }

    @Override
    public void onChartsChanged(List<ChartSource> sources) {
        rebuildStyle();
        if (openSheetRefresh != null) openSheetRefresh.run();
    }

    @Override
    public void onChartsBusy(boolean busy) {
        chartsBusy = busy;
        updateLoading();
    }

    /** Shows what's loading, after a short delay so quick loads don't flash the chip. */
    private void updateLoading() {
        int msg = chartsBusy ? R.string.loading_charts
                : styleBusy ? R.string.loading_style
                : iconsBusy ? R.string.loading_icons
                : tilesBusy ? R.string.loading_tiles
                : 0;
        loading.removeCallbacks(showLoading);
        if (msg == 0) {
            loading.setVisibility(View.GONE);
            return;
        }
        loadingText.setText(msg);
        if (loading.getVisibility() != View.VISIBLE) loading.postDelayed(showLoading, 250);
    }

    // ------------------------------------------------------------------ sheets

    private BottomSheetDialog newSheet(String title) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(R.layout.sheet_list);
        ((TextView) sheet.findViewById(R.id.sheetTitle)).setText(title);
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.setOnDismissListener(d -> openSheetRefresh = null);
        return sheet;
    }

    private View newRow(ViewGroup parent, int swatchColor, String title, String subtitle,
                        Boolean checked, CheckBox.OnCheckedChangeListener onCheck) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_row, parent, false);
        View swatch = row.findViewById(R.id.rowSwatch);
        if (swatchColor != 0) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(swatchColor);
            swatch.setBackground(dot);
        } else {
            swatch.setVisibility(View.GONE);
        }
        ((TextView) row.findViewById(R.id.rowTitle)).setText(title);
        TextView sub = row.findViewById(R.id.rowSubtitle);
        sub.setText(subtitle);
        sub.setVisibility(TextUtils.isEmpty(subtitle) ? View.GONE : View.VISIBLE);
        CheckBox check = row.findViewById(R.id.rowCheck);
        if (checked == null) {
            check.setVisibility(View.GONE);
        } else {
            check.setChecked(checked);
            check.setOnCheckedChangeListener(onCheck);
        }
        parent.addView(row);
        return row;
    }

    private MaterialButton textButton(String text, View.OnClickListener onClick) {
        MaterialButton b = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        b.setOnClickListener(onClick);
        return b;
    }

    // ---- charts

    private void showChartsSheet() {
        BottomSheetDialog sheet = newSheet(getString(R.string.charts));
        LinearLayout actions = sheet.findViewById(R.id.sheetActions);
        MaterialButton add = textButton(getString(R.string.add), null);
        add.setIconResource(R.drawable.ic_add);
        add.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, v);
            menu.getMenu().add(0, 1, 0, R.string.add_file_menu);
            menu.getMenu().add(0, 2, 1, R.string.add_folder_menu);
            menu.getMenu().add(0, 3, 2, R.string.add_url_menu);
            menu.setOnMenuItemClickListener(item -> {
                sheet.dismiss();
                if (item.getItemId() == 1) pickFile.launch(new String[]{"*/*"});
                else if (item.getItemId() == 2) pickFolder.launch(null);
                else showAddUrlDialog();
                return true;
            });
            menu.show();
        });
        actions.addView(add);

        Runnable populate = () -> {
            LinearLayout header = sheet.findViewById(R.id.sheetHeader);
            LinearLayout rows = sheet.findViewById(R.id.sheetRows);
            TextView empty = sheet.findViewById(R.id.sheetEmpty);
            header.removeAllViews();
            rows.removeAllViews();
            List<ChartSource> list = charts.sources();

            // Layer groups (merged across packs)
            if (chartStyle != null && !chartStyle.groups.isEmpty()) {
                header.addView(sectionLabel(getString(R.string.layers)));
                ChipGroup chips = chipRow(header);
                for (ChartStyler.Group g : chartStyle.groups) {
                    Chip c = new Chip(this, null, com.google.android.material.R.attr.chipStyle);
                    c.setText(g.title);
                    c.setCheckable(true);
                    c.setChecked(charts.isGroupVisible(g.id, g.defaultVisible));
                    c.setOnCheckedChangeListener((btn, on) -> setGroupVisible(g, on));
                    chips.addView(c);
                }
            }
            // Jump-to chips for anything with bounds
            List<ChartSource> withBounds = new ArrayList<>();
            for (ChartSource s : list) if (s.bounds() != null) withBounds.add(s);
            if (!withBounds.isEmpty()) {
                header.addView(sectionLabel(getString(R.string.go_to)));
                ChipGroup chips = chipRow(header);
                for (ChartSource s : withBounds) {
                    Chip c = new Chip(this, null, com.google.android.material.R.attr.chipStyle);
                    c.setText(s.title);
                    c.setOnClickListener(v -> {
                        sheet.dismiss();
                        zoomToBounds(s.bounds());
                    });
                    chips.addView(c);
                }
            }
            if (chartStyle != null && !chartStyle.attributions.isEmpty()) {
                TextView attr = new TextView(this);
                attr.setText(TextUtils.join("\n", chartStyle.attributions));
                attr.setTextSize(11);
                attr.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                attr.setPadding(dp(24), dp(8), dp(24), 0);
                header.addView(attr);
            }

            empty.setText(getString(R.string.no_charts, charts.chartsDir().getAbsolutePath()));
            empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
            for (ChartSource s : list) {
                int color = s.error != null ? 0xFFD32F2F : s.isPack() ? 0xFF0B5A8C
                        : s.info != null && s.info.vector ? 0xFF3E6374 : 0xFF9E9E9E;
                View row = newRow(rows, color, s.title, describeSource(s), s.enabled,
                        (b, on) -> charts.setEnabled(s, on));
                row.setOnClickListener(v -> showChartActions(s, sheet));
            }
        };
        openSheetRefresh = populate;
        populate.run();
        sheet.show();
    }

    private String describeSource(ChartSource s) {
        if (s.error != null) return s.error;
        StringBuilder sb = new StringBuilder();
        switch (s.kind) {
            case PACK:
                sb.append("Chart pack · ").append(s.pack.layers.length()).append(" layers");
                if (!s.pack.sprites.isEmpty()) sb.append(" · icons");
                if (!s.pack.alarms.isEmpty()) sb.append(" · depth alarm");
                sb.append('\n').append(s.file.getName()).append(" · ").append(formatSize(folderSize(s.file)));
                break;
            case REMOTE_PACK:
                sb.append("Remote chart pack · ").append(s.pack.layers.length()).append(" layers\n").append(s.url);
                break;
            case FILE_PMTILES:
                sb.append(s.info.vector ? "Vector" : "Raster").append(" PMTiles (auto style)");
                if (s.info.vector) sb.append(" · ").append(s.info.layers.size()).append(" layers");
                sb.append('\n').append(s.file.getName()).append(" · ").append(formatSize(s.file.length()));
                break;
            default:
                sb.append(s.info.vector ? "Remote vector" : "Remote raster").append('\n').append(s.url);
        }
        return sb.toString();
    }

    private void showChartActions(ChartSource s, BottomSheetDialog sheet) {
        List<String> items = new ArrayList<>();
        if (s.bounds() != null) items.add(getString(R.string.zoom_to));
        items.add(getString(R.string.delete));
        new MaterialAlertDialogBuilder(this)
                .setTitle(s.title)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String choice = items.get(which);
                    if (choice.equals(getString(R.string.zoom_to))) {
                        sheet.dismiss();
                        zoomToBounds(s.bounds());
                    } else {
                        confirmDelete(getString(R.string.remove_chart_q, s.title), () -> charts.remove(s, () -> {
                        }));
                    }
                })
                .show();
    }

    private void setGroupVisible(ChartStyler.Group g, boolean visible) {
        charts.setGroupVisible(g.id, visible);
        if (style != null) {
            for (String id : g.layerIds) {
                Layer l = style.getLayer(id);
                if (l != null) l.setProperties(PropertyFactory.visibility(visible ? Property.VISIBLE : Property.NONE));
            }
        }
        // Keep the cached style JSON in step so this doesn't trigger a full reload later.
        chartStyle = ChartStyler.build(charts.sources(), prefs.minSafeDepthM(), prefs.showShallow(),
                prefs.depthLabels(), charts::isGroupVisible);
        currentStyleJson = chartStyle.styleJson;
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setAllCaps(true);
        t.setLetterSpacing(0.06f);
        t.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        t.setPadding(dp(24), dp(8), dp(24), 0);
        return t;
    }

    private ChipGroup chipRow(LinearLayout parent) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        ChipGroup chips = new ChipGroup(this);
        chips.setSingleLine(true);
        chips.setPadding(dp(24), 0, dp(24), 0);
        scroll.addView(chips);
        parent.addView(scroll);
        return chips;
    }

    private void onFilePicked(Uri uri) {
        if (uri == null) return;
        String name = charts.displayName(uri);
        if (name == null) name = "chart.pmtiles";
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".pmtiles") && !lower.endsWith(".zip")) {
            Toast.makeText(this, R.string.not_a_chart, Toast.LENGTH_LONG).show();
            return;
        }
        String fileName = name;
        File target = charts.importTarget(fileName);
        confirmReplaceIfExists(target, () -> {
            ImportProgress progress = new ImportProgress(getString(R.string.importing_file, fileName));
            charts.importFile(uri, fileName, progress);
        });
    }

    private void onFolderPicked(Uri treeUri) {
        if (treeUri == null) return;
        File target = charts.folderImportTarget(treeUri);
        confirmReplaceIfExists(target, () -> {
            ImportProgress progress = new ImportProgress(getString(R.string.importing_file, target.getName()));
            charts.importFolder(treeUri, progress);
        });
    }

    private void confirmReplaceIfExists(File target, Runnable go) {
        if (!target.exists()) {
            go.run();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.replace_file_q, target.getName()))
                .setMessage(R.string.cannot_undo)
                .setPositiveButton(R.string.replace, (d, w) -> go.run())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Modal progress dialog for imports. */
    private final class ImportProgress implements ChartRepository.ImportCallback {
        private final AlertDialog dialog;
        private final LinearProgressIndicator bar;

        ImportProgress(String title) {
            bar = new LinearProgressIndicator(MainActivity.this);
            bar.setIndeterminate(true);
            LinearLayout box = new LinearLayout(MainActivity.this);
            box.setPadding(dp(24), dp(16), dp(24), dp(8));
            box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            dialog = new MaterialAlertDialogBuilder(MainActivity.this)
                    .setTitle(title)
                    .setView(box)
                    .setCancelable(false)
                    .show();
        }

        @Override
        public void onProgress(long copied, long total) {
            if (total > 0) {
                bar.setIndeterminate(false);
                bar.setProgressCompat((int) (copied * 100 / total), true);
            }
            dialog.setMessage(formatSize(copied) + (total > 0 ? " of " + formatSize(total) : ""));
        }

        @Override
        public void onDone(String title, String error) {
            dialog.dismiss();
            if (error == null) {
                Toast.makeText(MainActivity.this, getString(R.string.imported, title), Toast.LENGTH_SHORT).show();
            } else if (ChartRepository.NOT_A_CHART.equals(error)) {
                Toast.makeText(MainActivity.this, R.string.not_a_chart, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(MainActivity.this, getString(R.string.import_failed, error), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showAddUrlDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_edit, null);
        TextInputEditText name = v.findViewById(R.id.editName);
        TextInputEditText url = v.findViewById(R.id.editNote);
        ((com.google.android.material.textfield.TextInputLayout) v.findViewById(R.id.editNoteLayout))
                .setHint(R.string.remote_url_hint);
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        v.findViewById(R.id.editColorLabel).setVisibility(View.GONE);
        ((TextView) v.findViewById(R.id.editInfo)).setText(
                "Chart pack: https://…/chartpack.json\nPMTiles: https://…/file.pmtiles\n"
                        + "Raster tiles: https://…/{z}/{x}/{y}.png\nTileJSON: https://…/tiles.json");
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.add_remote_title)
                .setView(v)
                .setPositiveButton(R.string.add, (d, w) -> {
                    String u = text(url);
                    if (u.isEmpty()) return;
                    ImportProgress progress = new ImportProgress(u);
                    charts.addRemote(text(name), u, progress);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---- tracks

    private void showTracksSheet() {
        BottomSheetDialog sheet = newSheet(getString(R.string.tracks));
        Runnable populate = () -> {
            LinearLayout rows = sheet.findViewById(R.id.sheetRows);
            TextView empty = sheet.findViewById(R.id.sheetEmpty);
            rows.removeAllViews();
            List<Track> list = trackStore.snapshot();
            empty.setText(R.string.no_tracks);
            empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
            Track active = recorder.active();
            DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
            for (Track t : list) {
                boolean isActive = active != null && active.id.equals(t.id);
                String sub = (isActive ? "● Recording · " : "")
                        + Geo.formatDistance(t.lengthM(), prefs.distanceUnits())
                        + " · " + Geo.formatDuration(t.durationMs())
                        + "\n" + df.format(new Date(t.createdAt));
                View row = newRow(rows, t.color, t.name, sub, t.visible, (b, on) -> {
                    t.visible = on;
                    trackStore.save(t);
                });
                row.setOnClickListener(v -> showTrackDialog(t, sheet));
            }
        };
        openSheetRefresh = populate;
        populate.run();
        sheet.show();
    }

    private void showTrackDialog(Track t, BottomSheetDialog sheet) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_edit, null);
        TextInputEditText name = v.findViewById(R.id.editName);
        name.setText(t.name);
        v.findViewById(R.id.editNoteLayout).setVisibility(View.GONE);
        int[] color = {t.color};
        buildColorPicker(v.findViewById(R.id.editColors), color);
        ((TextView) v.findViewById(R.id.editInfo)).setText(String.format(Locale.US, "%s · %s · %d points",
                Geo.formatDistance(t.lengthM(), prefs.distanceUnits()), Geo.formatDuration(t.durationMs()), t.size()));
        boolean isActive = recorder.active() != null && recorder.active().id.equals(t.id);

        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.tracks)
                .setView(v)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String n = text(name);
                    if (!n.isEmpty()) t.name = n;
                    t.color = color[0];
                    trackStore.save(t);
                    if (isActive) overlays.setActive(t);
                })
                .setNeutralButton(R.string.zoom_to, (d, w) -> {
                    sheet.dismiss();
                    double[] bb = t.bounds();
                    if (bb != null) zoomToBounds(new double[]{bb[1], bb[0], bb[3], bb[2]});
                });
        if (!isActive) {
            b.setNegativeButton(R.string.delete, (d, w) ->
                    confirmDelete(getString(R.string.delete_track_q, t.name), () -> trackStore.delete(t)));
        }
        b.show();
    }

    // ---- marks

    private void showMarksSheet() {
        BottomSheetDialog sheet = newSheet(getString(R.string.marks));
        Runnable populate = () -> {
            LinearLayout rows = sheet.findViewById(R.id.sheetRows);
            TextView empty = sheet.findViewById(R.id.sheetEmpty);
            rows.removeAllViews();
            List<Mark> list = new ArrayList<>(markStore.snapshot());
            if (lastLocation != null) {
                double lat = lastLocation.getLatitude(), lon = lastLocation.getLongitude();
                list.sort((a, c) -> Double.compare(Geo.distanceM(lat, lon, a.lat, a.lon),
                        Geo.distanceM(lat, lon, c.lat, c.lon)));
            }
            empty.setText(R.string.no_marks);
            empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
            for (Mark m : list) {
                String sub = Geo.formatLatLon(m.lat, m.lon) + distanceFromBoat(m.lat, m.lon);
                View row = newRow(rows, m.color, m.name, sub, null, null);
                row.setOnClickListener(v -> {
                    sheet.dismiss();
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(m.lat, m.lon),
                            Math.max(map.getCameraPosition().zoom, 14)), 600);
                    stopFollowing();
                });
                row.setOnLongClickListener(v -> {
                    showMarkDialog(m, null);
                    return true;
                });
            }
        };
        openSheetRefresh = populate;
        populate.run();
        sheet.show();
    }

    /** Edit an existing mark, or create one at {@code at} when {@code mark} is null. */
    private void showMarkDialog(Mark mark, LatLng at) {
        boolean isNew = mark == null;
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_edit, null);
        TextInputEditText name = v.findViewById(R.id.editName);
        TextInputEditText note = v.findViewById(R.id.editNote);
        int[] color = {isNew ? Palette.pick(markStore.count() + 1) : mark.color};
        double lat = isNew ? at.getLatitude() : mark.lat, lon = isNew ? at.getLongitude() : mark.lon;
        name.setText(isNew ? "Mark " + (markStore.count() + 1) : mark.name);
        if (!isNew) note.setText(mark.note);
        buildColorPicker(v.findViewById(R.id.editColors), color);
        ((TextView) v.findViewById(R.id.editInfo)).setText(Geo.formatLatLon(lat, lon) + distanceFromBoat(lat, lon));

        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this)
                .setTitle(isNew ? R.string.new_mark : R.string.edit_mark)
                .setView(v)
                .setPositiveButton(R.string.save, (d, w) -> {
                    Mark m = isNew ? Mark.create("", color[0], lat, lon) : mark;
                    String n = text(name);
                    m.name = n.isEmpty() ? "Mark" : n;
                    m.note = text(note);
                    m.color = color[0];
                    markStore.save(m);
                });
        if (isNew) {
            b.setNegativeButton(R.string.cancel, null);
        } else {
            b.setNegativeButton(R.string.delete, (d, w) ->
                    confirmDelete(getString(R.string.delete_mark_q, mark.name), () -> markStore.delete(mark)));
            b.setNeutralButton(R.string.measure, (d, w) -> {
                setMeasuring(true);
                if (lastLocation != null) {
                    measurePoints.add(new LatLng(lastLocation.getLatitude(), lastLocation.getLongitude()));
                }
                measurePoints.add(new LatLng(mark.lat, mark.lon));
                updateMeasure();
            });
        }
        b.show();
    }

    // ------------------------------------------------------------------ shared UI helpers

    private void buildColorPicker(LinearLayout container, int[] selected) {
        container.removeAllViews();
        int perRow = 6;
        List<View> swatches = new ArrayList<>();
        LinearLayout row = null;
        for (int i = 0; i < Palette.COLORS.length; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                container.addView(row);
            }
            int c = Palette.COLORS[i];
            View sw = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
            lp.setMargins(0, dp(4), dp(10), dp(4));
            sw.setLayoutParams(lp);
            sw.setContentDescription(Palette.hex(c));
            sw.setOnClickListener(x -> {
                selected[0] = c;
                for (View s : swatches) styleSwatch(s, (int) s.getTag(), selected[0]);
            });
            sw.setTag(c);
            swatches.add(sw);
            styleSwatch(sw, c, selected[0]);
            row.addView(sw);
        }
    }

    private void styleSwatch(View v, int color, int selected) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        if (color == selected) d.setStroke(dp(3), 0xFF06314D);
        else d.setStroke(dp(1), 0x33000000);
        v.setBackground(d);
    }

    private void confirmDelete(String title, Runnable onDelete) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(R.string.cannot_undo)
                .setPositiveButton(R.string.delete, (d, w) -> onDelete.run())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** bounds = {minLon, minLat, maxLon, maxLat}. */
    private void zoomToBounds(double[] b) {
        if (b == null || map == null) return;
        stopFollowing();
        LatLngBounds bounds = LatLngBounds.from(b[3], b[2], b[1], b[0]);
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(48)), 700);
    }

    private void saveCamera() {
        if (map == null) return;
        CameraPosition c = map.getCameraPosition();
        if (c.target == null) return;
        prefs.raw().edit().putString(PREF_CAMERA, c.target.getLatitude() + "," + c.target.getLongitude()
                + "," + c.zoom + "," + c.bearing).apply();
    }

    private void restoreCamera() {
        String s = prefs.raw().getString(PREF_CAMERA, null);
        double lat = -40.9, lon = 174.4, zoom = 4.6, bearing = 0; // all of NZ
        if (s != null) {
            try {
                String[] p = s.split(",");
                lat = Double.parseDouble(p[0]);
                lon = Double.parseDouble(p[1]);
                zoom = Double.parseDouble(p[2]);
                bearing = Double.parseDouble(p[3]);
            } catch (RuntimeException ignored) {
            }
        }
        map.setCameraPosition(new CameraPosition.Builder()
                .target(new LatLng(lat, lon)).zoom(zoom).bearing(bearing).build());
    }

    private static String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static long folderSize(File f) {
        if (f.isFile()) return f.length();
        long total = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) total += folderSize(k);
        return total;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
