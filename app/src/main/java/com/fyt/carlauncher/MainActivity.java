package com.fyt.carlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ImageView;
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
    private static final String SLOT_STOCK_HOME = "slot_stock_home";

    private static final String KEY_PKG_SUFFIX = "_pkg";
    private static final String KEY_NAME_SUFFIX = "_name";
    private static final String DEFAULT_WAZE_PKG = "com.waze";

    private TextView tvClock;
    private TextView tvDate;
    private TextView tvFullDate;
    private TextView tvNavTitle;
    private TextView tvNavSub;
    private TextView tvPhoneSub;
    private TextView tvTrackTitle;
    private TextView tvTrackArtist;
    private TextView btnEnableMediaAccess;
    private TextView btnOtaUpdate;
    private ImageView imgAlbumArt;
    private View navBanner;
    private TextView tvNavInstruction;

    private String pendingUpdateUrl = null;
    private SharedPreferences prefs;
    private AudioManager audioManager;
    private MediaSessionManager mediaSessionManager;
    private MediaController activeController;
    private ComponentName listenerComponent;

    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm", Locale.FRENCH);
    private final SimpleDateFormat shortDateFormat = new SimpleDateFormat("d MMM", Locale.FRENCH);
    private final SimpleDateFormat fullDateFormat = new SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRENCH);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClockAndDate();
        }
    };

    private final BroadcastReceiver navReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String text = intent.getStringExtra(CarNotificationListenerService.EXTRA_NAV_TEXT);
            if (!TextUtils.isEmpty(text)) {
                tvNavInstruction.setText(text);
                navBanner.setVisibility(View.VISIBLE);
            } else {
                navBanner.setVisibility(View.GONE);
            }
        }
    };

    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            updateMediaMetadata(metadata);
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    bindActiveMediaController(controllers);
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        listenerComponent = new ComponentName(this, CarNotificationListenerService.class);

        bindViews();
        initDefaultWazeIfInstalled();
        setupActions();
        refreshShortcutLabels();
        checkOtaUpdate();
    }

    private void bindViews() {
        tvClock = findViewById(R.id.tvClock);
        tvDate = findViewById(R.id.tvDate);
        tvFullDate = findViewById(R.id.tvFullDate);
        tvNavTitle = findViewById(R.id.tvNavTitle);
        tvNavSub = findViewById(R.id.tvNavSub);
        tvPhoneSub = findViewById(R.id.tvPhoneSub);
        tvTrackTitle = findViewById(R.id.tvTrackTitle);
        tvTrackArtist = findViewById(R.id.tvTrackArtist);
        btnEnableMediaAccess = findViewById(R.id.btnEnableMediaAccess);
        btnOtaUpdate = findViewById(R.id.btnOtaUpdate);
        imgAlbumArt = findViewById(R.id.imgAlbumArt);
        navBanner = findViewById(R.id.navBanner);
        tvNavInstruction = findViewById(R.id.tvNavInstruction);
    }

    private void checkOtaUpdate() {
        OtaUpdater.checkForUpdate(this, executor, new OtaUpdater.UpdateCallback() {
            @Override
            public void onUpdateAvailable(String latestTag, String downloadUrl) {
                pendingUpdateUrl = downloadUrl;
                btnOtaUpdate.setVisibility(View.VISIBLE);
            }
        });
    }

    private void setupActions() {
        btnOtaUpdate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!TextUtils.isEmpty(pendingUpdateUrl)) {
                    OtaUpdater.downloadAndInstall(MainActivity.this, executor, pendingUpdateUrl);
                }
            }
        });

        findViewById(R.id.btnPrev).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { triggerMediaAction(KeyEvent.KEYCODE_MEDIA_PREVIOUS); }
        });
        findViewById(R.id.btnPlayPause).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { triggerMediaAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); }
        });
        findViewById(R.id.btnNext).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { triggerMediaAction(KeyEvent.KEYCODE_MEDIA_NEXT); }
        });

        btnEnableMediaAccess.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) {
                    openAndroidSettings();
                }
            }
        });

        setupShortcutView(R.id.dockNav, SLOT_NAV, getString(R.string.nav_title));
        setupShortcutView(R.id.cardNav, SLOT_NAV, getString(R.string.nav_title));
        setupShortcutView(R.id.dockPhone, SLOT_PHONE, getString(R.string.phone_title));
        setupShortcutView(R.id.cardPhone, SLOT_PHONE, getString(R.string.phone_title));

        findViewById(R.id.cardMusicPanel).setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                openAppPickerDialog(SLOT_MUSIC, getString(R.string.music_title));
                return true;
            }
        });
        findViewById(R.id.cardMusicPanel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String pkg = prefs.getString(SLOT_MUSIC + KEY_PKG_SUFFIX, null);
                if (!TextUtils.isEmpty(pkg)) {
                    launchAssignedApp(SLOT_MUSIC, getString(R.string.music_title));
                }
            }
        });

        View dockCarSystem = findViewById(R.id.dockCarSystem);
        dockCarSystem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String pkg = prefs.getString(SLOT_STOCK_HOME + KEY_PKG_SUFFIX, null);
                if (!TextUtils.isEmpty(pkg)) {
                    launchAssignedApp(SLOT_STOCK_HOME, getString(R.string.stock_home_title));
                } else {
                    openHomeLauncherChooser();
                }
            }
        });
        dockCarSystem.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                openAppPickerDialog(SLOT_STOCK_HOME, getString(R.string.stock_home_title));
                return true;
            }
        });

        findViewById(R.id.dockApps).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, AppDrawerActivity.class));
            }
        });

        findViewById(R.id.dockSettings).setOnClickListener(new View.OnClickListener() {
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
        setupMediaSessions();

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
        try { unregisterReceiver(timeReceiver); } catch (IllegalArgumentException ignored) {}
        try { unregisterReceiver(navReceiver); } catch (IllegalArgumentException ignored) {}
        teardownMediaSessions();
    }

    @Override
    public void onBackPressed() {}

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private boolean isNotificationAccessEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    private void setupMediaSessions() {
        if (!isNotificationAccessEnabled()) {
            btnEnableMediaAccess.setVisibility(View.VISIBLE);
            return;
        }
        btnEnableMediaAccess.setVisibility(View.GONE);
        if (mediaSessionManager == null) return;
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent);
            bindActiveMediaController(mediaSessionManager.getActiveSessions(listenerComponent));
        } catch (SecurityException e) {
            btnEnableMediaAccess.setVisibility(View.VISIBLE);
        }
    }

    private void teardownMediaSessions() {
        if (mediaSessionManager != null) {
            try { mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsListener); } catch (Exception ignored) {}
        }
        if (activeController != null) {
            try { activeController.unregisterCallback(mediaCallback); } catch (Exception ignored) {}
            activeController = null;
        }
    }

    private void bindActiveMediaController(List<MediaController> controllers) {
        if (activeController != null) {
            try { activeController.unregisterCallback(mediaCallback); } catch (Exception ignored) {}
            activeController = null;
        }
        if (controllers != null && !controllers.isEmpty()) {
            activeController = controllers.get(0);
            activeController.registerCallback(mediaCallback);
            updateMediaMetadata(activeController.getMetadata());
        } else {
            tvTrackTitle.setText(R.string.media_idle_title);
            tvTrackArtist.setText(R.string.media_idle_artist);
            imgAlbumArt.setImageResource(R.drawable.ic_music);
            imgAlbumArt.setScaleType(ImageView.ScaleType.CENTER);
        }
    }

    private void updateMediaMetadata(MediaMetadata metadata) {
        if (metadata == null) return;
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(artist)) artist = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
        Bitmap art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);

        tvTrackTitle.setText(!TextUtils.isEmpty(title) ? title : getString(R.string.media_idle_title));
        tvTrackArtist.setText(!TextUtils.isEmpty(artist) ? artist : "");

        if (art != null) {
            imgAlbumArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
            imgAlbumArt.setImageBitmap(art);
        } else {
            imgAlbumArt.setScaleType(ImageView.ScaleType.CENTER);
            imgAlbumArt.setImageResource(R.drawable.ic_music);
        }
    }

    private void triggerMediaAction(int keyCode) {
        if (activeController != null) {
            MediaController.TransportControls controls = activeController.getTransportControls();
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                controls.skipToPrevious();
                return;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
                controls.skipToNext();
                return;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                PlaybackState state = activeController.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    controls.pause();
                } else {
                    controls.play();
                }
                return;
            }
        }
        if (audioManager != null) {
            long eventTime = SystemClock.uptimeMillis();
            audioManager.dispatchMediaKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0));
            audioManager.dispatchMediaKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0));
        }
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
        tvDate.setText(shortDateFormat.format(now));
        String full = fullDateFormat.format(now);
        if (!full.isEmpty()) {
            full = full.substring(0, 1).toUpperCase(Locale.FRENCH) + full.substring(1);
        }
        tvFullDate.setText(full);
    }

    private void setupShortcutView(int viewId, final String slotKey, final String slotDisplayName) {
        View view = findViewById(viewId);
        view.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { launchAssignedApp(slotKey, slotDisplayName); }
        });
        view.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { openAppPickerDialog(slotKey, slotDisplayName); return true; }
        });
    }

    private void launchAssignedApp(String slotKey, String slotDisplayName) {
        String pkg = prefs.getString(slotKey + KEY_PKG_SUFFIX, null);
        if (TextUtils.isEmpty(pkg)) {
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
                    if (selfPackage.equals(pkg)) continue;
                    apps.add(new AppInfo(ri.loadLabel(pm).toString(), pkg, ri.loadIcon(pm)));
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
                        if (isFinishing() || isDestroyed() || !dialog.isShowing()) return;
                        listView.setAdapter(new AppListAdapter(MainActivity.this, apps, R.layout.item_app_picker));
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
        String phoneName = prefs.getString(SLOT_PHONE + KEY_NAME_SUFFIX, null);
        tvPhoneSub.setText(!TextUtils.isEmpty(phoneName) ? phoneName : getString(R.string.tap_to_assign));

        String navName = prefs.getString(SLOT_NAV + KEY_NAME_SUFFIX, null);
        if (!TextUtils.isEmpty(navName)) {
            tvNavTitle.setText(navName);
            tvNavSub.setText("Touchez pour ouvrir " + navName + " • Appui long pour changer");
        } else {
            tvNavTitle.setText("Waze");
            tvNavSub.setText(R.string.nav_idle_hint);
        }
    }

    private void openHomeLauncherChooser() {
        try {
            Intent intent = new Intent(Settings.ACTION_HOME_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            openAndroidSettings();
        }
    }

    private void openAndroidSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.launcher_settings_error, Toast.LENGTH_SHORT).show();
        }
    }
}
