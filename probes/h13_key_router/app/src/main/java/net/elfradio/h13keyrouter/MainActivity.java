package net.elfradio.h13keyrouter;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler();
    private TextView serviceState;
    private TextView eventState;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateState();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private View createContentView() {
        int padding = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(Color.rgb(246, 247, 248));

        TextView title = new TextView(this);
        title.setText("H13 按键路由");
        title.setTextSize(20);
        title.setTextColor(Color.rgb(25, 30, 35));
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        TextView safety = new TextView(this);
        safety.setText("大 PTT → Zello");
        safety.setTextSize(14);
        safety.setTextColor(Color.rgb(20, 105, 55));
        root.addView(safety, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32)));

        serviceState = new TextView(this);
        serviceState.setTextSize(14);
        serviceState.setTextColor(Color.rgb(35, 40, 45));
        root.addView(serviceState, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));

        eventState = new TextView(this);
        eventState.setTextSize(12);
        eventState.setTextColor(Color.rgb(55, 60, 65));
        eventState.setTextIsSelectable(true);
        root.addView(eventState, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Button settingsButton = new Button(this);
        settingsButton.setText("辅助功能设置");
        settingsButton.setOnClickListener(view ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(settingsButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        return root;
    }

    private void updateState() {
        boolean enabled = isServiceEnabled();
        serviceState.setText(enabled ? "路由服务：已启用" : "路由服务：未启用");
        serviceState.setTextColor(enabled
                ? Color.rgb(20, 105, 55)
                : Color.rgb(165, 35, 35));
        eventState.setText(KeyFilterService.getStatus());
    }

    private boolean isServiceEnabled() {
        AccessibilityManager manager =
                (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (manager == null) {
            return false;
        }
        ComponentName expected = new ComponentName(this, KeyFilterService.class);
        List<AccessibilityServiceInfo> services =
                manager.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo service : services) {
            ResolveInfo resolveInfo = service.getResolveInfo();
            if (resolveInfo != null
                    && resolveInfo.serviceInfo != null
                    && expected.equals(new ComponentName(
                            resolveInfo.serviceInfo.packageName,
                            resolveInfo.serviceInfo.name))) {
                return true;
            }
        }
        return false;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
