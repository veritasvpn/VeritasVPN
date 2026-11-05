//! Service install, recovery restarts, and the service control loop.

use std::ffi::OsStr;
use std::os::windows::ffi::OsStrExt;
use std::path::Path;
use std::ptr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use windows_sys::Win32::System::Services::{
    ChangeServiceConfig2W, CloseServiceHandle, ControlService, CreateServiceW, DeleteService,
    OpenSCManagerW, OpenServiceW, RegisterServiceCtrlHandlerExW, SetServiceStatus, StartServiceW,
    StartServiceCtrlDispatcherW, SC_ACTION, SC_ACTION_RESTART, SC_HANDLE, SC_MANAGER_CONNECT,
    SC_MANAGER_CREATE_SERVICE, SERVICE_ACCEPT_SHUTDOWN, SERVICE_ACCEPT_STOP,
    SERVICE_AUTO_START, SERVICE_CONFIG_DESCRIPTION, SERVICE_CONFIG_FAILURE_ACTIONS,
    SERVICE_CONTROL_SHUTDOWN, SERVICE_CONTROL_STOP, SERVICE_DESCRIPTIONW, SERVICE_ERROR_NORMAL,
    SERVICE_FAILURE_ACTIONSW, SERVICE_RUNNING, SERVICE_START_PENDING, SERVICE_STATUS,
    SERVICE_STOPPED, SERVICE_STOP_PENDING, SERVICE_TABLE_ENTRYW, SERVICE_WIN32_OWN_PROCESS,
};

use crate::engine;
use crate::pipe;

const NAME: &str = "VeritasVPN";

static STOP: AtomicBool = AtomicBool::new(false);
static STATUS_HANDLE: std::sync::atomic::AtomicPtr<std::ffi::c_void> =
    std::sync::atomic::AtomicPtr::new(ptr::null_mut());

pub fn install(client: &Path, dll: Option<&Path>) -> Result<(), i32> {
    let client = std::fs::canonicalize(client).map_err(|_| 3)?;
    let sid = pipe::current_user_sid().ok_or(3)?;
    pipe::write_state(&client, &sid).map_err(|_| 3)?;
    if let Some(dll) = dll {
        if let Some(dir) = engine::program_data() {
            let _ = std::fs::create_dir_all(&dir);
            let _ = std::fs::copy(dll, dir.join("wireguard.dll"));
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
    let _ = crate::engine::release_lock();
    let _ = stop_service();
    unsafe {
        let scm = OpenSCManagerW(ptr::null(), ptr::null(), SC_MANAGER_CONNECT);
        if scm.is_null() {
            return Err(3);
        }
        let deleted = delete_open(scm);
        CloseServiceHandle(scm);
        if !deleted {
            return Err(3);
        }
    }
    if let Some(dir) = engine::program_data() {
        let _ = std::fs::remove_file(dir.join("client.txt"));
    }
    Ok(())
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
        if ok == 0 {
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
        if ok == 0 {
            return Err(3);
        }
    }
    std::thread::sleep(Duration::from_millis(500));
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
    set_state(SERVICE_RUNNING, SERVICE_ACCEPT_STOP | SERVICE_ACCEPT_SHUTDOWN, 0);
    pipe::serve_until(&STOP);
    engine::shutdown_tunnel();
    set_state(SERVICE_STOPPED, 0, 0);
}

unsafe extern "system" fn control_handler(
    control: u32,
    _event: u32,
    _data: *mut core::ffi::c_void,
    _context: *mut core::ffi::c_void,
) -> u32 {
    if control == SERVICE_CONTROL_STOP || control == SERVICE_CONTROL_SHUTDOWN {
        set_state(SERVICE_STOP_PENDING, 0, 5000);
        STOP.store(true, Ordering::SeqCst);
        pipe::poke();
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

fn wide(value: impl AsRef<OsStr>) -> Vec<u16> {
    value.as_ref().encode_wide().chain(std::iter::once(0)).collect()
}
