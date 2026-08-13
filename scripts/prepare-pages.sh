#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="$repo_root/dist-site"

mkdir -p "$output_dir"
find "$output_dir" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
rsync -a \
  --exclude 'downloads/veritasvpn-linux.AppImage' \
  --exclude 'downloads/veritasvpn-android.apk' \
  --exclude 'downloads/veritasvpn-macos.dmg' \
  --exclude 'README.md' \
  "$repo_root/website/" "$output_dir/"

echo "Prepared Cloudflare Pages output in $output_dir"
