package com.budguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 开机自启：如果用户开启过守护，重启后自动恢复。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Store store = new Store(context);
        if (!store.monitor() || store.target() == null) return;
        Intent i = new Intent(context, BudMonitorService.class);
        i.setAction(BudMonitorService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }
}
