#!/system/bin/sh

DEST=/sdcard/h13_dynsplit0_round2_freeze_20260806
mkdir -p "$DEST"

date '+%Y-%m-%d %H:%M:%S %z' > "$DEST/freeze_time.txt"

# Pause only the three recorder PIDs established before the overnight test.
for pid in 3232 3459 3275; do
    if [ -r "/proc/$pid/cmdline" ]; then
        cmdline=$(tr '\000' ' ' < "/proc/$pid/cmdline")
        echo "$pid $cmdline" >> "$DEST/recorder_processes.txt"
        case "$cmdline" in
            *iwpriv-probe*|*prima-logcat-dynsplit0-round2*|*com.qualcomm.qti.logkit*)
                kill -STOP "$pid"
                ;;
        esac
    fi
done

cp -p /data/local/tmp/prima-monitor.log* "$DEST"/ 2>/dev/null
cp -p /data/local/tmp/prima-logcat-dynsplit0-round2.txt* "$DEST"/ 2>/dev/null
cp -pr /data/local/tmp/wlan_logs_dynsplit0_round2 "$DEST"/ 2>/dev/null

dumpsys wifi > "$DEST/dumpsys_wifi.txt" 2>&1
dumpsys connectivity > "$DEST/dumpsys_connectivity.txt" 2>&1
dumpsys power > "$DEST/dumpsys_power.txt" 2>&1
dmesg > "$DEST/dmesg.txt" 2>&1
logcat -d -b all -v threadtime > "$DEST/logcat_immediate_all.txt" 2>&1
iwpriv wlan0 getConfig > "$DEST/iwpriv_getConfig.txt" 2>&1
iwconfig wlan0 > "$DEST/iwconfig_wlan0.txt" 2>&1
ifconfig wlan0 > "$DEST/ifconfig_wlan0.txt" 2>&1
ip addr show wlan0 > "$DEST/ip_addr_wlan0.txt" 2>&1
ip route show table all > "$DEST/ip_route_all.txt" 2>&1
cat /proc/net/wireless > "$DEST/proc_net_wireless.txt" 2>&1
cat /proc/net/arp > "$DEST/proc_net_arp.txt" 2>&1
ps -A > "$DEST/ps_A.txt" 2>&1
date '+%Y-%m-%d %H:%M:%S %z' > "$DEST/capture_complete_time.txt"
sync

echo "$DEST"
