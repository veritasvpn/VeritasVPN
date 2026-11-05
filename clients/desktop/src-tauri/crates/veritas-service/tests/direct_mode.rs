//! Proves the service on a Windows runner: install, pipe ACL, a local
//! WireGuard peer, traffic, and teardown. Not part of the Linux build.

#![cfg(windows)]

use std::fs;
use std::io::Write;
use std::net::{Ipv4Addr, SocketAddr};
use std::path::PathBuf;
use std::process::Command;
use std::thread;
use std::time::Duration;

use base64::{engine::general_purpose::STANDARD, Engine};
use veritas_core::{generate_keypair, WgTunnelConfig};
use veritas_ipc::{Request, OP_CONNECT, OP_DISCONNECT, OP_HELLO, OP_STATS};
use veritas_service::{ensure_api, transact, TestPeer};
use windows_sys::Win32::Foundation::{
    CloseHandle, GetLastError, ERROR_ACCESS_DENIED, GENERIC_READ, GENERIC_WRITE,
    INVALID_HANDLE_VALUE,
};
use windows_sys::Win32::Security::{
    ImpersonateLoggedOnUser, LogonUserW, RevertToSelf, LOGON32_LOGON_NETWORK,
    LOGON32_PROVIDER_DEFAULT,
};
use windows_sys::Win32::Storage::FileSystem::{
    CreateFileW, FILE_ATTRIBUTE_NORMAL, OPEN_EXISTING,
};

#[test]
fn direct_mode_on_the_runner() {
    let dll = std::env::var("VERITAS_WIREGUARD_DLL").expect("VERITAS_WIREGUARD_DLL");
    let service = PathBuf::from(
        option_env!("CARGO_BIN_EXE_veritas_service").unwrap_or("veritas-service.exe"),
    );
    let client = std::env::current_exe().expect("test exe");
    let _guard = Cleanup {
        service: service.clone(),
    };
    fs::copy(&dll, service.parent().unwrap().join("wireguard.dll")).expect("copy dll");

    run(
        &service,
        ["install", "--client", &client.display().to_string(), "--dll", &dll],
    );
    println!("SERVICE_INSTALLED");
    run(&service, ["start"]);
    wait_until_hello();
    println!("PIPE_HELLO_OK");

    let path_denied = Command::new(&service).args(["call", "hello"]).output().unwrap();
    let path_text = String::from_utf8_lossy(&path_denied.stdout);
    assert!(
        path_text.contains("CALL_CODE denied"),
        "service binary must not be accepted as the UI: {path_text}"
    );
    println!("PIPE_PATH_DENIED");

    assert_other_user_denied();
    println!("PIPE_ACL_DENIED");

    let server_keys = generate_keypair();
    let client_keys = generate_keypair();
    let api = ensure_api(std::path::Path::new(&dll)).expect("load dll");
    let _peer = TestPeer::up(
        api,
        "VeritasTestSrv",
        decode(&server_keys.private_key),
        decode(&client_keys.public_key),
        51830,
        "10.66.0.1".parse().unwrap(),
        24,
        &[("10.66.0.2".parse().unwrap(), 32)],
    )
    .expect("server peer");

    let host = primary_ipv4();
    let config = WgTunnelConfig {
        private_key: client_keys.private_key,
        address: "10.66.0.2/32".into(),
        dns: "10.66.0.1".into(),
        server_public_key: server_keys.public_key,
        endpoint: format!("{host}:51830"),
        allowed_ips: vec!["10.66.0.1/32".into()],
        peer_id: "peer-test".into(),
        preshared_key: String::new(),
        stealth_endpoint: String::new(),
        stealth_path_prefix: String::new(),
        endpoint_lan: String::new(),
        endpoint_wan: String::new(),
    };
    let mut request = Request::new(2, OP_CONNECT);
    request.config = Some(config);
    let connected = transact(&request, 20_000).expect("connect pipe");
    assert!(
        connected.ok,
        "connect code {} log {}",
        connected.code,
        fs::read_to_string(r"C:\ProgramData\VeritasVPN\service.log").unwrap_or_default()
    );
    println!("TUNNEL_UP");

    let mut handshake = 0i64;
    let mut rx = 0u64;
    for _ in 0..30 {
        let stats = transact(&Request::new(3, OP_STATS), 2_000).expect("stats");
        let status = stats.status.expect("status");
        assert!(status.up);
        handshake = status.handshake;
        rx = status.rx;
        if handshake > 0 && rx > 0 {
            break;
        }
        thread::sleep(Duration::from_millis(500));
    }
    assert!(handshake > 0, "handshake did not complete");
    allow_icmp();
    let ping = Command::new("ping")
        .args(["-n", "3", "-w", "2000", "10.66.0.1"])
        .output()
        .expect("ping");
    assert!(ping.status.success(), "ping failed: {}", String::from_utf8_lossy(&ping.stdout));
    let stats = transact(&Request::new(4, OP_STATS), 2_000).expect("stats after ping");
    let status = stats.status.unwrap();
    assert!(status.rx > 0 && status.tx > 0, "no tunnel traffic");
    assert_eq!(mtu_of("VeritasVPN"), 1280);
    assert!(dns_is("VeritasVPN", "10.66.0.1"), "dns was not applied");
    println!("TRAFFIC_OK rx={} tx={}", status.rx, status.tx);

    let down = transact(&Request::new(5, OP_DISCONNECT), 10_000).expect("disconnect");
    assert!(down.ok);
    thread::sleep(Duration::from_millis(400));
    assert!(adapter_gone("VeritasVPN"));
    drop(_peer);
    thread::sleep(Duration::from_millis(400));
    assert!(adapter_gone("VeritasTestSrv"));
    println!("TEARDOWN_OK");
}

struct Cleanup {
    service: PathBuf,
}

impl Drop for Cleanup {
    fn drop(&mut self) {
        let _ = Command::new(&self.service).arg("repair").status();
        let _ = Command::new(&self.service).arg("stop").status();
        let _ = Command::new(&self.service).arg("uninstall").status();
        let _ = Command::new("netsh").args([
            "advfirewall", "firewall", "delete", "rule", "name=VeritasPhase2Icmp",
        ]).status();
        let _ = Command::new("net").args(["user", "veritas-other", "/delete"]).status();
    }
}

fn wait_until_hello() {
    for _ in 0..50 {
        if let Ok(response) = transact(&Request::new(1, OP_HELLO), 1_000) {
            if response.ok {
                return;
            }
        }
        thread::sleep(Duration::from_millis(200));
    }
    panic!("service pipe did not answer hello");
}

fn assert_other_user_denied() {
    let ticks = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.subsec_nanos())
        .unwrap_or(1);
    // 14 characters or fewer, or `net user` asks a question this runner cannot answer.
    let password = format!("Aa1!{ticks:09x}");
    let mut child = Command::new("net")
        .args(["user", "veritas-other", password.as_str(), "/add"])
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .spawn()
        .expect("net user");
    child.stdin.as_mut().unwrap().write_all(b"Y\r\n").ok();
    let created = child.wait_with_output().expect("net user");
    assert!(
        created.status.success(),
        "could not create the other user: {}",
        String::from_utf8_lossy(&created.stdout)
    );
    let user = wide("veritas-other");
    let pass = wide(&password);
    let mut token = std::ptr::null_mut();
    let ok = unsafe {
        LogonUserW(
            user.as_ptr(),
            std::ptr::null(),
            pass.as_ptr(),
            LOGON32_LOGON_NETWORK,
            LOGON32_PROVIDER_DEFAULT,
            &mut token,
        )
    };
    assert!(ok != 0, "logon of the other user failed ({})", unsafe { GetLastError() });
    unsafe { ImpersonateLoggedOnUser(token) };
    let name = wide(veritas_ipc::PIPE_NAME);
    let handle = unsafe {
        CreateFileW(
            name.as_ptr(),
            GENERIC_READ | GENERIC_WRITE,
            0,
            std::ptr::null(),
            OPEN_EXISTING,
            FILE_ATTRIBUTE_NORMAL,
            std::ptr::null_mut(),
        )
    };
    let err = unsafe { GetLastError() };
    unsafe {
        if handle != INVALID_HANDLE_VALUE {
            CloseHandle(handle);
        }
        RevertToSelf();
        CloseHandle(token);
    }
    assert_eq!(err, ERROR_ACCESS_DENIED, "other user was not rejected ({err})");
}

fn allow_icmp() {
    let _ = Command::new("netsh").args([
        "advfirewall", "firewall", "add", "rule", "name=VeritasPhase2Icmp", "dir=in",
        "action=allow", "protocol=icmpv4",
    ]).status();
}

fn mtu_of(name: &str) -> u32 {
    let script = format!(
        "(Get-NetIPInterface -InterfaceAlias '{name}' -AddressFamily IPv4).NlMtu"
    );
    let output = Command::new("powershell")
        .args(["-NoProfile", "-Command", &script])
        .output()
        .unwrap();
    String::from_utf8_lossy(&output.stdout).trim().parse().unwrap_or(0)
}

fn dns_is(name: &str, expect: &str) -> bool {
    let script = format!(
        "(Get-DnsClientServerAddress -InterfaceAlias '{name}' -AddressFamily IPv4).ServerAddresses -join ','"
    );
    let output = Command::new("powershell")
        .args(["-NoProfile", "-Command", &script])
        .output()
        .unwrap();
    String::from_utf8_lossy(&output.stdout).contains(expect)
}

fn adapter_gone(name: &str) -> bool {
    let script = format!("Get-NetAdapter -Name '{name}' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name");
    let output = Command::new("powershell")
        .args(["-NoProfile", "-Command", &script])
        .output()
        .unwrap();
    !String::from_utf8_lossy(&output.stdout).to_ascii_lowercase().contains(&name.to_ascii_lowercase())
}

fn primary_ipv4() -> Ipv4Addr {
    let output = Command::new("powershell")
        .args(["-NoProfile", "-Command", "(Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '127.*' -and $_.PrefixOrigin -ne 'WellKnown' } | Select-Object -First 1 -ExpandProperty IPAddress)"])
        .output()
        .unwrap();
    String::from_utf8_lossy(&output.stdout).trim().parse().expect("host ipv4")
}

fn decode(text: &str) -> [u8; 32] {
    STANDARD.decode(text.trim()).unwrap().try_into().unwrap()
}

fn run(bin: &std::path::Path, args: impl IntoIterator<Item = impl AsRef<std::ffi::OsStr>>) {
    let output = Command::new(bin).args(args).output().expect("spawn service");
    assert!(
        output.status.success(),
        "service command failed {}",
        String::from_utf8_lossy(&output.stderr)
    );
}

#[test]
fn leak_harness_blocks_until_repair() {
    let dll = std::env::var("VERITAS_WIREGUARD_DLL").expect("VERITAS_WIREGUARD_DLL");
    let service = PathBuf::from(
        option_env!("CARGO_BIN_EXE_veritas_service").unwrap_or("veritas-service.exe"),
    );
    let client = std::env::current_exe().expect("test exe");
    let _guard = Cleanup {
        service: service.clone(),
    };
    fs::copy(&dll, service.parent().unwrap().join("wireguard.dll")).expect("copy dll");
    run(
        &service,
        ["install", "--client", &client.display().to_string(), "--dll", &dll],
    );
    run(&service, ["start"]);
    wait_until_hello();

    let listener = std::net::TcpListener::bind("0.0.0.0:0").expect("listener");
    let port = listener.local_addr().unwrap().port();
    thread::spawn(move || {
        for _ in listener.incoming() {}
    });
    let physical = primary_ipv4();
    let open = SocketAddr::from((physical, port));
    let loopback = SocketAddr::from((Ipv4Addr::LOCALHOST, port));
    assert!(
        probe(open).is_ok(),
        "baseline connection to the physical address should work before the lock"
    );

    let _peer = bring_up_tunnel(&dll);
    // Drop the tunnel default route so the next connects try the physical NIC.
    // The lock has to be what stops them. The /32 tunnel route stays.
    drop_tunnel_default_route();
    allow_icmp();
    let ping = Command::new("ping")
        .args(["-n", "2", "-w", "2000", "10.66.0.1"])
        .output()
        .expect("ping");
    assert!(ping.status.success(), "tunnel traffic was not permitted");
    assert!(probe(loopback).is_ok(), "loopback must stay open");
    let v6_loop = std::net::TcpListener::bind("[::1]:0").ok();
    if let Some(v6_loop) = v6_loop {
        let addr = v6_loop.local_addr().unwrap();
        thread::spawn(move || {
            for _ in v6_loop.incoming() {}
        });
        assert!(probe(addr).is_ok(), "ipv6 loopback must stay open");
    }
    assert_blocked(open, "physical leak while connected");
    assert_blocked(SocketAddr::from((Ipv4Addr::new(203, 0, 113, 10), 53)), "dns port 53");
    assert_blocked(SocketAddr::from((Ipv4Addr::new(203, 0, 113, 10), 853)), "dns port 853");
    let v6 = ipv6_listener();
    if let Some(addr) = v6 {
        assert_blocked(addr, "ipv6 leak while connected");
        println!("IPV6_BLOCKED");
    } else {
        println!("IPV6_NO_LOCAL");
    }
    println!("LEAK_CONNECTED");

    let api = ensure_api(std::path::Path::new(&dll)).expect("dll");
    if let Ok(adapter) = veritas_service::AdapterHandle::open(api, "VeritasVPN") {
        let _ = adapter.set_down();
    }
    assert!(probe(loopback).is_ok(), "loopback during reconnect");
    assert_blocked(open, "physical leak while reconnecting");
    println!("LEAK_RECONNECTING");

    let _ = Command::new("taskkill")
        .args(["/F", "/IM", "veritas-service.exe"])
        .status();
    thread::sleep(Duration::from_millis(400));
    assert_blocked(open, "physical leak after the service was killed");
    thread::sleep(Duration::from_millis(1500));
    assert_blocked(open, "physical leak after service recovery");
    println!("LEAK_AFTER_KILL");

    run(&service, ["repair"]);
    assert!(probe(open).is_ok(), "repair must release the lock");
    println!("LEAK_REPAIRED");
}

fn bring_up_tunnel(dll: &str) -> TestPeer {
    let server_keys = generate_keypair();
    let client_keys = generate_keypair();
    let api = ensure_api(std::path::Path::new(dll)).expect("load dll");
    let peer = TestPeer::up(
        api,
        "VeritasTestSrv",
        decode(&server_keys.private_key),
        decode(&client_keys.public_key),
        51830,
        "10.66.0.1".parse().unwrap(),
        24,
        &[("10.66.0.2".parse().unwrap(), 32)],
    )
    .expect("server peer");
    let host = primary_ipv4();
    let config = WgTunnelConfig {
        private_key: client_keys.private_key,
        address: "10.66.0.2/32".into(),
        dns: "10.66.0.1".into(),
        server_public_key: server_keys.public_key,
        endpoint: format!("{host}:51830"),
        allowed_ips: vec!["0.0.0.0/0".into(), "::/0".into(), "10.66.0.1/32".into()],
        peer_id: "peer-lock".into(),
        preshared_key: String::new(),
        stealth_endpoint: String::new(),
        stealth_path_prefix: String::new(),
        endpoint_lan: String::new(),
        endpoint_wan: String::new(),
    };
    let mut request = Request::new(2, OP_CONNECT);
    request.config = Some(config);
    let connected = transact(&request, 20_000).expect("connect");
    assert!(
        connected.ok,
        "connect code {} log {}",
        connected.code,
        fs::read_to_string(r"C:\ProgramData\VeritasVPN\service.log").unwrap_or_default()
    );
    peer
}

fn probe(addr: SocketAddr) -> std::io::Result<()> {
    std::net::TcpStream::connect_timeout(&addr, Duration::from_millis(800)).map(|_| ())
}

fn assert_blocked(addr: SocketAddr, label: &str) {
    match probe(addr) {
        Ok(()) => panic!("{label}: connection was allowed"),
        Err(err) => {
            let raw = err.raw_os_error();
            assert!(
                err.kind() == std::io::ErrorKind::PermissionDenied || raw == Some(5) || raw == Some(10013),
                "{label}: filter did not deny it (kind {:?} raw {raw:?})",
                err.kind()
            );
        }
    }
}

fn drop_tunnel_default_route() {
    let _ = Command::new("powershell").args([
        "-NoProfile",
        "-Command",
        "Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue | Where-Object { $_.InterfaceAlias -like '*Veritas*' -or $_.InterfaceAlias -like '*WireGuard*' } | Remove-NetRoute -Confirm:$false -ErrorAction SilentlyContinue",
    ]).status();
}

fn ipv6_listener() -> Option<SocketAddr> {
    let output = Command::new("powershell").args([
        "-NoProfile",
        "-Command",
        "(Get-NetIPAddress -AddressFamily IPv6 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -notlike 'fe80*' -and $_.IPAddress -ne '::1' } | Select-Object -First 1 -ExpandProperty IPAddress)",
    ]).output().ok()?;
    let text = String::from_utf8_lossy(&output.stdout);
    let addr: std::net::Ipv6Addr = text.trim().parse().ok()?;
    let listener = std::net::TcpListener::bind((addr, 0)).ok()?;
    let bound = listener.local_addr().ok()?;
    thread::spawn(move || {
        for _ in listener.incoming() {}
    });
    Some(bound)
}

fn wide(value: &str) -> Vec<u16> {
    use std::os::windows::ffi::OsStrExt;
    std::ffi::OsStr::new(value).encode_wide().chain(std::iter::once(0)).collect()
}
