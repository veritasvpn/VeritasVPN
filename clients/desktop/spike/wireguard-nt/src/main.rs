//! Throwaway probe for WireGuardNT (wireguard.dll).
//!
//! Loads the official prebuilt DLL, creates an adapter named VeritasVPN, sets a
//! config, brings it up, checks the interface is visible, then deletes it.
//! Nothing here is linked into the desktop app. Drop this directory when the
//! real Windows engine lands.

fn main() {
    #[cfg(not(windows))]
    {
        eprintln!("wireguard-nt spike runs on Windows only");
        std::process::exit(2);
    }
    #[cfg(windows)]
    {
        if let Err(err) = windows::run() {
            eprintln!("spike failed: {err}");
            std::process::exit(1);
        }
    }
}

#[cfg(windows)]
mod windows {
    use std::ffi::c_void;
    use std::mem::{size_of, zeroed};
    use std::os::windows::ffi::OsStrExt;
    use std::path::Path;
    use std::process::Command;
    use std::ptr;

    const AF_INET: u16 = 2;
    const ADAPTER_NAME: &str = "VeritasVPN";
    // The signed driver is registered as the "WireGuard" tunnel type. The
    // adapter name above is ours, so it does not take the official app's name.
    const TUNNEL_TYPE: &str = "WireGuard";
    const ERROR_MORE_DATA: u32 = 234;

    type AdapterHandle = *mut c_void;

    #[repr(C)]
    struct Guid {
        data1: u32,
        data2: u16,
        data3: u16,
        data4: [u8; 8],
    }

    #[repr(C)]
    struct NetLuid {
        value: u64,
    }

    /// SOCKADDR_INET is 28 bytes and 4-aligned (the sockaddr_in6 arm).
    #[repr(C, align(4))]
    struct SockaddrInet {
        family: u16,
        port: u16,
        addr: [u8; 4],
        _rest: [u8; 20],
    }

    #[repr(C, align(8))]
    struct WgInterface {
        flags: u32,
        listen_port: u16,
        private_key: [u8; 32],
        public_key: [u8; 32],
        _pad_peers: u16,
        peers_count: u32,
    }

    #[repr(C, align(8))]
    struct WgPeer {
        flags: u32,
        reserved: u32,
        public_key: [u8; 32],
        preshared_key: [u8; 32],
        persistent_keepalive: u16,
        _pad_endpoint: u16,
        endpoint: SockaddrInet,
        tx_bytes: u64,
        rx_bytes: u64,
        last_handshake: u64,
        allowed_ips_count: u32,
        _pad_end: u32,
    }

    #[repr(C, align(8))]
    struct WgAllowedIp {
        address: [u8; 16],
        address_family: u16,
        cidr: u8,
        _pad_flags: u8,
        flags: u32,
    }

    #[repr(C)]
    struct ConfigBlob {
        interface: WgInterface,
        peer: WgPeer,
        allowed: WgAllowedIp,
    }

    #[repr(C)]
    struct OsVersion {
        size: u32,
        major: u32,
        minor: u32,
        build: u32,
        platform: u32,
        csd: [u16; 128],
        sp_major: u16,
        sp_minor: u16,
        suite: u16,
        product_type: u8,
        reserved: u8,
    }

    type CreateAdapter = unsafe extern "system" fn(*const u16, *const u16, *const Guid) -> AdapterHandle;
    type CloseAdapter = unsafe extern "system" fn(AdapterHandle);
    type GetLuid = unsafe extern "system" fn(AdapterHandle, *mut NetLuid);
    type SetState = unsafe extern "system" fn(AdapterHandle, u32) -> i32;
    type GetState = unsafe extern "system" fn(AdapterHandle, *mut u32) -> i32;
    type SetConfig = unsafe extern "system" fn(AdapterHandle, *const WgInterface, u32) -> i32;
    type GetConfig = unsafe extern "system" fn(AdapterHandle, *mut WgInterface, *mut u32) -> i32;
    type DriverVersion = unsafe extern "system" fn() -> u32;
    type DeleteDriver = unsafe extern "system" fn() -> i32;

    struct Api {
        _library: *mut c_void,
        create: CreateAdapter,
        close: CloseAdapter,
        luid: GetLuid,
        set_state: SetState,
        get_state: GetState,
        set_config: SetConfig,
        get_config: GetConfig,
        driver_version: DriverVersion,
        delete_driver: DeleteDriver,
    }

    #[link(name = "kernel32")]
    extern "system" {
        fn LoadLibraryW(name: *const u16) -> *mut c_void;
        fn GetProcAddress(module: *mut c_void, name: *const u8) -> *mut c_void;
        fn GetLastError() -> u32;
        fn FreeLibrary(module: *mut c_void) -> i32;
    }

    #[link(name = "ntdll")]
    extern "system" {
        fn RtlGetVersion(info: *mut OsVersion) -> i32;
    }

    pub fn run() -> Result<(), String> {
        let dll = std::env::args()
            .nth(1)
            .ok_or_else(|| "usage: wireguard-nt-spike <path-to-wireguard.dll>".to_string())?;
        let dll_path = Path::new(&dll);
        if !dll_path.is_file() {
            return Err(format!("DLL not found at {}", dll_path.display()));
        }

        print_os();
        print_layout();
        assert_layout();

        let api = load_api(dll_path)?;
        let adapter = AdapterGuard::create(&api)?;
        let luid = unsafe {
            let mut luid = NetLuid { value: 0 };
            (api.luid)(adapter.handle, &mut luid);
            luid.value
        };
        println!("SPIKE_LUID {luid:#x}");

        let version = unsafe { (api.driver_version)() };
        println!("SPIKE_DRIVER_VERSION {version} ({version:#x})");
        if version == 0 {
            return Err(format!(
                "driver did not report a version (GetLastError={})",
                unsafe { GetLastError() }
            ));
        }

        set_config(&api, adapter.handle)?;
        set_up(&api, adapter.handle)?;
        confirm_interface_present()?;
        set_down(&api, adapter.handle)?;
        drop(adapter);
        confirm_interface_gone()?;
        let deleted = unsafe { (api.delete_driver)() };
        println!("SPIKE_DELETE_DRIVER {deleted}");

        unsafe {
            FreeLibrary(api._library);
        }
        println!("SPIKE_OK adapter={ADAPTER_NAME}");
        Ok(())
    }

    fn print_os() {
        let mut info: OsVersion = unsafe { zeroed() };
        info.size = size_of::<OsVersion>() as u32;
        let status = unsafe { RtlGetVersion(&mut info) };
        println!(
            "SPIKE_OS status={status} major={} minor={} build={} product_type={}",
            info.major, info.minor, info.build, info.product_type
        );
        if let Ok(output) = Command::new("cmd").args(["/c", "ver"]).output() {
            let text = String::from_utf8_lossy(&output.stdout);
            println!("SPIKE_VER {}", text.trim());
        }
    }

    fn print_layout() {
        println!(
            "SPIKE_LAYOUT interface={} peer={} allowed={} config={} sockaddr={}",
            size_of::<WgInterface>(),
            size_of::<WgPeer>(),
            size_of::<WgAllowedIp>(),
            size_of::<ConfigBlob>(),
            size_of::<SockaddrInet>()
        );
    }

    fn assert_layout() {
        // Sizes implied by wireguard.h `struct ALIGNED(8)` under MSVC rules.
        // The const asserts fail the build when the Rust layout drifts.
        const _: () = assert!(size_of::<WgInterface>() == 80);
        const _: () = assert!(size_of::<WgPeer>() == 136);
        const _: () = assert!(size_of::<WgAllowedIp>() == 24);
        const _: () = assert!(size_of::<SockaddrInet>() == 28);
        const _: () = assert!(size_of::<ConfigBlob>() == 240);
    }

    fn load_api(path: &Path) -> Result<Api, String> {
        let wide = wide(path.as_os_str());
        let library = unsafe { LoadLibraryW(wide.as_ptr()) };
        if library.is_null() {
            return Err(format!(
                "LoadLibraryW failed ({})",
                unsafe { GetLastError() }
            ));
        }
        Ok(Api {
            _library: library,
            create: proc(library, b"WireGuardCreateAdapter\0")?,
            close: proc(library, b"WireGuardCloseAdapter\0")?,
            luid: proc(library, b"WireGuardGetAdapterLUID\0")?,
            set_state: proc(library, b"WireGuardSetAdapterState\0")?,
            get_state: proc(library, b"WireGuardGetAdapterState\0")?,
            set_config: proc(library, b"WireGuardSetConfiguration\0")?,
            get_config: proc(library, b"WireGuardGetConfiguration\0")?,
            driver_version: proc(library, b"WireGuardGetRunningDriverVersion\0")?,
            delete_driver: proc(library, b"WireGuardDeleteDriver\0")?,
        })
    }

    fn proc<T>(library: *mut c_void, name: &[u8]) -> Result<T, String> {
        assert_eq!(size_of::<T>(), size_of::<*mut c_void>());
        let ptr = unsafe { GetProcAddress(library, name.as_ptr().cast()) };
        if ptr.is_null() {
            let label = String::from_utf8_lossy(name);
            return Err(format!("GetProcAddress failed for {label}"));
        }
        let mut slot = std::mem::MaybeUninit::<T>::uninit();
        unsafe {
            std::ptr::write(slot.as_mut_ptr().cast(), ptr);
            Ok(slot.assume_init())
        }
    }

    struct AdapterGuard<'a> {
        api: &'a Api,
        handle: AdapterHandle,
    }

    impl<'a> AdapterGuard<'a> {
        fn create(api: &'a Api) -> Result<Self, String> {
            let name = wide(ADAPTER_NAME);
            let kind = wide(TUNNEL_TYPE);
            let handle = unsafe { (api.create)(name.as_ptr(), kind.as_ptr(), ptr::null()) };
            if handle.is_null() {
                return Err(format!(
                    "WireGuardCreateAdapter failed ({})",
                    unsafe { GetLastError() }
                ));
            }
            println!("SPIKE_CREATED name={ADAPTER_NAME} type={TUNNEL_TYPE}");
            Ok(Self { api, handle })
        }
    }

    impl Drop for AdapterGuard<'_> {
        fn drop(&mut self) {
            unsafe { (self.api.close)(self.handle) };
            println!("SPIKE_CLOSED");
        }
    }

    fn set_config(api: &Api, adapter: AdapterHandle) -> Result<(), String> {
        // Ephemeral bytes, generated here and never written down. 192.0.2.1 is
        // TEST-NET-1 (RFC 5737), not a Veritas server. No route is installed,
        // so bringing the adapter up does not move the machine's traffic.
        let mut private_key = [0u8; 32];
        let mut public_key = [0u8; 32];
        fill_ephemeral(&mut private_key, 0xA5);
        fill_ephemeral(&mut public_key, 0x5A);
        let mut config = ConfigBlob {
            interface: WgInterface {
                flags: 1 << 1, // HAS_PRIVATE_KEY
                listen_port: 0,
                private_key,
                public_key: [0u8; 32],
                _pad_peers: 0,
                peers_count: 1,
            },
            peer: WgPeer {
                flags: (1 << 0) | (1 << 3) | (1 << 5),
                reserved: 0,
                public_key,
                preshared_key: [0u8; 32],
                persistent_keepalive: 0,
                _pad_endpoint: 0,
                endpoint: SockaddrInet {
                    family: AF_INET,
                    port: 51820u16.to_be(),
                    addr: [192, 0, 2, 1],
                    _rest: [0u8; 20],
                },
                tx_bytes: 0,
                rx_bytes: 0,
                last_handshake: 0,
                allowed_ips_count: 1,
                _pad_end: 0,
            },
            allowed: WgAllowedIp {
                address: [192, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
                address_family: AF_INET,
                cidr: 24,
                _pad_flags: 0,
                flags: 0,
            },
        };
        let bytes = size_of::<ConfigBlob>() as u32;
        let ok = unsafe { (api.set_config)(adapter, &mut config.interface, bytes) };
        if ok == 0 {
            return Err(format!(
                "WireGuardSetConfiguration failed ({})",
                unsafe { GetLastError() }
            ));
        }
        let mut got = [0u8; 512];
        let mut got_len = got.len() as u32;
        let read = unsafe {
            (api.get_config)(
                adapter,
                got.as_mut_ptr().cast(),
                &mut got_len,
            )
        };
        if read == 0 {
            let err = unsafe { GetLastError() };
            if err == ERROR_MORE_DATA {
                return Err(format!("WireGuardGetConfiguration needs {got_len} bytes"));
            }
            return Err(format!("WireGuardGetConfiguration failed ({err})"));
        }
        let peers = u32::from_ne_bytes(got[72..76].try_into().unwrap_or([0; 4]));
        println!("SPIKE_CONFIG_SET bytes={got_len} peers_field={peers}");
        if peers != 1 {
            return Err(format!(
                "configuration read-back did not show one peer (field={peers}, bytes={got_len})"
            ));
        }
        Ok(())
    }

    fn set_up(api: &Api, adapter: AdapterHandle) -> Result<(), String> {
        let ok = unsafe { (api.set_state)(adapter, 1) };
        if ok == 0 {
            return Err(format!(
                "WireGuardSetAdapterState(UP) failed ({})",
                unsafe { GetLastError() }
            ));
        }
        let mut state = 99u32;
        let read = unsafe { (api.get_state)(adapter, &mut state) };
        if read == 0 {
            return Err(format!(
                "WireGuardGetAdapterState failed ({})",
                unsafe { GetLastError() }
            ));
        }
        println!("SPIKE_STATE {state}");
        if state != 1 {
            return Err(format!("adapter state is {state}, expected UP (1)"));
        }
        Ok(())
    }

    fn set_down(api: &Api, adapter: AdapterHandle) -> Result<(), String> {
        let ok = unsafe { (api.set_state)(adapter, 0) };
        if ok == 0 {
            return Err(format!(
                "WireGuardSetAdapterState(DOWN) failed ({})",
                unsafe { GetLastError() }
            ));
        }
        println!("SPIKE_DOWN");
        Ok(())
    }

    fn confirm_interface_present() -> Result<(), String> {
        let output = net_adapter()?;
        println!("SPIKE_ADAPTER_PRESENT {}", output.trim());
        if !output.to_ascii_lowercase().contains(&ADAPTER_NAME.to_ascii_lowercase()) {
            return Err("Get-NetAdapter did not list VeritasVPN".into());
        }
        Ok(())
    }

    fn confirm_interface_gone() -> Result<(), String> {
        let output = Command::new("powershell")
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "Get-NetAdapter -Name 'VeritasVPN' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name",
            ])
            .output()
            .map_err(|err| format!("Get-NetAdapter after close failed to spawn: {err}"))?;
        let text = String::from_utf8_lossy(&output.stdout);
        if text.to_ascii_lowercase().contains("veritasvpn") {
            return Err(format!("adapter still present after close: {}", text.trim()));
        }
        println!("SPIKE_ADAPTER_GONE");
        Ok(())
    }

    fn net_adapter() -> Result<String, String> {
        let output = Command::new("powershell")
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "Get-NetAdapter -Name 'VeritasVPN' | Select-Object Name, Status, InterfaceDescription, MacAddress | Format-List | Out-String -Width 200",
            ])
            .output()
            .map_err(|err| format!("Get-NetAdapter failed to spawn: {err}"))?;
        if !output.status.success() {
            let err = String::from_utf8_lossy(&output.stderr);
            return Err(format!(
                "Get-NetAdapter failed: {}",
                err.trim()
            ));
        }
        Ok(String::from_utf8_lossy(&output.stdout).to_string())
    }

    fn fill_ephemeral(buf: &mut [u8], seed: u8) {
        let tick = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.subsec_nanos())
            .unwrap_or(1);
        let mut state = tick ^ (seed as u32).wrapping_mul(0x0100_0193);
        for (index, byte) in buf.iter_mut().enumerate() {
            state = state
                .wrapping_mul(1664525)
                .wrapping_add(1013904223)
                .wrapping_add(index as u32);
            *byte = (state >> 16) as u8;
        }
    }

    fn wide(value: impl AsRef<std::ffi::OsStr>) -> Vec<u16> {
        value.as_ref().encode_wide().chain(std::iter::once(0)).collect()
    }
}
