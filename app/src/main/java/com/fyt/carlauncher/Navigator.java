package com.fyt.carlauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.location.Location;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Polyline;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Navigation intégrée : itinéraire Stadia tracé sur la carte, bandeau de la prochaine manœuvre,
 * barre « arrivée / distance », guidage vocal en français, recalcul automatique si on sort du trajet.
 */
final class Navigator {

    /** Hors trajet au-delà de cette distance (élargie si le GPS annonce une mauvaise précision). */
    private static final float OFF_ROUTE_MIN_M = 30f, OFF_ROUTE_MAX_M = 60f;
    /** En dessous, la voiture est dessinée exactement sur le trajet (« collée à la route »). */
    private static final float SNAP_M = 35f;
    /** Écart de cap au-delà duquel on roule dans le mauvais sens du trajet. */
    private static final float WRONG_WAY_DEG = 120f;
    private static final float ARRIVED_M = 35f;
    private static final long REROUTE_MIN_INTERVAL_MS = 8000;

    private final Activity activity;
    private final MapView map;
    private final LiveMap liveMap;
    private final View banner, bottomBar;
    private final ImageView imgArrow;
    private final TextView tvDistance, tvInstruction, tvEta;
    private final AudioManager audioManager;
    private TextToSpeech tts;
    private boolean ttsReady;

    private StadiaApi.Route route;
    private double[] cumulative;          // distance cumulée (m) le long du trajet, par point
    private Polyline line, casing;
    private String destName;
    private double destLat, destLon;
    private int progressIndex;
    private int announcedAlertFor = -1, announcedPreFor = -1;
    private int offRouteCount;
    private long lastRerouteAt;
    private boolean active, routing;
    // Dernier « accrochage » au trajet : distance parcourue le long du trajet et validité.
    private double lastAlong;
    private boolean onRoute;
    private int reroutes;
    private String lastRouteError;
    private final Runnable stopAfterArrival = this::stop;

    Navigator(Activity activity, MapView map, LiveMap liveMap) {
        this.activity = activity;
        this.map = map;
        this.liveMap = liveMap;
        this.banner = activity.findViewById(R.id.navBanner);
        this.bottomBar = activity.findViewById(R.id.navBottomBar);
        this.imgArrow = activity.findViewById(R.id.imgNavArrow);
        this.tvDistance = activity.findViewById(R.id.tvNavDistance);
        this.tvInstruction = activity.findViewById(R.id.tvNavInstruction);
        this.tvEta = activity.findViewById(R.id.tvNavEta);
        this.audioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        ensureTts();
    }

    boolean isActive() {
        return active;
    }

    // ---------------------------------------------------------------- démarrage / arrêt

    void navigateTo(String name, double lat, double lon) {
        // Une arrivée précédente avait programmé un arrêt dans 6 s : il ne doit pas couper ce nouveau trajet.
        map.removeCallbacks(stopAfterArrival);
        destName = name;
        destLat = lat;
        destLon = lon;
        route = null;
        removeLines();
        onRoute = false;
        reroutes = 0;
        lastRouteError = null;
        ensureTts();
        Location here = liveMap.lastLocation();
        if (here == null) {
            Toast.makeText(activity, R.string.nav_no_gps, Toast.LENGTH_LONG).show();
            return;
        }
        active = true;
        banner.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
        imgArrow.setImageResource(R.drawable.ic_turn);
        imgArrow.setRotation(0);
        tvDistance.setText("");
        tvInstruction.setText(R.string.nav_computing);
        tvEta.setText(name);
        requestRoute(here, true);
    }

    void stop() {
        map.removeCallbacks(stopAfterArrival);
        active = false;
        routing = false;
        route = null;
        onRoute = false;
        removeLines();
        banner.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        liveMap.setNavigationMode(false);
        if (tts != null) tts.stop();
    }

    void release() {
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
    }

    /** Ouvre la même destination dans Waze (trafic en temps réel). */
    void openInWaze() {
        String uri = "https://waze.com/ul?ll=" + destLat + "," + destLon + "&navigate=yes";
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(activity, R.string.app_not_found, Toast.LENGTH_SHORT).show();
        }
    }

    static void openInWaze(Activity activity, double lat, double lon) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://waze.com/ul?ll=" + lat + "," + lon + "&navigate=yes"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(activity, R.string.app_not_found, Toast.LENGTH_SHORT).show();
        }
    }

    private void requestRoute(Location from, final boolean firstTime) {
        if (routing) return;
        routing = true;
        lastRerouteAt = SystemClock.elapsedRealtime();
        float heading = from.hasBearing() && from.getSpeed() > 2f ? from.getBearing() : -1f;
        StadiaApi.route(from.getLatitude(), from.getLongitude(), heading, destLat, destLon, (r, error) -> {
            routing = false;
            if (!active) return;
            if (r == null || r.shape.size() < 2) {
                // On garde l'ancien trajet s'il existe ; sinon onLocation() redemandera dans quelques secondes.
                boolean firstError = lastRouteError == null;   // dit une seule fois, pas à chaque nouvel essai
                lastRouteError = error != null ? error : "trajet vide";
                tvInstruction.setText(R.string.nav_route_error);
                if (firstTime && firstError) speak(activity.getString(R.string.nav_route_error));
                return;
            }
            lastRouteError = null;
            if (!firstTime) reroutes++;
            setRoute(r);
            if (firstTime) {
                speak(activity.getString(R.string.nav_start_spoken, destName));
                map.zoomToBoundingBox(BoundingBox.fromGeoPoints(r.shape).increaseByScale(1.25f), true);
                map.postDelayed(() -> { if (active) liveMap.setNavigationMode(true); }, 2500);
            } else {
                speak(activity.getString(R.string.nav_rerouted));
                liveMap.setNavigationMode(true);
            }
            Location here = liveMap.lastLocation();
            if (here != null) onLocation(here);
        });
    }

    private void setRoute(StadiaApi.Route r) {
        route = r;
        progressIndex = 0;
        offRouteCount = 0;
        announcedAlertFor = announcedPreFor = -1;
        cumulative = new double[r.shape.size()];
        for (int i = 1; i < r.shape.size(); i++) {
            cumulative[i] = cumulative[i - 1] + r.shape.get(i - 1).distanceToAsDouble(r.shape.get(i));
        }
        removeLines();
        casing = makeLine(r.shape, 0xFF0A4F9E, 16f);
        line = makeLine(r.shape, 0xFF0A84FF, 10f);
        // Sous le marqueur de la voiture (dernier overlay).
        map.getOverlays().add(0, line);
        map.getOverlays().add(0, casing);
        map.invalidate();
    }

    private Polyline makeLine(List<GeoPoint> pts, int color, float width) {
        Polyline p = new Polyline(map);
        p.setPoints(pts);
        Paint paint = p.getOutlinePaint();
        paint.setColor(color);
        paint.setStrokeWidth(width * activity.getResources().getDisplayMetrics().density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setAntiAlias(true);
        p.setInfoWindow(null);
        return p;
    }

    private void removeLines() {
        if (line != null) map.getOverlays().remove(line);
        if (casing != null) map.getOverlays().remove(casing);
        line = casing = null;
        map.invalidate();
    }

    // ---------------------------------------------------------------- guidage

    /** Appelé à chaque position GPS. */
    void onLocation(Location loc) {
        if (!active) return;
        if (route == null) {
            // Premier calcul raté (pas d'Internet, erreur Stadia…) : on réessaie tout seul.
            if (!routing && SystemClock.elapsedRealtime() - lastRerouteAt > REROUTE_MIN_INTERVAL_MS) {
                tvInstruction.setText(R.string.nav_computing);
                requestRoute(loc, true);
            }
            return;
        }
        List<GeoPoint> shape = route.shape;
        GeoPoint here = new GeoPoint(loc.getLatitude(), loc.getLongitude());
        boolean movingFast = loc.hasSpeed() && loc.getSpeed() > 4f;            // > ~15 km/h : cap GPS fiable
        float carBearing = movingFast && loc.hasBearing() ? loc.getBearing() : -1f;

        // Segment du trajet le plus proche, en préférant ceux orientés dans notre sens de marche
        // (sinon, sur un trajet qui revient sur ses pas, on s'accrocherait à la mauvaise branche).
        int[] seg = new int[1];
        double[] tOut = new double[1];
        double best = match(here, carBearing, Math.max(0, progressIndex - 10),
                Math.min(shape.size() - 2, progressIndex + 250), seg, tOut);
        if (best > SNAP_M) {
            // Rien autour de la progression : on cherche sur tout le trajet (saut GPS, raccourci…).
            int[] seg2 = new int[1];
            double[] t2 = new double[1];
            double best2 = match(here, carBearing, 0, shape.size() - 2, seg2, t2);
            if (best2 < best) {
                best = best2;
                seg[0] = seg2[0];
                tOut[0] = t2[0];
            }
        }
        int bestSeg = seg[0];
        double bestT = tOut[0];
        double distToRoute = project(here, shape.get(bestSeg), shape.get(bestSeg + 1))[0];
        boolean wrongWay = carBearing >= 0
                && angleDiff(carBearing, segmentBearing(bestSeg)) > WRONG_WAY_DEG;

        progressIndex = bestSeg;
        double along = cumulative[bestSeg] + bestT * (cumulative[bestSeg + 1] - cumulative[bestSeg]);
        double total = cumulative[cumulative.length - 1];
        double remaining = Math.max(0, total - along);
        lastAlong = along;
        onRoute = distToRoute <= SNAP_M && !wrongWay;

        // Hors trajet ou à contresens : 2 positions de suite suffisent, puis au plus un recalcul toutes les 8 s.
        float accuracy = loc.hasAccuracy() ? loc.getAccuracy() : 15f;
        double offThreshold = Math.max(OFF_ROUTE_MIN_M, Math.min(OFF_ROUTE_MAX_M, accuracy * 1.5 + 15));
        if (distToRoute > offThreshold || wrongWay) {
            offRouteCount++;
            if (offRouteCount >= 2 && !routing
                    && SystemClock.elapsedRealtime() - lastRerouteAt > REROUTE_MIN_INTERVAL_MS) {
                tvInstruction.setText(R.string.nav_computing);
                tvDistance.setText("");
                requestRoute(loc, false);
            }
            if (offRouteCount >= 2) return;   // consignes de l'ancien trajet fausses : on attend le nouveau
        } else {
            offRouteCount = 0;
        }

        // Arrivée
        if (remaining < ARRIVED_M || here.distanceToAsDouble(new GeoPoint(destLat, destLon)) < ARRIVED_M) {
            speak(activity.getString(R.string.nav_arrived));
            tvInstruction.setText(R.string.nav_arrived);
            tvDistance.setText("");
            imgArrow.setImageResource(R.drawable.ic_flag);
            imgArrow.setRotation(0);
            map.removeCallbacks(stopAfterArrival);
            map.postDelayed(stopAfterArrival, 6000);
            route = null;
            onRoute = false;
            active = false;   // plus de recalcul ni de nouvelle demande d'itinéraire après l'arrivée
            return;
        }

        // Prochaine manœuvre
        int next = -1;
        for (int i = 0; i < route.maneuvers.size(); i++) {
            if (route.maneuvers.get(i).beginIndex > bestSeg) {
                next = i;
                break;
            }
        }
        if (next < 0) next = route.maneuvers.size() - 1;
        StadiaApi.Maneuver m = route.maneuvers.get(next);
        int idx = Math.min(m.beginIndex, cumulative.length - 1);
        double toManeuver = Math.max(0, cumulative[idx] - along);

        tvInstruction.setText(m.instruction);
        tvDistance.setText(formatDistance(toManeuver));
        setArrow(m.type);

        // Voix : alerte à l'avance, puis consigne juste avant la manœuvre.
        float speed = loc.hasSpeed() ? loc.getSpeed() : 10f;
        double alertDist = Math.max(250, speed * 14);
        double preDist = Math.max(45, speed * 4.5);
        if (toManeuver <= preDist && announcedPreFor != next) {
            announcedPreFor = next;
            announcedAlertFor = next;
            speak(m.pre);
        } else if (toManeuver <= alertDist && toManeuver > preDist * 1.6 && announcedAlertFor != next
                && m.alert != null && !m.alert.isEmpty()) {
            announcedAlertFor = next;
            speak(activity.getString(R.string.nav_in_distance, spokenDistance(toManeuver), m.alert));
        }

        // Barre du bas : heure d'arrivée · durée · distance
        double ratio = total > 0 ? remaining / total : 0;
        long secs = Math.round(route.timeSec * ratio);
        String eta = new SimpleDateFormat("HH:mm", Locale.FRENCH).format(new Date(System.currentTimeMillis() + secs * 1000));
        tvEta.setText(activity.getString(R.string.nav_eta, eta, formatDuration(secs), formatDistance(remaining)));
    }

    /** Distance point→segment (m) et position relative t∈[0,1] sur le segment (projection plane locale). */
    private static double[] project(GeoPoint p, GeoPoint a, GeoPoint b) {
        double kx = 111320 * Math.cos(Math.toRadians(p.getLatitude()));
        double ky = 110540;
        double ax = (a.getLongitude() - p.getLongitude()) * kx, ay = (a.getLatitude() - p.getLatitude()) * ky;
        double bx = (b.getLongitude() - p.getLongitude()) * kx, by = (b.getLatitude() - p.getLatitude()) * ky;
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 > 0 ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2)) : 0;
        double cx = ax + t * dx, cy = ay + t * dy;
        return new double[]{Math.sqrt(cx * cx + cy * cy), t};
    }

    /**
     * Segment le plus proche entre from et to (inclus). Un segment orienté à contresens de la voiture
     * est pénalisé de 60 m pour qu'une branche du trajet dans notre sens gagne.
     * @return distance « pénalisée » ; seg[0] et t[0] reçoivent le segment et la position sur ce segment.
     */
    private double match(GeoPoint here, float carBearing, int from, int to, int[] seg, double[] t) {
        List<GeoPoint> shape = route.shape;
        double best = Double.MAX_VALUE;
        seg[0] = Math.max(0, Math.min(progressIndex, shape.size() - 2));
        t[0] = 0;
        for (int i = from; i <= to; i++) {
            double[] pr = project(here, shape.get(i), shape.get(i + 1));
            double d = pr[0];
            if (carBearing >= 0 && cumulative[i + 1] - cumulative[i] > 1
                    && angleDiff(carBearing, segmentBearing(i)) > 90) {
                d += 60;
            }
            if (d < best) {
                best = d;
                seg[0] = i;
                t[0] = pr[1];
            }
        }
        return best;
    }

    private float segmentBearing(int i) {
        List<GeoPoint> shape = route.shape;
        return (float) shape.get(i).bearingTo(shape.get(i + 1));
    }

    static float angleDiff(float a, float b) {
        float d = Math.abs(((a - b) % 360f + 540f) % 360f - 180f);
        return d;
    }

    /**
     * Position « collée au trajet » pour dessiner la voiture : point du trajet situé aheadM mètres après la
     * dernière position GPS projetée, avec le cap de la route à cet endroit.
     * @return {lat, lon, cap} ou null si on n'est pas sur le trajet (hors trajet, contresens, pas de guidage).
     */
    double[] snapped(double aheadM) {
        if (!active || route == null || !onRoute || cumulative == null) return null;
        double target = Math.min(lastAlong + Math.max(0, aheadM), cumulative[cumulative.length - 1]);
        int i = progressIndex;
        while (i < cumulative.length - 2 && cumulative[i + 1] < target) i++;
        while (i > 0 && cumulative[i] > target) i--;
        double len = cumulative[i + 1] - cumulative[i];
        double f = len > 0 ? Math.max(0, Math.min(1, (target - cumulative[i]) / len)) : 0;
        GeoPoint a = route.shape.get(i), b = route.shape.get(i + 1);
        // Segment très court (point en double) : on prend le cap du segment suivant non nul.
        int j = i;
        while (j < cumulative.length - 2 && cumulative[j + 1] - cumulative[j] < 1) j++;
        return new double[]{
                a.getLatitude() + (b.getLatitude() - a.getLatitude()) * f,
                a.getLongitude() + (b.getLongitude() - a.getLongitude()) * f,
                segmentBearing(j)};
    }

    /** État du guidage pour le diagnostic (sans position ni destination : le rapport est public). */
    String status() {
        return "Navigation : active=" + active + " calcul=" + routing
                + (route != null ? " points=" + route.shape.size() + " manœuvres=" + route.maneuvers.size()
                + " progression=" + progressIndex + " surTrajet=" + onRoute : " (pas de trajet)")
                + " horsTrajet=" + offRouteCount + " recalculs=" + reroutes
                + (lastRouteError != null ? " dernièreErreur=" + safeError(lastRouteError) : "");
    }

    private static String safeError(String e) {
        String s = e.replaceAll("api_key=[^&\\s\"]*", "api_key=…");
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    /** Flèche selon le type de manœuvre Valhalla. */
    private void setArrow(int type) {
        float rot;
        switch (type) {
            case 4: case 5: case 6:          // destination
                imgArrow.setImageResource(R.drawable.ic_flag);
                imgArrow.setRotation(0);
                return;
            case 9: case 18: case 20: case 23: case 37: rot = 45; break;     // légèrement à droite / sortie
            case 10: rot = 90; break;                                        // à droite
            case 11: rot = 135; break;                                       // serré à droite
            case 12: case 13: rot = 180; break;                              // demi-tour
            case 14: rot = -135; break;                                      // serré à gauche
            case 15: rot = -90; break;                                       // à gauche
            case 16: case 19: case 21: case 24: case 38: rot = -45; break;   // légèrement à gauche
            default: rot = 0; break;                                         // tout droit, rond-point, etc.
        }
        imgArrow.setImageResource(R.drawable.ic_nav_arrow);
        imgArrow.setRotation(rot);
    }

    static String formatDistance(double m) {
        if (m >= 10000) return String.format(Locale.FRENCH, "%.0f km", m / 1000);
        if (m >= 1000) return String.format(Locale.FRENCH, "%.1f km", m / 1000);
        if (m >= 100) return String.format(Locale.FRENCH, "%d m", (int) (Math.round(m / 50) * 50));
        return String.format(Locale.FRENCH, "%d m", (int) (Math.round(m / 10) * 10));
    }

    private static String spokenDistance(double m) {
        if (m >= 1000) return String.format(Locale.FRENCH, "%.1f kilomètre", m / 1000).replace(".", ",");
        return String.format(Locale.FRENCH, "%d mètres", (int) (Math.round(m / 50) * 50));
    }

    private static String formatDuration(long secs) {
        long min = Math.max(1, Math.round(secs / 60.0));
        if (min < 60) return min + " min";
        return String.format(Locale.FRENCH, "%d h %02d", min / 60, min % 60);
    }

    // ---------------------------------------------------------------- voix

    private static final String GOOGLE_TTS = "com.google.android.tts";
    private String voiceStatus = "voix : pas encore initialisée";
    private String pendingSpeech;

    /** État de la voix (affiché dans Diagnostic et au test). */
    String voiceStatus() {
        return voiceStatus;
    }

    /** Bouton « Tester la voix » des réglages. */
    void testVoice() {
        ensureTts();
        if (ttsReady) {
            speak(activity.getString(R.string.nav_voice_test), true);
        } else {
            pendingSpeech = activity.getString(R.string.nav_voice_test);
            Toast.makeText(activity, voiceStatus, Toast.LENGTH_LONG).show();
        }
    }

    /** Démarre la synthèse vocale tout de suite (pas au premier trajet) : elle est prête quand on en a besoin. */
    void ensureTts() {
        if (tts != null) return;
        // Moteur Google en priorité (meilleure voix française), sinon le moteur par défaut de l'autoradio.
        boolean google = Apps.isInstalledPackage(activity, GOOGLE_TTS);
        TextToSpeech.OnInitListener onInit = status -> {
            if (status != TextToSpeech.SUCCESS || tts == null) {
                voiceStatus = activity.getString(R.string.nav_voice_no_engine);
                return;
            }
            Locale[] wanted = {Locale.CANADA_FRENCH, Locale.FRENCH, new Locale("fr", "FR"), Locale.getDefault(), Locale.US};
            int r = TextToSpeech.LANG_NOT_SUPPORTED;
            Locale used = null;
            for (Locale l : wanted) {
                r = tts.setLanguage(l);
                if (r >= TextToSpeech.LANG_AVAILABLE) {
                    used = l;
                    break;
                }
            }
            ttsReady = used != null;
            voiceStatus = !ttsReady ? activity.getString(R.string.nav_voice_no_language)
                    : "fr".equals(used.getLanguage())
                    ? activity.getString(R.string.nav_voice_ok, tts.getDefaultEngine())
                    : activity.getString(R.string.nav_voice_not_french, used.getDisplayName(Locale.FRENCH));
            if (ttsReady && pendingSpeech != null) {
                String s = pendingSpeech;
                pendingSpeech = null;
                activity.runOnUiThread(() -> speak(s, true));
            }
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { abandonFocus(); }
                @Override public void onError(String id) { abandonFocus(); }
            });
        };
        Context app = activity.getApplicationContext();
        tts = google ? new TextToSpeech(app, onInit, GOOGLE_TTS) : new TextToSpeech(app, onInit);
    }

    private AudioFocusRequest focusRequest;

    static final String KEY_VOICE = "nav_voice";

    private void speak(String text) {
        speak(text, false);
    }

    private void speak(String text, boolean force) {
        if (text == null || text.isEmpty()) return;
        if (!force && !activity.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_VOICE, false)) return;   // voix désactivée par défaut (Réglages › Voix de navigation)
        if (!ttsReady || tts == null) {
            pendingSpeech = text;   // dite dès que la voix est prête
            ensureTts();
            return;
        }
        // Baisse la musique le temps de la consigne.
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .build();
            audioManager.requestAudioFocus(focusRequest);
        } else {
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        }
        Bundle params = new Bundle();
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "nav" + SystemClock.uptimeMillis());
    }

    private void abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        } else {
            audioManager.abandonAudioFocus(null);
        }
    }
}
