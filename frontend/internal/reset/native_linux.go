package reset

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
)

const systemctl = "/usr/bin/systemctl"

// nativeSupervisor is the user's systemd manager, where Kast starts transient units (LinuxPersistentBrokerServiceHost.kt).
type nativeSupervisor struct{ uid int }

func (s nativeSupervisor) environment() []string { return []string{"XDG_RUNTIME_DIR=" + s.runtime()} }

func (s nativeSupervisor) runtime() string { return filepath.Join("/run/user", strconv.Itoa(s.uid)) }

// Jobs reads each unit's main executable. A unit without a main process runs nothing that a reset must stop.
func (s nativeSupervisor) Jobs() ([]Job, error) {
	if !exists(filepath.Join(s.runtime(), "systemd", "private")) {
		return nil, nil
	}
	listing, err := observation(s.environment(), systemctl, "--user", "list-units", "--all", "--full", "--plain",
		"--no-legend", "--no-pager", labelPrefix+"*")
	if err != nil {
		return nil, err
	}
	var jobs []Job
	for line := range strings.Lines(listing) {
		fields := strings.Fields(line)
		if len(fields) == 0 || !strings.HasPrefix(fields[0], labelPrefix) {
			continue
		}
		main, err := observation(s.environment(), systemctl, "--user", "show", "--property=MainPID", "--value", fields[0])
		if err != nil {
			return nil, err
		}
		pid, err := strconv.Atoi(strings.TrimSpace(main))
		if err != nil {
			return nil, err
		}
		jobs = append(jobs, Job{fields[0], executable(pid)})
	}
	return jobs, nil
}

// Unload accepts every status: the caller observes whether the job remains.
func (s nativeSupervisor) Unload(label string) error {
	_, _, err := command(s.environment(), systemctl, "--user", "stop", "--no-block", label)
	return err
}

func (nativeProcesses) List() ([]Process, error) {
	listed, err := os.ReadDir("/proc")
	if err != nil {
		return nil, err
	}
	var processes []Process
	for _, entry := range listed {
		pid, err := strconv.Atoi(entry.Name())
		if err != nil {
			continue
		}
		directory := filepath.Join("/proc", entry.Name())
		info, err := os.Stat(directory)
		if err != nil {
			continue
		}
		stat, err := os.ReadFile(filepath.Join(directory, "stat"))
		if errors.Is(err, fs.ErrNotExist) || errors.Is(err, syscall.ESRCH) {
			continue
		}
		if err != nil {
			return nil, err
		}
		// The command name can hold any character, so the fields start after its last parenthesis.
		fields := strings.Fields(string(stat[strings.LastIndexByte(string(stat), ')')+1:]))
		if len(fields) < 2 {
			return nil, fmt.Errorf("%s/stat is malformed", directory)
		}
		parent, err := strconv.Atoi(fields[1])
		if err != nil {
			return nil, err
		}
		processes = append(processes, Process{pid, parent, owner(info), executable(pid)})
	}
	return processes, nil
}

// executable is the kernel's link to the running file, which survives that file's deletion with a suffix.
func executable(pid int) string {
	path, err := os.Readlink(filepath.Join("/proc", strconv.Itoa(pid), "exe"))
	if err != nil {
		return ""
	}
	return strings.TrimSuffix(path, " (deleted)")
}
