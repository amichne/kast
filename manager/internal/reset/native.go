package reset

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"time"
)

// labelPrefix starts every launchd label and systemd unit name Kast publishes: SemanticServiceLaunch.kt and
// PersistentBrokerService.kt.
const labelPrefix = "io.github.amichne.kast."

const commandTimeout = 10 * time.Second

// Native resets the current user's installation beneath home.
func Native(home string, arguments []string, out io.Writer) error {
	temporary, err := filepath.EvalSymlinks("/tmp")
	if err != nil {
		return reject(RootRejected, "%v", err)
	}
	uid := os.Getuid()
	reset := &Reset{
		Home: home, UID: uid, PID: os.Getpid(), Temporary: temporary,
		Supervisor: nativeSupervisor{uid}, Processes: nativeProcesses{},
		Pause: time.Sleep, Now: time.Now, Out: out,
	}
	return reset.Run(arguments)
}

type nativeProcesses struct{}

func (nativeProcesses) Signal(pid int, signal syscall.Signal) error { return syscall.Kill(pid, signal) }

// command runs one supervisor command within a fixed bound and returns its output and exit status.
func command(environment []string, path string, arguments ...string) (string, int, error) {
	ctx, cancel := context.WithTimeout(context.Background(), commandTimeout)
	defer cancel()
	process := exec.CommandContext(ctx, path, arguments...)
	process.Env = environment
	output, err := process.Output()
	var exit *exec.ExitError
	if errors.As(err, &exit) && ctx.Err() == nil {
		return string(output), exit.ExitCode(), nil
	}
	if err != nil {
		return "", 0, fmt.Errorf("%s: %w", path, err)
	}
	return string(output), 0, nil
}

// observation runs a command whose output counts only when it exits with status 0.
func observation(environment []string, path string, arguments ...string) (string, error) {
	output, status, err := command(environment, path, arguments...)
	if err == nil && status != 0 {
		err = fmt.Errorf("%s %s: status %d", path, arguments[0], status)
	}
	return output, err
}
