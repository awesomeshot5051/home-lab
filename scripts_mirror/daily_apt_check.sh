#!/bin/bash

# Run apt update
apt update -qq > /dev/null

# Get upgradable package count
UPGRADABLE=$(apt list --upgradable 2>/dev/null | grep -c 'upgradable from')

# Save message to a file readable by user
echo "$UPGRADABLE" > /tmp/apt_upgradable_count
chown root:users /tmp/apt_upgradable_count
chmod 644 /tmp/apt_upgradable_count

