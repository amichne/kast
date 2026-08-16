#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct IndexerBootstrapToken(uuid::Uuid);

impl IndexerBootstrapToken {
    fn new() -> Self {
        Self(uuid::Uuid::new_v4())
    }

    fn argument(self) -> String {
        format!("--bootstrap-token={}", self.0)
    }

    fn receipt_file(self, layout: &IndexerProjectLayout) -> PathBuf {
        layout.bootstrap_directory.join(format!("{}.json", self.0))
    }
}

#[derive(Debug, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct IndexerBootstrapReceipt {
    schema_version: u32,
    token: uuid::Uuid,
    pid: u32,
    canonical_workspace_root: PathBuf,
    canonical_storage_root: PathBuf,
}

fn wait_for_indexer_bootstrap(
    child: &mut std::process::Child,
    layout: &IndexerProjectLayout,
    token: IndexerBootstrapToken,
    deadline: crate::runtime::RuntimeStartDeadline,
) -> Result<()> {
    let receipt_file = token.receipt_file(layout);
    loop {
        if receipt_file.is_file() {
            let result = validate_indexer_bootstrap_receipt(child, layout, token, &receipt_file);
            let _ = fs::remove_file(&receipt_file);
            if let Err(error) = result {
                terminate_spawned_indexer(child);
                return Err(error);
            }
            return Ok(());
        }
        if let Some(status) = child.try_wait()? {
            let _ = fs::remove_file(&receipt_file);
            return Err(CliError::new(
                "INDEXER_BOOTSTRAP_FAILED",
                format!(
                    "The indexer process {} exited with {status} before bootstrap admission.",
                    child.id(),
                ),
            ));
        }
        if deadline.is_elapsed() {
            terminate_spawned_indexer(child);
            let _ = fs::remove_file(&receipt_file);
            return Err(CliError::new(
                "INDEXER_BOOTSTRAP_TIMEOUT",
                format!(
                    "Timed out waiting for lease-owned indexer bootstrap for {}.",
                    layout.identity.workspace_root().display(),
                ),
            ));
        }
        std::thread::sleep(std::time::Duration::from_millis(20));
    }
}

fn validate_indexer_bootstrap_receipt(
    child: &std::process::Child,
    layout: &IndexerProjectLayout,
    token: IndexerBootstrapToken,
    receipt_file: &Path,
) -> Result<()> {
    reject_symbolic_link(receipt_file)?;
    let receipt: IndexerBootstrapReceipt = serde_json::from_slice(&fs::read(receipt_file)?)?;
    if receipt.schema_version != 1
        || receipt.token != token.0
        || receipt.pid != child.id()
        || receipt.canonical_workspace_root != layout.identity.workspace_root()
        || receipt.canonical_storage_root != layout.identity.storage_root()
    {
        return Err(CliError::new(
            "INDEXER_BOOTSTRAP_RECEIPT_INVALID",
            format!(
                "Indexer bootstrap receipt {} does not match the spawned process and canonical storage identity.",
                receipt_file.display(),
            ),
        ));
    }
    Ok(())
}

fn terminate_spawned_indexer(child: &mut std::process::Child) {
    let _ = child.kill();
    let _ = child.wait();
}
