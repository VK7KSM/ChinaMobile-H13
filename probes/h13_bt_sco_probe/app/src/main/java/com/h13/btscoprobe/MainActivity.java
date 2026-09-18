package com.h13.btscoprobe;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private TextView statusView;
    private boolean receiverRegistered;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(BtScoRouteService.EXTRA_STATUS);
            if (status != null) {
                statusView.setText(status);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 24, 24, 24);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("H13蓝牙双向音频路由 " + BuildConfig.VERSION_NAME);
        title.setTextSize(20);
        layout.addView(title, fullWidth());

        statusView = new TextView(this);
        statusView.setText("服务未启动");
        statusView.setTextSize(16);
        statusView.setPadding(0, 24, 0, 24);
        layout.addView(statusView, fullWidth());

        Button start = new Button(this);
        start.setText("开启蓝牙双向音频");
        start.setOnClickListener(v -> startRouter());
        layout.addView(start, fullWidth());

        Button stop = new Button(this);
        stop.setText("停止并恢复原音频路由");
        stop.setOnClickListener(v -> stopRouter());
        layout.addView(stop, fullWidth());

        setContentView(layout);

        IntentFilter filter = new IntentFilter(BtScoRouteService.ACTION_STATUS);
        registerReceiver(statusReceiver, filter);
        receiverRegistered = true;

        if (getIntent().getBooleanExtra("auto_start", false)) {
            startRouter();
        } else {
            BtScoRouteService.requestStatus(this);
        }
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void startRouter() {
        Intent intent = new Intent(this, BtScoRouteService.class);
        intent.setAction(BtScoRouteService.ACTION_START);
        startService(intent);
        statusView.setText("正在请求蓝牙SCO连接……");
    }

    private void stopRouter() {
        Intent intent = new Intent(this, BtScoRouteService.class);
        intent.setAction(BtScoRouteService.ACTION_STOP);
        startService(intent);
        statusView.setText("正在停止并恢复……");
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        super.onDestroy();
    }
}
