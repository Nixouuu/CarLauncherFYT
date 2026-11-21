package com.fyt.carlauncher;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

/**
 * Carte + position de la voiture + vitesse.
 *
 * - GPS demandé à chaque fix (≈1 Hz), plus de filtre « 2 s / 3 m » qui figeait la vitesse.
 * - Entre deux fix, la voiture glisse en continu (interpolation linéaire ~30 i/s) au lieu de se téléporter.
 * - Vitesse filtrée : 0 à l'arrêt (hystérésis 4/6 km/h + rejet des fix imprécis),
 *   « -- » si plus de signal GPS depuis 4 s.
 * - Carte OpenStreetMap toujours sombre (filtre de couleurs, voir MapTiles).
 */
final class LiveMap implements LocationListener {

    private static final double DEFAULT_LAT = 45.5017;   // Montréal
    private static final double DEFAULT_LON = -73.5673;
    private static final double DEFAULT_ZOOM = 16.0;

    private static final float START_MOVING_KMH = 6f;    // en dessous, on reste « à l'arrêt »
    private static final float STOP_MOVING_KMH = 4f;     // en dessous, on repasse « à l'arrêt »
    private static final float MAX_ACCURACY_M = 30f;     // fix plus imprécis : ignoré pour la vitesse
    private static final long GPS_STALE_MS = 4000;
    private static final long FRAME_MS = 33;

    private final Activity activity;
    private final MapView map;
    private final TextView tvSpeed;
    private final View btnRecenter;
    private final LocationManager locationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Marker marker;
    private ValueAnimator animator;
    private boolean following = true;
    private boolean started;

    // Position actuellement affichée (peut être en cours d'animation)
    private boolean hasPosition;
    private double shownLat, shownLon;
    private float shownBearing;
    private float shownSpeed = -1f;

    private Location lastFix;
    private long lastGpsFixAt;
    private boolean moving;
    private long lastFrameAt;

    LiveMap(Activity activity, MapView map, TextView tvSpeed, View btnRecenter) {
        this.activity = activity;
        this.map = map;
        this.tvSpeed = tvSpeed;
        this.btnRecenter = btnRecenter;
        this.locationManager = (LocationManager) activity.getSystemService(Activity.LOCATION_SERVICE);
    }

    void setup() {
        map.setTilesScaledToDpi(false);
        map.setTilesScaleFactor(MapTiles.SCALE);
        map.setMultiTouchControls(true);
        map.setBuiltInZoomControls(false);
        map.setMinZoomLevel(3.0);
        map.setMaxZoomLevel(19.5);
        map.setHorizontalMapRepetitionEnabled(false);
        map.setVerticalMapRepetitionEnabled(false);
        map.setTileSource(MapTiles.SOURCE);
        map.getOverlayManager().getTilesOverlay().setColorFilter(MapTiles.darkFilter());
        map.getOverlayManager().getTilesOverlay().setLoadingBackgroundColor(0xFF1C1C1E);
        map.getOverlayManager().getTilesOverlay().setLoadingLineColor(0xFF1C1C1E);
        map.getController().setZoom(DEFAULT_ZOOM);
        map.getController().setCenter(new GeoPoint(DEFAULT_LAT, DEFAULT_LON));

        marker = new Marker(map);
        marker.setIcon(activity.getDrawable(R.drawable.ic_car_arrow));
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        marker.setFlat(true);
        marker.setInfoWindow(null);
        marker.setOnMarkerClickListener((m, v) -> true);
        map.getOverlays().add(marker);

        final int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        map.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        // Un glissé à un doigt = l'utilisateur explore la carte : on arrête de suivre.
                        if (following && e.getPointerCount() == 1
                                && (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop)) {
                            setFollowing(false);
                        }
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    void recenter() {
        setFollowing(true);
        if (hasPosition) map.getController().animateTo(new GeoPoint(shownLat, shownLon));
    }

    private void setFollowing(boolean follow) {
        following = follow;
        btnRecenter.setVisibility(follow ? View.GONE : View.VISIBLE);
    }

    // ---------------------------------------------------------------- cycle de vie

    void start() {
        map.onResume();
        if (started || locationManager == null || !hasPermission()) return;
        started = true;
        try {
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null) jumpTo(last);

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, this, Looper.getMainLooper());
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, this, Looper.getMainLooper());
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
        handler.post(staleWatchdog);
    }

    void stop() {
        map.onPause();
        started = false;
        handler.removeCallbacks(staleWatchdog);
        if (animator != null) animator.cancel();
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (Exception ignored) {
            }
        }
    }

    void onPermissionResult() {
        if (!started) start();
    }

    private boolean hasPermission() {
        return activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private final Runnable staleWatchdog = new Runnable() {
        @Override
        public void run() {
            if (SystemClock.elapsedRealtime() - lastGpsFixAt > GPS_STALE_MS) {
                shownSpeed = -1f;
                moving = false;
                tvSpeed.setText(R.string.speed_none);
            }
            handler.postDelayed(this, 1000);
        }
    };

    // ---------------------------------------------------------------- localisation

    @Override
    public void onLocationChanged(Location loc) {
        if (loc == null) return;
        long now = SystemClock.elapsedRealtime();
        boolean gps = LocationManager.GPS_PROVIDER.equals(loc.getProvider());
        if (gps) {
            lastGpsFixAt = now;
        } else if (now - lastGpsFixAt < 10000) {
            return;   // GPS actif : on ignore le réseau, moins précis
        }

        if (!hasPosition || lastFix == null) {
            jumpTo(loc);
            lastFix = loc;
            return;
        }

        float targetSpeed = gps ? filteredSpeedKmh(loc) : -1f;

        // À l'arrêt, on ne déplace pas la voiture pour un simple bruit de position.
        double toLat = loc.getLatitude();
        double toLon = loc.getLongitude();
        if (!moving) {
            float[] d = new float[1];
            Location.distanceBetween(shownLat, shownLon, toLat, toLon, d);
            float noise = Math.max(8f, loc.hasAccuracy() ? loc.getAccuracy() : 8f);
            if (d[0] < noise) {
                toLat = shownLat;
                toLon = shownLon;
            }
        }

        float toBearing = shownBearing;
        if (moving && gps && loc.hasBearing()) {
            toBearing = loc.getBearing();
        } else if (moving && lastFix.distanceTo(loc) > 4f) {
            toBearing = lastFix.bearingTo(loc);
        }

        long dtMs = (loc.getElapsedRealtimeNanos() - lastFix.getElapsedRealtimeNanos()) / 1_000_000L;
        lastFix = loc;

        float[] jump = new float[1];
        Location.distanceBetween(shownLat, shownLon, toLat, toLon, jump);
        if (dtMs <= 0 || dtMs > 5000 || jump[0] > 500f) {
            applyFrame(toLat, toLon, toBearing);
            showSpeed(targetSpeed);
            return;
        }
        animateTo(toLat, toLon, toBearing, targetSpeed, dtMs);
    }

    /** Vitesse en km/h avec hystérésis et rejet des fix imprécis. */
    private float filteredSpeedKmh(Location loc) {
        float kmh;
        if (loc.hasSpeed()) {
            kmh = loc.getSpeed() * 3.6f;
            if (Build.VERSION.SDK_INT >= 26 && loc.hasSpeedAccuracy()) {
                // Vitesse indiscernable de son incertitude → considérée nulle.
                if (loc.getSpeed() < 1.5f * loc.getSpeedAccuracyMetersPerSecond()) kmh = 0f;
            }
        } else if (lastFix != null) {
            long dt = (loc.getElapsedRealtimeNanos() - lastFix.getElapsedRealtimeNanos()) / 1_000_000L;
            kmh = dt > 0 ? lastFix.distanceTo(loc) / (dt / 1000f) * 3.6f : 0f;
        } else {
            kmh = 0f;
        }
        if (loc.hasAccuracy() && loc.getAccuracy() > MAX_ACCURACY_M && kmh < 20f) kmh = 0f;

        if (moving) {
            if (kmh < STOP_MOVING_KMH) moving = false;
        } else if (kmh >= START_MOVING_KMH) {
            moving = true;
        }
        return moving ? kmh : 0f;
    }

    private void jumpTo(Location loc) {
        applyFrame(loc.getLatitude(), loc.getLongitude(), shownBearing);
        if (following) map.getController().setCenter(new GeoPoint(shownLat, shownLon));
    }

    private void animateTo(final double toLat, final double toLon, float toBearing,
                           final float toSpeed, long durationMs) {
        if (animator != null) animator.cancel();
        final double fromLat = shownLat, fromLon = shownLon;
        final float fromBearing = shownBearing;
        final float bearingDelta = shortestAngle(fromBearing, toBearing);
        final float fromSpeed = shownSpeed < 0 ? toSpeed : shownSpeed;

        // Légèrement plus long que l'intervalle entre fix : la voiture ne marque jamais d'arrêt.
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(Math.min(1600, durationMs + 120));
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            long t = SystemClock.uptimeMillis();
            if (f < 1f && t - lastFrameAt < FRAME_MS) return;
            lastFrameAt = t;
            applyFrame(fromLat + (toLat - fromLat) * f,
                    fromLon + (toLon - fromLon) * f,
                    fromBearing + bearingDelta * f);
            if (toSpeed >= 0) showSpeed(fromSpeed + (toSpeed - fromSpeed) * f);
        });
        animator.start();
        if (toSpeed < 0) showSpeed(-1f);
    }

    private void applyFrame(double lat, double lon, float bearing) {
        shownLat = lat;
        shownLon = lon;
        shownBearing = bearing;
        hasPosition = true;
        GeoPoint p = new GeoPoint(lat, lon);
        marker.setPosition(p);
        marker.setRotation(-bearing);
        if (following) {
            map.getController().setCenter(p);
        } else {
            map.invalidate();
        }
    }

    private void showSpeed(float kmh) {
        shownSpeed = kmh;
        if (kmh < 0) {
            tvSpeed.setText(R.string.speed_none);
        } else {
            tvSpeed.setText(String.valueOf(Math.round(kmh)));
        }
    }


    private static float shortestAngle(float from, float to) {
        float d = (to - from) % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
}
