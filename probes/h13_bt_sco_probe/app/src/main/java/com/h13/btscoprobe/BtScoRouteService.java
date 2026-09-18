package com.h13.btscoprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

public final class BtScoRouteService extends Service {
    public static final String ACTION_START = "com.h13.btscoprobe.action.START";
    public static final String ACTION_STOP = "com.h13.btscoprobe.action.STOP";
    public static final String ACTION_QUERY = "com.h13.btscoprobe.action.QUERY";
    public static final String ACTION_STATUS = "com.h13.btscoprobe.action.STATUS";
    public static final String EXTRA_STATUS = "status";

    private static final String TAG = "H13BtScoRouter";
    private static final String CHANNEL_ID = "h13_bt_sco_route";
    private static final int NOTIFICATION_ID = 2001;
    private static final long RETRY_INTERVAL_MS = 3000;
    private static final long RETRY_COOLDOWN_MS = 5000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager audioManager;
    private BluetoothAdapter bluetoothAdapter;
    private boolean receiverRegistered;
    private boolean routingRequested;
    private boolean scoConnected;
    private int originalMode;
    private boolean originalScoOn;
    private boolean originalSpeakerOn;
    private long lastScoRequestElapsed;
    private String lastStatus = "服务未启动";

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED.equals(action)) {
                int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE,
                        AudioManager.SCO_AUDIO_STATE_ERROR);
                scoConnected = state == AudioManager.SCO_AUDIO_STATE_CONNECTED;
                if (scoConnected) {
                    audioManager.setBluetoothScoOn(true);
                    publishStatus("蓝牙双向音频已启用\n" + routeState());
                } else if (routingRequested) {
                    publishStatus("SCO已断开，等待自动重连\n" + routeState());
                }
            } else if (BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED.equals(action)
                    || android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                if (routingRequested) {
                    requestScoIfNeeded();
                }
            }
        }
    };

    private final Runnable keepAlive = new Runnable() {
        @Override
        public void run() {
            if (!routingRequested) {
                return;
            }
            requestScoIfNeeded();
            handler.postDelayed(this, RETRY_INTERVAL_MS);
        }
    };

    public static void requestStatus(Context context) {
        Intent intent = new Intent(context, BtScoRouteService.class);
        intent.setAction(ACTION_QUERY);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        originalMode = audioManager.getMode();
        originalScoOn = audioManager.isBluetoothScoOn();
        originalSpeakerOn = audioManager.isSpeakerphoneOn();

        IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        registerReceiver(stateReceiver, filter);
        receiverRegistered = true;
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_QUERY : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            restoreAndStop("已停止，原音频路由已恢复");
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            startRouting();
            return START_STICKY;
        }
        dispatchStatus(lastStatus);
        if (!routingRequested) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startRouting() {
        if (!routingRequested) {
            routingRequested = true;
            startForeground(NOTIFICATION_ID, notification("正在连接蓝牙通话音频"));
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(false);
            handler.removeCallbacks(keepAlive);
            handler.post(keepAlive);
        }
        requestScoIfNeeded();
    }

    private void requestScoIfNeeded() {
        boolean hfpConnected = bluetoothAdapter != null
                && bluetoothAdapter.isEnabled()
                && bluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEADSET)
                == BluetoothProfile.STATE_CONNECTED;
        if (!hfpConnected) {
            scoConnected = false;
            publishStatus("蓝牙耳机通话配置未连接，等待HFP连接\n" + routeState());
            return;
        }

        if (audioManager.getMode() != AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        }
        if (audioManager.isSpeakerphoneOn()) {
            audioManager.setSpeakerphoneOn(false);
        }
        if (scoConnected) {
            if (!audioManager.isBluetoothScoOn()) {
                audioManager.setBluetoothScoOn(true);
            }
            publishStatus("蓝牙双向音频已启用\n" + routeState());
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastScoRequestElapsed < RETRY_COOLDOWN_MS) {
            return;
        }
        lastScoRequestElapsed = now;
        audioManager.startBluetoothSco();
        publishStatus("已请求SCO，等待耳机建立双向音频\n" + routeState());
    }

    private String routeState() {
        int hfp = bluetoothAdapter == null ? BluetoothProfile.STATE_DISCONNECTED
                : bluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEADSET);
        return "HFP=" + profileStateName(hfp)
                + "，SCO连接=" + scoConnected
                + "，系统SCO路由=" + audioManager.isBluetoothScoOn()
                + "，音频模式=" + audioManager.getMode();
    }

    private void publishStatus(String status) {
        if (status.equals(lastStatus)) {
            return;
        }
        lastStatus = status;
        dispatchStatus(status);
    }

    private void dispatchStatus(String status) {
        Log.i(TAG, status.replace('\n', ' '));
        if (routingRequested) {
            NotificationManager manager = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            manager.notify(NOTIFICATION_ID, notification(status.split("\\n", 2)[0]));
        }
        Intent update = new Intent(ACTION_STATUS);
        update.setPackage(getPackageName());
        update.putExtra(EXTRA_STATUS, status);
        sendBroadcast(update);
    }

    private void restoreAndStop(String status) {
        routingRequested = false;
        handler.removeCallbacksAndMessages(null);
        try {
            audioManager.stopBluetoothSco();
            audioManager.setBluetoothScoOn(originalScoOn);
            audioManager.setSpeakerphoneOn(originalSpeakerOn);
            audioManager.setMode(originalMode);
        } catch (RuntimeException e) {
            Log.e(TAG, "恢复音频路由失败", e);
            status = status + "\n恢复异常=" + e;
        }
        scoConnected = false;
        publishStatus(status + "\n" + routeState());
        stopForeground(true);
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "H13蓝牙双向音频", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("H13蓝牙双向音频")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pending)
                .build();
    }

    private static String profileStateName(int state) {
        switch (state) {
            case BluetoothProfile.STATE_CONNECTED: return "已连接";
            case BluetoothProfile.STATE_CONNECTING: return "正在连接";
            case BluetoothProfile.STATE_DISCONNECTING: return "正在断开";
            default: return "未连接";
        }
    }

    @Override
    public void onDestroy() {
        if (routingRequested && audioManager != null) {
            routingRequested = false;
            handler.removeCallbacksAndMessages(null);
            try {
                audioManager.stopBluetoothSco();
                audioManager.setBluetoothScoOn(originalScoOn);
                audioManager.setSpeakerphoneOn(originalSpeakerOn);
                audioManager.setMode(originalMode);
            } catch (RuntimeException e) {
                Log.e(TAG, "服务销毁时恢复音频路由失败", e);
            }
        }
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver);
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
