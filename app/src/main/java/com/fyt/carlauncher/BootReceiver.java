package com.fyt.carlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Affiche le launcher dès le démarrage de l'autoradio (contact mis), même si le firmware
 * FYT tente de rouvrir sa propre interface ou la dernière app utilisée.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Intent home = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            context.startActivity(home);
        } catch (Exception ignored) {
        }
    }
}
