package com.fyt.carlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.SeekBar;
import android.widget.Switch;

/** Réglages du launcher : son des touches (on/off, volume, adaptatif) + raccourcis système. */
final class SettingsDialog {

    interface Actions {
        void pickMusicApp();
        void openHomeChooser();
        void openAndroidSettings();
        void showDiagnostics();
        void testVoice();
    }

    private SettingsDialog() {}

    static AlertDialog show(Activity activity, final SharedPreferences prefs, final ClickSound sound, final Actions actions) {
        View root = activity.getLayoutInflater().inflate(R.layout.dialog_settings, null);
        final Switch swEnabled = root.findViewById(R.id.swSoundEnabled);
        final Switch swAdaptive = root.findViewById(R.id.swSoundAdaptive);
        final SeekBar seekVolume = root.findViewById(R.id.seekSoundVolume);
        final View rowVolume = root.findViewById(R.id.rowVolume);
        final View btnTest = root.findViewById(R.id.btnSoundTest);

        boolean enabled = prefs.getBoolean(ClickSound.KEY_ENABLED, true);
        swEnabled.setChecked(enabled);
        swAdaptive.setChecked(prefs.getBoolean(ClickSound.KEY_ADAPTIVE, true));
        seekVolume.setProgress(prefs.getInt(ClickSound.KEY_VOLUME, ClickSound.DEFAULT_VOLUME));
        setSoundControlsEnabled(enabled, rowVolume, seekVolume, btnTest, swAdaptive);

        swEnabled.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(ClickSound.KEY_ENABLED, checked).apply();
            setSoundControlsEnabled(checked, rowVolume, seekVolume, btnTest, swAdaptive);
            if (checked) sound.play();
        });
        swAdaptive.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(ClickSound.KEY_ADAPTIVE, checked).apply();
            sound.play();
        });
        seekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putInt(ClickSound.KEY_VOLUME, s.getProgress()).apply();
                sound.playAt(s.getProgress(), swAdaptive.isChecked());   // aperçu au relâchement
            }
        });
        btnTest.setOnClickListener(v -> sound.playAt(seekVolume.getProgress(), swAdaptive.isChecked()));

        final AlertDialog dialog = new AlertDialog.Builder(activity, R.style.CarDialogTheme)
                .setTitle(R.string.settings_title)
                .setView(root)
                .setPositiveButton(R.string.settings_close, null)
                .create();

        root.findViewById(R.id.btnMusicApp).setOnClickListener(v -> {
            sound.play();
            dialog.dismiss();
            actions.pickMusicApp();
        });
        root.findViewById(R.id.btnHomeApp).setOnClickListener(v -> {
            sound.play();
            dialog.dismiss();
            actions.openHomeChooser();
        });
        final Switch swNavVoice = root.findViewById(R.id.swNavVoice);
        swNavVoice.setChecked(prefs.getBoolean(Navigator.KEY_VOICE, false));
        swNavVoice.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(Navigator.KEY_VOICE, checked).apply();
            sound.play();
        });
        root.findViewById(R.id.btnVoiceTest).setOnClickListener(v -> {
            sound.play();
            actions.testVoice();
        });
        root.findViewById(R.id.btnDiagnostics).setOnClickListener(v -> {
            sound.play();
            dialog.dismiss();
            actions.showDiagnostics();
        });
        root.findViewById(R.id.btnAndroidSettings).setOnClickListener(v -> {
            sound.play();
            dialog.dismiss();
            actions.openAndroidSettings();
        });
        dialog.show();
        return dialog;
    }

    private static void setSoundControlsEnabled(boolean on, View row, SeekBar seek, View test, Switch adaptive) {
        row.setAlpha(on ? 1f : 0.4f);
        seek.setEnabled(on);
        test.setEnabled(on);
        adaptive.setEnabled(on);
        adaptive.setAlpha(on ? 1f : 0.4f);
    }
}
