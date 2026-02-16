#!/bin/bash

TARGET="google.com"
WAIT_TIME=10
INCREMENT=10
MAX_WAIT=60

while true; do
    # -c 4 sends 4 packets
    if ping -c 4 "$TARGET" > /dev/null 2>&1; then
        echo "Network is up. Running maintenance tasks..."
        
        # Run your specified scripts
        ./wake_db_server
        ./daily_apt_check.sh
        
        # Exit the loop after success
        break
    else
        echo "Ping failed. Waiting $WAIT_TIME minutes before retrying."
        sleep "${WAIT_TIME}m"
        
        # Increase wait time for next failure, capping at MAX_WAIT
        if [ "$WAIT_TIME" -lt "$MAX_WAIT" ]; then
            WAIT_TIME=$((WAIT_TIME + INCREMENT))
        fi
    fi
done
