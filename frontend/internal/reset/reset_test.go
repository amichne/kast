package reset

import (
	"bytes"
	"errors"
	"fmt"
	"io/fs"
	"maps"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"slices"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"
)

const (
	transaction = "f6321576-8633-4827-adf1-33557355f5d0"
	container   = "1f17a8bb10e901daaad54571279f35c8679199a0b3f2aa227076a72f2b788e6e"
	evidence    = ".kast/state/workspaces/0000/mutation.sqlite"
)

// A child of this test is a copy of the test binary that only waits.
func TestMain(m *testing.M) {
	if os.Getenv("KAST_RESET_CHILD") != "" {
		time.Sleep(time.Minute)
		os.Exit(0)
	}
	status := m.Run()
	if buildDirectory != "" {
		os.RemoveAll(buildDirectory)
	}
	os.Exit(status)
}

var buildDirectory string

// build is one real build of the public command.
var build = sync.OnceValues(func() (path string, err error) {
	if buildDirectory, err = os.MkdirTemp("", "kast-reset-test"); err != nil {
		return "", err
	}
	path = filepath.Join(buildDirectory, "kast")
	if output, err := exec.Command("go", "build", "-trimpath", "-o", path, "../../cmd/kast").CombinedOutput(); err != nil {
		return "", fmt.Errorf("%w: %s", err, output)
	}
	return path, nil
})

// placeBuild puts the real command at path.
func (f *fixture) placeBuild(path string) {
	f.t.Helper()
	built, err := build()
	if err != nil {
		f.t.Fatal(err)
	}
	if err := os.Remove(path); err != nil && !errors.Is(err, fs.ErrNotExist) {
		f.t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		f.t.Fatal(err)
	}
	if err := os.Link(built, path); err != nil {
		f.t.Fatal(err)
	}
}

// fixture is one home with scripted supervisor and process adapters that reject unexpected effects.
type fixture struct {
	t                          *testing.T
	home, share, temporary     string
	root, installation, public string
	jobs                       []Job
	processes                  []Process
	// unload and signal script the permitted effects. A label or process without an entry fails the test.
	unload  map[string]func()
	signal  map[int]func(syscall.Signal)
	signals []string
	out     bytes.Buffer
	remove  func(string) []string
	pause   func()
}

func newFixture(t *testing.T) *fixture {
	t.Helper()
	base, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	f := &fixture{t: t, home: filepath.Join(base, "home"), temporary: filepath.Join(base, "tmp"),
		unload: map[string]func(){}, signal: map[int]func(syscall.Signal){}}
	f.share = filepath.Join(f.home, ".local/share")
	f.root = filepath.Join(f.share, "kast")
	f.installation = filepath.Join(f.root, "installation")
	f.public = filepath.Join(f.home, ".local/bin/kast")
	for _, directory := range []string{f.home, f.temporary} {
		if err := os.MkdirAll(directory, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	// The testing package cannot delete a directory that a case left unreadable.
	t.Cleanup(func() {
		filepath.WalkDir(base, func(path string, entry fs.DirEntry, _ error) error {
			if entry != nil && entry.IsDir() {
				os.Chmod(path, 0o700)
			}
			return nil
		})
	})
	return f
}

func (f *fixture) write(path, content string) {
	f.t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		f.t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		f.t.Fatal(err)
	}
}

func (f *fixture) link(target, path string) {
	f.t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		f.t.Fatal(err)
	}
	if err := os.Symlink(target, path); err != nil {
		f.t.Fatal(err)
	}
}

func (f *fixture) profile() string {
	return filepath.Join(f.share, "kast-workspaces/containers", container, "state/home")
}

func (f *fixture) generation() string {
	return filepath.Join(f.share, "kast-workspaces/containers", container, "generations", transaction, "state/home")
}

func (f *fixture) agent(digest string) string {
	return filepath.Join(f.home, "Library/LaunchAgents/io.github.amichne.kast.broker."+strings.Repeat(digest, 32)+".login.plist")
}

// install lays out everything Kast creates, beside foreign entries that must survive. It returns the deletion targets.
func (f *fixture) install() []string {
	f.write(filepath.Join(f.installation, "share/kast/libexec/kast-management"), "payload")
	f.write(f.public, "payload")
	f.write(filepath.Join(f.root, "management.json"), "{}")
	f.write(filepath.Join(f.installation, "config/environment"), "installed configuration")
	f.write(filepath.Join(f.installation, "config/workspaces/abc/environment"), "overlay")
	f.write(filepath.Join(f.root, "recovery/replacement/payload/config/environment"), "previous configuration")
	f.write(filepath.Join(f.share, ".kast-install-"+transaction, "config/environment"), "staged configuration")
	f.write(filepath.Join(f.share, "kast-workspaces/runtimes/527/host/bin/java"), "java")
	f.write(filepath.Join(f.share, "kast-runtime-bases/abc/base"), "base")
	f.write(filepath.Join(f.profile(), evidence), "unsettled change")
	f.write(filepath.Join(f.generation(), evidence), "retired change")
	f.write(filepath.Join(filepath.Dir(filepath.Dir(f.profile())), "container.json"), "workspace")
	f.write(filepath.Join(f.share, ".kast-prepare-abc_1234/receipt.json"), "{}")
	f.write(filepath.Join(f.share, ".kast-uninstall-"+digest(f.root)+".json"), "{}")
	f.write(filepath.Join(f.home, ".local/bin/.kast-managed-"+transaction+"-command"), "payload")
	f.write(f.agent("a"), "<string>"+f.installation+"/share/kast/libexec/kast-daemon</string>")
	run := filepath.Join(f.installation, "state/run")
	f.write(filepath.Join(run, "endpoint-alias.json"), "{}")
	alias := filepath.Join(f.temporary, "kast-uds-"+digest(run)[:32])
	f.link(run, alias)
	codex := filepath.Join(f.temporary, "kast-codex-"+digest(run)[:32])
	f.write(filepath.Join(codex, "u.sock"), "")
	endpoint := filepath.Join(f.temporary, "kast-"+digest(f.profile())[:32])
	f.write(filepath.Join(endpoint, "h/abc/endpoint.json"), "{}")

	// The installer makes immutable directories read-only, and a payload can hold a link that leads outside.
	if err := os.Chmod(filepath.Join(f.share, "kast-runtime-bases/abc"), 0o500); err != nil {
		f.t.Fatal(err)
	}
	f.write(filepath.Join(f.home, "project/Main.kt"), "source")
	f.link(filepath.Join(f.home, "project"), filepath.Join(f.installation, "state/project"))
	for _, path := range f.foreign() {
		f.write(path, "foreign")
	}
	return []string{f.agent("a"), alias, codex, endpoint, f.root, filepath.Join(f.share, "kast-workspaces"),
		filepath.Join(f.share, "kast-runtime-bases"), filepath.Join(f.share, ".kast-install-"+transaction),
		filepath.Join(f.share, ".kast-prepare-abc_1234"), filepath.Join(f.share, ".kast-uninstall-"+digest(f.root)+".json"),
		filepath.Join(f.home, ".local/bin/.kast-managed-"+transaction+"-command"), f.public}
}

// foreign are entries a reset must not touch: user data, near misses of Kast's names, and other installations.
func (f *fixture) foreign() []string {
	return []string{
		filepath.Join(f.home, ".kast/state/workspaces/1/mutation.sqlite"),
		filepath.Join(f.home, ".local/bin/kast-tool"),
		filepath.Join(f.share, "kast-local/pr-1/file"),
		filepath.Join(f.share, "kast-notes/file"),
		filepath.Join(f.share, ".kast-install-notes"),
		filepath.Join(f.share, ".kast-workspaces.lifetime.lock"),
		filepath.Join(f.share, ".kast-uninstall-"+strings.Repeat("0", 64)+".json"),
		filepath.Join(f.temporary, "kast-uds-"+strings.Repeat("0", 32)),
		f.agent("b"),
		filepath.Join(f.home, "Library/LaunchAgents/com.example.editor.plist"),
	}
}

func (f *fixture) run(arguments ...string) error {
	f.t.Helper()
	r := &Reset{
		Home: f.home, UID: os.Getuid(), PID: os.Getpid(), Temporary: f.temporary,
		Supervisor: f, Processes: scriptedProcesses{f}, Out: &f.out, remove: f.remove,
		Pause: func(time.Duration) {
			if f.pause != nil {
				f.pause()
			}
		},
		Now: func() time.Time { return time.Date(2026, 11, 23, 10, 11, 12, 0, time.UTC) },
	}
	return r.Run(arguments)
}

func (f *fixture) Jobs() ([]Job, error) { return slices.Clone(f.jobs), nil }

func (f *fixture) Unload(label string) error {
	effect, ok := f.unload[label]
	if !ok {
		f.t.Errorf("unexpected unload of %s", label)
		return errors.New("unexpected unload")
	}
	f.signals = append(f.signals, "unload "+label)
	effect()
	return nil
}

func (f *fixture) endJob(label string) func() {
	return func() { f.jobs = slices.DeleteFunc(f.jobs, func(job Job) bool { return job.Label == label }) }
}

func (f *fixture) endProcess(pid int) {
	f.processes = slices.DeleteFunc(f.processes, func(process Process) bool { return process.PID == pid })
}

type scriptedProcesses struct{ *fixture }

func (s scriptedProcesses) List() ([]Process, error) { return slices.Clone(s.processes), nil }

func (s scriptedProcesses) Signal(pid int, signal syscall.Signal) error {
	effect, ok := s.signal[pid]
	if !ok {
		s.t.Errorf("unexpected signal %v to %d", signal, pid)
		return errors.New("unexpected signal")
	}
	s.signals = append(s.signals, fmt.Sprintf("%v %d", signal, pid))
	effect(signal)
	return nil
}

// snapshot is every path beneath the fixture with its type, mode and content.
func (f *fixture) snapshot() map[string]string {
	f.t.Helper()
	observed := map[string]string{}
	err := filepath.WalkDir(filepath.Dir(f.home), func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		observed[path] = info.Mode().String()
		if info.Mode().IsRegular() {
			content, err := os.ReadFile(path)
			if err != nil {
				observed[path] += " unreadable"
			}
			observed[path] += " " + string(content)
		}
		return nil
	})
	if err != nil {
		f.t.Fatal(err)
	}
	return observed
}

func requireFailure(t *testing.T, err error, want Failure) {
	t.Helper()
	var rejection *Rejection
	if !errors.As(err, &rejection) || rejection.Failure != want {
		t.Fatalf("got %v, want %s", err, want)
	}
}

func (f *fixture) requireAbsent(paths []string) {
	f.t.Helper()
	for _, path := range paths {
		if _, err := os.Lstat(path); !errors.Is(err, fs.ErrNotExist) {
			f.t.Errorf("%s remains: %v", path, err)
		}
	}
}

func (f *fixture) requireForeign() {
	f.t.Helper()
	for _, path := range f.foreign() {
		if content, err := os.ReadFile(path); err != nil || string(content) != "foreign" {
			f.t.Errorf("foreign %s: %q %v", path, content, err)
		}
	}
	if content, _ := os.ReadFile(filepath.Join(f.home, "project/Main.kt")); string(content) != "source" {
		f.t.Error("a link was followed out of the installation")
	}
}

// backups maps each backed-up path, relative to its backup directory, to its content.
func (f *fixture) backups() map[string]string {
	f.t.Helper()
	copied := map[string]string{}
	directories, _ := filepath.Glob(filepath.Join(f.share, backupPrefix+"*"))
	for _, directory := range directories {
		filepath.WalkDir(directory, func(path string, entry fs.DirEntry, err error) error {
			if err == nil && !entry.IsDir() {
				content, _ := os.ReadFile(path)
				relative, _ := filepath.Rel(directory, path)
				copied[relative] = string(content)
			}
			return err
		})
	}
	return copied
}

func wantBackups() map[string]string {
	containers := "kast-workspaces/containers/" + container
	return map[string]string{
		"kast/installation/config/environment":                                 "installed configuration",
		"kast/installation/config/workspaces/abc/environment":                  "overlay",
		"kast/recovery/replacement/payload/config/environment":                 "previous configuration",
		".kast-install-" + transaction + "/config/environment":                 "staged configuration",
		containers + "/container.json":                                         "workspace",
		containers + "/state/home/" + evidence:                                 "unsettled change",
		containers + "/generations/" + transaction + "/state/home/" + evidence: "retired change",
	}
}

func (f *fixture) java() string {
	return filepath.Join(f.share, "kast-workspaces/runtimes/527/host/bin/java")
}

func (f *fixture) control() string {
	return filepath.Join(f.installation, "share/kast/libexec/kast-management")
}

func TestStopsBacksUpAndDeletesOnlyWhatKastOwns(t *testing.T) {
	f := newFixture(t)
	targets := f.install()
	const owned, other = "io.github.amichne.kast.semantic.owned", "io.github.amichne.kast.semantic.other"
	f.jobs = []Job{{owned, f.control()}, {other, "/elsewhere/kast-management"}, {"io.github.amichne.kast.unproven", ""}}
	f.unload[owned] = f.endJob(owned)
	f.processes = []Process{
		{PID: 1}, {os.Getpid(), 50, os.Getuid(), f.control()}, {50, 1, os.Getuid(), "/bin/zsh"},
		{100, 1, os.Getuid(), f.java()},
		{101, 1, os.Getuid(), f.java()},
		{102, 1, os.Getuid() + 1, f.java()},
		{103, 1, os.Getuid(), "/Applications/Editor"},
		{104, 1, os.Getuid(), f.root + "-notes/bin/tool"},
		{105, 1, os.Getuid(), ""},
	}
	f.signal[100] = func(syscall.Signal) { f.endProcess(100) }
	f.signal[101] = func(signal syscall.Signal) {
		if signal == syscall.SIGKILL {
			f.endProcess(101)
		}
	}
	if err := f.run("--yes"); err != nil {
		t.Fatal(err)
	}
	want := []string{"unload " + owned, "terminated 100", "terminated 101", "killed 101"}
	if !slices.Equal(f.signals, want) {
		t.Errorf("effects %v, want %v", f.signals, want)
	}
	f.requireAbsent(targets)
	f.requireForeign()
	if got := f.backups(); !maps.Equal(got, wantBackups()) {
		t.Errorf("backup %v, want %v", got, wantBackups())
	}
	for _, text := range append(targets, InstallCommand, "Backup: "+filepath.Join(f.share, backupPrefix+"20261123T101112")) {
		if !strings.Contains(f.out.String(), text) {
			t.Errorf("output lacks %q", text)
		}
	}
}

func TestADryRunPrintsThePlanAndChangesNothing(t *testing.T) {
	f := newFixture(t)
	targets := f.install()
	f.jobs = []Job{{"io.github.amichne.kast.semantic.owned", f.control()}}
	f.processes = []Process{{100, 1, os.Getuid(), f.java()}}
	before := f.snapshot()
	if err := f.run(); err != nil {
		t.Fatal(err)
	}
	if !maps.Equal(before, f.snapshot()) {
		t.Error("a dry run changed the filesystem")
	}
	for _, text := range append(targets, "job io.github.amichne.kast.semantic.owned", "process 100", "Keep:\n  "+
		filepath.Join(f.home, ".kast"), filepath.Join(f.profile(), evidence), f.agent("b")+": starts no program") {
		if !strings.Contains(f.out.String(), text) {
			t.Errorf("plan lacks %q:\n%s", text, f.out.String())
		}
	}
}

func TestDamagedAndMissingRecordsDoNotBlockAReset(t *testing.T) {
	for name, damage := range map[string]func(*fixture){
		"corrupt records": func(f *fixture) {
			for _, path := range []string{filepath.Join(f.root, "management.json"),
				filepath.Join(f.installation, "share/kast/product-selection.json"),
				filepath.Join(f.installation, "share/kast/frontend.json")} {
				f.write(path, "{damaged")
			}
		},
		"missing records": func(f *fixture) { os.Remove(filepath.Join(f.root, "management.json")) },
		"missing runtime": func(f *fixture) { os.RemoveAll(filepath.Join(f.share, "kast-workspaces/runtimes")) },
		"records that are directories": func(f *fixture) {
			os.Remove(filepath.Join(f.root, "management.json"))
			f.write(filepath.Join(f.root, "management.json/file"), "")
		},
		"a store that holds a file for a directory": func(f *fixture) {
			os.RemoveAll(filepath.Join(f.share, "kast-workspaces/containers"))
			f.write(filepath.Join(f.share, "kast-workspaces/containers"), "")
		},
		"an unreadable directory": func(f *fixture) { os.Chmod(filepath.Join(f.installation, "share"), 0) },
	} {
		t.Run(name, func(t *testing.T) {
			f := newFixture(t)
			f.install()
			// Only the payload proves this copy, and one case makes the payload unreadable.
			os.Remove(f.public)
			os.Remove(filepath.Join(f.home, ".local/bin/.kast-managed-"+transaction+"-command"))
			damage(f)
			if err := f.run("--yes"); err != nil {
				t.Fatal(err)
			}
			f.requireAbsent([]string{f.root, filepath.Join(f.share, "kast-workspaces"), filepath.Join(f.share, "kast-runtime-bases")})
			f.requireForeign()
		})
	}
}

func TestNothingInstalledIsSuccessWithoutABackup(t *testing.T) {
	for _, arguments := range [][]string{nil, {"--yes"}} {
		f := newFixture(t)
		before := f.snapshot()
		if err := f.run(arguments...); err != nil || !strings.Contains(f.out.String(), InstallCommand) {
			t.Fatalf("%v: %v %s", arguments, err, f.out.String())
		}
		if !maps.Equal(before, f.snapshot()) {
			t.Errorf("%v changed the filesystem", arguments)
		}
	}
}

func TestOtherArgumentsAreUsageErrorsWithoutEffects(t *testing.T) {
	for _, arguments := range [][]string{{"--force"}, {"--yes", "--yes"}, {"yes"}} {
		f := newFixture(t)
		targets := f.install()
		requireFailure(t, f.run(arguments...), UsageRejected)
		if _, err := os.Lstat(targets[4]); err != nil {
			t.Error(err)
		}
	}
	f := newFixture(t)
	if err := f.run("--help"); err != nil || !strings.HasPrefix(f.out.String(), "Usage: kast reset") {
		t.Errorf("help: %v %q", err, f.out.String())
	}
}

func TestThePublicCommandIsDeletedOnlyWhenKastWroteIt(t *testing.T) {
	testBinary, err := os.ReadFile(os.Args[0])
	if err != nil {
		t.Fatal(err)
	}
	for name, test := range map[string]struct {
		place   func(*fixture)
		deleted bool
	}{
		"the payload's bytes":     {func(f *fixture) { f.write(f.public, "payload") }, true},
		"another build of Kast":   {func(f *fixture) { f.placeBuild(f.public) }, true},
		"a link into the root":    {func(f *fixture) { f.link(f.control(), f.public) }, true},
		"a relative link into it": {func(f *fixture) { f.link("../share/kast/installation/bin/kast", f.public) }, true},
		"a foreign file":          {func(f *fixture) { f.write(f.public, "#!/bin/sh") }, false},
		"an empty file": {func(f *fixture) {
			f.write(f.public, "")
			f.write(f.control(), "")
		}, false},
		"another Go program":          {func(f *fixture) { f.write(f.public, string(testBinary)) }, false},
		"a foreign link":              {func(f *fixture) { f.link("/usr/local/bin/kast", f.public) }, false},
		"a link through the root":     {func(f *fixture) { f.link(f.root+"/../../bin/tool", f.public) }, false},
		"a link to a sibling of root": {func(f *fixture) { f.link(f.root+"-notes/kast", f.public) }, false},
		"a directory":                 {func(f *fixture) { f.write(filepath.Join(f.public, "file"), "") }, false},
	} {
		t.Run(name, func(t *testing.T) {
			f := newFixture(t)
			f.write(f.control(), "payload")
			test.place(f)
			if err := f.run("--yes"); err != nil {
				t.Fatal(err)
			}
			f.requireAbsent([]string{f.root})
			if _, err := os.Lstat(f.public); errors.Is(err, fs.ErrNotExist) != test.deleted {
				t.Fatalf("deleted %v, want %v", errors.Is(err, fs.ErrNotExist), test.deleted)
			}
			if !test.deleted && !strings.Contains(f.out.String(), "Skip:\n  "+f.public) {
				t.Errorf("skip is not reported: %s", f.out.String())
			}
		})
	}
}

func TestAHomeThatCouldWidenAPathIsRefused(t *testing.T) {
	f := newFixture(t)
	f.install()
	linked := filepath.Join(filepath.Dir(f.home), "linked")
	f.link(f.home, linked)
	f.link(filepath.Dir(f.home), filepath.Join(filepath.Dir(f.home), "parent"))
	before := f.snapshot()
	for _, home := range []string{"", "relative/home", "/", "//", "/..", linked, filepath.Join(f.home, "absent"),
		filepath.Join(f.home, "project/Main.kt"), filepath.Join(filepath.Dir(f.home), "parent/home")} {
		f.home, f.out = home, bytes.Buffer{}
		requireFailure(t, f.run("--yes"), HomeRejected)
	}
	f.home = filepath.Join(filepath.Dir(linked), "home")
	if !maps.Equal(before, f.snapshot()) {
		t.Error("a refused home changed the filesystem")
	}
	// A home of another user is refused, and the filesystem root is refused even for its owner.
	_, err := admitLayout(f.home, f.temporary, os.Getuid()+1)
	requireFailure(t, err, HomeRejected)
	_, err = admitLayout("/", f.temporary, 0)
	requireFailure(t, err, HomeRejected)
}

func TestLinkedRootsAreRefusedBeforeAnyEffect(t *testing.T) {
	for _, relative := range []string{".local", ".local/share", ".local/share/kast", ".local/share/kast-workspaces",
		".local/share/kast-runtime-bases"} {
		t.Run(relative, func(t *testing.T) {
			f := newFixture(t)
			f.install()
			path := filepath.Join(f.home, relative)
			moved := filepath.Join(filepath.Dir(f.home), "moved")
			if err := os.Rename(path, moved); err != nil {
				t.Fatal(err)
			}
			f.link(moved, path)
			f.jobs = []Job{{"io.github.amichne.kast.semantic.owned", f.control()}}
			f.processes = []Process{{100, 1, os.Getuid(), f.java()}}
			before := f.snapshot()
			requireFailure(t, f.run("--yes"), RootRejected)
			if !maps.Equal(before, f.snapshot()) {
				t.Error("a linked root was changed")
			}
		})
	}
}

func TestLinksBesideTheRootsAreSkippedOrDeletedAsLinks(t *testing.T) {
	f := newFixture(t)
	f.install()
	outside := filepath.Join(filepath.Dir(f.home), "outside")
	f.write(filepath.Join(outside, "file"), "outside")
	// A linked container is deleted as a link. A linked sibling, endpoint directory or agent directory is skipped.
	f.link(outside, filepath.Join(f.share, "kast-workspaces/containers/linked"))
	sibling := filepath.Join(f.share, ".kast-prepare-linked_1")
	f.link(outside, sibling)
	endpoint := filepath.Join(f.temporary, "kast-"+digest(f.generation())[:32])
	f.link(outside, endpoint)
	agents := filepath.Join(f.home, "Library/LaunchAgents")
	if err := os.Rename(agents, filepath.Join(outside, "agents")); err != nil {
		t.Fatal(err)
	}
	f.link(filepath.Join(outside, "agents"), agents)
	if err := f.run("--yes"); err != nil {
		t.Fatal(err)
	}
	if content, _ := os.ReadFile(filepath.Join(outside, "file")); string(content) != "outside" {
		t.Error("a link was followed")
	}
	for _, path := range []string{sibling, endpoint, agents} {
		if _, err := os.Lstat(path); err != nil || !strings.Contains(f.out.String(), path+": ") {
			t.Errorf("%s was not skipped and reported: %v", path, err)
		}
	}
}

func TestLinkedConfigurationAndEvidenceAreReportedAndTheirTargetsStay(t *testing.T) {
	f := newFixture(t)
	outside := filepath.Join(f.home, "dotfiles")
	f.write(filepath.Join(outside, "environment"), "outside")
	f.write(filepath.Join(outside, "state/mutation.sqlite"), "outside")
	environment := filepath.Join(f.installation, "config/environment")
	f.link(filepath.Join(outside, "environment"), environment)
	f.link(outside, filepath.Join(f.profile(), ".kast"))
	if err := f.run("--yes"); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{environment, filepath.Join(f.profile(), ".kast/state")} {
		if !strings.Contains(f.out.String(), path+": ") {
			t.Errorf("%s is not reported: %s", path, f.out.String())
		}
	}
	for _, name := range []string{"environment", "state/mutation.sqlite"} {
		if content, _ := os.ReadFile(filepath.Join(outside, name)); string(content) != "outside" {
			t.Errorf("%s changed", name)
		}
	}
	if copied := f.backups(); len(copied) != 0 {
		t.Errorf("links were followed: %v", copied)
	}
	f.requireAbsent([]string{f.root})
}

func TestAnAliasThatLinksElsewhereSurvives(t *testing.T) {
	f := newFixture(t)
	f.write(f.control(), "payload")
	alias := filepath.Join(f.temporary, "kast-uds-"+digest(filepath.Join(f.installation, "state/run"))[:32])
	f.link("/elsewhere/state/run", alias)
	if err := f.run("--yes"); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Lstat(alias); err != nil {
		t.Error(err)
	}
}

func TestABackupFailureDeletesNothing(t *testing.T) {
	if os.Getuid() == 0 {
		t.Skip("root reads every file")
	}
	for name, damage := range map[string]func(*fixture){
		"unreadable configuration": func(f *fixture) {
			os.Chmod(filepath.Join(f.installation, "config/environment"), 0)
		},
		"unreadable workspace evidence": func(f *fixture) {
			os.Chmod(filepath.Join(f.profile(), ".kast/state"), 0)
		},
		"an existing backup of the same instant": func(f *fixture) {
			f.write(filepath.Join(f.share, backupPrefix+"20261123T101112.000000000Z.partial/file"), "earlier")
		},
		"a share directory that rejects writes": func(f *fixture) { os.Chmod(f.share, 0o500) },
	} {
		t.Run(name, func(t *testing.T) {
			f := newFixture(t)
			targets := f.install()
			damage(f)
			f.remove = func(path string) []string {
				t.Errorf("deleted %s after a failed backup", path)
				return nil
			}
			requireFailure(t, f.run("--yes"), BackupRejected)
			for _, target := range targets {
				if _, err := os.Lstat(target); err != nil {
					t.Error(err)
				}
			}
		})
	}
}

func TestWhateverKeepsRunningBlocksDeletion(t *testing.T) {
	const label = "io.github.amichne.kast.semantic.owned"
	for name, script := range map[string]func(*fixture){
		"a process that survives SIGKILL": func(f *fixture) {
			f.processes = []Process{{100, 1, os.Getuid(), f.java()}}
			f.signal[100] = func(syscall.Signal) {}
		},
		"a process that restarts": func(f *fixture) {
			pid := 100
			f.processes = []Process{{pid, 1, os.Getuid(), f.java()}}
			restart := func(syscall.Signal) {}
			restart = func(syscall.Signal) {
				f.endProcess(pid)
				pid++
				f.processes = append(f.processes, Process{pid, 1, os.Getuid(), f.java()})
				f.signal[pid] = restart
			}
			f.signal[pid] = restart
		},
		"a job that stays loaded": func(f *fixture) {
			f.jobs = []Job{{label, f.control()}}
			f.unload[label] = func() {}
		},
	} {
		t.Run(name, func(t *testing.T) {
			f := newFixture(t)
			f.install()
			script(f)
			before := f.snapshot()
			requireFailure(t, f.run("--yes"), StopRejected)
			if !maps.Equal(before, f.snapshot()) {
				t.Error("a running installation was changed")
			}
		})
	}
}

func TestAProcessThatStartsDuringTheBackupBlocksDeletion(t *testing.T) {
	f := newFixture(t)
	targets := f.install()
	r := &Reset{Home: f.home, UID: os.Getuid(), PID: os.Getpid(), Temporary: f.temporary, Supervisor: f,
		Processes: scriptedProcesses{f}, Out: &f.out, Pause: func(time.Duration) {},
		Now: func() time.Time {
			f.processes = []Process{{100, 1, os.Getuid(), f.java()}}
			return time.Now()
		}}
	requireFailure(t, r.Run([]string{"--yes"}), StopRejected)
	for _, target := range targets {
		if _, err := os.Lstat(target); err != nil {
			t.Error(err)
		}
	}
}

func TestAnAncestorThatIsKastStopsTheResetBeforeAnyEffect(t *testing.T) {
	for _, uid := range []int{os.Getuid(), os.Getuid() + 1} {
		f := newFixture(t)
		f.install()
		f.jobs = []Job{{"io.github.amichne.kast.semantic.owned", f.control()}}
		f.processes = []Process{{os.Getpid(), 60, os.Getuid(), "/bin/kast"}, {60, 50, os.Getuid(), "/bin/zsh"},
			{50, 1, uid, f.java()}, {100, 1, os.Getuid(), f.java()}}
		before := f.snapshot()
		err := f.run("--yes")
		requireFailure(t, err, StopRejected)
		if !strings.Contains(err.Error(), "process 50 ("+f.java()+")") {
			t.Errorf("the ancestor is not named: %v", err)
		}
		if !maps.Equal(before, f.snapshot()) || len(f.signals) != 0 {
			t.Errorf("effects before the rejection: %v", f.signals)
		}
	}
}

func TestAProcessListFailureStopsTheReset(t *testing.T) {
	f := newFixture(t)
	f.install()
	before := f.snapshot()
	r := &Reset{Home: f.home, UID: os.Getuid(), PID: os.Getpid(), Temporary: f.temporary, Supervisor: f,
		Processes: failingProcesses{}, Out: &f.out, Pause: func(time.Duration) {}, Now: time.Now}
	requireFailure(t, r.Run([]string{"--yes"}), StopRejected)
	r.Processes, r.Supervisor = scriptedProcesses{f}, failingSupervisor{}
	requireFailure(t, r.Run([]string{"--yes"}), StopRejected)
	if !maps.Equal(before, f.snapshot()) {
		t.Error("an unobserved installation was changed")
	}
}

type failingProcesses struct{}

func (failingProcesses) List() ([]Process, error)         { return nil, errors.New("unavailable") }
func (failingProcesses) Signal(int, syscall.Signal) error { return errors.New("unexpected signal") }

type failingSupervisor struct{}

func (failingSupervisor) Jobs() ([]Job, error) { return nil, errors.New("unavailable") }
func (failingSupervisor) Unload(string) error  { return errors.New("unexpected unload") }

// Each case interrupts one deletion, halfway through a directory or before it, and reruns.
func TestARerunFinishesAnInterruptionAtEachDeletion(t *testing.T) {
	type interruption struct{}
	count := len(newFixture(t).install())
	for step := range count {
		for _, partly := range []bool{false, true} {
			t.Run(fmt.Sprintf("%d partly=%v", step, partly), func(t *testing.T) {
				f := newFixture(t)
				targets := f.install()
				// Every installed copy is a Go build, which the rerun recognizes without the payload.
				f.placeBuild(f.public)
				f.placeBuild(filepath.Join(f.home, ".local/bin/.kast-managed-"+transaction+"-command"))
				paths, err := admitLayout(f.home, f.temporary, os.Getuid())
				if err != nil {
					t.Fatal(err)
				}
				calls := 0
				f.remove = func(path string) []string {
					if calls == step {
						if children, _ := os.ReadDir(path); partly && len(children) > 0 {
							paths.removeTree(filepath.Join(path, children[0].Name()))
						}
						panic(interruption{})
					}
					calls++
					return paths.removeTree(path)
				}
				func() {
					defer func() {
						if recovered := recover(); recovered != (interruption{}) {
							t.Fatalf("not interrupted: %v", recovered)
						}
					}()
					f.run("--yes")
				}()
				if _, err := os.Lstat(f.public); err != nil {
					t.Fatalf("the command that reruns the reset is gone: %v", err)
				}
				f.remove, f.out = nil, bytes.Buffer{}
				// The fixture's clock stands still, so the first backup would share the second one's name.
				os.Rename(filepath.Join(f.share, backupPrefix+"20261123T101112.000000000Z"),
					filepath.Join(f.share, backupPrefix+"first"))
				if err := f.run("--yes"); err != nil {
					t.Fatal(err)
				}
				f.requireAbsent(targets)
				f.requireForeign()
				if got := f.backups(); !maps.Equal(got, wantBackups()) {
					t.Errorf("backup %v", got)
				}
			})
		}
	}
}

func TestWhatRemainsIsListedAndRejected(t *testing.T) {
	f := newFixture(t)
	f.install()
	f.remove = func(path string) []string { return []string{path + "/inner: owned by another user"} }
	err := f.run("--yes")
	requireFailure(t, err, ResetIncomplete)
	for _, text := range []string{f.root + "\n", f.root + "/inner: owned by another user"} {
		if !strings.Contains(err.Error(), text) {
			t.Errorf("missing %q in %v", text, err)
		}
	}
}

func TestDeletionNeverEntersAnotherUsersEntryOrAnotherFilesystem(t *testing.T) {
	f := newFixture(t)
	f.install()
	before := f.snapshot()
	stranger := &layout{uid: os.Getuid() + 1}
	if left := stranger.removeTree(f.root); !slices.Equal(left, []string{f.root + ": owned by another user"}) {
		t.Errorf("left %v", left)
	}
	parent, err := os.OpenRoot(f.share)
	if err != nil {
		t.Fatal(err)
	}
	defer parent.Close()
	own := &layout{uid: os.Getuid()}
	if left := own.removeEntry(parent, f.root, 0); !slices.Equal(left, []string{f.root + ": on another filesystem"}) {
		t.Errorf("left %v", left)
	}
	if !maps.Equal(before, f.snapshot()) {
		t.Error("a refused entry was changed")
	}
}

func TestLaunchdJobsAreSelectedByLabelAndProgram(t *testing.T) {
	listing := "PID\tStatus\tLabel\n12\t0\tio.github.amichne.kast.semantic.abc\n-\t0\tcom.apple.x\n" +
		"-\t0\tother.io.github.amichne.kast.semantic.abc\n-\t78\tio.github.amichne.kast.broker.def\n"
	if got := parseLabels(listing); !slices.Equal(got, []string{"io.github.amichne.kast.semantic.abc",
		"io.github.amichne.kast.broker.def"}) {
		t.Errorf("labels %v", got)
	}
	definition := func(arguments ...string) string {
		return "gui/501/label = {\n\tpath = /x.plist\n\tprogram = /usr/bin/env\n\targuments = {\n\t\t" +
			strings.Join(arguments, "\n\t\t") + "\n\t}\n\tworking directory = /installed\n}\n"
	}
	for want, raw := range map[string]string{
		"/installed/kast-management": definition("/usr/bin/env", "-i", "HOME=/home/a b", "PATH=/installed/bin:/usr/bin",
			`KAST_OPTS=-Duser.home="/home/a b"`, "/installed/kast-management", "-Duser.home=/home/a b"),
		"": definition("/bin/sh", "-c", "/installed/kast-management"),
	} {
		if got := parseProgram(raw); got != want {
			t.Errorf("program %q, want %q", got, want)
		}
	}
	for _, raw := range []string{"", definition(), definition("/usr/bin/env", "-i"), definition("/usr/bin/env", "-i", "A=b")} {
		if got := parseProgram(raw); got != "" {
			t.Errorf("unproven program %q from %q", got, raw)
		}
	}
}

// ownChildren reads the real process list, and signals only the processes this test started.
type ownChildren struct {
	t        *testing.T
	children map[int]bool
}

func (o ownChildren) List() ([]Process, error) { return nativeProcesses{}.List() }

func (o ownChildren) Signal(pid int, signal syscall.Signal) error {
	if !o.children[pid] {
		o.t.Errorf("unexpected signal %v to %d", signal, pid)
		return errors.New("unexpected signal")
	}
	return nativeProcesses{}.Signal(pid, signal)
}

// The kernel's executable path selects a process. Its working directory, arguments and argv[0] do not.
func TestARealProcessIsSelectedByItsExecutableOnly(t *testing.T) {
	if runtime.GOOS != "darwin" && runtime.GOOS != "linux" {
		t.Skip("no native process adapter")
	}
	f := newFixture(t)
	f.install()
	program := f.java()
	raw, err := os.ReadFile(os.Args[0])
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(program, raw, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(program, 0o700); err != nil {
		t.Fatal(err)
	}
	test, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	start := func(executable string, arguments ...string) (*exec.Cmd, chan error) {
		child := &exec.Cmd{Path: executable, Args: arguments, Dir: f.root, Env: []string{"KAST_RESET_CHILD=1"}}
		if err := child.Start(); err != nil {
			t.Fatal(err)
		}
		ended := make(chan error, 1)
		go func() { ended <- child.Wait() }()
		t.Cleanup(func() { child.Process.Kill() })
		return child, ended
	}
	host, hostEnded := start(program, "innocent")
	editor, editorEnded := start(test, program, f.root)
	adapter := ownChildren{t, map[int]bool{host.Process.Pid: true}}
	// A started child shows its own executable only after exec completes.
	for deadline := time.Now().Add(10 * time.Second); ; time.Sleep(10 * time.Millisecond) {
		listed, err := adapter.List()
		if err != nil {
			t.Fatal(err)
		}
		if slices.ContainsFunc(listed, func(process Process) bool {
			return process.PID == host.Process.Pid && beneath(process.Executable, f.share) &&
				process.Parent == os.Getpid() && process.UID == os.Getuid()
		}) {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("the child never showed its executable")
		}
	}
	r := &Reset{Home: f.home, UID: os.Getuid(), PID: os.Getpid(), Temporary: f.temporary, Supervisor: f,
		Processes: adapter, Out: &f.out, Pause: time.Sleep, Now: time.Now}
	if err := r.Run([]string{"--yes"}); err != nil {
		t.Fatal(err)
	}
	if err := <-hostEnded; err == nil || !strings.Contains(err.Error(), "terminated") {
		t.Errorf("the process beneath the root ended with %v", err)
	}
	select {
	case err := <-editorEnded:
		t.Errorf("a process outside the roots ended with %v", err)
	default:
	}
	if strings.Contains(f.out.String(), fmt.Sprintf("process %d ", editor.Process.Pid)) {
		t.Error("a process outside the roots was selected")
	}
	f.requireAbsent([]string{f.root, filepath.Join(f.share, "kast-workspaces")})
}
