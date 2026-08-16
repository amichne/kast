use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

const MARKER_WAIT: Duration = Duration::from_secs(3);
const EXIT_WAIT: Duration = Duration::from_secs(3);

pub(super) struct FixtureProcessGuard {
    pid_marker: PathBuf,
    identity: FixtureProcessIdentity,
    armed: bool,
}

impl FixtureProcessGuard {
    pub(super) fn new(pid_marker: &Path, java: &Path, workspace: &Path) -> Self {
        Self {
            pid_marker: pid_marker.to_path_buf(),
            identity: FixtureProcessIdentity {
                java: java.canonicalize().expect("canonical fake JBR executable"),
                workspace_argument: format!(
                    "--workspace-root={}",
                    workspace
                        .canonicalize()
                        .expect("canonical fixture workspace")
                        .display()
                ),
            },
            armed: true,
        }
    }

    pub(super) fn pid(&self) -> Option<u32> {
        let pid = read_pid_marker(&self.pid_marker)?;
        self.owns(pid).then_some(pid)
    }

    pub(super) fn terminate(mut self) {
        self.terminate_owned_process();
        self.armed = false;
    }

    fn terminate_owned_process(&self) {
        let Some(pid) = wait_for_pid_marker(&self.pid_marker) else {
            return;
        };
        if !self.signal_if_owned(pid, libc::SIGTERM) {
            return;
        }
        let deadline = Instant::now() + EXIT_WAIT;
        while self.owns(pid) && Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(25));
        }
        if self.owns(pid) {
            self.signal_if_owned(pid, libc::SIGKILL);
        }
    }

    fn signal_if_owned(&self, pid: u32, signal: libc::c_int) -> bool {
        if !self.owns(pid) {
            return false;
        }
        let Some(pid) = native_pid(pid) else {
            return false;
        };
        unsafe { libc::kill(pid, signal) == 0 }
    }

    fn owns(&self, pid: u32) -> bool {
        read_pid_marker(&self.pid_marker) == Some(pid)
            && process_arguments(pid).is_ok_and(|arguments| self.identity.matches(&arguments))
    }
}

impl Drop for FixtureProcessGuard {
    fn drop(&mut self) {
        if self.armed {
            self.terminate_owned_process();
        }
    }
}

struct FixtureProcessIdentity {
    java: PathBuf,
    workspace_argument: String,
}

impl FixtureProcessIdentity {
    fn matches(&self, arguments: &[String]) -> bool {
        let exact_java = self.java.to_string_lossy();
        arguments
            .iter()
            .any(|argument| argument == exact_java.as_ref())
            && arguments.iter().any(|argument| argument == "kast-indexer")
            && arguments.contains(&self.workspace_argument)
            && arguments
                .iter()
                .any(|argument| argument.starts_with("--storage-lease-fd="))
            && arguments
                .iter()
                .any(|argument| argument.starts_with("--bootstrap-token="))
    }
}

fn wait_for_pid_marker(path: &Path) -> Option<u32> {
    let deadline = Instant::now() + MARKER_WAIT;
    loop {
        if let Some(pid) = read_pid_marker(path) {
            return Some(pid);
        }
        if Instant::now() >= deadline {
            return None;
        }
        std::thread::sleep(Duration::from_millis(25));
    }
}

fn read_pid_marker(path: &Path) -> Option<u32> {
    let pid = std::fs::read_to_string(path).ok()?.trim().parse().ok()?;
    native_pid(pid).map(|_| pid)
}

fn process_arguments(pid: u32) -> std::io::Result<Vec<String>> {
    let pid = native_pid(pid)
        .ok_or_else(|| std::io::Error::new(std::io::ErrorKind::InvalidInput, "invalid PID"))?;
    let mut mib = [libc::CTL_KERN, libc::KERN_PROCARGS2, pid];
    let mut size = 0usize;
    if unsafe {
        libc::sysctl(
            mib.as_mut_ptr(),
            mib.len() as _,
            std::ptr::null_mut(),
            &mut size,
            std::ptr::null_mut(),
            0,
        )
    } == -1
    {
        return Err(std::io::Error::last_os_error());
    }
    let mut buffer = vec![0u8; size];
    if unsafe {
        libc::sysctl(
            mib.as_mut_ptr(),
            mib.len() as _,
            buffer.as_mut_ptr().cast(),
            &mut size,
            std::ptr::null_mut(),
            0,
        )
    } == -1
    {
        return Err(std::io::Error::last_os_error());
    }
    buffer.truncate(size);
    parse_process_arguments(&buffer)
}

fn native_pid(pid: u32) -> Option<libc::pid_t> {
    libc::pid_t::try_from(pid).ok().filter(|value| *value > 0)
}

fn parse_process_arguments(buffer: &[u8]) -> std::io::Result<Vec<String>> {
    let count_bytes: [u8; 4] = buffer
        .get(..4)
        .and_then(|value| value.try_into().ok())
        .ok_or_else(|| std::io::Error::new(std::io::ErrorKind::InvalidData, "missing argc"))?;
    let count = usize::try_from(i32::from_ne_bytes(count_bytes))
        .map_err(|_| std::io::Error::new(std::io::ErrorKind::InvalidData, "invalid argc"))?;
    let mut cursor = 4;
    while cursor < buffer.len() && buffer[cursor] != 0 {
        cursor += 1;
    }
    while cursor < buffer.len() && buffer[cursor] == 0 {
        cursor += 1;
    }
    let mut arguments = Vec::with_capacity(count);
    while cursor < buffer.len() && arguments.len() < count {
        let end = buffer[cursor..]
            .iter()
            .position(|byte| *byte == 0)
            .map_or(buffer.len(), |offset| cursor + offset);
        arguments.push(String::from_utf8_lossy(&buffer[cursor..end]).into_owned());
        cursor = end.saturating_add(1);
    }
    Ok(arguments)
}
