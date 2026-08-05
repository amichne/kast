    #[test]
    fn shared_git_fixtures_map_to_exact_deterministic_global_paths() {
        let workspaces_root = PathBuf::from("/global/workspaces");
        for fixture in include_str!(
            "../../../../../../analysis-api/src/test/resources/workspace-git-layout-fixtures.tsv"
        )
        .lines()
        .filter(|line| !line.starts_with('#') && !line.is_empty())
        {
            let fields = fixture.split('\t').collect::<Vec<_>>();
            assert_eq!(fields.len(), 4, "invalid fixture: {fixture}");
            let workspace = GitWorkspace {
                toplevel: PathBuf::from(fields[0]),
                common_dir: PathBuf::from(fields[1]),
                git_dir: PathBuf::from(fields[2]),
            };

            assert_eq!(
                workspace_data_directory_for_git(&workspaces_root, &workspace)
                    .expect("workspace"),
                workspaces_root.join(fields[3]),
            );
        }
    }

    #[test]
    fn git_workspace_data_directory_uses_stable_common_directory_path() {
        let workspaces_root = PathBuf::from("/home/alex/.local/share/kast/state/workspaces");
        let workspace = GitWorkspace {
            toplevel: PathBuf::from("/work/kast"),
            common_dir: PathBuf::from("/work/kast/.git"),
            git_dir: PathBuf::from("/work/kast/.git"),
        };

        assert_eq!(
            workspace_data_directory_for_git(&workspaces_root, &workspace).expect("workspace"),
            workspaces_root.join(format!(
                "git/local/{}/worktrees/kast--{}",
                git_common_dir_hash(&workspace.common_dir),
                git_worktree_hash(&workspace.toplevel, &workspace.git_dir)
            )),
        );
    }

    #[test]
    fn git_workspace_data_directory_isolates_sibling_worktrees() {
        let workspaces_root = PathBuf::from("/home/alex/.local/share/kast/state/workspaces");
        let common_dir = PathBuf::from("/work/kast/.git");
        let first = GitWorkspace {
            toplevel: PathBuf::from("/work/kast"),
            common_dir: common_dir.clone(),
            git_dir: common_dir.clone(),
        };
        let second = GitWorkspace {
            toplevel: PathBuf::from("/work/kast-feature"),
            common_dir,
            git_dir: PathBuf::from("/work/kast/.git/worktrees/kast-feature"),
        };

        assert_ne!(
            workspace_data_directory_for_git(&workspaces_root, &first).expect("first"),
            workspace_data_directory_for_git(&workspaces_root, &second).expect("second"),
        );
    }

    #[test]
    fn git_workspace_data_directory_supports_git_without_origin() {
        let workspaces_root = PathBuf::from("/home/alex/.local/share/kast/state/workspaces");
        let workspace = GitWorkspace {
            toplevel: PathBuf::from("/work/private"),
            common_dir: PathBuf::from("/work/private/.git"),
            git_dir: PathBuf::from("/work/private/.git/worktrees/private"),
        };

        assert_eq!(
            workspace_data_directory_for_git(&workspaces_root, &workspace).expect("workspace"),
            workspaces_root.join(format!(
                "git/local/{}/worktrees/private--{}",
                git_common_dir_hash(&workspace.common_dir),
                git_worktree_hash(&workspace.toplevel, &workspace.git_dir)
            )),
        );
    }

    #[test]
    fn changing_or_removing_origin_does_not_change_git_workspace_directory() {
        let temp = tempfile::tempdir().expect("tempdir");
        let workspaces_root = temp.path().join("workspaces");
        let toplevel = temp.path().join("kast");
        fs::create_dir_all(&toplevel).expect("workspace");
        let git = |args: &[&str]| {
            let status = std::process::Command::new("git")
                .args(args)
                .current_dir(&toplevel)
                .status()
                .expect("git command");
            assert!(status.success(), "git command failed: {args:?}");
        };
        git(&["init", "--quiet"]);
        git(&["remote", "add", "origin", "https://github.com/amichne/kast.git"]);
        let initial =
            workspace_data_directory_from(&workspaces_root, &toplevel).expect("initial");

        git(&[
            "remote",
            "set-url",
            "origin",
            "https://git.example.com/fork/renamed.git",
        ]);
        assert_eq!(
            initial,
            workspace_data_directory_from(&workspaces_root, &toplevel)
                .expect("changed origin"),
        );

        git(&["remote", "remove", "origin"]);
        assert_eq!(
            initial,
            workspace_data_directory_from(&workspaces_root, &toplevel)
                .expect("without origin"),
        );
    }

    #[test]
    fn server_launch_args_include_a_live_linked_worktree_registration_claim() {
        let temp = tempfile::tempdir().expect("tempdir");
        let (worktree, git_directory) = create_linked_worktree(temp.path());

        let launch_args = server_launch_args(
            &daemon_start_args(&worktree),
            &KastConfig::defaults(),
        )
        .expect("launch arguments");

        assert!(launch_args.contains(&format!(
            "--linked-worktree-git-file={}",
            worktree.join(".git").display()
        )));
        assert!(launch_args.contains(&format!(
            "--linked-worktree-git-directory={}",
            git_directory.display()
        )));
    }

    #[test]
    fn server_launch_args_omit_a_linked_worktree_claim_for_a_mismatched_backlink() {
        let temp = tempfile::tempdir().expect("tempdir");
        let (worktree, git_directory) = create_linked_worktree(temp.path());
        let forged_git_file = temp.path().join("forged/.git");
        fs::create_dir_all(forged_git_file.parent().expect("forged parent"))
            .expect("forged parent directory");
        fs::write(&forged_git_file, "forged\n").expect("forged Git file");
        fs::write(
            git_directory.join("gitdir"),
            format!("{}\n", forged_git_file.display()),
        )
        .expect("mismatched backlink");

        let launch_args = server_launch_args(
            &daemon_start_args(&worktree),
            &KastConfig::defaults(),
        )
        .expect("launch arguments");

        assert!(!launch_args.iter().any(|argument| {
            argument.starts_with("--linked-worktree-git-file=")
                || argument.starts_with("--linked-worktree-git-directory=")
        }));
    }

    #[test]
    fn server_launch_args_omit_an_unregistered_bidirectional_filesystem_claim() {
        let temp = tempfile::tempdir().expect("tempdir");
        let (_, registered_git_directory) = create_linked_worktree(temp.path());
        let common_git_directory = registered_git_directory
            .parent()
            .and_then(Path::parent)
            .expect("common Git directory");
        let forged_worktree = temp.path().join("forged-worktree");
        let forged_git_file = forged_worktree.join(".git");
        let forged_git_directory = common_git_directory.join("worktrees/forged-worktree");
        fs::create_dir(&forged_worktree).expect("forged worktree directory");
        fs::create_dir(&forged_git_directory).expect("forged Git directory");
        fs::write(
            &forged_git_file,
            format!("gitdir: {}\n", forged_git_directory.display()),
        )
        .expect("forged Git file");
        fs::write(
            forged_git_directory.join("gitdir"),
            format!("{}\n", forged_git_file.display()),
        )
        .expect("forged backlink");

        let launch_args = server_launch_args(
            &daemon_start_args(&forged_worktree),
            &KastConfig::defaults(),
        )
        .expect("launch arguments");

        assert!(!launch_args.iter().any(|argument| {
            argument.starts_with("--linked-worktree-git-file=")
                || argument.starts_with("--linked-worktree-git-directory=")
        }));
    }

    #[test]
    fn server_launch_args_ignore_poisoned_repository_selection_environment() {
        const PROBE_WORKSPACE: &str = "KAST_TEST_LINKED_CLAIM_WORKSPACE";
        if let Some(workspace) = std::env::var_os(PROBE_WORKSPACE) {
            let launch_args = server_launch_args(
                &daemon_start_args(Path::new(&workspace)),
                &KastConfig::defaults(),
            )
            .expect("launch arguments");
            assert!(!launch_args.iter().any(|argument| {
                argument.starts_with("--linked-worktree-git-file=")
                    || argument.starts_with("--linked-worktree-git-directory=")
            }));
            return;
        }

        let temp = tempfile::tempdir().expect("tempdir");
        let (_, registered_git_directory) = create_linked_worktree(temp.path());
        let fixture_root = fs::canonicalize(temp.path()).expect("canonical fixture root");
        let forged_worktree = fixture_root.join("forged-worktree");
        let forged_git_file = forged_worktree.join(".git");
        let common_git_directory = registered_git_directory
            .parent()
            .and_then(Path::parent)
            .expect("common Git directory");
        let forged_git_directory = common_git_directory.join("worktrees/poisoned-forgery");
        fs::create_dir(&forged_worktree).expect("forged worktree directory");
        fs::create_dir(&forged_git_directory).expect("forged Git directory");
        fs::write(
            &forged_git_file,
            format!("gitdir: {}\n", forged_git_directory.display()),
        )
        .expect("forged Git file");
        fs::write(
            forged_git_directory.join("gitdir"),
            format!("{}\n", forged_git_file.display()),
        )
        .expect("forged backlink");
        fs::copy(
            registered_git_directory.join("HEAD"),
            forged_git_directory.join("HEAD"),
        )
        .expect("forged Git HEAD");
        let output = std::process::Command::new(std::env::current_exe().expect("test executable"))
            .args([
                "--exact",
                "config::tests::server_launch_args_ignore_poisoned_repository_selection_environment",
                "--nocapture",
            ])
            .env(PROBE_WORKSPACE, &forged_worktree)
            .env("GIT_DIR", &forged_git_directory)
            .env("GIT_WORK_TREE", &forged_worktree)
            .env("GIT_COMMON_DIR", common_git_directory)
            .output()
            .expect("poisoned Git environment probe");

        assert!(
            output.status.success(),
            "poisoned Git environment emitted a launch claim:\nstdout:\n{}\nstderr:\n{}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
    }

    fn create_linked_worktree(root: &Path) -> (PathBuf, PathBuf) {
        let root = fs::canonicalize(root).expect("canonical fixture root");
        let primary = root.join("primary");
        let worktree = root.join("linked");
        fs::create_dir(&primary).expect("primary directory");
        let git = |working_directory: &Path, args: &[&str]| {
            let status = std::process::Command::new("git")
                .args(args)
                .current_dir(working_directory)
                .status()
                .expect("git command");
            assert!(status.success(), "git command failed: {args:?}");
        };
        git(&primary, &["init", "--quiet"]);
        git(&primary, &["config", "user.email", "fixture@example.test"]);
        git(&primary, &["config", "user.name", "Fixture"]);
        git(&primary, &["commit", "--quiet", "--allow-empty", "-m", "base"]);
        git(
            &primary,
            &[
                "worktree",
                "add",
                "--quiet",
                "-b",
                "fixture-linked",
                worktree.to_str().expect("UTF-8 worktree path"),
            ],
        );

        let git_file = fs::read_to_string(worktree.join(".git")).expect("Git file");
        let git_directory = PathBuf::from(
            git_file
                .trim()
                .strip_prefix("gitdir: ")
                .expect("Git directory directive"),
        );
        (worktree, git_directory)
    }

    fn daemon_start_args(workspace_root: &Path) -> DaemonStartArgs {
        DaemonStartArgs {
            workspace_root: Some(workspace_root.to_path_buf()),
            runtime_libs_dir: None,
            idea_home: None,
            socket_path: None,
            module_name: None,
            source_roots: None,
            classpath: None,
            request_timeout_ms: None,
            max_results: None,
            max_concurrent_requests: None,
            stdio: false,
            profile: false,
            profile_modes: None,
            profile_duration: None,
            profile_otlp_endpoint: None,
        }
    }
