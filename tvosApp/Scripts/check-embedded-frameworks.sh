#!/bin/bash
# Fails the build when an embedded framework is packaged for the wrong platform, or its Info.plist
# MinimumOSVersion disagrees with its binary's minos. App Store Connect rejects either after upload
# (ITMS-90208) — build 1 shipped Libplacebo with a copied iOS Info.plist (iPhoneOS, 13.0) in its
# tvOS slice. Cheaper to stop here than to learn it from Apple's email.
set -euo pipefail
FW="$TARGET_BUILD_DIR/$FRAMEWORKS_FOLDER_PATH"
[ -d "$FW" ] || exit 0
case "$PLATFORM_NAME" in
  appletvos) want=AppleTVOS ;;
  appletvsimulator) want=AppleTVSimulator ;;
  *) exit 0 ;;
esac
bad=0
for f in "$FW"/*.framework; do
  plist="$f/Info.plist"; [ -f "$plist" ] || continue
  name=$(basename "$f" .framework)
  exe=$(/usr/libexec/PlistBuddy -c "Print :CFBundleExecutable" "$plist" 2>/dev/null || echo "$name")
  platforms=$(/usr/libexec/PlistBuddy -c "Print :CFBundleSupportedPlatforms" "$plist" 2>/dev/null | tr -d ' ')
  plist_min=$(/usr/libexec/PlistBuddy -c "Print :MinimumOSVersion" "$plist" 2>/dev/null || echo "")
  bin_min=$(otool -l "$f/$exe" 2>/dev/null | awk '/LC_BUILD_VERSION/{b=1} b&&/minos/{print $2; exit}')
  if ! grep -qx "$want" <<<"$platforms"; then
    echo "error: $name.framework Info.plist lists platforms [$(echo $platforms | tr '\n' ' ')], expected $want" >&2; bad=1
  fi
  if [ -n "$plist_min" ] && [ -n "$bin_min" ] && [ "$plist_min" != "$bin_min" ]; then
    echo "error: $name.framework MinimumOSVersion $plist_min != binary minos $bin_min (ITMS-90208)" >&2; bad=1
  fi
done
exit $bad
