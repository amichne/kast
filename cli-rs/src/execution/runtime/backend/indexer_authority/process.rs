use super::*;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(super) struct ManagedProcessIdentity {
    pub pid: u64,
    pub start_key: String,
    pub start_epoch_millis: u64,
    pub owner_uid: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct ObservedProcess {
    pub identity: ManagedProcessIdentity,
    pub command: Vec<String>,
}

pub(super) fn observe_process(pid: u64) -> Result<Option<ObservedProcess>> {
    if pid == 0 || pid > i32::MAX as u64 {
        return Ok(None);
    }
    #[cfg(target_os = "macos")]
    {
        observe_macos_process(pid)
    }
    #[cfg(target_os = "linux")]
    {
        observe_linux_process(pid)
    }
    #[cfg(not(any(target_os = "macos", target_os = "linux")))]
    {
        Err(CliError::new(
            "RUNTIME_PROCESS_EVIDENCE_UNAVAILABLE",
            "Kast runtime process evidence is unsupported on this platform.",
        ))
    }
}

pub(super) fn process_is_alive(pid: u64) -> Result<bool> {
    Ok(observe_process(pid)?.is_some())
}

pub(super) fn signal_process(expected: &ManagedProcessIdentity, force: bool) -> Result<()> {
    let pid = expected.pid;
    if pid == 0 || pid > i32::MAX as u64 {
        return Err(process_error("Runtime PID is outside the supported range."));
    }
    let current = observe_process(pid)?.ok_or_else(|| {
        CliError::new(
            "RUNTIME_OWNERSHIP_CHANGED",
            "Runtime process exited before it could be signaled.",
        )
    })?;
    if current.identity != *expected {
        return Err(CliError::new(
            "RUNTIME_OWNERSHIP_CHANGED",
            "Runtime PID was reused before it could be signaled.",
        ));
    }
    let signal = if force { libc::SIGKILL } else { libc::SIGTERM };
    #[cfg(target_os = "linux")]
    {
        return signal_linux_process(expected, signal);
    }
    #[cfg(not(target_os = "linux"))]
    if unsafe { libc::kill(pid as libc::pid_t, signal) } == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error().into())
    }
}

#[cfg(target_os = "linux")]
fn signal_linux_process(expected: &ManagedProcessIdentity, signal: libc::c_int) -> Result<()> {
    let pidfd = unsafe { libc::syscall(libc::SYS_pidfd_open, expected.pid as libc::pid_t, 0) };
    if pidfd == -1 {
        return Err(process_io_error(
            expected.pid,
            "pidfd ownership handle",
            std::io::Error::last_os_error(),
        ));
    }
    let pidfd = pidfd as libc::c_int;
    let result = (|| {
        let current = observe_process(expected.pid)?.ok_or_else(|| {
            CliError::new(
                "RUNTIME_OWNERSHIP_CHANGED",
                "Runtime process exited after its pidfd was opened.",
            )
        })?;
        if current.identity != *expected {
            return Err(CliError::new(
                "RUNTIME_OWNERSHIP_CHANGED",
                "Runtime PID was reused after its pidfd was opened.",
            ));
        }
        if unsafe {
            libc::syscall(
                libc::SYS_pidfd_send_signal,
                pidfd,
                signal,
                std::ptr::null::<libc::siginfo_t>(),
                0,
            )
        } == 0
        {
            Ok(())
        } else {
            Err(std::io::Error::last_os_error().into())
        }
    })();
    unsafe {
        libc::close(pidfd);
    }
    result
}

pub(super) fn wait_until_gone(
    expected: &ManagedProcessIdentity,
    timeout: Duration,
) -> Result<bool> {
    let deadline = Instant::now() + timeout;
    loop {
        match observe_process(expected.pid)? {
            None => return Ok(true),
            Some(process) if process.identity.start_key != expected.start_key => return Ok(true),
            Some(_) if Instant::now() >= deadline => return Ok(false),
            Some(_) => thread::sleep(Duration::from_millis(25)),
        }
    }
}

include!("ownership/process_platform.rs");

fn process_io_error(pid: u64, evidence: &str, error: std::io::Error) -> CliError {
    CliError::new(
        "RUNTIME_PROCESS_EVIDENCE_UNAVAILABLE",
        format!("Cannot read {evidence} for runtime PID {pid}: {error}"),
    )
}

fn process_error(message: &str) -> CliError {
    CliError::new("RUNTIME_PROCESS_EVIDENCE_UNAVAILABLE", message)
}
