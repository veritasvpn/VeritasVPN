//! Stealth helper. wstunnel 10.6.2, hash-pinned, killed with the service.
//! It never decides to fall back to Direct.

use std::fs;
use std::path::{Path, PathBuf};
use std::ptr;

use sha2::{Digest, Sha256};
use windows_sys::Win32::Foundation::{CloseHandle, GetLastError};
use windows_sys::Win32::System::JobObjects::{
    AssignProcessToJobObject, CreateJobObjectW, SetInformationJobObject, JobObjectExtendedLimitInformation,
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
};
use windows_sys::Win32::System::Threading::{
    CreateProcessW, ResumeThread, TerminateProcess, CREATE_NO_WINDOW, CREATE_SUSPENDED,
    PROCESS_INFORMATION, STARTUPINFOW,
};

const EXE_SHA256: &str = "e41b00423da049fd1395b456e422d3c806c9c8c4108eb4ea3e988cbeec493db9";
pub const LOCAL_WG: &str = "127.0.0.1:41820";

pub struct Helper {
    job: windows_sys::Win32::Foundation::HANDLE,
    process: windows_sys::Win32::Foundation::HANDLE,
    thread: windows_sys::Win32::Foundation::HANDLE,
}

unsafe impl Send for Helper {}

impl Drop for Helper {
    fn drop(&mut self) {
        unsafe {
            TerminateProcess(self.process, 1);
            CloseHandle(self.process);
            CloseHandle(self.thread);
            CloseHandle(self.job);
        }
    }
}

impl Helper {
    pub fn alive(&self) -> bool {
        let mut code = 0u32;
        let ok = unsafe { windows_sys::Win32::System::Threading::GetExitCodeProcess(self.process, &mut code) };
        ok != 0 && code == 259
    }
}

pub fn locate() -> Option<PathBuf> {
    if let Some(path) = std::env::var_os("VERITAS_WSTUNNEL") {
        let path = PathBuf::from(path);
        if hashed(&path) {
            return Some(path);
        }
    }
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            let beside = dir.join("wstunnel.exe");
            if hashed(&beside) {
                return Some(beside);
            }
        }
    }
    if let Some(dir) = crate::engine::program_data() {
        let stored = dir.join("wstunnel.exe");
        if hashed(&stored) {
            return Some(stored);
        }
    }
    None
}

pub fn start(exe: &Path, remote: &str, prefix: &str) -> Result<Helper, u32> {
    let url = tunnel_url(remote);
    let listen = format!("udp://{LOCAL_WG}:127.0.0.1:51820?timeout_sec=0");
    let mut command = format!("\"{}\" client", exe.display());
    if !prefix.is_empty() {
        command.push_str(" --http-upgrade-path-prefix ");
        command.push_str(prefix);
    }
    command.push_str(" -L \"");
    command.push_str(&listen);
    command.push_str("\" ");
    command.push_str(&url);
    let wide = wide(&command);
    let mut si: STARTUPINFOW = unsafe { std::mem::zeroed() };
    si.cb = std::mem::size_of::<STARTUPINFOW>() as u32;
    let mut pi: PROCESS_INFORMATION = unsafe { std::mem::zeroed() };
    let ok = unsafe {
        CreateProcessW(
            ptr::null(),
            wide.as_ptr() as *mut _,
            ptr::null(),
            ptr::null(),
            0,
            CREATE_NO_WINDOW | CREATE_SUSPENDED,
            ptr::null(),
            ptr::null(),
            &si,
            &mut pi,
        )
    };
    if ok == 0 {
        return Err(unsafe { GetLastError() });
    }
    let job = unsafe { CreateJobObjectW(ptr::null(), ptr::null()) };
    if job.is_null() {
        unsafe {
            TerminateProcess(pi.hProcess, 1);
            CloseHandle(pi.hProcess);
            CloseHandle(pi.hThread);
        }
        return Err(unsafe { GetLastError() });
    }
    let mut limits: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = unsafe { std::mem::zeroed() };
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    let set = unsafe {
        SetInformationJobObject(
            job,
            JobObjectExtendedLimitInformation,
            &limits as *const _ as *const _,
            std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
        )
    };
    if set == 0 || unsafe { AssignProcessToJobObject(job, pi.hProcess) } == 0 {
        let err = unsafe { GetLastError() };
        unsafe {
            TerminateProcess(pi.hProcess, 1);
            CloseHandle(pi.hProcess);
            CloseHandle(pi.hThread);
            CloseHandle(job);
        }
        return Err(err);
    }
    unsafe { ResumeThread(pi.hThread) };
    Ok(Helper {
        job,
        process: pi.hProcess,
        thread: pi.hThread,
    })
}

pub fn prefix_ok(prefix: &str) -> bool {
    !prefix.is_empty()
        && !prefix.contains("..")
        && prefix
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'/' | b'.' | b'_' | b'~' | b'-'))
}

pub fn tunnel_url(endpoint: &str) -> String {
    let host = endpoint.split(':').next().unwrap_or("");
    if host.parse::<std::net::Ipv4Addr>().is_ok() {
        format!("ws://{endpoint}")
    } else {
        format!("wss://{endpoint}")
    }
}

fn hashed(path: &Path) -> bool {
    let Ok(bytes) = fs::read(path) else {
        return false;
    };
    let digest = Sha256::digest(&bytes);
    let mut hex = String::with_capacity(64);
    for byte in digest {
        hex.push_str(&format!("{byte:02x}"));
    }
    hex == EXE_SHA256
}

fn wide(value: &str) -> Vec<u16> {
    use std::os::windows::ffi::OsStrExt;
    std::ffi::OsStr::new(value).encode_wide().chain(std::iter::once(0)).collect()
}
