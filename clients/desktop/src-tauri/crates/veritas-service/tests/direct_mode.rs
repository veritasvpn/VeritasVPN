//! Proves the service on a Windows runner: install, pipe ACL, a local
//! WireGuard peer, traffic, and teardown. Not part of the Linux build.

#![cfg(windows)]

use std::fs;
use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr};
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

use base64::{engine::general_purpose::STANDARD, Engine};
use veritas_core::{generate_keypair, WgTunnelConfig};
use veritas_ipc::{Request, OP_CONNECT, OP_DISCONNECT, OP_HELLO, OP_STATS};
use veritas_service::{ensure_api, management_snapshot, transact, TestPeer};
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
    let _guard = Cleanup {
        service: service.clone(),
    };
    let service = service.clone();
    finish_within(Duration::from_secs(150), move || direct_mode_body(dll, service));
}

fn direct_mode_body(dll: String, service: PathBuf) {
    let client = std::env::current_exe().expect("test exe");
    fs::copy(&dll, service.parent().unwrap().join("wireguard.dll")).expect("copy dll");

    run(
        &service,
        ["install", "--client", &client.display().to_string(), "--dll", &dll],
    );
    println!("SERVICE_INSTALLED");
    run(&service, ["start"]);
    wait_until_hello();
    println!("PIPE_HELLO_OK");

    let path_denied = output_bounded_os(
        &service,
        &["call".into(), "hello".into()],
        Duration::from_secs(15),
    );
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
        allowed_ips: vec!["10.66.0.1/32".into(), "10.66.0.50/32".into()],
        peer_id: "peer-test".into(),
        preshared_key: String::new(),
        stealth_endpoint: String::new(),
        stealth_path_prefix: String::new(),
        endpoint_lan: String::new(),
        endpoint_wan: String::new(),
    };
    let mut request = Request::new(2, OP_CONNECT);
    request.config = Some(config);
    attach_management(&mut request);
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
    assert_tunnel_carries();
    let stats = transact(&Request::new(4, OP_STATS), 2_000).expect("stats after traffic");
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
        // Bound every call. A Drop that waits on WFP or PowerShell is how the
        // job sat for an hour with the runner cut off.
        quiet_bounded(&self.service, &["repair"], Duration::from_secs(15));
        quiet_bounded(&self.service, &["stop"], Duration::from_secs(15));
        quiet_bounded(&self.service, &["uninstall"], Duration::from_secs(15));
        quiet_bounded(
            "netsh",
            &["advfirewall", "firewall", "delete", "rule", "name=VeritasPhase2Icmp"],
            Duration::from_secs(10),
        );
        quiet_bounded("net", &["user", "veritas-other", "/delete"], Duration::from_secs(10));
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
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("net user");
    child.stdin.as_mut().unwrap().write_all(b"Y\r\n").ok();
    let created = wait_output(child, Duration::from_secs(15), "net user");
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

fn assert_tunnel_carries() {
    // 10.66.0.1 is also a local address on the test peer, so a ping can be
    // answered without entering the tunnel and the lock correctly drops that.
    // 10.66.0.50 is only routed at the tunnel.
    let before = transact(&Request::new(7, OP_STATS), 2_000).expect("stats").status.expect("status").tx;
    let socket = std::net::UdpSocket::bind("0.0.0.0:0").expect("udp");
    socket.set_write_timeout(Some(Duration::from_secs(2))).ok();
    let sent = socket.send_to(b"veritas", "10.66.0.50:9");
    thread::sleep(Duration::from_millis(500));
    let after = transact(&Request::new(8, OP_STATS), 2_000).expect("stats").status.expect("status").tx;
    assert!(after > before, "tunnel did not carry the packet (send {sent:?} tx {before} -> {after})");
    eprintln!("TUNNEL_CARRY tx {before} -> {after}");
}

fn allow_icmp() {
    let _ = output_bounded(
        "netsh",
        &[
            "advfirewall", "firewall", "add", "rule", "name=VeritasPhase2Icmp", "dir=in",
            "action=allow", "protocol=icmpv4",
        ],
        Duration::from_secs(10),
    );
}

fn mtu_of(name: &str) -> u32 {
    let script = format!(
        "(Get-NetIPInterface -InterfaceAlias '{name}' -AddressFamily IPv4).NlMtu"
    );
    let output = output_bounded("powershell", &["-NoProfile", "-Command", &script], Duration::from_secs(15));
    String::from_utf8_lossy(&output.stdout).trim().parse().unwrap_or(0)
}

fn dns_is(name: &str, expect: &str) -> bool {
    let script = format!(
        "(Get-DnsClientServerAddress -InterfaceAlias '{name}' -AddressFamily IPv4).ServerAddresses -join ','"
    );
    let output = output_bounded("powershell", &["-NoProfile", "-Command", &script], Duration::from_secs(15));
    String::from_utf8_lossy(&output.stdout).contains(expect)
}

fn adapter_gone(name: &str) -> bool {
    let script = format!("Get-NetAdapter -Name '{name}' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name");
    let output = output_bounded("powershell", &["-NoProfile", "-Command", &script], Duration::from_secs(15));
    !String::from_utf8_lossy(&output.stdout).to_ascii_lowercase().contains(&name.to_ascii_lowercase())
}

fn primary_ipv4() -> Ipv4Addr {
    management_snapshot().local_v4.expect("host ipv4")
}

fn decode(text: &str) -> [u8; 32] {
    STANDARD.decode(text.trim()).unwrap().try_into().unwrap()
}

fn run(bin: &std::path::Path, args: impl IntoIterator<Item = impl AsRef<std::ffi::OsStr>>) {
    let args: Vec<std::ffi::OsString> = args.into_iter().map(|a| a.as_ref().to_os_string()).collect();
    let output = output_bounded_os(bin, &args, Duration::from_secs(20));
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
    let _guard = Cleanup {
        service: service.clone(),
    };
    let service = service.clone();
    finish_within(Duration::from_secs(120), move || leak_body(dll, service));
}

fn leak_body(dll: String, service: PathBuf) {
    let client = std::env::current_exe().expect("test exe");
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
    let doc = outside_oracle();

    // No 0.0.0.0/0 route: that blackholed the runner. full_lock still blocks
    // the physical NIC. Management hosts captured before the lock stay permitted.
    let _peer = bring_up_tunnel(&dll);
    assert_tunnel_carries();
    assert!(probe(loopback).is_ok(), "loopback must stay open");
    let v6_loop = std::net::TcpListener::bind("[::1]:0").ok();
    if let Some(v6_loop) = v6_loop {
        let addr = v6_loop.local_addr().unwrap();
        thread::spawn(move || {
            for _ in v6_loop.incoming() {}
        });
        assert!(probe(addr).is_ok(), "ipv6 loopback must stay open");
    }
    // Windows delivers a connection to this machine's own IP as loopback, so
    // that connect staying open is not a physical leak.
    assert!(probe(open).is_ok(), "own address should stay reachable");
    eprintln!("PHYSICAL_SELF_STAYS_OPEN");
    assert_blocked(doc, "non-local ipv4 while connected");
    assert_blocked(SocketAddr::from((Ipv4Addr::new(203, 0, 113, 10), 53)), "dns port 53");
    assert_blocked(SocketAddr::from((Ipv4Addr::new(203, 0, 113, 10), 853)), "dns port 853");
    let doc6: SocketAddr = "[2001:db8::1]:9".parse().unwrap();
    match probe(doc6) {
        Err(err) if is_denied(&err) => eprintln!("IPV6_BLOCKED"),
        Err(_) => eprintln!("IPV6_NO_ROUTE"),
        Ok(()) => panic!("non-local ipv6 while connected: connection was allowed"),
    }
    eprintln!("LEAK_CONNECTED");

    let api = ensure_api(std::path::Path::new(&dll)).expect("dll");
    if let Ok(adapter) = veritas_service::AdapterHandle::open(api, "VeritasVPN") {
        let _ = adapter.set_down();
    }
    assert!(probe(loopback).is_ok(), "loopback during reconnect");
    assert_blocked(doc, "non-local ipv4 while reconnecting");
    eprintln!("LEAK_RECONNECTING");

    let _ = output_bounded("taskkill", &["/F", "/IM", "veritas-service.exe"], Duration::from_secs(10));
    thread::sleep(Duration::from_millis(400));
    assert_blocked(doc, "non-local ipv4 after the service was killed");
    thread::sleep(Duration::from_millis(1500));
    assert_blocked(doc, "non-local ipv4 after service recovery");
    eprintln!("LEAK_AFTER_KILL");

    run(&service, ["repair"]);
    assert!(probe(open).is_ok(), "repair must release the lock");
    // The outside address is chosen because it was not denied before the lock.
    // After repair it may time out or connect. Either means the filter is gone.
    // A deny can linger for a moment while WFP drops the persistent filters.
    let mut released = probe(doc);
    for _ in 0..8 {
        let cleared = match &released {
            Ok(()) => true,
            Err(err) => !is_denied(err),
        };
        if cleared {
            break;
        }
        thread::sleep(Duration::from_millis(400));
        released = probe(doc);
    }
    if let Err(err) = &released {
        assert!(
            !is_denied(err),
            "repair left the documentation address denied (kind {:?} raw {:?})",
            err.kind(),
            err.raw_os_error()
        );
    }
    eprintln!("LEAK_REPAIRED");
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
        allowed_ips: vec!["10.66.0.1/32".into(), "10.66.0.50/32".into()],
        peer_id: "peer-lock".into(),
        preshared_key: String::new(),
        stealth_endpoint: String::new(),
        stealth_path_prefix: String::new(),
        endpoint_lan: String::new(),
        endpoint_wan: String::new(),
    };
    let mut request = Request::new(2, OP_CONNECT);
    request.config = Some(config);
    request.full_lock = Some(true);
    attach_management(&mut request);
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

fn outside_oracle() -> SocketAddr {
    let candidates = [
        SocketAddr::from((Ipv4Addr::new(1, 1, 1, 1), 9)),
        SocketAddr::from((Ipv4Addr::new(8, 8, 8, 8), 9)),
        SocketAddr::from((Ipv4Addr::new(9, 9, 9, 9), 9)),
        SocketAddr::from((Ipv4Addr::new(203, 0, 113, 10), 9)),
    ];
    for addr in candidates {
        match probe(addr) {
            Err(err) if is_denied(&err) => continue,
            other => {
                eprintln!("BASELINE_ORACLE denied=false reachable={}", other.is_ok());
                return addr;
            }
        }
    }
    panic!("every outside address was already denied before the lock");
}

fn is_denied(err: &std::io::Error) -> bool {
    let raw = err.raw_os_error();
    err.kind() == std::io::ErrorKind::PermissionDenied || raw == Some(5) || raw == Some(10013)
}

fn assert_blocked(addr: SocketAddr, label: &str) {
    match probe(addr) {
        Ok(()) => panic!("{label}: connection was allowed"),
        Err(err) => {
            let raw = err.raw_os_error();
            assert!(
                is_denied(&err),
                "{label}: filter did not deny it (kind {:?} raw {raw:?})",
                err.kind()
            );
        }
    }
}

fn attach_management(request: &mut Request) {
    let snap = management_snapshot();
    request.management_v4 = snap.v4.iter().map(|addr| addr.to_string()).collect();
    request.management_v6 = snap.v6.iter().map(|addr| addr.to_string()).collect();
    // TCP 80/443 stays open for the runner. Leak probes use other ports.
    request.permit_web = Some(true);
    eprintln!(
        "MANAGEMENT_PERMIT v4={} v6={}",
        request.management_v4.len(),
        request.management_v6.len()
    );
    assert!(
        !request.management_v4.is_empty(),
        "no management hosts; refusing to arm a lock that would cut off the runner"
    );
}

fn finish_within(limit: Duration, body: impl FnOnce() + Send + 'static) {
    let (tx, rx) = std::sync::mpsc::channel();
    thread::spawn(move || {
        let _ = tx.send(std::panic::catch_unwind(std::panic::AssertUnwindSafe(body)));
    });
    match rx.recv_timeout(limit) {
        Ok(Ok(())) => {}
        Ok(Err(payload)) => std::panic::resume_unwind(payload),
        Err(_) => panic!("test exceeded {}s", limit.as_secs()),
    }
}

fn output_bounded(cmd: &str, args: &[&str], limit: Duration) -> std::process::Output {
    let os: Vec<std::ffi::OsString> = args.iter().map(|arg| (*arg).into()).collect();
    output_bounded_os(std::path::Path::new(cmd), &os, limit)
}

fn output_bounded_os(cmd: &std::path::Path, args: &[std::ffi::OsString], limit: Duration) -> std::process::Output {
    let child = Command::new(cmd)
        .args(args)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap_or_else(|_| panic!("spawn {}", cmd.display()));
    wait_output(child, limit, "command")
}

fn wait_output(mut child: std::process::Child, limit: Duration, label: &str) -> std::process::Output {
    let start = Instant::now();
    loop {
        match child.try_wait() {
            Ok(Some(status)) => {
                let mut stdout = Vec::new();
                let mut stderr = Vec::new();
                if let Some(mut out) = child.stdout.take() {
                    let _ = out.read_to_end(&mut stdout);
                }
                if let Some(mut err) = child.stderr.take() {
                    let _ = err.read_to_end(&mut stderr);
                }
                return std::process::Output { status, stdout, stderr };
            }
            Ok(None) if start.elapsed() > limit => {
                let _ = child.kill();
                let _ = child.wait();
                panic!("{label} exceeded {}s", limit.as_secs());
            }
            Ok(None) => thread::sleep(Duration::from_millis(50)),
            Err(_) => panic!("{label} disappeared"),
        }
    }
}

fn quiet_bounded(cmd: impl AsRef<std::ffi::OsStr>, args: &[&str], limit: Duration) {
    let Ok(mut child) = Command::new(cmd).args(args).stdout(Stdio::null()).stderr(Stdio::null()).spawn() else {
        return;
    };
    let start = Instant::now();
    loop {
        match child.try_wait() {
            Ok(Some(_)) => return,
            Ok(None) if start.elapsed() > limit => {
                let _ = child.kill();
                let _ = child.wait();
                return;
            }
            Ok(None) => thread::sleep(Duration::from_millis(50)),
            Err(_) => return,
        }
    }
}

fn wide(value: &str) -> Vec<u16> {
    use std::os::windows::ffi::OsStrExt;
    std::ffi::OsStr::new(value).encode_wide().chain(std::iter::once(0)).collect()
}
