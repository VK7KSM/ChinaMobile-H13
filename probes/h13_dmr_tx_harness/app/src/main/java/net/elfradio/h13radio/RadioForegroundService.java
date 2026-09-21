package net.elfradio.h13radio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 射频服务的 Android 外壳：**只负责活着、占住串口、退出时收干净**。
 *
 * <p>决策逻辑全在 {@link RadioService} 与 {@link RfSafetyGate} 里，那两层
 * 不依赖 Android、能离线逐条验收（2.9.51、2.9.52）。这一层只做框架的事：
 * 前台通知、生命周期、串口归属、退出兜底。**两者分开，是为了让"什么时候
 * 允许发射"这种要紧的判断不必靠真机才能验。**
 *
 * <p>三条纪律：
 *
 * <ul>
 *   <li><b>绝不自启。</b> 没有开机广播、没有 `LAUNCHER` 入口，只能由
 *       显式的 `startForegroundService` 拉起。探针工程里多一个会抢串口的
 *       常驻组件，风险不对称。</li>
 *   <li><b>退出必关射频。</b> `onDestroy` 与 `finally` 都要走
 *       {@link RfSafetyGate#close}，并把串口交还。漏掉的后果不是状态不准，
 *       是发射没停下来（2.9.55）。</li>
 *   <li><b>拿不到串口就老实失败。</b> 生产专网应用常驻持有 `/dev/ttyHS0`，
 *       抢不到就停掉自己并说明原因，不重试、不静默降级。</li>
 * </ul>
 *
 * <p>当前状态：**串口归属与生命周期这一层可用，帧泵尚未接到内存桥**。
 * 把 {@link ModuleTransport} 接到桥上是下一步，需要真机反复验证。
 */
public final class RadioForegroundService extends Service {

    public static final String ACTION_START = "net.elfradio.h13radio.START";
    public static final String ACTION_STOP = "net.elfradio.h13radio.STOP";
    /** 呼号，授权发射的前提。没有它连授权都拿不到。 */
    public static final String EXTRA_CALLSIGN = "callsign";

    private static final String CHANNEL_ID = "h13radio";
    private static final int NOTIFICATION_ID = 0x4831;
    private static final String SERIAL_PATH = "/dev/ttyHS0";

    private final RadioService radio = new RadioService();
    private final RfSafetyGate gate = new RfSafetyGate();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private String lastError;

    @Override
    public IBinder onBind(Intent intent) {
        return null;                       // 暂不提供跨进程接口
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification("正在启动"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown("收到停止指令");
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            shutdown("未知指令：" + action);
            return START_NOT_STICKY;
        }

        String callsign = intent.getStringExtra(EXTRA_CALLSIGN);
        if (callsign == null || callsign.trim().isEmpty()) {
            shutdown("拒绝启动：未提供呼号");
            return START_NOT_STICKY;
        }

        if (!new File(SERIAL_PATH).exists()) {
            shutdown("拒绝启动：找不到 " + SERIAL_PATH);
            return START_NOT_STICKY;
        }

        RadioService.Outcome granted = gate.grant(callsign,
                System.currentTimeMillis(), RfSafetyGate.MAX_GRANT_MS);
        if (!granted.ok) {
            shutdown(granted.why);
            return START_NOT_STICKY;
        }
        running.set(true);
        update("已就绪（" + callsign + "）");
        // START_NOT_STICKY：被系统杀掉之后**不要自己爬起来**。
        // 常驻组件自动复活会在无人知晓的情况下重新占住串口。
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        shutdown("服务销毁");
        super.onDestroy();
    }

    /** 关射频、撤授权、停自己。任何退出路径都走这里。 */
    private void shutdown(String why) {
        lastError = why;
        try {
            long now = System.currentTimeMillis();
            if (gate.transmitting()) {
                gate.close(now);
            }
            radio.stopTx(now);
        } finally {
            gate.revoke();
            running.set(false);
            update("已停止：" + why);
            stopForeground(true);
            stopSelf();
        }
    }

    public String statusLine() {
        return String.format(Locale.US, "服务 %s  %s  %s",
                running.get() ? "运行中" : "已停",
                radio.status(),
                lastError == null ? "" : "最近：" + lastError);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) getSystemService(
                        Context.NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID)
                != null) {
            return;
        }
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                "H13 射频服务", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification notification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("H13 射频服务")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
    }

    private void update(String text) {
        NotificationManager manager =
                (NotificationManager) getSystemService(
                        Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification(text));
        }
    }
}
