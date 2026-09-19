package net.elfradio.h13dmrtx;

final class DeviceModePolicy {
    private DeviceModePolicy() {
    }

    static boolean autoStartsWithoutRf(String mode) {
        return DmrTxController.MODE_NO_RF.equals(mode)
                || DmrTxController.MODE_DEADLINE_HANDSHAKE_NO_RF.equals(mode)
                || DmrTxController.MODE_SETUP0_ONLY.equals(mode)
                || DmrTxController.MODE_CLEAR_ONLY.equals(mode)
                || DmrTxController.MODE_RELAY_ONE_NO_RF.equals(mode)
                || DmrTxController.MODE_RELAY_FIXED_ASSET_NO_RF.equals(mode)
                || DmrTxController.MODE_RELAY_FIXED_ASSET_THREE_NO_RF.equals(mode)
                || DmrTxController.MODE_RELAY_FIXED_ASSET_FIVE_NO_RF.equals(mode)
                || DmrTxController.MODE_RELAY_FIXED_ASSET_TWENTYFIVE_NO_RF.equals(mode)
                || DmrTxController.MODE_RELAY_SOFTWARE_49BIT_NO_RF.equals(mode)
                || DmrTxController.MODE_RELAY_SOFTWARE_49BIT_TONE800_NO_RF.equals(mode)
                || DmrTxController.MODE_RELAY_SOFTWARE_49BIT_MORSE_NO_RF.equals(mode)
                || DmrTxController.MODE_RELAY_ENCODE_DMR_SILENCE_NO_RF.equals(mode)
                || DmrTxController.MODE_RELAY_ENCODE_DMR_TONE800_NO_RF.equals(mode)
                || DmrTxController.MODE_RELAY_ENCODE_DMR_TONE800_THREE_NO_RF.equals(mode)
                || DmrTxController.MODE_RELAY_ENCODE_DMR_TONE800_FIVE_NO_RF.equals(mode)
                || DmrTxController.MODE_RELAY_ENCODE_DMR_MORSE_UNIQUE_NO_RF.equals(mode)
                || DmrTxController.MODE_RELAY_ENCODE_DMR_MORSE_UNIQUE_THREE_NO_RF.equals(mode)
                || DmrTxController.MODE_RELAY_ENCODE_DMR_MORSE_UNIQUE_FIVE_NO_RF.equals(mode)
                || DmrTxController.MODE_RELAY_SOFTWARE_PRIVACY_MORSE_TWENTYFIVE_NO_RF.equals(mode)
                || DmrTxController.MODE_RELAY_SOFTWARE_PRIVACY_TRIPLE_SOS_NO_RF.equals(mode)
                || DmrTxController.MODE_POST_VLC_THREE_LIVE_SOFTWARE_ONE_NO_RF.equals(mode)
                || DmrTxController.MODE_ACK_PACED_VLC_SOFTWARE_ONE_NO_RF.equals(mode)
                || DmrTxController.MODE_ACK_PACED_VLC_SOFTWARE_TRIPLE_SOS_NO_RF.equals(mode)
                || DmrTxController.isVoiceBurstMode(mode)
                || DmrTxController.isSpeechAz09Mode(mode)
                // 短素材的无射频变体在这里登记；低功率发射变体不登记，
                // 与三遍SOS低功率一致，由MainActivity的射频分支带许可启动。
                || DmrTxController.MODE_SPEECH_SHORT_ABC_NO_RF.equals(mode);
    }

    static boolean showsRelayStatus(String mode) {
        return autoStartsWithoutRf(mode)
                && !DmrTxController.MODE_NO_RF.equals(mode)
                && !DmrTxController.MODE_DEADLINE_HANDSHAKE_NO_RF.equals(mode)
                && !DmrTxController.MODE_SETUP0_ONLY.equals(mode)
                && !DmrTxController.MODE_CLEAR_ONLY.equals(mode);
    }
}
