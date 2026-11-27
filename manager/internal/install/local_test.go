package install

import (
	"encoding/json/v2"
	"errors"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

// localDirectory writes a product directory with its record and payload/, as local_product_install.py does. A local
// record holds one target and local producers; a published record holds both targets.
func (a *activation) localDirectory(built *testBuild, schema int) string {
	a.t.Helper()
	directory := filepath.Join(a.t.TempDir(), "product")
	built.local, built.unsplit = schema == localRecordSchema, schema == unsplitRecordSchema
	mac, assets := built.assets(a.t)
	record := map[string]any{"schemaVersion": schema, "repository": repository, "sourceRevision": nextRevision,
		"sourceTree": nextRevision, "version": built.version, "targets": []any{mac},
		"producer": map[string]any{"runId": 1, "runAttempt": 1, "workflow": ".github/workflows/ci.yml"}}
	if built.local {
		record["producer"] = map[string]any{"kind": "LOCAL"}
	} else {
		other := newProduct(built.version, "linux")
		other.target, other.unsplit = "linux-x86_64", built.unsplit
		linux, _ := other.assets(a.t)
		record["targets"] = []any{linux, mac}
	}
	for name, raw := range assets {
		a.write(filepath.Join(directory, "payload", name), string(raw), 0o644)
	}
	a.write(filepath.Join(directory, "build-record.json"), string(a.encode(record)), 0o644)
	return directory
}

func TestALocalProductInstallsWithoutARequestAndCannotBeRepairedFromTheReleases(t *testing.T) {
	a := newActivation(t)
	directory := a.localDirectory(newProduct(nextVersion, "local"), localRecordSchema)
	a.calls = nil
	if err := a.first("--local-artifact", directory); err != nil {
		t.Fatal(err)
	}
	var selection productReference
	if err := json.Unmarshal([]byte(a.read(filepath.Join(a.installation, selectionPath))), &selection); err != nil {
		t.Fatal(err)
	}
	runtime := selection.RuntimeRoot
	if len(a.calls) != 0 || selection.ReleaseTag != nextTag || selection.Policy != pinnedBuild ||
		selection.PublicVersion != nil || a.read(filepath.Join(runtime, prefix, "lib/base.jar")) != "base" ||
		a.read(filepath.Join(a.installation, managementPath)) != "management local" {
		t.Errorf("requests %v, selection %+v", a.calls, selection)
	}
	if !strings.Contains(a.out.String(), "Kast "+nextVersion+" (from "+directory+")") {
		t.Errorf("out %q", a.out.String())
	}
	a.requireFailure(LocalArtifactRequired, a.upgrade("--repair"))
}

func TestAPublishedRecordInAProductDirectoryInstalls(t *testing.T) {
	a := newActivation(t)
	directory := a.localDirectory(newProduct(nextVersion, "candidate"), recordSchema)
	a.calls = nil
	if err := a.first("--local-artifact", directory, "--json"); err != nil {
		t.Fatal(err)
	}
	if len(a.calls) != 0 || a.read(filepath.Join(a.installation, managementPath)) != "management candidate" ||
		!strings.Contains(a.out.String(), `"type":"PRODUCT_INSTALLED"`) {
		t.Errorf("requests %v, out %q", a.calls, a.out.String())
	}
}

// local_product_install.py installs through local_product_probe.py's schema 2 record: both targets, CI producers,
// and runtimes without a base.
func TestAnUnsplitRecordInAProductDirectoryInstalls(t *testing.T) {
	a := newActivation(t)
	directory := a.localDirectory(newProduct(nextVersion, "unsplit"), unsplitRecordSchema)
	a.calls = nil
	if err := a.first("--local-artifact", directory); err != nil {
		t.Fatal(err)
	}
	var selection productReference
	if err := json.Unmarshal([]byte(a.read(filepath.Join(a.installation, selectionPath))), &selection); err != nil {
		t.Fatal(err)
	}
	if len(a.calls) != 0 || a.read(filepath.Join(a.installation, managementPath)) != "management unsplit" ||
		a.read(filepath.Join(selection.RuntimeRoot, prefix, "lib/base.jar")) != "base" {
		t.Errorf("requests %v, selection %+v", a.calls, selection)
	}
}

func TestALocalPayloadThatDiffersFromItsRecordChangesNothing(t *testing.T) {
	a := newActivation(t)
	directory := a.localDirectory(newProduct(nextVersion, "local"), localRecordSchema)
	a.write(filepath.Join(directory, "payload", "qualification-macos-aarch64.json"), "{}", 0o644)
	a.requireFailure(DownloadInvalid, a.first("--local-artifact", directory))
	if present(a.installation) || len(a.resets) != 0 || len(a.children) != 0 {
		t.Errorf("resets %v, children %v", a.resets, a.children)
	}
}

func TestOnlyALocalDirectoryAdmitsALocalRecord(t *testing.T) {
	a := newActivation(t)
	var record map[string]any
	if err := json.Unmarshal(a.pages[releases+"/"+tag+"/build-record.json"], &record); err != nil {
		t.Fatal(err)
	}
	record["schemaVersion"] = localRecordSchema
	raw := a.encode(record)
	a.pages[releases+"/"+tag+"/build-record.json"] = raw
	a.builds[0]["recordSha256"] = digest(raw)
	a.pages[catalogURL] = a.encode(map[string]any{"schemaVersion": 2, "builds": a.builds})
	a.requireFailure(ReleaseUnsupported, a.first("--channel", "preview"))
	for _, arguments := range [][]string{{"--local-artifact", "/product"}, {"--replace-existing"},
		{"--local-artifact", "relative"}} {
		a.requireFailure(UsageRejected, a.upgrade(arguments...))
	}
	a.requireFailure(UsageRejected, a.first("--local-artifact", "/product", "--channel", "stable"))
}

// damaged installs the fixture's build and then adds a file that the installation does not record.
func (a *activation) damaged() {
	a.t.Helper()
	a.installed()
	a.write(filepath.Join(a.installation, "share/extra"), "x", 0o644)
	a.reset = func(arguments []string) error {
		if arguments != nil {
			removeTree(a.root)
			os.Remove(a.command)
		}
		return nil
	}
}

func TestAnInstallationThatCannotBeReplacedIsResetOnlyWithConsent(t *testing.T) {
	a := newActivation(t)
	a.damaged()
	rejection := a.requireFailure(InstallationDamaged, a.first("--channel", "preview"))
	if !strings.Contains(rejection.Next, "--replace-existing") || len(a.resets) != 0 || !present(a.installation) {
		t.Errorf("next %q, resets %v", rejection.Next, a.resets)
	}
	if err := a.first("--channel", "preview", "--replace-existing"); err != nil {
		t.Fatal(err)
	}
	if !slices.EqualFunc(a.resets, [][]string{{"--yes"}}, slices.Equal) || present(filepath.Join(a.installation, "share/extra")) {
		t.Errorf("resets %v", a.resets)
	}
	if err := admitPayload(a.installation); err != nil {
		t.Error(err)
	}
}

func TestAPersonAtTheTerminalIsShownTheResetAndAsked(t *testing.T) {
	for _, agreed := range []bool{false, true} {
		a := newActivation(t)
		a.damaged()
		a.terminal, a.answer = true, &agreed
		err := a.first("--channel", "preview")
		want := [][]string{nil}
		if agreed {
			want = append(want, []string{"--yes"})
		}
		if !slices.EqualFunc(a.resets, want, slices.Equal) || (err == nil) != agreed {
			t.Errorf("agreed %v: %v, resets %v", agreed, err, a.resets)
		}
		var rejection *Rejection
		if !agreed && (!errors.As(err, &rejection) || rejection.Failure != Interrupted || !present(a.installation)) {
			t.Errorf("a refusal changed something: %v", err)
		}
	}
}

func TestAnInstallOnATerminalOffersAgentsAndAnUpgradeRefreshesThem(t *testing.T) {
	a := newActivation(t)
	a.terminal = true
	if err := a.first("--channel", "preview"); err != nil {
		t.Fatal(err)
	}
	if a.connects != 1 || len(a.refreshes) != 0 {
		t.Errorf("connects %d, refreshes %v", a.connects, a.refreshes)
	}
	a.publishBuild(nextTag, nextVersion, nextRevision, newProduct(nextVersion, "next"))
	if err := a.upgrade(); err != nil {
		t.Fatal(err)
	}
	if a.connects != 1 || !slices.Equal(a.refreshes, []string{a.command}) {
		t.Errorf("connects %d, refreshes %v", a.connects, a.refreshes)
	}
}
