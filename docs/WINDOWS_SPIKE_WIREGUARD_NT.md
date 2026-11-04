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

Recorded from GitHub Actions run 41935115731 (4 Nov 2026). Both jobs printed `SPIKE_OK`.

| Runner | Windows | Driver | Adapter |
| --- | --- | --- | --- |
| windows-latest | 10.0.26100.33674, build 26100, product type 3 (Server) | 65537 (`0x10001`, 1.1) | Created, state Up, then removed |
| windows-2022 | 10.0.20348.5773, build 20348, product type 3 (Server) | 65537 (`0x10001`, 1.1) | Created, state Up, then removed |

`Get-NetAdapter` showed `Name: VeritasVPN`, `Status: Up`, `InterfaceDescription: WireGuard Tunnel`. After `WireGuardCloseAdapter` the adapter was gone, and `WireGuardDeleteDriver` returned success. No reboot.

The struct sizes that the driver accepted were interface 80, peer 136, allowed-IP 24, config blob 240.

## What this does and does not prove

The lowest build that actually ran is **20348** (Windows Server 2022). That is the Windows 10 generation, and it is newer than Windows 10 22H2 (build 19045). GitHub has no 22H2 client image, so 22H2 Home or Pro is still best-effort and untested. Both runners are Server SKUs, not Windows 11 Home or Pro. Build 26100 is the Windows 11 generation (Server 2025 on `windows-latest`).

"Up" means the NDIS adapter exists and the driver accepted a config. No address and no route were added, and there was no handshake.

## Surprises

- Nothing failed. The first `WireGuardCreateAdapter` installed the driver and brought the adapter up in the same process, with no reboot.
- The adapter **name** can be `VeritasVPN`. The tunnel **type** passed to the API was `WireGuard`, which is the name the signed driver registers. Windows then describes the interface as "WireGuard Tunnel". A different tunnel type was not tried.
- The runner is already an administrator, so this does not show the consent prompt a normal user would see. The real app must not call this API itself. The SYSTEM service will.
- The driver version DWORD is `0x00010001`, which matches package 1.1.
