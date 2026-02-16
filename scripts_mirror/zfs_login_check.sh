#!/bin/bash
# Display ZFS and disk health summary at login

THRESH=80
HOST=$(hostname)
DATE=$(date +'%Y-%m-%d %H:%M:%S')
RED="\033[1;31m"
YELLOW="\033[1;33m"
GREEN="\033[1;32m"
RESET="\033[0m"

echo
echo -e "${YELLOW}===== System Health Check @ ${DATE} (${HOST}) =====${RESET}"

# --- ZFS pool health ---
BAD_POOLS=$(/usr/sbin/zpool status -x 2>/dev/null | grep -v "all pools are healthy" || true)
if [[ -z "$BAD_POOLS" ]]; then
    echo -e "${GREEN}ZFS pools: Healthy${RESET}"
else
    echo -e "${RED}!!! ZFS ALERT !!!${RESET}"
    /usr/sbin/zpool status -x
fi

# --- ZFS space usage ---
echo
echo "ZFS pool usage:"
/usr/sbin/zpool list -o name,size,alloc,free,cap,health

OVERFULL=$(/usr/sbin/zpool list -H -o name,cap | awk -v t=$THRESH '{gsub("%","",$2); if ($2+0 >= t) print $1":"$2"%"}')
if [[ -n "$OVERFULL" ]]; then
    echo -e "${RED}!!! Space usage above ${THRESH}% !!!${RESET}"
    echo "$OVERFULL" | sed 's/^/  - /'
fi

# --- vaultbackup filesystem usage ---
if df -P /vaultbackup &>/dev/null; then
    USE=$(df -P /vaultbackup | awk 'NR==2 {gsub("%","",$5); print $5}')
    if [[ "$USE" -ge "$THRESH" ]]; then
        echo -e "${RED}!!! /vaultbackup at ${USE}% !!!${RESET}"
    else
        echo -e "${GREEN}/vaultbackup usage OK (${USE}%)${RESET}"
    fi
fi

echo -e "${YELLOW}==============================================${RESET}"
echo
