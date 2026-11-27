package install

import (
	"bytes"
	"context"
	"encoding/json/v2"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"
)

const (
	nextVersion  = "0.0.124"
	nextTag      = "developer-v0.0.124"
	nextRevision = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
)

// activation is one home with published builds and scripted children. An unexpected child fails the test.
type activation struct {
	*fixture
	root, installation, share, command string
	products                           map[string]*testBuild
	builds                             []map[string]any
	// retire and train replace the outgoing build's retirement and the class-data training.
	retire      func(command, environment []string) (int, error)
	train       func(archive string) (int, error)
	children    []string
	engine      func(command []string, out io.Writer) (int, error)
	refreshes   []string
	environment []string
}

func newActivation(t *testing.T) *activation {
	t.Helper()
	f := newFixture(t)
	share := filepath.Join(f.home, ".local", "share")
	a := &activation{fixture: f, share: share, root: filepath.Join(share, "kast"),
		installation: filepath.Join(share, "kast", "installation"), command: filepath.Join(f.home, ".local", "bin", "kast"),
		products: map[string]*testBuild{}}
	// A sealed runtime's directories are read-only; the temporary directory's removal needs them writable.
	t.Cleanup(func() { removeTree(f.home) })
	a.retire = func(_, _ []string) (int, error) {
		a.write(filepath.Join(a.root, fenceName), `{"installationRoot":"`+a.installation+`","schemaVersion":1}`, 0o600)
		return 0, nil
	}
	a.train = func(archive string) (int, error) {
		a.write(archive, "archive", 0o600)
		return 0, nil
	}
	a.engine = func([]string, io.Writer) (int, error) {
		t.Error("unexpected engine run")
		return 1, nil
	}
	a.publishBuild(tag, version, revision, newProduct(version, "first"))
	return a
}

// publishBuild publishes one more preview build; the catalog lists the newest first.
func (a *activation) publishBuild(name, number, source string, built *testBuild) {
	a.t.Helper()
	a.products[name] = built
	// The Linux files have no page: a Mac that requested one would fail the test.
	other := newProduct(number, "linux")
	other.target = "linux-x86_64"
	linux, _ := other.assets(a.t)
	mac, assets := built.assets(a.t)
	record := map[string]any{
		"schemaVersion": 3, "repository": repository, "sourceRevision": source, "sourceTree": source, "version": number,
		"producer": map[string]any{"runId": 1, "runAttempt": 1, "workflow": ".github/workflows/ci.yml"},
		"targets":  []any{linux, mac},
	}
	for asset, raw := range assets {
		a.pages[releases+"/"+name+"/"+asset] = raw
	}
	raw := a.encode(record)
	a.pages[releases+"/"+name+"/build-record.json"] = raw
	a.builds = append([]map[string]any{a.entry(source, name, digest(raw))}, a.builds...)
	a.pages[catalogURL] = a.encode(map[string]any{"schemaVersion": 2, "builds": a.builds})
}

func (a *activation) write(path, text string, mode os.FileMode) {
	a.t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		a.t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(text), mode); err != nil {
		a.t.Fatal(err)
	}
}

func (a *activation) read(path string) string {
	a.t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		a.t.Fatal(err)
	}
	return string(raw)
}

func (a *activation) upgrader() *Upgrade {
	inner := a.fixture.install()
	inner.Environment = append([]string{"HOME=/elsewhere", "KEEP=1"}, a.environment...)
	return &Upgrade{Install: *inner, Temporary: a.t.TempDir(),
		Engine: func(_ context.Context, command, _ []string, out, _ io.Writer) (int, error) {
			return a.engine(command, out)
		},
		Child: func(_ context.Context, command, environment []string, _ time.Duration) (int, error) {
			switch {
			case len(command) == 3 && command[1] == "--internal-install" && command[2] == "managed-retire":
				a.children = append(a.children, "retire "+command[0])
				for _, required := range []string{"HOME=" + a.home, "KAST_INSTALL_ROOT=" + a.root, "KEEP=1"} {
					if !slices.Contains(environment, required) || slices.Contains(environment, "HOME=/elsewhere") {
						a.t.Errorf("retirement environment %v lacks %s", environment, required)
					}
				}
				return a.retire(command, environment)
			case len(command) > 1 && strings.HasPrefix(command[1], "-XX:ArchiveClassesAtExit="):
				a.children = append(a.children, "train "+command[0])
				return a.train(strings.TrimPrefix(command[1], "-XX:ArchiveClassesAtExit="))
			default:
				a.t.Errorf("unexpected child %v", command)
				return 1, nil
			}
		},
		Refresh: func(_ context.Context, command string, _ []string, _ io.Writer) error {
			a.refreshes = append(a.refreshes, command)
			return nil
		}}
}

func (a *activation) upgrade(arguments ...string) error {
	a.t.Helper()
	a.out.Reset()
	a.log.Reset()
	return a.upgrader().Run(context.Background(), arguments)
}

func (a *activation) first(arguments ...string) error {
	a.t.Helper()
	a.out.Reset()
	a.log.Reset()
	return a.upgrader().RunInstall(context.Background(), arguments)
}

// installed performs the first installation of the fixture's build.
func (a *activation) installed() {
	a.t.Helper()
	if err := a.first("--channel", "preview"); err != nil {
		a.t.Fatal(err)
	}
	a.children, a.refreshes = nil, nil
}

func (a *activation) runtimeRoot(name string) string {
	a.t.Helper()
	raw := a.pages[releases+"/"+name+"/build-record.json"]
	var record recordDocument
	if err := json.Unmarshal(raw, &record); err != nil {
		a.t.Fatal(err)
	}
	return filepath.Join(a.share, "kast-workspaces", "runtimes", record.Targets[1].Runtime.SHA256)
}

// leftovers are the entries beside the management root that an activation must not leave.
func (a *activation) leftovers() []string {
	entries, _ := os.ReadDir(a.share)
	var left []string
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name(), ".kast-") && entry.Name() != ".kast-downloads" {
			left = append(left, entry.Name())
		}
	}
	return left
}

func TestAFirstInstallationWritesTheLayoutTheJvmAdmits(t *testing.T) {
	a := newActivation(t)
	if err := a.first("--channel", "preview"); err != nil {
		t.Fatal(err)
	}
	record := a.pages[releases+"/"+tag+"/build-record.json"]
	if a.read(filepath.Join(a.installation, buildPath)) != string(record) {
		t.Error("the build record is not the published one")
	}
	runtime := a.runtimeRoot(tag)
	manifest := digest([]byte(a.read(filepath.Join(runtime, runtimeManifestName))))
	archive := filepath.Base(runtime)
	if got, want := a.read(filepath.Join(runtime, "runtime.json")),
		`{"archiveSha256":"`+archive+`","manifestSha256":"`+manifest+`","schemaVersion":1}`+"\n"; got != want {
		t.Errorf("runtime receipt %q want %q", got, want)
	}
	if got, want := a.read(filepath.Join(runtime, prefix, "kast-headless.json")),
		`{"schemaVersion":1,"installation":"`+filepath.Join(runtime, prefix)+`","distributionSha256":"`+
			digest([]byte("donor"))+`","bootstrapSha256":"`+digest([]byte("boot first"))+
			`","platformBuild":"262.1.2","kotlinPluginBuild":"262.1.2-IJ"}`+"\n"; got != want {
		t.Errorf("assembly record %q want %q", got, want)
	}
	if link, err := os.Readlink(filepath.Join(runtime, prefix, "current")); err != nil || link != "lib" {
		t.Errorf("link %q, %v", link, err)
	}
	for path, mode := range map[string]os.FileMode{
		runtime: 0o700, filepath.Join(runtime, prefix): 0o555, filepath.Join(runtime, prefix, "lib/base.jar"): 0o444,
		filepath.Join(runtime, prefix, "jbr/Contents/Home/bin/java"): 0o555, a.command: 0o755,
		filepath.Join(a.installation, "config/environment"): 0o600, filepath.Join(a.installation, "bin/kast-complete"): 0o755,
		filepath.Join(a.root, "management.json"): 0o600, filepath.Join(a.installation, "lib/control.jsa"): 0o644,
	} {
		if info, err := os.Lstat(path); err != nil || info.Mode().Perm() != mode {
			t.Errorf("%s: %v, %v want %v", path, err, info.Mode().Perm(), mode)
		}
	}
	reference := `{"releaseTag":"` + tag + `","publicVersion":null,"version":"` + version + `","sourceRevision":"` +
		revision + `","sourceTree":"` + revision + `","buildRecordSha256":"` + digest(record) +
		`","target":"macos-aarch64","bundleSha256":"` + a.target(tag, "bundle") + `","qualificationSha256":"` +
		digest([]byte(`{"qualification":"COMPLETE"}`)) + `","runtimeRoot":"` + runtime + `","runtimeArchiveSha256":"` +
		archive + `","runtimeManifestSha256":"` + manifest + `","policy":"TrackMain","schemaVersion":1}`
	if got := a.read(filepath.Join(a.installation, selectionPath)); got != reference+"\n" {
		t.Errorf("selection %q want %q", got, reference)
	}
	management := digest([]byte("management first"))
	if got, want := a.read(filepath.Join(a.root, "management.json")), `{"installationRoot":"`+a.root+`","executable":"`+
		a.command+`","executableSha256":"`+management+`","product":`+reference+`,"schemaVersion":3}`+"\n"; got != want {
		t.Errorf("management %q want %q", got, want)
	}
	if a.read(a.command) != "management first" ||
		a.read(filepath.Join(a.installation, "config/environment")) != defaultEnvironment {
		t.Error("the command or the configuration differs")
	}
	if !slices.Equal(a.children, []string{"train " + filepath.Join(runtime, prefix, "jbr/Contents/Home/bin/java")}) ||
		!slices.Equal(a.refreshes, []string{a.command}) || len(a.leftovers()) != 0 || present(filepath.Join(a.root, fenceName)) {
		t.Errorf("children %v, refreshes %v, leftovers %v", a.children, a.refreshes, a.leftovers())
	}
	if err := admitPayload(a.installation); err != nil {
		t.Errorf("the installed payload is not admitted: %v", err)
	}
	if !strings.Contains(a.out.String(), "Kast 0.0.123 is installed.") || !strings.Contains(a.log.String(), "Unpacking Kast") {
		t.Errorf("out %q, log %q", a.out.String(), a.log.String())
	}
}

func (a *activation) target(name, asset string) string {
	a.t.Helper()
	var record recordDocument
	if err := json.Unmarshal(a.pages[releases+"/"+name+"/build-record.json"], &record); err != nil {
		a.t.Fatal(err)
	}
	return map[string]string{"bundle": record.Targets[1].Bundle.SHA256}[asset]
}

func TestTheInstallationManifestListsEveryPayloadFileInPythonsPathOrder(t *testing.T) {
	a := newActivation(t)
	a.installed()
	var manifest installationManifest
	if err := json.Unmarshal([]byte(a.read(filepath.Join(a.installation, "installation.json"))), &manifest); err != nil {
		t.Fatal(err)
	}
	var paths []string
	for _, file := range manifest.PayloadFiles {
		paths = append(paths, file.Path)
	}
	// Python compares paths part by part, so a/x precedes a-b/x although "-" sorts before "/".
	want := []string{"bin/kast", "bin/kast-codex", "bin/kast-codex-complete", "bin/kast-complete", "bin/kast-mcp",
		"bin/kast-mcp-complete", "bin/kast-query", "bin/kast-query-complete", "lib/control.jsa", "lib/kast-cli.jar",
		"share/kast/a/x", "share/kast/a-b/x", "share/kast/build-record.json", "share/kast/frontend.json",
		"share/kast/libexec/kast-management", "share/kast/product-selection.json"}
	if !slices.Equal(paths, want) || manifest.InstallationRoot != a.installation || manifest.ExternalAnchors == nil {
		t.Errorf("paths %v\nwant %v", paths, want)
	}
	if !strings.HasSuffix(a.read(filepath.Join(a.installation, "installation.json")), `"schemaVersion":3,"externalAnchors":[]}`) {
		t.Error("the manifest does not end with its schema and an empty anchor list")
	}
}

func TestAnUpgradeRetiresTheInstalledBuildBeforeItSwitches(t *testing.T) {
	a := newActivation(t)
	a.installed()
	workspace := strings.Repeat("c", 64)
	a.write(filepath.Join(a.installation, "config/environment"), "KAST_EDITED=1\n", 0o600)
	a.write(filepath.Join(a.installation, "config/workspaces", workspace, "environment"), "ROOT=1\n", 0o600)
	if err := os.MkdirAll(filepath.Join(a.installation, "config/workspaces", strings.Repeat("d", 64)), 0o700); err != nil {
		t.Fatal(err)
	}
	a.publishBuild(nextTag, nextVersion, nextRevision, newProduct(nextVersion, "next"))
	a.retire = func(command, _ []string) (int, error) {
		// The outgoing build stops while it is still the installation.
		if a.read(filepath.Join(a.installation, managementPath)) != "management first" ||
			!present(a.runtimeRoot(nextTag)) {
			t.Error("retirement did not precede the switch, or the runtime was not published")
		}
		a.write(filepath.Join(a.root, fenceName), `{"installationRoot":"`+a.installation+`","schemaVersion":1}`, 0o600)
		return 0, nil
	}
	if err := a.upgrade("--channel", "preview"); err != nil {
		t.Fatal(err)
	}
	if want := "retire " + filepath.Join(a.installation, managementPath); len(a.children) != 2 || a.children[1] != want {
		t.Errorf("children %v", a.children)
	}
	if a.read(filepath.Join(a.installation, managementPath)) != "management next" || a.read(a.command) != "management next" ||
		a.read(filepath.Join(a.installation, "config/environment")) != "KAST_EDITED=1\n" ||
		a.read(filepath.Join(a.installation, "config/workspaces", workspace, "environment")) != "ROOT=1\n" {
		t.Error("the new build or the kept configuration is missing")
	}
	if present(filepath.Join(a.installation, "config/workspaces", strings.Repeat("d", 64))) ||
		present(filepath.Join(a.root, fenceName)) || len(a.leftovers()) != 0 || !present(a.runtimeRoot(tag)) {
		t.Errorf("leftovers %v", a.leftovers())
	}
	if !strings.Contains(a.log.String(), "  Stopping workspace Hosts\n  Switching to the new build\n") {
		t.Errorf("log %q", a.log.String())
	}
}

func TestAFailedRetirementLeavesTheInstalledBuildInPlace(t *testing.T) {
	for name, retire := range map[string]func([]string, []string) (int, error){
		"status":   func([]string, []string) (int, error) { return 1, nil },
		"no fence": func([]string, []string) (int, error) { return 0, nil },
		"failure":  func([]string, []string) (int, error) { return 0, errors.New("exec format error") },
	} {
		t.Run(name, func(t *testing.T) {
			a := newActivation(t)
			a.installed()
			receipt := a.read(filepath.Join(a.root, "management.json"))
			a.publishBuild(nextTag, nextVersion, nextRevision, newProduct(nextVersion, "next"))
			a.retire = retire
			a.requireFailure(StopRejected, a.upgrade())
			if a.read(filepath.Join(a.installation, managementPath)) != "management first" ||
				a.read(filepath.Join(a.root, "management.json")) != receipt || a.read(a.command) != "management first" ||
				len(a.leftovers()) != 0 || len(a.refreshes) != 0 {
				t.Errorf("the installation changed, leftovers %v", a.leftovers())
			}
			if err := admitPayload(a.installation); err != nil {
				t.Error(err)
			}
		})
	}
}

func TestTheNextRunUndoesAnInterruptedSwitchAndRemovesWhatItLeft(t *testing.T) {
	a := newActivation(t)
	a.installed()
	manifest := a.read(filepath.Join(a.installation, "installation.json"))
	retired := filepath.Join(a.share, ".kast-retiring-"+sha256Hex([]byte(a.root))[:32]+"-"+newUUID())
	if err := os.Rename(a.installation, retired); err != nil {
		t.Fatal(err)
	}
	staged := filepath.Join(a.share, ".kast-install-"+newUUID())
	sealed := filepath.Join(a.share, ".kast-runtime-abcd_123", "host")
	a.write(filepath.Join(staged, "bin/kast"), "partial", 0o755)
	a.write(filepath.Join(sealed, "file"), "partial", 0o444)
	if err := os.Chmod(sealed, 0o555); err != nil {
		t.Fatal(err)
	}
	a.write(filepath.Join(a.share, ".kast-prepare-unrelated", "kept"), "", 0o600)
	if err := a.upgrade("--dry-run"); err != nil {
		t.Fatal(err)
	}
	if a.read(filepath.Join(a.installation, "installation.json")) != manifest ||
		!slices.Equal(a.leftovers(), []string{".kast-prepare-unrelated"}) || len(a.children) != 0 {
		t.Errorf("leftovers %v, children %v", a.leftovers(), a.children)
	}
	if !strings.Contains(a.out.String(), "Kast 0.0.123 is verified. Nothing was installed.") {
		t.Errorf("out %q", a.out.String())
	}
}

func TestADryRunVerifiesTheBuildWithoutChangingAnything(t *testing.T) {
	a := newActivation(t)
	a.installed()
	receipt := a.read(filepath.Join(a.root, "management.json"))
	a.publishBuild(nextTag, nextVersion, nextRevision, newProduct(nextVersion, "next"))
	if err := a.upgrade("--dry-run", "--json"); err != nil {
		t.Fatal(err)
	}
	var planned struct {
		Type    string `json:"type"`
		Product struct {
			Version string `json:"version"`
		} `json:"product"`
	}
	if err := json.Unmarshal(a.out.Bytes(), &planned); err != nil || planned.Type != "PRODUCT_PLANNED" ||
		planned.Product.Version != nextVersion {
		t.Errorf("%v, out %q", err, a.out.String())
	}
	if a.read(filepath.Join(a.root, "management.json")) != receipt || present(a.runtimeRoot(nextTag)) ||
		len(a.children) != 0 || len(a.refreshes) != 0 || len(a.leftovers()) != 0 {
		t.Errorf("children %v, leftovers %v", a.children, a.leftovers())
	}
}

func TestDamagedArchivesAreRejectedBeforeTheInstalledBuildStops(t *testing.T) {
	for name, damage := range map[string]func(*testBuild){
		"changed file": func(p *testBuild) {
			p.mutate = func(packed *archives) { packed.base[prefix+"/lib/base.jar"] = []byte("other") }
		},
		"in both archives": func(p *testBuild) {
			p.mutate = func(packed *archives) { packed.runtime[prefix+"/lib/base.jar"] = []byte("base") }
		},
		"unlisted member": func(p *testBuild) {
			p.mutate = func(packed *archives) { packed.runtime["host/extra"] = []byte("x") }
		},
		"other base": func(p *testBuild) {
			p.mutate = func(packed *archives) {
				packed.runtime[runtimeMarkerName] = bytes.Replace(packed.marker, []byte(`"schemaVersion":1`),
					[]byte(`"schemaVersion":2`), 1)
			}
		},
		"escaping link": func(p *testBuild) { p.runtime[4].link = "../../../../etc" },
		"entry beneath another": func(p *testBuild) {
			p.runtime = append(p.runtime, runtimeMember{path: prefix + "/current/inner", data: "x"})
		},
		"unqualified management": func(p *testBuild) {
			p.files = maps(p.files)
			p.files["share/kast/libexec/kast-management"] = payloadMember{"other", 0o755}
			p.mutate = nil
		},
		"payload outside its roots": func(p *testBuild) {
			p.files = maps(p.files)
			p.files["etc/kast"] = payloadMember{"x", 0o644}
		},
	} {
		t.Run(name, func(t *testing.T) {
			a := newActivation(t)
			a.installed()
			next := newProduct(nextVersion, "next")
			damage(next)
			if name == "unqualified management" {
				// The record names the qualified executable, not the archived one.
				a.publishBuild(nextTag, nextVersion, nextRevision, next)
				var record map[string]any
				raw := a.pages[releases+"/"+nextTag+"/build-record.json"]
				json.Unmarshal(raw, &record)
				record["targets"].([]any)[1].(map[string]any)["qualification"].(map[string]any)["controlSha256"] =
					digest([]byte("qualified"))
				raw = a.encode(record)
				a.pages[releases+"/"+nextTag+"/build-record.json"] = raw
				a.builds[0]["recordSha256"] = digest(raw)
				a.pages[catalogURL] = a.encode(map[string]any{"schemaVersion": 2, "builds": a.builds})
			} else {
				a.publishBuild(nextTag, nextVersion, nextRevision, next)
			}
			a.requireFailure(DownloadInvalid, a.upgrade())
			if !slices.Equal(a.children, nil) || a.read(a.command) != "management first" || len(a.leftovers()) != 0 ||
				present(a.runtimeRoot(nextTag)) {
				t.Errorf("children %v, leftovers %v", a.children, a.leftovers())
			}
		})
	}
}

func maps(files map[string]payloadMember) map[string]payloadMember {
	copied := map[string]payloadMember{}
	for name, file := range files {
		copied[name] = file
	}
	return copied
}

func TestAFailedTrainingLeavesNoArchiveAndInstalls(t *testing.T) {
	a := newActivation(t)
	a.train = func(archive string) (int, error) {
		a.write(archive, "partial", 0o600)
		return 1, nil
	}
	a.installed()
	if present(filepath.Join(a.installation, "lib/control.jsa")) {
		t.Error("a failed training left its archive")
	}
	if err := admitPayload(a.installation); err != nil {
		t.Error(err)
	}
}

func TestAFirstInstallationRefusesWhatItWouldReplace(t *testing.T) {
	for name, prepare := range map[string]func(*activation){
		"command":   func(a *activation) { a.write(a.command, "someone else's", 0o755) },
		"receipt":   func(a *activation) { a.write(filepath.Join(a.root, "management.json"), "{}", 0o600) },
		"workspace": func(a *activation) { a.write(filepath.Join(a.share, "kast-workspaces/containers/x/y"), "", 0o600) },
	} {
		t.Run(name, func(t *testing.T) {
			a := newActivation(t)
			prepare(a)
			err := a.first("--channel", "preview")
			var rejection *Rejection
			if !errors.As(err, &rejection) || present(a.installation) || len(a.children) != 0 {
				t.Errorf("%v, children %v", err, a.children)
			}
		})
	}
}

func TestACommandThatTheInstallationDidNotWriteBlocksAnUpgrade(t *testing.T) {
	a := newActivation(t)
	a.installed()
	a.write(a.command, "replaced", 0o755)
	a.requireFailure(CommandForeign, a.upgrade())
	if len(a.children) != 0 {
		t.Errorf("children %v", a.children)
	}
}

func TestJSONPrintsOneInstalledDocumentOrOneRejection(t *testing.T) {
	a := newActivation(t)
	if err := a.first("--channel", "preview", "--json"); err != nil {
		t.Fatal(err)
	}
	var installed map[string]any
	if err := json.Unmarshal(a.out.Bytes(), &installed); err != nil || installed["type"] != "PRODUCT_INSTALLED" ||
		installed["hostState"] != "NOT_STARTED" || installed["executable"] != a.command ||
		strings.Count(a.out.String(), "\n") != 1 || strings.Contains(a.log.String(), "Unpacking") {
		t.Errorf("%v, out %q, log %q", err, a.out.String(), a.log.String())
	}
	a.write(filepath.Join(a.root, "management.json"), `{"product":{"policy":"PinnedBuild"}}`, 0o600)
	if err := a.upgrade("--json"); err == nil || !strings.Contains(a.out.String(), `"failure":"PINNED_BUILD"`) ||
		strings.Count(a.out.String(), "\n") != 1 {
		t.Errorf("%v, out %q", err, a.out.String())
	}
}

func TestARepairInstallsTheExactInstalledBuildAgain(t *testing.T) {
	a := newActivation(t)
	a.installed()
	a.publishBuild(nextTag, nextVersion, nextRevision, newProduct(nextVersion, "next"))
	a.write(filepath.Join(a.installation, "config/environment"), "KEPT=1\n", 0o600)
	a.calls = nil
	if err := a.upgrade("--repair"); err != nil {
		t.Fatal(err)
	}
	if a.read(filepath.Join(a.installation, managementPath)) != "management first" ||
		slices.Contains(a.calls, filepath.Base(catalogURL)) || len(a.children) != 2 ||
		a.read(filepath.Join(a.installation, "config/environment")) != "KEPT=1\n" {
		t.Errorf("calls %v, children %v", a.calls, a.children)
	}
	if err := admitPayload(a.installation); err != nil {
		t.Error(err)
	}
}

func TestADamagedInstallationIsNeitherRetiredNorReplaced(t *testing.T) {
	for name, damage := range map[string]func(*activation){
		"changed file": func(a *activation) { a.write(filepath.Join(a.installation, "bin/kast"), "other", 0o755) },
		"extra file":   func(a *activation) { a.write(filepath.Join(a.installation, "share/extra"), "x", 0o644) },
		"no manifest":  func(a *activation) { os.Remove(filepath.Join(a.installation, "installation.json")) },
		"no record":    func(a *activation) { os.Remove(filepath.Join(a.root, "management.json")) },
		"foreign configuration": func(a *activation) {
			a.write(filepath.Join(a.installation, "config/selected-ide.json"), "{}", 0o600)
		},
	} {
		t.Run(name, func(t *testing.T) {
			a := newActivation(t)
			a.installed()
			damage(a)
			a.requireFailure(InstallationDamaged, a.upgrade("--channel", "preview"))
			if len(a.children) != 0 || len(a.leftovers()) != 0 {
				t.Errorf("children %v, leftovers %v", a.children, a.leftovers())
			}
		})
	}
}

func TestWhatCannotBeUpgradedIsRejectedBeforeAnyRequest(t *testing.T) {
	a := newActivation(t)
	a.requireFailure(NotInstalled, a.upgrade())
	a.installed()
	a.calls = nil
	a.write(filepath.Join(a.root, "management.json"), `{"product":{"policy":"PinnedBuild"}}`, 0o600)
	a.requireFailure(PinnedBuild, a.upgrade())
	a.environment = []string{"KAST_INSTALL_ROOT=/elsewhere"}
	a.requireFailure(StorageRejected, a.upgrade())
	a.environment = nil
	for _, arguments := range [][]string{
		{"--channel"}, {"--channel", "beta"}, {"--repair", "--channel", "stable"}, {"--version=1"}, {"extra"},
	} {
		a.requireFailure(UsageRejected, a.upgrade(arguments...))
	}
	for _, arguments := range [][]string{{"--repair"}, {"--dry-run"}, {"--channel", "stable", "extra"}} {
		if err := a.first(arguments...); !strings.Contains(fmt.Sprint(err), "kast install [") {
			t.Errorf("%v: %v", arguments, err)
		}
	}
	if len(a.calls) != 0 {
		t.Errorf("requests %v", a.calls)
	}
}

func TestAnUnfinishedPythonUpgradeIsFinishedByItsOwnEngine(t *testing.T) {
	a := newActivation(t)
	a.installed()
	a.write(filepath.Join(a.root, "recovery/replacement/receipt.json"), "{}", 0o600)
	a.requireFailure(RecoveryRequired, a.upgrade())
	a.write(filepath.Join(a.installation, bridgePath), "engine", 0o644)
	var manifest installationManifest
	json.Unmarshal([]byte(a.read(filepath.Join(a.installation, "installation.json"))), &manifest)
	manifest.PayloadFiles = append(manifest.PayloadFiles, payloadFile{bridgePath, "sha256:" + digest([]byte("engine")), 0o644})
	a.write(filepath.Join(a.installation, "installation.json"), string(a.encode(manifest)), 0o600)
	var commands [][]string
	a.engine = func(command []string, out io.Writer) (int, error) {
		commands = append(commands, command)
		io.WriteString(out, `{"type":"PRODUCT_INSTALLED","product":{"version":"0.0.123","publicVersion":null}}`)
		return 0, nil
	}
	a.calls = nil
	if err := a.upgrade("--channel", "preview"); err != nil {
		t.Fatal(err)
	}
	if len(commands) != 1 || !slices.Contains(commands[0], "--recover-only") || len(a.calls) != 0 ||
		!strings.Contains(a.out.String(), "Kast 0.0.123 is installed.") {
		t.Errorf("commands %v, requests %v, out %q", commands, a.calls, a.out.String())
	}
	a.engine = func(_ []string, out io.Writer) (int, error) {
		io.WriteString(out, `{"type":"RECOVERY_REQUIRED","failure":{"stage":"FENCE","reason":"CONCURRENT_CHANGE"}}`)
		return 1, nil
	}
	if rejection := a.requireFailure(RecoveryRequired, a.upgrade()); !strings.Contains(rejection.Detail, "holding new requests") {
		t.Errorf("detail %q", rejection.Detail)
	}
}

func TestAChildThatOutlivesItsTimeoutIsStopped(t *testing.T) {
	started := time.Now()
	// The descendant outlives a shell that only waits for it, as a retiring JVM's children can.
	status, err := runChild(context.Background(), []string{"/bin/sh", "-c", "sleep 5 & wait"}, nil, 100*time.Millisecond)
	if err != nil || status == 0 || time.Since(started) > 3*time.Second {
		t.Errorf("status %d, %v after %v", status, err, time.Since(started))
	}
}

func TestATerminalShowsHowLongAStageRuns(t *testing.T) {
	var log bytes.Buffer
	s := &stages{log: &log, terminal: true, now: time.Now, stopped: make(chan struct{}), ended: make(chan struct{})}
	close(s.ended)
	io.WriteString(s, `{"stage":"DOWNLOAD","outcome":"STARTED"}`+"\n"+`{"stage":"ARCHIVE","out`)
	io.WriteString(s, `come":"STARTED"}`+"\n")
	s.enter("Switching to the new build")
	s.end()
	want := eraseLine + "  Downloading and verifying" + eraseLine + "  Downloading and verifying\n" +
		eraseLine + "  Unpacking" + eraseLine + "  Unpacking\n" +
		eraseLine + "  Switching to the new build" + eraseLine + "  Switching to the new build\n"
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
