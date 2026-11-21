package com.fyt.carlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * Son de toucher doux (petit « tock » feutré, pas un bip), synthétisé une seule fois en mémoire
 * et rejoué instantanément via un AudioTrack statique.
 *
 * Volume final = volume choisi par l'utilisateur × facteur adaptatif :
 *  - musique forte  → le son est atténué (il ne doit pas agresser) ;
 *  - musique faible → le son est remonté (il doit rester audible).
 * Le son passe par le flux musique pour suivre le même chemin audio que la radio/musique.
 */
final class ClickSound {

    static final String KEY_ENABLED = "sound_enabled";
    static final String KEY_VOLUME = "sound_volume";      // 0..100
    static final String KEY_ADAPTIVE = "sound_adaptive";
    static final int DEFAULT_VOLUME = 60;

    private static final int SAMPLE_RATE = 44100;
    private static ClickSound instance;

    private final Context appContext;
    private final AudioManager audioManager;
    private final SharedPreferences prefs;
    private AudioTrack track;

    static synchronized ClickSound get(Context context) {
        if (instance == null) instance = new ClickSound(context.getApplicationContext());
        return instance;
    }

    private ClickSound(Context app) {
        appContext = app;
        audioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        prefs = app.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        try {
            short[] pcm = synthesize();
            track = new AudioTrack(
                    new AudioAttributes.Builder()
                            // Flux « système », indépendant du volume de la musique : le son garde le même
                            // niveau que la musique soit à 1 ou à 100 (le volume musique sert seulement à
                            // couper le son quand il est à 0).
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    pcm.length * 2,
                    AudioTrack.MODE_STATIC,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            track.write(pcm, 0, pcm.length);
        } catch (Exception e) {
            track = null;
        }
    }

    /** Joue le son de toucher si activé. */
    void play() {
        if (!prefs.getBoolean(KEY_ENABLED, true)) return;
        playAt(prefs.getInt(KEY_VOLUME, DEFAULT_VOLUME), prefs.getBoolean(KEY_ADAPTIVE, true));
    }

    /** Joue le son avec un volume donné (utilisé par le bouton « Tester » des réglages). */
    void playAt(int volumePercent, boolean adaptive) {
        if (track == null || track.getState() != AudioTrack.STATE_INITIALIZED) return;
        float user = Math.max(0, Math.min(100, volumePercent)) / 100f;
        float gain = user * user;                     // courbe perceptuelle
        float factor = adaptiveFactor();
        if (factor <= 0f) return;                     // volume à 0 ou sourdine : silence, comme un téléphone
        if (adaptive) gain *= factor;
        if (gain <= 0.001f) return;
        try {
            if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.stop();
            track.reloadStaticData();
            track.setVolume(Math.min(1f, gain));
            track.play();
        } catch (Exception ignored) {
        }
    }

    /**
     * Comme sur un téléphone : volume à 0 (ou sourdine) → aucun son ; sinon toujours le même niveau.
     *  - Volume Android : le son joue sur le flux système, donc le volume musique ne le change pas → 1.
     *  - Autoradio FYT : son volume (le « 25 » de la barre) agit sur l'amplificateur, après Android, donc
     *    il amplifie aussi le son des touches. On compense : son remonté quand le volume est bas,
     *    baissé quand il est haut, pour garder à peu près le même niveau perçu.
     * Retourne 0 quand il ne faut rien jouer.
     */
    private float adaptiveFactor() {
        if (audioManager != null && audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) return 0f;
        FytBt bt = FytBt.get(appContext);
        if (bt.volume < 0) return 1f;
        if (bt.muted || bt.volume == 0) return 0f;
        float ratio = bt.volume / (float) Math.max(1, bt.volumeMax);
        // 1/40 → 1.0 ; 25 % → ~0.35 ; 100 % → 0.12
        return Math.max(0.12f, Math.min(1f, 0.12f / ratio * 0.95f + 0.05f));
    }

    /**
     * « Tock » feutré de 55 ms : un partiel aigu très court (attaque nette) + un partiel grave
     * légèrement plus long (corps), enveloppe exponentielle, attaque adoucie pour éviter les clics.
     */
    private static short[] synthesize() {
        int n = (int) (SAMPLE_RATE * 0.055);
        float[] buf = new float[n];
        float peak = 0f;
        for (int i = 0; i < n; i++) {
            double t = i / (double) SAMPLE_RATE;
            double attack = Math.min(1.0, t / 0.0015);
            double high = Math.sin(2 * Math.PI * 1350 * t) * Math.exp(-t / 0.008);
            double low = Math.sin(2 * Math.PI * 420 * t) * Math.exp(-t / 0.016);
            double tail = 1.0 - Math.max(0, (t - 0.045) / 0.010);   // fondu final
            float v = (float) ((0.45 * high + 0.55 * low) * attack * tail);
            buf[i] = v;
            peak = Math.max(peak, Math.abs(v));
        }
        short[] pcm = new short[n];
        float norm = peak > 0 ? 0.85f / peak : 0f;
        for (int i = 0; i < n; i++) pcm[i] = (short) (buf[i] * norm * Short.MAX_VALUE);
        return pcm;
    }
}
