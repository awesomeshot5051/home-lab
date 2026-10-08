#!/bin/bash
# nightly_suspend.sh: suspend to RAM for 8 hours via an RTC alarm,
# unless something needs the machine awake.
# rtcwake -m mem ignores systemd inhibitors, so every hold is checked here.
# Usage: nightly_suspend.sh [-n]    (-n = dry run, prints what it would do)

LOCKFILE="/tmp/suspend_disabled"
SLEEP_SECONDS=28800
DRY_RUN=0
[ "${1:-}" = "-n" ] && DRY_RUN=1

log() { logger -t nightly_suspend "$*"; echo "$*"; }

# 1. Manual hold from disablesuspend (the file holds an expiry time in epoch seconds)
if [ -f "$LOCKFILE" ]; then
    EXPIRY=$(cat "$LOCKFILE" 2>/dev/null)
    if [[ "$EXPIRY" =~ ^[0-9]+$ ]] && [ "$(date +%s)" -lt "$EXPIRY" ]; then
        log "Suspend skipped: disabled until $(date -d "@$EXPIRY" '+%F %T')."
        exit 0
    fi
    rm -f "$LOCKFILE"
fi

# 2. Monthly backup service is running (file server)
if systemctl is-active --quiet monthly-backup.service; then
    log "Suspend skipped: monthly backup is running."
    exit 0
fi

# 3. Backup inhibitor held by a remote backup (works on the backup server too)
if systemd-inhibit --list --no-legend 2>/dev/null | grep -q 'monthly-backup'; then
    log "Suspend skipped: a monthly-backup inhibitor is held."
    exit 0
fi

if [ "$DRY_RUN" -eq 1 ]; then
    log "Dry run: would suspend for ${SLEEP_SECONDS} seconds."
    exit 0
fi

log "Suspending for ${SLEEP_SECONDS} seconds."
/usr/sbin/rtcwake -m mem -s "$SLEEP_SECONDS"
