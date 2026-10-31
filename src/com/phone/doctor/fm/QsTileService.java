package com.phone.doctor.fm;

import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: tap = capture the screen behind the shade and open the chat with it attached. */
public class QsTileService extends TileService {
    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        ScreenService s = ScreenService.inst;
        if (s == null) {
            // accessibility is off: open the app so it can be enabled
            try {
                startActivityAndCollapse(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("needA11y", true));
            } catch (Exception ignored) { }
            return;
        }
        s.captureAndOpen(true);
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = ScreenService.inst != null;
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("צלם ושאל");
        t.setIcon(Icon.createWithResource(this, android.R.drawable.ic_menu_camera));
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(on ? "צילום ופתיחת הצ'אט" : "הפעל שירות נגישות");
        t.updateTile();
    }
}
