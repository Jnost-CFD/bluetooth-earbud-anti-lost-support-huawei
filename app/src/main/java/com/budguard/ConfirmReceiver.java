package com.budguard;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 接收常驻确认通知上的「在仓内 / 不在仓」两个按钮。
 * 只负责把动作转发给前台服务，具体状态由服务处理。
 */
public class ConfirmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        Intent svc = new Intent(context, BudMonitorService.class);
        svc.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc);
        } else {
            context.startService(svc);
        }
    }
}
