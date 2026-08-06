#[cfg(target_os = "linux")]
fn observe_linux_process(pid: u64) -> Result<Option<ObservedProcess>> {
    let Some(identity) = linux_process_identity(pid)? else {
        return Ok(None);
    };
    let command_bytes = fs::read(format!("/proc/{pid}/cmdline"))
        .map_err(|error| process_io_error(pid, "cmdline", error))?;
    let command = parse_nul_command(&command_bytes)?;
    let confirmed = linux_process_identity(pid)?.ok_or_else(|| {
        CliError::new(
            "RUNTIME_PROCESS_IDENTITY_CHANGED",
            "Linux process exited while ownership evidence was collected.",
        )
    })?;
    if confirmed != identity {
        return Err(CliError::new(
            "RUNTIME_PROCESS_IDENTITY_CHANGED",
            "Linux PID identity changed while ownership evidence was collected.",
        ));
    }
    Ok(Some(ObservedProcess { identity, command }))
}

#[cfg(target_os = "linux")]
fn linux_process_identity(pid: u64) -> Result<Option<ManagedProcessIdentity>> {
    let directory = PathBuf::from(format!("/proc/{pid}"));
    let stat = match fs::read_to_string(directory.join("stat")) {
        Ok(stat) => stat,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(process_io_error(pid, "stat", error)),
    };
    let end = stat
        .rfind(") ")
        .ok_or_else(|| process_error("Linux process stat is malformed."))?;
    let fields = stat[end + 2..].split_whitespace().collect::<Vec<_>>();
    let start_ticks = fields
        .get(19)
        .ok_or_else(|| process_error("Linux process stat omits start time."))?
        .parse::<u64>()
        .map_err(|_| process_error("Linux process start time is invalid."))?;
    let status = fs::read_to_string(directory.join("status"))
        .map_err(|error| process_io_error(pid, "status", error))?;
    let owner_uid = status
        .lines()
        .find_map(|line| line.strip_prefix("Uid:"))
        .and_then(|value| value.split_whitespace().nth(1))
        .and_then(|value| value.parse::<u64>().ok())
        .ok_or_else(|| process_error("Linux process effective UID is unavailable."))?;
    let boot_id = fs::read_to_string("/proc/sys/kernel/random/boot_id")
        .map_err(|error| process_io_error(pid, "boot identity", error))?
        .trim()
        .to_string();
    let boot_seconds = fs::read_to_string("/proc/stat")
        .map_err(|error| process_io_error(pid, "boot time", error))?
        .lines()
        .find_map(|line| line.strip_prefix("btime "))
        .and_then(|value| value.parse::<u64>().ok())
        .ok_or_else(|| process_error("Linux boot time is unavailable."))?;
    let ticks_per_second = unsafe { libc::sysconf(libc::_SC_CLK_TCK) };
    if ticks_per_second <= 0 {
        return Err(process_error("Linux clock tick rate is unavailable."));
    }
    let ticks_per_second = u64::try_from(ticks_per_second)
        .map_err(|_| process_error("Linux clock tick rate is invalid."))?;
    Ok(Some(ManagedProcessIdentity {
        pid,
        start_key: format!("linux:{boot_id}:{start_ticks}"),
        start_epoch_millis: boot_seconds
            .saturating_mul(1_000)
            .saturating_add(start_ticks.saturating_mul(1_000) / ticks_per_second),
        owner_uid,
    }))
}

#[cfg(target_os = "macos")]
#[repr(C)]
struct ProcBsdInfo {
    flags: u32,
    status: u32,
    xstatus: u32,
    pid: u32,
    ppid: u32,
    uid: u32,
    gid: u32,
    ruid: u32,
    rgid: u32,
    svuid: u32,
    svgid: u32,
    reserved: u32,
    command: [u8; 16],
    name: [u8; 32],
    nfiles: u32,
    pgid: u32,
    pjobc: u32,
    controlling_tty: u32,
    foreground_pgid: u32,
    nice: i32,
    start_seconds: u64,
    start_microseconds: u64,
}

#[cfg(target_os = "macos")]
#[link(name = "proc")]
unsafe extern "C" {
    fn proc_pidinfo(
        pid: libc::c_int,
        flavor: libc::c_int,
        arg: u64,
        buffer: *mut libc::c_void,
        buffer_size: libc::c_int,
    ) -> libc::c_int;
}

#[cfg(target_os = "macos")]
fn observe_macos_process(pid: u64) -> Result<Option<ObservedProcess>> {
    let Some(identity) = macos_process_identity(pid)? else {
        return Ok(None);
    };
    let command = macos_process_arguments(pid as libc::c_int)?;
    let confirmed = macos_process_identity(pid)?.ok_or_else(|| {
        CliError::new(
            "RUNTIME_PROCESS_IDENTITY_CHANGED",
            "macOS process exited while ownership evidence was collected.",
        )
    })?;
    if confirmed != identity {
        return Err(CliError::new(
            "RUNTIME_PROCESS_IDENTITY_CHANGED",
            "macOS PID identity changed while ownership evidence was collected.",
        ));
    }
    Ok(Some(ObservedProcess { identity, command }))
}

#[cfg(target_os = "macos")]
fn macos_process_identity(pid: u64) -> Result<Option<ManagedProcessIdentity>> {
    const PROC_PIDTBSDINFO: libc::c_int = 3;
    let mut info = unsafe { std::mem::zeroed::<ProcBsdInfo>() };
    let size = libc::c_int::try_from(std::mem::size_of::<ProcBsdInfo>())
        .map_err(|_| process_error("macOS process evidence structure is too large."))?;
    let read = unsafe {
        proc_pidinfo(
            pid as libc::c_int,
            PROC_PIDTBSDINFO,
            0,
            (&mut info as *mut ProcBsdInfo).cast(),
            size,
        )
    };
    if read == 0 {
        let error = std::io::Error::last_os_error();
        return if matches!(error.raw_os_error(), Some(libc::ESRCH)) {
            Ok(None)
        } else {
            Err(process_io_error(pid, "BSD process info", error))
        };
    }
    if read != size || u64::from(info.pid) != pid {
        return Err(process_error("macOS process evidence is incomplete."));
    }
    Ok(Some(ManagedProcessIdentity {
        pid,
        start_key: format!("macos:{}:{}", info.start_seconds, info.start_microseconds),
        start_epoch_millis: info
            .start_seconds
            .saturating_mul(1_000)
            .saturating_add(info.start_microseconds / 1_000),
        owner_uid: u64::from(info.uid),
    }))
}

#[cfg(target_os = "macos")]
fn macos_process_arguments(pid: libc::c_int) -> Result<Vec<String>> {
    const CTL_KERN: libc::c_int = 1;
    const KERN_ARGMAX: libc::c_int = 8;
    const KERN_PROCARGS2: libc::c_int = 49;
    let mut argmax: libc::c_int = 0;
    let mut size = std::mem::size_of::<libc::c_int>();
    let mut argmax_mib = [CTL_KERN, KERN_ARGMAX];
    if unsafe {
        libc::sysctl(
            argmax_mib.as_mut_ptr(),
            2,
            (&mut argmax as *mut libc::c_int).cast(),
            &mut size,
            std::ptr::null_mut(),
            0,
        )
    } != 0
        || argmax <= 0
    {
        return Err(process_error("macOS process argument limit is unavailable."));
    }
    let mut buffer = vec![0_u8; argmax as usize];
    let mut read = buffer.len();
    let mut args_mib = [CTL_KERN, KERN_PROCARGS2, pid];
    if unsafe {
        libc::sysctl(
            args_mib.as_mut_ptr(),
            3,
            buffer.as_mut_ptr().cast(),
            &mut read,
            std::ptr::null_mut(),
            0,
        )
    } != 0
    {
        return Err(process_io_error(
            pid as u64,
            "command arguments",
            std::io::Error::last_os_error(),
        ));
    }
    buffer.truncate(read);
    parse_macos_arguments(&buffer)
}

#[cfg(target_os = "macos")]
fn parse_macos_arguments(bytes: &[u8]) -> Result<Vec<String>> {
    if bytes.len() < std::mem::size_of::<libc::c_int>() {
        return Err(process_error("macOS process arguments are truncated."));
    }
    let count_bytes: [u8; 4] = bytes[..4]
        .try_into()
        .map_err(|_| process_error("macOS process argument count is truncated."))?;
    let count = i32::from_ne_bytes(count_bytes);
    if count <= 0 {
        return Err(process_error("macOS process argument count is invalid."));
    }
    let mut cursor = skip_c_string(bytes, 4)?;
    while bytes.get(cursor) == Some(&0) {
        cursor += 1;
    }
    let mut arguments = Vec::with_capacity(count as usize);
    for _ in 0..count {
        let end = bytes[cursor..]
            .iter()
            .position(|byte| *byte == 0)
            .map(|offset| cursor + offset)
            .ok_or_else(|| process_error("macOS process argument is unterminated."))?;
        arguments.push(
            String::from_utf8(bytes[cursor..end].to_vec())
                .map_err(|_| process_error("macOS process argument is not UTF-8."))?,
        );
        cursor = end + 1;
    }
    Ok(arguments)
}

#[cfg(target_os = "macos")]
fn skip_c_string(bytes: &[u8], cursor: usize) -> Result<usize> {
    bytes[cursor..]
        .iter()
        .position(|byte| *byte == 0)
        .map(|offset| cursor + offset + 1)
        .ok_or_else(|| process_error("macOS executable path is unterminated."))
}

#[cfg(target_os = "linux")]
fn parse_nul_command(bytes: &[u8]) -> Result<Vec<String>> {
    if bytes.last() != Some(&0) {
        return Err(process_error("Linux process command line is truncated."));
    }
    let arguments = bytes[..bytes.len().saturating_sub(1)]
        .split(|byte| *byte == 0)
        .map(|value| {
            String::from_utf8(value.to_vec())
                .map_err(|_| process_error("Linux process argument is not UTF-8."))
        })
        .collect::<Result<Vec<_>>>()?;
    if arguments.is_empty() || arguments[0].is_empty() {
        Err(process_error("Linux process command line is empty."))
    } else {
        Ok(arguments)
    }
}
