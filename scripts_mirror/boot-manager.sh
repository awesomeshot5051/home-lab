#!/bin/bash

# Get current hour (0-23)
hour=$(date +%H)

# Check for maintenance windows:
# 08:00 - 08:59 (for the 08:05 boot)
# 22:00 - 23:59 (for the 10:50 boot - covers hour 22 and 23 just in case)
if [ "$hour" -eq 8 ] || [ "$hour" -eq 22 ] || [ "$hour" -eq 23 ]; then
    echo "Maintenance Window Detected. Stopping Heartbeat."
    
    # 1. Stop the conflicting service
    /usr/bin/systemctl stop heartbeat-controller.service

    # 2. Run your backup and update commands
    # (Add your actual backup commands below)
    echo "Starting updates..."
    /usr/bin/apt-get update && /usr/bin/apt-get upgrade -y
    
    echo "Starting backup..."
    # /path/to/your/backup_script.sh

    # 3. Optional: Shutdown after maintenance is done?
    # /usr/sbin/shutdown -h now
else
    echo "Normal boot detected. Leaving Heartbeat Controller running."
fi
