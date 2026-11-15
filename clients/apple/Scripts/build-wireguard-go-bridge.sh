#!/bin/sh
# Builds libwg-go.a (wireguard-go bridge) for WireGuardKit.
# Called by the WireGuardGoBridge-* External Build System targets with Xcode
# build settings in the environment. Needs Go on PATH (or VERITAS_GO_BIN).
set -eu

ACTION="${1:-}"
[ -n "$ACTION" ] || ACTION=build

if [ -n "${VERITAS_GO_BIN:-}" ]; then
    PATH="${VERITAS_GO_BIN}:${PATH}"
fi
PATH="${PATH}:/usr/local/go/bin:/opt/homebrew/bin:/usr/local/bin"
export PATH

# SPM checkout location. CI can pass -clonedSourcePackagesDirPath and set
# VERITAS_SPM_CHECKOUTS to the same directory + /checkouts.
if [ -n "${VERITAS_SPM_CHECKOUTS:-}" ]; then
    CHECKOUTS="${VERITAS_SPM_CHECKOUTS}"
else
    CHECKOUTS="${BUILD_DIR%Build/*}SourcePackages/checkouts"
fi
GO_DIR="${CHECKOUTS}/wireguard-apple/Sources/WireGuardKitGo"
if [ ! -d "$GO_DIR" ]; then
    echo "error: WireGuardKitGo sources not found at ${GO_DIR}. Resolve Swift packages first." >&2
    exit 1
fi

if [ "$ACTION" = "clean" ]; then
    exec make -C "$GO_DIR" clean
fi

if ! command -v go >/dev/null 2>&1; then
    echo "error: Go is required to build the WireGuard bridge. Install Go 1.22 (brew install go@1.22) or set VERITAS_GO_BIN." >&2
    exit 1
fi

# Prepare the patched GOROOT ourselves so a runtime patch that no longer
# applies to a newer Go is a warning, not a failed build. The Makefile skips
# this step when .prepared exists.
BUILDDIR="${CONFIGURATION_TEMP_DIR}/wireguard-go-bridge"
PREPARED="${BUILDDIR}/goroot/.prepared"
if [ ! -f "$PREPARED" ]; then
    REAL_GOROOT="$(go env GOROOT)"
    mkdir -p "${BUILDDIR}/goroot"
    rsync -a --delete --exclude=pkg/obj/go-build "${REAL_GOROOT}/" "${BUILDDIR}/goroot/"
    if ! cat "$GO_DIR"/goruntime-*.diff | patch -p1 -f -N -r- -d "${BUILDDIR}/goroot"; then
        echo "warning: the WireGuardKit Go runtime patch did not apply to $(go version). Building without it." >&2
    fi
    touch "$PREPARED"
fi

EXTRA=""
case "${PLATFORM_NAME:-}" in
    iphonesimulator) EXTRA="GOOS_iphonesimulator=ios" ;;
esac

# shellcheck disable=SC2086
exec make -C "$GO_DIR" $EXTRA build
