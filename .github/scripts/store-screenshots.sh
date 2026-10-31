#!/usr/bin/env bash
# Takes the App Store screenshot set on two simulators. Called by .github/workflows/ios-store-screenshots.yml, which sets:
#   SITE, ALBUM_KEY, ALBUM_TITLE, IMAGE_KEY, SCENES (space separated), IPHONE_NAME, IPAD_NAME, APP_PATH, BUNDLE_ID, OUT_DIR
# Output: $OUT_DIR/{iphone,ipad}/NN-scene.jpg, $OUT_DIR/sizes.tsv (file, width, height, alpha, ok), $OUT_DIR/README.md
# DRAFT, third-party content, not for the store: the default site belongs to SmugMug and its photographers.
# Written without a Mac: the macOS runner is its first run. Unverified pieces are marked UNVERIFIED.
set -u

: "${SITE:?}" "${ALBUM_KEY:?}" "${ALBUM_TITLE:?}" "${IMAGE_KEY:?}" "${SCENES:?}" "${IPHONE_NAME:?}" "${IPAD_NAME:?}" "${APP_PATH:?}"
BUNDLE_ID="${BUNDLE_ID:-com.smugview.app}"
OUT_DIR="${OUT_DIR:-store-screenshots}"
LAUNCH_WAIT="${LAUNCH_WAIT:-30}"
mkdir -p "$OUT_DIR"
: > "$OUT_DIR/sizes.tsv"
failed=0

# Apple's accepted sizes (portrait). iPhone: Dynamic Island (medium) 1206x2622 / 1179x2556, optional 1320x2868. iPad 13-inch: 2064x2752 / 2048x2732.
size_ok() { # $1 = kind, $2 = w, $3 = h
  case "$1:$2x$3" in
    iphone:1206x2622|iphone:1179x2556|iphone:1320x2868|ipad:2064x2752|ipad:2048x2732) return 0 ;;
    *) return 1 ;;
  esac
}

# Scene name -> the extra environment the app gets (the app maps these in IosApp.kt; all are inert in a normal launch).
scene_env() { # prints "KEY=VALUE" lines
  case "$1" in
    home) ;;
    album-grid) echo "SMUGVIEW_DEMO_ROUTE=photo_grid/${ALBUM_KEY}/${ALBUM_TITLE}" ;;
    photo) echo "SMUGVIEW_DEMO_ROUTE=photo_detail/${ALBUM_KEY}/${IMAGE_KEY}" ;;
    info) echo "SMUGVIEW_DEMO_ROUTE=photo_detail/${ALBUM_KEY}/${IMAGE_KEY}"; echo "SMUGVIEW_DEMO_SHEET=info" ;;
    share) echo "SMUGVIEW_DEMO_ROUTE=photo_detail/${ALBUM_KEY}/${IMAGE_KEY}"; echo "SMUGVIEW_DEMO_SHEET=share" ;;
    hub) echo "SMUGVIEW_DEMO_TAB=Hub" ;;
    search) echo "SMUGVIEW_DEMO_TAB=Search" ;;
    collections) echo "SMUGVIEW_DEMO_TAB=Collections" ;;
    *) return 1 ;;
  esac
}

find_udid() { # exact device name among the available simulators
  xcrun simctl list devices available | grep -F "    $1 (" | head -1 | sed -E 's/.*\(([0-9A-F-]{36})\).*/\1/'
}

run_device() { # $1 = kind (iphone|ipad), $2 = device name
  local kind="$1" name="$2" udid n=0 scene
  udid=$(find_udid "$name")
  if [ -z "$udid" ]; then
    echo "::error title=simulator missing::no available simulator is named '$name'. Available:"
    xcrun simctl list devices available | grep -E "iPhone|iPad" | sed 's/^/    /'
    failed=1; return
  fi
  echo "== $kind: $name = $udid"
  mkdir -p "$OUT_DIR/$kind" "$OUT_DIR/raw/$kind"
  xcrun simctl boot "$udid" || true
  xcrun simctl bootstatus "$udid" -b >/dev/null 2>&1 || true
  # Clean status bar: 9:41, full battery and bars. UNVERIFIED on this Xcode: flag names come from `xcrun simctl status_bar` help for Xcode 15+.
  xcrun simctl status_bar "$udid" override --time 9:41 --batteryState charged --batteryLevel 100 --cellularMode active --cellularBars 4 --wifiBars 3 \
    || echo "::warning title=status_bar::simctl status_bar override failed on $name; the shots show the real status bar"
  xcrun simctl install "$udid" "$APP_PATH" || { echo "::error title=install failed::$name"; failed=1; return; }
  # First launch: opens the site as if tapped; the app saves it once the profile loads. Then every scene is a returning-user launch.
  SIMCTL_CHILD_SMUGVIEW_DEMO_SITE="$SITE" xcrun simctl launch --terminate-running-process "$udid" "$BUNDLE_ID" || true
  sleep 25
  xcrun simctl terminate "$udid" "$BUNDLE_ID" || true
  sleep 2

  for scene in $SCENES; do
    n=$((n + 1))
    local nn base envs=() line
    nn=$(printf '%02d' "$n"); base="$nn-$scene"
    while IFS= read -r line; do [ -n "$line" ] && envs+=("$line"); done < <(scene_env "$scene")
    if ! scene_env "$scene" >/dev/null; then echo "::error title=unknown scene::'$scene'"; failed=1; continue; fi
    xcrun simctl terminate "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true
    sleep 1
    local pairs=()
    for line in "${envs[@]+"${envs[@]}"}"; do pairs+=("SIMCTL_CHILD_$line"); done
    echo "-- $kind/$base env: ${envs[*]+"${envs[*]}"}"
    env ${pairs[@]+"${pairs[@]}"} xcrun simctl launch --stdout="$OUT_DIR/raw/$kind/$base.out.txt" --stderr="$OUT_DIR/raw/$kind/$base.err.txt" "$udid" "$BUNDLE_ID" || { echo "::error title=launch failed::$kind/$base"; failed=1; continue; }
    sleep "$LAUNCH_WAIT"
    # Run 41167973961: the info shots showed the home screen, so the app was gone. Record whether it is still running and print any crash report.
    if xcrun simctl spawn "$udid" launchctl list 2>/dev/null | grep -q "$BUNDLE_ID"; then echo "   app still running: $kind/$base"; else echo "::warning title=app not running::$kind/$base: the app is gone at screenshot time"; fi
    for ips in $(ls -t "$HOME"/Library/Logs/DiagnosticReports/*.ips 2>/dev/null | head -2); do
      echo "   crash report: $ips"; cp "$ips" "$OUT_DIR/raw/$kind/$base.$(basename "$ips")" || true
      grep -m6 -E '"exception"|"termination"|"asi"|"type"|"subtype"' "$ips" | cut -c1-300 || true
    done
    tail -n 15 "$OUT_DIR/raw/$kind/$base.out.txt" "$OUT_DIR/raw/$kind/$base.err.txt" 2>/dev/null | cut -c1-300 || true
    xcrun simctl io "$udid" screenshot "$OUT_DIR/raw/$kind/$base.png" || { echo "::error title=screenshot failed::$kind/$base"; failed=1; continue; }
    # PNG from simctl may carry an alpha channel; the store wants none: flat JPEG.
    sips -s format jpeg -s formatOptions 95 "$OUT_DIR/raw/$kind/$base.png" --out "$OUT_DIR/$kind/$base.jpg" >/dev/null
    local w h a ok
    w=$(sips -g pixelWidth "$OUT_DIR/$kind/$base.jpg" | awk '/pixelWidth/{print $2}')
    h=$(sips -g pixelHeight "$OUT_DIR/$kind/$base.jpg" | awk '/pixelHeight/{print $2}')
    a=$(sips -g hasAlpha "$OUT_DIR/$kind/$base.jpg" | awk '/hasAlpha/{print $2}')
    ok=yes
    size_ok "$kind" "$w" "$h" || ok=NO-SIZE
    [ "$a" = "no" ] || ok=NO-ALPHA
    [ "$ok" = yes ] || failed=1
    printf '%s/%s.jpg\t%s\t%s\t%s\t%s\n' "$kind" "$base" "$w" "$h" "$a" "$ok" >> "$OUT_DIR/sizes.tsv"
  done
  xcrun simctl status_bar "$udid" clear || true
  xcrun simctl shutdown "$udid" || true
}

run_device iphone "$IPHONE_NAME"
run_device ipad "$IPAD_NAME"

echo
echo "file | width x height | alpha | ok"
awk -F'\t' '{printf "%s | %sx%s | alpha=%s | %s\n", $1, $2, $3, $4, $5}' "$OUT_DIR/sizes.tsv"

{
  echo "# App Store screenshots: DRAFT, third-party content, not for the store"
  echo
  echo "Draft, third-party content, not for the store. These are layout checks only. The photos in the default run belong to SmugMug and"
  echo "its photographers (site \`$SITE\`). Point the workflow at your own public gallery before using any of this in App Store Connect."
  echo
  echo "Run: ${GITHUB_RUN_ID:-local}, commit ${GITHUB_SHA:-unknown}. Devices: $IPHONE_NAME, $IPAD_NAME. Scenes: $SCENES."
  echo "Rules checked: JPEG, no alpha channel, iPhone 1206x2622 / 1179x2556 / 1320x2868, iPad 2064x2752 / 2048x2732 (portrait)."
  echo
  echo "| file | pixels | alpha | accepted size |"
  echo "|---|---|---|---|"
  awk -F'\t' '{printf "| %s | %sx%s | %s | %s |\n", $1, $2, $3, $4, $5}' "$OUT_DIR/sizes.tsv"
  echo
  echo "## iPad layout check: Home view"
  echo
  echo "![iPad home view](ipad/01-home.jpg)"
  echo
  echo "(Look for: stretched phone layout, grids with too few columns, text lines far wider than a phone, content under the status bar or home"
  echo "indicator, bottom bar versus a sidebar, sheets and dialogs that stay phone-narrow in the middle of a 13-inch screen.)"
} > "$OUT_DIR/README.md"

exit "$failed"
