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
 * - GPS demandé à chaque fix (≈1 Hz).
 * - Entre deux fix, la voiture glisse en continu (~30 i/s) vers l'endroit où elle sera au prochain fix
 *   (position GPS + vitesse × intervalle) : sans cette anticipation, elle était dessinée ~1 s en retard
 *   (≈15 m à 50 km/h), donc toujours décalée derrière la vraie voiture.
 * - En guidage, la voiture est collée au tracé de l'itinéraire et orientée comme la route (voir Navigator.snapped).
 * - Vitesse affichée telle que mesurée à chaque fix (pas d'interpolation entre deux valeurs),
 *   0 à l'arrêt (hystérésis 3/5 km/h), « -- » si plus de signal GPS depuis 4 s.
 * - Carte toujours sombre (Stadia, ou OpenStreetMap filtré, voir MapTiles).
 */
final class LiveMap implements LocationListener {

    private static final double DEFAULT_LAT = 45.5017;   // Montréal
    private static final double DEFAULT_LON = -73.5673;
    private static final double DEFAULT_ZOOM = 16.0;

    private static final float START_MOVING_KMH = 5f;    // en dessous, on reste « à l'arrêt »
    private static final float STOP_MOVING_KMH = 3f;     // en dessous, on repasse « à l'arrêt »
    private static final float MAX_ACCURACY_M = 30f;     // fix plus imprécis : ignoré pour la vitesse
    private static final long GPS_STALE_MS = 4000;
    private static final long FRAME_MS = 33;
    /** Anticipation maximale (m) quand on prolonge en ligne droite hors guidage. */
    private static final float MAX_LEAD_M = 35f;

    /** Fournit la position collée au trajet (guidage) : {lat, lon, cap} ou null. */
    interface Snapper {
        double[] snapped(double aheadM);
    }

    private Snapper snapper;

    void setSnapper(Snapper s) {
        snapper = s;
    }

    // ---- Statistiques GPS pour le diagnostic (aucune coordonnée : le rapport est public)
    private int statFixes, statWithBearing, statWithSpeed, statSnapped;
    private long statIntervalSumMs, statIntervalCount;
    private float statLastAccuracy = -1f, statLastSpeedKmh = -1f;
    private String statProvider = "aucun";

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
        map.setMultiTouchControls(true);
        map.setBuiltInZoomControls(false);
        // Pas d'inertie après un glissé : la carte ne continue pas de bouger quand on recentre.
        map.setFlingEnabled(false);
        // Voiture placée sous le centre (à ~72 % de la hauteur) pour voir plus loin devant.
        map.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) map.setMapCenterOffset(0, Math.round((b - t) * 0.22f));
        });
        map.setMinZoomLevel(3.0);
        map.setMaxZoomLevel(19.5);
        map.setHorizontalMapRepetitionEnabled(false);
        map.setVerticalMapRepetitionEnabled(false);
        applyTiles(StadiaApi.hasKey());
        if (StadiaApi.hasKey()) {
            StadiaApi.checkTiles((ok, err) -> { if (!Boolean.TRUE.equals(ok)) applyTiles(false); });
        }
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

    interface LocationObserver {
        void onLocation(Location loc);
    }

    private LocationObserver observer;
    private Location lastAccepted;

    void setLocationObserver(LocationObserver o) {
        observer = o;
    }

    /** Dernière position reçue (GPS de préférence), ou dernière connue du système. */
    Location lastLocation() {
        if (lastAccepted != null) return lastAccepted;
        if (locationManager == null || !hasPermission()) return null;
        try {
            Location l = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            return l != null ? l : locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        } catch (SecurityException e) {
            return null;
        }
    }

    /** Mode navigation : zoom rapproché et suivi de la voiture. */
    void setNavigationMode(boolean on) {
        map.getController().setZoom(on ? 17.5 : DEFAULT_ZOOM);
        recenter();
    }

    MapView map() {
        return map;
    }

    /** Fond de carte : Stadia (sombre) si la clé est valide, sinon OpenStreetMap assombri. */
    private void applyTiles(boolean stadia) {
        if (stadia) {
            map.setTilesScaleFactor(MapTiles.STADIA_SCALE);
            map.setTileSource(MapTiles.stadiaDark());
            map.getOverlayManager().getTilesOverlay().setColorFilter(null);
        } else {
            map.setTilesScaleFactor(MapTiles.SCALE);
            map.setTileSource(MapTiles.SOURCE);
            map.getOverlayManager().getTilesOverlay().setColorFilter(MapTiles.darkFilter());
        }
        if (attribution != null) {
            attribution.setText(stadia ? R.string.map_attribution_stadia : R.string.map_attribution);
        }
    }

    private TextView attribution;

    void setAttributionView(TextView v) {
        attribution = v;
    }

    /** Revient à la voiture d'un coup : pas d'animation qui se battrait avec le suivi en direct. */
    void recenter() {
        map.getController().stopAnimation(false);
        setFollowing(true);
        if (hasPosition) applyFrame(shownLat, shownLon, shownBearing);
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
        lastAccepted = loc;
        recordStats(loc, gps);
        if (observer != null) observer.onLocation(loc);   // Navigator d'abord : il met à jour l'accrochage au trajet

        if (!hasPosition || lastFix == null) {
            jumpTo(loc);
            lastFix = loc;
            return;
        }

        float targetSpeed = gps ? filteredSpeedKmh(loc) : -1f;
        // Vitesse affichée immédiatement, telle que mesurée (plus d'interpolation sur 1 s).
        showSpeed(targetSpeed);

        long dtMs = (loc.getElapsedRealtimeNanos() - lastFix.getElapsedRealtimeNanos()) / 1_000_000L;

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
        lastFix = loc;

        // Anticipation : on vise l'endroit où la voiture sera au prochain fix (≈ dtMs plus tard).
        if (moving && gps && dtMs > 0 && dtMs <= 3000) {
            float speedMs = loc.hasSpeed() ? loc.getSpeed() : 0f;
            double lead = speedMs * (dtMs / 1000.0);
            double[] snap = snapper != null ? snapper.snapped(lead) : null;
            if (snap != null) {
                // Guidage : collé au tracé, orienté comme la route.
                toLat = snap[0];
                toLon = snap[1];
                toBearing = (float) snap[2];
                statSnapped++;
            } else {
                double l = Math.min(lead, MAX_LEAD_M);
                double rad = Math.toRadians(toBearing);
                toLat += (l * Math.cos(rad)) / 111320.0;
                toLon += (l * Math.sin(rad)) / (111320.0 * Math.cos(Math.toRadians(toLat)));
            }
        } else if (snapper != null) {
            double[] snap = snapper.snapped(0);
            if (snap != null) {
                toLat = snap[0];
                toLon = snap[1];
                if (moving) toBearing = (float) snap[2];
                statSnapped++;
            }
        }

        float[] jump = new float[1];
        Location.distanceBetween(shownLat, shownLon, toLat, toLon, jump);
        if (dtMs <= 0 || dtMs > 5000 || jump[0] > 500f) {
            applyFrame(toLat, toLon, toBearing);
            return;
        }
        animateTo(toLat, toLon, toBearing, dtMs);
    }

    private void recordStats(Location loc, boolean gps) {
        statFixes++;
        statProvider = loc.getProvider();
        if (loc.hasBearing()) statWithBearing++;
        if (loc.hasSpeed()) statWithSpeed++;
        statLastAccuracy = loc.hasAccuracy() ? loc.getAccuracy() : -1f;
        statLastSpeedKmh = loc.hasSpeed() ? loc.getSpeed() * 3.6f : -1f;
        if (gps && lastAccepted != null && lastFix != null) {
            long dt = (loc.getElapsedRealtimeNanos() - lastFix.getElapsedRealtimeNanos()) / 1_000_000L;
            if (dt > 0 && dt < 10000) {
                statIntervalSumMs += dt;
                statIntervalCount++;
            }
        }
    }

    /** Résumé GPS pour l'écran Diagnostic et le rapport GitHub (aucune coordonnée). */
    String stats() {
        int pct = statFixes > 0 ? 100 : 0;
        return "GPS : fix=" + statFixes + " source=" + statProvider
                + " intervalle moyen=" + (statIntervalCount > 0 ? statIntervalSumMs / statIntervalCount + " ms" : "?")
                + " précision=" + (statLastAccuracy >= 0 ? Math.round(statLastAccuracy) + " m" : "?")
                + " vitesse brute=" + (statLastSpeedKmh >= 0 ? Math.round(statLastSpeedKmh) + " km/h" : "?")
                + " avec cap=" + (statFixes > 0 ? statWithBearing * pct / statFixes : 0) + "%"
                + " avec vitesse=" + (statFixes > 0 ? statWithSpeed * pct / statFixes : 0) + "%"
                + " collé au trajet=" + statSnapped
                + " suivi=" + following + " orientation carte=" + Math.round(map.getMapOrientation()) + "°";
    }

    /** Vitesse en km/h avec hystérésis et rejet des fix imprécis. */
    private float filteredSpeedKmh(Location loc) {
        float kmh;
        if (loc.hasSpeed()) {
            kmh = loc.getSpeed() * 3.6f;
            if (Build.VERSION.SDK_INT >= 26 && loc.hasSpeedAccuracy()) {
                // Très basse vitesse indiscernable de son incertitude → considérée nulle (seulement < 7 km/h :
                // au-dessus, on affiche toujours la vraie vitesse mesurée).
                if (loc.getSpeed() < 2f && loc.getSpeed() < 1.5f * loc.getSpeedAccuracyMetersPerSecond()) kmh = 0f;
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

    private void animateTo(final double toLat, final double toLon, float toBearing, long durationMs) {
        if (animator != null) animator.cancel();
        final double fromLat = shownLat, fromLon = shownLon;
        final float fromBearing = shownBearing;
        final float bearingDelta = shortestAngle(fromBearing, toBearing);

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
        });
        animator.start();
    }

    private void applyFrame(double lat, double lon, float bearing) {
        shownLat = lat;
        shownLon = lon;
        shownBearing = bearing;
        hasPosition = true;
        GeoPoint p = new GeoPoint(lat, lon);
        marker.setPosition(p);
        if (following) {
            // Cap en haut : la carte tourne pour que la route devant la voiture soit toujours vers le haut.
            map.setMapOrientation(-bearing, false);
            map.getController().setCenter(p);
        }
        // Marqueur « plat » (setFlat(true)) : osmdroid le dessine dans le repère de la carte (déjà tournée),
        // en tournant de -rotation. Pour pointer vers le cap (degrés depuis le nord, sens horaire), la rotation
        // est donc simplement -cap, quelle que soit l'orientation de la carte. L'ancien calcul
        // -(cap + orientation) donnait 0 en mode « cap en haut » : la flèche pointait vers le nord de la carte,
        // pas dans le sens de marche.
        marker.setRotation(-bearing);
        if (!following) map.invalidate();
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
