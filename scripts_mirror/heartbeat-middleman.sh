#!/bin/bash

# HeartbeatMiddleman - Safely switches between controller and heartbeat services
#
# Usage:
#   heartbeat-middleman.sh 1   # Stop controller, start heartbeat
#   heartbeat-middleman.sh 2   # Stop heartbeat, start controller

CONTROLLER_SERVICE="heartbeat-controller"
HEARTBEAT_SERVICE="heartbeat"
MAX_STOP_WAIT_SEC=5
POLL_INTERVAL=0.2

LOG_FILE="/var/log/hb-mm.log"
ERROR_LOG="/var/log/hb-mm-error.log"

# Function to log messages
log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" | tee -a "$LOG_FILE"
}

# Function to log errors
log_error() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] ERROR: $*" | tee -a "$ERROR_LOG" >&2
}

# Function to check if service is active
is_service_active() {
    systemctl is-active "$1" 2>/dev/null | grep -q "^active$"
}

# Function to stop a service (tolerant of already-stopped)
stop_service() {
    local service=$1
    log "Stopping $service..."

    # If it's not active, just log and move on
    if ! is_service_active "$service"; then
        log "$service is already inactive (nothing to stop)."
        return 0
    fi

    if systemctl stop "$service" 2>>"$ERROR_LOG"; then
        log "Stop command issued for $service"
    else
        # Do NOT bail out here — it might already be stopping or failed
        log_error "systemctl stop returned non-zero for $service (may already be stopping/failed)."
    fi
}

# Function to start a service
start_service() {
    local service=$1
    log "Starting $service..."

    if systemctl start "$service" 2>>"$ERROR_LOG"; then
        log "Start command issued for $service"
    else
        log_error "Failed to start $service"
        # Don't hard-exit; we'll still verify status below
    fi
}

# Function to wait for service to stop
wait_for_service_stop() {
    local service=$1
    local elapsed=0

    log "Waiting for $service to fully stop..."

    while [ "$elapsed" -lt "$MAX_STOP_WAIT_SEC" ]; do
        if ! is_service_active "$service"; then
            log "✓ $service is stopped"
            return 0
        fi

        sleep "$POLL_INTERVAL"
        elapsed=$(echo "$elapsed + $POLL_INTERVAL" | bc)
    done

    log_error "$service did not stop within ${MAX_STOP_WAIT_SEC}s"
    return 1
}

# Function to switch services
switch_services() {
    local service_to_stop=$1
    local service_to_start=$2

    log "=========================================="
    log "Switching from $service_to_stop to $service_to_start"
    log "=========================================="

    # Step 1: Stop the first service
    log "Step 1: Stopping $service_to_stop"
    stop_service "$service_to_stop"

    # Step 2: Wait for it to stop
    log "Step 2: Verifying $service_to_stop has stopped"
    if ! wait_for_service_stop "$service_to_stop"; then
        log_error "Proceeding anyway, but $service_to_stop did not report as stopped."
    fi

    # Step 3: Small delay to ensure port is released
    log "Step 3: Waiting for port to be released..."
    local port_free=0
    for i in $(seq 1 20); do  # max 2 seconds (20 * 0.1s)
        if ! ss -lnu sport = :46317 | grep -q ":46317"; then
            port_free=1
            log "✓ Port 46317 is free"
            break
        fi
        sleep 0.1
    done

    if [ $port_free -eq 0 ]; then
        log_error "Port 46317 still in use after 2s — proceeding anyway (risk of bind failure)"
    fi

    # Step 4: Start the second service
    log "Step 4: Starting $service_to_start"
    start_service "$service_to_start"

    # Step 5: Verify it started
    log "Step 5: Verifying $service_to_start started"
    sleep 1

    if is_service_active "$service_to_start"; then
        log "✓ $service_to_start is running"
    else
        log_error "$service_to_start may not have started properly"
    fi

    log "=========================================="
    log "Service switch completed"
    log "=========================================="
}

# Main script
main() {
    # Ensure log files exist and are writable
    touch "$LOG_FILE" "$ERROR_LOG" 2>/dev/null || {
        echo "ERROR: Cannot create log files. Run with sudo?" >&2
        exit 5
    }

    # Check arguments
    if [ $# -ne 1 ] || { [ "$1" != "1" ] && [ "$1" != "2" ]; }; then
        log_error "Invalid arguments"
        echo "Usage: $0 <1|2>" >&2
        echo "  1 = Stop controller, start heartbeat" >&2
        echo "  2 = Stop heartbeat, start controller" >&2
        exit 1
    fi

    MODE=$1

    log "HeartbeatMiddleman starting (mode $MODE)"

    if [ "$MODE" = "1" ]; then
        log "Mode 1: Controller → Heartbeat"
        switch_services "$CONTROLLER_SERVICE" "$HEARTBEAT_SERVICE"
    else
        log "Mode 2: Heartbeat → Controller"
        switch_services "$HEARTBEAT_SERVICE" "$CONTROLLER_SERVICE"
    fi

    log "HeartbeatMiddleman completed"
    exit 0
}

main "$@"

