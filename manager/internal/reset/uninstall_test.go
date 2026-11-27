package reset

import (
	"errors"
	"maps"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

func (f *fixture) uninstall(settle func() error, arguments ...string) error {
	f.t.Helper()
	r := &Reset{
		Home: f.home, UID: os.Getuid(), PID: os.Getpid(), Temporary: f.temporary,
		Supervisor: f, Processes: scriptedProcesses{f}, Out: &f.out, remove: f.remove, Pause: func(time.Duration) {},
		Now: func() time.Time { return time.Date(2026, 11, 23, 10, 11, 12, 0, time.UTC) },
	}
	return r.Uninstall(arguments, settle)
}

func (f *fixture) unexpectedSettle() error {
	f.t.Error("unexpected settlement")
	return errors.New("unexpected settlement")
}

// clean lays out an installation without an unfinished upgrade.
func (f *fixture) clean() []string {
	targets := f.install()
	if err := os.RemoveAll(filepath.Join(f.root, "recovery", "replacement")); err != nil {
		f.t.Fatal(err)
	}
	bases := filepath.Join(f.share, "kast-runtime-bases")
	if err := os.Rename(filepath.Join(bases, "abc"), filepath.Join(bases, digest("base"))); err != nil {
		f.t.Fatal(err)
	}
	return targets
}

func TestAnUninstallSettlesHostsThenStopsBacksUpAndDeletes(t *testing.T) {
	f := newFixture(t)
	targets := f.clean()
	f.processes = []Process{{100, 1, os.Getuid(), f.java()}}
	var order []string
	f.signal[100] = func(syscall.Signal) {
		order = append(order, "signal")
		f.endProcess(100)
	}
	if err := f.uninstall(func() error { order = append(order, "settle"); return nil }); err != nil {
		t.Fatal(err)
	}
	if strings.Join(order, " ") != "settle signal" {
		t.Errorf("order %v", order)
	}
	f.requireAbsent(targets)
	f.requireForeign()
	if len(f.backups()) == 0 || !strings.Contains(f.out.String(), "Kast is removed.") {
		t.Errorf("backups %v, output %q", f.backups(), f.out.String())
	}
	if strings.Contains(f.out.String(), "{") {
		t.Errorf("output holds JSON: %q", f.out.String())
	}
}

func TestAHostThatDoesNotSettleKeepsEverything(t *testing.T) {
	f := newFixture(t)
	f.clean()
	before := f.snapshot()
	err := f.uninstall(func() error { return &Refused{"busy"} })
	requireFailure(t, err, StopRejected)
	if !strings.Contains(err.Error(), "busy") || !strings.Contains(err.Error(), "kast reset") {
		t.Errorf("%v", err)
	}
	if !maps.Equal(before, f.snapshot()) || len(f.signals) != 0 {
		t.Errorf("something changed; signals %v", f.signals)
	}
}

func TestAnUninstallRefusesWhatKastDidNotWriteOrFinish(t *testing.T) {
	for name, path := range map[string]string{
		"foreign in the root":   "kast/notes.txt",
		"foreign in the store":  "kast-workspaces/notes.txt",
		"foreign in the bases":  "kast-runtime-bases/notes.txt",
		"unfinished upgrade":    "kast/recovery/replacement/receipt.json",
		"unfinished retirement": "kast/shutdown.json",
	} {
		t.Run(name, func(t *testing.T) {
			f := newFixture(t)
			f.clean()
			f.write(filepath.Join(f.share, path), "x")
			before := f.snapshot()
			requireFailure(t, f.uninstall(f.unexpectedSettle), RootRejected)
			if !maps.Equal(before, f.snapshot()) {
				t.Error("something changed")
			}
		})
	}
}

func TestAnUninstallInsideKastIsRefusedBeforeSettlement(t *testing.T) {
	f := newFixture(t)
	f.clean()
	f.processes = []Process{{os.Getpid(), 50, os.Getuid(), "/bin/kast"}, {50, 1, os.Getuid(), f.java()}}
	requireFailure(t, f.uninstall(f.unexpectedSettle), StopRejected)
}

func TestNothingInstalledAndUsageNeedNoSettlement(t *testing.T) {
	f := newFixture(t)
	if err := f.uninstall(f.unexpectedSettle); err != nil || !strings.Contains(f.out.String(), InstallCommand) {
		t.Errorf("%v, output %q", err, f.out.String())
	}
	if err := f.uninstall(f.unexpectedSettle, "--help"); err != nil || !strings.Contains(f.out.String(), "kast uninstall") {
		t.Errorf("%v", err)
	}
	for _, argument := range []string{"--force", "--json", "--yes"} {
		requireFailure(t, f.uninstall(f.unexpectedSettle, argument), UsageRejected)
	}
}

func TestAnInstallationWithoutWorkspacesNeedsNoSettlement(t *testing.T) {
	f := newFixture(t)
	targets := f.clean()
	if err := os.RemoveAll(filepath.Join(f.share, "kast-workspaces", "containers")); err != nil {
		t.Fatal(err)
	}
	if err := f.uninstall(f.unexpectedSettle); err != nil {
		t.Fatal(err)
	}
	f.requireAbsent(targets[:1])
}
