package net.elfradio.h13interphoneprobe;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressLint("SetTextI18n")
public final class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean runInProgress = new AtomicBoolean(false);
    private InterphoneProbe probe;
    private TextView banner;
    private TextView detail;
    private TextView status;
    private Button runButton;
    private String probeMode;
    private String hpiRoute;
    private int powerCode;
    private boolean allowPotentialRf;
    private boolean allowChannelMutation;
    private String acceptedLaunchSessionId;

    private static final String[] KNOWN_PROBE_MODES = {
            "launch_contract_selftest_no_device",
            "software_ambe_no_rf_selftest",
            "software_ambe_data36_privacy_no_rf_selftest",
            "software_ambe_measured_privacy_no_rf",
            "software_ambe_session_ready_no_rf",
            "software_ambe_one_data36_no_rf",
            "software_ambe_one_data36_low_power_rf",
            "software_ambe_full_morse_low_power_rf",
            "software_ambe_production_full_morse_low_power_rf",
            "software_ambe_production_rf_infrastructure_preflight_no_rf",
            "software_ambe_realtime_trigger_one_data36_no_rf",
            "analog_rf_carrier_pulse",
            "baseline_reload_proof",
            "chan_d_long_stream",
            "channel_config_hpi_entry_230400_no_rf",
            "dmr_external_encoded",
            "dmr_external_encoded_one_unit_230400",
            "dmr_external_one_data36_credit_no_rf",
            "dmr_external_fixed_asset_realtime_trigger_data36_credit",
            "dmr_external_realtime_relay_data36_credit",
            "dmr_external_privacy_contract_preflight_57600",
            "dmr_external_runtime_snapshot_57600",
            "dmr_external_sct_ready_readonly_57600",
            "dmr_external_step1_offline_handshake",
            "dmr_external_step4_one_data36_credit",
            "dmr_external_setup0_only_no_rf",
            "dmr_external_setup04_only_no_rf",
            "dmr_external_vlc_session_no_rf",
            "dmr_external_setup_fullprep_isolation_no_rf",
            "dmr_external_combined_window_readonly_dump_57600",
            "dmr_external_audited_zero_combined_window_57600",
            "dmr_rx_multiplane_capture",
            "dmr_voice_in_morse",
            "dmr_voice_in_morse_abcdef_power_230400",
            "external_encoded_tx_route60_no_rf_57600",
            "fm_bridge_codec_diagnostic_230400",
            "fm_voice_in_five_blocks_power_230400",
            "fm_voice_in_morse_abcdef_power_230400",
            "fm_voice_in_one_block_230400",
            "fm_voice_in_one_block_power_230400",
            "hpi_loader_roundtrip_proof",
            "hpi_pcm_hunt",
            "hpi_hobib_observer_no_hpi_no_rf",
            "hpi_frame_adapter_arm_ready_no_hpi_no_rf",
            "hpi_frame_adapter_hpi_stage_no_pcm_no_rf",
            "hpi_frame_adapter_truncated_pcm_hpi_stage_no_rf",
            "hpi_frame_adapter_complete_pcm_hpi_stage_no_rf",
            "hpi_frame_adapter_complete_pcm_hpi_vocoder90_no_rf",
            "hpi_bridge_vocoder_off_only_no_rf",
            "hpi_bridge_vocoder_off80_no_rf",
            "hpi_bridge_vocoder_off80_process_no_rf",
            "hpi_bridge_vocoder_control_full_no_rf",
            "hpi_bridge_control_then_speech_consume_no_rf",
            "hpi_frame_adapter_truncated_pcm_no_hpi_no_rf",
            "hpi_frame_adapter_complete_receive_no_hpi_no_rf",
            "hpi_frame_adapter_complete_tone_no_rf",
            "hpi_frame_adapter_tone_to_dmr_low_power",
            "vocoder_encoder_no_input_baseline_230400_no_rf",
            "vocoder_encoder_one_zero_pcm_230400_no_rf",
            "vocoder_encoder_one_tone_pcm_230400_no_rf",
            "vocoder_encoder_two_stage_tone_pcm_230400_no_rf",
            "local_chan_d_decode",
            "local_one_chan_d_57600",
            "local_one_chan_d_input_only_57600",
            "local_process_57600",
            "local_route_57600",
            "local_work_57600",
            "mode0_voice_out_pcm_capture",
            "p0_session_smoke",
            "pendsv_exec_proof",
            "pendsv_priority_byte_proof",
            "scb_priority_readonly_57600",
            "power_calibration_snapshot_57600",
            "ram_exec_proof",
            "ram_survey",
            "realtime_data_plane_probe",
            "restore_roundtrip",
            "rx_pcm_stream",
            "sct_flash_dump",
            "segmented_baseline_loader_proof",
            "sram_preflight_57600",
            "uart_baud_230400_roundtrip",
            "uart_baud_keep_57600_isr",
            "uart_hpi_bridge_230400_smoke",
            "uart_hpi_bridge_timeout_proof",
            "uart_wake_kick_proof",
            "vocoder_encoder_loopback_no_rf",
            "vocoder_encoder_tone_230400_no_rf",
            "vocoder_encoder_zero_230400_no_rf",
            "voice_out_pcm_230400_control_b",
            "voice_out_pcm_230400_rx",
            "voice_out_pcm_57600_control_a"
    };

    /** 名称带no_rf不能作为安全依据，凡可能进入发送工作态均要求额外门。 */
    private static final String[] POTENTIAL_RF_MODES = {
            "software_ambe_one_data36_no_rf",
            "software_ambe_one_data36_low_power_rf",
            "software_ambe_full_morse_low_power_rf",
            "software_ambe_production_full_morse_low_power_rf",
            "software_ambe_realtime_trigger_one_data36_no_rf",
            "analog_rf_carrier_pulse",
            "dmr_external_encoded",
            "dmr_external_encoded_one_unit_230400",
            "dmr_external_one_data36_credit_no_rf",
            "dmr_external_fixed_asset_realtime_trigger_data36_credit",
            "dmr_external_realtime_relay_data36_credit",
            "dmr_external_step4_one_data36_credit",
            "dmr_external_setup0_only_no_rf",
            "dmr_external_setup04_only_no_rf",
            "dmr_external_vlc_session_no_rf",
            "dmr_external_setup_fullprep_isolation_no_rf",
            "dmr_voice_in_morse",
            "dmr_voice_in_morse_abcdef_power_230400",
            "hpi_frame_adapter_tone_to_dmr_low_power",
            "external_encoded_tx_route60_no_rf_57600",
            "fm_bridge_codec_diagnostic_230400",
            "fm_voice_in_five_blocks_power_230400",
            "fm_voice_in_morse_abcdef_power_230400",
            "fm_voice_in_one_block_230400",
            "fm_voice_in_one_block_power_230400",
            "local_one_chan_d_57600",
            "local_one_chan_d_input_only_57600",
            "local_process_57600",
            "local_route_57600",
            "local_work_57600",
            "realtime_data_plane_probe",
            "vocoder_encoder_loopback_no_rf",
            "vocoder_encoder_tone_230400_no_rf",
            "vocoder_encoder_zero_230400_no_rf"
    };

    /**
     * v3.82真机证明这两个入口会先进入普通麦克风PTT状态机，产生持续CHAN_D，
     * 且文本停发可能被模块拒绝。保留名称只为拒绝旧调用，不允许再次执行。
     */
    private static final String[] BLOCKED_RF_MODES = {
            "software_ambe_one_data36_low_power_rf",
            "software_ambe_full_morse_low_power_rf"
    };

    private static final String[] POWER_CODE_MODES = {
            "analog_rf_carrier_pulse",
            "dmr_external_encoded",
            "dmr_external_encoded_one_unit_230400",
            "dmr_voice_in_morse",
            "dmr_voice_in_morse_abcdef_power_230400",
            "fm_voice_in_five_blocks_power_230400",
            "fm_voice_in_morse_abcdef_power_230400",
            "fm_voice_in_one_block_power_230400"
    };

    /** 会执行DMOSETDIGITALCH的入口必须取得独立频道变更许可。 */
    private static final String[] CHANNEL_MUTATION_MODES = {
            "software_ambe_measured_privacy_no_rf",
            "software_ambe_session_ready_no_rf",
            "software_ambe_one_data36_no_rf",
            "software_ambe_one_data36_low_power_rf",
            "software_ambe_full_morse_low_power_rf",
            "software_ambe_production_full_morse_low_power_rf",
            "software_ambe_realtime_trigger_one_data36_no_rf",
            "vocoder_encoder_no_input_baseline_230400_no_rf",
            "vocoder_encoder_one_zero_pcm_230400_no_rf",
            "vocoder_encoder_one_tone_pcm_230400_no_rf",
            "vocoder_encoder_two_stage_tone_pcm_230400_no_rf",
            "dmr_external_privacy_contract_preflight_57600",
            "dmr_external_fixed_asset_realtime_trigger_data36_credit",
            "dmr_external_one_data36_credit_no_rf",
            "dmr_external_realtime_relay_data36_credit",
            "dmr_external_step4_one_data36_credit",
            "dmr_external_setup0_only_no_rf",
            "dmr_external_setup04_only_no_rf",
            "dmr_external_vlc_session_no_rf",
            "dmr_external_setup_fullprep_isolation_no_rf",
            "hpi_frame_adapter_tone_to_dmr_low_power"
    };

    /** 这些入口只能使用频道合同中的low，拒绝独立功率覆盖。 */
    private static final String[] FIXED_LOW_POWER_DMR_MODES = {
            "software_ambe_session_ready_no_rf",
            "software_ambe_one_data36_no_rf",
            "software_ambe_one_data36_low_power_rf",
            "software_ambe_full_morse_low_power_rf",
            "software_ambe_production_full_morse_low_power_rf",
            "software_ambe_realtime_trigger_one_data36_no_rf",
            "dmr_external_privacy_contract_preflight_57600",
            "dmr_external_fixed_asset_realtime_trigger_data36_credit",
            "dmr_external_one_data36_credit_no_rf",
            "dmr_external_realtime_relay_data36_credit",
            "dmr_external_step4_one_data36_credit",
            "dmr_external_setup0_only_no_rf",
            "dmr_external_setup04_only_no_rf",
            "dmr_external_vlc_session_no_rf",
            "dmr_external_setup_fullprep_isolation_no_rf",
            "hpi_frame_adapter_tone_to_dmr_low_power"
    };

    /** 本地无射频模式不使用功率参数，出现覆盖值即拒绝。 */
    private static final String[] POWER_CODE_FORBIDDEN_MODES = {
            "software_ambe_no_rf_selftest",
            "software_ambe_data36_privacy_no_rf_selftest",
            "software_ambe_measured_privacy_no_rf",
            "software_ambe_session_ready_no_rf",
            "software_ambe_production_rf_infrastructure_preflight_no_rf",
            "vocoder_encoder_no_input_baseline_230400_no_rf",
            "vocoder_encoder_one_zero_pcm_230400_no_rf",
            "vocoder_encoder_one_tone_pcm_230400_no_rf",
            "vocoder_encoder_two_stage_tone_pcm_230400_no_rf",
            "hpi_hobib_observer_no_hpi_no_rf",
            "hpi_frame_adapter_truncated_pcm_no_hpi_no_rf",
            "hpi_frame_adapter_complete_receive_no_hpi_no_rf",
            "hpi_frame_adapter_complete_tone_no_rf",
            "hpi_frame_adapter_complete_pcm_hpi_stage_no_rf",
            "hpi_frame_adapter_complete_pcm_hpi_vocoder90_no_rf",
            "hpi_bridge_vocoder_off_only_no_rf",
            "hpi_bridge_vocoder_off80_no_rf",
            "hpi_bridge_vocoder_off80_process_no_rf",
            "hpi_bridge_vocoder_control_full_no_rf",
            "hpi_bridge_control_then_speech_consume_no_rf"
    };

    /** 本地纯编码会话拒绝多余射频许可，防止调用边界漂移。 */
    private static final String[] POTENTIAL_RF_PERMISSION_FORBIDDEN_MODES = {
            "software_ambe_no_rf_selftest",
            "software_ambe_data36_privacy_no_rf_selftest",
            "software_ambe_measured_privacy_no_rf",
            "software_ambe_session_ready_no_rf",
            "software_ambe_production_rf_infrastructure_preflight_no_rf",
            "vocoder_encoder_no_input_baseline_230400_no_rf",
            "vocoder_encoder_one_zero_pcm_230400_no_rf",
            "vocoder_encoder_one_tone_pcm_230400_no_rf",
            "vocoder_encoder_two_stage_tone_pcm_230400_no_rf",
            "hpi_hobib_observer_no_hpi_no_rf",
            "hpi_frame_adapter_truncated_pcm_no_hpi_no_rf",
            "hpi_frame_adapter_complete_receive_no_hpi_no_rf",
            "hpi_frame_adapter_complete_tone_no_rf",
            "hpi_frame_adapter_complete_pcm_hpi_stage_no_rf",
            "hpi_frame_adapter_complete_pcm_hpi_vocoder90_no_rf",
            "hpi_bridge_vocoder_off_only_no_rf",
            "hpi_bridge_vocoder_off80_no_rf",
            "hpi_bridge_vocoder_off80_process_no_rf",
            "hpi_bridge_vocoder_control_full_no_rf",
            "hpi_bridge_control_then_speech_consume_no_rf"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        probe = new InterphoneProbe(getFilesDir());
        probe.setProgressListener((code, bigBanner, detailLine) ->
                runOnUiThread(() -> applyPhase(code, bigBanner, detailLine)));
        readLaunchDisplayFields(getIntent());
        setContentView(createContentView());
        handleLaunchIntent(getIntent(), "onCreate");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readLaunchDisplayFields(intent);
        handleLaunchIntent(intent, "onNewIntent");
    }

    private void readLaunchDisplayFields(Intent intent) {
        probeMode = intent.getStringExtra("mode");
        hpiRoute = intent.getStringExtra("hpi_route");
        powerCode = intent.getIntExtra("power_code", -1);
        allowPotentialRf = intent.getBooleanExtra("allow_potential_rf", false);
        allowChannelMutation = intent.getBooleanExtra(
                "allow_channel_mutation", false);
    }

    private void handleLaunchIntent(Intent intent, String entry) {
        final String mode = intent.getStringExtra("mode");
        final String route = intent.getStringExtra("hpi_route");
        final int requestedPower = intent.getIntExtra("power_code", -1);
        final boolean rfAllowed = intent.getBooleanExtra(
                "allow_potential_rf", false);
        final boolean channelAllowed = intent.getBooleanExtra(
                "allow_channel_mutation", false);
        boolean runExtra = intent.getBooleanExtra("run", false);
        final String sessionId = intent.getStringExtra("launch_session_id");
        android.util.Log.i("H13InterphoneProbe",
                "LAUNCH " + entry + " mode=" + mode
                        + " run=" + runExtra
                        + " session=" + sessionId
                        + " channel=" + channelAllowed
                        + " rf=" + rfAllowed
                        + " power=" + requestedPower);
        // 运行中的唯一会话优先于新Intent。新Intent无论合法与否都不得改写
        // probe内的会话号，也不得覆盖运行中会话的原子结果。
        if (acceptedLaunchSessionId != null || runInProgress.get()) {
            if (sessionId != null && sessionId.equals(acceptedLaunchSessionId)) {
                android.util.Log.w("H13InterphoneProbe",
                        "LAUNCH duplicate ignored session=" + sessionId);
            } else {
                android.util.Log.e("H13InterphoneProbe",
                        "LAUNCH concurrent session rejected current="
                                + acceptedLaunchSessionId + " requested=" + sessionId);
            }
            return;
        }
        String rejection = validateLaunchRequest(mode, runExtra, sessionId,
                rfAllowed, channelAllowed, requestedPower);
        if (rejection != null) {
            android.util.Log.e("H13InterphoneProbe",
                    "LAUNCH reject entry=" + entry + " mode=" + mode
                            + " session=" + sessionId + " reason=" + rejection);
            probe.setLaunchSessionId(sessionId);
            probe.writeLaunchAtomic(false,
                    "失败：启动合同拒绝：" + rejection,
                    launchRejectionExtra(mode, entry, runExtra, channelAllowed,
                            rfAllowed, "launch_contract"));
            return;
        }
        acceptedLaunchSessionId = sessionId;
        probe.setLaunchSessionId(sessionId);
        runButton.post(() -> runProbe(mode, route, requestedPower, rfAllowed,
                channelAllowed, sessionId));
    }

    @Override
    protected void onDestroy() {
        if (probe != null) {
            probe.close();
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    private View createContentView() {
        int padding = dp(8);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(Color.rgb(245, 246, 247));

        TextView title = new TextView(this);
        String ver = BuildConfig.VERSION_NAME;
        title.setText("fm_voice_in_five_blocks_power_230400".equals(probeMode)
                ? "H13 FM VoiceIn Five Blocks Power " + powerCode + " " + ver
                : "fm_voice_in_one_block_power_230400".equals(probeMode)
                ? "H13 FM VoiceIn Power " + powerCode + " " + ver
                : "fm_voice_in_one_block_230400".equals(probeMode)
                ? "H13 FM VoiceIn One Block 230400 " + ver
                : "channel_config_hpi_entry_230400_no_rf".equals(probeMode)
                ? "H13 CH1 HPI Entry No RF " + ver
                : "power_calibration_snapshot_57600".equals(probeMode)
                ? "H13 Power Calibration Snapshot 57600 " + ver
                : "sram_preflight_57600".equals(probeMode)
                ? "H13 SRAM Preflight 57600 " + ver
                : "external_encoded_tx_route60_no_rf_57600".equals(probeMode)
                ? "H13 External Encoded TX Route 60 No RF " + ver
                : "local_one_chan_d_input_only_57600".equals(probeMode)
                ? "H13 One CHAN_D Input Only 57600 " + ver
                : "local_one_chan_d_57600".equals(probeMode)
                ? "H13 One CHAN_D 57600 " + ver
                : "local_work_57600".equals(probeMode)
                ? "H13 Local Work 57600 " + ver
                : "local_process_57600".equals(probeMode)
                ? "H13 Local Process 57600 " + ver
                : "local_route_57600".equals(probeMode)
                ? "H13 Local Route 57600 " + ver
                : "local_chan_d_decode".equals(probeMode)
                ? "H13 Local CHAN_D Decode " + ver
                : "restore_roundtrip".equals(probeMode)
                ? "H13 Restore Roundtrip " + ver
                : "hpi_pcm_hunt".equals(probeMode)
                ? "H13 HPI PCM Hunt " + ver + " " + (hpiRoute == null ? "voice_out" : hpiRoute)
                : "rx_pcm_stream".equals(probeMode)
                        ? "H13 P2 RX PCM Stream " + ver
                        : "p0_session_smoke".equals(probeMode)
                                ? "H13 P0 Session Smoke " + ver
                                : "voice_out_pcm_57600_control_a".equals(probeMode)
                                        ? "H13 Control A " + ver
                                        : "voice_out_pcm_230400_control_b".equals(probeMode)
                                                || "voice_out_pcm_230400_rx".equals(probeMode)
                                                ? "H13 Control B " + ver
                                                : "chan_d_long_stream".equals(probeMode)
                                                        || probeMode == null
                                                        || probeMode.isEmpty()
                                                        ? "H13 CHAN_D Long Stream " + ver
                                                        : "H13 Probe " + ver + " mode="
                                                                + probeMode);
        title.setTextSize(14);
        title.setTextColor(Color.rgb(25, 30, 35));
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));

        // Big operator banner — primary UX on 240x320
        banner = new TextView(this);
        banner.setText("待机 Idle");
        banner.setTextSize(22);
        banner.setTypeface(Typeface.DEFAULT_BOLD);
        banner.setTextColor(Color.WHITE);
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(dp(6), dp(10), dp(6), dp(10));
        banner.setBackgroundColor(Color.rgb(60, 70, 80));
        root.addView(banner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(72)));

        detail = new TextView(this);
        detail.setText("等 RUN 或 adb 自动启动");
        detail.setTextSize(12);
        detail.setTextColor(Color.rgb(30, 35, 40));
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(dp(4), dp(4), dp(4), dp(4));
        detail.setBackgroundColor(Color.rgb(230, 235, 240));
        root.addView(detail, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setText("Idle");
        status.setTextSize(11);
        status.setTextColor(Color.rgb(35, 40, 45));
        status.setTextIsSelectable(true);
        status.setPadding(0, dp(4), 0, dp(4));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(status, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        runButton = new Button(this);
        runButton.setText("RUN");
        runButton.setOnClickListener(view -> {
            status.setText("拒绝执行：必须由带唯一会话标识的USB ADB宿主启动");
            status.setTextColor(Color.rgb(165, 35, 35));
            banner.setText("拒绝手动启动");
            banner.setBackgroundColor(Color.rgb(165, 35, 35));
            runButton.setEnabled(false);
        });
        actions.addView(runButton, new LinearLayout.LayoutParams(0, dp(44), 1f));

        Button closeButton = new Button(this);
        closeButton.setText("CLOSE");
        closeButton.setOnClickListener(view -> {
            probe.close();
            status.append("\nPort close requested");
        });
        actions.addView(closeButton, new LinearLayout.LayoutParams(0, dp(44), 1f));

        root.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private void applyPhase(String code, String bigBanner, String detailLine) {
        banner.setText(bigBanner);
        detail.setText(detailLine == null || detailLine.isEmpty()
                ? code : (code + " · " + detailLine));
        banner.setKeepScreenOn(true);
        int bg;
        int fg = Color.WHITE;
        if ("TRANSMIT_NOW".equals(code) || "WAITING_TAPE".equals(code)) {
            bg = Color.rgb(200, 40, 40); // red — fire / keep firing
        } else if ("TAPE_OK".equals(code) || "CAPTURING".equals(code)) {
            bg = Color.rgb(20, 130, 50); // green — keep holding
        } else if ("DONE_PASS".equals(code)) {
            bg = Color.rgb(10, 100, 40);
        } else if ("DONE_FAIL".equals(code)) {
            bg = Color.rgb(140, 20, 20);
        } else if ("RESTORE".equals(code)) {
            bg = Color.rgb(40, 90, 160); // blue — can release
        } else {
            bg = Color.rgb(60, 70, 80); // setup/idle grey
        }
        banner.setBackgroundColor(bg);
        banner.setTextColor(fg);
        status.append("\n[" + code + "] " + bigBanner
                + (detailLine == null || detailLine.isEmpty()
                ? "" : (" — " + detailLine)));
    }

    private void runProbe(String probeMode, String hpiRoute, int powerCode,
            boolean allowPotentialRf, boolean allowChannelMutation,
            String launchSessionId) {
        probe.setLaunchSessionId(launchSessionId);
        String rejection = validateLaunch(probeMode, allowPotentialRf,
                allowChannelMutation, powerCode);
        if (rejection != null) {
            android.util.Log.e("H13InterphoneProbe",
                    "LAUNCH reject mode=" + probeMode + " reason=" + rejection);
            probe.writeLaunchAtomic(false, "失败：启动校验拒绝：" + rejection,
                    launchRejectionExtra(probeMode, "runProbe", true,
                            allowChannelMutation, allowPotentialRf,
                            "launch_validation"));
            status.setText("拒绝执行：" + rejection);
            status.setTextColor(Color.rgb(165, 35, 35));
            banner.setText("拒绝执行");
            banner.setBackgroundColor(Color.rgb(165, 35, 35));
            runButton.setEnabled(false);
            return;
        }
        if (!runInProgress.compareAndSet(false, true)) {
            status.setText("拒绝执行：已有探针任务正在运行");
            status.setTextColor(Color.rgb(165, 35, 35));
            runButton.setEnabled(false);
            return;
        }
        runButton.setEnabled(false);
        status.setKeepScreenOn(true);
        status.setText("Running...");
        applyPhase("SETUP", "准备中…", "探针启动");
        executor.execute(() -> {
            ProbeResult result;
            try {
            if ("launch_contract_selftest_no_device".equals(probeMode)) {
                result = probe.runLaunchContractSelftest();
            } else if ("software_ambe_no_rf_selftest".equals(probeMode)) {
                result = SoftwareAmbeNoRfTest.run(getFilesDir(), getAssets());
            } else if ("software_ambe_data36_privacy_no_rf_selftest".equals(probeMode)) {
                result = SoftwareAmbeNoRfTest.runData36Privacy(
                        getFilesDir(), getAssets());
            } else if ("software_ambe_measured_privacy_no_rf".equals(probeMode)) {
                try {
                    SoftwareAmbeNoRfTest.VerifiedMorseAsset asset =
                            SoftwareAmbeNoRfTest.requireVerifiedMorse(getAssets());
                    result = probe.runSoftwareAmbeMeasuredPrivacyNoRf(
                            asset.pcmS16le, asset.ambe9);
                } catch (Exception error) {
                    result = new ProbeResult(false,
                            "软件摩尔斯AMBE准备失败：" + error.getMessage());
                }
            } else if ("software_ambe_session_ready_no_rf".equals(probeMode)) {
                try {
                    SoftwareAmbeNoRfTest.VerifiedMorseAsset asset =
                            SoftwareAmbeNoRfTest.requireVerifiedMorse(getAssets());
                    result = probe.runSoftwareAmbeSessionReadyNoRf(
                            asset.pcmS16le, asset.ambe9);
                } catch (Exception error) {
                    result = new ProbeResult(false,
                            "软件摩尔斯会话资产准备失败：" + error.getMessage());
                }
            } else if ("software_ambe_one_data36_no_rf".equals(probeMode)) {
                try {
                    SoftwareAmbeNoRfTest.VerifiedMorseAsset asset =
                            SoftwareAmbeNoRfTest.requireVerifiedMorse(getAssets());
                    result = probe.runSoftwareAmbeOneData36NoRf(
                            asset.pcmS16le, asset.ambe9);
                } catch (Exception error) {
                    result = new ProbeResult(false,
                            "软件摩尔斯单data36资产准备失败：" + error.getMessage());
                }
            } else if ("software_ambe_one_data36_low_power_rf".equals(
                    probeMode)) {
                try {
                    SoftwareAmbeNoRfTest.VerifiedMorseAsset asset =
                            SoftwareAmbeNoRfTest.requireVerifiedMorse(getAssets());
                    result = probe.runSoftwareAmbeOneData36LowPowerRf(
                            asset.pcmS16le, asset.ambe9);
                } catch (Exception error) {
                    result = new ProbeResult(false,
                            "软件摩尔斯低功率发射资产准备失败："
                                    + error.getMessage());
                }
            } else if ("software_ambe_full_morse_low_power_rf".equals(
                    probeMode)) {
                try {
                    SoftwareAmbeNoRfTest.VerifiedMorseAsset asset =
                            SoftwareAmbeNoRfTest.requireVerifiedMorse(getAssets());
                    result = probe.runSoftwareAmbeFullMorseLowPowerRf(
                            asset.pcmS16le, asset.ambe9);
                } catch (Exception error) {
                    result = new ProbeResult(false,
                            "软件完整摩尔斯低功率发射资产准备失败："
                                    + error.getMessage());
                }
            } else if ("software_ambe_production_full_morse_low_power_rf".equals(
                    probeMode)) {
                try {
                    SoftwareAmbeNoRfTest.VerifiedMorseAsset asset =
                            SoftwareAmbeNoRfTest.requireVerifiedMorse(getAssets());
                    result = probe.runSoftwareAmbeProductionFullMorseLowPowerRf(
                            asset.pcmS16le, asset.ambe9);
                } catch (Exception error) {
                    String failure = "软件生产同构摩尔斯低功率发射资产准备失败："
                            + error.getMessage();
                    probe.writeLaunchAtomic(false, "失败：" + failure,
                            launchRejectionExtra(probeMode, "asset_preflight",
                                    true, allowChannelMutation,
                                    allowPotentialRf, "ambe_asset"));
                    result = new ProbeResult(false, failure);
                }
            } else if ("software_ambe_production_rf_infrastructure_preflight_no_rf".equals(
                    probeMode)) {
                result = probe.runSoftwareAmbeProductionRfInfrastructurePreflightNoRf();
            } else if ("software_ambe_realtime_trigger_one_data36_no_rf".equals(
                    probeMode)) {
                try {
                    SoftwareAmbeNoRfTest.VerifiedMorseAsset asset =
                            SoftwareAmbeNoRfTest.requireVerifiedMorse(getAssets());
                    result = probe.runSoftwareAmbeRealtimeTriggerOneData36NoRf(
                            asset.pcmS16le, asset.ambe9);
                } catch (Exception error) {
                    result = new ProbeResult(false,
                            "软件摩尔斯实时触发单data36资产准备失败："
                                    + error.getMessage());
                }
            } else if ("analog_rf_carrier_pulse".equals(probeMode)) {
                result = probe.runAnalogRfCarrierPulseTest(powerCode, 600);
            } else if ("dmr_external_step1_offline_handshake".equals(probeMode)) {
                result = probe.runExternalDmrStep1OfflineHandshakeProof();
            } else if ("dmr_external_runtime_snapshot_57600".equals(probeMode)) {
                result = probe.runExternalDmrRuntimeSnapshot57600();
            } else if ("dmr_external_sct_ready_readonly_57600".equals(probeMode)) {
                result = probe.runExternalDmrSctReadyReadonly57600();
            } else if ("dmr_external_privacy_contract_preflight_57600".equals(
                    probeMode)) {
                result = probe.runExternalDmrPrivacyContractPreflight57600();
            } else if ("dmr_external_vlc_session_no_rf".equals(probeMode)) {
                result = probe.runExternalDmrVlcSessionNoRfProof();
            } else if ("dmr_external_setup0_only_no_rf".equals(probeMode)) {
                result = probe.runExternalDmrSetup0OnlyNoRfProof();
            } else if ("dmr_external_setup04_only_no_rf".equals(probeMode)) {
                result = probe.runExternalDmrSetup04OnlyNoRfProof();
            } else if ("dmr_external_setup_fullprep_isolation_no_rf".equals(
                    probeMode)) {
                result = probe.runExternalDmrSetupFullprepIsolationNoRfProof();
            } else if ("dmr_external_combined_window_readonly_dump_57600".equals(
                    probeMode)) {
                result = probe.runExternalDmrCombinedWindowReadonlyDump57600();
            } else if ("dmr_external_audited_zero_combined_window_57600".equals(
                    probeMode)) {
                result = probe.runExternalDmrAuditedZeroCombinedWindow57600();
            } else if ("dmr_external_one_data36_credit_no_rf".equals(probeMode)
                    || "dmr_external_step4_one_data36_credit".equals(probeMode)) {
                result = probe.runExternalDmrOneData36CreditNoRfProof();
            } else if ("dmr_external_realtime_relay_data36_credit".equals(
                    probeMode)) {
                result = probe.runExternalDmrRealtimeRelayData36CreditProof();
            } else if ("dmr_external_fixed_asset_realtime_trigger_data36_credit".equals(
                    probeMode)) {
                try {
                    result = probe.runExternalDmrFixedAssetRealtimeTriggerData36CreditProof(
                            readAsset("v090_chan_d_244units.bin"));
                } catch (Exception error) {
                    result = new ProbeResult(false,
                            "固定资产读取失败：" + error.getMessage());
                }
            } else if ("vocoder_encoder_loopback_no_rf".equals(probeMode)) {
                result = probe.runVocoderEncoderLoopbackNoRfProof();
            } else if ("vocoder_encoder_no_input_baseline_230400_no_rf".equals(
                    probeMode)) {
                result = probe.runHardwareEncoderNoInputBaseline230400NoRf();
            } else if ("vocoder_encoder_one_zero_pcm_230400_no_rf".equals(
                    probeMode)) {
                result = probe.runHardwareEncoderOneZeroPcm230400NoRf();
            } else if ("vocoder_encoder_one_tone_pcm_230400_no_rf".equals(
                    probeMode)) {
                result = probe.runHardwareEncoderOneTonePcm230400NoRf();
            } else if ("vocoder_encoder_two_stage_tone_pcm_230400_no_rf".equals(
                    probeMode)) {
                result = probe.runHardwareEncoderTwoStageTonePcm230400NoRf();
            } else if ("hpi_hobib_observer_no_hpi_no_rf".equals(probeMode)) {
                result = probe.runHobibObserverNoHpiNoRf();
            } else if ("hpi_frame_adapter_arm_ready_no_hpi_no_rf".equals(
                    probeMode)) {
                result = probe.runFrameAdapterArmReadyNoHpiNoRf();
            } else if ("hpi_frame_adapter_hpi_stage_no_pcm_no_rf".equals(
                    probeMode)) {
                result = probe.runFrameAdapterHpiStageNoPcmNoRf();
            } else if ("hpi_frame_adapter_truncated_pcm_hpi_stage_no_rf".equals(
                    probeMode)) {
                result = probe.runFrameAdapterTruncatedPcmHpiStageNoRf();
            } else if ("hpi_frame_adapter_complete_pcm_hpi_stage_no_rf".equals(
                    probeMode)) {
                result = probe.runFrameAdapterCompletePcmHpiStageNoRf();
            } else if ("hpi_frame_adapter_complete_pcm_hpi_vocoder90_no_rf".equals(
                    probeMode)) {
                // 已否决：文本面 preArm VOCODER；保留入口仅返回失败说明。
                result = probe.runFrameAdapterCompletePcmHpiVocoder90NoRf();
            } else if ("hpi_bridge_vocoder_off_only_no_rf".equals(probeMode)) {
                result = probe.runBridgeVocoderOffOnlyNoRf();
            } else if ("hpi_bridge_vocoder_off80_no_rf".equals(probeMode)) {
                result = probe.runBridgeVocoderOff80NoRf();
            } else if ("hpi_bridge_vocoder_off80_process_no_rf".equals(
                    probeMode)) {
                result = probe.runBridgeVocoderOff80ProcessNoRf();
            } else if ("hpi_bridge_vocoder_control_full_no_rf".equals(
                    probeMode)) {
                result = probe.runBridgeVocoderControlFullNoRf();
            } else if ("hpi_bridge_control_then_speech_consume_no_rf".equals(
                    probeMode)) {
                result = probe.runBridgeControlThenSpeechConsumeNoRf();
            } else if ("hpi_frame_adapter_truncated_pcm_no_hpi_no_rf".equals(
                    probeMode)) {
                result = probe.runFrameAdapterTruncatedPcmNoHpiNoRf();
            } else if ("hpi_frame_adapter_complete_receive_no_hpi_no_rf".equals(
                    probeMode)) {
                result = probe.runFrameAdapterCompleteReceiveNoHpiNoRf();
            } else if ("hpi_frame_adapter_complete_tone_no_rf".equals(
                    probeMode)) {
                result = probe.runFrameAdapterCompleteTonePcmNoRf();
            } else if ("hpi_frame_adapter_tone_to_dmr_low_power".equals(
                    probeMode)) {
                result = probe.runFrameAdapterToneToDmrLowPower();
            } else if ("vocoder_encoder_zero_230400_no_rf".equals(probeMode)) {
                result = probe.runVocoderEncoderZero230400NoRfProof();
            } else if ("vocoder_encoder_tone_230400_no_rf".equals(probeMode)) {
                result = probe.runVocoderEncoderTone230400NoRfProof();
            } else if ("channel_config_hpi_entry_230400_no_rf".equals(probeMode)) {
                result = probe.runChannelConfigHpiEntry230400NoRfProof();
            } else if ("dmr_external_encoded_one_unit_230400".equals(probeMode)
                    || "dmr_external_encoded".equals(probeMode)) {
                result = probe.runDmrExternalEncodedOneUnit230400(powerCode);
            } else if ("dmr_voice_in_morse_abcdef_power_230400".equals(probeMode)
                    || "dmr_voice_in_morse".equals(probeMode)) {
                result = probe.runDmrVoiceInMorseAbcdefPower230400(powerCode);
            } else if ("fm_bridge_codec_diagnostic_230400".equals(probeMode)) {
                result = probe.runFmBridgeCodecDiagnostic230400();
            } else if ("fm_voice_in_morse_abcdef_power_230400".equals(probeMode)) {
                result = probe.runFmVoiceInMorseAbcdefPower230400(powerCode);
            } else if ("fm_voice_in_five_blocks_power_230400".equals(probeMode)) {
                result = probe.runFmVoiceInFiveBlocksPower230400(powerCode);
            } else if ("fm_voice_in_one_block_power_230400".equals(probeMode)) {
                result = probe.runFmVoiceInOneBlockPower230400(powerCode);
            } else if ("fm_voice_in_one_block_230400".equals(probeMode)) {
                result = probe.runFmVoiceInOneBlock230400();
            } else if ("power_calibration_snapshot_57600".equals(probeMode)) {
                result = probe.runPowerCalibrationSnapshot57600();
            } else if ("sram_preflight_57600".equals(probeMode)) {
                result = probe.runSramPreflight57600();
            } else if ("external_encoded_tx_route60_no_rf_57600".equals(probeMode)) {
                result = probe.runExternalEncodedTxRoute60NoRfProof();
            } else if ("local_one_chan_d_input_only_57600".equals(probeMode)) {
                try {
                    result = probe.runLocalOneChanDInputOnly57600Proof(
                            readAsset("v090_chan_d_244units.bin"));
                } catch (Exception error) {
                    result = new ProbeResult(false,
                            "Asset load failed: " + error.getMessage());
                }
            } else if ("local_one_chan_d_57600".equals(probeMode)) {
                try {
                    result = probe.runLocalOneChanD57600Proof(
                            readAsset("v090_chan_d_244units.bin"));
                } catch (Exception error) {
                    result = new ProbeResult(false,
                            "Asset load failed: " + error.getMessage());
                }
            } else if ("local_work_57600".equals(probeMode)) {
                result = probe.runLocalWork57600Proof();
            } else if ("local_process_57600".equals(probeMode)) {
                result = probe.runLocalProcess57600Proof();
            } else if ("local_route_57600".equals(probeMode)) {
                result = probe.runLocalRoute57600Proof();
            } else if ("local_chan_d_decode".equals(probeMode)) {
                try {
                    result = probe.runLocalChanDDecodeProof(
                            readAsset("v090_chan_d_244units.bin"));
                } catch (Exception error) {
                    result = new ProbeResult(false,
                            "Asset load failed: " + error.getMessage());
                }
            } else if ("restore_roundtrip".equals(probeMode)) {
                result = probe.runRestoreRoundTrip();
            } else if ("hpi_pcm_hunt".equals(probeMode)) {
                result = probe.runHpiPcmRouteHunt(
                        hpiRoute == null ? "voice_out" : hpiRoute);
            } else if ("rx_pcm_stream".equals(probeMode)) {
                result = probe.runRxPcmProductStream();
            } else if ("chan_d_long_stream".equals(probeMode)) {
                result = probe.run();
            } else if ("p0_session_smoke".equals(probeMode)) {
                result = probe.runP0SessionSmoke();
            } else if ("ram_survey".equals(probeMode)) {
                result = probe.runRamSurvey();
            } else if ("ram_exec_proof".equals(probeMode)) {
                result = probe.runRamExecutionProof();
            } else if ("pendsv_exec_proof".equals(probeMode)) {
                result = probe.runPendSvExecutionProof();
            } else if ("hpi_loader_roundtrip_proof".equals(probeMode)) {
                result = probe.runHpiLoaderRoundTripProof();
            } else if ("pendsv_priority_byte_proof".equals(probeMode)) {
                result = probe.runPendSvPriorityByteProof();
            } else if ("scb_priority_readonly_57600".equals(probeMode)) {
                result = probe.runScbPriorityReadonly57600();
            } else if ("segmented_baseline_loader_proof".equals(probeMode)) {
                result = probe.runSegmentedBaselineLoaderProof();
            } else if ("uart_wake_kick_proof".equals(probeMode)) {
                result = probe.runUartWakeKickProof();
            } else if ("uart_hpi_bridge_timeout_proof".equals(probeMode)) {
                result = probe.runUartHpiBridgeTimeoutProof();
            } else if ("realtime_data_plane_probe".equals(probeMode)) {
                result = probe.runRealtimeDataPlaneProbe();
            } else if ("sct_flash_dump".equals(probeMode)) {
                result = probe.runSctFlashDump();
            } else if ("mode0_voice_out_pcm_capture".equals(probeMode)) {
                result = probe.runMode0VoiceOutPcmCapture();
            } else if ("dmr_rx_multiplane_capture".equals(probeMode)) {
                result = probe.runDmrRxMultiplaneCapture();
            } else if ("uart_baud_keep_57600_isr".equals(probeMode)) {
                result = probe.runUartBaudKeep57600IsrProof();
            } else if ("uart_baud_230400_roundtrip".equals(probeMode)) {
                result = probe.runUartBaud230400RoundTrip();
            } else if ("uart_hpi_bridge_230400_smoke".equals(probeMode)) {
                result = probe.runUartHpiBridge230400Smoke();
            } else if ("voice_out_pcm_57600_control_a".equals(probeMode)) {
                result = probe.runVoiceOutPcm57600ControlA();
            } else if ("voice_out_pcm_230400_control_b".equals(probeMode)
                    || "voice_out_pcm_230400_rx".equals(probeMode)) {
                result = probe.runVoiceOutPcm230400ControlB();
            } else if ("baseline_reload_proof".equals(probeMode)) {
                result = probe.runBaselineReloadProof();
            } else {
                result = new ProbeResult(false,
                        "拒绝执行：未知模式 " + probeMode);
            }
            } catch (Throwable error) {
                result = new ProbeResult(false,
                        "探针执行异常：" + error.getClass().getSimpleName()
                                + "：" + error.getMessage());
            }
            final ProbeResult finalResult = result;
            runOnUiThread(() -> {
                status.setText(finalResult.report);
                status.setTextColor(finalResult.success
                        ? Color.rgb(20, 105, 55)
                        : Color.rgb(165, 35, 35));
                status.setKeepScreenOn(false);
                banner.setKeepScreenOn(false);
                runInProgress.set(false);
                runButton.setEnabled(true);
            });
        });
    }

    static boolean isKnownProbeMode(String mode) {
        return containsMode(KNOWN_PROBE_MODES, mode);
    }

    static boolean isPotentialRfMode(String mode) {
        return containsMode(POTENTIAL_RF_MODES, mode);
    }

    static String validateLaunch(String mode, boolean allowPotentialRf,
            boolean allowChannelMutation, int powerCode) {
        if (!isKnownProbeMode(mode)) {
            return "模式为空或不在明确清单中";
        }
        if (containsMode(BLOCKED_RF_MODES, mode)) {
            return "该旧射频入口已冻结：普通DMOPTT麦克风路径与外部编码DMR会话冲突";
        }
        if (isPotentialRfMode(mode) && !allowPotentialRf) {
            return "该模式可能进入发送状态，缺少allow_potential_rf明确门";
        }
        if (containsMode(POTENTIAL_RF_PERMISSION_FORBIDDEN_MODES, mode)
                && allowPotentialRf) {
            return "该本地无射频模式禁止allow_potential_rf参数";
        }
        if (containsMode(CHANNEL_MUTATION_MODES, mode)
                && !allowChannelMutation) {
            return "该模式会改变数字频道，缺少allow_channel_mutation明确门";
        }
        if (containsMode(FIXED_LOW_POWER_DMR_MODES, mode)
                && powerCode != -1) {
            return "该模式固定使用频道low，禁止power_code覆盖";
        }
        if (containsMode(POWER_CODE_FORBIDDEN_MODES, mode)
                && powerCode != -1) {
            return "该本地无射频模式禁止power_code参数";
        }
        if (containsMode(POWER_CODE_MODES, mode)
                && (powerCode < 0 || powerCode > 2030)) {
            return "该模式必须显式提供0至2030范围内的power_code";
        }
        return null;
    }

    static String validateLaunchRequest(String mode, boolean runRequested,
            String launchSessionId, boolean allowPotentialRf,
            boolean allowChannelMutation, int powerCode) {
        if (!runRequested) {
            return "缺少run=true明确执行门";
        }
        if (!isValidLaunchSessionId(launchSessionId)) {
            return "launch_session_id为空或格式非法";
        }
        return validateLaunch(mode, allowPotentialRf, allowChannelMutation,
                powerCode);
    }

    static boolean isValidLaunchSessionId(String launchSessionId) {
        return launchSessionId != null
                && launchSessionId.matches("[A-Za-z0-9._-]{16,64}");
    }

    static String launchRejectionExtra(String mode, String entry,
            boolean runRequested, boolean allowChannelMutation,
            boolean allowPotentialRf, String rejectionStage) {
        StringBuilder extra = new StringBuilder();
        extra.append("launch_entry=").append(entry == null ? "" : entry)
                .append('\n');
        extra.append("probe_mode=").append(mode == null ? "" : mode)
                .append('\n');
        extra.append("launch_run_extra=").append(runRequested).append('\n');
        extra.append("allow_channel_mutation=")
                .append(allowChannelMutation).append('\n');
        extra.append("allow_potential_rf=").append(allowPotentialRf)
                .append('\n');
        extra.append("startup_rejection_stage=")
                .append(rejectionStage == null ? "" : rejectionStage)
                .append('\n');
        if ("software_ambe_production_full_morse_low_power_rf".equals(mode)) {
            extra.append("startup_rejected=true\n");
            extra.append("rf_prepare_executed=false\n");
            extra.append("work_mode_tx_sent=false\n");
            extra.append("vlc_sent=false\n");
            extra.append("data36_written=false\n");
            extra.append("watchdog_expired=false\n");
        }
        extra.append("rf_command_count=0\n");
        return extra.toString();
    }

    private static boolean containsMode(String[] modes, String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        for (String mode : modes) {
            if (mode.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private byte[] readAsset(String name) throws Exception {
        try (InputStream input = getAssets().open(name);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    output.write(buffer, 0, count);
                }
            }
            return output.toByteArray();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
