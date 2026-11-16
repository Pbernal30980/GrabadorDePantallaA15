package com.pedro.grabador;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Botón del panel rápido: inicia o detiene la grabación. */
public class RecTileService extends TileService {

    @Override
    public void onStartListening() { update(); }

    @Override
    public void onClick() {
        if (RecorderService.isRecording) {
            startService(new Intent(this, RecorderService.class).setAction(RecorderService.ACTION_STOP));
        } else {
            Intent i = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_AUTO_START, true);
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pi);
            } else {
                startActivityAndCollapse(i);
            }
        }
        update();
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean rec = RecorderService.isRecording;
        t.setState(rec ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(rec ? "Detener grabación" : "Grabar pantalla");
        t.updateTile();
    }
}
