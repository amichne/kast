use super::*;
use uuid::Uuid;

#[derive(Debug, Clone, Default)]
pub(in crate::runtime::indexer_authority) struct RegisteredRuntimeIdentities(
    std::collections::HashSet<Uuid>,
);

impl RegisteredRuntimeIdentities {
    /// Proof transition: `Iterator<Uuid> -> RegisteredRuntimeIdentities`.
    pub(in crate::runtime::indexer_authority) fn derive(
        ids: impl IntoIterator<Item = Uuid>,
    ) -> Self {
        Self(ids.into_iter().collect())
    }

    /// Proof transition: `(RegisteredRuntimeIdentities, Uuid) -> RegisteredRuntimeIdentities`.
    pub(in crate::runtime::indexer_authority) fn record(&mut self, id: Uuid) {
        self.0.insert(id);
    }

    fn contains(&self, id: &Uuid) -> bool {
        self.0.contains(id)
    }

    pub(in crate::runtime::indexer_authority) fn contains_descriptor_id(&self, raw: &str) -> bool {
        Uuid::parse_str(raw).is_ok_and(|id| self.contains(&id))
    }
}

#[derive(Debug, Clone)]
pub(in crate::runtime::indexer_authority) struct UnregisteredRuntimeProcess {
    process: super::super::process::ObservedProcess,
    runtime_instance_id: Option<Uuid>,
}

impl UnregisteredRuntimeProcess {
    pub(in crate::runtime::indexer_authority) fn evidence(&self) -> String {
        self.runtime_instance_id.map_or_else(
            || {
                format!(
                    "PID {} with malformed runtime identity",
                    self.process.identity.pid
                )
            },
            |id| format!("runtime {id} at PID {}", self.process.identity.pid),
        )
    }

    /// Refines an exact-workspace process candidate using retained registration identity evidence.
    fn has_persisted_identity(&self, persisted_runtime_ids: &RegisteredRuntimeIdentities) -> bool {
        self.runtime_instance_id
            .is_some_and(|id| persisted_runtime_ids.contains(&id))
    }
}

pub(in crate::runtime::indexer_authority) fn discover_unregistered(
    workspace_root: &Path,
    persisted_runtime_ids: &RegisteredRuntimeIdentities,
) -> Result<Vec<UnregisteredRuntimeProcess>> {
    let owner_uid = u64::from(unsafe { libc::geteuid() });
    let mut matches = Vec::new();
    for pid in process_ids(owner_uid)? {
        let Some(process) = super::super::process::observe_owned_process(pid, owner_uid)? else {
            continue;
        };
        if let Some(process) = indexer_process_for_workspace(process, workspace_root)
            && !process.has_persisted_identity(persisted_runtime_ids)
        {
            matches.push(process);
        }
    }
    matches.sort_by_key(|candidate| candidate.process.identity.pid);
    Ok(matches)
}

fn indexer_process_for_workspace(
    process: super::super::process::ObservedProcess,
    workspace_root: &Path,
) -> Option<UnregisteredRuntimeProcess> {
    let is_indexer = process.has_argument("kast-indexer")
        || process.has_argument("io.github.amichne.kast.indexer.KastIndexerMainKt");
    if !is_indexer {
        return None;
    }
    let claims_root = process
        .command
        .iter()
        .filter_map(|argument| argument.to_str())
        .any(|argument| {
            argument
                .strip_prefix("--workspace-root=")
                .map(PathBuf::from)
                .filter(|path| path.is_absolute())
                .is_some_and(|path| config::normalize(path) == workspace_root)
        });
    if !claims_root {
        return None;
    }
    let runtime_instance_id = process
        .command
        .iter()
        .filter_map(|argument| argument.to_str())
        .filter_map(|argument| argument.strip_prefix("--runtime-instance-id="))
        .next()
        .and_then(|value| Uuid::parse_str(value).ok());
    Some(UnregisteredRuntimeProcess {
        process,
        runtime_instance_id,
    })
}

#[cfg(target_os = "linux")]
fn process_ids(_owner_uid: u64) -> Result<Vec<u64>> {
    let mut pids = fs::read_dir("/proc")?
        .filter_map(std::result::Result::ok)
        .filter_map(|entry| entry.file_name().to_str()?.parse::<u64>().ok())
        .collect::<Vec<_>>();
    pids.sort_unstable();
    pids.dedup();
    Ok(pids)
}

#[cfg(target_os = "macos")]
fn process_ids(owner_uid: u64) -> Result<Vec<u64>> {
    const PROC_UID_ONLY: u32 = 4;
    #[link(name = "proc")]
    unsafe extern "C" {
        fn proc_listpids(
            kind: u32,
            type_info: u32,
            buffer: *mut libc::c_void,
            buffer_size: libc::c_int,
        ) -> libc::c_int;
    }

    let owner_uid = u32::try_from(owner_uid)
        .map_err(|_| manager_error("macOS process owner identity is invalid."))?;
    let required_bytes =
        unsafe { proc_listpids(PROC_UID_ONLY, owner_uid, std::ptr::null_mut(), 0) };
    if required_bytes <= 0 {
        return Err(manager_error(
            "Cannot enumerate macOS processes for runtime ownership.",
        ));
    }
    let required_bytes = usize::try_from(required_bytes)
        .map_err(|_| manager_error("macOS process buffer size is invalid."))?;
    let capacity = required_bytes
        .div_ceil(std::mem::size_of::<i32>())
        .saturating_add(64);
    let mut pids = vec![0_i32; capacity];
    let bytes = pids
        .len()
        .checked_mul(std::mem::size_of::<i32>())
        .and_then(|value| libc::c_int::try_from(value).ok())
        .ok_or_else(|| manager_error("macOS process buffer is too large."))?;
    let read = unsafe { proc_listpids(PROC_UID_ONLY, owner_uid, pids.as_mut_ptr().cast(), bytes) };
    if read < 0 {
        return Err(manager_error("Cannot read the macOS process list."));
    }
    let read_bytes = usize::try_from(read)
        .map_err(|_| manager_error("macOS process buffer size is invalid."))?;
    if read_bytes % std::mem::size_of::<i32>() != 0 {
        return Err(manager_error("macOS process list is truncated."));
    }
    let read = read_bytes / std::mem::size_of::<i32>();
    if read > pids.len() {
        return Err(manager_error(
            "macOS process enumeration exceeded its buffer.",
        ));
    }
    pids.truncate(read);
    let mut pids = pids
        .into_iter()
        .filter_map(|pid| u64::try_from(pid).ok())
        .filter(|pid| *pid > 0)
        .collect::<Vec<_>>();
    pids.sort_unstable();
    pids.dedup();
    Ok(pids)
}

#[cfg(not(any(target_os = "macos", target_os = "linux")))]
fn process_ids(_owner_uid: u64) -> Result<Vec<u64>> {
    Err(manager_error(
        "Runtime process discovery is unsupported on this platform.",
    ))
}
