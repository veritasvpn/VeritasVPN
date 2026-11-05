//! Windows service that owns the WireGuardNT adapter.
//!
//! The window never touches the adapter. It sends versioned messages on the
//! named pipe. The kill switch is a persistent WFP lock. Reconnect keeps that
//! lock and never changes Direct or Stealth. Stealth itself is still refused.

#[cfg(windows)]
mod engine;
#[cfg(windows)]
mod net;
#[cfg(windows)]
mod pipe;
#[cfg(windows)]
mod svc;
#[cfg(windows)]
mod wfp;
mod reconnect;
mod wfp_plan;
#[cfg(windows)]
mod wg;

#[cfg(windows)]
pub use engine::ensure_api;
#[cfg(windows)]
pub use pipe::transact;
#[cfg(windows)]
pub use svc::{install, start, stop_service, uninstall};
#[cfg(windows)]
pub use net::{management_snapshot, ManagementSnapshot};
#[cfg(windows)]
pub use wg::{AdapterHandle, TestPeer};

#[cfg(windows)]
pub fn console_main() -> i32 {
    let mut args = std::env::args().skip(1);
    let cmd = args.next().unwrap_or_default();
    let result = match cmd.as_str() {
        "install" => install_from_args(args.collect()),
        "uninstall" => uninstall(),
        "start" => start(),
        "stop" => stop_service(),
        "repair" => repair(),
        "run" => svc::run_service(),
        "call" => call_from_args(args.collect()),
        _ => {
            eprintln!("usage: veritas-service install|uninstall|start|stop|repair|run|call");
            Err(2)
        }
    };
    match result {
        Ok(()) => 0,
        Err(code) => code,
    }
}

#[cfg(windows)]
fn install_from_args(args: Vec<String>) -> Result<(), i32> {
    let mut client = None;
    let mut dll = None;
    let mut iter = args.into_iter();
    while let Some(flag) = iter.next() {
        match flag.as_str() {
            "--client" => client = iter.next(),
            "--dll" => dll = iter.next(),
            _ => return Err(2),
        }
    }
    let client = client.ok_or(2)?;
    install(std::path::Path::new(&client), dll.as_deref().map(std::path::Path::new))
}

#[cfg(windows)]
fn repair() -> Result<(), i32> {
    engine::release_lock().map_err(|code| {
        engine::log_code("repair", code);
        3
    })
}

#[cfg(windows)]
fn call_from_args(args: Vec<String>) -> Result<(), i32> {
    let op = args.first().map(String::as_str).unwrap_or("");
    let mut request = veritas_ipc::Request::new(1, op);
    if op == veritas_ipc::OP_CONNECT {
        let path = args.get(1).ok_or(2)?;
        let text = std::fs::read_to_string(path).map_err(|_| 3)?;
        request.config = Some(serde_json::from_str(&text).map_err(|_| 3)?);
    } else if op == veritas_ipc::OP_SET_OPTIONS {
        request.local_network = Some(args.get(1).map(|v| v == "1").unwrap_or(false));
        request.stealth = Some(false);
    }
    match transact(&request, 20_000) {
        Ok(response) if response.ok => {
            println!("CALL_OK");
            if let Some(status) = response.status {
                println!(
                    "CALL_STATUS up={} rx={} tx={} handshake={}",
                    status.up as u8, status.rx, status.tx, status.handshake
                );
            }
            Ok(())
        }
        Ok(response) => {
            println!("CALL_CODE {}", response.code);
            Err(4)
        }
        Err(veritas_ipc::CODE_DENIED) => {
            println!("CALL_OPEN_DENIED");
            Err(5)
        }
        Err(_) => {
            println!("CALL_FAIL");
            Err(6)
        }
    }
}

#[cfg(not(windows))]
pub fn console_main() -> i32 {
    0
}
