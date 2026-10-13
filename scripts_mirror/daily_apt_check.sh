#!/bin/bash
#
# daily_apt_check.sh
# Runs as root (systemd timer / cron). Publishes the number of pending apt
# upgrades and the list of pending Flatpak updates for unprivileged readers.
#
# Output lives in /run/apt-status (root-owned, not writable by other users),
# so there is no predictable world-writable path an attacker could pre-create
# as a symlink. Files are written atomically (temp file + rename).
#
#   /run/apt-status/apt_upgradable_count   one line: number of upgradable packages
#   /run/apt-status/flatpak_updates        line 1: count, then one "name application" per line
#
# /run is a tmpfs, so these files vanish on reboot until the next run.
# Readers should treat a missing file as "not checked yet".

set -u
umask 022

STATUS_DIR="/run/apt-status"
LOCK_FILE="/run/apt-status.lock"
FILE_GROUP="users"

if [ "$(id -u)" -ne 0 ]; then
    echo "daily_apt_check: must run as root" >&2
    exit 1
fi

# Fall back to root if the group does not exist on this machine
if ! getent group "$FILE_GROUP" > /dev/null 2>&1; then
    FILE_GROUP="root"
fi

# Prevent overlapping runs
exec 9> "$LOCK_FILE" || exit 1
flock -n 9 || exit 0

# Create the directory. install -d does not follow a pre-existing symlink as
# the final component into a different place silently; we also refuse to
# continue if it turns out to be anything other than a real directory.
install -d -m 0755 -o root -g root "$STATUS_DIR" || exit 1
if [ -L "$STATUS_DIR" ] || [ ! -d "$STATUS_DIR" ]; then
    echo "daily_apt_check: $STATUS_DIR is not a plain directory" >&2
    exit 1
fi

# Atomically replace a status file with stdin
# usage: some_command | write_status /run/apt-status/name
write_status() {
    local dest="$1" tmp
    tmp=$(mktemp "${STATUS_DIR}/.tmp.XXXXXX") || return 1
    if ! cat > "$tmp"; then
        rm -f -- "$tmp"
        return 1
    fi
    chown root:"$FILE_GROUP" "$tmp"
    chmod 0644 "$tmp"
    mv -f -- "$tmp" "$dest"
}

# --- APT SECTION ---

if ! apt-get update -qq > /dev/null 2>&1; then
    echo "daily_apt_check: apt-get update failed, counts may be stale" >&2
fi

UPGRADABLE=$(apt list --upgradable 2>/dev/null | grep -c 'upgradable from')
echo "$UPGRADABLE" | write_status "${STATUS_DIR}/apt_upgradable_count"

# --- FLATPAK SECTION ---

# Refresh appstream metadata. '|| true' keeps the script going if the network
# is down or flatpak is not installed.
flatpak update --appstream -y > /dev/null 2>&1 || true

# Readable name plus application ID
FLATPAK_LIST=$(flatpak remote-ls --updates --columns=name,application 2>/dev/null)

if [ -z "$FLATPAK_LIST" ]; then
    FLATPAK_COUNT=0
else
    FLATPAK_COUNT=$(echo "$FLATPAK_LIST" | wc -l)
fi

{
    echo "$FLATPAK_COUNT"
    if [ "$FLATPAK_COUNT" -gt 0 ]; then
        echo "$FLATPAK_LIST"
    fi
} | write_status "${STATUS_DIR}/flatpak_updates"
