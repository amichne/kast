struct PlanPaths {
    directory: PathBuf,
    plan: PathBuf,
    content: PathBuf,
    preview_content: PathBuf,
}

impl PlanPaths {
    fn new(plan_id: Uuid) -> Self {
        let directory = manifest::default_install_root().join("state/agent-plans");
        let id = plan_id.hyphenated();
        Self {
            plan: directory.join(format!("{id}.json")),
            content: directory.join(format!("{id}.content")),
            preview_content: directory.join(format!(".{id}.preview-{}.content", Uuid::new_v4())),
            directory,
        }
    }
}

fn canonical_workspace_root() -> Result<PathBuf> {
    let root = config::resolve_workspace_root(None)?;
    root.canonicalize().map_err(|error| {
        CliError::new(
            "WORKSPACE_ROOT_UNAVAILABLE",
            format!(
                "The workspace root {} could not be canonicalized: {error}",
                root.display()
            ),
        )
    })
}

fn parse_plan_id(raw: &str) -> Result<Uuid> {
    let plan_id = Uuid::parse_str(raw)
        .ok()
        .filter(|id| id.get_version() == Some(Version::Random))
        .filter(|id| id.hyphenated().to_string() == raw)
        .ok_or_else(|| {
            CliError::new(
                "CLI_USAGE",
                "Plan ids must be canonical lowercase version-4 UUIDs returned by `kast change`.",
            )
        })?;
    Ok(plan_id)
}

fn validate_plan(plan: &StoredPlan, expected_id: Uuid) -> Result<()> {
    if plan.schema_version != PLAN_SCHEMA_VERSION {
        return Err(CliError::new(
            "KAST_PLAN_VERSION_UNSUPPORTED",
            format!(
                "Plan {} uses unsupported private schema version {}.",
                plan.plan_id, plan.schema_version
            ),
        ));
    }
    if plan.plan_id != expected_id {
        return Err(CliError::new(
            "KAST_PLAN_INVALID",
            "The stored change plan identity does not match its requested id.",
        ));
    }
    if plan.operation.requires_content() != plan.content_sha256.is_some() {
        return Err(CliError::new(
            "KAST_PLAN_INVALID",
            "The stored change plan has inconsistent content evidence.",
        ));
    }
    Ok(())
}

fn read_stdin() -> Result<Vec<u8>> {
    let mut stdin = std::io::stdin();
    if stdin.is_terminal() {
        return Err(CliError::new(
            "CLI_USAGE",
            "Pipe the Kotlin content to stdin.",
        ));
    }
    let mut content = Vec::new();
    stdin.read_to_end(&mut content)?;
    if content.is_empty() {
        return Err(CliError::new(
            "CLI_USAGE",
            "Piped Kotlin content must not be empty.",
        ));
    }
    Ok(content)
}

fn ensure_private_directory(path: &Path) -> Result<()> {
    fs::create_dir_all(path)?;
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        return Err(CliError::new(
            "KAST_PLAN_STORE_INVALID",
            format!(
                "The private plan store {} is not a directory.",
                path.display()
            ),
        ));
    }
    set_mode(path, 0o700)?;
    Ok(())
}

fn write_plan(path: &Path, plan: &StoredPlan) -> Result<()> {
    let mut encoded = serde_json::to_vec(plan)?;
    encoded.push(b'\n');
    let temporary = path.with_extension(format!("json.tmp-{}", Uuid::new_v4()));
    write_private_file(&temporary, &encoded)?;
    if let Err(error) = rename_private_file(&temporary, path) {
        remove_if_exists(&temporary);
        return Err(error);
    }
    Ok(())
}

fn write_private_file(path: &Path, bytes: &[u8]) -> Result<()> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let result = (|| {
        let mut file = options.open(path)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        set_mode(path, 0o600)
    })();
    if result.is_err() {
        remove_if_exists(path);
    }
    result
}

fn rename_private_file(from: &Path, to: &Path) -> Result<()> {
    if to.exists() {
        return Err(CliError::new(
            "KAST_PLAN_ALREADY_EXISTS",
            format!(
                "Refusing to overwrite private plan data at {}.",
                to.display()
            ),
        ));
    }
    fs::rename(from, to)?;
    sync_directory(
        to.parent()
            .expect("private plan files always have a parent directory"),
    )
}

fn read_private_file(path: &Path, missing_code: &'static str) -> Result<Vec<u8>> {
    let metadata = fs::symlink_metadata(path).map_err(|error| {
        CliError::new(
            missing_code,
            format!(
                "Private plan data {} is unavailable: {error}",
                path.display()
            ),
        )
    })?;
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err(CliError::new(
            "KAST_PLAN_INVALID",
            format!(
                "Private plan data {} is not a regular file.",
                path.display()
            ),
        ));
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if metadata.permissions().mode() & 0o077 != 0 {
            return Err(CliError::new(
                "KAST_PLAN_PERMISSIONS_INVALID",
                format!(
                    "Private plan data {} is readable outside its owner.",
                    path.display()
                ),
            ));
        }
    }
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc::O_NOFOLLOW);
    }
    let mut file = options.open(path)?;
    let opened = file.metadata()?;
    if !opened.is_file() {
        return Err(CliError::new(
            "KAST_PLAN_INVALID",
            format!(
                "Private plan data {} changed while opening.",
                path.display()
            ),
        ));
    }
    let mut bytes = Vec::new();
    file.read_to_end(&mut bytes)?;
    Ok(bytes)
}

fn set_mode(path: &Path, mode: u32) -> Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(mode))?;
    }
    #[cfg(not(unix))]
    let _ = (path, mode);
    Ok(())
}

fn sync_directory(path: &Path) -> Result<()> {
    File::open(path)?.sync_all()?;
    Ok(())
}

fn projected_result(envelope: &Value) -> Result<&Value> {
    envelope.get("result").ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "The validated change completed without a result.",
        )
    })
}

fn public_plan(preview: &Value) -> Value {
    let plan = preview.get("plan").cloned().unwrap_or_else(|| json!({}));
    strip_private_fields(plan)
}

fn strip_private_fields(value: Value) -> Value {
    match value {
        Value::Object(fields) => Value::Object(
            fields
                .into_iter()
                .filter_map(|(key, value)| {
                    (!matches!(
                        key.as_str(),
                        "contentFile"
                            | "help"
                            | "method"
                            | "mutates"
                            | "ok"
                            | "schemaVersion"
                            | "applyRequired"
                            | "type"
                    ))
                    .then(|| (key, strip_private_fields(value)))
                })
                .collect(),
        ),
        Value::Array(items) => Value::Array(items.into_iter().map(strip_private_fields).collect()),
        scalar => scalar,
    }
}

fn remove_if_exists(path: &Path) {
    let _ = fs::remove_file(path);
}
