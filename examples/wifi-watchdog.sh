#!/bin/sh
# Wi-Fi watchdog. The Pi has dropped off the network after about a day while
# everything else kept running (no SSH, gone from the Spotify app, Icecast
# still encoding). Ping the gateway; reconnect wlan0 if it stops answering,
# and reboot if reconnecting does not bring it back.
#
# Installed as /usr/local/sbin/wifi-watchdog.sh, run by wifi-watchdog.service.

IFACE=wlan0
INTERVAL=30          # seconds between checks
RECONNECT_AT=4       # failed checks before reconnecting (~2 min); again 8 later
REBOOT_AT=20         # failed checks before rebooting (~10 min)
MIN_UPTIME=900       # never reboot within 15 min of boot, so no reboot loop

fails=0
while :; do
    gw=$(ip -4 route show default dev "$IFACE" | awk '{print $3; exit}')
    if [ -n "$gw" ] && ping -c 3 -W 2 -I "$IFACE" "$gw" >/dev/null 2>&1; then
        if [ "$fails" -gt 0 ]; then
            echo "gateway $gw answering again after $fails failed check(s)"
        fi
        fails=0
    else
        fails=$((fails + 1))
        echo "gateway ${gw:-<no default route>} not answering ($fails)"
        if [ "$fails" -eq 1 ]; then
            # The state at the moment it fails is what explains it later.
            /usr/sbin/iw dev "$IFACE" link
            nmcli -t -f DEVICE,STATE,CONNECTION device status
        fi
        if [ "$fails" -ge "$REBOOT_AT" ]; then
            if [ "$(cut -d. -f1 /proc/uptime)" -ge "$MIN_UPTIME" ]; then
                echo "reconnecting did not help, rebooting"
                systemctl reboot
            fi
        elif [ $((fails % 8)) -eq "$RECONNECT_AT" ]; then
            echo "reconnecting $IFACE"
            nmcli device disconnect "$IFACE"
            sleep 2
            nmcli device connect "$IFACE"
        fi
    fi
    sleep "$INTERVAL"
done
