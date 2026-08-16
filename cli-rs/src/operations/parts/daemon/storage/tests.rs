#[cfg(unix)]
#[test]
fn cross_process_storage_owner_returns_typed_collision() {
    let temp = tempfile::tempdir().unwrap();
    let workspace = temp.path().join("workspace");
    fs::create_dir(&workspace).unwrap();
    let mut config = KastConfig::defaults();
    config.paths.cache_dir = temp.path().join("cache");
    let layout = IndexerProjectLayout::for_workspace(&workspace, &config).unwrap();
    let ready = temp.path().join("lock-ready");
    let program = "import fcntl,pathlib,sys,time; f=open(sys.argv[1],'a+'); fcntl.lockf(f,fcntl.LOCK_EX); pathlib.Path(sys.argv[2]).write_text('ready'); time.sleep(30)";
    let mut owner = Command::new("python3")
        .arg("-c")
        .arg(program)
        .arg(layout.storage_lease_file.display().to_string())
        .arg(ready.display().to_string())
        .spawn()
        .unwrap();
    for _ in 0..200 {
        if ready.is_file() {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(10));
    }

    let collision = IndexerStorageAvailabilityProbe::acquire(&layout);

    let _ = owner.kill();
    let _ = owner.wait();
    assert!(ready.is_file(), "cross-process lock holder did not start");
    let collision = collision.expect_err("live owner must exclude replacement");
    assert_eq!(collision.code, "INDEXER_STORAGE_IN_USE");
}

#[cfg(unix)]
#[test]
fn admitted_storage_owner_rejects_matching_candidate_when_another_pid_holds_lease() {
    let temp = tempfile::tempdir().unwrap();
    let workspace = temp.path().join("workspace");
    fs::create_dir(&workspace).unwrap();
    let mut config = KastConfig::defaults();
    config.paths.cache_dir = temp.path().join("cache");
    let layout = IndexerProjectLayout::for_workspace(&workspace, &config).unwrap();
    let ready = temp.path().join("holder-ready");
    let holder_program = "import fcntl,pathlib,sys,time; f=open(sys.argv[1],'a+'); fcntl.lockf(f,fcntl.LOCK_EX); pathlib.Path(sys.argv[2]).write_text('ready'); time.sleep(30)";
    let mut holder = Command::new("python3")
        .arg("-c")
        .arg(holder_program)
        .arg(&layout.storage_lease_file)
        .arg(&ready)
        .spawn()
        .unwrap();
    for _ in 0..200 {
        if ready.is_file() {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    let root_argument = format!(
        "--workspace-root={}",
        layout.identity.workspace_root().display(),
    );
    let storage_argument = format!(
        "--indexer-storage-root={}",
        layout.identity.storage_root().display(),
    );
    let mut candidate = Command::new("sh")
        .args([
            "-c",
            "while :; do sleep 1; done",
            INDEXER_STARTER_COMMAND,
            &root_argument,
            &storage_argument,
            "--storage-lease-fd=9",
        ])
        .spawn()
        .unwrap();

    let result = require_admitted_storage_owner(&layout.identity, candidate.id());

    let _ = candidate.kill();
    let _ = candidate.wait();
    let _ = holder.kill();
    let _ = holder.wait();
    let error = result.expect_err("matching argv must not substitute for exact lock ownership");
    assert_eq!(error.code, "INDEXER_STORAGE_OWNER_UNVERIFIED");
    assert!(error.message.contains(&holder.id().to_string()), "{error:?}");
}

#[cfg(unix)]
#[test]
fn inherited_storage_lease_excludes_replacement_through_the_child_lifetime() {
    let temp = tempfile::tempdir().unwrap();
    let workspace = temp.path().join("workspace");
    fs::create_dir(&workspace).unwrap();
    let mut config = KastConfig::defaults();
    config.paths.cache_dir = temp.path().join("cache");
    let layout = IndexerProjectLayout::for_workspace(&workspace, &config).unwrap();
    let bootstrap = temp.path().join("bootstrap-ready");
    let continue_lifetime = temp.path().join("continue-lifetime");
    let lifetime = temp.path().join("lifetime-ready");
    let program = "import os,pathlib,sys,time; lease=sys.argv[1]; bootstrap=pathlib.Path(sys.argv[2]); gate=pathlib.Path(sys.argv[3]); lifetime=pathlib.Path(sys.argv[4]); inherited=int(next(value for value in sys.argv[5:] if value.startswith('--storage-lease-fd=')).split('=',1)[1]); assert os.fstat(inherited).st_ino == os.stat(lease).st_ino; time.sleep(0.25); bootstrap.write_text('ready'); deadline=time.time()+10; exec('while not gate.exists() and time.time() < deadline:\\n time.sleep(0.01)'); assert gate.exists(); lifetime.write_text('ready'); time.sleep(30)";
    let mut command = Command::new("python3");
    command
        .arg("-c")
        .arg(program)
        .arg(&layout.storage_lease_file)
        .arg(&bootstrap)
        .arg(&continue_lifetime)
        .arg(&lifetime)
        .arg(INDEXER_STARTER_COMMAND)
        .arg(format!(
            "--workspace-root={}",
            layout.identity.workspace_root().display(),
        ))
        .arg(format!(
            "--indexer-storage-root={}",
            layout.identity.storage_root().display(),
        ));
    let mut probe = IndexerStorageAvailabilityProbe::acquire(&layout).unwrap();
    probe.arm_child_process(&mut command).unwrap();
    let mut child = command.spawn().unwrap();
    assert_eq!(
        unsafe { libc::getsid(child.id() as libc::pid_t) },
        child.id() as libc::pid_t,
        "lease-owned child must have an independent session",
    );
    drop(probe);
    let collision_before_bootstrap = IndexerStorageAvailabilityProbe::acquire(&layout).is_err();
    for _ in 0..200 {
        if bootstrap.is_file() {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    let collision_after_bootstrap = IndexerStorageAvailabilityProbe::acquire(&layout).is_err();
    fs::write(&continue_lifetime, b"continue").unwrap();
    for _ in 0..200 {
        if lifetime.is_file() {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    let collision_during_lifetime = IndexerStorageAvailabilityProbe::acquire(&layout).is_err();
    let admitted_owner = require_admitted_storage_owner(&layout.identity, child.id());

    let _ = child.kill();
    let _ = child.wait();
    assert!(
        collision_before_bootstrap,
        "the inherited lease did not cover pre-bootstrap initialization",
    );
    assert!(
        bootstrap.is_file(),
        "child did not reach bootstrap under the inherited lease",
    );
    assert!(
        collision_after_bootstrap,
        "the inherited lease did not remain exclusive after bootstrap",
    );
    assert!(lifetime.is_file(), "child did not reach its lifetime phase");
    assert!(
        collision_during_lifetime,
        "the inherited lease was not exclusive for the child lifetime",
    );
    admitted_owner.expect("lease-owned exact process must be reusable");
    IndexerStorageAvailabilityProbe::acquire(&layout)
        .expect("child exit must release the lifetime lease");
}

#[test]
fn bootstrap_timeout_kills_and_reaps_only_the_spawned_child() {
    let temp = tempfile::tempdir().unwrap();
    let workspace = temp.path().join("workspace");
    fs::create_dir(&workspace).unwrap();
    let mut config = KastConfig::defaults();
    config.paths.cache_dir = temp.path().join("cache");
    let layout = IndexerProjectLayout::for_workspace(&workspace, &config).unwrap();
    let token = IndexerBootstrapToken::new();
    let mut child = Command::new("sh")
        .args(["-c", "while :; do sleep 1; done"])
        .spawn()
        .unwrap();

    let error = wait_for_indexer_bootstrap(
        &mut child,
        &layout,
        token,
        crate::runtime::RuntimeStartDeadline::after_millis(50),
    )
    .expect_err("missing bootstrap receipt must time out");

    assert_eq!(error.code, "INDEXER_BOOTSTRAP_TIMEOUT");
    assert!(child.try_wait().unwrap().is_some(), "timed-out child was not reaped");
    assert!(!token.receipt_file(&layout).exists());
}

#[test]
fn sibling_physical_roots_have_disjoint_writable_layouts() {
    let temp = tempfile::tempdir().unwrap();
    let main = temp.path().join("main");
    let linked = temp.path().join("linked");
    fs::create_dir(&main).unwrap();
    fs::create_dir(&linked).unwrap();
    let mut config = KastConfig::defaults();
    config.paths.cache_dir = temp.path().join("cache");

    let main_layout = IndexerProjectLayout::for_workspace(&main, &config).unwrap();
    let linked_layout = IndexerProjectLayout::for_workspace(&linked, &config).unwrap();

    assert_ne!(main_layout.project_identity_directory, linked_layout.project_identity_directory);
    assert_ne!(main_layout.gradle_project_cache_directory, linked_layout.gradle_project_cache_directory);
    assert_ne!(main_layout.storage_lease_file, linked_layout.storage_lease_file);
    assert_ne!(main_layout.identity.storage_root(), linked_layout.identity.storage_root());
    assert!(!main.join(".idea").exists());
    assert!(!linked.join(".idea").exists());
}

#[test]
fn real_git_linked_worktree_has_disjoint_indexer_and_analysis_identity() {
    let temp = tempfile::tempdir().unwrap();
    let main = temp.path().join("main");
    let linked = temp.path().join("linked");
    fs::create_dir(&main).unwrap();
    let git = |directory: &Path, arguments: &[&str]| {
        let status = Command::new("git")
            .args(arguments)
            .current_dir(directory)
            .status()
            .unwrap();
        assert!(status.success(), "git command failed: {arguments:?}");
    };
    git(&main, &["init", "--quiet"]);
    git(&main, &["config", "user.name", "Kast Test"]);
    git(&main, &["config", "user.email", "kast@example.invalid"]);
    fs::write(main.join("settings.gradle.kts"), "rootProject.name = \"fixture\"\n").unwrap();
    git(&main, &["add", "settings.gradle.kts"]);
    git(&main, &["commit", "--quiet", "-m", "fixture"]);
    git(
        &main,
        &["worktree", "add", "--quiet", "-b", "linked", linked.to_str().unwrap()],
    );
    let mut config = KastConfig::defaults();
    config.paths.cache_dir = temp.path().join("cache");

    let main_layout = IndexerProjectLayout::for_workspace(&main, &config).unwrap();
    let linked_layout = IndexerProjectLayout::for_workspace(&linked, &config).unwrap();
    let main_manifest: IndexerLaunchManifestDocument =
        serde_json::from_slice(&fs::read(&main_layout.launch_manifest_file).unwrap()).unwrap();
    let linked_manifest: IndexerLaunchManifestDocument =
        serde_json::from_slice(&fs::read(&linked_layout.launch_manifest_file).unwrap()).unwrap();

    assert_ne!(main_layout.identity, linked_layout.identity);
    assert_ne!(main_layout.project_identity_directory, linked_layout.project_identity_directory);
    assert_ne!(main_layout.gradle_project_cache_directory, linked_layout.gradle_project_cache_directory);
    assert_ne!(main_layout.storage_lease_file, linked_layout.storage_lease_file);
    assert_ne!(
        main_manifest.workspace_data_directory,
        linked_manifest.workspace_data_directory,
    );
    assert_eq!(
        main_manifest.repository_data_directory,
        linked_manifest.repository_data_directory,
    );
    assert!(!linked.join(".idea").exists());
}

#[cfg(unix)]
#[test]
fn storage_identity_rejects_a_child_symlink_into_source() {
    let temp = tempfile::tempdir().unwrap();
    let workspace = temp.path().join("workspace");
    let cache = temp.path().join("cache");
    fs::create_dir(&workspace).unwrap();
    let mut config = KastConfig::defaults();
    config.paths.cache_dir = cache;
    let identity = IndexerStorageIdentity::resolve(&workspace, &config).unwrap();
    std::os::unix::fs::symlink(&workspace, identity.storage_root().join("idea-system")).unwrap();

    let error = IndexerProjectLayout::for_workspace(&workspace, &config).unwrap_err();

    assert_eq!(error.code, "INDEXER_STORAGE_LAYOUT_INVALID");
}

#[cfg(target_os = "macos")]
#[test]
fn macos_process_scan_prefilters_before_exact_argument_reads() {
    let workspace = Path::new("/tmp/worktree with spaces");
    let output = "\
101 501 /usr/bin/java unrelated.Main --workspace-root=/tmp/worktree with spaces\n\
102 501 /usr/bin/java io.github.amichne.kast.indexer.KastIndexerMainKt --workspace-root=/tmp/other\n\
103 777 /usr/bin/java io.github.amichne.kast.indexer.KastIndexerMainKt --workspace-root=/tmp/worktree with spaces\n\
104 501 /usr/bin/java io.github.amichne.kast.indexer.KastIndexerMainKt --workspace-root=/tmp/worktree with spaces\n\
105 501 /bin/sh kast-indexer --workspace-root=/tmp/worktree with spaces\n";

    assert_eq!(
        macos_process_candidates(output, 501, workspace),
        vec![104, 105],
    );
}
