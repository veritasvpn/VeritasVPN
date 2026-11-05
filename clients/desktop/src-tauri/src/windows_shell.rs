//! Tray, one running window, and the current-user start-up entry.
//! The tunnel stays with the service. Closing the window does not disconnect.

use std::ffi::OsStr;
use std::os::windows::ffi::OsStrExt;
use std::ptr;
use std::sync::atomic::{AtomicBool, AtomicPtr, Ordering};
use std::sync::OnceLock;

use tauri::{AppHandle, Emitter, Manager};
use windows_sys::Win32::Foundation::{GetLastError, HWND, POINT};
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
use windows_sys::Win32::System::Threading::CreateMutexW;
use windows_sys::Win32::UI::Shell::{
    ExtractIconW, Shell_NotifyIconW, NIF_ICON, NIF_MESSAGE, NIF_TIP, NIM_ADD, NOTIFYICONDATAW,
};
use windows_sys::Win32::UI::WindowsAndMessaging::{
    AppendMenuW, CreatePopupMenu, CreateWindowExW, DefWindowProcW, FindWindowW, GetCursorPos,
    ModifyMenuW, PostMessageW, RegisterClassW, SetForegroundWindow, ShowWindow, TrackPopupMenu,
    HWND_MESSAGE, MF_STRING, SW_SHOW, TPM_RETURNCMD, WNDCLASSW, WM_LBUTTONDBLCLK, WM_LBUTTONUP,
    WM_RBUTTONUP,
};

const TRAY_MSG: u32 = 0x8000 + 24;
const ID_OPEN: usize = 1;
const ID_TOGGLE: usize = 2;
const ID_QUIT: usize = 3;
const ERROR_ALREADY_EXISTS: u32 = 183;

static RESIDENT: AtomicBool = AtomicBool::new(false);
static QUIT: AtomicBool = AtomicBool::new(false);
static MENU: AtomicPtr<std::ffi::c_void> = AtomicPtr::new(ptr::null_mut());
static APP: OnceLock<AppHandle> = OnceLock::new();

pub fn acquire_instance() -> bool {
    let name = wide(r"Local\VeritasVPN");
    let handle = unsafe { CreateMutexW(ptr::null(), 0, name.as_ptr()) };
    if handle.is_null() {
        return true;
    }
    if unsafe { GetLastError() } == ERROR_ALREADY_EXISTS {
        unsafe { windows_sys::Win32::Foundation::CloseHandle(handle) };
        focus_existing();
        return false;
    }
    // The mutex lives until this process exits.
    std::mem::forget(handle);
    true
}

pub fn watch_close(window: &tauri::WebviewWindow) {
    let window = window.clone();
    window.clone().on_window_event(move |event| {
        if let tauri::WindowEvent::CloseRequested { api, .. } = event {
            if RESIDENT.load(Ordering::SeqCst) && !QUIT.load(Ordering::SeqCst) {
                api.prevent_close();
                let _ = window.hide();
            }
        }
    });
}

pub fn install_tray(app: AppHandle) {
    let _ = APP.set(app);
    unsafe {
        let class_name = leak_wide("VeritasVPNTray");
        let mut class: WNDCLASSW = std::mem::zeroed();
        class.lpfnWndProc = Some(wndproc);
        class.hInstance = GetModuleHandleW(ptr::null());
        class.lpszClassName = class_name.as_ptr();
        if RegisterClassW(&class) == 0 && GetLastError() != 1410 {
            return;
        }
        let hwnd = CreateWindowExW(
            0,
            class_name.as_ptr(),
            class_name.as_ptr(),
            0,
            0,
            0,
            0,
            0,
            HWND_MESSAGE,
            ptr::null_mut(),
            class.hInstance,
            ptr::null(),
        );
        if hwnd.is_null() {
            return;
        }
        let menu = CreatePopupMenu();
        if menu.is_null() {
            return;
        }
        let open = wide("Open");
        let connect = wide("Connect");
        let quit = wide("Quit");
        AppendMenuW(menu, MF_STRING, ID_OPEN, open.as_ptr());
        AppendMenuW(menu, MF_STRING, ID_TOGGLE, connect.as_ptr());
        AppendMenuW(menu, MF_STRING, ID_QUIT, quit.as_ptr());
        MENU.store(menu as *mut _, Ordering::SeqCst);

        let mut data: NOTIFYICONDATAW = std::mem::zeroed();
        data.cbSize = std::mem::size_of::<NOTIFYICONDATAW>() as u32;
        data.hWnd = hwnd;
        data.uID = 1;
        data.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
        data.uCallbackMessage = TRAY_MSG;
        data.hIcon = tray_icon();
        let tip = wide("VeritasVPN");
        for (index, unit) in tip.iter().take(data.szTip.len() - 1).enumerate() {
            data.szTip[index] = *unit;
        }
        Shell_NotifyIconW(NIM_ADD, &data);
    }
}

pub fn set_resident(connected: bool) {
    RESIDENT.store(connected, Ordering::SeqCst);
    let menu = MENU.load(Ordering::SeqCst);
    if menu.is_null() {
        return;
    }
    let text = wide(if connected { "Disconnect" } else { "Connect" });
    unsafe {
        ModifyMenuW(menu as _, ID_TOGGLE as u32, MF_STRING, ID_TOGGLE, text.as_ptr());
    }
}

pub fn autostart_enabled() -> bool {
    use windows_sys::Win32::System::Registry::{
        RegCloseKey, RegOpenKeyExW, RegQueryValueExW, HKEY_CURRENT_USER, KEY_QUERY_VALUE,
    };
    unsafe {
        let mut key = ptr::null_mut();
        let name = wide(RUN_KEY);
        if RegOpenKeyExW(HKEY_CURRENT_USER, name.as_ptr(), 0, KEY_QUERY_VALUE, &mut key) != 0 {
            return false;
        }
        let value = wide("VeritasVPN");
        let err = RegQueryValueExW(key, value.as_ptr(), ptr::null_mut(), ptr::null_mut(), ptr::null_mut(), ptr::null_mut());
        RegCloseKey(key);
        err == 0
    }
}

pub fn set_autostart(enabled: bool) -> bool {
    use windows_sys::Win32::System::Registry::{
        RegCloseKey, RegDeleteValueW, RegOpenKeyExW, RegSetValueExW, HKEY_CURRENT_USER,
        KEY_SET_VALUE, REG_SZ,
    };
    unsafe {
        let mut key = ptr::null_mut();
        let name = wide(RUN_KEY);
        if RegOpenKeyExW(HKEY_CURRENT_USER, name.as_ptr(), 0, KEY_SET_VALUE, &mut key) != 0 {
            return false;
        }
        let value = wide("VeritasVPN");
        let ok = if enabled {
            let Ok(exe) = std::env::current_exe() else {
                RegCloseKey(key);
                return false;
            };
            let command = wide(format!("\"{}\"", exe.display()));
            RegSetValueExW(
                key,
                value.as_ptr(),
                0,
                REG_SZ,
                command.as_ptr() as *const u8,
                (command.len() * 2) as u32,
            ) == 0
        } else {
            let err = RegDeleteValueW(key, value.as_ptr());
            err == 0 || err == 2
        };
        RegCloseKey(key);
        ok
    }
}

const RUN_KEY: &str = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";

fn focus_existing() {
    let title = wide("VeritasVPN");
    unsafe {
        let hwnd = FindWindowW(ptr::null(), title.as_ptr());
        if !hwnd.is_null() {
            ShowWindow(hwnd, SW_SHOW);
            SetForegroundWindow(hwnd);
        }
    }
}

fn open_main() {
    let Some(app) = APP.get() else {
        return;
    };
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

fn toggle_from_tray() {
    open_main();
    let Some(app) = APP.get() else {
        return;
    };
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.emit("tray-toggle", ());
    }
}

fn quit_from_tray() {
    QUIT.store(true, Ordering::SeqCst);
    if let Some(app) = APP.get() {
        app.exit(0);
    }
}

fn show_menu(hwnd: HWND) {
    let menu = MENU.load(Ordering::SeqCst);
    if menu.is_null() {
        return;
    }
    unsafe {
        let mut point = POINT { x: 0, y: 0 };
        GetCursorPos(&mut point);
        SetForegroundWindow(hwnd);
        let cmd = TrackPopupMenu(
            menu as _,
            TPM_RETURNCMD,
            point.x,
            point.y,
            0,
            hwnd,
            ptr::null(),
        );
        PostMessageW(hwnd, 0, 0, 0);
        match cmd as usize {
            ID_OPEN => open_main(),
            ID_TOGGLE => toggle_from_tray(),
            ID_QUIT => quit_from_tray(),
            _ => {}
        }
    }
}

unsafe extern "system" fn wndproc(
    hwnd: HWND,
    msg: u32,
    wparam: windows_sys::Win32::Foundation::WPARAM,
    lparam: windows_sys::Win32::Foundation::LPARAM,
) -> windows_sys::Win32::Foundation::LRESULT {
    if msg == TRAY_MSG {
        let event = lparam as u32;
        if event == WM_RBUTTONUP {
            show_menu(hwnd);
        } else if event == WM_LBUTTONUP || event == WM_LBUTTONDBLCLK {
            open_main();
        }
        return 0;
    }
    DefWindowProcW(hwnd, msg, wparam, lparam)
}

fn tray_icon() -> windows_sys::Win32::UI::WindowsAndMessaging::HICON {
    let Ok(exe) = std::env::current_exe() else {
        return ptr::null_mut();
    };
    let path = wide(exe.as_os_str());
    unsafe { ExtractIconW(GetModuleHandleW(ptr::null()), path.as_ptr(), 0) }
}

fn leak_wide(value: &str) -> &'static [u16] {
    Box::leak(wide(value).into_boxed_slice())
}

fn wide(value: impl AsRef<OsStr>) -> Vec<u16> {
    value.as_ref().encode_wide().chain(std::iter::once(0)).collect()
}
