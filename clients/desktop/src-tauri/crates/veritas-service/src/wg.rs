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

pub use crate::wg_blob::{decode_preshared_key, direct_config};

type Adapter = *mut c_void;

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
        preshared_key: Option<[u8; 32]>,
        listen_port: u16,
        address: Ipv4Addr,
        prefix: u8,
        allowed: &[(Ipv4Addr, u8)],
    ) -> Result<Self, ()> {
        let _ = AdapterHandle::open(api, name).map(|old| {
            let _ = old.set_down();
        });
        let adapter = AdapterHandle::create(api, name)?;
        let blob = direct_config(private_key, peer_key, preshared_key, None, listen_port, 0, allowed);
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

fn wide(value: impl AsRef<std::ffi::OsStr>) -> Vec<u16> {
    value.as_ref().encode_wide().chain(std::iter::once(0)).collect()
}
