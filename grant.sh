#!/bin/sh
# Grant all possible permissions and service toggles to the target app via adb.
set -u

PKG=dev.aster.probe
A11Y=$PKG/$PKG.AsterA11yService
IME=$PKG/.AsterIme
LISTENER=$PKG/$PKG.AsterNotifications

failed=""

try() {
  out="$(adb shell "$@" 2>&1)"
  case "$out" in
    *Exception*|*Error*|*error*|*"Unknown permission"*|*"not found"*)
      failed="$failed\n  $* -> $(printf '%s' "$out" | head -1)"
      ;;
  esac
}

# Sorted runtime permissions (android.permission.*)
PERMISSIONS="
ACCEPT_HANDOVER
ACCESS_BACKGROUND_LOCATION
ACCESS_COARSE_LOCATION
ACCESS_FINE_LOCATION
ACCESS_MEDIA_LOCATION
ACTIVITY_RECOGNITION
ANSWER_PHONE_CALLS
BATTERY_STATS
BLUETOOTH_ADVERTISE
BLUETOOTH_CONNECT
BLUETOOTH_SCAN
BODY_SENSORS
BODY_SENSORS_BACKGROUND
CALL_PHONE
CAMERA
CHANGE_CONFIGURATION
DUMP
GET_ACCOUNTS
POST_NOTIFICATIONS
READ_CALENDAR
READ_CALL_LOG
READ_CONTACTS
READ_HEART_RATE
READ_HEALTH_DATA_IN_BACKGROUND
READ_LOGS
READ_MEDIA_AUDIO
READ_MEDIA_IMAGES
READ_MEDIA_VIDEO
READ_MEDIA_VISUAL_USER_SELECTED
READ_PHONE_NUMBERS
READ_PHONE_STATE
READ_SMS
RECEIVE_MMS
RECEIVE_SMS
RECEIVE_WAP_PUSH
RECORD_AUDIO
SEND_SMS
SET_ANIMATION_SCALE
UWB_RANGING
USE_FULL_SCREEN_INTENT
USE_SIP
WRITE_CALENDAR
WRITE_CALL_LOG
WRITE_CONTACTS
WRITE_SECURE_SETTINGS
"

# Removed READ_HEART_RATE and READ_HEALTH_DATA_IN_BACKGROUND from here and place in Health Connect section below.

# Grant regular permissions
for p in $PERMISSIONS; do
  case $p in
    READ_HEART_RATE|READ_HEALTH_DATA_IN_BACKGROUND)
      # Skip, handled in Health Connect section
      continue
      ;;
    *)
      try pm grant "$PKG" "android.permission.$p"
      ;;
  esac
done

# Special permission for voicemail (not android.permission.*)
try pm grant $PKG com.android.voicemail.permission.ADD_VOICEMAIL

# Android 16+: Health Connect permissions
for p in READ_HEART_RATE READ_HEALTH_DATA_IN_BACKGROUND; do
  try pm grant $PKG android.permission.health.$p
done

# Sorted app operations (appops)
APPOPS="
ACCESS_RESTRICTED_SETTINGS
GET_USAGE_STATS
MANAGE_EXTERNAL_STORAGE
MANAGE_MEDIA
PICTURE_IN_PICTURE
REQUEST_INSTALL_PACKAGES
RUN_ANY_IN_BACKGROUND
RUN_IN_BACKGROUND
SCHEDULE_EXACT_ALARM
START_FOREGROUND
SYSTEM_ALERT_WINDOW
USE_FULL_SCREEN_INTENT
WRITE_SETTINGS
"

for op in $APPOPS; do
  try appops set "$PKG" "$op" allow
done

# Service toggles and component enabling
try cmd notification allow_listener "$LISTENER"
try cmd notification allow_dnd "$PKG"
try dumpsys deviceidle whitelist +$PKG
try ime enable "$IME"
try ime set "$IME"

# Accessibility service enabling
current="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
case "$current" in
  *"$A11Y"*) ;;
  null|"") try settings put secure enabled_accessibility_services "$A11Y" ;;
  *) try settings put secure enabled_accessibility_services "$current:$A11Y" ;;
esac
try settings put secure accessibility_enabled 1

# Report failures and denied runtime permissions
if [ -n "$failed" ]; then
  printf 'refused:%b\n' "$failed"
fi

adb shell dumpsys package "$PKG" | grep -E "granted=false" | sed 's/^ *//' | sort -u | sed 's/^/still denied: /'
echo "done"
