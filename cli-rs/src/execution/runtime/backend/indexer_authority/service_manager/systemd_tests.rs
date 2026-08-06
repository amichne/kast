use super::*;
use std::collections::VecDeque;
use std::os::unix::process::ExitStatusExt as _;

fn properties(active: &str, sub: &str, pid: &str) -> BTreeMap<String, String> {
    BTreeMap::from([
        ("LoadState".to_string(), "loaded".to_string()),
        ("ActiveState".to_string(), active.to_string()),
        ("SubState".to_string(), sub.to_string()),
        ("MainPID".to_string(), pid.to_string()),
        ("FragmentPath".to_string(), "/tmp/kast.service".to_string()),
    ])
}

#[test]
fn systemd_state_requires_consistent_pid_and_substate() {
    assert_eq!(
        classify_properties(&properties("active", "running", "42")).unwrap(),
        ServiceManagerObservation::Running(42),
    );
    for state in [
        properties("active", "running", "0"),
        properties("inactive", "dead", "42"),
        properties("active", "exited", "42"),
    ] {
        assert!(classify_properties(&state).is_err());
    }
    let mut absent = properties("inactive", "dead", "0");
    absent.insert("LoadState".to_string(), "not-found".to_string());
    absent.insert("FragmentPath".to_string(), String::new());
    assert_eq!(
        classify_properties(&absent).unwrap(),
        ServiceManagerObservation::Absent
    );
    absent.insert("MainPID".to_string(), "42".to_string());
    assert!(classify_properties(&absent).is_err());
}

struct FakeSystemctl {
    outputs: VecDeque<std::process::Output>,
    calls: Vec<Vec<String>>,
}

impl SystemctlRunner for FakeSystemctl {
    fn output(&mut self, arguments: &[&str]) -> std::io::Result<std::process::Output> {
        self.calls
            .push(arguments.iter().map(|value| value.to_string()).collect());
        self.outputs.pop_front().ok_or_else(|| {
            std::io::Error::new(std::io::ErrorKind::UnexpectedEof, "missing fake output")
        })
    }
}

fn output(success: bool) -> std::process::Output {
    std::process::Output {
        status: std::process::ExitStatus::from_raw(if success { 0 } else { 256 }),
        stdout: vec![],
        stderr: if success {
            Vec::new()
        } else {
            b"injected failure".to_vec()
        },
    }
}

fn service_state(
    load: &str,
    active: &str,
    sub: &str,
    pid: &str,
    fragment: &str,
) -> std::process::Output {
    let mut result = output(true);
    result.stdout = format!(
        "LoadState={load}\nActiveState={active}\nSubState={sub}\nMainPID={pid}\nFragmentPath={fragment}\n"
    )
    .into_bytes();
    result
}

fn manager() -> ServiceManagerRegistration {
    ServiceManagerRegistration::SystemdUser {
        unit: "kast-indexer-test.service".to_string(),
        definition_path: "/tmp/kast-indexer-test.service".to_string(),
    }
}

#[test]
fn link_failure_does_not_unlink_an_unproven_unit() {
    let mut runner = FakeSystemctl {
        outputs: vec![output(true), output(false)].into(),
        calls: vec![],
    };

    assert!(register_with(&manager(), &mut runner).is_err());
    assert_eq!(runner.calls.len(), 2);
    assert!(runner.calls[0].contains(&"show-environment".to_string()));
    assert!(runner.calls[1].contains(&"link".to_string()));
}

#[test]
fn reload_failure_uses_supported_disable_for_proven_link_review_regression() {
    let mut runner = FakeSystemctl {
        outputs: vec![
            output(true),
            output(true),
            output(false),
            output(true),
            output(true),
        ]
        .into(),
        calls: vec![],
    };

    assert!(register_with(&manager(), &mut runner).is_err());
    assert_eq!(
        runner.calls[runner.calls.len() - 2],
        [
            "--user",
            "--no-pager",
            "disable",
            "kast-indexer-test.service",
        ]
    );
    assert_eq!(
        runner
            .calls
            .last()
            .and_then(|call| call.last())
            .map(String::as_str),
        Some("daemon-reload")
    );
}

#[test]
fn unregister_disables_an_exact_registered_link_review_regression() {
    let mut runner = FakeSystemctl {
        outputs: vec![
            service_state(
                "loaded",
                "inactive",
                "dead",
                "0",
                "/tmp/kast-indexer-test.service",
            ),
            output(true),
            service_state("not-found", "inactive", "dead", "0", ""),
        ]
        .into(),
        calls: vec![],
    };

    unregister_with(&manager(), &mut runner).unwrap();

    assert_eq!(
        runner.calls[1],
        [
            "--user",
            "--no-pager",
            "disable",
            "kast-indexer-test.service",
        ]
    );
    assert_eq!(runner.calls.len(), 3);
}

#[test]
fn unregister_of_an_absent_link_is_a_noop_review_regression() {
    let mut runner = FakeSystemctl {
        outputs: vec![service_state("not-found", "inactive", "dead", "0", "")].into(),
        calls: vec![],
    };

    unregister_with(&manager(), &mut runner).unwrap();

    assert_eq!(runner.calls.len(), 1);
    assert!(runner.calls[0].contains(&"show".to_string()));
}
