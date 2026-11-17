//! Stealth helper. wstunnel 10.6.2, hash-pinned, killed with the service.
//! It never decides to fall back to Direct.

use std::fs;
use std::io::{BufRead, BufReader, Write};
use std::os::windows::io::FromRawHandle;
use std::path::{Path, PathBuf};
use std::ptr;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::Arc;
use std::time::Instant;

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
    health: Arc<Health>,
}

/// What the log reader learned about the running helper.
#[derive(Default)]
pub struct Health {
    /// wstunnel was retrying a refused upgrade in a tight loop and was cut.
    flood: AtomicBool,
    upgrade_failures: AtomicU32,
}

unsafe impl Send for Helper {}

impl Drop for Helper {
    fn drop(&mut self) {
        unsafe {
            TerminateProcess(self.process, 1);
            // Wait briefly so the next helper can bind 127.0.0.1:41820.
            windows_sys::Win32::System::Threading::WaitForSingleObject(self.process, 3_000);
            CloseHandle(self.process);
            CloseHandle(self.thread);
            CloseHandle(self.job);
        }
    }
}

impl Helper {
    pub fn alive(&self) -> bool {
        if self.flooded() {
            return false;
        }
        let mut code = 0u32;
        let ok = unsafe { windows_sys::Win32::System::Threading::GetExitCodeProcess(self.process, &mut code) };
        ok != 0 && code == 259
    }

    /// The websocket upgrade failed in a loop and the helper was stopped.
    pub fn flooded(&self) -> bool {
        self.health.flood.load(Ordering::SeqCst)
    }

    pub fn upgrade_failures(&self) -> u32 {
        self.health.upgrade_failures.load(Ordering::SeqCst)
    }
}

pub fn helper_is_pinned(path: &Path) -> bool {
    hashed(path)
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
        if let Ok(text) = fs::read_to_string(dir.join("wstunnel.path")) {
            let pointed = PathBuf::from(text.trim());
            if hashed(&pointed) {
                return Some(pointed);
            }
        }
        let stored = dir.join("wstunnel.exe");
        if hashed(&stored) {
            return Some(stored);
        }
    }
    None
}

pub fn start(exe: &Path, remote: &str, prefix: &str) -> Result<Helper, u32> {
    let url = crate::stealth_watch::tunnel_url(remote);
    let secret = veritas_core::normalize_path_prefix(prefix);
    let listen = format!("udp://{LOCAL_WG}:127.0.0.1:51820?timeout_sec=0");
    let mut command = format!("\"{}\" client", exe.display());
    // wstunnel builds `/<prefix>/events` itself and the server secret has no
    // leading slash, so always hand it the bare segment.
    if let Some(prefix) = veritas_core::normalize_path_prefix(prefix) {
        command.push_str(" --http-upgrade-path-prefix ");
        command.push_str(&prefix);
    }
    command.push_str(" -L \"");
    command.push_str(&listen);
    command.push_str("\" ");
    command.push_str(&url);
    let wide = wide(&command);
    let mut si: STARTUPINFOW = unsafe { std::mem::zeroed() };
    si.cb = std::mem::size_of::<STARTUPINFOW>() as u32;
    // wstunnel writes into a pipe the service drains. Writing straight to a
    // file let a refused upgrade loop fill the disk (600k lines in minutes).
    let (pipe_read, log) = output_pipe();
    if !log.is_null() {
        si.dwFlags = 0x00000100;
        si.hStdInput = log;
        si.hStdOutput = log;
        si.hStdError = log;
    }
    let mut pi: PROCESS_INFORMATION = unsafe { std::mem::zeroed() };
    let ok = unsafe {
        CreateProcessW(
            ptr::null(),
            wide.as_ptr() as *mut _,
            ptr::null(),
            ptr::null(),
            if log.is_null() { 0 } else { 1 },
            CREATE_NO_WINDOW | CREATE_SUSPENDED,
            ptr::null(),
            ptr::null(),
            &si,
            &mut pi,
        )
    };
    if !log.is_null() {
        unsafe { CloseHandle(log) };
    }
    if ok == 0 {
        let err = unsafe { GetLastError() };
        if !pipe_read.is_null() {
            unsafe { CloseHandle(pipe_read) };
        }
        return Err(err);
    }
    let health = Arc::new(Health::default());
    if !pipe_read.is_null() {
        spawn_reader(pipe_read, pi.hProcess, health.clone(), secret);
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
    thread_sleep();
    let helper = Helper {
        job,
        process: pi.hProcess,
        thread: pi.hThread,
        health,
    };
    if !helper.alive() {
        return Err(helper_exit(&helper));
    }
    Ok(helper)
}

fn helper_exit(helper: &Helper) -> u32 {
    let mut code = 1u32;
    unsafe { windows_sys::Win32::System::Threading::GetExitCodeProcess(helper.process, &mut code) };
    code
}

/// Read end (service only) and inheritable write end (child stdout/stderr).
fn output_pipe() -> (windows_sys::Win32::Foundation::HANDLE, windows_sys::Win32::Foundation::HANDLE) {
    use windows_sys::Win32::Foundation::{SetHandleInformation, HANDLE_FLAG_INHERIT};
    use windows_sys::Win32::Security::SECURITY_ATTRIBUTES;
    use windows_sys::Win32::System::Pipes::CreatePipe;
    let mut security = SECURITY_ATTRIBUTES {
        nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
        lpSecurityDescriptor: ptr::null_mut(),
        bInheritHandle: 1,
    };
    let mut read = ptr::null_mut();
    let mut write = ptr::null_mut();
    if unsafe { CreatePipe(&mut read, &mut write, &mut security, 64 * 1024) } == 0 {
        return (ptr::null_mut(), ptr::null_mut());
    }
    if unsafe { SetHandleInformation(read, HANDLE_FLAG_INHERIT, 0) } == 0 {
        unsafe {
            CloseHandle(read);
            CloseHandle(write);
        }
        return (ptr::null_mut(), ptr::null_mut());
    }
    (read, write)
}

/// Drain wstunnel output into a capped, rotated stealth.log with the path
/// secret masked. The pipe is always drained, so wstunnel never blocks or
/// aborts on a full or closed stdout. On a refused-upgrade loop the helper is
/// terminated; the engine restarts it on the Stealth backoff.
fn spawn_reader(
    pipe_read: windows_sys::Win32::Foundation::HANDLE,
    process: windows_sys::Win32::Foundation::HANDLE,
    health: Arc<Health>,
    secret: Option<String>,
) {
    use windows_sys::Win32::Foundation::{DuplicateHandle, DUPLICATE_SAME_ACCESS};
    use windows_sys::Win32::System::Threading::GetCurrentProcess;
    let mut killer: windows_sys::Win32::Foundation::HANDLE = ptr::null_mut();
    let dup = unsafe {
        DuplicateHandle(
            GetCurrentProcess(),
            process,
            GetCurrentProcess(),
            &mut killer,
            0,
            0,
            DUPLICATE_SAME_ACCESS,
        )
    };
    let killer = if dup == 0 { 0usize } else { killer as usize };
    let pipe = pipe_read as usize;
    std::thread::spawn(move || {
        let file = unsafe { fs::File::from_raw_handle(pipe as *mut _) };
        let mut reader = BufReader::new(file);
        let mut gate = crate::stealth_watch::LogGate::new(secret.as_deref());
        let mut sink = LogSink::open();
        let mut raw = Vec::with_capacity(512);
        loop {
            raw.clear();
            match reader.read_until(b'\n', &mut raw) {
                Ok(0) | Err(_) => break,
                Ok(_) => {}
            }
            let line = String::from_utf8_lossy(&raw);
            let line = line.trim_end_matches(['\n', '\r']);
            let out = gate.line(line, Instant::now());
            if out.upgrade_failed {
                health.upgrade_failures.fetch_add(1, Ordering::SeqCst);
            }
            if let Some(text) = out.text.as_deref() {
                sink.write_line(text);
            }
            if out.flood {
                health.flood.store(true, Ordering::SeqCst);
                if killer != 0 {
                    unsafe { TerminateProcess(killer as *mut _, 1) };
                }
            }
        }
        if killer != 0 {
            unsafe { CloseHandle(killer as *mut _) };
        }
    });
}

struct LogSink {
    path: Option<PathBuf>,
    file: Option<fs::File>,
    len: u64,
}

impl LogSink {
    fn open() -> Self {
        let Some(dir) = crate::engine::program_data() else {
            return Self { path: None, file: None, len: 0 };
        };
        let _ = fs::create_dir_all(&dir);
        let path = dir.join("stealth.log");
        // Each helper start begins a fresh log; the previous one is kept once.
        if fs::metadata(&path).map(|m| m.len() > 0).unwrap_or(false) {
            let _ = fs::rename(&path, dir.join("stealth.log.1"));
        }
        let file = fs::OpenOptions::new().create(true).write(true).truncate(true).open(&path).ok();
        Self { path: Some(path), file, len: 0 }
    }

    fn write_line(&mut self, text: &str) {
        let add = text.len() as u64 + 1;
        if crate::stealth_watch::should_rotate(self.len, add) {
            self.rotate();
        }
        if let Some(file) = self.file.as_mut() {
            if writeln!(file, "{text}").is_ok() {
                self.len += add;
            }
        }
    }

    fn rotate(&mut self) {
        let Some(path) = self.path.clone() else {
            return;
        };
        self.file = None;
        let _ = fs::rename(&path, path.with_extension("log.1"));
        self.file = fs::OpenOptions::new().create(true).write(true).truncate(true).open(&path).ok();
        self.len = 0;
    }
}

fn thread_sleep() {
    std::thread::sleep(std::time::Duration::from_millis(400));
}

pub fn prefix_ok(prefix: &str) -> bool {
    veritas_core::normalize_path_prefix(prefix).is_some()
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
