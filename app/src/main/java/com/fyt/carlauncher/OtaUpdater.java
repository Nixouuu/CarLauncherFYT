package com.fyt.carlauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
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
    }

    public static void checkForUpdate(final Context context, ExecutorService executor, final UpdateCallback callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
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
                }
            }
        });
    }

    public static void downloadAndInstall(final Context context, ExecutorService executor, final String apkUrl) {
        Toast.makeText(context, R.string.update_downloading, Toast.LENGTH_SHORT).show();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(apkUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(20000);
                    conn.setInstanceFollowRedirects(true);

                    File outFile = new File(context.getCacheDir(), "update.apk");
                    if (outFile.exists()) outFile.delete();

                    InputStream in = conn.getInputStream();
                    FileOutputStream out = new FileOutputStream(outFile);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.flush();
                    out.close();
                    in.close();

                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            Intent install = new Intent(Intent.ACTION_VIEW);
                            install.setDataAndType(ApkFileProvider.UPDATE_APK_URI,
                                    "application/vnd.android.package-archive");
                            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                            context.startActivity(install);
                        }
                    });
                } catch (Exception e) {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(context, R.string.update_failed, Toast.LENGTH_SHORT).show();
                        }
                    });
                } finally {
                    if (conn != null) conn.disconnect();
                }
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
