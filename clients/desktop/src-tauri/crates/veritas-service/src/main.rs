fn main() {
    #[cfg(windows)]
    {
        let code = veritas_service::console_main();
        std::process::exit(code);
    }
    #[cfg(not(windows))]
    {
        eprintln!("veritas-service runs on Windows only");
    }
}
