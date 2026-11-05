//! Service install, recovery restarts, and the service control loop.

use std::ffi::OsStr;
use std::os::windows::ffi::OsStrExt;
use std::path::Path;
use std::process::{Command, Stdio};
use std::ptr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use windows_sys::Win32::Foundation::GetLastError;
use windows_sys::Win32::System::Services::{
    ChangeServiceConfig2W, CloseServiceHandle, ControlService, CreateServiceW, DeleteService,
    OpenSCManagerW, OpenServiceW, QueryServiceStatus, RegisterServiceCtrlHandlerExW,
    SetServiceStatus, StartServiceW, StartServiceCtrlDispatcherW, SC_ACTION, SC_ACTION_RESTART,
    SC_HANDLE, SC_MANAGER_CONNECT, SC_MANAGER_CREATE_SERVICE, SERVICE_ACCEPT_POWEREVENT,
    SERVICE_ACCEPT_SHUTDOWN, SERVICE_ACCEPT_STOP, SERVICE_AUTO_START, SERVICE_CONFIG_DESCRIPTION,
    SERVICE_CONFIG_FAILURE_ACTIONS, SERVICE_CONTROL_POWEREVENT, SERVICE_CONTROL_SHUTDOWN,
    SERVICE_CONTROL_STOP, SERVICE_DESCRIPTIONW, SERVICE_ERROR_NORMAL, SERVICE_FAILURE_ACTIONSW,
    SERVICE_QUERY_STATUS, SERVICE_RUNNING, SERVICE_START_PENDING, SERVICE_STATUS, SERVICE_STOPPED,
    SERVICE_STOP_PENDING, SERVICE_TABLE_ENTRYW, SERVICE_WIN32_OWN_PROCESS,
};

use crate::engine;
use crate::pipe;

const NAME: &str = "VeritasVPN";

static STOP: AtomicBool = AtomicBool::new(false);
static STATUS_HANDLE: std::sync::atomic::AtomicPtr<std::ffi::c_void> =
    std::sync::atomic::AtomicPtr::new(ptr::null_mut());

pub fn install(client: &Path, dll: Option<&Path>, helper: Option<&Path>) -> Result<(), i32> {
    let client = std::fs::canonicalize(client).map_err(|_| 3)?;
    if let Some(helper) = helper {
        if !crate::stealth::helper_is_pinned(helper) {
            engine::log_line("install", "helper");
            return Err(3);
        }
    }
    let sid = pipe::current_user_sid().ok_or(3)?;
    pipe::write_state(&client, &sid).map_err(|_| 3)?;
    if let Some(dir) = engine::program_data() {
        let _ = std::fs::create_dir_all(&dir);
        if let Some(dll) = dll {
            let _ = std::fs::copy(dll, dir.join("wireguard.dll"));
        }
        if let Some(helper) = helper {
            std::fs::copy(helper, dir.join("wstunnel.exe")).map_err(|_| 3)?;
        }
    }
    unsafe {
        let scm = OpenSCManagerW(ptr::null(), ptr::null(), SC_MANAGER_CREATE_SERVICE | SC_MANAGER_CONNECT);
        if scm.is_null() {
            return Err(3);
        }
        let _ = delete_open(scm);
        let exe = std::env::current_exe().map_err(|_| 3)?;
        let bin = format!("\"{}\" run", exe.display());
        let name = wide(NAME);
        let display = wide("VeritasVPN");
        let path = wide(&bin);
        let service = CreateServiceW(
            scm,
            name.as_ptr(),
            display.as_ptr(),
            0xF01FF, // SERVICE_ALL_ACCESS
            SERVICE_WIN32_OWN_PROCESS,
            SERVICE_AUTO_START,
            SERVICE_ERROR_NORMAL,
            path.as_ptr(),
            ptr::null(),
            ptr::null_mut(),
            ptr::null(),
            ptr::null(),
            ptr::null(),
        );
        if service.is_null() {
            CloseServiceHandle(scm);
            return Err(3);
        }
        set_recovery(service);
        set_description(service);
        CloseServiceHandle(service);
        CloseServiceHandle(scm);
    }
    Ok(())
}

pub fn uninstall() -> Result<(), i32> {
    // Stop first. The monitor re-arms the lock if it is still running.
    let _ = stop_service();
    wait_until_stopped();
    let lock_ok = crate::engine::release_lock().is_ok();
    let _ = remove_adapter();
    kill_helper();
    let deleted = delete_service();
    let _ = remove_autostart();
    let files_ok = remove_program_data();
    println!("UNINSTALL_LOCK {}", u8::from(lock_ok));
    println!("UNINSTALL_SERVICE {}", u8::from(deleted));
    println!("UNINSTALL_FILES {}", u8::from(files_ok));
    if deleted && files_ok {
        Ok(())
    } else {
        Err(3)
    }
}

pub fn service_installed() -> bool {
    unsafe {
        let scm = OpenSCManagerW(ptr::null(), ptr::null(), SC_MANAGER_CONNECT);
        if scm.is_null() {
            return false;
        }
        let name = wide(NAME);
        let service = OpenServiceW(scm, name.as_ptr(), SERVICE_QUERY_STATUS);
        let present = !service.is_null();
        if !service.is_null() {
            CloseServiceHandle(service);
        }
        CloseServiceHandle(scm);
        present
    }
}

pub fn start() -> Result<(), i32> {
    unsafe {
        let scm = OpenSCManagerW(ptr::null(), ptr::null(), SC_MANAGER_CONNECT);
        if scm.is_null() {
            return Err(3);
        }
        let name = wide(NAME);
        let service = OpenServiceW(scm, name.as_ptr(), 0x0010 | 0x0020); // START | QUERY
        if service.is_null() {
            CloseServiceHandle(scm);
            return Err(3);
        }
        let ok = StartServiceW(service, 0, ptr::null());
        CloseServiceHandle(service);
        CloseServiceHandle(scm);
        if ok == 0 && GetLastError() != 1056 {
            return Err(3);
        }
    }
    Ok(())
}

pub fn stop_service() -> Result<(), i32> {
    unsafe {
        let scm = OpenSCManagerW(ptr::null(), ptr::null(), SC_MANAGER_CONNECT);
        if scm.is_null() {
            return Err(3);
        }
        let name = wide(NAME);
        let service = OpenServiceW(scm, name.as_ptr(), 0x0020 | 0x0004); // QUERY | STOP
        if service.is_null() {
            CloseServiceHandle(scm);
            return Ok(());
        }
        let mut status = std::mem::zeroed::<SERVICE_STATUS>();
        let ok = ControlService(service, SERVICE_CONTROL_STOP, &mut status);
        CloseServiceHandle(service);
        CloseServiceHandle(scm);
        // 1062: the service is already stopped.
        if ok == 0 && GetLastError() != 1062 {
            return Err(3);
        }
    }
    wait_until_stopped();
    Ok(())
}

pub fn run_service() -> Result<(), i32> {
    let name = wide(NAME);
    let mut table = [
        SERVICE_TABLE_ENTRYW {
            lpServiceName: name.as_ptr() as *mut _,
            lpServiceProc: Some(service_main),
        },
        SERVICE_TABLE_ENTRYW {
            lpServiceName: ptr::null_mut(),
            lpServiceProc: None,
        },
    ];
    let ok = unsafe { StartServiceCtrlDispatcherW(table.as_mut_ptr()) };
    if ok == 0 {
        Err(3)
    } else {
        Ok(())
    }
}

unsafe extern "system" fn service_main(_argc: u32, _argv: *mut windows_sys::core::PWSTR) {
    let name = wide(NAME);
    let handle = RegisterServiceCtrlHandlerExW(name.as_ptr(), Some(control_handler), ptr::null());
    if handle.is_null() {
        return;
    }
    STATUS_HANDLE.store(handle as *mut _, Ordering::SeqCst);
    set_state(SERVICE_START_PENDING, 0, 3000);
    STOP.store(false, Ordering::SeqCst);
    set_state(
        SERVICE_RUNNING,
        SERVICE_ACCEPT_STOP | SERVICE_ACCEPT_SHUTDOWN | SERVICE_ACCEPT_POWEREVENT,
        0,
    );
    engine::start_monitor();
    pipe::serve_until(&STOP);
    engine::shutdown_tunnel();
    set_state(SERVICE_STOPPED, 0, 0);
}

unsafe extern "system" fn control_handler(
    control: u32,
    event: u32,
    _data: *mut core::ffi::c_void,
    _context: *mut core::ffi::c_void,
) -> u32 {
    if control == SERVICE_CONTROL_STOP || control == SERVICE_CONTROL_SHUTDOWN {
        set_state(SERVICE_STOP_PENDING, 0, 5000);
        STOP.store(true, Ordering::SeqCst);
        pipe::poke();
    } else if control == SERVICE_CONTROL_POWEREVENT {
        // PBT_APMSUSPEND, PBT_APMRESUMEAUTOMATIC, PBT_APMRESUMESUSPEND.
        if event == 4 {
            engine::note_power("sleep");
        } else if event == 7 || event == 18 {
            engine::note_power("resume");
        }
    }
    0
}

fn set_state(state: u32, accept: u32, wait_ms: u32) {
    let handle = STATUS_HANDLE.load(Ordering::SeqCst);
    if handle.is_null() {
        return;
    }
    let status = SERVICE_STATUS {
        dwServiceType: SERVICE_WIN32_OWN_PROCESS,
        dwCurrentState: state,
        dwControlsAccepted: accept,
        dwWin32ExitCode: 0,
        dwServiceSpecificExitCode: 0,
        dwCheckPoint: if state == SERVICE_STOPPED || state == SERVICE_RUNNING { 0 } else { 1 },
        dwWaitHint: wait_ms,
    };
    unsafe { SetServiceStatus(handle as _, &status) };
}

fn set_recovery(service: SC_HANDLE) {
    let mut actions = [
        SC_ACTION { Type: SC_ACTION_RESTART, Delay: 1000 },
        SC_ACTION { Type: SC_ACTION_RESTART, Delay: 5000 },
        SC_ACTION { Type: SC_ACTION_RESTART, Delay: 30_000 },
    ];
    let mut failure = SERVICE_FAILURE_ACTIONSW {
        dwResetPeriod: 24 * 60 * 60,
        lpRebootMsg: ptr::null_mut(),
        lpCommand: ptr::null_mut(),
        cActions: actions.len() as u32,
        lpsaActions: actions.as_mut_ptr(),
    };
    unsafe {
        ChangeServiceConfig2W(
            service,
            SERVICE_CONFIG_FAILURE_ACTIONS,
            &mut failure as *mut SERVICE_FAILURE_ACTIONSW as *mut _,
        );
    }
}

fn set_description(service: SC_HANDLE) {
    let mut text = wide("VeritasVPN Direct mode tunnel");
    let mut desc = SERVICE_DESCRIPTIONW { lpDescription: text.as_mut_ptr() };
    unsafe {
        ChangeServiceConfig2W(
            service,
            SERVICE_CONFIG_DESCRIPTION,
            &mut desc as *mut SERVICE_DESCRIPTIONW as *mut _,
        );
    }
}

unsafe fn delete_open(scm: SC_HANDLE) -> bool {
    let name = wide(NAME);
    let service = OpenServiceW(scm, name.as_ptr(), 0x00010000); // DELETE
    if service.is_null() {
        return true;
    }
    let mut status = std::mem::zeroed::<SERVICE_STATUS>();
    let stop = OpenServiceW(scm, name.as_ptr(), 0x0020 | 0x0004);
    if !stop.is_null() {
        ControlService(stop, SERVICE_CONTROL_STOP, &mut status);
        CloseServiceHandle(stop);
    }
    let ok = DeleteService(service);
    CloseServiceHandle(service);
    ok != 0
}

fn wait_until_stopped() {
    let start = Instant::now();
    while start.elapsed() < Duration::from_secs(8) {
        if !service_running() {
            return;
        }
        std::thread::sleep(Duration::from_millis(200));
    }
}

fn service_running() -> bool {
    unsafe {
        let scm = OpenSCManagerW(ptr::null(), ptr::null(), SC_MANAGER_CONNECT);
        if scm.is_null() {
            return false;
        }
        let name = wide(NAME);
        let service = OpenServiceW(scm, name.as_ptr(), SERVICE_QUERY_STATUS);
        if service.is_null() {
            CloseServiceHandle(scm);
            return false;
        }
        let mut status = std::mem::zeroed::<SERVICE_STATUS>();
        let ok = QueryServiceStatus(service, &mut status);
        CloseServiceHandle(service);
        CloseServiceHandle(scm);
        ok != 0 && status.dwCurrentState != SERVICE_STOPPED
    }
}

fn delete_service() -> bool {
    unsafe {
        let scm = OpenSCManagerW(ptr::null(), ptr::null(), SC_MANAGER_CONNECT);
        if scm.is_null() {
            return false;
        }
        let _ = delete_open(scm);
        CloseServiceHandle(scm);
    }
    let start = Instant::now();
    while start.elapsed() < Duration::from_secs(8) {
        if !service_installed() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(200));
    }
    !service_installed()
}

fn remove_adapter() -> bool {
    let Ok(path) = engine::dll_path() else {
        return true;
    };
    match crate::wg::adapter_present(&path) {
        Ok(true) => {
            // Opening the adapter is enough for WireGuardNT to drop it when
            // this process closes the handle. A second look must miss it.
            crate::wg::adapter_present(&path).ok() != Some(true)
        }
        Ok(false) => true,
        Err(()) => false,
    }
}

fn kill_helper() {
    let Ok(mut child) = Command::new("taskkill")
        .args(["/F", "/IM", "wstunnel.exe"])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
    else {
        return;
    };
    let start = Instant::now();
    loop {
        if child.try_wait().ok().flatten().is_some() {
            return;
        }
        if start.elapsed() > Duration::from_secs(8) {
            let _ = child.kill();
            return;
        }
        std::thread::sleep(Duration::from_millis(100));
    }
}

fn remove_program_data() -> bool {
    let Some(dir) = engine::program_data() else {
        return true;
    };
    if !dir.exists() {
        return true;
    }
    std::fs::remove_dir_all(&dir).is_ok() || !dir.exists()
}

fn remove_autostart() -> bool {
    use windows_sys::Win32::System::Registry::{
        RegCloseKey, RegDeleteValueW, RegOpenKeyExW, HKEY_CURRENT_USER, KEY_SET_VALUE,
    };
    let key_name = wide("Software\\Microsoft\\Windows\\CurrentVersion\\Run");
    let mut key = ptr::null_mut();
    let err = unsafe {
        RegOpenKeyExW(
            HKEY_CURRENT_USER,
            key_name.as_ptr(),
            0,
            KEY_SET_VALUE,
            &mut key,
        )
    };
    if err != 0 {
        return true;
    }
    let value = wide(NAME);
    unsafe {
        RegDeleteValueW(key, value.as_ptr());
        RegCloseKey(key);
    }
    true
}

fn wide(value: impl AsRef<OsStr>) -> Vec<u16> {
    value.as_ref().encode_wide().chain(std::iter::once(0)).collect()
}
