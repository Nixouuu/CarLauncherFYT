package com.fyt.carlauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * Écran d'appel de CarLauncher. S'ouvre tout seul dès qu'un appel Bluetooth commence (entrant ou sortant)
 * et se ferme à la fin de l'appel.
 */
public class CallActivity extends Activity implements FytBt.Listener {

    private static boolean visible;
    private static boolean watching;
    /** Écran d'appel existant (même caché derrière l'accueil), pour le fermer à la fin de l'appel. */
    private static WeakReference<CallActivity> current;
    /** Dernier appel / état pour lequel l'écran a été ouvert : un état répété ne le rouvre pas. */
    private static int openedForCall = -1, openedForState = -1;

    /** À appeler une fois (au démarrage du launcher) : ouvre l'écran d'appel à chaque appel. */
    static void watchCalls(Context context) {
        if (watching) return;
        watching = true;
        final Context app = context.getApplicationContext();
        final FytBt bt = FytBt.get(app);
        bt.addListener(code -> {
            if (code != FytBt.U_PHONE_STATE) return;
            if (!bt.isInCall()) {
                // Appel terminé pendant que l'écran était caché (retour à l'accueil) : on le ferme quand même.
                CallActivity a = current != null ? current.get() : null;
                if (a != null && !visible && !a.isFinishing()) a.finish();
                return;
            }
            // On ouvre à chaque nouvel appel ou changement d'état (sonnerie → en ligne), pas à chaque
            // répétition du même état : sinon « retour à l'accueil » pendant un appel était aussitôt annulé.
            if (visible || (bt.callId == openedForCall && bt.phoneState == openedForState)) return;
            openedForCall = bt.callId;
            openedForState = bt.phoneState;
            Intent i = new Intent(app, CallActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            try {
                app.startActivity(i);
            } catch (Exception ignored) {
            }
        });
    }

    /** Première lettre d'un nom (emoji et lettres hors BMP comprises, sans couper un caractère en deux). */
    static String initial(String name) {
        if (TextUtils.isEmpty(name)) return "?";
        String s = name.trim();
        if (s.isEmpty()) return "?";
        return s.substring(0, s.offsetByCodePoints(0, 1)).toUpperCase(Locale.FRENCH);
    }

    /** Appel affiché actuellement : un autre numéro = nouvel appel, on vide le clavier de l'appel précédent. */
    private int shownCallId = -1;

    private FytBt bt;
    private ClickSound sound;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView tvAvatar, tvName, tvNumber, tvStatus, tvAudio;
    private View panelIncoming, panelActive, callButtons;
    private LinearLayout keypad;
    private ImageButton btnAudio;
    private final StringBuilder dtmf = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        setContentView(R.layout.activity_call);
        current = new WeakReference<>(this);
        bt = FytBt.get(this);
        sound = ClickSound.get(this);

        tvAvatar = findViewById(R.id.tvCallAvatar);
        tvName = findViewById(R.id.tvCallName);
        tvNumber = findViewById(R.id.tvCallNumber);
        tvStatus = findViewById(R.id.tvCallStatus);
        tvAudio = findViewById(R.id.tvCallAudio);
        panelIncoming = findViewById(R.id.panelIncoming);
        panelActive = findViewById(R.id.panelActive);
        callButtons = findViewById(R.id.callButtons);
        keypad = findViewById(R.id.callKeypad);
        btnAudio = findViewById(R.id.btnCallAudio);

        tap(R.id.btnAnswer, bt::answer);
        tap(R.id.btnReject, () -> {
            bt.reject();
            finishSoon();
        });
        tap(R.id.btnHangUp, () -> {
            bt.hangUp();
            finishSoon();
        });
        tap(R.id.btnCallAudio, bt::toggleAudio);
        tap(R.id.btnCallKeypad, () -> showKeypad(keypad.getVisibility() != View.VISIBLE));

        Keypad.build(this, keypad, 64, sound, key -> {
            bt.sendKey(key);
            dtmf.append(key);
            tvNumber.setText(dtmf);
        });
        keypad.setOnClickListener(v -> showKeypad(false));
    }

    private void tap(int id, Runnable action) {
        View v = findViewById(id);
        v.setOnClickListener(view -> {
            sound.play();
            action.run();
        });
    }

    private void showKeypad(boolean show) {
        keypad.setVisibility(show ? View.VISIBLE : View.GONE);
        callButtons.setVisibility(show ? View.GONE : View.VISIBLE);
        if (!show) render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        bt.addListener(this);
        render();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        bt.removeListener(this);
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onBackPressed() {
        // Retour = revenir à l'accueil sans raccrocher.
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        overridePendingTransition(0, 0);
    }

    @Override
    public void onFytUpdate(int code) {
        render();
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (current != null && current.get() == this) current = null;
        handler.removeCallbacksAndMessages(null);
    }

    private void render() {
        int state = bt.phoneState;
        if (!bt.isInCall() && state != -1) {
            finishSoon();
            return;
        }
        if (bt.callId != shownCallId) {
            // Nouvel appel dans un écran réutilisé : clavier fermé et chiffres de l'appel précédent effacés.
            shownCallId = bt.callId;
            dtmf.setLength(0);
            keypad.setVisibility(View.GONE);
            callButtons.setVisibility(View.VISIBLE);
        }
        String number = bt.callNumber;
        String name = contactName(number);
        tvName.setText(!TextUtils.isEmpty(name) ? name : (!TextUtils.isEmpty(number) ? number : getString(R.string.call_unknown)));
        if (keypad.getVisibility() != View.VISIBLE) {
            tvNumber.setText(!TextUtils.isEmpty(name) && !TextUtils.isEmpty(number) ? number : "");
        }
        tvAvatar.setText(initial(name));

        boolean ringing = state == FytBt.PHONE_RING;
        panelIncoming.setVisibility(ringing ? View.VISIBLE : View.GONE);
        panelActive.setVisibility(ringing ? View.GONE : View.VISIBLE);
        if (ringing) {
            tvStatus.setText(R.string.call_incoming);
        } else if (state == FytBt.PHONE_DIAL) {
            tvStatus.setText(R.string.call_dialing);
        } else {
            int s = bt.callSeconds();
            tvStatus.setText(String.format(Locale.FRENCH, "%d:%02d", s / 60, s % 60));
        }

        btnAudio.setImageResource(bt.audioOnPhone ? R.drawable.ic_car_audio : R.drawable.ic_smartphone);
        tvAudio.setText(bt.audioOnPhone ? R.string.call_audio_car : R.string.call_audio_phone);
    }

    private String contactName(String number) {
        if (TextUtils.isEmpty(number)) return null;
        String digits = number.replaceAll("\\D", "");
        if (digits.length() < 4) return null;
        String tail = digits.substring(Math.max(0, digits.length() - 10));
        for (FytBt.Contact c : bt.contacts.values()) {
            String d = c.number.replaceAll("\\D", "");
            if (d.length() < 4) continue;
            if (d.endsWith(tail) || tail.endsWith(d.substring(Math.max(0, d.length() - 10)))) return c.name;
        }
        return null;
    }

    private void finishSoon() {
        handler.removeCallbacks(ticker);
        handler.postDelayed(() -> {
            if (!bt.isInCall() && !isFinishing()) {
                finish();
                overridePendingTransition(0, 0);
            }
        }, 1200);
    }
}
