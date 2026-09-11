# Security exceptions

Security exceptions are temporary release decisions. Each exception must name the affected artifact, document why a supported fix is unavailable, define compensating controls, and carry an expiry date. An expired exception blocks release until it is renewed with current evidence or remediated.

## Active exceptions

None.

## Resolved exceptions

### VE-2026-001: glib 0.18.5 VariantStrIter unsoundness

- Advisory: GHSA-wrw7-89jp-8q8g / RUSTSEC-2024-0429
- Severity: Medium
- Affected artifact: Linux desktop client 0.2.58
- Fixed artifact: Linux desktop client 0.2.59
- Status: Remediated with a reviewed upstream backport
- Recorded: 2026-09-10
- Resolved: 2026-09-11

`glib` 0.18.5 is a transitive Linux-only dependency through Tauri 2.11.5, Wry, WebKitGTK, and GTK 3. The advisory affects `glib::VariantStrIter` and can cause a null-pointer crash in optimized builds.

The first patched `glib` release is 0.20.0. The resolved GTK 3 stack requires `glib ^0.18`, so Cargo cannot select that release without the still-pending upstream Tauri/Wry GTK4 migration.

The exact crates.io `glib 0.18.5` source (archive SHA-256 `233daaf6e83ae6a12a52055f568f9d7cf4671dabb78ff9560ab6da230ce00ee5`) is vendored at `third_party/rust/glib-0.18.5`. It carries the two-line fix from gtk-rs/gtk-rs-core PR #1343: the FFI out-pointer is mutable and is passed as `&mut p`. Cargo is pinned to the vendored path.

CI verifies the patched source digest, runs the affected `VariantStrIter` tests in an optimized build, checks the locked desktop dependency graph, and builds the full desktop application. This closes the application-level vulnerability while Tauri remains on GTK3.

The vendored backport should be removed once a stable Tauri/Wry release migrates to `glib >=0.20.0`.
