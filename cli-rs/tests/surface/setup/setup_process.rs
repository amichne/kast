const MAX_CONCURRENT_SETUP_PROCESSES: usize = 4;

struct SetupProcessLimiter {
    active: std::sync::Mutex<usize>,
    available: std::sync::Condvar,
    maximum: usize,
}

impl SetupProcessLimiter {
    fn new(maximum: usize) -> Self {
        assert!(maximum > 0, "setup process limit must be positive");
        Self {
            active: std::sync::Mutex::new(0),
            available: std::sync::Condvar::new(),
            maximum,
        }
    }

    fn acquire(self: &std::sync::Arc<Self>) -> SetupProcessPermit {
        let mut active = self.active.lock().expect("setup process limit");
        while *active >= self.maximum {
            active = self
                .available
                .wait(active)
                .expect("wait for setup process permit");
        }
        *active += 1;
        SetupProcessPermit {
            limiter: std::sync::Arc::clone(self),
        }
    }
}

struct SetupProcessPermit {
    limiter: std::sync::Arc<SetupProcessLimiter>,
}

impl Drop for SetupProcessPermit {
    fn drop(&mut self) {
        let mut active = self
            .limiter
            .active
            .lock()
            .expect("release setup process permit");
        *active = active
            .checked_sub(1)
            .expect("a setup permit must be active before release");
        drop(active);
        self.limiter.available.notify_one();
    }
}

fn setup_process_limiter() -> std::sync::Arc<SetupProcessLimiter> {
    static LIMITER: std::sync::OnceLock<std::sync::Arc<SetupProcessLimiter>> =
        std::sync::OnceLock::new();
    std::sync::Arc::clone(LIMITER.get_or_init(|| {
        std::sync::Arc::new(SetupProcessLimiter::new(MAX_CONCURRENT_SETUP_PROCESSES))
    }))
}

struct SetupCommand {
    command: Command,
    limiter: std::sync::Arc<SetupProcessLimiter>,
}

impl SetupCommand {
    fn new(command: Command) -> Self {
        Self::with_limiter(command, setup_process_limiter())
    }

    fn with_limiter(command: Command, limiter: std::sync::Arc<SetupProcessLimiter>) -> Self {
        Self { command, limiter }
    }

    fn arg<S: AsRef<std::ffi::OsStr>>(&mut self, arg: S) -> &mut Self {
        self.command.arg(arg);
        self
    }

    fn args<I, S>(&mut self, args: I) -> &mut Self
    where
        I: IntoIterator<Item = S>,
        S: AsRef<std::ffi::OsStr>,
    {
        self.command.args(args);
        self
    }

    fn env<K, V>(&mut self, key: K, value: V) -> &mut Self
    where
        K: AsRef<std::ffi::OsStr>,
        V: AsRef<std::ffi::OsStr>,
    {
        self.command.env(key, value);
        self
    }

    fn env_remove<K: AsRef<std::ffi::OsStr>>(&mut self, key: K) -> &mut Self {
        self.command.env_remove(key);
        self
    }

    fn current_dir<P: AsRef<Path>>(&mut self, directory: P) -> &mut Self {
        self.command.current_dir(directory);
        self
    }

    fn stdout<T: Into<std::process::Stdio>>(&mut self, configuration: T) -> &mut Self {
        self.command.stdout(configuration);
        self
    }

    fn stderr<T: Into<std::process::Stdio>>(&mut self, configuration: T) -> &mut Self {
        self.command.stderr(configuration);
        self
    }

    fn spawn(&mut self) -> std::io::Result<SetupChild> {
        let permit = self.limiter.acquire();
        self.command.spawn().map(|child| SetupChild {
            child: Some(child),
            permit: Some(permit),
        })
    }

    fn output(&mut self) -> std::io::Result<std::process::Output> {
        let _permit = self.limiter.acquire();
        self.command.output()
    }
}

struct SetupChild {
    child: Option<std::process::Child>,
    permit: Option<SetupProcessPermit>,
}

impl SetupChild {
    fn id(&self) -> u32 {
        self.child.as_ref().expect("live setup child").id()
    }

    fn try_wait(&mut self) -> std::io::Result<Option<std::process::ExitStatus>> {
        self.child.as_mut().expect("live setup child").try_wait()
    }

    fn wait_with_output(mut self) -> std::io::Result<std::process::Output> {
        self.child
            .take()
            .expect("live setup child")
            .wait_with_output()
    }
}

impl Drop for SetupChild {
    fn drop(&mut self) {
        if let Some(mut child) = self.child.take() {
            match child.try_wait() {
                Ok(Some(_)) => {}
                Ok(None) | Err(_) => {
                    let _ = child.kill();
                    let _ = child.wait();
                }
            }
        }
        drop(self.permit.take());
    }
}

#[test]
fn setup_subprocess_concurrency_is_bounded() {
    let limiter = std::sync::Arc::new(SetupProcessLimiter::new(1));
    let mut command = Command::new("sleep");
    command.arg("30");
    let child = SetupCommand::with_limiter(command, std::sync::Arc::clone(&limiter))
        .spawn()
        .expect("spawn setup-limit fixture");

    let (acquired, receiver) = std::sync::mpsc::channel();
    let waiting_limiter = std::sync::Arc::clone(&limiter);
    let waiter = std::thread::spawn(move || {
        let _permit = waiting_limiter.acquire();
        acquired.send(()).expect("report acquired permit");
    });
    assert!(
        receiver
            .recv_timeout(std::time::Duration::from_millis(100))
            .is_err(),
        "a second setup permit must remain blocked while the child is live",
    );

    drop(child);
    receiver
        .recv_timeout(std::time::Duration::from_secs(1))
        .expect("child drop must release its setup permit");
    waiter.join().expect("join setup permit waiter");
}
