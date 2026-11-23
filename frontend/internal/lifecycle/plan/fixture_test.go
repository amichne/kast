package plan

import (
	"fmt"
	"os"
	"strings"
	"testing"

	"github.com/amichne/kast/frontend/internal/lifecycle/envelope"
	"github.com/amichne/kast/frontend/internal/lifecycle/failure"
	"github.com/amichne/kast/frontend/internal/lifecycle/layout"
	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

const (
	home       = "/home/user"
	oldHex     = "0123456789abcdef"
	runtimeHex = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
	baseHex    = "9999999999999999999999999999999999999999999999999999999999999999"
	controlHex = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
	semantic   = "io.github.amichne.kast.semantic.0123456789abcdef0123456789abcdef"
)

// sequence numbers identifiers 1, 2, 3, ... so expected plans spell them literally.
type sequence struct{}

func (sequence) ID(n int) record.VersionID {
	id, _ := record.ParseVersionID(fmt.Sprintf("%016x", n+1))
	return id
}

func id(n int) string { return fmt.Sprintf("%016x", n) }

func must[T any](value T, ok bool) T {
	if !ok {
		panic("literal value rejected")
	}
	return value
}

func paths(t *testing.T) Paths {
	t.Helper()
	admitted, rejected := AdmitHome(home)
	if rejected != nil {
		t.Fatal(rejected)
	}
	return admitted
}

func buildRecord(t *testing.T) []byte {
	t.Helper()
	raw, err := os.ReadFile("../record/testdata/build-record.json")
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

// otherRecord is the same build record with different bytes, so its digest differs: a new release of the same
// content, which binds the same runtime and base entries.
func otherRecord(t *testing.T) []byte { return append(buildRecord(t), ' ') }

func previewIdentity() record.Identity {
	return must(record.NewIdentity(record.DeveloperTag(must(record.ParseDeveloperVersion("0.0.42"))),
		record.PolicyTrackMain, nil))
}

func digest(text string) record.Digest { return must(record.ParseDigest(text)) }

// versionRecords is a consistent installed version for a record's bytes.
func versionRecords(t *testing.T, version string, build []byte) *layout.VersionRecords {
	t.Helper()
	v := must(record.ParseVersionID(version))
	base := digest(baseHex)
	selection := record.NewProductSelection(v, record.TargetLinuxX8664, record.DigestOf(build), previewIdentity(),
		must(record.ParseRevision(strings.Repeat("1", 40))), digest(runtimeHex), digest(strings.Repeat("f", 64)),
		&base).Encode()
	marker := []byte(record.LayoutMarker)
	manifest, err := record.NewPayloadManifest(v, []record.PayloadFile{
		{Path: record.BuildRecordPath, SHA256: record.DigestOf(build), Bytes: int64(len(build)), Mode: record.ModeReadOnly},
		{Path: record.LayoutMarkerPath, SHA256: record.DigestOf(marker), Bytes: 2, Mode: record.ModeReadOnly},
		{Path: record.ProductSelectionPath, SHA256: record.DigestOf(selection), Bytes: int64(len(selection)),
			Mode: record.ModeReadOnly},
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	return &layout.VersionRecords{Manifest: layout.Contents(manifest.Encode()), Selection: layout.Contents(selection),
		Build: layout.Contents(build), Marker: layout.Contents(marker)}
}

func publicLink(p Paths) layout.Entry { return layout.Link("kast", true, p.PublicTarget()) }

// installedObservation is a current installation with both stores, the public link and an occupied container store.
func installedObservation(t *testing.T) layout.Observation {
	p := paths(t)
	return layout.Observation{
		Share: []layout.Entry{layout.Directory("kast", true), layout.File("kast.lock", true),
			layout.Directory("kast-workspaces", true), layout.Directory("kast-runtime-bases", true)},
		Root: []layout.Entry{layout.Link("current", true, "versions/"+oldHex), layout.Directory("versions", true),
			layout.Directory("config", true), layout.Directory("state", true)},
		Versions:     []layout.Entry{layout.Directory(oldHex, true)},
		Current:      versionRecords(t, oldHex, buildRecord(t)),
		RuntimeStore: []layout.Entry{layout.Directory(runtimeHex, true)},
		BaseStore:    []layout.Entry{layout.Directory(baseHex, true)},
		Containers:   layout.ContainersOccupied,
		Bin:          []layout.Entry{publicLink(p)},
	}
}

func observe(t *testing.T, o layout.Observation) layout.Snapshot {
	t.Helper()
	if o.Containers == 0 {
		o.Containers = layout.ContainersAbsent
	}
	snapshot, err := layout.Observe(o)
	if err != nil {
		t.Fatalf("observation rejected: %v", err)
	}
	return snapshot
}

// withStaging adds the command's own staging tree to an observation, as A9 sees it.
func withStaging(o layout.Observation, staging string) layout.Observation {
	o.Root = append(append([]layout.Entry{}, o.Root...), layout.Directory(".staging-"+staging, true))
	return o
}

func healthyHome() HomeFacts {
	return HomeFacts{UserID: 1000, Local: PathOwnedDirectory, Share: PathOwnedDirectory, Bin: PathOwnedDirectory}
}

func handoff(t *testing.T, operation envelope.Operation, build []byte) envelope.Handoff {
	t.Helper()
	selection := envelope.NewSelection(previewIdentity(), must(record.ParseRevision(strings.Repeat("1", 40))))
	value, ok := envelope.NewHandoff(1, operation, selection, record.DigestOf(build), digest(controlHex),
		envelope.OutputText)
	if !ok {
		t.Fatal("handoff rejected")
	}
	return value
}

func retiredDocument() []byte {
	return []byte(`{"schemaVersion":1,"outcome":"RETIRED","semanticLabel":"` + semantic +
		`","brokerLabels":[],"hosts":[{"pid":4100,"startTime":5}]}` + "\n")
}

func cleanRetirement() RetirementObservation {
	return RetirementObservation{Exit: must(failure.ParseExitCode(0)), Document: retiredDocument(),
		Supervisor: SupervisorObservation{Available: true, Kind: failure.SupervisorSystemd}}
}

// upgradeInput is a representative upgrade: a current installation, a new record with the same entries, a clean
// retirement, and no process left using the outgoing version.
func upgradeInput(t *testing.T) InstallInput {
	t.Helper()
	installed := installedObservation(t)
	next := otherRecord(t)
	return InstallInput{
		Operation: envelope.OperationUpgrade, Host: record.TargetLinuxX8664, Paths: paths(t), IDs: sequence{},
		Home: healthyHome(), Before: observe(t, installed), Locked: observe(t, installed),
		Handoff: handoff(t, envelope.OperationUpgrade, next), RecordBytes: next, OwnExecutable: digest(controlHex),
		RuntimeManifest: digest(strings.Repeat("f", 64)),
		Runtime:         EntryObservation{Presence: EntryPresent}, Base: EntryObservation{Presence: EntryPresent},
		LayoutMarker: true, Relocked: observe(t, withStaging(installed, id(2))), Retirement: cleanRetirement(),
	}
}

// freshInput is a first install into an empty share directory with no public command directory.
func freshInput(t *testing.T) InstallInput {
	t.Helper()
	empty := layout.Observation{}
	facts := healthyHome()
	facts.Bin = PathMissing
	build := buildRecord(t)
	return InstallInput{
		Operation: envelope.OperationInstall, Host: record.TargetLinuxX8664, Paths: paths(t), IDs: sequence{},
		Home: facts, Before: observe(t, empty), Locked: observe(t, empty),
		Handoff: handoff(t, envelope.OperationInstall, build), RecordBytes: build, OwnExecutable: digest(controlHex),
		RuntimeManifest: digest(strings.Repeat("f", 64)),
		Runtime:         EntryObservation{Presence: EntryMissing}, Base: EntryObservation{Presence: EntryMissing},
		LayoutMarker: true,
		Relocked: observe(t, layout.Observation{Share: []layout.Entry{layout.Directory("kast", true),
			layout.Directory("kast-workspaces", true), layout.Directory("kast-runtime-bases", true)},
			Root:         []layout.Entry{layout.Directory("versions", true), layout.Directory(".staging-"+id(2), true)},
			RuntimeStore: []layout.Entry{layout.Directory(runtimeHex, true)},
			BaseStore:    []layout.Entry{layout.Directory(baseHex, true)},
			Containers:   layout.ContainersEmpty}),
	}
}

func strings_(intents []Intent) []string {
	out := make([]string, len(intents))
	for i, intent := range intents {
		out[i] = intent.String()
	}
	return out
}

func expectSequence(t *testing.T, name string, got Plan, want []string) {
	t.Helper()
	actual := strings_(got.Intents())
	if strings.Join(actual, "\n") != strings.Join(want, "\n") {
		t.Errorf("%s: intents differ\ngot:\n  %s\nwant:\n  %s", name, strings.Join(actual, "\n  "),
			strings.Join(want, "\n  "))
	}
}

func expectOutcome(t *testing.T, name string, got Plan, want failure.OutcomeKind) {
	t.Helper()
	if got.Result.Failure != nil || got.Result.Outcome == nil || got.Result.Outcome.Kind() != want {
		t.Errorf("%s: result %+v, want %v", name, got.Result, want)
	}
}

func expectFailure(t *testing.T, name string, got Plan, want failure.Code) {
	t.Helper()
	if got.Result.Outcome != nil || got.Result.Failure == nil || got.Result.Failure.Code() != want {
		t.Errorf("%s: result %+v, want %v", name, got.Result, want)
	}
}
