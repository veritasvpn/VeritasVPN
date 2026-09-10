#!/bin/sh
set -e

# v0.2.58 removes the legacy dispatcher and broad passwordless recovery rule.
# The application installs narrowly scoped, root-owned helpers when connecting.
rm -f /etc/NetworkManager/dispatcher.d/50-veritasvpn
rm -f /etc/sudoers.d/veritasvpn-soft
# Refresh freedesktop caches so GNOME/KDE pick up the new app icon immediately.
if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database -q /usr/share/applications || true
fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
  gtk-update-icon-cache -f -t /usr/share/icons/hicolor >/dev/null 2>&1 || true
fi
if command -v xdg-icon-resource >/dev/null 2>&1; then
  # Best-effort pixmap registration for shells that skip hicolor.
  if [ -f /usr/share/icons/hicolor/256x256/apps/veritasvpn-desktop.png ]; then
    xdg-icon-resource install --novendor --size 256 \
      /usr/share/icons/hicolor/256x256/apps/veritasvpn-desktop.png veritasvpn-desktop >/dev/null 2>&1 || true
  fi
fi
exit 0
