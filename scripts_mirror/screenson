#!/bin/bash

# Ensure the script is run as root
if [ "$EUID" -ne 0 ]; then
    echo "Error: Please run this script with sudo." >&2
    exit 1
fi

echo "Resetting hardware brightness to 100% across all displays..."

# Loop through display IDs 1, 2, and 3
for display_id in 1 2 3; do
    # Run ddcutil and discard both stdout and stderr
    ddcutil --display "$display_id" setvcp 10 100 &>/dev/null
done

echo "Done!"
