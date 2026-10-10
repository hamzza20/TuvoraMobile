#!/bin/sh
set -eu

if [ "${CONFIGURATION:-}" != "Release" ]; then
  exit 0
fi

fail_or_warn() {
  message="$1"
  if [ "${ACTION:-}" = "install" ] || [ -n "${CI:-}" ]; then
    echo "error: $message" >&2
    exit 1
  fi
  echo "warning: $message" >&2
  exit 0
}

if [ -z "${POSTHOG_CLI_API_KEY:-}" ]; then
  fail_or_warn "POSTHOG_CLI_API_KEY is missing; a Release archive cannot upload its dSYM. Add it to iosApp/Configuration/PostHog.local.xcconfig or the CI environment."
fi

dsym_path="${DWARF_DSYM_FOLDER_PATH:-}/${DWARF_DSYM_FILE_NAME:-}"
dwarf_path="$dsym_path/Contents/Resources/DWARF/${EXECUTABLE_NAME:-}"
if [ ! -f "$dwarf_path" ]; then
  fail_or_warn "Expected dSYM binary was not generated at $dwarf_path"
fi

upload_helper="${BUILD_DIR%/Build/*}/SourcePackages/checkouts/posthog-ios/build-tools/upload-symbols.sh"
if [ ! -x "$upload_helper" ]; then
  fail_or_warn "PostHog symbol uploader was not found at $upload_helper"
fi

debug_uuid="$(xcrun dwarfdump --uuid "$dsym_path" | awk 'NR == 1 { print $2 }')"
if [ -z "$debug_uuid" ]; then
  fail_or_warn "Could not read a UUID from $dsym_path"
fi

export POSTHOG_CLI_HOST="${POSTHOG_CLI_HOST:-https://us.posthog.com}"
export POSTHOG_CLI_PROJECT_ID="${POSTHOG_CLI_PROJECT_ID:-494529}"
# Same call as PostHog's upload-symbols.sh helper (found above, which also proves the package is present), plus
# --skip-on-conflict: a dSYM whose UUID was already uploaded (an unchanged framework, or a re-archive of the same
# binary from another path) differs only in recorded paths, and the helper's plain upload failed the whole
# archive on it ("Content mismatch", 2026-10-10). The existing set is kept, and still symbolicates: the UUID
# identifies the binary.
ph_cli="$HOME/.posthog/posthog-cli"
[ -x "$ph_cli" ] || ph_cli="$(command -v posthog-cli 2>/dev/null || true)"
[ -n "$ph_cli" ] || fail_or_warn "posthog-cli not found (npm install -g @posthog/cli@latest)"
"$ph_cli" dsym upload --directory "$DWARF_DSYM_FOLDER_PATH" --main-dsym "$DWARF_DSYM_FILE_NAME" \
  --release-name "$PRODUCT_BUNDLE_IDENTIFIER" --release-version "$MARKETING_VERSION" --build "$CURRENT_PROJECT_VERSION" \
  --skip-on-conflict || fail_or_warn "PostHog dSYM upload failed for UUID $debug_uuid"
echo "PostHog dSYM upload completed for UUID $debug_uuid"
