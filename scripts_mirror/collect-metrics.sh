#!/usr/bin/env bash
set -euo pipefail

# -- Environment & Logging Setup --
ENV_FILE="/etc/metrics-agent.env"
LOG_FILE="/var/log/metrics-agent.log"
# Ensure log file exists and is writable
sudo touch "$LOG_FILE"
sudo chmod 666 "$LOG_FILE"
exec >> "$LOG_FILE" 2>&1

# -- Configuration --
if [[ -f "$ENV_FILE" ]]; then
    METRICS_API_KEY=$(cat "$ENV_FILE" | tr -d '[:space:]')
fi

FULL_HOSTNAME=$(hostname)
CLEAN_NAME="${FULL_HOSTNAME%.england}"

ENDPOINT="${METRICS_ENDPOINT:-https://portfolioapi.englandtechnologies.net/api/metrics}"
API_KEY="${METRICS_API_KEY:-REPLACE_ME}"

# HOSTNAME_ID: swaps dots for dashes (file.server -> file-server) for HTML IDs
# LABEL: keeps dots (file.server) for the display label
HOSTNAME_ID="${METRICS_HOSTNAME:-$(echo "$CLEAN_NAME" | tr '.' '-' | tr '[:upper:]' '[:lower:]')}"
LABEL="${METRICS_LABEL:-$CLEAN_NAME}"

# -- Metrics Gathering --
UPTIME_RAW=$(uptime -p 2>/dev/null || uptime | sed 's/.*up /up /' | cut -d',' -f1-2)
UPTIME=$(echo "$UPTIME_RAW" | sed 's/^up //')

cpu_usage() {
    local line1 line2
    read -r line1 < /proc/stat
    sleep 0.5
    read -r line2 < /proc/stat
    read -r _ u1 n1 s1 i1 w1 r1 q1 _ <<< "$line1"
    read -r _ u2 n2 s2 i2 w2 r2 q2 _ <<< "$line2"
    local idle1=$(( i1 + w1 ))
    local idle2=$(( i2 + w2 ))
    local total1=$(( u1+n1+s1+i1+w1+r1+q1 ))
    local total2=$(( u2+n2+s2+i2+w2+r2+q2 ))
    local d_total=$(( total2 - total1 ))
    local d_idle=$(( idle2 - idle1 ))
    if (( d_total == 0 )); then echo "0.0"; return; fi
    local usage_x1000=$(( (d_total - d_idle) * 1000 / d_total ))
    awk "BEGIN { printf \"%.1f\", $usage_x1000 / 10 }"
}
CPU_USAGE=$(cpu_usage)

CPU_TEMP=$(inxi -s --output-file print 2>/dev/null | grep -i "cpu thermal\|package\|tdie\|temp" | grep -oP '\d+\.\d+(?= C)' | head -1 || echo "null")
if [[ -z "$CPU_TEMP" || "$CPU_TEMP" == "null" ]]; then
    CPU_TEMP=$(cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null | awk '{printf "%.1f", $1/1000}' || echo "null")
fi

mem_info() {
    local mem_total mem_free mem_buffers mem_cached mem_sreclaimable
    mem_total=$(grep MemTotal: /proc/meminfo | awk '{print $2}')
    mem_free=$(grep MemFree: /proc/meminfo | awk '{print $2}')
    mem_buffers=$(grep Buffers: /proc/meminfo | awk '{print $2}')
    mem_cached=$(grep "^Cached:" /proc/meminfo | awk '{print $2}')
    mem_sreclaimable=$(grep SReclaimable: /proc/meminfo | awk '{print $2}')
    local total_mib=$(( mem_total / 1024 ))
    local used_kb=$(( mem_total - mem_free - mem_buffers - mem_cached - mem_sreclaimable ))
    local used_mib=$(( used_kb / 1024 ))
    local pct=$(awk "BEGIN { printf \"%.1f\", ($used_mib / $total_mib) * 100 }")
    echo "${used_mib} ${total_mib} ${pct}"
}
read -r RAM_USED_MIB RAM_TOTAL_MIB RAM_PCT <<< "$(mem_info)"

build_disk_json() {
    local json_array="["
    local first=true
    while IFS= read -r line; do
        local device used_gib total_gib pct_raw mount
        device=$(echo "$line" | awk '{print $1}')
        total_gib=$(echo "$line" | awk '{printf "%.1f", $2/1048576}')
        used_gib=$(echo "$line" | awk '{printf "%.1f", $3/1048576}')
        pct_raw=$(echo "$line" | awk '{print $5}' | tr -d '%')
        mount=$(echo "$line" | awk '{print $6}')
        [[ "$first" == false ]] && json_array+=","
        json_array+="{\"device\":\"${mount}\",\"usedGiB\":${used_gib},\"totalGiB\":${total_gib},\"usagePct\":${pct_raw}}"
        first=false
    done < <(df --block-size=1K --output=source,size,used,avail,pcent,target | tail -n +2 | grep -v -E 'tmpfs|devtmpfs|udev|overlay|shm|cgroupfs|none|efivars|efivarfs|/boot|^/dev/loop')
    json_array+="]"
    echo "$json_array"
}
DISK_JSON=$(build_disk_json)

PAYLOAD=$(jq -n \
    --arg hostname "$HOSTNAME_ID" \
    --arg label "$LABEL" \
    --arg uptime "$UPTIME" \
    --argjson cpuUsage "$CPU_USAGE" \
    --argjson cpuTemp "${CPU_TEMP:-null}" \
    --argjson ramUsedMiB "$RAM_USED_MIB" \
    --argjson ramTotalMiB "$RAM_TOTAL_MIB" \
    --argjson ramUsagePct "$RAM_PCT" \
    --argjson disks "$DISK_JSON" \
    '{hostname:$hostname, label:$label, uptime:$uptime, cpuUsage:$cpuUsage, cpuTemp:$cpuTemp, ramUsedMiB:$ramUsedMiB, ramTotalMiB:$ramTotalMiB, ramUsagePct:$ramUsagePct, disks:$disks}')

HTTP_STATUS=$(curl --silent --output /dev/null --write-out "%{http_code}" --max-time 10 -X POST -H "Content-Type: application/json" -H "X-Metrics-Key: ${API_KEY}" -d "$PAYLOAD" "$ENDPOINT")

if [[ "$HTTP_STATUS" == "200" ]]; then
    echo "[$(date -u +%FT%TZ)] OK - sent for ${HOSTNAME_ID}"
else
    echo "[$(date -u +%FT%TZ)] ERR - HTTP ${HTTP_STATUS}" >&2
fi
