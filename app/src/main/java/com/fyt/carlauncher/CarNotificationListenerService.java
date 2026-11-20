package com.fyt.carlauncher;

import android.app.Notification;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class CarNotificationListenerService extends NotificationListenerService {

    public static final String ACTION_NAV_UPDATE = "com.fyt.carlauncher.NAV_UPDATE";
    public static final String EXTRA_NAV_TEXT = "nav_text";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if ("com.waze".equals(pkg) || "com.google.android.apps.maps".equals(pkg)) {
            Notification notif = sbn.getNotification();
            if (notif != null && notif.extras != null) {
                Bundle extras = notif.extras;
                CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
                StringBuilder sb = new StringBuilder();
                if (title != null && title.length() > 0) {
                    sb.append(title);
                }
                if (text != null && text.length() > 0) {
                    if (sb.length() > 0) sb.append(" • ");
                    sb.append(text);
                }
                sendNavBroadcast(sb.toString());
            }
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if ("com.waze".equals(pkg) || "com.google.android.apps.maps".equals(pkg)) {
            sendNavBroadcast("");
        }
    }

    private void sendNavBroadcast(String navText) {
        Intent intent = new Intent(ACTION_NAV_UPDATE);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_NAV_TEXT, navText);
        sendBroadcast(intent);
    }
}
