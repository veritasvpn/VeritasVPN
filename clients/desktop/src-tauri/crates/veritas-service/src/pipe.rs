//! Named pipe. The DACL allows SYSTEM and the interactive user only.
//! A caller whose program path is not the one recorded at install is refused.

use std::fs;
use std::path::Path;
use std::ptr;

use veritas_ipc::{decode_request, decode_response, encode, Request, Response, PIPE_NAME};
use windows_sys::Win32::Foundation::{
    CloseHandle, GetLastError, ERROR_ACCESS_DENIED, ERROR_PIPE_BUSY, ERROR_PIPE_CONNECTED,
    GENERIC_READ, GENERIC_WRITE, INVALID_HANDLE_VALUE,
};
use windows_sys::Win32::Security::Authorization::{
    ConvertStringSecurityDescriptorToSecurityDescriptorW, ConvertStringSidToSidW,
};
use windows_sys::Win32::Security::{
    GetTokenInformation, TokenUser, SECURITY_ATTRIBUTES, TOKEN_QUERY, TOKEN_USER,
};
use windows_sys::Win32::Storage::FileSystem::{
    CreateFileW, FILE_ATTRIBUTE_NORMAL, OPEN_EXISTING, PIPE_ACCESS_DUPLEX,
};
use windows_sys::Win32::System::Pipes::{
    CallNamedPipeW, ConnectNamedPipe, CreateNamedPipeW, DisconnectNamedPipe,
    GetNamedPipeClientProcessId, PIPE_READMODE_MESSAGE, PIPE_TYPE_MESSAGE,
    PIPE_UNLIMITED_INSTANCES, PIPE_WAIT,
};
use windows_sys::Win32::System::RemoteDesktop::{
    WTSGetActiveConsoleSessionId, WTSQueryUserToken,
};
use windows_sys::Win32::System::Threading::{
    OpenProcess, OpenProcessToken, QueryFullProcessImageNameW, PROCESS_QUERY_LIMITED_INFORMATION,
};

use crate::engine;

const NMPWAIT_WAIT_FOREVER: u32 = 0xffffffff;

pub fn transact(request: &Request, timeout_ms: u32) -> Result<Response, &'static str> {
    let bytes = encode(request).map_err(|_| veritas_ipc::CODE_TUNNEL_FAILED)?;
    let name = wide(PIPE_NAME);
    let mut out = vec![0u8; veritas_ipc::MAX_MESSAGE];
    let mut read = 0u32;
    let ok = unsafe {
        CallNamedPipeW(
            name.as_ptr(),
            bytes.as_ptr().cast(),
            bytes.len() as u32,
            out.as_mut_ptr().cast(),
            out.len() as u32,
            &mut read,
            timeout_ms,
        )
    };
    if ok == 0 {
        let err = unsafe { GetLastError() };
        if err == ERROR_ACCESS_DENIED {
            return Err(veritas_ipc::CODE_DENIED);
        }
        if err == ERROR_PIPE_BUSY {
            return Err(veritas_ipc::CODE_BUSY);
        }
        return Err(veritas_ipc::CODE_UNAVAILABLE);
    }
    decode_response(&out[..read as usize]).map_err(|_| veritas_ipc::CODE_TUNNEL_FAILED)
}

pub fn serve_until(stop: &std::sync::atomic::AtomicBool) {
    let sd = security_descriptor();
    while !stop.load(std::sync::atomic::Ordering::SeqCst) {
        let mut sa = SECURITY_ATTRIBUTES {
            nLength: size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: sd,
            bInheritHandle: 0,
        };
        let name = wide(PIPE_NAME);
        let pipe = unsafe {
            CreateNamedPipeW(
                name.as_ptr(),
                PIPE_ACCESS_DUPLEX,
                PIPE_TYPE_MESSAGE | PIPE_READMODE_MESSAGE | PIPE_WAIT,
                PIPE_UNLIMITED_INSTANCES,
                64 * 1024,
                64 * 1024,
                0,
                if sd.is_null() { ptr::null() } else { &mut sa },
            )
        };
        if pipe == INVALID_HANDLE_VALUE {
            break;
        }
        let connected = unsafe { ConnectNamedPipe(pipe, ptr::null_mut()) };
        let err = unsafe { GetLastError() };
        if connected == 0 && err != ERROR_PIPE_CONNECTED {
            unsafe { windows_sys::Win32::Foundation::CloseHandle(pipe) };
            continue;
        }
        if stop.load(std::sync::atomic::Ordering::SeqCst) {
            unsafe {
                DisconnectNamedPipe(pipe);
                CloseHandle(pipe);
            }
            break;
        }
        let caller_ok = client_is_allowed(pipe);
        let response = match read_request(pipe) {
            Ok(request) => engine::handle(&request, caller_ok),
            Err(()) => Response::fail(0, veritas_ipc::CODE_INVALID_CONFIG),
        };
        if let Ok(bytes) = encode(&response) {
            let mut written = 0u32;
            unsafe {
                windows_sys::Win32::Storage::FileSystem::WriteFile(
                    pipe,
                    bytes.as_ptr(),
                    bytes.len() as u32,
                    &mut written,
                    ptr::null_mut(),
                );
            }
        }
        unsafe {
            DisconnectNamedPipe(pipe);
            CloseHandle(pipe);
        }
    }
}

pub fn poke() {
    let name = wide(PIPE_NAME);
    let handle = unsafe {
        CreateFileW(
            name.as_ptr(),
            GENERIC_READ | GENERIC_WRITE,
            0,
            ptr::null(),
            OPEN_EXISTING,
            FILE_ATTRIBUTE_NORMAL,
            ptr::null_mut(),
        )
    };
    if handle != INVALID_HANDLE_VALUE {
        unsafe { CloseHandle(handle) };
    }
}

fn read_request(pipe: windows_sys::Win32::Foundation::HANDLE) -> Result<Request, ()> {
    let mut buf = vec![0u8; veritas_ipc::MAX_MESSAGE];
    let mut read = 0u32;
    let ok = unsafe {
        windows_sys::Win32::Storage::FileSystem::ReadFile(
            pipe,
            buf.as_mut_ptr(),
            buf.len() as u32,
            &mut read,
            ptr::null_mut(),
        )
    };
    if ok == 0 {
        return Err(());
    }
    decode_request(&buf[..read as usize])
}

fn client_is_allowed(pipe: windows_sys::Win32::Foundation::HANDLE) -> bool {
    let mut pid = 0u32;
    let ok = unsafe { GetNamedPipeClientProcessId(pipe, &mut pid) };
    if ok == 0 || pid == 0 {
        return false;
    }
    let process = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid) };
    if process.is_null() {
        return false;
    }
    let mut wide = vec![0u16; 1024];
    let mut len = wide.len() as u32;
    let ok = unsafe { QueryFullProcessImageNameW(process, 0, wide.as_mut_ptr(), &mut len) };
    unsafe { CloseHandle(process) };
    if ok == 0 {
        return false;
    }
    wide.truncate(len as usize);
    let path = String::from_utf16_lossy(&wide);
    paths_match(&path, &allowed_client_path().unwrap_or_default())
}

pub fn paths_match(actual: &str, allowed: &str) -> bool {
    let actual = actual.trim().trim_start_matches(r"\\?\").to_ascii_lowercase();
    let allowed = allowed.trim().trim_start_matches(r"\\?\").to_ascii_lowercase();
    !allowed.is_empty() && actual == allowed
}

fn allowed_client_path() -> Option<String> {
    let text = fs::read_to_string(state_path()).ok()?;
    for line in text.lines() {
        if let Some(path) = line.strip_prefix("path=") {
            return Some(path.trim().to_string());
        }
    }
    None
}

pub fn allowed_sids() -> Vec<String> {
    let mut sids = Vec::new();
    if let Some(sid) = console_user_sid() {
        sids.push(sid);
    }
    if let Ok(text) = fs::read_to_string(state_path()) {
        for line in text.lines() {
            if let Some(sid) = line.strip_prefix("sid=") {
                let sid = sid.trim().to_string();
                if !sid.is_empty() && !sids.iter().any(|have| have == &sid) {
                    sids.push(sid);
                }
            }
        }
    }
    sids
}

fn security_descriptor() -> *mut core::ffi::c_void {
    let mut aces = String::from("D:P(A;;GA;;;SY)");
    for sid in allowed_sids() {
        if sid.starts_with("S-1-") && sid.len() < 128 && sid.chars().all(|c| c.is_ascii() && c != '"')
        {
            aces.push_str(&format!("(A;;GRGW;;;{sid})"));
        }
    }
    let wide = wide(&aces);
    let mut sd = ptr::null_mut();
    let ok = unsafe {
        ConvertStringSecurityDescriptorToSecurityDescriptorW(wide.as_ptr(), 1, &mut sd, ptr::null_mut())
    };
    if ok == 0 {
        ptr::null_mut()
    } else {
        sd
    }
}

fn console_user_sid() -> Option<String> {
    let session = unsafe { WTSGetActiveConsoleSessionId() };
    if session == u32::MAX {
        return None;
    }
    let mut token = ptr::null_mut();
    let ok = unsafe { WTSQueryUserToken(session, &mut token) };
    if ok == 0 || token.is_null() {
        return None;
    }
    let sid = sid_of_token(token);
    unsafe { CloseHandle(token) };
    sid
}

pub fn current_user_sid() -> Option<String> {
    let mut token = ptr::null_mut();
    let ok = unsafe {
        OpenProcessToken(
            windows_sys::Win32::System::Threading::GetCurrentProcess(),
            TOKEN_QUERY,
            &mut token,
        )
    };
    if ok == 0 {
        return None;
    }
    let sid = sid_of_token(token);
    unsafe { CloseHandle(token) };
    sid
}

fn sid_of_token(token: windows_sys::Win32::Foundation::HANDLE) -> Option<String> {
    let mut needed = 0u32;
    unsafe {
        GetTokenInformation(token, TokenUser, ptr::null_mut(), 0, &mut needed);
    }
    if needed == 0 || needed > 4096 {
        return None;
    }
    let mut buf = vec![0u8; needed as usize];
    let ok = unsafe {
        GetTokenInformation(
            token,
            TokenUser,
            buf.as_mut_ptr().cast(),
            needed,
            &mut needed,
        )
    };
    if ok == 0 {
        return None;
    }
    let user = unsafe { &*(buf.as_ptr() as *const TOKEN_USER) };
    let mut text = ptr::null_mut();
    let ok = unsafe {
        windows_sys::Win32::Security::Authorization::ConvertSidToStringSidW(user.User.Sid, &mut text)
    };
    if ok == 0 || text.is_null() {
        return None;
    }
    let mut len = 0usize;
    unsafe {
        while *text.add(len) != 0 {
            len += 1;
        }
    }
    let sid = String::from_utf16_lossy(unsafe { std::slice::from_raw_parts(text, len) });
    unsafe { windows_sys::Win32::Foundation::LocalFree(text as _) };
    Some(sid)
}

pub fn write_state(client: &Path, sid: &str) -> Result<(), ()> {
    let dir = engine::program_data().ok_or(())?;
    fs::create_dir_all(&dir).map_err(|_| ())?;
    let body = format!("sid={sid}\npath={}\n", client.display());
    fs::write(dir.join("client.txt"), body).map_err(|_| ())?;
    let sddl = wide("D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)");
    let mut sd = ptr::null_mut();
    unsafe {
        if ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.as_ptr(), 1, &mut sd, ptr::null_mut()) != 0
        {
            let wide_dir = wide(dir.as_os_str());
            windows_sys::Win32::Security::SetFileSecurityW(
                wide_dir.as_ptr(),
                windows_sys::Win32::Security::DACL_SECURITY_INFORMATION,
                sd,
            );
            windows_sys::Win32::Foundation::LocalFree(sd);
        }
    }
    Ok(())
}

fn state_path() -> std::path::PathBuf {
    engine::program_data().unwrap_or_default().join("client.txt")
}

fn wide(value: impl AsRef<std::ffi::OsStr>) -> Vec<u16> {
    use std::os::windows::ffi::OsStrExt;
    value.as_ref().encode_wide().chain(std::iter::once(0)).collect()
}

#[allow(dead_code)]
fn _sid_roundtrip_typecheck(sid: &str) -> bool {
    let wide = wide(sid);
    let mut psid = ptr::null_mut();
    unsafe { ConvertStringSidToSidW(wide.as_ptr(), &mut psid) != 0 }
}
