package net.elfradio.h13keyrouter;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.Locale;

public final class KeyFilterService extends AccessibilityService {
    public static final String TAG = "H13KeyRouter";
    private static final int H13_PTT_SCAN_CODE = 59;
    private static final ComponentName ZELLO_PTT_RECEIVER = new ComponentName(
            "com.loudtalks", "com.zello.ui.PttButtonReceiver");
    private static final ComponentName ZELLO_MAIN_ACTIVITY = new ComponentName(
            "com.loudtalks", "com.zello.ui.MainActivity");

    private static volatile String status = "服务尚未连接";
    private BroadcastReceiver factoryBroadcastMonitor;
    private boolean zelloPttDown;

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);
        registerFactoryBroadcastMonitor();
        status = "服务已连接，大 PTT 已路由至 Zello";
        Log.i(TAG, "BOUND Zello route flags=0x" + Integer.toHexString(info.flags));
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_F1
                || event.getScanCode() != H13_PTT_SCAN_CODE) {
            return false;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (!zelloPttDown && event.getRepeatCount() == 0) {
                zelloPttDown = true;
                sendZelloPtt("com.zello.ptt.down");
                raiseZelloUi();
                logKeyEvent(event, "DOWN dispatched");
            }
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (zelloPttDown) {
                zelloPttDown = false;
                sendZelloPtt("com.zello.ptt.up");
                logKeyEvent(event, "UP dispatched");
            }
            return true;
        }

        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // This service filters hardware keys only.
    }

    @Override
    public void onInterrupt() {
        releaseZelloPtt("interrupt");
        status = "服务被系统中断";
        Log.w(TAG, "INTERRUPTED");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        releaseZelloPtt("unbind");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        releaseZelloPtt("destroy");
        if (factoryBroadcastMonitor != null) {
            unregisterReceiver(factoryBroadcastMonitor);
            factoryBroadcastMonitor = null;
        }
        status = "服务已停止";
        Log.i(TAG, "DESTROYED");
        super.onDestroy();
    }

    static String getStatus() {
        return status;
    }

    private void sendZelloPtt(String action) {
        Intent intent = new Intent(action);
        intent.setComponent(ZELLO_PTT_RECEIVER);
        try {
            sendBroadcast(intent);
        } catch (RuntimeException error) {
            Log.e(TAG, "Zello PTT broadcast failed: " + action, error);
            status = "Zello PTT 发送失败：" + error.getClass().getSimpleName();
        }
    }

    private void raiseZelloUi() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(ZELLO_MAIN_ACTIVITY);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            Log.e(TAG, "Zello main activity not found", error);
            status = "找不到 Zello 主界面";
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to raise Zello UI", error);
            status = "无法打开 Zello：" + error.getClass().getSimpleName();
        }
    }

    private void releaseZelloPtt(String reason) {
        if (!zelloPttDown) {
            return;
        }
        zelloPttDown = false;
        sendZelloPtt("com.zello.ptt.up");
        Log.w(TAG, "Synthetic Zello PTT up on " + reason);
    }

    private void logKeyEvent(KeyEvent event, String routeAction) {
        InputDevice inputDevice = event.getDevice();
        String deviceName = inputDevice == null ? "unknown" : inputDevice.getName();
        String message = String.format(
                Locale.US,
                "%s keyCode=%d scanCode=%d device=%s eventTime=%d dispatchTime=%d",
                routeAction,
                event.getKeyCode(),
                event.getScanCode(),
                deviceName,
                event.getEventTime(),
                SystemClock.uptimeMillis());
        status = message;
        Log.i(TAG, message);
    }

    private void registerFactoryBroadcastMonitor() {
        if (factoryBroadcastMonitor != null) {
            return;
        }
        factoryBroadcastMonitor = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String message = "FACTORY_BROADCAST observed action=" + intent.getAction();
                status = message;
                Log.w(TAG, message);
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction("BOPTT.privkey.ptt.down");
        filter.addAction("BOPTT.privkey.ptt.up");
        filter.addAction("BOPTT.privkey.fun1.down");
        filter.addAction("BOPTT.privkey.fun1.up");
        filter.addAction("BOPTT.privkey.fun1.long");
        filter.addAction("BOPTT.privkey.m1.down");
        filter.addAction("BOPTT.privkey.m1.up");
        filter.addAction("BOPTT.privkey.m1.long");
        filter.addAction("BOPTT.privkey.m2.down");
        filter.addAction("BOPTT.privkey.m2.up");
        filter.addAction("BOPTT.privkey.m2.long");
        filter.addAction("BOPTT.privkey.p1.down");
        filter.addAction("BOPTT.privkey.p1.up");
        filter.addAction("BOPTT.privkey.p1.long");
        filter.addAction("BOPTT.privkey.p2.down");
        filter.addAction("BOPTT.privkey.p2.up");
        filter.addAction("BOPTT.privkey.p2.long");
        filter.addAction("BOPTT.privkey.chup.up");
        filter.addAction("BOPTT.privkey.chdown.up");
        registerReceiver(factoryBroadcastMonitor, filter);
    }
}
