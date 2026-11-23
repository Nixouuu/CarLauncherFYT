package com.fyt.carlauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;

public class OtaUpdater {

    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/Nixouuu/CarLauncherFYT/releases/latest";

    public interface UpdateCallback {
        void onUpdateAvailable(String latestTag, String downloadUrl);

        /** Vérification impossible (pas d'Internet, GitHub indisponible) : à réessayer bientôt. */
        default void onCheckFailed() {}
    }

    /** Un seul téléchargement à la fois (plusieurs appuis sur « MÀJ » ouvraient plusieurs installations). */
    private static volatile boolean downloading;

    public static void checkForUpdate(final Context context, ExecutorService executor, final UpdateCallback callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                boolean checked = false;
                try {
                    PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                    int currentCode = pInfo.versionCode;

                    URL url = new URL(LATEST_RELEASE_API);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(4000);
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    if (conn.getResponseCode() != 200) return;

                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JSONObject json = new JSONObject(sb.toString());
                    checked = true;   // réponse valide de GitHub, même s'il n'y a pas de nouvelle version
                    final String tag = json.optString("tag_name", "");
                    int remoteCode = parseBuildNumber(tag);
                    if (remoteCode <= currentCode) return;

                    JSONArray assets = json.optJSONArray("assets");
                    if (assets == null) return;
                    String foundUrl = null;
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String name = asset.optString("name", "");
                        if (name.endsWith(".apk")) {
                            foundUrl = asset.optString("browser_download_url", null);
                            break;
                        }
                    }
                    if (foundUrl == null) return;

                    final String apkUrl = foundUrl;
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onUpdateAvailable(tag, apkUrl);
                        }
                    });
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) conn.disconnect();
                    // Dans finally : les « return » du bloc try (HTTP ≠ 200) passent aussi par ici.
                    if (!checked) new Handler(Looper.getMainLooper()).post(callback::onCheckFailed);
                }
            }
        });
    }

    public static void downloadAndInstall(Context activityContext, ExecutorService executor, final String apkUrl) {
        final Context context = activityContext.getApplicationContext();

        // Android 8+ : l'utilisateur doit autoriser CarLauncher à installer des applications.
        if (Build.VERSION.SDK_INT >= 26 && !context.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(context, R.string.update_allow_install, Toast.LENGTH_LONG).show();
            try {
                context.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
            return;
        }

        if (downloading) {
            Toast.makeText(context, R.string.update_downloading, Toast.LENGTH_SHORT).show();
            return;
        }
        downloading = true;
        Toast.makeText(context, R.string.update_downloading, Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            final Handler main = new Handler(Looper.getMainLooper());
            File outFile = new File(context.getCacheDir(), "update.apk");
            File partFile = new File(context.getCacheDir(), "update.apk.part");
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(apkUrl).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(20000);
                conn.setInstanceFollowRedirects(true);
                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new java.io.IOException("HTTP " + conn.getResponseCode());
                }
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(partFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (outFile.exists() && !outFile.delete()) throw new java.io.IOException("delete");
                if (!partFile.renameTo(outFile)) throw new java.io.IOException("rename");

                main.post(() -> {
                    Intent install = new Intent(Intent.ACTION_VIEW);
                    install.setDataAndType(ApkFileProvider.UPDATE_APK_URI,
                            "application/vnd.android.package-archive");
                    install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(install);
                });
            } catch (Exception e) {
                partFile.delete();
                main.post(() -> Toast.makeText(context, R.string.update_failed, Toast.LENGTH_SHORT).show());
            } finally {
                if (conn != null) conn.disconnect();
                downloading = false;
            }
        });
    }

    private static int parseBuildNumber(String tag) {
        try {
            int dot = tag.lastIndexOf('.');
            if (dot >= 0 && dot + 1 < tag.length()) {
                return Integer.parseInt(tag.substring(dot + 1).replaceAll("[^0-9]", ""));
            }
        } catch (Exception ignored) {}
        return 0;
    }
}
