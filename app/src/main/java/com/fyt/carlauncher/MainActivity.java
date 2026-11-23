package com.fyt.carlauncher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.views.MapView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static final String PREFS_NAME = "car_launcher_prefs";
    static final String KEY_PKG_SUFFIX = "_pkg";
    static final String KEY_NAME_SUFFIX = "_name";
    private static final int REQ_LOCATION = 1001;

    private static final String SLOT_NAV = "slot_nav";
    private static final String SLOT_PHONE = "slot_phone";
    private static final String SLOT_STOCK_HOME = "slot_stock_home";
    private static final String DEFAULT_WAZE_PKG = "com.waze";

    private TextView tvClock, tvDay, tvDate, tvPhoneSub, tvNavInstruction;
    private View navBanner, btnOtaUpdate;
    private String pendingUpdateUrl;

    private SharedPreferences prefs;
    private ClickSound sound;
    private LiveMap liveMap;
    private MediaHub mediaHub;
    private Navigator navigator;
    private PlaceSearch placeSearch;
    private FytBt fyt;

    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm", Locale.FRENCH);
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("EEEE", Locale.FRENCH);
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("d MMM", Locale.FRENCH);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClock();
        }
    };

    private final BroadcastReceiver navReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            showWazeInstruction(intent.getStringExtra(CarNotificationListenerService.EXTRA_NAV_TEXT));
        }
    };

    /** Consigne Waze / Google Maps (notification) dans le bandeau, sauf pendant notre propre guidage. */
    private void showWazeInstruction(String text) {
        if (navigator != null && navigator.isActive()) return;   // notre guidage a la priorité
        if (!TextUtils.isEmpty(text)) {
            tvNavInstruction.setText(text);
            navBanner.setVisibility(View.VISIBLE);
        } else {
            navBanner.setVisibility(View.GONE);
        }
    }

    /** Retiré dans onDestroy : FytBt est un singleton, il ne doit pas garder l'Activity en vie. */
    private final FytBt.Listener fytListener = code -> {
        if (code == FytBt.U_PHONE_NAME || code == FytBt.U_PHONE_STATE || code == -1) refreshShortcutLabels();
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Apps.refresh(context);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureOsmdroid();
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sound = ClickSound.get(this);
        Apps.preload(this);

        tvClock = findViewById(R.id.tvClock);
        tvDay = findViewById(R.id.tvDay);
        tvDate = findViewById(R.id.tvDate);
        tvPhoneSub = findViewById(R.id.tvPhoneSub);
        tvNavInstruction = findViewById(R.id.tvNavInstruction);
        navBanner = findViewById(R.id.navBanner);
        btnOtaUpdate = findViewById(R.id.btnOtaUpdate);

        liveMap = new LiveMap(this, (MapView) findViewById(R.id.mapView),
                (TextView) findViewById(R.id.tvSpeed), findViewById(R.id.btnRecenter));
        liveMap.setAttributionView((TextView) findViewById(R.id.tvMapAttribution));
        liveMap.setup();
        navigator = new Navigator(this, liveMap.map(), liveMap);
        liveMap.setLocationObserver(navigator::onLocation);
        liveMap.setSnapper(navigator::snapped);   // en guidage, la voiture est collée au tracé
        placeSearch = new PlaceSearch(this, prefs, sound, liveMap, navigator);

        fyt = FytBt.get(this);
        fyt.connect();
        CallActivity.watchCalls(this);
        fyt.addListener(fytListener);
        DiagReport.setExtra(() -> liveMap.stats() + "\n" + navigator.status() + "\n" + navigator.voiceStatus());
        DiagReport.start(this);
        mediaHub = new MediaHub(this, prefs,
                new ComponentName(this, CarNotificationListenerService.class));

        initDefaultWaze();
        setupActions();
        refreshShortcutLabels();
        requestLocationIfNeeded();
        navigator.restoreSaved();   // guidage interrompu par une fermeture du launcher : on le reprend
        ui.post(otaTick);

        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgFilter.addDataScheme("package");
        registerReceiver(packageReceiver, pkgFilter);
    }

    private void configureOsmdroid() {
        IConfigurationProvider cfg = Configuration.getInstance();
        cfg.load(getApplicationContext(), PreferenceManager.getDefaultSharedPreferences(getApplicationContext()));
        String version = "dev";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        cfg.setUserAgentValue("CarLauncherFYT/" + version);
        File base = new File(getCacheDir(), "osmdroid");
        cfg.setOsmdroidBasePath(base);
        cfg.setOsmdroidTileCache(new File(base, "tiles"));
        cfg.setTileDownloadThreads((short) 2);   // politique d’usage des tuiles OSM : 2 connexions max
        cfg.setTileFileSystemCacheMaxBytes(300L * 1024 * 1024);
        cfg.setTileFileSystemCacheTrimBytes(250L * 1024 * 1024);
    }

    // ---------------------------------------------------------------- actions

    /** Clic avec son doux (et sans le son système). */
    private void onTap(int id, final Runnable action) {
        View v = findViewById(id);
        v.setSoundEffectsEnabled(false);
        v.setOnClickListener(view -> {
            sound.play();
            action.run();
        });
    }

    private void onLongTap(int id, final Runnable action) {
        View v = findViewById(id);
        v.setHapticFeedbackEnabled(true);
        v.setOnLongClickListener(view -> {
            sound.play();
            action.run();
            return true;
        });
    }

    private void setupActions() {
        String nav = getString(R.string.nav_title);
        String music = getString(R.string.music_title);
        String phone = getString(R.string.phone_title);
        String stock = getString(R.string.stock_home_title);

        // Lecteur
        onTap(R.id.btnPlayPause, mediaHub::togglePlayPause);
        onTap(R.id.btnPrev, mediaHub::previous);
        onTap(R.id.btnNext, mediaHub::next);
        onTap(R.id.btnEnableMediaAccess, mediaHub::openNotificationAccessSettings);
        onTap(R.id.cardMusicPanel, this::openMusicApp);
        onLongTap(R.id.cardMusicPanel, () -> openAppPicker(MediaHub.SLOT_MUSIC, music));

        // Dock
        onTap(R.id.dockNav, placeSearch::open);                         // navigation intégrée
        onLongTap(R.id.dockNav, () -> launchSlot(SLOT_NAV, nav));        // appui long : Waze
        onTap(R.id.dockMusic, this::openMusicApp);
        onLongTap(R.id.dockMusic, () -> openAppPicker(MediaHub.SLOT_MUSIC, music));
        onTap(R.id.dockPhone, this::openPhone);
        onLongTap(R.id.dockPhone, () -> launchSlot(SLOT_PHONE, phone));
        onTap(R.id.dockApps, () -> {
            startActivity(new Intent(this, AppDrawerActivity.class));
            overridePendingTransition(0, 0);
        });
        onTap(R.id.dockCarSystem, () -> {
            if (!TextUtils.isEmpty(prefs.getString(SLOT_STOCK_HOME + KEY_PKG_SUFFIX, null))) {
                launchSlot(SLOT_STOCK_HOME, stock);
            } else {
                openHomeChooser();
            }
        });
        onLongTap(R.id.dockCarSystem, () -> openAppPicker(SLOT_STOCK_HOME, stock));
        onTap(R.id.dockSettings, this::openLauncherSettings);
        onLongTap(R.id.dockSettings, this::openAndroidSettings);

        // Carte
        onTap(R.id.btnRecenter, liveMap::recenter);
        onTap(R.id.btnSearchPlace, placeSearch::open);
        onTap(R.id.btnNavWaze, navigator::openInWaze);
        onTap(R.id.btnNavStop, navigator::stop);

        // Téléphone
        onTap(R.id.cardPhone, this::openPhone);
        onLongTap(R.id.cardPhone, () -> launchSlot(SLOT_PHONE, phone));

        // Mise à jour
        onTap(R.id.btnOtaUpdate, () -> {
            if (!TextUtils.isEmpty(pendingUpdateUrl)) {
                OtaUpdater.downloadAndInstall(this, executor, pendingUpdateUrl);
            }
        });
    }

    private void openPhone() {
        startActivity(new Intent(this, PhoneActivity.class));
        overridePendingTransition(0, 0);
    }

    private void openMusicApp() {
        String pkg = mediaHub.musicPackage();
        if (pkg == null && fyt.isPhoneConnected()) {
            mediaHub.togglePlayPause();   // musique Bluetooth : on reste dans le launcher
            return;
        }
        if (pkg == null || !Apps.launch(this, pkg)) {
            openAppPicker(MediaHub.SLOT_MUSIC, getString(R.string.music_title));
        }
    }

    /** Une seule fenêtre à la fois : un double appui rapide n'en empile plus deux. */
    private AlertDialog openDialog;

    private boolean dialogOpen() {
        return openDialog != null && openDialog.isShowing();
    }

    private void openLauncherSettings() {
        if (dialogOpen()) return;
        openDialog = SettingsDialog.show(this, prefs, sound, new SettingsDialog.Actions() {
            @Override public void pickMusicApp() { openAppPicker(MediaHub.SLOT_MUSIC, getString(R.string.music_title)); }
            @Override public void openHomeChooser() { MainActivity.this.openHomeChooser(); }
            @Override public void openAndroidSettings() { MainActivity.this.openAndroidSettings(); }
            @Override public void showDiagnostics() { MainActivity.this.showDiagnostics(); }
            @Override public void testVoice() { navigator.testVoice(); }
        });
    }

    // ---------------------------------------------------------------- cycle de vie

    @Override
    protected void onResume() {
        super.onResume();
        updateClock();
        refreshShortcutLabels();
        liveMap.start();
        mediaHub.start();
        checkOtaUpdate();
        // Consigne Waze arrivée ou retirée pendant que l'accueil était caché (récepteur alors désinscrit).
        showWazeInstruction(CarNotificationListenerService.lastNavText);

        IntentFilter timeFilter = new IntentFilter();
        timeFilter.addAction(Intent.ACTION_TIME_TICK);
        timeFilter.addAction(Intent.ACTION_TIME_CHANGED);
        timeFilter.addAction(Intent.ACTION_DATE_CHANGED);
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, timeFilter);

        IntentFilter navFilter = new IntentFilter(CarNotificationListenerService.ACTION_NAV_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(navReceiver, navFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(navReceiver, navFilter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Pendant un guidage, le GPS reste actif quand un autre écran passe devant (appel, téléphone, autre app).
        liveMap.stop(navigator.isActive());
        mediaHub.stop();
        try { unregisterReceiver(timeReceiver); } catch (IllegalArgumentException ignored) {}
        try { unregisterReceiver(navReceiver); } catch (IllegalArgumentException ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (liveMap != null) liveMap.stop(false);
        if (openDialog != null && openDialog.isShowing()) openDialog.dismiss();
        try { unregisterReceiver(packageReceiver); } catch (IllegalArgumentException ignored) {}
        if (fyt != null) fyt.removeListener(fytListener);
        DiagReport.setExtra(null);
        if (navigator != null) navigator.release();
        executor.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        // Launcher : le bouton retour ne quitte pas l'accueil ; il ferme seulement la recherche.
        if (placeSearch != null && placeSearch.isOpen()) placeSearch.close();
    }

    // ---------------------------------------------------------------- permissions / OTA

    private void requestLocationIfNeeded() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQ_LOCATION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) liveMap.onPermissionResult();
    }

    private static final long OTA_CHECK_INTERVAL_MS = 30 * 60_000;
    /** Après un échec (pas encore d'Internet au démarrage de la voiture), nouvel essai 2 min plus tard. */
    private static final long OTA_RETRY_MS = 2 * 60_000;
    private long lastOtaCheckAt;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Vérifie la mise à jour toutes les minutes au plus (même si on ne quitte jamais l'accueil). */
    private final Runnable otaTick = new Runnable() {
        @Override
        public void run() {
            checkOtaUpdate();
            ui.postDelayed(this, 60_000);
        }
    };

    /**
     * Le launcher (accueil, singleTask) n'est presque jamais recréé : on revérifie donc régulièrement,
     * au plus une fois toutes les 30 min après une vérification réussie, toutes les 2 min après un échec.
     */
    private void checkOtaUpdate() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastOtaCheckAt != 0 && now - lastOtaCheckAt < OTA_CHECK_INTERVAL_MS) return;
        lastOtaCheckAt = now;
        OtaUpdater.checkForUpdate(this, executor, new OtaUpdater.UpdateCallback() {
            @Override
            public void onUpdateAvailable(String latestTag, String downloadUrl) {
                if (isFinishing() || isDestroyed()) return;
                pendingUpdateUrl = downloadUrl;
                btnOtaUpdate.setVisibility(View.VISIBLE);
            }

            @Override
            public void onCheckFailed() {
                // Prochain essai dans OTA_RETRY_MS au lieu de 30 min.
                lastOtaCheckAt = android.os.SystemClock.elapsedRealtime() - OTA_CHECK_INTERVAL_MS + OTA_RETRY_MS;
                if (lastOtaCheckAt == 0) lastOtaCheckAt = -1;
            }
        });
    }

    // ---------------------------------------------------------------- raccourcis

    private void initDefaultWaze() {
        if (!prefs.contains(SLOT_NAV + KEY_PKG_SUFFIX) && Apps.isInstalled(this, DEFAULT_WAZE_PKG)) {
            prefs.edit()
                    .putString(SLOT_NAV + KEY_PKG_SUFFIX, DEFAULT_WAZE_PKG)
                    .putString(SLOT_NAV + KEY_NAME_SUFFIX, "Waze")
                    .apply();
        }
    }

    private void updateClock() {
        Date now = new Date();
        tvClock.setText(clockFormat.format(now));
        tvDay.setText(dayFormat.format(now));
        tvDate.setText(dateFormat.format(now));
    }

    private void launchSlot(String slotKey, String slotName) {
        String pkg = prefs.getString(slotKey + KEY_PKG_SUFFIX, null);
        if (TextUtils.isEmpty(pkg)) {
            Toast.makeText(this, R.string.no_app_assigned, Toast.LENGTH_SHORT).show();
            openAppPicker(slotKey, slotName);
        } else if (!Apps.launch(this, pkg)) {
            Toast.makeText(this, R.string.app_not_found, Toast.LENGTH_SHORT).show();
            openAppPicker(slotKey, slotName);
        }
    }

    private void openAppPicker(final String slotKey, final String slotName) {
        if (dialogOpen()) return;
        final ListView list = new ListView(this);
        list.setDivider(null);
        list.setSoundEffectsEnabled(false);
        final AlertDialog dialog = new AlertDialog.Builder(this, R.style.CarDialogTheme)
                .setTitle(getString(R.string.picker_title, slotName))
                .setView(list)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        openDialog = dialog;
        dialog.show();

        final boolean musicSlot = MediaHub.SLOT_MUSIC.equals(slotKey);
        Apps.get(this, all -> {
            if (isFinishing() || isDestroyed() || !dialog.isShowing()) return;
            // Musique : première ligne « Musique Bluetooth » pour revenir à l'iPhone sans app associée.
            final List<AppInfo> apps = new ArrayList<>(all.size() + 1);
            if (musicSlot) apps.add(new AppInfo(getString(R.string.music_bt), "", getDrawable(R.drawable.ic_smartphone)));
            apps.addAll(all);
            list.setAdapter(new AppListAdapter(this, apps, R.layout.item_app_picker));
            list.setOnItemClickListener((parent, view, position, id) -> {
                sound.play();
                AppInfo chosen = apps.get(position);
                if (TextUtils.isEmpty(chosen.packageName)) {
                    prefs.edit()
                            .remove(slotKey + KEY_PKG_SUFFIX)
                            .remove(slotKey + KEY_NAME_SUFFIX)
                            .putBoolean(MediaHub.KEY_MUSIC_BT_ONLY, true)
                            .apply();
                } else {
                    SharedPreferences.Editor e = prefs.edit()
                            .putString(slotKey + KEY_PKG_SUFFIX, chosen.packageName)
                            .putString(slotKey + KEY_NAME_SUFFIX, chosen.label);
                    if (musicSlot) e.remove(MediaHub.KEY_MUSIC_BT_ONLY);
                    e.apply();
                }
                refreshShortcutLabels();
                Toast.makeText(this, getString(R.string.app_assigned, chosen.label, slotName),
                        Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
        });
    }

    private void refreshShortcutLabels() {
        if (fyt != null && fyt.isPhoneConnected()) {
            tvPhoneSub.setText(fyt.phoneName != null ? fyt.phoneName : getString(R.string.phone_connected));
        } else if (fyt != null && fyt.isReady()) {
            tvPhoneSub.setText(R.string.phone_not_connected_short);
        } else {
            tvPhoneSub.setText(R.string.phone_keypad_contacts);
        }
    }

    /**
     * Journal des échanges avec le Bluetooth FYT (pour déboguer appels et contacts sur l'autoradio).
     * « Envoyer sur GitHub » envoie le rapport complet (anonymisé) sans clé USB ni photo.
     */
    private void showDiagnostics() {
        if (dialogOpen()) return;
        TextView text = new TextView(this);
        text.setText(navigator.voiceStatus() + "\n" + liveMap.stats() + "\n" + fyt.dump());
        text.setTextColor(0xFFFFFFFF);
        text.setTextSize(12);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setTextIsSelectable(true);
        int pad = Keypad.dp(this, 16);
        text.setPadding(pad, pad, pad, pad);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(text);
        openDialog = new AlertDialog.Builder(this, R.style.CarDialogTheme)
                .setTitle(R.string.settings_diagnostics)
                .setView(scroll)
                .setPositiveButton(R.string.settings_close, null)
                .setNegativeButton(R.string.diag_send, (d, w) -> DiagReport.sendWithToast(this))
                .setNeutralButton(R.string.phone_sync, (d, w) -> fyt.downloadContacts())
                .show();
    }

    private void openHomeChooser() {
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            openAndroidSettings();
        }
    }

    private void openAndroidSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, R.string.launcher_settings_error, Toast.LENGTH_SHORT).show();
        }
    }
}
