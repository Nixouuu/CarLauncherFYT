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
import java.util.Date;
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
            if (navigator != null && navigator.isActive()) return;   // notre guidage a la priorité
            String text = intent.getStringExtra(CarNotificationListenerService.EXTRA_NAV_TEXT);
            if (!TextUtils.isEmpty(text)) {
                tvNavInstruction.setText(text);
                navBanner.setVisibility(View.VISIBLE);
            } else {
                navBanner.setVisibility(View.GONE);
            }
        }
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
        placeSearch = new PlaceSearch(this, prefs, sound, liveMap, navigator);

        fyt = FytBt.get(this);
        fyt.connect();
        CallActivity.watchCalls(this);
        fyt.addListener(code -> {
            if (code == FytBt.U_PHONE_NAME || code == FytBt.U_PHONE_STATE || code == -1) refreshShortcutLabels();
        });
        mediaHub = new MediaHub(this, prefs,
                new ComponentName(this, CarNotificationListenerService.class));

        initDefaultWaze();
        setupActions();
        refreshShortcutLabels();
        requestLocationIfNeeded();
        checkOtaUpdate();

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

    private void openLauncherSettings() {
        SettingsDialog.show(this, prefs, sound, new SettingsDialog.Actions() {
            @Override public void pickMusicApp() { openAppPicker(MediaHub.SLOT_MUSIC, getString(R.string.music_title)); }
            @Override public void openHomeChooser() { MainActivity.this.openHomeChooser(); }
            @Override public void openAndroidSettings() { MainActivity.this.openAndroidSettings(); }
            @Override public void showDiagnostics() { MainActivity.this.showDiagnostics(); }
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
        liveMap.stop();
        mediaHub.stop();
        try { unregisterReceiver(timeReceiver); } catch (IllegalArgumentException ignored) {}
        try { unregisterReceiver(navReceiver); } catch (IllegalArgumentException ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(packageReceiver); } catch (IllegalArgumentException ignored) {}
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

    private void checkOtaUpdate() {
        OtaUpdater.checkForUpdate(this, executor, (latestTag, downloadUrl) -> {
            pendingUpdateUrl = downloadUrl;
            btnOtaUpdate.setVisibility(View.VISIBLE);
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
        final ListView list = new ListView(this);
        list.setDivider(null);
        list.setSoundEffectsEnabled(false);
        final AlertDialog dialog = new AlertDialog.Builder(this, R.style.CarDialogTheme)
                .setTitle(getString(R.string.picker_title, slotName))
                .setView(list)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();

        Apps.get(this, apps -> {
            if (isFinishing() || isDestroyed() || !dialog.isShowing()) return;
            list.setAdapter(new AppListAdapter(this, apps, R.layout.item_app_picker));
            list.setOnItemClickListener((parent, view, position, id) -> {
                sound.play();
                AppInfo chosen = apps.get(position);
                prefs.edit()
                        .putString(slotKey + KEY_PKG_SUFFIX, chosen.packageName)
                        .putString(slotKey + KEY_NAME_SUFFIX, chosen.label)
                        .apply();
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

    /** Journal des échanges avec le Bluetooth FYT (pour déboguer appels et contacts sur l'autoradio). */
    private void showDiagnostics() {
        TextView text = new TextView(this);
        text.setText(fyt.dump());
        text.setTextColor(0xFFFFFFFF);
        text.setTextSize(12);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setTextIsSelectable(true);
        int pad = Keypad.dp(this, 16);
        text.setPadding(pad, pad, pad, pad);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(text);
        new AlertDialog.Builder(this, R.style.CarDialogTheme)
                .setTitle(R.string.settings_diagnostics)
                .setView(scroll)
                .setPositiveButton(R.string.settings_close, null)
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
