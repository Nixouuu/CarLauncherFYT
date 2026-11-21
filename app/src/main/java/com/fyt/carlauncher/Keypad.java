package com.fyt.carlauncher;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Pavé numérique rond style iPhone, construit en code (utilisé par l'écran Téléphone et l'écran d'appel). */
final class Keypad {

    interface OnKey {
        void onKey(char key);
    }

    private static final String[][] KEYS = {
            {"1", ""}, {"2", "ABC"}, {"3", "DEF"},
            {"4", "GHI"}, {"5", "JKL"}, {"6", "MNO"},
            {"7", "PQRS"}, {"8", "TUV"}, {"9", "WXYZ"},
            {"*", ""}, {"0", "+"}, {"#", ""}};

    private Keypad() {}

    /** Remplit {@code container} (LinearLayout vertical) avec 4 rangées de 3 touches. */
    static void build(Context ctx, LinearLayout container, int keySizeDp, final ClickSound sound, final OnKey onKey) {
        container.removeAllViews();
        int size = dp(ctx, keySizeDp);
        int gap = dp(ctx, 10);
        for (int row = 0; row < 4; row++) {
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (row > 0) lp.topMargin = gap;
            container.addView(line, lp);
            for (int col = 0; col < 3; col++) {
                final String[] k = KEYS[row * 3 + col];
                TextView key = new TextView(ctx);
                key.setGravity(Gravity.CENTER);
                key.setBackgroundResource(R.drawable.bg_circle_gray);
                key.setTextColor(0xFFFFFFFF);
                key.setSoundEffectsEnabled(false);
                String sub = k[1].isEmpty() ? "" : "\n" + k[1];
                android.text.SpannableString text = new android.text.SpannableString(k[0] + sub);
                if (!sub.isEmpty()) {
                    text.setSpan(new android.text.style.RelativeSizeSpan(0.36f), 1, text.length(), 0);
                    text.setSpan(new android.text.style.ForegroundColorSpan(0xFF8E8E93), 1, text.length(), 0);
                }
                key.setText(text);
                key.setTextSize(TypedValue.COMPLEX_UNIT_SP, keySizeDp * 0.42f);
                key.setLineSpacing(0, 0.85f);
                key.setOnClickListener(v -> {
                    sound.play();
                    onKey.onKey(k[0].charAt(0));
                });
                if ("0".equals(k[0])) {
                    key.setOnLongClickListener(v -> {
                        sound.play();
                        onKey.onKey('+');
                        return true;
                    });
                }
                LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(size, size);
                if (col > 0) kp.leftMargin = dp(ctx, 22);
                line.addView(key, kp);
            }
        }
    }

    static int dp(Context ctx, float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics()));
    }
}
