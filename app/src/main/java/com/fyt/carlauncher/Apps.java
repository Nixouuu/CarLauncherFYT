package com.fyt.carlauncher;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Liste des applications installées chargée une seule fois en arrière-plan puis gardée en mémoire :
 * le tiroir d'apps et le sélecteur s'ouvrent instantanément. Rechargée quand une app est
 * installée / désinstallée.
 */
final class Apps {

    interface Listener {
        void onApps(List<AppInfo> apps);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> pending = new ArrayList<>();

    private static List<AppInfo> cached;
    private static boolean loading;
    private static boolean dirty;

    private Apps() {}

    /** Appelé sur le thread principal. Le listener est appelé sur le thread principal. */
    static void get(Context context, Listener listener) {
        if (cached != null) {
            listener.onApps(cached);
            return;
        }
        pending.add(listener);
        load(context.getApplicationContext());
    }

    static void preload(Context context) {
        if (cached == null) load(context.getApplicationContext());
    }

    /** À appeler quand la liste des paquets change. Garde l'ancienne liste jusqu'au rechargement. */
    static void refresh(Context context) {
        if (loading) {
            dirty = true;
            return;
        }
        load(context.getApplicationContext());
    }

    private static void load(final Context app) {
        if (loading) return;
        loading = true;
        EXECUTOR.execute(() -> {
            final List<AppInfo> apps = query(app);
            MAIN.post(() -> {
                cached = apps;
                loading = false;
                List<Listener> toNotify = new ArrayList<>(pending);
                pending.clear();
                for (Listener l : toNotify) l.onApps(apps);
                if (dirty) {
                    dirty = false;
                    load(app);
                }
            });
        });
    }

    private static List<AppInfo> query(Context app) {
        PackageManager pm = app.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);
        List<AppInfo> apps = new ArrayList<>(infos.size());
        String self = app.getPackageName();
        for (ResolveInfo ri : infos) {
            String pkg = ri.activityInfo.packageName;
            if (self.equals(pkg)) continue;
            try {
                apps.add(new AppInfo(ri.loadLabel(pm).toString(), pkg, ri.loadIcon(pm)));
            } catch (Exception ignored) {
            }
        }
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return apps;
    }

    static boolean isInstalled(Context context, String pkg) {
        return pkg != null && context.getPackageManager().getLaunchIntentForPackage(pkg) != null;
    }

    /** Paquet installé, même sans icône de lancement (ex. moteur de synthèse vocale). */
    static boolean isInstalledPackage(Context context, String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String labelOf(Context context, String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** Lance une app sans animation de transition (ouverture instantanée). */
    static boolean launch(Context context, String pkg) {
        if (pkg == null) return false;
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent, ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
