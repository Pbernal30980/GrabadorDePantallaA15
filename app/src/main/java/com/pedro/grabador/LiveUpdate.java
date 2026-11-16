package com.pedro.grabador;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * "Live Updates" de Android 16+ (lo que One UI 8 / 8.5 / 9 muestra en la Now Bar,
 * la pantalla de bloqueo y como chip en la barra de estado).
 *
 * Se usa reflexión y claves de extras para que el mismo APK funcione en Android 14/15
 * (se ignora), Android 16 (One UI 8.x) y Android 17 (One UI 9).
 */
final class LiveUpdate {

    private LiveUpdate() {}

    /** Notification.EXTRA_REQUEST_PROMOTED_ONGOING (Android 16 QPR1+ / 17). */
    private static final String EXTRA_REQUEST_PROMOTED = "android.requestPromotedOngoing";
    /** Valor usado en versiones preliminares de Android 16. */
    private static final String EXTRA_REQUEST_PROMOTED_OLD = "android.REQUEST_PROMOTED_ONGOING";
    /** Notification.FLAG_PROMOTED_ONGOING */
    static final int FLAG_PROMOTED_ONGOING = 0x00040000;

    static boolean supported() { return Build.VERSION.SDK_INT >= 36; }

    /**
     * Pide que la notificación sea promovida a Live Update.
     * Requisitos: ongoing, título, sin estilo personalizado, sin colorized, canal no MIN.
     */
    static void apply(Notification.Builder b, Context ctx, String chipText, String primary, String secondary) {
        Bundle ex = new Bundle();
        ex.putBoolean(EXTRA_REQUEST_PROMOTED, true);
        ex.putBoolean(EXTRA_REQUEST_PROMOTED_OLD, true);

        // Extras de las "Live Notifications" propias de Samsung (One UI 7+). Si la versión
        // de One UI no las usa, simplemente se ignoran.
        ex.putInt("android.ongoingActivityNoti.style", 1);
        ex.putString("android.ongoingActivityNoti.primaryInfo", primary);
        ex.putString("android.ongoingActivityNoti.secondaryInfo", secondary);
        ex.putString("android.ongoingActivityNoti.chipExpandedText", chipText);
        ex.putString("android.ongoingActivityNoti.nowbarPrimaryInfo", primary);
        ex.putString("android.ongoingActivityNoti.nowbarSecondaryInfo", secondary);
        ex.putInt("android.ongoingActivityNoti.chipBgColor", 0xFFD32F2F);
        ex.putParcelable("android.ongoingActivityNoti.chipIcon", Icon.createWithResource(ctx, R.drawable.ic_stat_rec));
        b.addExtras(ex);

        // API 37: setRequestPromotedOngoing(boolean)
        call(b, "setRequestPromotedOngoing", new Class<?>[]{boolean.class}, true);
        // API 36: setShortCriticalText(String) → texto del chip en la barra de estado
        if (chipText != null && !call(b, "setShortCriticalText", new Class<?>[]{String.class}, chipText)) {
            call(b, "setShortCriticalText", new Class<?>[]{CharSequence.class}, chipText);
        }
    }

    /** ¿El usuario permite Live Updates para esta app? (null = no se sabe / no aplica) */
    static Boolean canPost(Context ctx) {
        if (!supported()) return null;
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            Method m = NotificationManager.class.getMethod("canPostPromotedNotifications");
            return (Boolean) m.invoke(nm);
        } catch (Throwable t) {
            return null;
        }
    }

    /** ¿La notificación de grabación quedó realmente promovida? */
    static boolean isPromoted(Context ctx, int id) {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            for (StatusBarNotification s : nm.getActiveNotifications()) {
                if (s.getId() == id) return (s.getNotification().flags & FLAG_PROMOTED_ONGOING) != 0;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** Abre la pantalla de ajustes donde se activan las Live Updates / notificaciones en vivo. */
    static void openSettings(Context ctx) {
        String action = null;
        try {
            Field f = Settings.class.getField("ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS");
            action = (String) f.get(null);
        } catch (Throwable ignored) {}
        Intent[] tries = {
                action != null ? new Intent(action).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.getPackageName())
                        .setData(Uri.parse("package:" + ctx.getPackageName())) : null,
                action != null ? new Intent(action).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.getPackageName()) : null,
                new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.getPackageName()),
        };
        for (Intent i : tries) {
            if (i == null) continue;
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return;
            } catch (Throwable ignored) {}
        }
    }

    private static boolean call(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method m = target.getClass().getMethod(name, types);
            m.invoke(target, args);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
