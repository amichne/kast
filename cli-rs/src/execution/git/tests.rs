use super::ReadOnlyGitCommand;
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::time::{SystemTime, UNIX_EPOCH};

#[test]
fn read_only_git_command_disables_optional_locks_for_child_process() {
    let directory = std::env::temp_dir().join(format!(
        "kast-read-only-git-{}-{}",
        std::process::id(),
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("system time follows Unix epoch")
            .as_nanos()
    ));
    fs::create_dir_all(&directory).expect("fake Git directory is created");
    let fake_git = directory.join("git");
    fs::write(
        &fake_git,
        "#!/bin/sh\nprintf '%s' \"${GIT_OPTIONAL_LOCKS-unset}\"\n",
    )
    .expect("fake Git is written");
    fs::set_permissions(&fake_git, fs::Permissions::from_mode(0o700))
        .expect("fake Git is executable");

    let output = ReadOnlyGitCommand::with_executable(&fake_git)
        .output()
        .expect("fake Git runs");

    assert!(output.status.success());
    assert_eq!(String::from_utf8(output.stdout).unwrap(), "0");
    fs::remove_dir_all(directory).expect("fake Git directory is removed");
}
