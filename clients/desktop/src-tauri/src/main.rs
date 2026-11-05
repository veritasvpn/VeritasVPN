#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    #[cfg(windows)]
    if std::env::args().any(|arg| arg == "--veritas-pipe-check") {
        std::process::exit(veritasvpn_desktop_lib::pipe_check());
    }
    veritasvpn_desktop_lib::run()
}
