#!/bin/bash

TARGET="google.com"
WAIT_TIME=10
INCREMENT=10
MAX_WAIT=60
while true; do
    if ping -c 4 "$TARGET" > /dev/null 2>&1; then
        echo "Connection established. Running APT check..."
        ./daily_apt_check.sh
        break
    else
        echo "Ping failed. Retrying in $WAIT_TIME minutes."
        sleep "${WAIT_TIME}m"
        if [ "$WAIT_TIME" -lt "$MAX_WAIT" ]; then
            WAIT_TIME=$((WAIT_TIME + INCREMENT))
        fi
    fi
done
