#[derive(Debug, Clone)]
struct EmbeddedResourceFile {
    relative: PathBuf,
    contents: Vec<u8>,
    executable: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ResourceReplaceMode {
    WholeDirectory,
    ManagedFilesOnly,
}

const THIN_SKILL_OUTPUTS: &[&str] = &["SKILL.md"];
const THIN_INSTRUCTION_OUTPUTS: &[&str] = &["README.md", "cli.md", "tools.md", "lsp.md"];
const RETIRED_SKILL_PACKAGE_OUTPUTS: &[&str] = &["AGENTS.md", "fixtures", "references", "scripts"];
const RETIRED_INSTRUCTION_OUTPUTS: &[&str] = &["AGENTS.md", "rpc.md"];

#[derive(Debug)]
struct EmbeddedResourceInstallOutcome {
    skipped: bool,
    source_bundle_sha256: String,
    output_paths: Vec<PathBuf>,
    output_checksums: Vec<ManagedResourceOutputChecksum>,
}

fn install_embedded_resource(
    kind: ManagedResourceKind,
    target: &Path,
    files: &[EmbeddedResourceFile],
    force: bool,
    retired_markers: &[&str],
    replace_mode: ResourceReplaceMode,
) -> Result<EmbeddedResourceInstallOutcome> {
    let source_bundle_sha256 = source_bundle_sha256(files);
    let output_paths = files
        .iter()
        .map(|file| target.join(&file.relative))
        .collect::<Vec<_>>();
    let retired_marker_paths = retired_markers
        .iter()
        .map(|marker| target.join(marker))
        .collect::<Vec<_>>();
    let outputs_match = resource_outputs_match(target, files)?;
    let markers_present = retired_marker_paths.iter().any(|path| path.exists());
    let retired_outputs_present = retired_resource_outputs_present(kind, target);
    let manifest_managed = manifest_has_resource(kind, target)?;
    if !force && outputs_match && !markers_present && !retired_outputs_present {
        return Ok(EmbeddedResourceInstallOutcome {
            skipped: true,
            source_bundle_sha256,
            output_checksums: resource_output_checksums(&output_paths)?,
            output_paths,
        });
    }

    let retired_marker_managed = markers_present;
    if target.exists()
        && !force
        && retired_outputs_present
        && matches!(
            kind,
            ManagedResourceKind::Skill | ManagedResourceKind::Instructions
        )
        && !manifest_managed
        && !retired_marker_managed
    {
        return Err(CliError::new(
            "INSTALL_TARGET_EXISTS",
            format!(
                "{} contains unmanaged retired Kast {} files. Pass --force to replace them.",
                target.display(),
                kind
            ),
        ));
    }
    if target.exists()
        && !force
        && !outputs_match
        && !manifest_managed
        && !retired_marker_managed
        && resource_outputs_collide(target, files)
    {
        return Err(CliError::new(
            "INSTALL_TARGET_EXISTS",
            format!(
                "{} already contains unmanaged Kast {} files. Pass --force to replace them.",
                target.display(),
                kind
            ),
        ));
    }

    match replace_mode {
        ResourceReplaceMode::WholeDirectory => {
            if target.exists() && (force || manifest_managed || retired_marker_managed) {
                fs::remove_dir_all(target)?;
            }
        }
        ResourceReplaceMode::ManagedFilesOnly => {
            if force || manifest_managed || retired_marker_managed || retired_outputs_present {
                remove_managed_resource_outputs(&output_paths)?;
                remove_retired_copilot_package_outputs(target)?;
            }
        }
    }
    remove_paths(&retired_marker_paths)?;
    write_embedded_resource_files(target, files)?;
    Ok(EmbeddedResourceInstallOutcome {
        skipped: false,
        source_bundle_sha256,
        output_checksums: resource_output_checksums(&output_paths)?,
        output_paths,
    })
}

fn embedded_dir_resource_files(dir: &'static Dir<'static>) -> Result<Vec<EmbeddedResourceFile>> {
    let mut files = Vec::new();
    collect_embedded_dir_files(dir.entries(), &mut files)?;
    files.sort_by(|left, right| left.relative.cmp(&right.relative));
    Ok(files)
}

fn resource_install_files(
    source_dir: Option<&Path>,
    embedded_dir: &'static Dir<'static>,
) -> Result<Vec<EmbeddedResourceFile>> {
    match source_dir {
        Some(source_dir) => filesystem_resource_files(source_dir),
        None => embedded_dir_resource_files(embedded_dir),
    }
}

fn thin_skill_install_files(
    source_dir: Option<&Path>,
) -> Result<Vec<EmbeddedResourceFile>> {
    let files = match source_dir {
        Some(source_dir) => filesystem_resource_files(source_dir).map(|files| {
            filter_resource_install_files(files, |relative| {
                relative_matches_any(relative, THIN_SKILL_OUTPUTS)
            })
        })?,
        None => vec![EmbeddedResourceFile {
            relative: PathBuf::from("SKILL.md"),
            contents: include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/resources/kast-skill/SKILL.md"
            ))
            .to_vec(),
            executable: false,
        }],
    };
    require_resource_outputs(files, THIN_SKILL_OUTPUTS)
}

fn thin_instruction_install_files(
    source_dir: Option<&Path>,
    embedded_dir: &'static Dir<'static>,
) -> Result<Vec<EmbeddedResourceFile>> {
    let files = resource_install_files(source_dir, embedded_dir).map(|files| {
        filter_resource_install_files(files, |relative| {
            relative_matches_any(relative, THIN_INSTRUCTION_OUTPUTS)
        })
    })?;
    require_resource_outputs(files, THIN_INSTRUCTION_OUTPUTS)
}

fn filter_resource_install_files(
    files: Vec<EmbeddedResourceFile>,
    include: impl Fn(&Path) -> bool,
) -> Vec<EmbeddedResourceFile> {
    files
        .into_iter()
        .filter(|file| include(&file.relative))
        .collect()
}

fn relative_matches_any(path: &Path, candidates: &[&str]) -> bool {
    candidates.iter().any(|candidate| path == Path::new(candidate))
}

fn require_resource_outputs(
    files: Vec<EmbeddedResourceFile>,
    required: &[&str],
) -> Result<Vec<EmbeddedResourceFile>> {
    for relative in required {
        if !files.iter().any(|file| file.relative == Path::new(relative)) {
            return Err(CliError::new(
                "RESOURCE_SOURCE_INCOMPLETE",
                format!("Resource source is missing required installed file: {relative}"),
            ));
        }
    }
    Ok(files)
}

fn filesystem_resource_files(source_dir: &Path) -> Result<Vec<EmbeddedResourceFile>> {
    if !source_dir.is_dir() {
        return Err(CliError::new(
            "RESOURCE_SOURCE_MISSING",
            format!(
                "Resource source directory does not exist: {}",
                source_dir.display()
            ),
        ));
    }
    let mut files = Vec::new();
    collect_filesystem_resource_files(source_dir, source_dir, &mut files)?;
    files.sort_by(|left, right| left.relative.cmp(&right.relative));
    Ok(files)
}

fn collect_filesystem_resource_files(
    root: &Path,
    current: &Path,
    files: &mut Vec<EmbeddedResourceFile>,
) -> Result<()> {
    for entry in fs::read_dir(current)? {
        let entry = entry?;
        let path = entry.path();
        let file_type = entry.file_type()?;
        if file_type.is_dir() {
            collect_filesystem_resource_files(root, &path, files)?;
        } else if file_type.is_file() {
            let relative = path
                .strip_prefix(root)
                .map_err(|_| {
                    CliError::new(
                        "RESOURCE_SOURCE_PATH",
                        format!(
                            "Resource source file is not under source directory: {}",
                            path.display()
                        ),
                    )
                })?
                .to_path_buf();
            if is_retired_resource_marker(&relative) || is_generated_resource_cache_file(&relative)
            {
                continue;
            }
            let contents = fs::read(&path)?;
            files.push(EmbeddedResourceFile {
                relative,
                executable: is_script_contents(&contents),
                contents,
            });
        }
    }
    Ok(())
}

fn collect_embedded_dir_files(
    entries: &[DirEntry<'static>],
    files: &mut Vec<EmbeddedResourceFile>,
) -> Result<()> {
    for entry in entries {
        match entry {
            DirEntry::Dir(dir) => collect_embedded_dir_files(dir.entries(), files)?,
            DirEntry::File(file) => {
                if is_retired_resource_marker(file.path())
                    || is_generated_resource_cache_file(file.path())
                {
                    continue;
                }
                files.push(EmbeddedResourceFile {
                    relative: file.path().to_path_buf(),
                    contents: file.contents().to_vec(),
                    executable: is_script_contents(file.contents()),
                });
            }
        }
    }
    Ok(())
}

fn is_generated_resource_cache_file(path: &Path) -> bool {
    path.components().any(|component| {
        component
            .as_os_str()
            .to_str()
            .is_some_and(|value| value == "__pycache__")
    }) || path
        .file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| {
            matches!(name, ".DS_Store") || name.ends_with(".pyc") || name.ends_with(".pyo")
        })
}

fn is_retired_resource_marker(path: &Path) -> bool {
    path.file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| matches!(name, RESOURCE_MARKER | COPILOT_PACKAGE_MARKER))
}

fn is_script_contents(contents: &[u8]) -> bool {
    contents.starts_with(b"#!")
}

fn source_bundle_sha256(files: &[EmbeddedResourceFile]) -> String {
    let mut digest = Sha256::new();
    let mut ordered = files.iter().collect::<Vec<_>>();
    ordered.sort_by(|left, right| left.relative.cmp(&right.relative));
    for file in ordered {
        digest.update(file.relative.to_string_lossy().as_bytes());
        digest.update([0]);
        digest.update(if file.executable { b"1" } else { b"0" });
        digest.update([0]);
        digest.update(file.contents.len().to_le_bytes());
        digest.update([0]);
        digest.update(&file.contents);
        digest.update([0]);
    }
    hex::encode(digest.finalize())
}

fn resource_outputs_match(target: &Path, files: &[EmbeddedResourceFile]) -> Result<bool> {
    for file in files {
        let output = target.join(&file.relative);
        if !output.is_file() {
            return Ok(false);
        }
        let actual = manifest::sha256_file(&output)?;
        let expected = manifest::sha256_bytes(&file.contents);
        if actual != expected {
            return Ok(false);
        }
    }
    Ok(true)
}

fn resource_outputs_collide(target: &Path, files: &[EmbeddedResourceFile]) -> bool {
    files
        .iter()
        .any(|file| target.join(&file.relative).exists())
}

fn write_embedded_resource_files(target: &Path, files: &[EmbeddedResourceFile]) -> Result<()> {
    for file in files {
        let output = target.join(&file.relative);
        if let Some(parent) = output.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(&output, &file.contents)?;
        if file.executable {
            set_executable(&output)?;
        } else {
            set_executable_if_script(&output)?;
        }
    }
    Ok(())
}

fn resource_output_checksums(paths: &[PathBuf]) -> Result<Vec<ManagedResourceOutputChecksum>> {
    paths
        .iter()
        .map(|path| {
            Ok(ManagedResourceOutputChecksum {
                path: path.display().to_string(),
                sha256: manifest::sha256_file(path)?,
                region: None,
            })
        })
        .collect()
}

fn manifest_has_resource(kind: ManagedResourceKind, target: &Path) -> Result<bool> {
    let normalized_target = config::normalize(target.to_path_buf());
    Ok(
        self_mgmt::read_global_install_state()?.is_some_and(|install| {
            install.repos.iter().any(|repo| {
                repo.resources.iter().any(|resource| {
                    resource.kind == kind
                        && config::normalize(PathBuf::from(&resource.target_path))
                            == normalized_target
                })
            })
        }),
    )
}

fn remove_managed_resource_outputs(paths: &[PathBuf]) -> Result<()> {
    for path in paths {
        remove_existing_path(path)?;
    }
    Ok(())
}

fn remove_paths(paths: &[PathBuf]) -> Result<()> {
    for path in paths {
        remove_existing_path(path)?;
    }
    Ok(())
}

fn remove_retired_copilot_package_outputs(github_dir: &Path) -> Result<()> {
    for relative in RETIRED_COPILOT_PACKAGE_OUTPUTS {
        remove_existing_path(&github_dir.join(relative))?;
    }
    Ok(())
}

fn retired_copilot_package_outputs_present(github_dir: &Path) -> bool {
    RETIRED_COPILOT_PACKAGE_OUTPUTS
        .iter()
        .any(|relative| github_dir.join(relative).exists())
}

fn retired_resource_outputs_present(kind: ManagedResourceKind, target: &Path) -> bool {
    match kind {
        ManagedResourceKind::CopilotPackage => retired_copilot_package_outputs_present(target),
        ManagedResourceKind::Skill => RETIRED_SKILL_PACKAGE_OUTPUTS
            .iter()
            .any(|relative| target.join(relative).exists()),
        ManagedResourceKind::Instructions => RETIRED_INSTRUCTION_OUTPUTS
            .iter()
            .any(|relative| target.join(relative).exists()),
        ManagedResourceKind::AgentGuidance => false,
    }
}

fn record_managed_resource(
    kind: ManagedResourceKind,
    repo_root: &Path,
    target: &Path,
    outcome: &EmbeddedResourceInstallOutcome,
) -> Result<()> {
    self_mgmt::record_repo_resource(
        repo_root,
        ManagedRepoResource {
            kind,
            target_path: target.display().to_string(),
            primitive_version: cli::version().to_string(),
            source_bundle_sha256: outcome.source_bundle_sha256.clone(),
            output_paths: outcome
                .output_paths
                .iter()
                .map(|path| path.display().to_string())
                .collect(),
            output_checksums: outcome.output_checksums.clone(),
            installed_at: current_timestamp(),
            history: vec![],
        },
    )
}

fn update_resource_git_exclude(
    kind: ManagedResourceKind,
    repo_root: &Path,
    target: &Path,
    output_paths: &[PathBuf],
    disabled: bool,
) -> Result<GitExcludeResult> {
    if disabled {
        return Ok(GitExcludeResult {
            attempted: false,
            updated: false,
            exclude_file: None,
            reason: Some("disabled".to_string()),
            schema_version: SCHEMA_VERSION,
        });
    }
    let Some(exclude_file) = git_info_exclude_path(repo_root) else {
        return Ok(GitExcludeResult {
            attempted: false,
            updated: false,
            exclude_file: None,
            reason: Some("not a git repository".to_string()),
            schema_version: SCHEMA_VERSION,
        });
    };
    let paths = resource_git_exclude_paths(kind, repo_root, target, output_paths)?;
    let entries = git_exclude_entries(repo_root, &paths)?;
    let (start_marker, end_marker) = git_exclude_markers(kind);
    let block = format!("{start_marker}\n{}\n{end_marker}\n", entries.join("\n"));
    let original = match fs::read_to_string(&exclude_file) {
        Ok(content) => content,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(error) => return Err(error.into()),
    };
    let updated_content =
        replace_managed_block_with_markers(&original, &block, start_marker, end_marker);
    let updated = updated_content != original;
    if updated {
        if let Some(parent) = exclude_file.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(&exclude_file, updated_content)?;
    }
    Ok(GitExcludeResult {
        attempted: true,
        updated,
        exclude_file: Some(exclude_file.display().to_string()),
        reason: None,
        schema_version: SCHEMA_VERSION,
    })
}

fn resource_git_exclude_paths(
    kind: ManagedResourceKind,
    repo_root: &Path,
    target: &Path,
    output_paths: &[PathBuf],
) -> Result<Vec<PathBuf>> {
    let normalized_repo = config::normalize(repo_root.to_path_buf());
    let normalized_target = config::normalize(target.to_path_buf());
    let mut paths = output_paths.to_vec();
    if let Some(install) = self_mgmt::read_global_install_state()? {
        for repo in install
            .repos
            .iter()
            .filter(|repo| config::normalize(PathBuf::from(&repo.path)) == normalized_repo)
        {
            for resource in &repo.resources {
                if resource.kind == kind
                    && config::normalize(PathBuf::from(&resource.target_path)) != normalized_target
                {
                    paths.extend(resource.output_paths.iter().map(PathBuf::from));
                }
            }
        }
    }
    Ok(paths)
}

fn git_exclude_not_repository() -> GitExcludeResult {
    GitExcludeResult {
        attempted: false,
        updated: false,
        exclude_file: None,
        reason: Some("not a git repository".to_string()),
        schema_version: SCHEMA_VERSION,
    }
}

fn git_info_exclude_path(repo_root: &Path) -> Option<PathBuf> {
    let output = ProcessCommand::new("git")
        .arg("-C")
        .arg(repo_root)
        .args(["rev-parse", "--git-path", "info/exclude"])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let raw = String::from_utf8(output.stdout).ok()?;
    let raw = raw.trim();
    if raw.is_empty() {
        return None;
    }
    let path = PathBuf::from(raw);
    Some(if path.is_absolute() {
        path
    } else {
        repo_root.join(path)
    })
}

fn git_exclude_entries(repo_root: &Path, output_paths: &[PathBuf]) -> Result<Vec<String>> {
    let mut entries = output_paths
        .iter()
        .map(|path| path_to_git_exclude_entry(repo_root, path))
        .collect::<Result<Vec<_>>>()?;
    entries.sort();
    entries.dedup();
    Ok(entries)
}

fn path_to_git_exclude_entry(repo_root: &Path, path: &Path) -> Result<String> {
    let normalized_repo_root = canonical_path_for_compare(repo_root);
    let normalized_path = canonical_path_for_compare(path);
    let relative = normalized_path
        .strip_prefix(&normalized_repo_root)
        .map_err(|_| {
            CliError::new(
                "INSTALL_TARGET_OUTSIDE_GIT_REPO",
                format!(
                    "Managed Kast resource path {} is not under Git repository {}",
                    path.display(),
                    repo_root.display()
                ),
            )
        })?;
    Ok(relative
        .components()
        .map(|component| component.as_os_str().to_string_lossy())
        .collect::<Vec<_>>()
        .join("/"))
}

fn canonical_path_for_compare(path: &Path) -> PathBuf {
    path.canonicalize()
        .unwrap_or_else(|_| path.to_path_buf())
        .components()
        .collect()
}

fn git_exclude_markers(kind: ManagedResourceKind) -> (&'static str, &'static str) {
    match kind {
        ManagedResourceKind::CopilotPackage => (
            COPILOT_GIT_EXCLUDE_BLOCK_START,
            COPILOT_GIT_EXCLUDE_BLOCK_END,
        ),
        ManagedResourceKind::Skill => ("# >>> kast skill >>>", "# <<< kast skill <<<"),
        ManagedResourceKind::Instructions => {
            ("# >>> kast instructions >>>", "# <<< kast instructions <<<")
        }
        ManagedResourceKind::AgentGuidance => (
            "# >>> kast agent guidance >>>",
            "# <<< kast agent guidance <<<",
        ),
    }
}

struct CopilotPackageOutput {
    target: PathBuf,
    contents: &'static [u8],
    executable: bool,
}

fn copilot_package_outputs() -> Result<Vec<CopilotPackageOutput>> {
    let manifest = embedded_file_contents(&COPILOT_PLUGIN, COPILOT_PRIMITIVE_MANIFEST)?;
    let manifest: Value = serde_json::from_slice(manifest)?;
    let outputs = manifest
        .get("outputs")
        .and_then(Value::as_array)
        .ok_or_else(|| {
            CliError::new(
                "COPILOT_PACKAGE_MANIFEST_INVALID",
                "Copilot primitive manifest must contain an outputs array.",
            )
        })?;
    let mut resolved = Vec::with_capacity(outputs.len());
    for output in outputs {
        let output_type = manifest_string_field(output, "type")?;
        let source = manifest_string_field(output, "source")?;
        let target = validate_manifest_relative_path(manifest_string_field(output, "target")?)?;
        let source = validate_manifest_relative_path(source)?;
        let source_path = source.to_string_lossy();
        let contents = match output_type {
            "PACKAGE_FILE" => embedded_file_contents(&COPILOT_PLUGIN, &source_path)?,
            other => {
                return Err(CliError::new(
                    "COPILOT_PACKAGE_MANIFEST_INVALID",
                    format!("Unsupported Copilot package output type `{other}`."),
                ));
            }
        };
        resolved.push(CopilotPackageOutput {
            target,
            contents,
            executable: output
                .get("executable")
                .and_then(Value::as_bool)
                .unwrap_or(false),
        });
    }
    Ok(resolved)
}

fn embedded_file_contents(dir: &'static Dir<'static>, relative: &str) -> Result<&'static [u8]> {
    dir.get_file(relative)
        .map(|file| file.contents())
        .ok_or_else(|| {
            CliError::new(
                "COPILOT_PACKAGE_SOURCE_MISSING",
                format!("Embedded Copilot package source `{relative}` was not found."),
            )
        })
}

fn manifest_string_field<'a>(value: &'a Value, field: &str) -> Result<&'a str> {
    value.get(field).and_then(Value::as_str).ok_or_else(|| {
        CliError::new(
            "COPILOT_PACKAGE_MANIFEST_INVALID",
            format!("Copilot package output must contain string field `{field}`."),
        )
    })
}

fn validate_manifest_relative_path(value: &str) -> Result<PathBuf> {
    let path = Path::new(value);
    let safe = !value.trim().is_empty()
        && path
            .components()
            .all(|component| matches!(component, Component::Normal(_) | Component::CurDir));
    if safe && !path.is_absolute() {
        return Ok(path.to_path_buf());
    }
    Err(CliError::new(
        "COPILOT_PACKAGE_MANIFEST_INVALID",
        format!("Manifest path `{value}` must be relative and must not contain `..`."),
    ))
}

#[cfg(unix)]
fn set_executable(path: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    let mut permissions = fs::metadata(path)?.permissions();
    permissions.set_mode(0o755);
    fs::set_permissions(path, permissions)?;
    Ok(())
}

#[cfg(not(unix))]
fn set_executable(_path: &Path) -> Result<()> {
    Ok(())
}

#[cfg(unix)]
fn set_executable_if_script(path: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    let executable = path
        .extension()
        .and_then(|extension| extension.to_str())
        .is_some_and(|extension| matches!(extension, "sh" | "py" | "mjs"));
    if executable {
        let mut permissions = fs::metadata(path)?.permissions();
        permissions.set_mode(0o755);
        fs::set_permissions(path, permissions)?;
    }
    Ok(())
}

#[cfg(not(unix))]
fn set_executable_if_script(_path: &Path) -> Result<()> {
    Ok(())
}
