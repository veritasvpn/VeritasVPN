# WireGuardNT spike (Phase 0)

Throwaway probe. It is not linked into the desktop app. Delete `clients/desktop/spike/wireguard-nt` and `.github/workflows/windows-spike.yml` when the Windows tunnel engine replaces it.

## What it checks

The program loads the official prebuilt `wireguard.dll` with `LoadLibraryW` and calls only the public exports (`WireGuardCreateAdapter`, `WireGuardSetConfiguration`, `WireGuardSetAdapterState`, `WireGuardGetAdapterState`, `WireGuardGetConfiguration`, `WireGuardCloseAdapter`). The file is not modified.

- Adapter name: `VeritasVPN` (not the official app's tunnel name).
- Tunnel type: `WireGuard`, because that is the type the signed driver registers. The adapter name is what keeps it from clashing with the official app.
- Config uses ephemeral key bytes generated in the process and the documentation address 192.0.2.1 (TEST-NET-1). No route is added, so the probe does not move the machine's traffic.
- After the adapter is up, `Get-NetAdapter -Name VeritasVPN` must list it. After close, it must be gone.

## Pin

| Item | Value |
| --- | --- |
| Package | wireguard-nt-1.1.zip from https://download.wireguard.com/wireguard-nt/ |
| Zip SHA-256 | `dceb30a9bc4be48cce0f74160fc88a585a2c2627366e8f846fc6658f9038dace` |
| amd64 `wireguard.dll` SHA-256 | `b1b85e072c45d81358be29d94c599dc76652f912be8c0f0a41e2d5d89a6461d3` |
| DLL timestamp inside the zip | 2026-05-07 |
| Header | `wireguard.h` in that zip (public API, April 2026) |

As of 4 Nov 2026 the download page lists only 1.1. Upstream's README says WireGuardNT targets the NT kernel used by Windows 10 and 11 (AMD64, x86, ARM64).

## Where it ran

GitHub-hosted runners only. There is no Windows 10 22H2 (build 19045) image. `windows-2022` is Windows Server 2022, build 20348, which is the Windows 10 generation and newer than 22H2. `windows-latest` is the current Server image (Windows 11 generation). Both are Server SKUs (`product_type` 3), not client Windows. A pass here does not by itself prove Windows 10 22H2 Home or Pro.

The job log lines prefixed `SPIKE_` are the raw record. The table below is filled from that log.

## Results

Filled after the first CI run on this branch. Until then the cells stay blank on purpose, rather than guessing a build number.

| Runner | Windows build | Driver version | Adapter created, up, and removed |
| --- | --- | --- | --- |
| windows-latest | | | |
| windows-2022 | | | |

## Surprises

Recorded with the results above. Known before the run:

- Creating an adapter needs an elevated process. The GitHub runner account is an administrator, so the probe does not show what a standard user would see. The real app will not create adapters itself; the SYSTEM service will.
- Struct layout is `repr(C, align(8))` matched to `wireguard.h`. `SPIKE_LAYOUT` prints the sizes. A wrong size fails `WireGuardSetConfiguration` or the one-peer read-back.
- No IP address and no route are assigned. "Up" means the NDIS adapter exists and the driver accepted the config, not that a handshake completed.
