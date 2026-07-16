use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::ffi::OsString;
use std::fs;
use std::io::{Read, Write};
use std::path::{Component, Path, PathBuf};
use std::process::Command as ProcessCommand;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SourceSnapshot {
    pub canonical_root: PathBuf,
    pub worktree_kind: WorktreeKind,
    pub git_commit: GitCommit,
    pub source_tree_sha256: Sha256Digest,
}

impl SourceSnapshot {
    pub fn capture(requested_root: &Path) -> Result<Self> {
        let requested_root = canonical_directory(requested_root, "source checkout")?;
        let repository_root = git_path(&requested_root, &["rev-parse", "--show-toplevel"])?;
        let canonical_root = canonical_directory(&repository_root, "Git worktree root")?;
        let git_commit = GitCommit::try_from(git_text(
            &canonical_root,
            &["rev-parse", "--verify", "HEAD"],
        )?)?;
        let git_directory = resolved_git_directory(
            &canonical_root,
            &git_text(&canonical_root, &["rev-parse", "--git-dir"])?
        )?;
        let common_git_directory = resolved_git_directory(
            &canonical_root,
            &git_text(&canonical_root, &["rev-parse", "--git-common-dir"])?
        )?;
        let worktree_kind = if git_directory == common_git_directory {
            WorktreeKind::Primary
        } else {
            WorktreeKind::Linked
        };
        let source_tree_sha256 = source_tree_digest(&canonical_root)?;
        Ok(Self {
            canonical_root,
            worktree_kind,
            git_commit,
            source_tree_sha256,
        })
    }

    pub fn write_atomic(&self, path: &Path) -> Result<()> {
        let parent = path.parent().ok_or_else(|| {
            CliError::new(
                "LOCAL_SOURCE_SNAPSHOT_PATH_INVALID",
                format!("Source snapshot path has no parent: {}", path.display()),
            )
        })?;
        fs::create_dir_all(parent)?;
        let temporary = path.with_extension(format!("json.tmp-{}", std::process::id()));
        let result = (|| -> Result<()> {
            let mut output = fs::File::create(&temporary)?;
            output.write_all(&serde_json::to_vec_pretty(self)?)?;
            output.write_all(b"\n")?;
            output.sync_all()?;
            fs::rename(&temporary, path)?;
            Ok(())
        })();
        if result.is_err() {
            let _ = fs::remove_file(&temporary);
        }
        result
    }

    pub fn read_strict(path: &Path) -> Result<Self> {
        Self::from_slice(&fs::read(path)?, &path.display().to_string())
    }

    fn from_slice(bytes: &[u8], source: &str) -> Result<Self> {
        serde_json::from_slice(bytes).map_err(|error| {
            CliError::new(
                "LOCAL_SOURCE_SNAPSHOT_INVALID",
                format!("Invalid source snapshot at {source}: {error}"),
            )
        })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum WorktreeKind {
    Primary,
    Linked,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(try_from = "String", into = "String")]
pub struct GitCommit(String);

impl TryFrom<String> for GitCommit {
    type Error = CliError;

    fn try_from(value: String) -> Result<Self> {
        let value = value.trim().to_ascii_lowercase();
        if (40..=64).contains(&value.len()) && value.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            Ok(Self(value))
        } else {
            Err(CliError::new(
                "LOCAL_SOURCE_SNAPSHOT_INVALID",
                "Git commit identity must be a 40-64 character hexadecimal object ID.",
            ))
        }
    }
}

impl GitCommit {
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl From<GitCommit> for String {
    fn from(value: GitCommit) -> Self {
        value.0
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(try_from = "String", into = "String")]
pub struct Sha256Digest(String);

impl Sha256Digest {
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl TryFrom<String> for Sha256Digest {
    type Error = CliError;

    fn try_from(value: String) -> Result<Self> {
        let value = value.trim().to_ascii_lowercase();
        if value.len() == 64 && value.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            Ok(Self(value))
        } else {
            Err(CliError::new(
                "LOCAL_SOURCE_SNAPSHOT_INVALID",
                "A SHA-256 identity must be exactly 64 hexadecimal characters.",
            ))
        }
    }
}

impl From<Sha256Digest> for String {
    fn from(value: Sha256Digest) -> Self {
        value.0
    }
}

fn source_tree_digest(root: &Path) -> Result<Sha256Digest> {
    let tracked = listed_paths(&git_bytes(root, &["ls-files", "-z", "--cached"])?)?;
    let tracked_paths = tracked.iter().cloned().collect::<std::collections::HashSet<_>>();
    let untracked = listed_paths(&git_bytes(
        root,
        &["ls-files", "-z", "--others", "--exclude-standard"],
    )?)?;
    let mut paths = tracked;
    paths.extend(untracked);
    paths.sort_by_key(|path| path_identity_bytes(path));
    paths.dedup();

    let mut digest = Sha256::new();
    digest.update(b"kast-local-source-snapshot-v2\0");
    digest.update((paths.len() as u64).to_be_bytes());
    for relative in paths {
        require_safe_relative_path(&relative)?;
        let identity = path_identity_bytes(&relative);
        update_framed_bytes(&mut digest, &identity);
        let mut entry_digest = Sha256::new();
        hash_source_entry(
            root,
            &relative,
            tracked_paths.contains(&relative),
            &mut entry_digest,
        )?;
        digest.update(entry_digest.finalize());
    }
    Sha256Digest::try_from(hex::encode(digest.finalize()))
}

fn hash_source_entry(
    root: &Path,
    relative: &Path,
    tracked: bool,
    digest: &mut Sha256,
) -> Result<()> {
    let path = root.join(relative);
    let metadata = match fs::symlink_metadata(&path) {
        Ok(metadata) => metadata,
        Err(error) if tracked && error.kind() == std::io::ErrorKind::NotFound => {
            digest.update(b"deleted\0");
            return Ok(());
        }
        Err(error) => {
            return Err(CliError::new(
                "LOCAL_SOURCE_SNAPSHOT_CHANGED",
                format!(
                    "Source entry changed while capturing {}: {error}",
                    path.display()
                ),
            ));
        }
    };
    let file_type = metadata.file_type();
    if file_type.is_symlink() {
        digest.update(b"symlink\0");
        let target = fs::read_link(&path)?;
        let bytes = path_identity_bytes(&target);
        update_framed_bytes(digest, &bytes);
        return Ok(());
    }
    if file_type.is_file() {
        digest.update(b"file\0");
        digest.update([executable_marker(&metadata)]);
        digest.update(metadata.len().to_be_bytes());
        let mut file = fs::File::open(&path)?;
        let mut buffer = [0_u8; 64 * 1024];
        let mut total = 0_u64;
        loop {
            let read = file.read(&mut buffer)?;
            if read == 0 {
                break;
            }
            digest.update(&buffer[..read]);
            total += read as u64;
        }
        if total != metadata.len() {
            return Err(CliError::new(
                "LOCAL_SOURCE_SNAPSHOT_CHANGED",
                format!("Source file length changed while hashing {}.", path.display()),
            ));
        }
        return Ok(());
    }
    if file_type.is_dir() {
        digest.update(b"gitlink\0");
        let commit = git_text(&path, &["rev-parse", "--verify", "HEAD"])?;
        update_framed_bytes(digest, commit.as_bytes());
        let status = git_bytes(
            &path,
            &["status", "--porcelain=v2", "-z", "--untracked-files=all"],
        )?;
        update_framed_bytes(digest, &status);
        return Ok(());
    }
    Err(CliError::new(
        "LOCAL_SOURCE_ENTRY_UNSUPPORTED",
        format!(
            "Source snapshot refuses unsupported filesystem entry {}.",
            path.display()
        ),
    ))
}

fn update_framed_bytes(digest: &mut Sha256, bytes: &[u8]) {
    digest.update((bytes.len() as u64).to_be_bytes());
    digest.update(bytes);
}

fn canonical_directory(path: &Path, label: &str) -> Result<PathBuf> {
    let canonical = fs::canonicalize(path).map_err(|error| {
        CliError::new(
            "LOCAL_SOURCE_ROOT_INVALID",
            format!("Could not resolve {label} {}: {error}", path.display()),
        )
    })?;
    if canonical.is_dir() {
        Ok(canonical)
    } else {
        Err(CliError::new(
            "LOCAL_SOURCE_ROOT_INVALID",
            format!("{label} is not a directory: {}", canonical.display()),
        ))
    }
}

fn resolved_git_directory(root: &Path, raw: &str) -> Result<PathBuf> {
    let path = PathBuf::from(raw);
    let path = if path.is_absolute() {
        path
    } else {
        root.join(path)
    };
    canonical_directory(&path, "Git metadata directory")
}

fn git_path(root: &Path, args: &[&str]) -> Result<PathBuf> {
    Ok(PathBuf::from(git_text(root, args)?))
}

fn git_text(root: &Path, args: &[&str]) -> Result<String> {
    let bytes = git_bytes(root, args)?;
    String::from_utf8(bytes)
        .map(|text| text.trim().to_string())
        .map_err(|error| {
            CliError::new(
                "LOCAL_SOURCE_GIT_FAILED",
                format!("Git returned non-UTF-8 text for {:?}: {error}", args),
            )
        })
}

fn git_bytes(root: &Path, args: &[&str]) -> Result<Vec<u8>> {
    let output = ProcessCommand::new("git")
        .arg("-C")
        .arg(root)
        .args(args)
        .output()
        .map_err(|error| {
            CliError::new(
                "LOCAL_SOURCE_GIT_FAILED",
                format!("Could not execute git {:?}: {error}", args),
            )
        })?;
    if output.status.success() {
        Ok(output.stdout)
    } else {
        Err(CliError::new(
            "LOCAL_SOURCE_GIT_FAILED",
            format!(
                "git {:?} failed for {}: {}",
                args,
                root.display(),
                String::from_utf8_lossy(&output.stderr).trim()
            ),
        ))
    }
}

fn listed_paths(output: &[u8]) -> Result<Vec<PathBuf>> {
    output
        .split(|byte| *byte == 0)
        .filter(|raw| !raw.is_empty())
        .map(path_from_git_bytes)
        .collect()
}

#[cfg(unix)]
fn path_from_git_bytes(raw: &[u8]) -> Result<PathBuf> {
    use std::os::unix::ffi::OsStringExt;

    Ok(PathBuf::from(OsString::from_vec(raw.to_vec())))
}

#[cfg(not(unix))]
fn path_from_git_bytes(raw: &[u8]) -> Result<PathBuf> {
    String::from_utf8(raw.to_vec())
        .map(PathBuf::from)
        .map_err(|error| {
            CliError::new(
                "LOCAL_SOURCE_PATH_INVALID",
                format!("Git source path is not valid UTF-8: {error}"),
            )
        })
}

fn require_safe_relative_path(path: &Path) -> Result<()> {
    if path.as_os_str().is_empty()
        || path
            .components()
            .any(|component| !matches!(component, Component::Normal(_)))
    {
        return Err(CliError::new(
            "LOCAL_SOURCE_PATH_INVALID",
            format!("Git returned an unsafe source path: {}", path.display()),
        ));
    }
    Ok(())
}

#[cfg(unix)]
fn path_identity_bytes(path: &Path) -> Vec<u8> {
    use std::os::unix::ffi::OsStrExt;

    path.as_os_str().as_bytes().to_vec()
}

#[cfg(not(unix))]
fn path_identity_bytes(path: &Path) -> Vec<u8> {
    path.to_string_lossy().as_bytes().to_vec()
}

#[cfg(unix)]
fn executable_marker(metadata: &fs::Metadata) -> u8 {
    use std::os::unix::fs::PermissionsExt;

    u8::from(metadata.permissions().mode() & 0o111 != 0)
}

#[cfg(not(unix))]
fn executable_marker(_metadata: &fs::Metadata) -> u8 {
    0
}
