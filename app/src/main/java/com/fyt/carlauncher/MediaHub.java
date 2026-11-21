package com.fyt.carlauncher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Lecteur de l'écran d'accueil.
 *
 * - Affiche titre / artiste / pochette / progression de l'app qui joue (via les sessions média).
 * - Le bouton lecture montre l'état réel : ▶ quand c'est en pause, ❚❚ quand ça joue.
 * - Si rien ne joue, « lecture » démarre directement l'app musique choisie (Apple Music, Spotify,
 *   musique Bluetooth…) sans que l'utilisateur ait à l'ouvrir. Si l'app refuse de démarrer en
 *   arrière-plan, elle est ouverte, la lecture est lancée, puis on revient à l'accueil.
 */
final class MediaHub {

    static final String SLOT_MUSIC = "slot_music";
    private static final String[] MUSIC_CANDIDATES = {
            "com.apple.android.music",
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "deezer.android.app",
            "com.amazon.mp3",
    };

    private final Activity activity;
    private final SharedPreferences prefs;
    private final AudioManager audioManager;
    private final MediaSessionManager sessionManager;
    private final ComponentName listenerComponent;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Handler séparé : la séquence « ouvrir l'app → lecture → retour » doit survivre à onPause. */
    private final Handler launchHandler = new Handler(Looper.getMainLooper());

    private final TextView tvSource, tvTitle, tvArtist;
    private final ImageView imgArt;
    private final ImageButton btnPlay;
    private final ProgressBar progress;
    private final View btnAccess;

    private MediaController controller;
    private boolean started;
    private Boolean shownPlaying;
    /** Après un appui, on garde l'état affiché le temps que l'app réagisse (évite le clignotement). */
    private long holdStateUntil;

    MediaHub(Activity activity, SharedPreferences prefs, ComponentName listenerComponent) {
        this.activity = activity;
        this.prefs = prefs;
        this.listenerComponent = listenerComponent;
        this.audioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        this.sessionManager = (MediaSessionManager) activity.getSystemService(Context.MEDIA_SESSION_SERVICE);
        tvSource = activity.findViewById(R.id.tvMusicSource);
        tvTitle = activity.findViewById(R.id.tvTrackTitle);
        tvArtist = activity.findViewById(R.id.tvTrackArtist);
        imgArt = activity.findViewById(R.id.imgAlbumArt);
        btnPlay = activity.findViewById(R.id.btnPlayPause);
        progress = activity.findViewById(R.id.progressTrack);
        btnAccess = activity.findViewById(R.id.btnEnableMediaAccess);
    }

    // ---------------------------------------------------------------- app musique associée

    /** App musique choisie, sinon la première app connue installée, sinon null (Bluetooth / dernière app). */
    String musicPackage() {
        String pkg = prefs.getString(SLOT_MUSIC + MainActivity.KEY_PKG_SUFFIX, null);
        if (!TextUtils.isEmpty(pkg) && Apps.isInstalled(activity, pkg)) return pkg;
        for (String candidate : MUSIC_CANDIDATES) {
            if (Apps.isInstalled(activity, candidate)) return candidate;
        }
        return null;
    }

    // ---------------------------------------------------------------- cycle de vie

    void start() {
        started = true;
        if (!hasNotificationAccess()) {
            btnAccess.setVisibility(View.VISIBLE);
            bind(null);
        } else {
            btnAccess.setVisibility(View.GONE);
            try {
                sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent);
                bind(pickController(sessionManager.getActiveSessions(listenerComponent)));
            } catch (SecurityException e) {
                btnAccess.setVisibility(View.VISIBLE);
                bind(null);
            }
        }
        handler.post(ticker);
    }

    void stop() {
        started = false;
        handler.removeCallbacksAndMessages(null);
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
        } catch (Exception ignored) {
        }
        if (controller != null) {
            try {
                controller.unregisterCallback(callback);
            } catch (Exception ignored) {
            }
            controller = null;
        }
    }

    void openNotificationAccessSettings() {
        try {
            activity.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private boolean hasNotificationAccess() {
        String flat = Settings.Secure.getString(activity.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(activity.getPackageName());
    }

    // ---------------------------------------------------------------- sessions

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            controllers -> bind(pickController(controllers));

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            renderMetadata(metadata);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            renderPlaying(isPlaying(state));
            renderProgress();
        }

        @Override
        public void onSessionDestroyed() {
            refreshSessions();
        }
    };

    /** Priorité à la session qui joue ; sinon la plus récente. */
    private static MediaController pickController(List<MediaController> list) {
        if (list == null || list.isEmpty()) return null;
        for (MediaController c : list) {
            if (isPlaying(c.getPlaybackState())) return c;
        }
        return list.get(0);
    }

    private void refreshSessions() {
        if (!started) return;
        try {
            bind(pickController(sessionManager.getActiveSessions(listenerComponent)));
        } catch (Exception e) {
            bind(null);
        }
    }

    private void bind(MediaController next) {
        if (controller != null && next != null && controller.getSessionToken().equals(next.getSessionToken())) {
            return;
        }
        if (controller != null) {
            try {
                controller.unregisterCallback(callback);
            } catch (Exception ignored) {
            }
        }
        controller = next;
        if (controller != null) {
            controller.registerCallback(callback, handler);
            tvSource.setText(Apps.labelOf(activity, controller.getPackageName()));
            renderMetadata(controller.getMetadata());
            renderPlaying(isPlaying(controller.getPlaybackState()));
        } else {
            String pkg = musicPackage();
            tvSource.setText(pkg != null ? Apps.labelOf(activity, pkg) : activity.getString(R.string.music_title));
            tvTitle.setText(R.string.media_idle_title);
            tvArtist.setText(R.string.media_idle_artist);
            showPlaceholderArt();
            renderPlaying(audioManager != null && audioManager.isMusicActive());
        }
        renderProgress();
    }

    private static boolean isPlaying(PlaybackState state) {
        if (state == null) return false;
        int s = state.getState();
        return s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING
                || s == PlaybackState.STATE_CONNECTING;
    }

    // ---------------------------------------------------------------- rendu

    private void renderMetadata(MediaMetadata md) {
        if (md == null) return;
        String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(artist)) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);

        tvTitle.setText(!TextUtils.isEmpty(title) ? title : activity.getString(R.string.media_idle_title));
        tvArtist.setText(artist != null ? artist : "");
        if (art != null) {
            imgArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
            imgArt.setImageBitmap(art);
        } else {
            showPlaceholderArt();
        }
    }

    private void showPlaceholderArt() {
        imgArt.setScaleType(ImageView.ScaleType.CENTER);
        imgArt.setImageResource(R.drawable.ic_music_placeholder);
    }

    private void renderPlaying(boolean playing) {
        if (shownPlaying != null && shownPlaying == playing) return;
        shownPlaying = playing;
        btnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        btnPlay.setContentDescription(playing ? "Pause" : "Lecture");
    }

    private void renderProgress() {
        if (controller == null) {
            progress.setVisibility(View.INVISIBLE);
            return;
        }
        MediaMetadata md = controller.getMetadata();
        PlaybackState st = controller.getPlaybackState();
        long duration = md != null ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
        if (duration <= 0 || st == null || st.getPosition() < 0) {
            progress.setVisibility(View.INVISIBLE);
            return;
        }
        long pos = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        }
        progress.setVisibility(View.VISIBLE);
        progress.setProgress((int) (1000L * Math.max(0, Math.min(pos, duration)) / duration));
    }

    /** Toutes les secondes : progression + état réel quand aucune session n'est visible (Bluetooth). */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            if (controller == null && SystemClock.elapsedRealtime() > holdStateUntil) {
                renderPlaying(audioManager != null && audioManager.isMusicActive());
            } else if (!isPlaying(controller.getPlaybackState())) {
                refreshSessions();   // une autre app a peut-être pris le relais
            }
            renderProgress();
            handler.postDelayed(this, 1000);
        }
    };

    // ---------------------------------------------------------------- commandes

    void togglePlayPause() {
        holdStateUntil = SystemClock.elapsedRealtime() + 2500;
        if (controller != null) {
            MediaController.TransportControls tc = controller.getTransportControls();
            if (isPlaying(controller.getPlaybackState())) {
                tc.pause();
                renderPlaying(false);
            } else {
                tc.play();
                renderPlaying(true);
            }
            return;
        }
        if (audioManager != null && audioManager.isMusicActive()) {
            dispatchKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
            renderPlaying(false);
            return;
        }
        startMusic();
    }

    void previous() {
        if (controller != null) controller.getTransportControls().skipToPrevious();
        else dispatchKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    void next() {
        if (controller != null) controller.getTransportControls().skipToNext();
        else dispatchKey(KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    /** Démarre la musique sans que l'utilisateur ait à ouvrir l'app. */
    private void startMusic() {
        final String pkg = musicPackage();
        renderPlaying(true);
        if (pkg != null) {
            sendMediaButton(pkg, KeyEvent.KEYCODE_MEDIA_PLAY);
        } else {
            dispatchKey(KeyEvent.KEYCODE_MEDIA_PLAY);
        }
        holdStateUntil = SystemClock.elapsedRealtime() + 12000;
        launchHandler.removeCallbacksAndMessages(null);
        launchHandler.postDelayed(() -> {
            if (isSomethingPlaying()) return;
            if (pkg != null) {
                launchPlayAndReturn(pkg);
            } else {
                holdStateUntil = 0;
                renderPlaying(false);
                Toast.makeText(activity, R.string.music_no_app, Toast.LENGTH_LONG).show();
            }
        }, 1500);
    }

    /** Ouvre l'app, lance la lecture, puis revient à l'accueil dès que le son démarre (max 9 s). */
    private void launchPlayAndReturn(final String pkg) {
        final Context app = activity.getApplicationContext();
        Toast.makeText(app, app.getString(R.string.music_starting, Apps.labelOf(app, pkg)), Toast.LENGTH_SHORT).show();
        if (!Apps.launch(activity, pkg)) {
            renderPlaying(false);
            return;
        }
        final long startedAt = SystemClock.elapsedRealtime();
        launchHandler.postDelayed(new Runnable() {
            boolean playSent;

            @Override
            public void run() {
                long elapsed = SystemClock.elapsedRealtime() - startedAt;
                if (isSomethingPlaying()) {
                    Intent home = new Intent(app, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    app.startActivity(home);
                    return;
                }
                if (!playSent && elapsed >= 2000) {
                    playSent = true;
                    sendMediaButton(pkg, KeyEvent.KEYCODE_MEDIA_PLAY);
                    dispatchKey(KeyEvent.KEYCODE_MEDIA_PLAY);
                }
                if (elapsed < 9000) launchHandler.postDelayed(this, 500);
                else holdStateUntil = 0;
            }
        }, 1500);
    }

    private boolean isSomethingPlaying() {
        if (audioManager != null && audioManager.isMusicActive()) return true;
        try {
            List<MediaController> list = sessionManager.getActiveSessions(listenerComponent);
            for (MediaController c : list) if (isPlaying(c.getPlaybackState())) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    private void sendMediaButton(String pkg, int keyCode) {
        long t = SystemClock.uptimeMillis();
        for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg);
            i.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(t, t, action, keyCode, 0));
            try {
                activity.sendBroadcast(i);
            } catch (Exception ignored) {
            }
        }
    }

    private void dispatchKey(int keyCode) {
        if (audioManager == null) return;
        long t = SystemClock.uptimeMillis();
        audioManager.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0));
        audioManager.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0));
    }
}
