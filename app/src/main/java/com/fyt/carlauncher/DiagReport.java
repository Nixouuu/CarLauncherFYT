package com.fyt.carlauncher;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Diagnostic complet de l'autoradio, envoyé tout seul sur GitHub (une « issue » du dépôt) :
 * pas de clé USB, pas de photo d'écran.
 *
 * Envois automatiques :
 *  - une fois par version installée, ~90 s après le démarrage (le temps que Bluetooth et GPS s'initialisent) ;
 *  - à la fin de chaque appel (au plus un toutes les 5 min), pour comprendre ce qui bloque pendant les appels.
 * Envoi manuel : bouton « Envoyer sur GitHub » de l'écran Diagnostic.
 *
 * Le dépôt est PUBLIC : le rapport ne contient ni numéro, ni nom de contact, ni nom de téléphone,
 * ni position GPS, ni adresse MAC / numéro de série.
 *
 * Jeton : secret GitHub DIAG_TOKEN (jeton « fine-grained » limité à ce dépôt, permission Issues seulement),
 * injecté au build dans BuildConfig.DIAG_TOKEN. Sans jeton, l'envoi est désactivé.
 */
final class DiagReport {

    private static final String ISSUES_API = "https://api.github.com/repos/Nixouuu/CarLauncherFYT/issues";
    private static final String KEY_SENT_VERSION = "diag_sent_version";
    private static final long AUTO_DELAY_MS = 90_000;
    private static final long RETRY_DELAY_MS = 5 * 60_000;
    private static final long MIN_CALL_REPORT_INTERVAL_MS = 5 * 60_000;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Sections fournies par l'écran d'accueil (GPS, navigation, voix) : lues sur le thread principal. */
    private static volatile Supplier<String> extra;
    private static boolean started;
    private static long lastCallReportAt;
    private static int autoRetries;

    interface Done {
        void onDone(boolean ok, String message);
    }

    private DiagReport() {}

    static boolean hasToken() {
        return !BuildConfig.DIAG_TOKEN.trim().isEmpty();
    }

    static void setExtra(Supplier<String> s) {
        extra = s;
    }

    /** À appeler une fois au démarrage du launcher. */
    static void start(Context context) {
        if (started) return;
        started = true;
        final Context app = context.getApplicationContext();
        if (!hasToken()) return;

        // 1) Une fois par version.
        MAIN.postDelayed(() -> autoSendForVersion(app), AUTO_DELAY_MS);

        // 2) À la fin de chaque appel.
        final FytBt bt = FytBt.get(app);
        final boolean[] wasInCall = {false};
        bt.addListener(code -> {
            if (code != FytBt.U_PHONE_STATE) return;
            boolean inCall = bt.isInCall();
            if (wasInCall[0] && !inCall
                    && SystemClock.elapsedRealtime() - lastCallReportAt > MIN_CALL_REPORT_INTERVAL_MS) {
                lastCallReportAt = SystemClock.elapsedRealtime();
                // Petit délai : les dernières mises à jour FYT de l'appel arrivent juste après.
                MAIN.postDelayed(() -> send(app, "fin d'appel", null), 3000);
            }
            wasInCall[0] = inCall;
        });
    }

    private static void autoSendForVersion(final Context app) {
        final int version = versionCode(app);
        SharedPreferences prefs = app.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getInt(KEY_SENT_VERSION, -1) == version) return;
        send(app, "démarrage", (ok, msg) -> {
            if (ok) {
                prefs.edit().putInt(KEY_SENT_VERSION, version).apply();
            } else if (++autoRetries <= 3) {
                MAIN.postDelayed(() -> autoSendForVersion(app), RETRY_DELAY_MS);   // pas encore d'Internet ?
            }
        });
    }

    /** Construit puis envoie le rapport. Doit être appelé sur le thread principal. */
    static void send(final Context context, final String reason, final Done done) {
        final Context app = context.getApplicationContext();
        if (!hasToken()) {
            if (done != null) done.onDone(false, app.getString(R.string.diag_no_token));
            return;
        }
        // Parties lues sur le thread principal (état FYT, GPS, navigation).
        final String fytPart = FytBt.get(app).dump(true);
        String extraPart;
        try {
            extraPart = extra != null ? extra.get() : "(écran d'accueil pas encore prêt)";
        } catch (Exception e) {
            extraPart = "erreur : " + e;
        }
        final String extraFinal = extraPart;
        EXEC.execute(() -> {
            String body;
            try {
                body = build(app, reason, fytPart, extraFinal);
            } catch (Throwable t) {
                body = "Erreur pendant la construction du rapport : " + t + "\n\n```\n" + fytPart + "\n```";
            }
            String title = "[Diagnostic] v" + versionName(app) + " · " + reason + " · "
                    + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.FRENCH).format(new Date())
                    + " · FYT " + FytBt.get(app).status();
            boolean ok;
            String message;
            try {
                int number = postIssue(title, body);
                ok = true;
                message = app.getString(R.string.diag_sent, number);
            } catch (Exception e) {
                ok = false;
                message = app.getString(R.string.diag_send_failed, String.valueOf(e.getMessage()));
            }
            final boolean fOk = ok;
            final String fMessage = message;
            MAIN.post(() -> {
                if (done != null) done.onDone(fOk, fMessage);
            });
        });
    }

    /** Envoi manuel (bouton) avec un message à l'écran. */
    static void sendWithToast(final Context context) {
        final Context app = context.getApplicationContext();
        if (!hasToken()) {
            Toast.makeText(app, R.string.diag_no_token, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(app, R.string.diag_sending, Toast.LENGTH_SHORT).show();
        send(app, "manuel", (ok, msg) -> Toast.makeText(app, msg, Toast.LENGTH_LONG).show());
    }

    // ---------------------------------------------------------------- rapport

    private static String build(Context app, String reason, String fytPart, String extraPart) {
        StringBuilder sb = new StringBuilder();
        sb.append("Rapport envoyé automatiquement par CarLauncher FYT (").append(reason).append(").\n\n");

        sb.append("## Appareil\n```\n");
        sb.append("Version app : ").append(versionName(app)).append(" (").append(versionCode(app)).append(")\n");
        sb.append("Fabricant/modèle : ").append(Build.MANUFACTURER).append(" / ").append(Build.MODEL)
                .append(" / ").append(Build.DEVICE).append('\n');
        sb.append("Carte/matériel : ").append(Build.BOARD).append(" / ").append(Build.HARDWARE).append('\n');
        sb.append("Produit : ").append(Build.PRODUCT).append('\n');
        sb.append("Build : ").append(Build.DISPLAY).append('\n');
        sb.append("Empreinte : ").append(Build.FINGERPRINT).append('\n');
        sb.append("Android : ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Allumé depuis : ").append(SystemClock.elapsedRealtime() / 60000).append(" min\n");
        sb.append("```\n\n");

        sb.append("## Service FYT (com.syu.ms) et Bluetooth de l'autoradio\n```\n").append(fytPart).append("```\n\n");

        sb.append("## Paquets FYT / Bluetooth / téléphone\n```\n").append(packages(app)).append("```\n\n");

        sb.append("## Applications d'appel\n```\n").append(callHandlers(app)).append("```\n\n");

        sb.append("## Audio et micro\n```\n").append(audio(app)).append("```\n\n");

        sb.append("## Bluetooth Android (pile standard)\n```\n").append(androidBluetooth()).append("```\n\n");

        sb.append("## Carte, GPS, navigation, voix\n```\n").append(extraPart).append("\n```\n\n");

        sb.append("## Propriétés système (filtrées)\n```\n").append(props()).append("```\n");

        // Limite GitHub : 65 536 caractères pour le corps d'une issue.
        if (sb.length() > 60000) {
            sb.setLength(60000);
            sb.append("\n… (tronqué)\n```\n");
        }
        return sb.toString();
    }

    private static String packages(Context app) {
        StringBuilder sb = new StringBuilder();
        PackageManager pm = app.getPackageManager();
        List<PackageInfo> all;
        try {
            all = pm.getInstalledPackages(0);
        } catch (Exception e) {
            return "getInstalledPackages : " + e + "\n";
        }
        for (PackageInfo p : all) {
            String n = p.packageName;
            if (!n.matches("(?i).*(syu|fyt|bt|blue|phone|dial|call|telecom|contacts|carplay|zlink|tlink|carlink|autolink|mic|voice|audio).*")) {
                continue;
            }
            boolean system = p.applicationInfo != null
                    && (p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            sb.append(n).append("  v").append(p.versionName).append(" (").append(p.versionCode).append(")")
                    .append(system ? " [système]" : "").append('\n');
            if (n.startsWith("com.syu") || n.startsWith("com.fyt") || n.contains("bt")) {
                try {
                    PackageInfo detail = pm.getPackageInfo(n, PackageManager.GET_SERVICES);
                    if (detail.services != null) {
                        for (ServiceInfo s : detail.services) {
                            sb.append("    service ").append(s.name)
                                    .append(s.exported ? " exporté" : " NON exporté")
                                    .append(s.permission != null ? " permission=" + s.permission : "")
                                    .append('\n');
                        }
                    }
                } catch (Exception e) {
                    sb.append("    services : ").append(e.getClass().getSimpleName()).append('\n');
                }
            }
        }
        if (sb.length() == 0) sb.append("aucun\n");
        return sb.toString();
    }

    private static String callHandlers(Context app) {
        StringBuilder sb = new StringBuilder();
        PackageManager pm = app.getPackageManager();
        Intent[] intents = {
                new Intent(Intent.ACTION_DIAL),
                new Intent(Intent.ACTION_DIAL, Uri.parse("tel:123")),
                new Intent(Intent.ACTION_CALL, Uri.parse("tel:123")),
                new Intent("android.intent.action.CALL_PRIVILEGED", Uri.parse("tel:123")),
        };
        for (Intent i : intents) {
            sb.append(i.getAction()).append(i.getData() != null ? " tel:" : "").append(" →");
            try {
                List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
                if (list.isEmpty()) sb.append(" aucune");
                for (ResolveInfo r : list) {
                    sb.append(' ').append(r.activityInfo.packageName).append('/').append(r.activityInfo.name);
                }
            } catch (Exception e) {
                sb.append(' ').append(e.getClass().getSimpleName());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String audio(Context app) {
        StringBuilder sb = new StringBuilder();
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return "AudioManager absent\n";
        try {
            sb.append("Mode : ").append(am.getMode())
                    .append(" (0=normal 1=sonnerie 2=appel 3=VoIP)\n");
            sb.append("Micro coupé : ").append(am.isMicrophoneMute()).append('\n');
            sb.append("SCO Bluetooth actif : ").append(am.isBluetoothScoOn())
                    .append(" / SCO hors appel possible : ").append(am.isBluetoothScoAvailableOffCall()).append('\n');
            sb.append("Haut-parleur : ").append(am.isSpeakerphoneOn()).append('\n');
            sb.append("Musique active : ").append(am.isMusicActive()).append('\n');
            int[] streams = {AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING,
                    AudioManager.STREAM_SYSTEM};
            String[] names = {"appel", "musique", "sonnerie", "système"};
            for (int i = 0; i < streams.length; i++) {
                sb.append("Volume ").append(names[i]).append(" : ").append(am.getStreamVolume(streams[i]))
                        .append('/').append(am.getStreamMaxVolume(streams[i])).append('\n');
            }
            sb.append("Fréquence de sortie : ").append(am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)).append('\n');
            if (Build.VERSION.SDK_INT >= 23) {
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_ALL)) {
                    sb.append(d.isSource() ? "Entrée " : "Sortie ").append(deviceType(d.getType()))
                            .append(" « ").append(d.getProductName()).append(" » id=").append(d.getId()).append('\n');
                }
            }
        } catch (Exception e) {
            sb.append("erreur : ").append(e).append('\n');
        }
        return sb.toString();
    }

    private static String deviceType(int t) {
        switch (t) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "micro intégré";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "haut-parleur intégré";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "Bluetooth SCO (appel)";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth A2DP (musique)";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "casque filaire (micro)";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "écouteurs filaires";
            case AudioDeviceInfo.TYPE_LINE_ANALOG: return "ligne analogique";
            case AudioDeviceInfo.TYPE_LINE_DIGITAL: return "ligne numérique";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "casque USB";
            case AudioDeviceInfo.TYPE_FM_TUNER: return "tuner FM";
            case AudioDeviceInfo.TYPE_TELEPHONY: return "téléphonie";
            case AudioDeviceInfo.TYPE_BUS: return "bus audio";
            default: return "type " + t;
        }
    }

    private static String androidBluetooth() {
        StringBuilder sb = new StringBuilder();
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null) return "Aucun adaptateur Bluetooth Android (normal sur FYT : puce gérée par com.syu.ms)\n";
            sb.append("Activé : ").append(a.isEnabled()).append(" état=").append(a.getState()).append('\n');
            java.util.Set<BluetoothDevice> bonded = a.getBondedDevices();
            sb.append("Appareils appairés : ").append(bonded == null ? 0 : bonded.size()).append('\n');
            int[] profiles = {BluetoothProfile.HEADSET, BluetoothProfile.A2DP, 16 /* HEADSET_CLIENT (HFP côté voiture) */,
                    11 /* A2DP_SINK */, 17 /* PBAP_CLIENT */};
            String[] names = {"HEADSET", "A2DP", "HFP_CLIENT", "A2DP_SINK", "PBAP_CLIENT"};
            for (int i = 0; i < profiles.length; i++) {
                sb.append("Profil ").append(names[i]).append(" : ");
                try {
                    sb.append(a.getProfileConnectionState(profiles[i]));
                } catch (Exception e) {
                    sb.append(e.getClass().getSimpleName());
                }
                sb.append('\n');
            }
        } catch (Exception e) {
            sb.append("erreur : ").append(e).append('\n');
        }
        return sb.toString();
    }

    /** getprop filtré : FYT, Bluetooth, audio, micro. Jamais de numéro de série, MAC ou IMEI. */
    private static String props() {
        StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("getprop");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n < 400) {
                String l = line.toLowerCase(Locale.ROOT);
                if (l.matches(".*(serial|mac|imei|addr|ssid|password|psk|token|key|gsm\\.|iccid|account|\\.name\\]).*")) {
                    continue;
                }
                if (l.matches(".*(fyt|syu|uis|unisoc|sprd|platform|bt|blue|audio|mic|hfp|sco|voice|codec|ro\\.build\\.|ro\\.product\\.|customer|mcu|car).*")) {
                    sb.append(line).append('\n');
                    n++;
                }
            }
        } catch (Exception e) {
            sb.append("getprop : ").append(e).append('\n');
        } finally {
            if (p != null) p.destroy();
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- GitHub

    private static int postIssue(String title, String body) throws Exception {
        JSONObject json = new JSONObject().put("title", title).put("body", body);
        HttpURLConnection c = (HttpURLConnection) new URL(ISSUES_API).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("Authorization", "Bearer " + BuildConfig.DIAG_TOKEN.trim());
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("User-Agent", "CarLauncherFYT");
            try (OutputStream os = c.getOutputStream()) {
                os.write(json.toString().getBytes("UTF-8"));
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            if (in != null) {
                try (InputStream is = in) {
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = is.read(b)) > 0) buf.write(b, 0, n);
                }
            }
            String text = buf.toString("UTF-8");
            if (code != 201) {
                throw new Exception("HTTP " + code + " " + (text.length() > 160 ? text.substring(0, 160) : text));
            }
            return new JSONObject(text).optInt("number", 0);
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- version

    private static int versionCode(Context app) {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String versionName(Context app) {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}
