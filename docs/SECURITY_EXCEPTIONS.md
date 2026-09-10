# Security exceptions

Security exceptions are temporary release decisions. Each exception must name the affected artifact, document why a supported fix is unavailable, define compensating controls, and carry an expiry date. An expired exception blocks release until it is renewed with current evidence or remediated.

## VE-2026-001: glib 0.18.5 VariantStrIter unsoundness

- Advisory: GHSA-wrw7-89jp-8q8g / RUSTSEC-2024-0429
- Severity: Medium
- Affected artifact: Linux desktop client 0.2.58
- Status: Temporarily accepted
- Recorded: 2026-09-10
- Expires: 2026-12-10

`glib` 0.18.5 is a transitive Linux-only dependency through Tauri 2.11.5, Wry, WebKitGTK, and GTK 3. The advisory affects `glib::VariantStrIter` and can cause a null-pointer crash in optimized builds. VeritasVPN does not directly depend on `glib` or call `VariantStrIter`, and the advisory does not describe data disclosure, privilege escalation, or code execution.

The first patched `glib` release is 0.20.0. The resolved GTK 3 stack requires `glib ^0.18`, so Cargo cannot select the patched release without an upstream Tauri/Wry migration. The official gtk-rs 0.18 branch does not contain a maintained backport.

Compensating controls:

- The desktop client validates untrusted VPN configuration before invoking platform networking code.
- CI locks the Rust dependency graph and scans it on every pull request.
- Linux packages remain versioned release artifacts so a patched build can replace 0.2.58 promptly.

Remediation is to adopt a Tauri/Wry Linux stack that resolves `glib >=0.20.0`, or a maintainer-supported backport. Review this exception when Tauri or Wry changes its GTK dependency and no later than the expiry date.
