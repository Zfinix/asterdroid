//! Connection to the accessibility service's abstract unix socket. One line in,
//! the reply read to EOF, one connection per command.

use std::io::{Read, Write};
use std::os::unix::io::FromRawFd;
use std::os::unix::net::UnixStream;

const NAME: &str = "aster-eyes";

pub fn connect() -> std::io::Result<UnixStream> {
    unsafe {
        let fd = libc::socket(libc::AF_UNIX, libc::SOCK_STREAM, 0);
        if fd < 0 {
            return Err(std::io::Error::last_os_error());
        }
        let mut addr: libc::sockaddr_un = std::mem::zeroed();
        addr.sun_family = libc::AF_UNIX as libc::sa_family_t;
        // Leading NUL selects the abstract namespace.
        for (i, b) in NAME.bytes().enumerate() {
            addr.sun_path[i + 1] = b as libc::c_char;
        }
        let len = (std::mem::size_of::<libc::sa_family_t>() + 1 + NAME.len()) as libc::socklen_t;
        if libc::connect(fd, &addr as *const _ as *const libc::sockaddr, len) < 0 {
            let e = std::io::Error::last_os_error();
            libc::close(fd);
            return Err(e);
        }
        Ok(UnixStream::from_raw_fd(fd))
    }
}

/// A command whose reply nobody is waiting for. The mirror's touches are sent
/// this way: reading the reply would put the round trip, and whatever the
/// service does before answering, in front of the next finger position.
pub fn fire(cmd: &str) -> std::io::Result<()> {
    let mut s = connect()?;
    s.write_all(format!("{cmd}\n").as_bytes())?;
    s.flush()
}

pub fn one(cmd: &str) -> std::io::Result<String> {
    Ok(one_timed(cmd)?.2)
}

pub fn one_timed(
    cmd: &str,
) -> std::io::Result<(std::time::Duration, std::time::Duration, String)> {
    let t0 = std::time::Instant::now();
    let mut s = connect()?;
    let connected = t0.elapsed();
    s.write_all(format!("{cmd}\n").as_bytes())?;
    s.flush()?;
    let mut reply = String::new();
    s.read_to_string(&mut reply)?;
    Ok((connected, t0.elapsed(), reply))
}