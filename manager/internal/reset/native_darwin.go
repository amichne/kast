package reset

import (
	"bytes"
	"fmt"
	"strconv"
	"strings"
	"syscall"
	"unsafe"
)

const (
	launchctl = "/bin/launchctl"
	// launchctlUnknown is launchctl's status for a service that is not loaded.
	launchctlUnknown = 113
)

// nativeSupervisor is the user's launchd GUI domain, where Kast bootstraps its jobs.
type nativeSupervisor struct{ uid int }

func (s nativeSupervisor) target(label string) string { return fmt.Sprintf("gui/%d/%s", s.uid, label) }

func (s nativeSupervisor) Jobs() ([]Job, error) {
	listing, err := observation(nil, launchctl, "list")
	if err != nil {
		return nil, err
	}
	var jobs []Job
	for _, label := range parseLabels(listing) {
		definition, status, err := command(nil, launchctl, "print", s.target(label))
		switch {
		case err != nil:
			return nil, err
		case status == launchctlUnknown:
		case status != 0:
			return nil, fmt.Errorf("launchctl print %s: status %d", label, status)
		default:
			jobs = append(jobs, Job{label, parseProgram(definition)})
		}
	}
	return jobs, nil
}

// Unload accepts every status: the caller observes whether the job remains.
func (s nativeSupervisor) Unload(label string) error {
	_, _, err := command(nil, launchctl, "bootout", s.target(label))
	return err
}

// List reads identity from ps and each executable path from the kernel, because ps reports argv[0].
func (nativeProcesses) List() ([]Process, error) {
	listing, err := observation(nil, "/bin/ps", "-axo", "pid=,ppid=,uid=")
	if err != nil {
		return nil, err
	}
	var processes []Process
	for line := range strings.Lines(listing) {
		var numbers [3]int
		fields := strings.Fields(line)
		if len(fields) != len(numbers) {
			return nil, fmt.Errorf("ps: unexpected line %q", line)
		}
		for i, field := range fields {
			if numbers[i], err = strconv.Atoi(field); err != nil {
				return nil, err
			}
		}
		processes = append(processes, Process{numbers[0], numbers[1], numbers[2], executable(numbers[0])})
	}
	return processes, nil
}

// executable is proc_pidpath(3), without cgo. An exited process has no path.
func executable(pid int) string {
	const (
		procInfo        = 336
		procInfoPIDInfo = 2
		procPIDPathInfo = 11
	)
	buffer := make([]byte, 4096)
	if _, _, failure := syscall.Syscall6(procInfo, procInfoPIDInfo, uintptr(pid), procPIDPathInfo, 0,
		uintptr(unsafe.Pointer(&buffer[0])), uintptr(len(buffer))); failure != 0 {
		return ""
	}
	path, _, _ := bytes.Cut(buffer, []byte{0})
	return string(path)
}
