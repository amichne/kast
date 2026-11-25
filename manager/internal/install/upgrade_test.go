package install

import (
	"bytes"
	"context"
	"encoding/json/v2"
	"errors"
	"io"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"
)

// installed is a fixture with one recorded installation and a scripted engine. An unexpected engine run fails the test.
type installed struct {
	*fixture
	root, installation string
	engine             func(ctx context.Context, command []string, out, log io.Writer) (int, error)
	commands           [][]string
	selection          map[string]any
	refreshes          []string
	refreshFailure     error
	environment        []string
}

func newInstalled(t *testing.T) *installed {
	t.Helper()
	f := newFixture(t)
	root := filepath.Join(f.home, ".local", "share", "kast")
	i := &installed{fixture: f, root: root, installation: filepath.Join(root, "installation")}
	i.put("installation/"+bridgePath, "engine", 0o644)
	i.put("installation/bin/kast", "launcher", 0o755)
	i.put("installation/"+buildPath, string(f.encode(f.record)), 0o644)
	i.selectInstalled("developer-v0.0.123", nil, trackMain)
	i.put("management.json", `{"product":{"policy":"TrackMain"}}`, 0o600)
	i.seal()
	i.engine = func(context.Context, []string, io.Writer, io.Writer) (int, error) {
		t.Error("unexpected engine run")
		return 1, nil
	}
	return i
}

func (i *installed) put(path, text string, mode os.FileMode) {
	i.t.Helper()
	path = filepath.Join(i.root, path)
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		i.t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(text), mode); err != nil {
		i.t.Fatal(err)
	}
	if err := os.Chmod(path, mode); err != nil {
		i.t.Fatal(err)
	}
}

func (i *installed) selectInstalled(tag string, public any, policy string) {
	record, err := os.ReadFile(filepath.Join(i.installation, buildPath))
	if err != nil {
		i.t.Fatal(err)
	}
	i.put("installation/"+selectionPath, string(i.encode(map[string]any{
		"releaseTag": tag, "publicVersion": public, "sourceRevision": revision, "buildRecordSha256": digest(record),
		"policy": policy,
	})), 0o644)
}

// seal records every payload file, as the installer does.
func (i *installed) seal() {
	var files []any
	for _, directory := range []string{"bin", "share"} {
		filepath.WalkDir(filepath.Join(i.installation, directory), func(path string, entry os.DirEntry, err error) error {
			if err != nil || entry.IsDir() {
				return err
			}
			raw, _ := os.ReadFile(path)
			info, _ := entry.Info()
			relative, _ := filepath.Rel(i.installation, path)
			files = append(files, map[string]any{"path": relative, "sha256": "sha256:" + digest(raw),
				"mode": int(info.Mode().Perm())})
			return nil
		})
	}
	os.MkdirAll(filepath.Join(i.installation, "lib"), 0o700)
	i.put("installation/installation.json", string(i.encode(map[string]any{
		"schemaVersion": 3, "installationRoot": i.installation, "payloadFiles": files,
	})), 0o600)
}

func (i *installed) upgrade(arguments ...string) error {
	i.t.Helper()
	i.out.Reset()
	i.log.Reset()
	inner := i.install()
	inner.Environment = append([]string{"HOME=/elsewhere", "KEEP=1"}, i.environment...)
	upgrade := &Upgrade{Install: *inner, Temporary: i.t.TempDir(),
		Activate: func(ctx context.Context, command, environment []string, out, log io.Writer) (int, error) {
			i.commands = append(i.commands, command)
			if !slices.Contains(environment, "HOME="+i.home) || slices.Contains(environment, "HOME=/elsewhere") ||
				!slices.Contains(environment, "KEEP=1") {
				i.t.Errorf("engine environment %v", environment)
			}
			if index := slices.Index(command, "--resolved-selection"); index >= 0 {
				raw, err := os.ReadFile(command[index+1])
				if err != nil {
					i.t.Error(err)
				}
				i.selection = map[string]any{}
				if err := json.Unmarshal(raw, &i.selection); err != nil {
					i.t.Error(err)
				}
			}
			return i.engine(ctx, command, out, log)
		},
		Refresh: func(_ context.Context, command string, _ []string, log io.Writer) error {
			i.refreshes = append(i.refreshes, command)
			return i.refreshFailure
		}}
	return upgrade.Run(context.Background(), arguments)
}

const installedResult = `{"type":"PRODUCT_INSTALLED","product":{"version":"0.0.123","publicVersion":null}}` + "\n"

func (i *installed) reports(status int, out string, log ...string) {
	i.engine = func(_ context.Context, _ []string, stdout, stderr io.Writer) (int, error) {
		io.WriteString(stderr, strings.Join(log, "\n")+"\n")
		io.WriteString(stdout, out)
		return status, nil
	}
}

func TestAnUpgradeFollowsTheRecordedPolicyAndPresentsWords(t *testing.T) {
	i := newInstalled(t)
	i.reports(0, installedResult, `{"stage": "DOWNLOAD", "outcome": "STARTED"}`, `{"stage": "ARCHIVE", "outcome": "STARTED"}`,
		`{"type":"READ","bytes":132}`, `{"stage": "ARCHIVE", "outcome": "STARTED"}`, "noise",
		`{"stage": "REQUEST_RETIREMENT", "outcome": "STARTED"}`, `{"stage": "REQUEST_RETIREMENT", "outcome": "COMPLETED"}`,
		`{"stage": "NEW_STAGE", "outcome": "STARTED"}`)
	if err := i.upgrade(); err != nil {
		t.Fatal(err)
	}
	if text := i.out.String() + i.log.String(); strings.ContainsAny(text, "{}\x1b") {
		t.Errorf("output holds JSON or control sequences: %q", text)
	}
	if want := "  Downloading and verifying\n  Unpacking\n  Stopping workspace Hosts\n  new stage\n"; i.log.String() != want {
		t.Errorf("stages %q want %q", i.log.String(), want)
	}
	if !strings.Contains(i.out.String(), "Kast 0.0.123 is installed.") {
		t.Errorf("output %q", i.out.String())
	}
	want := map[string]any{"type": "Product", "releaseTag": tag, "policy": trackMain, "publicVersion": nil,
		"mainRevision": revision, "recordSha256": digest(i.pages[releases+"/"+tag+"/build-record.json"])}
	for name, value := range want {
		if i.selection[name] != value {
			t.Errorf("selection %s is %v, want %v", name, i.selection[name], value)
		}
	}
	if record := i.selection["record"].(map[string]any); record["version"] != version || len(i.selection) != 7 {
		t.Errorf("selection %v", i.selection)
	}
	command := i.commands[0]
	if command[0] != "/bin/bash" || command[1] != filepath.Join(i.installation, bridgePath) ||
		!slices.Contains(command, "--json") || slices.Contains(command, "--dry-run") {
		t.Errorf("command %v", command)
	}
	if !slices.Equal(i.refreshes, []string{filepath.Join(i.home, ".local/bin/kast")}) {
		t.Errorf("refreshes %v", i.refreshes)
	}
	if _, err := os.Stat(command[3]); !errors.Is(err, os.ErrNotExist) {
		t.Error("the captured selection stayed")
	}
}

func TestASelectedBuildRecordsItsOwnPolicy(t *testing.T) {
	for argument, want := range map[string]string{"--channel=preview": trackMain, "--commit=" + revision: pinnedBuild} {
		i := newInstalled(t)
		i.put("management.json", `{"product":{"policy":"PinnedBuild"}}`, 0o600)
		i.reports(0, installedResult)
		if err := i.upgrade(argument); err != nil || i.selection["policy"] != want {
			t.Errorf("%s: %v, policy %v", argument, err, i.selection["policy"])
		}
	}
}

func TestADryRunInstallsAndRefreshesNothing(t *testing.T) {
	i := newInstalled(t)
	i.reports(0, strings.Replace(installedResult, "INSTALLED", "PLANNED", 1))
	if err := i.upgrade("--dry-run"); err != nil {
		t.Fatal(err)
	}
	if !slices.Contains(i.commands[0], "--dry-run") || len(i.refreshes) != 0 ||
		!strings.Contains(i.out.String(), "Nothing was installed.") {
		t.Errorf("command %v, refreshes %v, output %q", i.commands[0], i.refreshes, i.out.String())
	}
}

func TestJSONKeepsTheEnginesExactDocumentAndLog(t *testing.T) {
	i := newInstalled(t)
	i.reports(0, installedResult, `{"stage":"DOWNLOAD","outcome":"STARTED"}`)
	if err := i.upgrade("--json"); err != nil || i.out.String() != installedResult ||
		!strings.Contains(i.log.String(), `{"stage":"DOWNLOAD"`) {
		t.Errorf("%v, out %q, log %q", err, i.out.String(), i.log.String())
	}
	i.reports(1, `{"type":"REJECTED","stage":"PREPARATION","reason":"STORAGE_EXHAUSTED"}`+"\n")
	if err := i.upgrade("--json"); err == nil || strings.Count(i.out.String(), "\n") != 1 {
		t.Errorf("%v, out %q", err, i.out.String())
	}
	i.put("management.json", `{"product":{"policy":"PinnedBuild"}}`, 0o600)
	if err := i.upgrade("--json"); err == nil || !strings.Contains(i.out.String(), `"failure":"PINNED_BUILD"`) {
		t.Errorf("%v, out %q", err, i.out.String())
	}
}

func TestEachEngineOutcomeIsOneNamedFailureWithAnAction(t *testing.T) {
	for name, test := range map[string]struct {
		status     int
		out        string
		failure    Failure
		text, next string
	}{
		"storage": {1, `{"type":"REJECTED","stage":"PREPARATION","reason":"STORAGE_EXHAUSTED","adapterCode":null}`,
			ActivationRejected, "preparing the runtime: storage exhausted", "Free disk space"},
		"entries": {1, `{"type":"REJECTED","stage":"PREPARATION","reason":"UNSETTLED_INVOCATIONS",` +
			`"state":{"component":"kast-invocation-state","entries":["a","b"]}}`,
			ActivationRejected, "unsettled invocations in kast-invocation-state: a, b", "Wait for running"},
		"transfer": {1, `{"type":"REJECTED","stage":"DOWNLOAD","reason":"ARTIFACT_INVALID",` +
			`"transfer":{"cause":"HTTP_STATUS","httpStatus":503}}`, ActivationRejected, "http status 503", "same command"},
		"recovery": {1, `{"type":"RECOVERY_REQUIRED","transaction":"product",` +
			`"failure":{"stage":"FENCE","reason":"CONCURRENT_CHANGE"}}`, RecoveryRequired, "holding new requests", "other Kast"},
		"success with a failing status": {3, installedResult, ActivationRejected, "status 3", "same command"},
		"no result":                     {1, "", ActivationRejected, "no result", "same command"},
		"unknown result":                {0, `{"type":"LATER"}`, ActivationRejected, "LATER", "same command"},
	} {
		t.Run(name, func(t *testing.T) {
			i := newInstalled(t)
			i.reports(test.status, test.out)
			rejection := i.requireFailure(test.failure, i.upgrade())
			if !strings.Contains(rejection.Detail, test.text) || !strings.Contains(rejection.Next, test.next) {
				t.Errorf("%q, next %q", rejection.Detail, rejection.Next)
			}
			if len(i.refreshes) != 0 {
				t.Error("a failed upgrade refreshed agents")
			}
		})
	}
}

func TestADamagedInstallationNeverRunsItsEngine(t *testing.T) {
	for name, damage := range map[string]func(*installed){
		"changed engine": func(i *installed) { i.put("installation/"+bridgePath, "other", 0o644) },
		"changed mode":   func(i *installed) { os.Chmod(filepath.Join(i.installation, bridgePath), 0o755) },
		"missing file":   func(i *installed) { os.Remove(filepath.Join(i.installation, "bin/kast")) },
		"extra file":     func(i *installed) { i.put("installation/share/extra", "x", 0o644) },
		"linked engine": func(i *installed) {
			path := filepath.Join(i.installation, bridgePath)
			os.Rename(path, path+".real")
			os.Symlink(path+".real", path)
		},
		"other root":  func(i *installed) { i.put("installation/installation.json", `{"schemaVersion":3}`, 0o600) },
		"no manifest": func(i *installed) { os.Remove(filepath.Join(i.installation, "installation.json")) },
		"no record":   func(i *installed) { os.Remove(filepath.Join(i.root, "management.json")) },
	} {
		t.Run(name, func(t *testing.T) {
			i := newInstalled(t)
			damage(i)
			i.requireFailure(InstallationDamaged, i.upgrade())
		})
	}
}

func TestAnUnfinishedUpgradeIsFinishedBeforeAnotherSelection(t *testing.T) {
	i := newInstalled(t)
	i.put("recovery/replacement/receipt.json", "{}", 0o600)
	i.reports(0, installedResult)
	if err := i.upgrade("--channel", "preview"); err != nil {
		t.Fatal(err)
	}
	if !slices.Contains(i.commands[0], "--recover-only") || len(i.calls) != 0 {
		t.Errorf("command %v, requests %v", i.commands[0], i.calls)
	}
	i.put("installation/"+bridgePath, "other", 0o644)
	i.requireFailure(RecoveryRequired, i.upgrade())
}

func TestARepairSelectsTheExactInstalledBuildWithoutARequest(t *testing.T) {
	i := newInstalled(t)
	i.reports(0, installedResult)
	if err := i.upgrade("--repair"); err != nil {
		t.Fatal(err)
	}
	if i.selection["releaseTag"] != tag || i.selection["mainRevision"] != revision || len(i.calls) != 0 {
		t.Errorf("selection %v, requests %v", i.selection, i.calls)
	}
	i.selectInstalled("v1.2.3", "1.2.3", pinnedBuild)
	i.seal()
	if err := i.upgrade("--repair"); err != nil || i.selection["publicVersion"] != "1.2.3" ||
		i.selection["mainRevision"] != nil {
		t.Errorf("%v, selection %v", err, i.selection)
	}
	i.record["schemaVersion"] = localSchema
	i.put("installation/"+buildPath, string(i.encode(i.record)), 0o644)
	i.selectInstalled(tag, nil, pinnedBuild)
	i.seal()
	i.requireFailure(LocalArtifactRequired, i.upgrade("--repair"))
}

func TestWhatCannotBeUpgradedIsRejectedBeforeAnyRequest(t *testing.T) {
	i := newInstalled(t)
	i.put("management.json", `{"product":{"policy":"PinnedBuild"}}`, 0o600)
	i.requireFailure(PinnedBuild, i.upgrade())
	i.environment = []string{"KAST_INSTALL_ROOT=/elsewhere"}
	i.requireFailure(StorageRejected, i.upgrade())
	i.environment = nil
	for _, arguments := range [][]string{
		{"--channel"}, {"--channel", "beta"}, {"--repair", "--channel", "stable"}, {"--version=1"}, {"extra"},
	} {
		i.requireFailure(UsageRejected, i.upgrade(arguments...))
	}
	if err := os.RemoveAll(i.installation); err != nil {
		t.Fatal(err)
	}
	i.requireFailure(NotInstalled, i.upgrade())
	if len(i.calls) != 0 {
		t.Errorf("requests %v", i.calls)
	}
}

func TestAFailedRefreshKeepsTheUpgradesResult(t *testing.T) {
	i := newInstalled(t)
	i.reports(0, installedResult)
	i.refreshFailure = errors.New("no")
	if err := i.upgrade(); err != nil || !strings.Contains(i.log.String(), "kast agent refresh") {
		t.Errorf("%v, log %q", err, i.log.String())
	}
}

func TestATerminalShowsHowLongAStageRuns(t *testing.T) {
	var log bytes.Buffer
	s := &stages{log: &log, terminal: true, now: time.Now, stopped: make(chan struct{}), ended: make(chan struct{})}
	close(s.ended)
	io.WriteString(s, `{"stage":"DOWNLOAD","outcome":"STARTED"}`+"\n"+`{"stage":"ARCHIVE","out`)
	io.WriteString(s, `come":"STARTED"}`+"\n")
	s.end()
	want := eraseLine + "  Downloading and verifying" + eraseLine + "  Downloading and verifying\n" +
		eraseLine + "  Unpacking" + eraseLine + "  Unpacking\n"
	if log.String() != want {
		t.Errorf("got %q want %q", log.String(), want)
	}
}

func TestACancelledEngineIsAskedOnceAndAwaited(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	marker := filepath.Join(t.TempDir(), "ready")
	script := `trap 'sleep 0.2; exit 7' TERM; : > "$0"; while :; do sleep 0.05; done`
	go func() {
		for !present(marker) {
			time.Sleep(10 * time.Millisecond)
		}
		cancel()
	}()
	status, err := runEngine(ctx, []string{"/bin/sh", "-c", script, marker}, os.Environ(), io.Discard, io.Discard)
	if err != nil || status != 7 {
		t.Errorf("status %d, %v", status, err)
	}
}
