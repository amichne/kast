fn default_skill_target_dir() -> PathBuf {
    let cwd = env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
    for candidate in [
        ".agents/skills",
        ".codex/skills",
        ".github/skills",
        ".claude/skills",
    ] {
        let path = cwd.join(candidate);
        if path.is_dir() {
            return config::normalize(path);
        }
    }
    manifest::resolve_paths()
        .unwrap_or_else(|_| manifest::default_resolved_paths())
        .lib_dir
        .join("skills")
}

fn default_instructions_target_dir() -> PathBuf {
    let cwd = env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
    for candidate in [
        ".agents/instructions",
        ".codex/instructions",
        ".github/instructions",
        ".claude/instructions",
    ] {
        let path = cwd.join(candidate);
        if path.is_dir() {
            return config::normalize(path);
        }
    }
    manifest::resolve_paths()
        .unwrap_or_else(|_| manifest::default_resolved_paths())
        .lib_dir
        .join("instructions")
}

fn resource_repo_root(target: &Path) -> Option<PathBuf> {
    let start = if target.is_dir() {
        target
    } else {
        target.parent().unwrap_or(target)
    };
    git_repo_root(start)
}

fn git_repo_root(start: &Path) -> Option<PathBuf> {
    let output = ProcessCommand::new("git")
        .arg("-C")
        .arg(start)
        .args(["rev-parse", "--show-toplevel"])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let raw = String::from_utf8(output.stdout).ok()?;
    let raw = raw.trim();
    if raw.is_empty() {
        None
    } else {
        Some(PathBuf::from(raw).components().collect())
    }
}
