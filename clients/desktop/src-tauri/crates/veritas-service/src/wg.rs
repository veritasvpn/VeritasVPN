//! WireGuardNT through the public DLL exports. The DLL is not modified.
//! Layout matches wireguard.h `ALIGNED(8)` and was checked on the Phase 0 spike.

use std::ffi::c_void;
use std::net::Ipv4Addr;
use std::os::windows::ffi::OsStrExt;
use std::path::Path;
use std::ptr;

use windows_sys::Win32::Foundation::{FreeLibrary, GetLastError};
use windows_sys::Win32::System::LibraryLoader::{GetProcAddress, LoadLibraryW};

pub const ADAPTER_NAME: &str = "VeritasVPN";
const TUNNEL_TYPE: &str = "WireGuard";
const AF_INET: u16 = 2;

type Adapter = *mut c_void;

#[repr(C, align(4))]
#[derive(Clone, Copy)]
struct SockaddrInet {
    family: u16,
    port: u16,
    addr: [u8; 4],
    _rest: [u8; 20],
}

#[repr(C, align(8))]
#[derive(Clone, Copy)]
struct WgInterface {
    flags: u32,
    listen_port: u16,
    private_key: [u8; 32],
    public_key: [u8; 32],
    _pad_peers: u16,
    peers_count: u32,
}

#[repr(C, align(8))]
#[derive(Clone, Copy)]
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
#[derive(Clone, Copy)]
struct WgAllowedIp {
    address: [u8; 16],
    address_family: u16,
    cidr: u8,
    _pad_flags: u8,
    flags: u32,
}

const _: () = assert!(size_of::<WgInterface>() == 80);
const _: () = assert!(size_of::<WgPeer>() == 136);
const _: () = assert!(size_of::<WgAllowedIp>() == 24);
const _: () = assert!(size_of::<SockaddrInet>() == 28);

type CreateAdapter = unsafe extern "system" fn(*const u16, *const u16, *const u8) -> Adapter;
type OpenAdapter = unsafe extern "system" fn(*const u16) -> Adapter;
type CloseAdapter = unsafe extern "system" fn(Adapter);
type GetLuid = unsafe extern "system" fn(Adapter, *mut u64);
type SetState = unsafe extern "system" fn(Adapter, u32) -> i32;
type GetState = unsafe extern "system" fn(Adapter, *mut u32) -> i32;
type SetConfig = unsafe extern "system" fn(Adapter, *const u8, u32) -> i32;
type GetConfig = unsafe extern "system" fn(Adapter, *mut u8, *mut u32) -> i32;

pub struct Api {
    library: *mut c_void,
    create: CreateAdapter,
    open: OpenAdapter,
    close: CloseAdapter,
    luid: GetLuid,
    set_state: SetState,
    get_state: GetState,
    set_config: SetConfig,
    get_config: GetConfig,
}

unsafe impl Send for Api {}
unsafe impl Sync for Api {}
unsafe impl Send for AdapterHandle {}
unsafe impl Sync for AdapterHandle {}

impl Drop for Api {
    fn drop(&mut self) {
        unsafe { FreeLibrary(self.library as _) };
    }
}

impl Api {
    pub fn load(path: &Path) -> Result<Self, ()> {
        let wide = wide(path.as_os_str());
        let library = unsafe { LoadLibraryW(wide.as_ptr()) };
        if library.is_null() {
            return Err(());
        }
        let get = |name: &[u8]| -> Result<*mut c_void, ()> {
            let ptr = unsafe { GetProcAddress(library, name.as_ptr()) };
            if ptr.is_none() {
                Err(())
            } else {
                Ok(ptr.unwrap() as *mut c_void)
            }
        };
        let cast = |ptr: *mut c_void| -> CreateAdapter { unsafe { std::mem::transmute(ptr) } };
        // Each function pointer is pointer-sized. Transmute per signature below.
        let create = get(b"WireGuardCreateAdapter\0")?;
        let open = get(b"WireGuardOpenAdapter\0")?;
        let close = get(b"WireGuardCloseAdapter\0")?;
        let luid = get(b"WireGuardGetAdapterLUID\0")?;
        let set_state = get(b"WireGuardSetAdapterState\0")?;
        let get_state = get(b"WireGuardGetAdapterState\0")?;
        let set_config = get(b"WireGuardSetConfiguration\0")?;
        let get_config = get(b"WireGuardGetConfiguration\0")?;
        let _ = cast;
        Ok(Self {
            library: library as *mut c_void,
            create: unsafe { std::mem::transmute(create) },
            open: unsafe { std::mem::transmute(open) },
            close: unsafe { std::mem::transmute(close) },
            luid: unsafe { std::mem::transmute(luid) },
            set_state: unsafe { std::mem::transmute(set_state) },
            get_state: unsafe { std::mem::transmute(get_state) },
            set_config: unsafe { std::mem::transmute(set_config) },
            get_config: unsafe { std::mem::transmute(get_config) },
        })
    }
}

pub struct AdapterHandle {
    api: &'static Api,
    handle: Adapter,
}

impl AdapterHandle {
    pub fn create(api: &'static Api, name: &str) -> Result<Self, ()> {
        let name_w = wide(name);
        let kind = wide(TUNNEL_TYPE);
        let handle = unsafe { (api.create)(name_w.as_ptr(), kind.as_ptr(), ptr::null()) };
        if handle.is_null() {
            return Err(());
        }
        Ok(Self { api, handle })
    }

    pub fn open(api: &'static Api, name: &str) -> Result<Self, ()> {
        let name_w = wide(name);
        let handle = unsafe { (api.open)(name_w.as_ptr()) };
        if handle.is_null() {
            return Err(());
        }
        Ok(Self { api, handle })
    }

    pub fn luid(&self) -> u64 {
        let mut value = 0u64;
        unsafe { (self.api().luid)(self.handle, &mut value) };
        value
    }

    pub fn set_up(&self) -> Result<(), ()> {
        self.set_state(1)
    }

    pub fn set_down(&self) -> Result<(), ()> {
        self.set_state(0)
    }

    pub fn is_up(&self) -> bool {
        let mut state = 0u32;
        let ok = unsafe { (self.api().get_state)(self.handle, &mut state) };
        ok != 0 && state == 1
    }

    pub fn set_config(&self, bytes: &[u8]) -> Result<(), ()> {
        let ok = unsafe { (self.api().set_config)(self.handle, bytes.as_ptr(), bytes.len() as u32) };
        if ok == 0 {
            Err(())
        } else {
            Ok(())
        }
    }

    pub fn counters(&self) -> Option<(u64, u64, u64)> {
        let mut buf = vec![0u8; 512];
        let mut len = buf.len() as u32;
        let ok = unsafe { (self.api().get_config)(self.handle, buf.as_mut_ptr(), &mut len) };
        if ok == 0 || (len as usize) < 80 + 128 {
            return None;
        }
        let tx = u64::from_ne_bytes(buf[80 + 104..80 + 112].try_into().ok()?);
        let rx = u64::from_ne_bytes(buf[80 + 112..80 + 120].try_into().ok()?);
        let handshake = u64::from_ne_bytes(buf[80 + 120..80 + 128].try_into().ok()?);
        Some((rx, tx, handshake))
    }

    fn set_state(&self, state: u32) -> Result<(), ()> {
        let ok = unsafe { (self.api().set_state)(self.handle, state) };
        if ok == 0 {
            Err(())
        } else {
            Ok(())
        }
    }

    fn api(&self) -> &'static Api {
        self.api
    }
}

impl Drop for AdapterHandle {
    fn drop(&mut self) {
        unsafe { (self.api().close)(self.handle) };
    }
}

pub fn adapter_present(dll: &Path) -> Result<bool, ()> {
    let api = Api::load(dll)?;
    let api: &'static Api = Box::leak(Box::new(api));
    let opened = AdapterHandle::open(api, ADAPTER_NAME);
    let present = opened.is_ok();
    drop(opened);
    Ok(present)
}

pub fn last_error() -> u32 {
    unsafe { GetLastError() }
}

pub fn direct_config(
    private_key: [u8; 32],
    peer_key: [u8; 32],
    endpoint: Option<(Ipv4Addr, u16)>,
    listen_port: u16,
    keepalive: u16,
    allowed: &[(Ipv4Addr, u8)],
) -> Vec<u8> {
    let mut flags = 1 << 1; // HAS_PRIVATE_KEY
    flags |= 1 << 3; // REPLACE_PEERS
    if listen_port != 0 {
        flags |= 1 << 2; // HAS_LISTEN_PORT
    }
    let iface = WgInterface {
        flags,
        listen_port,
        private_key,
        public_key: [0; 32],
        _pad_peers: 0,
        peers_count: 1,
    };
    let mut peer_flags = 1 << 0 | 1 << 5; // public key, replace allowed IPs
    let mut endpoint_addr = SockaddrInet {
        family: AF_INET,
        port: 0,
        addr: [0; 4],
        _rest: [0; 20],
    };
    if let Some((ip, port)) = endpoint {
        peer_flags |= 1 << 3;
        endpoint_addr.port = port.to_be();
        endpoint_addr.addr = ip.octets();
    }
    if keepalive != 0 {
        peer_flags |= 1 << 2;
    }
    let peer = WgPeer {
        flags: peer_flags,
        reserved: 0,
        public_key: peer_key,
        preshared_key: [0; 32],
        persistent_keepalive: keepalive,
        _pad_endpoint: 0,
        endpoint: endpoint_addr,
        tx_bytes: 0,
        rx_bytes: 0,
        last_handshake: 0,
        allowed_ips_count: allowed.len() as u32,
        _pad_end: 0,
    };
    let mut bytes = Vec::with_capacity(80 + 136 + 24 * allowed.len());
    bytes.extend_from_slice(bytes_of(&iface));
    bytes.extend_from_slice(bytes_of(&peer));
    for (ip, cidr) in allowed {
        let row = WgAllowedIp {
            address: ip_bytes(*ip),
            address_family: AF_INET,
            cidr: *cidr,
            _pad_flags: 0,
            flags: 0,
        };
        bytes.extend_from_slice(bytes_of(&row));
    }
    bytes
}

pub fn filetime_to_unix(ticks: u64) -> i64 {
    if ticks == 0 {
        return 0;
    }
    const EPOCH_DIFF: u64 = 11_644_473_600;
    let secs = ticks / 10_000_000;
    if secs < EPOCH_DIFF {
        0
    } else {
        (secs - EPOCH_DIFF) as i64
    }
}

/// Local test peer. Drop closes the adapter.
pub struct TestPeer {
    _adapter: AdapterHandle,
    pub luid: u64,
}

impl TestPeer {
    pub fn up(
        api: &'static Api,
        name: &str,
        private_key: [u8; 32],
        peer_key: [u8; 32],
        listen_port: u16,
        address: Ipv4Addr,
        prefix: u8,
        allowed: &[(Ipv4Addr, u8)],
    ) -> Result<Self, ()> {
        let _ = AdapterHandle::open(api, name).map(|old| {
            let _ = old.set_down();
        });
        let adapter = AdapterHandle::create(api, name)?;
        let blob = direct_config(private_key, peer_key, None, listen_port, 0, allowed);
        adapter.set_config(&blob)?;
        adapter.set_up()?;
        let luid = adapter.luid();
        crate::net::assign_v4(luid, address, prefix).map_err(|_| ())?;
        crate::net::set_mtu(luid, 1280).map_err(|_| ())?;
        Ok(Self {
            _adapter: adapter,
            luid,
        })
    }
}

fn ip_bytes(ip: Ipv4Addr) -> [u8; 16] {
    let o = ip.octets();
    let mut out = [0u8; 16];
    out[..4].copy_from_slice(&o);
    out
}

fn bytes_of<T>(value: &T) -> &[u8] {
    unsafe { std::slice::from_raw_parts((value as *const T).cast(), size_of::<T>()) }
}

fn wide(value: impl AsRef<std::ffi::OsStr>) -> Vec<u16> {
    value.as_ref().encode_wide().chain(std::iter::once(0)).collect()
}
