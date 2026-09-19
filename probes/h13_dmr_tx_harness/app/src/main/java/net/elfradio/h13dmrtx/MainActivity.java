package net.elfradio.h13dmrtx;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button offlineRun;
    private Button setup0Run;
    private Button noRfRun;
    private Button relayRun;
    private Button rfRun;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(24);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        status = new TextView(this);
        status.setText(R.string.status_initial);
        status.setTextSize(16);
        status.setTextColor(Color.DKGRAY);
        status.setPadding(0, 24, 0, 24);
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        offlineRun = new Button(this);
        offlineRun.setText(R.string.run_offline);
        offlineRun.setOnClickListener(view -> runOfflineContract());
        root.addView(offlineRun);

        setup0Run = new Button(this);
        setup0Run.setText(R.string.run_setup0);
        setup0Run.setOnClickListener(view -> runDevice(
                DmrTxController.MODE_SETUP0_ONLY, null));
        root.addView(setup0Run);

        noRfRun = new Button(this);
        noRfRun.setText(R.string.run_no_rf);
        noRfRun.setOnClickListener(view -> runDevice(
                DmrTxController.MODE_NO_RF, null));
        root.addView(noRfRun);

        relayRun = new Button(this);
        relayRun.setText(R.string.run_relay_one);
        relayRun.setOnClickListener(view -> runDevice(
                DmrTxController.MODE_RELAY_ONE_NO_RF, null));
        root.addView(relayRun);

        setContentView(root);

        if (getIntent().getBooleanExtra("auto_start", false)) {
            String mode = getIntent().getStringExtra("mode");
            if (SoftwarePipelineDiagnostic.MODE.equals(mode)) {
                runSoftwarePipelineDiagnostic();
            } else if (DeviceModePolicy.autoStartsWithoutRf(mode)) {
                runDevice(mode, null);
            } else if (DmrTxController.MODE_RELAY_SOFTWARE_PRIVACY_TRIPLE_SOS_LOW_POWER_RF
                    .equals(mode)
                    || DmrTxController.MODE_SPEECH_SHORT_ABC_LOW_POWER_RF
                    .equals(mode)
                    || DmrTxController.MODE_DMR_REPLAY_CAPTURED_LOW_POWER_RF
                    .equals(mode)) {
                runDevice(mode, getIntent().getStringExtra("rf_permission"));
            } else if (DmrTxController.MODE_LOW_POWER_RF.equals(mode)) {
                status.setText("本版本拒绝摩尔斯低功率射频路径");
            }
        }
    }

    private void runSoftwarePipelineDiagnostic() {
        setButtonsEnabled(false);
        status.setText("运行纯软件AMBE分层诊断");
        worker.execute(() -> {
            try {
                String result = SoftwarePipelineDiagnostic.run(this);
                runOnUiThread(() -> {
                    status.setText(result);
                    setButtonsEnabled(true);
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    status.setText("纯软件AMBE分层诊断失败：" + error.getMessage());
                    setButtonsEnabled(true);
                });
            }
        });
    }

    private void runOfflineContract() {
        setButtonsEnabled(false);
        status.setText(R.string.status_offline_running);
        worker.execute(() -> {
            try {
                String result = OfflineContract.runThreeVectors(
                        getAssets().open("software_ambe_vectors/silence.pcm_s16le"),
                        getAssets().open("software_ambe_vectors/silence.ambe9_sequence.bin"),
                        getAssets().open("software_ambe_vectors/tone_800hz.pcm_s16le"),
                        getAssets().open("software_ambe_vectors/tone_800hz.ambe9_sequence.bin"),
                        getAssets().open("software_ambe_vectors/morse_sos.pcm_s16le"),
                        getAssets().open("software_ambe_vectors/morse_sos.ambe9_sequence.bin"));
                runOnUiThread(() -> {
                    status.setText(result);
                    setButtonsEnabled(true);
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    status.setText(getString(R.string.status_offline_failed,
                            error.getMessage()));
                    setButtonsEnabled(true);
                });
            }
        });
    }

    private void runDevice(String mode, String permission) {
        setButtonsEnabled(false);
        status.setText(DmrTxController.MODE_LOW_POWER_RF.equals(mode)
                || DmrTxController.MODE_RELAY_SOFTWARE_PRIVACY_TRIPLE_SOS_LOW_POWER_RF
                        .equals(mode)
                ? getString(R.string.status_rf_running)
                : DmrTxController.MODE_SETUP0_ONLY.equals(mode)
                ? getString(R.string.status_setup0_running)
                : DeviceModePolicy.showsRelayStatus(mode)
                ? getString(R.string.status_relay_running)
                : getString(R.string.status_no_rf_running));
        worker.execute(() -> {
            try {
                DmrTxController controller = new DmrTxController(this,
                        value -> runOnUiThread(() -> status.setText(value)));
                String result = controller.run(mode, permission);
                runOnUiThread(() -> {
                    status.setText(result);
                    setButtonsEnabled(true);
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    status.setText(getString(R.string.status_device_failed,
                            error.getMessage()));
                    setButtonsEnabled(true);
                });
            }
        });
    }

    private void setButtonsEnabled(boolean enabled) {
        if (offlineRun != null) {
            offlineRun.setEnabled(enabled);
        }
        if (setup0Run != null) {
            setup0Run.setEnabled(enabled);
        }
        if (noRfRun != null) {
            noRfRun.setEnabled(enabled);
        }
        if (relayRun != null) {
            relayRun.setEnabled(enabled);
        }
        if (rfRun != null) {
            rfRun.setEnabled(enabled);
        }
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
