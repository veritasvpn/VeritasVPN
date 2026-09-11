# VeritasVPN security backport

This directory is the unmodified crates.io `glib 0.18.5` archive except for the upstream security fix described below.

- Crate archive SHA-256 before extraction: `233daaf6e83ae6a12a52055f568f9d7cf4671dabb78ff9560ab6da230ce00ee5`
- Patched source-tree SHA-256: `95f5e214bc401522e246f5cd03b8bbabeee783ae72521e9b473b7c97bc17ca43`
- Advisory: `GHSA-wrw7-89jp-8q8g` / `RUSTSEC-2024-0429`
- Upstream pull request: https://github.com/gtk-rs/gtk-rs-core/pull/1343
- Upstream merge commit: `05dff0ee696f9bcd8617cd48c4b812d046d440cb`
- Patched file: `src/variant_iter.rs`

The patch makes the FFI out-pointer mutable and passes `&mut p` to `g_variant_get_child`. Remove this vendored crate and the Cargo patch once the stable Tauri/Wry dependency graph uses `glib >=0.20.0`.

`Cargo.lock` is a VeritasVPN addition that pins the backport regression-test dependency graph.
