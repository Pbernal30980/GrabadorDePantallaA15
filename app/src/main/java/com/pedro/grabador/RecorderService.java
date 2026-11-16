package com.pedro.grabador;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.service.quicksettings.TileService;
import android.content.ComponentName;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RecorderService extends Service {

    static final String ACTION_START = "com.pedro.grabador.START";
    static final String ACTION_STOP = "com.pedro.grabador.STOP";
    static final String ACTION_PAUSE = "com.pedro.grabador.PAUSE";
    static final String ACTION_RESUME = "com.pedro.grabador.RESUME";

    private static final String CH_REC = "grabacion";
    private static final String CH_DONE = "terminado";
    private static final int NOTIF_REC = 1;
    private static final int NOTIF_DONE = 2;

    static volatile boolean isRecording = false;
    static volatile boolean isPaused = false;
    static volatile long startedAt = 0;
    static volatile long pausedTotal = 0;
    static volatile long pausedSince = 0;
    static volatile String lastMessage = null;
    /** null = no se sabe todavía; true = la grabación aparece como Live Update (Now Bar). */
    static volatile Boolean liveActive = null;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private ScreenEncoder encoder;
    private Uri outUri;
    private ParcelFileDescriptor outPfd;
    private boolean stopping = false;

    private final MediaProjection.Callback projCallback = new MediaProjection.Callback() {
        @Override public void onStop() { main.post(() -> stopRecording()); }
    };

    static long elapsedMs() {
        long now = System.currentTimeMillis();
        long p = pausedTotal + (isPaused ? now - pausedSince : 0);
        return Math.max(0, now - startedAt - p);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel rec = new NotificationChannel(CH_REC, "Grabación en curso", NotificationManager.IMPORTANCE_LOW);
        rec.setShowBadge(false);
        nm.createNotificationChannel(rec);
        nm.createNotificationChannel(new NotificationChannel(CH_DONE, "Videos guardados", NotificationManager.IMPORTANCE_DEFAULT));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(action)) {
            if (!isRecording) handleStart(intent);
        } else if (ACTION_STOP.equals(action)) {
            stopRecording();
        } else if (ACTION_PAUSE.equals(action)) {
            if (isRecording && !isPaused && encoder != null) {
                encoder.pause();
                pausedSince = System.currentTimeMillis();
                isPaused = true;
                updateNotification();
                refreshTile();
            }
        } else if (ACTION_RESUME.equals(action)) {
            if (isRecording && isPaused && encoder != null) {
                encoder.resume();
                pausedTotal += System.currentTimeMillis() - pausedSince;
                isPaused = false;
                updateNotification();
                refreshTile();
            }
        } else if (!isRecording) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void handleStart(Intent intent) {
        int code = intent.getIntExtra("code", 0);
        Intent data = intent.getParcelableExtra("data");
        int audioMode = intent.getIntExtra("audio", ScreenEncoder.AUDIO_NONE);
        int quality = intent.getIntExtra("calidad", 1);
        boolean hasMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (!hasMic) audioMode = ScreenEncoder.AUDIO_NONE;
        boolean usesMicType = audioMode != ScreenEncoder.AUDIO_NONE;

        startedAt = System.currentTimeMillis();
        pausedTotal = 0;
        isPaused = false;
        Notification n = buildRecordingNotification();
        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if (usesMicType) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        try {
            startForeground(NOTIF_REC, n, types);
        } catch (Exception e) {
            try {
                startForeground(NOTIF_REC, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                audioMode = ScreenEncoder.AUDIO_NONE;
            } catch (Exception e2) {
                fail("Android no dejó iniciar la grabación: " + e2.getMessage());
                return;
            }
        }

        if (data == null) { fail("No se recibió el permiso de captura"); return; }
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (Exception e) {
            fail("No se pudo iniciar la captura: " + e.getMessage());
            return;
        }
        if (projection == null) { fail("No se pudo iniciar la captura"); return; }
        projection.registerCallback(projCallback, main);

        DisplayMetrics dm = new DisplayMetrics();
        getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(dm);

        encoder = new ScreenEncoder(projection, dm.widthPixels, dm.heightPixels, dm.densityDpi);
        try {
            encoder.prepare(quality, audioMode);
        } catch (Exception e) {
            fail("Este teléfono no aceptó el formato de video: " + e.getMessage());
            return;
        }
        if (encoder.warning != null) toast(encoder.warning);

        try {
            openOutput();
            encoder.start(outPfd.getFileDescriptor());
        } catch (Exception e) {
            discardOutput();
            fail("No se pudo empezar a grabar: " + e.getMessage());
            return;
        }
        isRecording = true;
        stopping = false;
        lastMessage = null;
        startedAt = System.currentTimeMillis();
        updateNotification();
        refreshTile();
    }

    private void openOutput() throws Exception {
        String name = "Grabacion_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Grabador");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        outUri = getContentResolver().insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        if (outUri == null) throw new IllegalStateException("no se pudo crear el archivo en la Galería");
        outPfd = getContentResolver().openFileDescriptor(outUri, "rw");
        if (outPfd == null) throw new IllegalStateException("no se pudo abrir el archivo");
    }

    private void discardOutput() {
        if (outPfd != null) { try { outPfd.close(); } catch (Exception ignored) {} outPfd = null; }
        if (outUri != null) { try { getContentResolver().delete(outUri, null, null); } catch (Exception ignored) {} outUri = null; }
    }

    private void fail(String msg) {
        lastMessage = msg;
        toast(msg);
        if (encoder != null) { encoder.releaseAll(); encoder = null; }
        releaseProjection();
        isRecording = false;
        isPaused = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        refreshTile();
        stopSelf();
    }

    private void releaseProjection() {
        if (projection != null) {
            try { projection.unregisterCallback(projCallback); } catch (Exception ignored) {}
            try { projection.stop(); } catch (Exception ignored) {}
            projection = null;
        }
    }

    private void stopRecording() {
        if (!isRecording) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return; }
        if (stopping) return;
        stopping = true;
        lastMessage = "Guardando video…";
        final ScreenEncoder enc = encoder;
        new Thread(() -> {
            boolean ok = enc != null && enc.stop();
            main.post(() -> finishSave(ok));
        }, "grabador-stop").start();
    }

    private void finishSave(boolean ok) {
        encoder = null;
        releaseProjection();
        isRecording = false;
        isPaused = false;
        liveActive = null;
        if (outPfd != null) { try { outPfd.close(); } catch (Exception ignored) {} outPfd = null; }
        ContentResolver cr = getContentResolver();
        if (ok && outUri != null) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            try { cr.update(outUri, v, null, null); } catch (Exception ignored) {}
            lastMessage = "✅ Guardado en Galería › Movies/Grabador";
            notifyDone(outUri);
        } else {
            discardOutput();
            lastMessage = "La grabación fue muy corta o falló; no se guardó";
        }
        toast(lastMessage);
        outUri = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        refreshTile();
        stopSelf();
    }

    // ---------------------------------------------------------------- notificaciones

    private PendingIntent servicePi(String action, int req) {
        Intent i = new Intent(this, RecorderService.class).setAction(action);
        return PendingIntent.getService(this, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildRecordingNotification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openPi = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Icon icon = Icon.createWithResource(this, R.drawable.ic_stat_rec);
        Notification.Builder b = new Notification.Builder(this, CH_REC)
                .setSmallIcon(R.drawable.ic_stat_rec)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setColor(0xFFD32F2F)
                .setContentIntent(openPi)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        String elapsed = fmt(elapsedMs());
        if (isPaused) {
            b.setContentTitle("Grabación en pausa")
             .setContentText("Llevas " + elapsed + " · toca Reanudar para seguir")
             .setUsesChronometer(false)
             .setShowWhen(false)
             .addAction(new Notification.Action.Builder(icon, "Reanudar", servicePi(ACTION_RESUME, 3)).build());
            LiveUpdate.apply(b, this, "Pausa", "Grabación en pausa", elapsed);
        } else {
            b.setContentTitle("Grabando pantalla")
             .setContentText("Toca Detener para guardar el video")
             .setWhen(System.currentTimeMillis() - elapsedMs())
             .setShowWhen(true)
             .setUsesChronometer(true)
             .addAction(new Notification.Action.Builder(icon, "Pausar", servicePi(ACTION_PAUSE, 4)).build());
            LiveUpdate.apply(b, this, null, "Grabando pantalla", "Toca para detener");
        }
        b.addAction(new Notification.Action.Builder(icon, "Detener", servicePi(ACTION_STOP, 1)).build());
        return b.build();
    }

    private void updateNotification() {
        if (!isRecording) return;
        getSystemService(NotificationManager.class).notify(NOTIF_REC, buildRecordingNotification());
        checkPromoted();
    }

    private void checkPromoted() {
        main.postDelayed(() -> {
            if (isRecording) liveActive = LiveUpdate.supported() ? LiveUpdate.isPromoted(this, NOTIF_REC) : null;
        }, 600);
    }

    private void notifyDone(Uri uri) {
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent send = new Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(send, "Compartir video").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Icon icon = Icon.createWithResource(this, R.drawable.ic_stat_rec);
        Notification n = new Notification.Builder(this, CH_DONE)
                .setSmallIcon(R.drawable.ic_stat_rec)
                .setColor(0xFFD32F2F)
                .setContentTitle("Video guardado")
                .setContentText("Galería › Movies/Grabador · toca para verlo")
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(this, 5, view, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .addAction(new Notification.Action.Builder(icon, "Compartir",
                        PendingIntent.getActivity(this, 6, chooser, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build())
                .build();
        try { getSystemService(NotificationManager.class).notify(NOTIF_DONE, n); } catch (Exception ignored) {}
    }

    static String fmt(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    private void refreshTile() {
        try { TileService.requestListeningState(this, new ComponentName(this, RecTileService.class)); } catch (Exception ignored) {}
    }

    private void toast(final String msg) {
        main.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show());
    }

    @Override
    public void onDestroy() {
        if (isRecording && encoder != null && !stopping) {
            encoder.stop();
            finishSave(true);
        }
        super.onDestroy();
    }
}
