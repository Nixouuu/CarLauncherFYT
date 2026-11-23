package com.fyt.carlauncher;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.util.GeoPoint;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client minimal des services Stadia Maps (clé dans BuildConfig, injectée par le CI depuis le secret
 * STADIA_API_KEY) : recherche d'adresse (autocomplete) et itinéraire voiture avec instructions en français.
 */
final class StadiaApi {

    static final String KEY = BuildConfig.STADIA_API_KEY;

    interface Callback<T> {
        void onResult(T result, String error);
    }

    static final class Place {
        final String name, label;
        final double lat, lon;
        final double distanceKm;

        Place(String name, String label, double lat, double lon, double distanceKm) {
            this.name = name;
            this.label = label;
            this.lat = lat;
            this.lon = lon;
            this.distanceKm = distanceKm;
        }
    }

    static final class Maneuver {
        final int type;
        final String instruction, alert, pre;
        final int beginIndex;

        Maneuver(int type, String instruction, String alert, String pre, int beginIndex) {
            this.type = type;
            this.instruction = instruction;
            this.alert = alert;
            this.pre = pre;
            this.beginIndex = beginIndex;
        }
    }

    static final class Route {
        final List<GeoPoint> shape;
        final List<Maneuver> maneuvers;
        final double lengthKm, timeSec;

        Route(List<GeoPoint> shape, List<Maneuver> maneuvers, double lengthKm, double timeSec) {
            this.shape = shape;
            this.maneuvers = maneuvers;
            this.lengthKm = lengthKm;
            this.timeSec = timeSec;
        }
    }

    /**
     * Files séparées : avec un réseau faible, une recherche lente (jusqu'à 20 s) ne retarde plus le calcul
     * de l'itinéraire ni un recalcul en conduisant.
     */
    private static final ExecutorService SEARCH_EXEC = Executors.newSingleThreadExecutor();
    private static final ExecutorService ROUTE_EXEC = Executors.newSingleThreadExecutor();
    /** Numéro de la dernière recherche : les recherches dépassées en attente ne sont pas envoyées. */
    private static final AtomicInteger searchGen = new AtomicInteger();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private StadiaApi() {}

    static boolean hasKey() {
        return KEY != null && !KEY.trim().isEmpty();
    }

    // ---------------------------------------------------------------- recherche

    static void autocomplete(final String text, final double lat, final double lon, final Callback<List<Place>> cb) {
        final int gen = searchGen.incrementAndGet();
        SEARCH_EXEC.execute(() -> {
            if (gen != searchGen.get()) return;   // une recherche plus récente a été tapée entre-temps
            try {
                String url = "https://api.stadiamaps.com/geocoding/v1/autocomplete?text=" + URLEncoder.encode(text, "UTF-8")
                        + "&focus.point.lat=" + lat + "&focus.point.lon=" + lon
                        + "&lang=fr&size=8&api_key=" + KEY;
                JSONObject json = new JSONObject(http(url, null));
                JSONArray features = json.optJSONArray("features");
                List<Place> out = new ArrayList<>();
                for (int i = 0; features != null && i < features.length(); i++) {
                    JSONObject f = features.getJSONObject(i);
                    JSONArray c = f.getJSONObject("geometry").getJSONArray("coordinates");
                    JSONObject p = f.getJSONObject("properties");
                    String label = p.optString("label", "");
                    String name = p.optString("name", label);
                    out.add(new Place(name, label, c.getDouble(1), c.getDouble(0), p.optDouble("distance", -1)));
                }
                post(cb, out, null);
            } catch (Exception e) {
                post(cb, null, e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------- itinéraire

    /** @param heading cap actuel en degrés (ou -1) : évite un demi-tour inutile au départ. */
    static void route(final double fromLat, final double fromLon, final float heading,
                      final double toLat, final double toLon, final Callback<Route> cb) {
        ROUTE_EXEC.execute(() -> {
            try {
                JSONObject start = new JSONObject().put("lat", fromLat).put("lon", fromLon);
                if (heading >= 0) start.put("heading", Math.round(heading)).put("heading_tolerance", 45);
                JSONObject body = new JSONObject()
                        .put("locations", new JSONArray().put(start)
                                .put(new JSONObject().put("lat", toLat).put("lon", toLon)))
                        .put("costing", "auto")
                        .put("directions_options", new JSONObject().put("units", "kilometers").put("language", "fr-FR"));
                JSONObject trip = new JSONObject(http("https://api.stadiamaps.com/route/v1?api_key=" + KEY, body.toString()))
                        .getJSONObject("trip");
                List<GeoPoint> shape = new ArrayList<>();
                List<Maneuver> maneuvers = new ArrayList<>();
                JSONArray legs = trip.getJSONArray("legs");
                for (int l = 0; l < legs.length(); l++) {
                    JSONObject leg = legs.getJSONObject(l);
                    int offset = shape.size();
                    shape.addAll(decodePolyline6(leg.getString("shape")));
                    JSONArray ms = leg.getJSONArray("maneuvers");
                    for (int i = 0; i < ms.length(); i++) {
                        JSONObject m = ms.getJSONObject(i);
                        maneuvers.add(new Maneuver(m.optInt("type"),
                                m.optString("instruction", ""),
                                m.optString("verbal_transition_alert_instruction", ""),
                                m.optString("verbal_pre_transition_instruction", m.optString("instruction", "")),
                                offset + m.optInt("begin_shape_index")));
                    }
                }
                JSONObject summary = trip.getJSONObject("summary");
                post(cb, new Route(shape, maneuvers, summary.optDouble("length"), summary.optDouble("time")), null);
            } catch (Exception e) {
                post(cb, null, e.getMessage());
            }
        });
    }

    /** Polyline encodée avec une précision de 6 décimales (format Valhalla). */
    static List<GeoPoint> decodePolyline6(String enc) {
        List<GeoPoint> pts = new ArrayList<>();
        int index = 0, lat = 0, lon = 0;
        while (index < enc.length()) {
            int[] r = decodeValue(enc, index);
            lat += r[0];
            index = r[1];
            r = decodeValue(enc, index);
            lon += r[0];
            index = r[1];
            pts.add(new GeoPoint(lat / 1e6, lon / 1e6));
        }
        return pts;
    }

    private static int[] decodeValue(String enc, int index) {
        int result = 0, shift = 0, b;
        do {
            b = enc.charAt(index++) - 63;
            result |= (b & 0x1f) << shift;
            shift += 5;
        } while (b >= 0x20);
        return new int[]{(result & 1) != 0 ? ~(result >> 1) : (result >> 1), index};
    }

    // ---------------------------------------------------------------- tuiles

    /** Vérifie que la clé est acceptée pour les tuiles (sinon la carte retombe sur OpenStreetMap). */
    static void checkTiles(final Callback<Boolean> cb) {
        ROUTE_EXEC.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL("https://tiles.stadiamaps.com/tiles/alidade_smooth_dark/1/0/0.png?api_key=" + KEY)
                        .openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                int code = c.getResponseCode();
                // Hors ligne : on garde Stadia (le cache fonctionne). Seul un refus explicite de la clé fait basculer.
                post(cb, !(code == 401 || code == 403), null);
            } catch (Exception e) {
                post(cb, true, e.getMessage());
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    // ---------------------------------------------------------------- HTTP

    private static String http(String url, String jsonBody) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("Accept", "application/json");
            if (jsonBody != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(jsonBody.getBytes("UTF-8"));
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            if (in != null) {
                try (InputStream is = in) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = is.read(b)) > 0) buf.write(b, 0, n);
                }
            }
            String text = buf.toString("UTF-8");
            if (code >= 400) throw new Exception(String.format(Locale.ROOT, "HTTP %d %s", code, text));
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static <T> void post(final Callback<T> cb, final T result, final String error) {
        MAIN.post(() -> cb.onResult(result, error));
    }
}
