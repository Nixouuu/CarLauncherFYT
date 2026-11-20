package com.fyt.carlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String PREFS_NAME = "car_launcher_prefs";

    private static final String SLOT_NAV = "slot_nav";
    private static final String SLOT_MUSIC = "slot_music";
    private static final String SLOT_PHONE = "slot_phone";

    private static final String KEY_PKG_SUFFIX = "_pkg";
    private static final String KEY_NAME_SUFFIX = "_name";

    private static final String DEFAULT_WAZE_PKG = "com.waze";

    private TextView tvClock;
    private TextView tvDate;
    private TextView tvNavSub;
    private TextView tvMusicSub;
    private TextView tvPhoneSub;

    private SharedPreferences prefs;
    private AudioManager audioManager;

    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm", Locale.FRENCH);
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRENCH);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClockAndDate();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        tvClock = findViewById(R.id.tvClock);
        tvDate = findViewById(R.id.tvDate);
        tvNavSub = findViewById(R.id.tvNavSub);
        tvMusicSub = findViewById(R.id.tvMusicSub);
        tvPhoneSub = findViewById(R.id.tvPhoneSub);

        initDefaultWazeIfInstalled();
        refreshShortcutLabels();

        // Contrôles audio Bluetooth directs via KeyEvent média
        findViewById(R.id.btnPrev).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            }
        });
        findViewById(R.id.btnPlayPause).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            }
        });
        findViewById(R.id.btnNext).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
            }
        });

        // 3 gros raccourcis : clic normal = lancer / appui long = choisir l'application
        setupShortcutCard(R.id.cardNav, SLOT_NAV, getString(R.string.nav_title));
        setupShortcutCard(R.id.cardMusic, SLOT_MUSIC, getString(R.string.music_title));
        setupShortcutCard(R.id.cardPhone, SLOT_PHONE, getString(R.string.phone_title));

        // Boutons discrets du bas
        findViewById(R.id.btnAllApps).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, AppDrawerActivity.class));
            }
        });

        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAndroidSettings();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateClockAndDate();
        refreshShortcutLabels();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_DATE_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(timeReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public void onBackPressed() {
        // Un Launcher ne quitte pas l'écran d'accueil sur le bouton Retour
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void initDefaultWazeIfInstalled() {
        if (!prefs.contains(SLOT_NAV + KEY_PKG_SUFFIX)) {
            if (getPackageManager().getLaunchIntentForPackage(DEFAULT_WAZE_PKG) != null) {
                prefs.edit()
                        .putString(SLOT_NAV + KEY_PKG_SUFFIX, DEFAULT_WAZE_PKG)
                        .putString(SLOT_NAV + KEY_NAME_SUFFIX, "Waze")
                        .apply();
            }
        }
    }

    private void updateClockAndDate() {
        Date now = new Date();
        tvClock.setText(clockFormat.format(now));
        String rawDate = dateFormat.format(now);
        if (!rawDate.isEmpty()) {
            rawDate = rawDate.substring(0, 1).toUpperCase(Locale.FRENCH) + rawDate.substring(1);
        }
        tvDate.setText(rawDate);
    }

    private void sendMediaKey(int keyCode) {
        if (audioManager == null) {
            return;
        }
        long eventTime = SystemClock.uptimeMillis();
        KeyEvent downEvent = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0);
        KeyEvent upEvent = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0);
        audioManager.dispatchMediaKeyEvent(downEvent);
        audioManager.dispatchMediaKeyEvent(upEvent);
    }

    private void setupShortcutCard(int viewId, final String slotKey, final String slotDisplayName) {
        View card = findViewById(viewId);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchAssignedApp(slotKey, slotDisplayName);
            }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                openAppPickerDialog(slotKey, slotDisplayName);
                return true;
            }
        });
    }

    private void launchAssignedApp(String slotKey, String slotDisplayName) {
        String pkg = prefs.getString(slotKey + KEY_PKG_SUFFIX, null);
        if (pkg == null || pkg.isEmpty()) {
            Toast.makeText(this, R.string.no_app_assigned, Toast.LENGTH_SHORT).show();
            openAppPickerDialog(slotKey, slotDisplayName);
            return;
        }

        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launchIntent);
        } else {
            Toast.makeText(this, R.string.app_not_found, Toast.LENGTH_SHORT).show();
            openAppPickerDialog(slotKey, slotDisplayName);
        }
    }

    private void openAppPickerDialog(final String slotKey, final String slotDisplayName) {
        final ListView listView = new ListView(this);
        listView.setDividerHeight(1);

        final AlertDialog dialog = new AlertDialog.Builder(this, R.style.CarDialogTheme)
                .setTitle(getString(R.string.picker_title, slotDisplayName))
                .setView(listView)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();

        executor.execute(new Runnable() {
            @Override
            public void run() {
                PackageManager pm = getPackageManager();
                Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
                mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

                List<ResolveInfo> resolveInfos = pm.queryIntentActivities(mainIntent, 0);
                final List<AppInfo> apps = new ArrayList<>();
                String selfPackage = getPackageName();

                for (ResolveInfo ri : resolveInfos) {
                    String pkg = ri.activityInfo.packageName;
                    if (selfPackage.equals(pkg)) {
                        continue;
                    }
                    String label = ri.loadLabel(pm).toString();
                    apps.add(new AppInfo(label, pkg, ri.loadIcon(pm)));
                }

                Collections.sort(apps, new Comparator<AppInfo>() {
                    @Override
                    public int compare(AppInfo a, AppInfo b) {
                        return a.label.compareToIgnoreCase(b.label);
                    }
                });

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed() || !dialog.isShowing()) {
                            return;
                        }
                        AppListAdapter adapter = new AppListAdapter(
                                MainActivity.this,
                                apps,
                                R.layout.item_app_picker
                        );
                        listView.setAdapter(adapter);
                        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                            @Override
                            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                                AppInfo chosen = apps.get(position);
                                prefs.edit()
                                        .putString(slotKey + KEY_PKG_SUFFIX, chosen.packageName)
                                        .putString(slotKey + KEY_NAME_SUFFIX, chosen.label)
                                        .apply();
                                refreshShortcutLabels();
                                Toast.makeText(
                                        MainActivity.this,
                                        getString(R.string.app_assigned, chosen.label, slotDisplayName),
                                        Toast.LENGTH_SHORT
                                ).show();
                                dialog.dismiss();
                            }
                        });
                    }
                });
            }
        });
    }

    private void refreshShortcutLabels() {
        updateSingleLabel(tvNavSub, SLOT_NAV);
        updateSingleLabel(tvMusicSub, SLOT_MUSIC);
        updateSingleLabel(tvPhoneSub, SLOT_PHONE);
    }

    private void updateSingleLabel(TextView target, String slotKey) {
        String name = prefs.getString(slotKey + KEY_NAME_SUFFIX, null);
        if (name != null && !name.isEmpty()) {
            target.setText(name);
        } else {
            target.setText(R.string.tap_to_assign);
        }
    }

    private void openAndroidSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.settings_error, Toast.LENGTH_SHORT).show();
        }
    }
}
