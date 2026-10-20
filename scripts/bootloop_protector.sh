#!/system/bin/sh

MARKER_DIR="/data/adb/YABP"
LOGFILE="/data/local/tmp/bootloop.log"
BOOT_TIMEOUT=180

mkdir -p "$MARKER_DIR"

log_event() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') - $1" >> "$LOGFILE"
}

is_boot_completed() {
    [ "$(getprop sys.boot_completed)" = "1" ]
}

disable_modules() {
    log_event "Bootloop detected! Disabling all Magisk modules..."
    for module in /data/adb/modules/*/; do
        [ -d "$module" ] || continue
        module_name=$(basename "$module")
        touch "$module/disable"
        log_event "Disabled module: $module_name"
    done

    for dir in /data/adb/service.d /data/adb/post-fs-data.d /data/adb/post-mount.d /data/adb/boot-completed.d; do
        [ -d "$dir" ] || continue
        for script in "$dir"/*; do
            [ -f "$script" ] || continue
            script_name=$(basename "$script")
            [ "$script_name" = "bootloop_protector.sh" ] && continue
            chmod 644 "$script"
            log_event "Disabled script: $script_name"
        done
    done
}

check_markers() {
    MARKER1="$MARKER_DIR/marker1"
    MARKER2="$MARKER_DIR/marker2"
    MARKER3="$MARKER_DIR/marker3"

    if [ -f "$MARKER3" ]; then
        log_event "Bootloop detected (3 failed boots). Disabling modules and rebooting..."
        disable_modules
        rm -f "$MARKER1" "$MARKER2" "$MARKER3"
        log_event "Rebooting device..."
        reboot
    elif [ -f "$MARKER2" ]; then
        log_event "Second failed boot detected, creating marker3"
        touch "$MARKER3"
    elif [ -f "$MARKER1" ]; then
        log_event "First failed boot detected, creating marker2"
        touch "$MARKER2"
    else
        log_event "Boot started, creating marker1"
        touch "$MARKER1"
    fi
}

log_event "Bootloop Protector service started"
check_markers

SECONDS_PASSED=0
while [ $SECONDS_PASSED -lt $BOOT_TIMEOUT ]; do
    if is_boot_completed; then
        log_event "Boot completed successfully. Cleaning up markers."
        rm -f "$MARKER_DIR/marker1" "$MARKER_DIR/marker2" "$MARKER_DIR/marker3"
        exit 0
    fi
    sleep 5
    SECONDS_PASSED=$((SECONDS_PASSED + 5))
done

log_event "Boot did not complete within $BOOT_TIMEOUT seconds. Disabling modules..."
disable_modules
rm -f "$MARKER_DIR/marker1" "$MARKER_DIR/marker2" "$MARKER_DIR/marker3"
log_event "Rebooting device..."
reboot
