// Package reset returns a user to "nothing installed" without reading an installation record, finding a runtime, or
// starting a JVM. It stops what runs from the installation, copies what the user would lose, and deletes the exact
// paths Kast creates beneath one home. Running it again finishes an interrupted reset.
package reset

import (
	"errors"
	"fmt"
	"io"
	"slices"
	"strings"
	"syscall"
	"time"
)

// InstallCommand is the documented installation command.
const InstallCommand = `/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"`

const usage = "Usage: kast reset [--yes]\n\n" +
	"Prints what Kast would stop and delete for this user. With --yes, stops and deletes it.\n" +
	"Keeps ~/.kast, and copies client configuration and workspace change evidence to a backup directory."

const notInstalled = "Kast is not installed for this user. To install it:\n  " + InstallCommand

// Failure is the closed set of reasons a reset does not end with nothing installed.
type Failure string

const (
	UsageRejected   Failure = "USAGE_REJECTED"
	HomeRejected    Failure = "HOME_REJECTED"
	RootRejected    Failure = "ROOT_REJECTED"
	StopRejected    Failure = "STOP_REJECTED"
	BackupRejected  Failure = "BACKUP_REJECTED"
	ResetIncomplete Failure = "RESET_INCOMPLETE"
)

// Rejection is one failure with the paths, jobs or processes that caused it.
type Rejection struct {
	Failure Failure
	Detail  string
}

func (r *Rejection) Error() string { return string(r.Failure) + ": " + r.Detail }

func reject(failure Failure, format string, values ...any) error {
	return &Rejection{failure, fmt.Sprintf(format, values...)}
}

// Job is one supervisor job with a Kast label. Program is empty when the supervisor does not prove it.
type Job struct{ Label, Program string }

// Process is one running process. Executable is the kernel's path, or empty when the kernel does not report it.
type Process struct {
	PID, Parent, UID int
	Executable       string
}

// Supervisor reads and unloads the user's launchd jobs or systemd user units.
type Supervisor interface {
	// Jobs returns the loaded jobs whose label has Kast's prefix.
	Jobs() ([]Job, error)
	// Unload asks the supervisor to stop one job and not start it again.
	Unload(label string) error
}

// Processes reads and signals processes.
type Processes interface {
	List() ([]Process, error)
	Signal(pid int, signal syscall.Signal) error
}

// Reset is one reset of one home. Every effect outside that home and Temporary goes through an injected adapter.
type Reset struct {
	Home       string
	UID, PID   int
	Temporary  string
	Supervisor Supervisor
	Processes  Processes
	Pause      func(time.Duration)
	Now        func() time.Time
	Out        io.Writer

	// remove deletes one inventoried path and returns what it left. Tests replace it to interrupt a deletion.
	remove func(path string) []string
}

const (
	pollInterval    = 250 * time.Millisecond
	terminatePolls  = 60 // 15 seconds: a Host saves its documents before it exits.
	killPolls       = 20
	supervisorPolls = 80
)

// Run admits the arguments, prints the plan, and performs it when the caller passed --yes.
func (r *Reset) Run(arguments []string) error {
	switch {
	case len(arguments) == 1 && (arguments[0] == "--help" || arguments[0] == "-h"):
		r.print(usage)
		return nil
	case len(arguments) == 0, len(arguments) == 1 && arguments[0] == "--yes":
	default:
		return reject(UsageRejected, "%s", usage)
	}
	paths, err := admitLayout(r.Home, r.Temporary, r.UID)
	if err != nil {
		return err
	}
	if r.remove == nil {
		r.remove = paths.removeTree
	}
	found, err := paths.inventory()
	if err != nil {
		return err
	}
	kept, err := paths.keepsakes(found)
	if err != nil {
		return err
	}
	running, err := r.observe(paths, found)
	if err != nil {
		return err
	}
	r.present(found, kept, running)
	if len(running.ancestors) > 0 {
		return reject(StopRejected, "this command runs inside Kast: %s. Run it from another terminal.",
			strings.Join(running.ancestors, ", "))
	}
	if len(found.targets) == 0 && len(running.lines()) == 0 {
		r.print(notInstalled)
		return nil
	}
	if len(arguments) == 0 {
		r.print("Nothing was changed. Run `kast reset --yes` to stop and delete this.")
		return nil
	}
	if err := r.perform(paths, found); err != nil {
		return err
	}
	r.print(notInstalled)
	return nil
}

// perform stops what runs, copies what the user would lose, and deletes the inventory.
func (r *Reset) perform(paths *layout, found *inventory) error {
	if err := r.stop(paths, found); err != nil {
		return err
	}
	if err := r.backup(paths, found); err != nil {
		return err
	}
	// The backup took time. A process that started meanwhile keeps everything in place.
	running, err := r.observe(paths, found)
	if err != nil {
		return err
	}
	if lines := running.lines(); len(lines) > 0 {
		return reject(StopRejected, "started during the reset: %s", strings.Join(lines, ", "))
	}
	var left []string
	for _, target := range found.targets {
		left = append(left, r.remove(target)...)
	}
	return r.finish(paths, left)
}

// activity is what currently runs from the installation.
type activity struct {
	jobs      []Job
	processes []Process
	ancestors []string
}

func (a activity) lines() []string {
	var lines []string
	for _, job := range a.jobs {
		lines = append(lines, "job "+job.Label+" ("+job.Program+")")
	}
	for _, process := range a.processes {
		lines = append(lines, fmt.Sprintf("process %d (%s)", process.PID, process.Executable))
	}
	return lines
}

// observe selects jobs by label and program path, and the user's processes by executable path. Command lines and
// working directories never select anything. This process and its ancestors are never selected.
func (r *Reset) observe(paths *layout, found *inventory) (activity, error) {
	var observed activity
	jobs, err := r.Supervisor.Jobs()
	if err != nil {
		return observed, reject(StopRejected, "the service supervisor did not list its jobs: %v", err)
	}
	owned := func(path string) bool {
		return slices.ContainsFunc(found.owned, func(root string) bool { return beneath(path, root) })
	}
	for _, job := range jobs {
		if owned(job.Program) {
			observed.jobs = append(observed.jobs, job)
		}
	}
	processes, err := r.Processes.List()
	if err != nil {
		return observed, reject(StopRejected, "the process list is unavailable: %v", err)
	}
	byPID := make(map[int]Process, len(processes))
	for _, process := range processes {
		byPID[process.PID] = process
	}
	own := map[int]bool{r.PID: true}
	for pid := byPID[r.PID].Parent; pid > 0 && !own[pid]; pid = byPID[pid].Parent {
		own[pid] = true
		if ancestor := byPID[pid]; owned(ancestor.Executable) {
			observed.ancestors = append(observed.ancestors,
				fmt.Sprintf("process %d (%s)", pid, ancestor.Executable))
		}
	}
	for _, process := range processes {
		if !own[process.PID] && process.PID > 1 && process.UID == paths.uid && owned(process.Executable) {
			observed.processes = append(observed.processes, process)
		}
	}
	return observed, nil
}

// stop unloads the jobs, ends the processes, and proves that neither remains.
func (r *Reset) stop(paths *layout, found *inventory) error {
	running, err := r.observe(paths, found)
	if err != nil {
		return err
	}
	for _, job := range running.jobs {
		if err := r.Supervisor.Unload(job.Label); err != nil {
			return reject(StopRejected, "job %s did not unload: %v", job.Label, err)
		}
	}
	for _, phase := range []struct {
		signal syscall.Signal
		polls  int
	}{{syscall.SIGTERM, terminatePolls}, {syscall.SIGKILL, killPolls}} {
		signaled := map[int]bool{}
		for poll := 0; ; poll++ {
			// Each signal follows a fresh observation, so a reused process identifier is never signaled.
			if running, err = r.observe(paths, found); err != nil {
				return err
			}
			if len(running.processes) == 0 || poll == phase.polls {
				break
			}
			for _, process := range running.processes {
				if signaled[process.PID] {
					continue
				}
				signaled[process.PID] = true
				if err := r.Processes.Signal(process.PID, phase.signal); err != nil && !errors.Is(err, syscall.ESRCH) {
					return reject(StopRejected, "process %d rejected %v: %v", process.PID, phase.signal, err)
				}
			}
			r.Pause(pollInterval)
		}
	}
	for poll := 0; len(running.jobs) > 0 && poll < supervisorPolls; poll++ {
		r.Pause(pollInterval)
		if running, err = r.observe(paths, found); err != nil {
			return err
		}
	}
	if lines := running.lines(); len(lines) > 0 {
		return reject(StopRejected, "still running: %s", strings.Join(lines, ", "))
	}
	return nil
}

// finish observes the result instead of trusting the deletions. left names what kept a target in place.
func (r *Reset) finish(paths *layout, left []string) error {
	found, err := paths.inventory()
	if err != nil {
		return err
	}
	running, err := r.observe(paths, found)
	if err != nil {
		return err
	}
	if remaining := slices.Concat(running.lines(), found.targets, left); len(remaining) > 0 {
		return reject(ResetIncomplete, "these remain; correct what is listed and run `kast reset --yes` again:\n  %s",
			strings.Join(remaining, "\n  "))
	}
	return nil
}

func (r *Reset) present(found *inventory, kept *keepsakes, running activity) {
	r.section("Stop", running.lines())
	r.section("Delete", found.targets)
	r.section("Copy to a backup directory", kept.files)
	r.section("Keep", found.kept)
	r.section("Skip", slices.Concat(found.skipped, kept.skipped))
}

func (r *Reset) section(title string, lines []string) {
	if len(lines) > 0 {
		r.print(title + ":\n  " + strings.Join(lines, "\n  "))
	}
}

func (r *Reset) print(text string) { fmt.Fprintln(r.Out, text) }
