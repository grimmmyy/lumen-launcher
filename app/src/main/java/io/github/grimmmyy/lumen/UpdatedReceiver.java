package io.github.grimmmyy.lumen;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * After an update, Android has closed Lumen and the TV shows the system home
 * screen. Open Lumen again, so updating from Settings > Updates lands back on
 * Home as before.
 */
public class UpdatedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        // Android only lets Lumen open a screen from the background while its
        // Home-button service is bound, which may not have happened yet right
        // after the update: the service opens Lumen when it reconnects.
        ctx.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
                .putLong("reopen_until", System.currentTimeMillis() + 60_000).apply();
        try {
            ctx.startActivity(new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
            // background start not allowed here: the Home button opens Lumen anyway
        }
    }
}
