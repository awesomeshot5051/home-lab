#!/bin/bash

LOCKFILE="/tmp/suspend_disabled"

# If lock file exists and is recent (within 1 day), skip suspend
if [ -f "$LOCKFILE" ]; then
    if test `find "$LOCKFILE" -mmin -1440`; then
        echo "Suspend disabled for today."
        exit 0
    else
        rm -f "$LOCKFILE"
    fi
fi

/usr/sbin/rtcwake -m mem -s 28800

