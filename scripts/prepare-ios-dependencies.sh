
#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"

# --------------------------------------------------
# NUVIO ENGINE CONFIGURATION
# --------------------------------------------------

engine_version="0.1.6"

engine_checksum="f556718ec0fd122a61bc5408307b19fcd54397595954c46c6ff8db1e5a179421"

engine_root="${NUVIO_ENGINE_ROOT:-${repository_root}/../nuvio-engine}"

engine_framework="${engine_root}/platform/apple/NuvioEngine.xcframework"

# --------------------------------------------------
# MPVKIT CONFIGURATION
# --------------------------------------------------

mpvkit_revision="742bf0177a93b353c68de57daa05bc5acd5d61ce"

mpvkit_release="mpvkit-742bf01-ios"

mpvkit_archive="mpvkit-ios-742bf01.zip"

mpvkit_release_url="https://github.com/hamzza20/TuvoraMobile/releases/download/${mpvkit_release}"

mpvkit_root="${repository_root}/MPVKit"

mpvkit_framework_root="${mpvkit_root}/dist/release/xcframework"

mpvkit_frameworks=(
    Libplacebo
    Libavcodec
    Libavdevice
    Libavformat
    Libavfilter
    Libavutil
    Libswresample
    Libswscale
    Libmpv
)

# --------------------------------------------------
# TEMPORARY DIRECTORY
# --------------------------------------------------

temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/tuvora-ios-deps.XXXXXX")"

trap 'rm -rf "${temporary_directory}"' EXIT

# --------------------------------------------------
# INITIALIZE MPVKIT SUBMODULE
# --------------------------------------------------

echo "Checking MPVKit submodule..."

git -C "${repository_root}" submodule update --init --depth 1 MPVKit

actual_revision="$(git -C "${mpvkit_root}" rev-parse HEAD)"

if [[ "${actual_revision}" != "${mpvkit_revision}" ]]; then
    echo "Unexpected MPVKit revision." >&2
    echo "Expected: ${mpvkit_revision}" >&2
    echo "Actual:   ${actual_revision}" >&2
    exit 1
fi

# --------------------------------------------------
# VALIDATE XCFRAMEWORK
# --------------------------------------------------

validate_framework() {
    local framework="$1"

    if [[ ! -f "${framework}/Info.plist" ]]; then
        return 1
    fi

    plutil -lint "${framework}/Info.plist" >/dev/null || return 1

    python3 - "${framework}" <<'PY'
import plistlib
import subprocess
import sys
from pathlib import Path

root = Path(sys.argv[1])

try:
    with open(root / "Info.plist", "rb") as f:
        info = plistlib.load(f)
except Exception as e:
    print(f"Invalid Info.plist: {e}", file=sys.stderr)
    sys.exit(1)

libraries = info.get("AvailableLibraries", [])

for entry in libraries:
    if entry.get("SupportedPlatform") != "ios":
        continue

    if entry.get("SupportedPlatformVariant") not in (None, ""):
        continue

    identifier = entry.get("LibraryIdentifier")
    library_path = entry.get("LibraryPath")

    if not identifier or not library_path:
        continue

    binary = root / identifier / library_path

    if binary.is_dir():
        binary = binary / binary.stem

    if not binary.is_file() or binary.stat().st_size == 0:
        continue

    result = subprocess.run(
        ["lipo", "-info", str(binary)],
        capture_output=True,
        text=True
    )

    if result.returncode == 0 and "arm64" in result.stdout:
        print(f"Valid iOS arm64 binary: {binary.name}")
        sys.exit(0)

print(f"No valid iOS arm64 binary found: {root.name}", file=sys.stderr)
sys.exit(1)
PY
}

validate_mpvkit_set() {
    local root="$1"

    for name in "${mpvkit_frameworks[@]}"; do
        local framework="${root}/${name}.xcframework"

        if ! validate_framework "${framework}"; then
            echo "Invalid or missing framework: ${name}" >&2
            return 1
        fi
    done
}

# --------------------------------------------------
# PREPARE NUVIO ENGINE
# --------------------------------------------------

prepare_nuvio_engine() {

    if [[ -f "${engine_framework}/Info.plist" ]] &&
       [[ -s "${engine_framework}/ios-arm64/libCNuvioEngine.a" ]]; then
        echo "NuvioEngine already available."
        return
    fi

    echo "Downloading NuvioEngine ${engine_version}..."

    local archive="${temporary_directory}/nuvio-engine.zip"

    curl --fail --location --retry 5 --retry-all-errors \
        --silent --show-error \
        --output "${archive}" \
        "https://github.com/NuvioMedia/nuvio-engine/releases/download/v${engine_version}/nuvio-engine-apple-${engine_version}.zip"

    local actual_checksum

    actual_checksum="$(shasum -a 256 "${archive}" | awk '{print $1}')"

    if [[ "${actual_checksum}" != "${engine_checksum}" ]]; then
        echo "NuvioEngine checksum mismatch." >&2
        exit 1
    fi

    local extraction_root="${temporary_directory}/engine"

    mkdir -p "${extraction_root}"

    unzip -q "${archive}" -d "${extraction_root}"

    local source_framework

    source_framework="$(find "${extraction_root}" \
        -type d -name NuvioEngine.xcframework -print -quit)"

    if [[ -z "${source_framework}" ]] ||
       [[ ! -f "${source_framework}/Info.plist" ]] ||
       [[ ! -s "${source_framework}/ios-arm64/libCNuvioEngine.a" ]]; then
        echo "Invalid NuvioEngine archive." >&2
        exit 1
    fi

    mkdir -p "$(dirname "${engine_framework}")"

    rm -rf "${engine_framework}"

    ditto "${source_framework}" "${engine_framework}"

    echo "NuvioEngine prepared."
}

# --------------------------------------------------
# PREPARE MPVKIT FRAMEWORKS
# --------------------------------------------------

prepare_mpvkit() {

    if validate_mpvkit_set "${mpvkit_framework_root}"; then
        echo "All MPVKit frameworks already available."
        return
    fi

    echo "Downloading MPVKit binary release..."

    local archive="${temporary_directory}/${mpvkit_archive}"

    local checksum_file="${temporary_directory}/${mpvkit_archive}.sha256"

    curl --fail --location --retry 5 --retry-all-errors \
        --silent --show-error \
        --output "${archive}" \
        "${mpvkit_release_url}/${mpvkit_archive}"

    curl --fail --location --retry 5 --retry-all-errors \
        --silent --show-error \
        --output "${checksum_file}" \
        "${mpvkit_release_url}/${mpvkit_archive}.sha256"

    local expected_checksum
    local actual_checksum

    expected_checksum="$(awk 'NF {print $1; exit}' "${checksum_file}")"

    if [[ ! "${expected_checksum}" =~ ^[[:xdigit:]]{64}$ ]]; then
        echo "Invalid MPVKit checksum file." >&2
        exit 1
    fi

    actual_checksum="$(shasum -a 256 "${archive}" | awk '{print $1}')"

    if [[ "${actual_checksum}" != "${expected_checksum}" ]]; then
        echo "MPVKit archive checksum mismatch." >&2
        exit 1
    fi

    echo "MPVKit archive checksum verified."

    local extraction_root="${temporary_directory}/mpvkit"

    mkdir -p "${extraction_root}"

    ditto -x -k "${archive}" "${extraction_root}"

    local source_root

    source_root="$(find "${extraction_root}" \
        -type d -name xcframework -print -quit)"

    if [[ -z "${source_root}" ]]; then
        echo "MPVKit archive does not contain xcframework directory." >&2
        exit 1
    fi

    # Validate every framework before installing anything.

    validate_mpvkit_set "${source_root}" || {
        echo "MPVKit archive validation failed." >&2
        exit 1
    }

    # Install atomically through a staging directory.

    local staging="${mpvkit_framework_root}.staging"

    rm -rf "${staging}"

    mkdir -p "${staging}"

    for name in "${mpvkit_frameworks[@]}"; do
        ditto \
            "${source_root}/${name}.xcframework" \
            "${staging}/${name}.xcframework"
    done

    rm -rf "${mpvkit_framework_root}"

    mkdir -p "$(dirname "${mpvkit_framework_root}")"

    mv "${staging}" "${mpvkit_framework_root}"

    echo "All nine MPVKit frameworks installed."
}

# --------------------------------------------------
# EXECUTE PREPARATION
# --------------------------------------------------

prepare_nuvio_engine

prepare_mpvkit

echo ""
echo "======================================"
echo " iOS dependencies prepared successfully"
echo " MPVKit: ${mpvkit_revision}"
echo " Frameworks: 9/9"
echo "======================================"
