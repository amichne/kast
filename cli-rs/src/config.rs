use crate::cli::{BackendName, DaemonStartArgs};
use crate::error::{CliError, Result};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::env;
use std::fs;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize)]
pub struct KastConfig {
    pub server: ServerConfig,
    #[serde(skip_serializing_if = "RuntimeConfig::is_default")]
    pub runtime: RuntimeConfig,
    pub paths: PathsConfig,
    pub backends: BackendsConfig,
    pub cli: CliConfig,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerConfig {
    pub max_results: u32,
    pub request_timeout_millis: u64,
    pub max_concurrent_requests: u32,
}

#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeConfig {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub default_backend: Option<BackendName>,
}

impl RuntimeConfig {
    fn is_default(&self) -> bool {
        self.default_backend.is_none()
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PathsConfig {
    pub install_root: PathBuf,
    pub bin_dir: PathBuf,
    pub lib_dir: PathBuf,
    pub cache_dir: PathBuf,
    pub logs_dir: PathBuf,
    pub descriptor_dir: PathBuf,
    pub socket_dir: PathBuf,
}

#[derive(Debug, Clone, Serialize)]
pub struct BackendsConfig {
    pub headless: HeadlessBackendConfig,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HeadlessBackendConfig {
    pub runtime_libs_dir: Option<PathBuf>,
    pub idea_home: Option<PathBuf>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CliConfig {
    pub binary_path: PathBuf,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialConfig {
    server: Option<PartialServer>,
    runtime: Option<PartialRuntime>,
    paths: Option<PartialPaths>,
    backends: Option<PartialBackends>,
    cli: Option<PartialCli>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialServer {
    max_results: Option<u32>,
    request_timeout_millis: Option<u64>,
    max_concurrent_requests: Option<u32>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialRuntime {
    default_backend: Option<BackendName>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialPaths {
    install_root: Option<PathBuf>,
    bin_dir: Option<PathBuf>,
    lib_dir: Option<PathBuf>,
    cache_dir: Option<PathBuf>,
    logs_dir: Option<PathBuf>,
    descriptor_dir: Option<PathBuf>,
    socket_dir: Option<PathBuf>,
}

#[derive(Debug, Default, Deserialize)]
struct PartialBackends {
    headless: Option<PartialHeadless>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialHeadless {
    runtime_libs_dir: Option<Option<PathBuf>>,
    idea_home: Option<Option<PathBuf>>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialCli {
    binary_path: Option<PathBuf>,
}

impl KastConfig {
    pub fn defaults() -> Self {
        let install_root = home_dir().join(".kast");
        let bin_dir = install_root.join("bin");
        let lib_dir = install_root.join("lib");
        let cache_dir = install_root.join("cache");
        let logs_dir = install_root.join("logs");
        let descriptor_dir = cache_dir.join("daemons");
        let socket_dir = env::temp_dir();
        Self {
            server: ServerConfig {
                max_results: 500,
                request_timeout_millis: 30_000,
                max_concurrent_requests: 4,
            },
            runtime: RuntimeConfig::default(),
            paths: PathsConfig {
                install_root,
                bin_dir: bin_dir.clone(),
                lib_dir: lib_dir.clone(),
                cache_dir,
                logs_dir,
                descriptor_dir,
                socket_dir,
            },
            backends: BackendsConfig {
                headless: HeadlessBackendConfig {
                    runtime_libs_dir: Some(lib_dir.join("backends/headless/current/runtime-libs")),
                    idea_home: None,
                },
            },
            cli: CliConfig {
                binary_path: env::current_exe().unwrap_or_else(|_| bin_dir.join("kast")),
            },
        }
    }

    pub fn load_global() -> Result<Self> {
        let mut config = Self::defaults();
        let global_config = global_config_path();
        if global_config.is_file() {
            config.apply(read_partial_config(&global_config)?);
        }
        Ok(config)
    }

    pub fn load(workspace_root: &Path) -> Result<Self> {
        let mut config = Self::load_global()?;
        let workspace_config = workspace_data_directory(workspace_root)?.join("config.toml");
        if workspace_config.is_file() {
            config.apply(read_partial_config(&workspace_config)?);
        }
        Ok(config)
    }

    fn apply(&mut self, partial: PartialConfig) {
        if let Some(paths) = partial.paths {
            if let Some(value) = paths.install_root {
                self.paths.install_root = normalize(value);
                self.paths.bin_dir = self.paths.install_root.join("bin");
                self.paths.lib_dir = self.paths.install_root.join("lib");
                self.paths.cache_dir = self.paths.install_root.join("cache");
                self.paths.logs_dir = self.paths.install_root.join("logs");
                self.paths.descriptor_dir = self.paths.cache_dir.join("daemons");
                self.cli.binary_path = self.paths.bin_dir.join("kast");
                self.backends.headless.runtime_libs_dir = Some(
                    self.paths
                        .lib_dir
                        .join("backends/headless/current/runtime-libs"),
                );
            }
            if let Some(value) = paths.bin_dir {
                self.paths.bin_dir = normalize(value);
                self.cli.binary_path = self.paths.bin_dir.join("kast");
            }
            if let Some(value) = paths.lib_dir {
                self.paths.lib_dir = normalize(value);
                self.backends.headless.runtime_libs_dir = Some(
                    self.paths
                        .lib_dir
                        .join("backends/headless/current/runtime-libs"),
                );
            }
            if let Some(value) = paths.cache_dir {
                self.paths.cache_dir = normalize(value);
                self.paths.descriptor_dir = self.paths.cache_dir.join("daemons");
            }
            if let Some(value) = paths.logs_dir {
                self.paths.logs_dir = normalize(value);
            }
            if let Some(value) = paths.descriptor_dir {
                self.paths.descriptor_dir = normalize(value);
            }
            if let Some(value) = paths.socket_dir {
                self.paths.socket_dir = normalize(value);
            }
        }
        if let Some(server) = partial.server {
            if let Some(value) = server.max_results {
                self.server.max_results = value;
            }
            if let Some(value) = server.request_timeout_millis {
                self.server.request_timeout_millis = value;
            }
            if let Some(value) = server.max_concurrent_requests {
                self.server.max_concurrent_requests = value;
            }
        }
        if let Some(runtime) = partial.runtime
            && let Some(value) = runtime.default_backend
        {
            self.runtime.default_backend = Some(value);
        }
        if let Some(backends) = partial.backends
            && let Some(headless) = backends.headless
        {
            if let Some(value) = headless.runtime_libs_dir {
                self.backends.headless.runtime_libs_dir = value.map(normalize);
            }
            if let Some(value) = headless.idea_home {
                self.backends.headless.idea_home = value.map(normalize);
            }
        }
        if let Some(cli) = partial.cli
            && let Some(value) = cli.binary_path
        {
            self.cli.binary_path = normalize(value);
        }
    }
}

pub fn init_config() -> Result<PathBuf> {
    let config_file = global_config_path();
    if !config_file.exists() {
        if let Some(parent) = config_file.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(&config_file, default_config_template()?)?;
    }
    Ok(config_file)
}

pub fn default_config_template() -> Result<String> {
    Ok(toml::to_string_pretty(&KastConfig::defaults())?)
}

pub fn backend_runtime_libs_dir(
    config: &KastConfig,
    backend_name: BackendName,
    override_dir: Option<PathBuf>,
) -> Result<PathBuf> {
    let configured = match backend_name {
        BackendName::Headless => config.backends.headless.runtime_libs_dir.clone(),
        BackendName::Idea => {
            return Err(CliError::new(
                "DAEMON_START_ERROR",
                "The idea backend is hosted by IDEA and cannot be launched by kast daemon start.",
            ));
        }
    };
    override_dir.map(normalize).or(configured).ok_or_else(|| {
        CliError::new(
            "DAEMON_START_ERROR",
            "Cannot locate backend runtime-libs. Set backends.headless.runtimeLibsDir in `kast config init` output, or pass --runtime-libs-dir.",
        )
    })
}

pub fn server_launch_args(args: &DaemonStartArgs, config: &KastConfig) -> Result<Vec<String>> {
    let workspace_root = normalize(args.workspace_root.clone().unwrap_or(env::current_dir()?));
    let socket_path = args
        .socket_path
        .clone()
        .map(normalize)
        .unwrap_or_else(|| default_socket_path(&workspace_root));
    let mut result = vec![format!("--workspace-root={}", workspace_root.display())];
    if args.stdio {
        result.push("--stdio".to_string());
    } else {
        result.push(format!("--socket-path={}", socket_path.display()));
    }
    result.push(format!(
        "--module-name={}",
        args.module_name.as_deref().unwrap_or("sources")
    ));
    if let Some(source_roots) = &args.source_roots {
        result.push(format!("--source-roots={source_roots}"));
    }
    if let Some(classpath) = &args.classpath {
        result.push(format!("--classpath={classpath}"));
    }
    result.push(format!(
        "--request-timeout-ms={}",
        args.request_timeout_ms
            .unwrap_or(config.server.request_timeout_millis)
    ));
    result.push(format!(
        "--max-results={}",
        args.max_results.unwrap_or(config.server.max_results)
    ));
    result.push(format!(
        "--max-concurrent-requests={}",
        args.max_concurrent_requests
            .unwrap_or(config.server.max_concurrent_requests)
    ));
    if args.profile {
        result.push("--profile".to_string());
    }
    if let Some(value) = &args.profile_modes {
        result.push(format!("--profile-modes={value}"));
    }
    if let Some(value) = args.profile_duration {
        result.push(format!("--profile-duration={value}"));
    }
    if let Some(value) = &args.profile_otlp_endpoint {
        result.push(format!("--profile-otlp-endpoint={value}"));
    }
    Ok(result)
}

pub fn kast_config_home() -> PathBuf {
    env::var_os("KAST_CONFIG_HOME")
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .map(normalize)
        .unwrap_or_else(|| normalize(home_dir().join(".config/kast")))
}

pub fn global_config_path() -> PathBuf {
    kast_config_home().join("config.toml")
}

pub fn home_dir() -> PathBuf {
    env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."))
}

pub fn normalize(path: PathBuf) -> PathBuf {
    if path.is_absolute() {
        path
    } else {
        env::current_dir()
            .unwrap_or_else(|_| PathBuf::from("."))
            .join(path)
    }
    .components()
    .collect()
}

pub fn workspace_data_directory(workspace_root: &Path) -> Result<PathBuf> {
    let root = normalize(workspace_root.to_path_buf());
    if let Some(workspace) = git_workspace(&root) {
        return Ok(workspace_data_directory_for_git(&home_dir(), &workspace));
    }
    if root.starts_with(env::temp_dir()) {
        return Ok(root.join(".gradle/kast"));
    }
    let id = local_workspace_id(&root)?;
    Ok(home_dir()
        .join(".kast/workspaces/local")
        .join(format!("{}--{id}", sanitized_path(&root))))
}

#[allow(dead_code)]
pub fn workspace_database_path(workspace_root: &Path) -> Result<PathBuf> {
    Ok(workspace_data_directory(workspace_root)?.join("cache/source-index.db"))
}

pub fn default_socket_path(workspace_root: &Path) -> PathBuf {
    env::temp_dir().join(format!("kast-{}.sock", workspace_hash(workspace_root)))
}

pub fn workspace_hash(workspace_root: &Path) -> String {
    let normalized = normalize(workspace_root.to_path_buf());
    let digest = Sha256::digest(normalized.to_string_lossy().as_bytes());
    hex::encode(digest)[0..12].to_string()
}

fn read_partial_config(path: &Path) -> Result<PartialConfig> {
    Ok(toml::from_str(&fs::read_to_string(path)?)?)
}

#[derive(Debug)]
struct GitWorkspace {
    toplevel: PathBuf,
    common_dir: PathBuf,
    git_dir: PathBuf,
    remote: Option<GitRemote>,
}

#[derive(Debug, Clone)]
struct GitRemote {
    host: String,
    owner: String,
    repo: String,
}

fn git_workspace(workspace_root: &Path) -> Option<GitWorkspace> {
    let toplevel = git_path(workspace_root, &["rev-parse", "--show-toplevel"])?;
    let common_dir = git_path(workspace_root, &["rev-parse", "--git-common-dir"])?;
    let git_dir = git_path(workspace_root, &["rev-parse", "--git-dir"])?;
    let remote = git_output(workspace_root, &["config", "--get", "remote.origin.url"])
        .and_then(|remote| parse_git_remote(remote.trim()));
    Some(GitWorkspace {
        toplevel,
        common_dir,
        git_dir,
        remote,
    })
}

fn workspace_data_directory_for_git(home: &Path, workspace: &GitWorkspace) -> PathBuf {
    let repo_root = if let Some(remote) = &workspace.remote {
        home.join(".kast/workspaces/git")
            .join(&remote.host)
            .join(&remote.owner)
            .join(&remote.repo)
    } else {
        home.join(".kast/workspaces/git/local")
            .join(git_common_dir_hash(&workspace.common_dir))
    };
    repo_root.join("worktrees").join(format!(
        "{}--{}",
        workspace_slug(&workspace.toplevel),
        git_worktree_hash(&workspace.toplevel, &workspace.git_dir)
    ))
}

fn git_worktree_hash(toplevel: &Path, git_dir: &Path) -> String {
    sha256_prefix(&format!(
        "{}\n{}",
        normalize(toplevel.to_path_buf()).display(),
        normalize(git_dir.to_path_buf()).display()
    ))
}

fn git_common_dir_hash(common_dir: &Path) -> String {
    sha256_prefix(&normalize(common_dir.to_path_buf()).display().to_string())
}

fn sha256_prefix(value: &str) -> String {
    let digest = Sha256::digest(value.as_bytes());
    hex::encode(digest)[0..12].to_string()
}

fn git_path(workspace_root: &Path, args: &[&str]) -> Option<PathBuf> {
    let raw = git_output(workspace_root, args)?;
    let path = PathBuf::from(raw.trim());
    Some(normalize(if path.is_absolute() {
        path
    } else {
        workspace_root.join(path)
    }))
}

fn git_output(workspace_root: &Path, args: &[&str]) -> Option<String> {
    let output = std::process::Command::new("git")
        .args(args)
        .current_dir(workspace_root)
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let value = String::from_utf8_lossy(&output.stdout).trim().to_string();
    (!value.is_empty()).then_some(value)
}

fn parse_git_remote(remote_url: &str) -> Option<GitRemote> {
    if let Some(rest) = remote_url.strip_prefix("git@") {
        let (host, path) = rest.split_once(':')?;
        let (owner, repo) = path.split_once('/')?;
        return Some(GitRemote {
            host: host.to_string(),
            owner: owner.to_string(),
            repo: repo.trim_end_matches(".git").to_string(),
        });
    }
    if let Some(rest) = remote_url.strip_prefix("https://") {
        let mut parts = rest.splitn(4, '/');
        let host = parts.next()?;
        let owner = parts.next()?;
        let repo = parts.next()?;
        return Some(GitRemote {
            host: host.to_string(),
            owner: owner.to_string(),
            repo: repo.trim_end_matches(".git").to_string(),
        });
    }
    None
}

fn local_workspace_id(workspace_root: &Path) -> Result<String> {
    let registry_path = home_dir().join(".kast/workspaces/local-workspaces.json");
    if let Some(parent) = registry_path.parent() {
        fs::create_dir_all(parent)?;
    }
    let mut registry: BTreeMap<String, String> = if registry_path.is_file() {
        serde_json::from_str(&fs::read_to_string(&registry_path)?).unwrap_or_default()
    } else {
        BTreeMap::new()
    };
    let key = workspace_root.to_string_lossy().to_string();
    if let Some(id) = registry.get(&key) {
        return Ok(id.clone());
    }
    let id = uuid::Uuid::new_v4().to_string();
    registry.insert(key, id.clone());
    fs::write(registry_path, serde_json::to_string_pretty(&registry)?)?;
    Ok(id)
}

fn sanitized_path(workspace_root: &Path) -> String {
    sanitized_segment(&workspace_root.to_string_lossy())
}

fn workspace_slug(workspace_root: &Path) -> String {
    workspace_root
        .file_name()
        .and_then(|name| name.to_str())
        .map(sanitized_segment)
        .unwrap_or_else(|| "workspace".to_string())
}

fn sanitized_segment(value: &str) -> String {
    let mut result = String::new();
    for ch in value.chars() {
        if ch.is_ascii_alphanumeric() || matches!(ch, '.' | '_' | '-') {
            result.push(ch);
        } else if !result.ends_with('-') {
            result.push('-');
        }
    }
    let trimmed = result.trim_matches('-');
    if trimmed.is_empty() {
        "workspace".to_string()
    } else {
        trimmed.chars().take(80).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn workspace_hash_matches_sha256_prefix_contract() {
        let path = PathBuf::from("/tmp/kast-workspace");
        let digest = Sha256::digest(path.to_string_lossy().as_bytes());
        assert_eq!(workspace_hash(&path), hex::encode(digest)[0..12]);
    }

    #[test]
    fn parses_github_remotes() {
        let ssh = parse_git_remote("git@github.com:amichne/kast.git").unwrap();
        assert_eq!(ssh.host, "github.com");
        assert_eq!(ssh.owner, "amichne");
        assert_eq!(ssh.repo, "kast");

        let https = parse_git_remote("https://github.com/amichne/kast.git").unwrap();
        assert_eq!(https.host, "github.com");
        assert_eq!(https.owner, "amichne");
        assert_eq!(https.repo, "kast");
    }

    #[test]
    fn git_workspace_data_directory_uses_remote_worktree_path() {
        let home = PathBuf::from("/home/alex");
        let workspace = GitWorkspace {
            toplevel: PathBuf::from("/work/kast"),
            common_dir: PathBuf::from("/work/kast/.git"),
            git_dir: PathBuf::from("/work/kast/.git"),
            remote: Some(GitRemote {
                host: "github.com".to_string(),
                owner: "amichne".to_string(),
                repo: "kast".to_string(),
            }),
        };

        assert_eq!(
            workspace_data_directory_for_git(&home, &workspace),
            home.join(format!(
                ".kast/workspaces/git/github.com/amichne/kast/worktrees/kast--{}",
                git_worktree_hash(&workspace.toplevel, &workspace.git_dir)
            )),
        );
    }

    #[test]
    fn git_workspace_data_directory_isolates_sibling_worktrees() {
        let home = PathBuf::from("/home/alex");
        let common_dir = PathBuf::from("/work/kast/.git");
        let remote = GitRemote {
            host: "github.com".to_string(),
            owner: "amichne".to_string(),
            repo: "kast".to_string(),
        };
        let first = GitWorkspace {
            toplevel: PathBuf::from("/work/kast"),
            common_dir: common_dir.clone(),
            git_dir: common_dir.clone(),
            remote: Some(remote.clone()),
        };
        let second = GitWorkspace {
            toplevel: PathBuf::from("/work/kast-feature"),
            common_dir,
            git_dir: PathBuf::from("/work/kast/.git/worktrees/kast-feature"),
            remote: Some(remote),
        };

        assert_ne!(
            workspace_data_directory_for_git(&home, &first),
            workspace_data_directory_for_git(&home, &second),
        );
    }

    #[test]
    fn git_workspace_data_directory_supports_git_without_origin() {
        let home = PathBuf::from("/home/alex");
        let workspace = GitWorkspace {
            toplevel: PathBuf::from("/work/private"),
            common_dir: PathBuf::from("/work/private/.git"),
            git_dir: PathBuf::from("/work/private/.git/worktrees/private"),
            remote: None,
        };

        assert_eq!(
            workspace_data_directory_for_git(&home, &workspace),
            home.join(format!(
                ".kast/workspaces/git/local/{}/worktrees/private--{}",
                git_common_dir_hash(&workspace.common_dir),
                git_worktree_hash(&workspace.toplevel, &workspace.git_dir)
            )),
        );
    }

    #[test]
    fn git_worktree_hash_matches_toplevel_and_git_dir_contract() {
        let toplevel = PathBuf::from("/work/kast");
        let git_dir = PathBuf::from("/work/kast/.git/worktrees/kast");

        assert_eq!(
            git_worktree_hash(&toplevel, &git_dir),
            sha256_prefix("/work/kast\n/work/kast/.git/worktrees/kast"),
        );
    }

    #[test]
    fn parses_runtime_default_backend() {
        let temp = tempfile::tempdir().unwrap();
        let config_file = temp.path().join("config.toml");
        fs::write(
            &config_file,
            r#"[runtime]
defaultBackend = "headless"
"#,
        )
        .unwrap();

        let mut config = KastConfig::defaults();
        config.apply(read_partial_config(&config_file).unwrap());

        assert_eq!(config.runtime.default_backend, Some(BackendName::Headless));
    }

    #[test]
    fn rejects_invalid_runtime_default_backend() {
        let temp = tempfile::tempdir().unwrap();
        let config_file = temp.path().join("config.toml");
        fs::write(
            &config_file,
            r#"[runtime]
defaultBackend = "sidecar"
"#,
        )
        .unwrap();

        let error = read_partial_config(&config_file).unwrap_err();

        assert_eq!(error.code, "CONFIG_ERROR");
        assert!(error.message.contains("sidecar"), "{}", error.message);
        assert!(error.message.contains("headless"), "{}", error.message);
    }
}
