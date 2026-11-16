package com.pedro.grabador;

import android.app.Application;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;

/** Guarda cualquier cierre inesperado en un archivo para mostrarlo la próxima vez que se abra la app. */
public class App extends Application {

    static File crashFile(android.content.Context c) { return new File(c.getFilesDir(), "ultimo_error.txt"); }

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                try (FileWriter w = new FileWriter(crashFile(this))) {
                    w.write("Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ") "
                            + android.os.Build.MODEL + "\nHilo: " + t.getName() + "\n\n" + sw);
                }
            } catch (Throwable ignored) {}
            Log.e("Grabador", "Cierre inesperado", e);
            if (prev != null) prev.uncaughtException(t, e);
        });
    }
}
