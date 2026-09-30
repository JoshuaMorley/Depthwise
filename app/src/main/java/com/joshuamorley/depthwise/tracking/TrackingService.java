package com.joshuamorley.depthwise.tracking;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.joshuamorley.depthwise.MainActivity;
import com.joshuamorley.depthwise.Prefs;
import com.joshuamorley.depthwise.R;
import com.joshuamorley.depthwise.data.Track;
import com.joshuamorley.depthwise.util.Geo;

/** Foreground service that feeds GPS fixes to {@link TrackRecorder} while recording. */
public final class TrackingService extends Service implements LocationListener {

    public static final String ACTION_STOP = "com.joshuamorley.depthwise.STOP_RECORDING";
    private static final String CHANNEL = "recording";
    private static final int NOTIFICATION_ID = 1;
    /** Fixes worse than this are skipped so the track doesn't zig-zag. */
    private static final float MAX_ACCURACY_M = 40f;

    private LocationManager locationManager;
    private NotificationManager notifications;
    private long lastNotificationUpdate;

    @Override
    public void onCreate() {
        super.onCreate();
        notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW));
        locationManager = getSystemService(LocationManager.class);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            TrackRecorder.get(this).stop();
            return START_NOT_STICKY;
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        Prefs prefs = new Prefs(this);
        try {
            locationManager.removeUpdates(this);
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    prefs.trackIntervalS() * 1000L, prefs.trackMinDistanceM(), this, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException e) {
            TrackRecorder.get(this).stop();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        if (location.hasAccuracy() && location.getAccuracy() > MAX_ACCURACY_M) return;
        TrackRecorder recorder = TrackRecorder.get(this);
        recorder.onLocation(location);
        long now = System.currentTimeMillis();
        if (now - lastNotificationUpdate > 10_000) {
            lastNotificationUpdate = now;
            notifications.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    @Override
    public void onProviderDisabled(@NonNull String provider) {}

    @Override
    public void onProviderEnabled(@NonNull String provider) {}

    private Notification buildNotification() {
        Track t = TrackRecorder.get(this).active();
        String text = t == null ? "" : getString(R.string.recording_notification_text,
                Geo.formatDistance(t.lengthM(), new Prefs(this).distanceUnits()), t.size());
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, TrackingService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_record)
                .setContentTitle(getString(R.string.recording_notification))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(R.drawable.ic_stop, getString(R.string.stop_recording), stop)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    @Override
    public void onDestroy() {
        locationManager.removeUpdates(this);
        TrackRecorder.get(this).onServiceStopped();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
