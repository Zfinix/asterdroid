//! Proof that the Rust agent can reach the APK's accessibility server on-device.
//! Abstract unix socket, so there is no path on disk and no permissions to get wrong.

mod server;
mod socket;

/// The agent calls this through its run_command tool, which waits for exit, so
/// serve re-execs itself in the background and the caller gets the URL at once.
fn serve_detached(port: u16, bind: &str) {
    let addr = format!("{bind}:{port}");
    if std::net::TcpStream::connect_timeout(
        &addr.parse().expect("addr"),
        std::time::Duration::from_millis(300),
    )
    .is_ok()
    {
        println!("mirror already running on http://{addr}");
        return;
    }
    let exe = std::env::current_exe().expect("current_exe");
    std::process::Command::new(exe)
        .args(["serve", &port.to_string(), "--bind", bind, "--foreground"])
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .expect("spawn mirror");
    println!("mirror starting on http://{addr}");
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.first().map(String::as_str) == Some("serve") {
        let rest: Vec<String> = args.iter().skip(1).cloned().collect();
        let foreground = rest.iter().any(|a| a == "--foreground");
        let port = rest
            .iter()
            .find_map(|a| a.parse::<u16>().ok())
            .unwrap_or(server::DEFAULT_PORT);
        let bind = rest
            .iter()
            .position(|a| a == "--bind")
            .and_then(|i| rest.get(i + 1))
            .map(String::as_str)
            .unwrap_or(server::DEFAULT_BIND);
        if foreground {
            server::run(port, bind);
        } else {
            serve_detached(port, bind);
        }
        return;
    }
    if args.is_empty() {
        println!(
            "usage: asterctl <verb> [args] | asterctl serve\n\
             verbs: map find tap do press swipe drag slide hold pinch text type clear scroll key volume media wait later ocr shot marks notes alerts apps \
             open restart install settings dial sms url web place search alarm timer event wallpaper \
             quicksettings notifications emergency pace capture help\n\
             serve: the mirror, http://127.0.0.1:7070 [port] [--bind ip], screen out and touches in\n\
             The full reference is the android-use skill already in your prompt."
        );
        std::process::exit(2);
    }
    let cmd = args.join(" ");
    match socket::one_timed(&cmd) {
        Ok((_, t, reply)) => {
            print!("{reply}");
            eprintln!("({:.0}ms)", t.as_secs_f64() * 1000.0);
        }
        Err(e) => {
            println!("error: cannot reach the accessibility service: {e}");
            std::process::exit(1);
        }
    }
}
