package com.pedro.grabador;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class MainActivity extends Activity {

    static final String PREFS = "ajustes";
    static final String EXTRA_AUTO_START = "auto_start";
    private static final int REQ_CAPTURE = 10;
    private static final int REQ_PERMS = 11;

    private SharedPreferences prefs;
    private TextView status;
    private Button mainButton, pauseButton;
    private RadioGroup qualityGroup, audioGroup;
    private TextView liveStatus;
    private Button liveButton;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 500); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (showLastCrash()) return;
        try {
            buildUi();
        } catch (Throwable e) {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            showError(sw.toString());
            return;
        }
        handleIntent(getIntent());
    }

    private String lastKey = null;

    /** Si la app se cerró la vez pasada, muestra el error para poder enviarlo. */
    private boolean showLastCrash() {
        java.io.File f = App.crashFile(this);
        if (!f.exists()) return false;
        String txt;
        try { txt = new String(java.nio.file.Files.readAllBytes(f.toPath())); } catch (Exception e) { txt = e.toString(); }
        f.delete();
        showError(txt);
        return true;
    }

    private void showError(String txt) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(56), dp(20), dp(40));
        TextView h = new TextView(this);
        h.setText("La app se cerró la última vez 😕\nCopia este texto y envíaselo a Claude:");
        h.setTextSize(17);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(h);
        Button copy = new Button(this);
        copy.setText("Copiar error");
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("error", txt));
            Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show();
        });
        box.addView(copy);
        Button retry = new Button(this);
        retry.setText("Volver a abrir la app");
        retry.setOnClickListener(v -> recreate());
        box.addView(retry);
        TextView t = new TextView(this);
        t.setText(txt);
        t.setTextIsSelectable(true);
        t.setTextSize(12);
        t.setTypeface(Typeface.MONOSPACE);
        box.addView(t);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        setContentView(sv);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
            intent.removeExtra(EXTRA_AUTO_START);
            if (!RecorderService.isRecording) startFlow();
        }
    }

    @Override protected void onResume() { super.onResume(); handler.post(ticker); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(ticker); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void buildUi() {

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#FAFAFA"));
        scroll.setFitsSystemWindows(false);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets b = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = b.top; bottom = b.bottom;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
            }
            root.setPadding(pad, pad + top, pad, pad + bottom);
            return insets;
        });

        TextView title = new TextView(this);
        title.setText("Grabador de pantalla");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#212121"));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Sin anuncios · v2.2");
        sub.setTextColor(Color.parseColor("#757575"));
        sub.setPadding(0, dp(4), 0, dp(20));
        root.addView(sub);

        status = new TextView(this);
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12), dp(16), dp(12), dp(16));
        GradientDrawable sbg = new GradientDrawable();
        sbg.setCornerRadius(dp(14));
        sbg.setColor(Color.parseColor("#EEEEEE"));
        status.setBackground(sbg);
        root.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mainButton = bigButton();
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        blp.topMargin = dp(16);
        root.addView(mainButton, blp);
        mainButton.setOnClickListener(v -> {
            if (RecorderService.isRecording) sendToService(RecorderService.ACTION_STOP);
            else startFlow();
        });

        pauseButton = bigButton();
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        plp.topMargin = dp(10);
        root.addView(pauseButton, plp);
        pauseButton.setOnClickListener(v -> sendToService(RecorderService.isPaused ? RecorderService.ACTION_RESUME : RecorderService.ACTION_PAUSE));

        root.addView(section("Sonido"));
        audioGroup = radios(new String[]{
                "Sin sonido",
                "Sonido interno (videos, juegos, música)",
                "Micrófono",
                "Sonido interno + micrófono"}, 200, prefs.getInt("audio", 1), "audio");
        root.addView(audioGroup);

        root.addView(section("Calidad del video"));
        qualityGroup = radios(new String[]{
                "Alta (1080p)",
                "Media (720p) — recomendada",
                "Baja (480p, ocupa menos)"}, 100, prefs.getInt("calidad", 1), "calidad");
        root.addView(qualityGroup);

        root.addView(section("Now Bar / Notificaciones en vivo"));
        liveStatus = new TextView(this);
        liveStatus.setTextSize(15);
        liveStatus.setTextColor(Color.parseColor("#424242"));
        liveStatus.setLineSpacing(dp(2), 1f);
        root.addView(liveStatus);
        liveButton = new Button(this);
        liveButton.setAllCaps(false);
        liveButton.setTextSize(15);
        liveButton.setTextColor(Color.parseColor("#B71C1C"));
        liveButton.setText("Abrir ajustes de notificaciones en vivo");
        GradientDrawable lbg = new GradientDrawable();
        lbg.setCornerRadius(dp(22));
        lbg.setStroke(dp(2), Color.parseColor("#D32F2F"));
        lbg.setColor(Color.TRANSPARENT);
        liveButton.setBackground(lbg);
        liveButton.setStateListAnimator(null);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        llp.topMargin = dp(8);
        root.addView(liveButton, llp);
        liveButton.setOnClickListener(v -> LiveUpdate.openSettings(this));

        TextView help = new TextView(this);
        help.setText("• Los videos quedan en la Galería, carpeta Movies/Grabador.\n"
                + "• Pausar y detener: desde la notificación o desde aquí.\n"
                + "• Atajo: en el panel rápido toca el lápiz (Editar) y agrega \"Grabar pantalla\".\n"
                                + "• Algunas apps (llamadas, WhatsApp, Netflix…) no dejan grabar su sonido o imagen; eso lo decide cada app.");
        help.setTextColor(Color.parseColor("#616161"));
        help.setLineSpacing(dp(4), 1f);
        help.setPadding(0, dp(24), 0, 0);
        root.addView(help);

        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                WindowInsetsController c = getWindow().getDecorView().getWindowInsetsController();
                if (c != null) c.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            } catch (Throwable ignored) {}
        }
        refresh();
    }

    private Button bigButton() {
        Button b = new Button(this);
        b.setTextSize(19);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        return b;
    }

    private RadioGroup radios(String[] labels, int baseId, int checked, String key) {
        RadioGroup g = new RadioGroup(this);
        for (int i = 0; i < labels.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(labels[i]);
            rb.setId(baseId + i);
            rb.setTextSize(16);
            rb.setPadding(dp(4), dp(6), 0, dp(6));
            g.addView(rb);
        }
        g.check(baseId + checked);
        g.setOnCheckedChangeListener((grp, id) -> prefs.edit().putInt(key, id - baseId).apply());
        return g;
    }

    private TextView section(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.parseColor("#B71C1C"));
        t.setPadding(0, dp(20), 0, dp(4));
        return t;
    }

    private void paint(Button b, String color, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(radius);
        bg.setColor(Color.parseColor(color));
        b.setBackground(bg);
    }

    private void refresh() {
        if (mainButton == null || liveStatus == null) return;
        boolean rec = RecorderService.isRecording;
        boolean paused = RecorderService.isPaused;
        // Solo redibujar cuando algo cambió (ahorra batería)
        String key = rec + "|" + paused + "|" + (rec ? RecorderService.elapsedMs() / 1000 : 0) + "|"
                + RecorderService.lastMessage + "|" + RecorderService.liveActive + "|" + LiveUpdate.canPost(this);
        if (key.equals(lastKey)) return;
        boolean modeChanged = lastKey == null || !lastKey.startsWith(rec + "|" + paused + "|");
        lastKey = key;
        if (!modeChanged && rec) {
            String t = RecorderService.fmt(RecorderService.elapsedMs());
            status.setText(paused ? "⏸ En pausa  " + t : "🔴 Grabando  " + t);
            refreshLive(rec);
            return;
        }
        paint(mainButton, rec ? "#424242" : "#D32F2F", dp(32));
        mainButton.setText(rec ? "■  Detener y guardar" : "●  Iniciar grabación");
        pauseButton.setVisibility(rec ? android.view.View.VISIBLE : android.view.View.GONE);
        paint(pauseButton, paused ? "#2E7D32" : "#F57C00", dp(28));
        pauseButton.setText(paused ? "▶  Reanudar" : "❚❚  Pausar");
        if (rec) {
            String t = RecorderService.fmt(RecorderService.elapsedMs());
            status.setText(paused ? "⏸ En pausa  " + t : "🔴 Grabando  " + t);
            status.setTextColor(Color.parseColor(paused ? "#E65100" : "#B71C1C"));
        } else {
            status.setText(RecorderService.lastMessage != null ? RecorderService.lastMessage : "Listo para grabar");
            status.setTextColor(Color.parseColor("#424242"));
        }
        refreshLive(rec);
        for (RadioGroup g : new RadioGroup[]{qualityGroup, audioGroup})
            for (int i = 0; i < g.getChildCount(); i++) g.getChildAt(i).setEnabled(!rec);
    }

    private void refreshLive(boolean rec) {
        if (!LiveUpdate.supported()) {
            liveStatus.setText("Tu versión de Android no tiene Now Bar para otras apps (necesita One UI 8 o superior). La grabación funciona igual.");
            liveButton.setVisibility(android.view.View.GONE);
            return;
        }
        Boolean allowed = LiveUpdate.canPost(this);
        Boolean active = RecorderService.liveActive;
        StringBuilder t = new StringBuilder();
        if (Boolean.FALSE.equals(allowed)) {
            t.append("⚠️ Las notificaciones en vivo están desactivadas para esta app. Actívalas para ver la grabación en la Now Bar y en la barra de estado.");
        } else if (rec && Boolean.TRUE.equals(active)) {
            t.append("✅ La grabación se está mostrando en la Now Bar y como chip en la barra de estado.");
        } else if (rec && Boolean.FALSE.equals(active)) {
            t.append("La grabación no quedó como notificación en vivo. Revisa en Ajustes › Notificaciones › Notificaciones en vivo que \"Grabador\" esté activado.");
        } else {
            t.append("Cuando grabes, el tiempo y los botones Pausar/Detener aparecen en la Now Bar (pantalla de bloqueo) y en la barra de estado.");
        }
        if (!t.toString().contentEquals(liveStatus.getText())) liveStatus.setText(t);
        if (liveButton.getVisibility() != android.view.View.VISIBLE) liveButton.setVisibility(android.view.View.VISIBLE);
    }

    private void sendToService(String action) {
        Intent i = new Intent(this, RecorderService.class).setAction(action);
        startService(i);
        handler.postDelayed(() -> { lastKey = null; refresh(); }, 150);
    }

    private int selectedAudio() { return prefs.getInt("audio", 1); }

    private void startFlow() {
        ArrayList<String> need = new ArrayList<>();
        if (selectedAudio() != ScreenEncoder.AUDIO_NONE
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("pidio_notif", false)) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
            prefs.edit().putBoolean("pidio_notif", true).apply();
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
            return;
        }
        askCapture();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_PERMS) askCapture();
        lastKey = null;
    }

    private void askCapture() {
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        try {
            Intent capture;
            if (Build.VERSION.SDK_INT >= 34) {
                // Pide directamente "pantalla completa" (sin tener que elegir en el menú)
                capture = mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay());
            } else {
                capture = mpm.createScreenCaptureIntent();
            }
            startActivityForResult(capture, REQ_CAPTURE);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir la captura: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Grabación cancelada", Toast.LENGTH_SHORT).show();
            return;
        }
        int audio = selectedAudio();
        if (audio != ScreenEncoder.AUDIO_NONE
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Sin permiso de audio: se grabará sin sonido", Toast.LENGTH_LONG).show();
            audio = ScreenEncoder.AUDIO_NONE;
        }
        Intent i = new Intent(this, RecorderService.class);
        i.setAction(RecorderService.ACTION_START);
        i.putExtra("code", resultCode);
        i.putExtra("data", data);
        i.putExtra("audio", audio);
        i.putExtra("calidad", prefs.getInt("calidad", 1));
        startForegroundService(i);
        moveTaskToBack(true);
    }
}
