package layout

import (
	"os"
	"reflect"
	"strings"
	"testing"

	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

const (
	versionHex = "0123456789abcdef"
	witnessHex = "00000000000000aa"
	otherHex   = "1111111111111111"
)

// installedVersion is one consistent version directory, built from the record package's build-record fixture.
type installedVersion struct {
	build, selection, manifest []byte
}

func consistentVersion(t *testing.T) installedVersion {
	t.Helper()
	build, err := os.ReadFile("../record/testdata/build-record.json")
	if err != nil {
		t.Fatal(err)
	}
	id, _ := record.ParseVersionID(versionHex)
	developer, _ := record.ParseDeveloperVersion("0.0.42")
	identity, _ := record.NewIdentity(record.DeveloperTag(developer), record.PolicyTrackMain, nil)
	revision, _ := record.ParseRevision(strings.Repeat("1", 40))
	runtime, _ := record.ParseDigest(strings.Repeat("e", 64))
	manifestDigest, _ := record.ParseDigest(strings.Repeat("f", 64))
	base, _ := record.ParseDigest(strings.Repeat("9", 64))
	selection := record.NewProductSelection(id, record.TargetLinuxX8664, record.DigestOf(build), identity, revision,
		runtime, manifestDigest, &base).Encode()
	marker := []byte(record.LayoutMarker)
	manifest, err := record.NewPayloadManifest(id, []record.PayloadFile{
		{Path: record.BuildRecordPath, SHA256: record.DigestOf(build), Bytes: int64(len(build)), Mode: record.ModeReadOnly},
		{Path: record.LayoutMarkerPath, SHA256: record.DigestOf(marker), Bytes: 2, Mode: record.ModeReadOnly},
		{Path: record.ProductSelectionPath, SHA256: record.DigestOf(selection), Bytes: int64(len(selection)),
			Mode: record.ModeReadOnly},
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	return installedVersion{build, selection, manifest.Encode()}
}

func (v installedVersion) records() *VersionRecords {
	return &VersionRecords{Contents(v.manifest), Contents(v.selection), Contents(v.build),
		Contents([]byte(record.LayoutMarker))}
}

func rootDirectory() Entry { return Directory(RootName, true) }

func currentLink() Entry { return Link(CurrentName, true, "versions/"+versionHex) }

func installed(t *testing.T) Observation {
	return Observation{
		Share:      []Entry{rootDirectory(), File(LockName, true)},
		Root:       []Entry{currentLink(), Directory(VersionsName, true), Directory(ConfigName, true)},
		Versions:   []Entry{Directory(versionHex, true)},
		Current:    consistentVersion(t).records(),
		Containers: ContainersAbsent,
	}
}

func classify(t *testing.T, o Observation) Classification {
	t.Helper()
	if o.Containers == 0 {
		o.Containers = ContainersAbsent
	}
	snapshot, err := Observe(o)
	if err != nil {
		t.Fatalf("observation rejected: %v", err)
	}
	return Classify(snapshot)
}

func expect(t *testing.T, name string, got Classification, want Classification) {
	t.Helper()
	if !reflect.DeepEqual(got, want) {
		t.Errorf("%s: got %#v, want %#v", name, got, want)
	}
}

func TestEveryShareLevelNameHasItsTreatment(t *testing.T) {
	witness, _ := record.ParseVersionID(witnessHex)
	cases := []struct {
		entry Entry
		kind  ShareKind
		want  Classification
	}{
		{rootDirectory(), ShareRoot, Incomplete{}},
		{File(LockName, true), ShareLock, Absent{}},
		{Directory(WorkspaceStoreName, true), ShareWorkspaceStore, Absent{}},
		{Directory(BaseStoreName, true), ShareBaseStore, Absent{}},
		{File(LifetimeLockName, true), ShareLifetimeLock, Absent{}},
		{Directory(RemovingPrefix+witnessHex, true), ShareRemoving, Absent{}},
		{Directory(WitnessPrefix+witnessHex, true), ShareWitness, Residual{[]record.VersionID{witness}, Absent{}}},
		{File(".kast-uninstall-"+strings.Repeat("a", 64)+".json", true), ShareLegacyUninstallJournal,
			Legacy{record.At(record.RootHomeShare, ".kast-uninstall-"+strings.Repeat("a", 64)+".json")}},
		{File(".kast-reset-"+strings.Repeat("b", 32)+".json", true), ShareLegacyResetFence,
			Legacy{record.At(record.RootHomeShare, ".kast-reset-"+strings.Repeat("b", 32)+".json")}},
		{Directory(".kast-install-01234567-89ab-cdef-0123-456789abcdef", true), ShareLegacyInstallStage,
			Legacy{record.At(record.RootHomeShare, ".kast-install-01234567-89ab-cdef-0123-456789abcdef")}},
		{File(".kast-reset-"+strings.Repeat("b", 32)+".json.lock", true), ShareLegacyResetLock, Absent{}},
		{Directory("other-application", false), ShareOther, Absent{}},
		{File(".kast-uninstall-"+strings.Repeat("a", 63)+".json", true), ShareOther, Absent{}},
		{File(".kast-reset-"+strings.Repeat("B", 32)+".json", true), ShareOther, Absent{}},
		{Directory(".kast-install-01234567-89AB-cdef-0123-456789abcdef", true), ShareOther, Absent{}},
		{Directory(RemovingPrefix+"short", true), ShareOther, Absent{}},
		{Directory(WitnessPrefix+strings.ToUpper(witnessHex)+"x", true), ShareOther, Absent{}},
		{File("kast.lock.old", true), ShareOther, Absent{}},
	}
	for _, c := range cases {
		if got := ClassifyShareName(c.entry.name).Kind; got != c.kind {
			t.Errorf("%s: kind %d, want %d", c.entry.name, got, c.kind)
		}
		expect(t, c.entry.name, classify(t, Observation{Share: []Entry{c.entry}}), c.want)
	}
}

func TestRootNamesAreExactlyKastNamed(t *testing.T) {
	kast := map[string]RootEntryKind{
		"current": RootCurrent, "current.next": RootCurrentNext, "versions": RootVersions, "config": RootConfig,
		"state": RootState, ".staging-" + versionHex: RootStaging, "installation": RootLegacy,
		"management.json": RootLegacy, "recovery": RootLegacy, "shutdown.json": RootLegacy,
		"management.lock": RootLegacy, "activation.lock": RootLegacy, ".staging-x": RootForeignName,
		"Current": RootForeignName, "state.json": RootForeignName,
	}
	for name, want := range kast {
		if got := ClassifyRootName(name).Kind; got != want {
			t.Errorf("%s: kind %d, want %d", name, got, want)
		}
	}
}

func TestCurrentNeedsThreeAgreeingRecords(t *testing.T) {
	got := classify(t, installed(t))
	current, ok := got.(Current)
	if !ok || current.Installed.Version.String() != versionHex ||
		current.Installed.Selection.Target() != record.TargetLinuxX8664 {
		t.Fatalf("got %#v", got)
	}
}

func TestIncompleteHoldsOnlyKastNamesWithoutCurrent(t *testing.T) {
	o := Observation{
		Share: []Entry{rootDirectory()},
		Root: []Entry{Directory(ConfigName, true), Directory(StateName, true), Directory(VersionsName, true),
			Directory(".staging-"+otherHex, true), Link(CurrentNextName, true, "versions/"+otherHex)},
		Versions: []Entry{Directory(otherHex, true)},
	}
	expect(t, "incomplete", classify(t, o), Incomplete{})
	expect(t, "empty R", classify(t, Observation{Share: []Entry{rootDirectory()}}), Incomplete{})
}

func at(root record.RootKind, path string) record.Location { return record.At(root, path) }

func TestDamagedNamesTheFailedCheck(t *testing.T) {
	v := consistentVersion(t)
	base := "versions/" + versionHex + "/"
	mutate := func(change func(*Observation)) Observation {
		o := installed(t)
		change(&o)
		return o
	}
	withRecords := func(records VersionRecords) Observation {
		return mutate(func(o *Observation) { o.Current = &records })
	}
	newerSelection := []byte(strings.Replace(string(v.selection), `"schemaVersion":2`, `"schemaVersion":3`, 1))
	otherSelection := []byte(strings.Replace(string(v.selection), versionHex, otherHex, 1))
	cases := map[string]struct {
		o    Observation
		want Damaged
	}{
		"current is a file": {mutate(func(o *Observation) {
			o.Root[0], o.Current = File(CurrentName, true), nil
		}), Damaged{DamageCurrentNotLink, at(record.RootR, "current")}},
		"current target shape": {mutate(func(o *Observation) {
			o.Root[0], o.Current = Link(CurrentName, true, "/abs/versions/"+versionHex), nil
		}), Damaged{DamageCurrentTarget, at(record.RootR, "current")}},
		"version missing": {mutate(func(o *Observation) { o.Versions, o.Current = nil, nil }),
			Damaged{DamageVersionMissing, at(record.RootR, "versions/"+versionHex)}},
		"version is a link": {mutate(func(o *Observation) {
			o.Versions, o.Current = []Entry{Link(versionHex, true, "elsewhere")}, nil
		}), Damaged{DamageVersionMissing, at(record.RootR, "versions/"+versionHex)}},
		"manifest missing": {withRecords(VersionRecords{Missing(), Contents(v.selection), Contents(v.build),
			Contents([]byte("2\n"))}), Damaged{DamageRecordMissing, at(record.RootR, base+"share/kast/installation.json")}},
		"selection not regular": {withRecords(VersionRecords{Contents(v.manifest), NotRegular(), Contents(v.build),
			Contents([]byte("2\n"))}), Damaged{DamageRecordInvalid, at(record.RootR, base+"share/kast/product-selection.json")}},
		"selection newer": {withRecords(VersionRecords{Contents(v.manifest), Contents(newerSelection),
			Contents(v.build), Contents([]byte("2\n"))}),
			Damaged{DamageRecordSchemaUnsupported, at(record.RootR, base+"share/kast/product-selection.json")}},
		"build invalid": {withRecords(VersionRecords{Contents(v.manifest), Contents(v.selection),
			Contents([]byte(`{}`)), Contents([]byte("2\n"))}),
			Damaged{DamageRecordInvalid, at(record.RootR, base+"share/kast/build-record.json")}},
		"marker missing": {withRecords(VersionRecords{Contents(v.manifest), Contents(v.selection), Contents(v.build),
			Missing()}), Damaged{DamageLayoutMarker, at(record.RootR, base+"share/kast/layout-v2")}},
		"marker content": {withRecords(VersionRecords{Contents(v.manifest), Contents(v.selection), Contents(v.build),
			Contents([]byte("1\n"))}), Damaged{DamageLayoutMarker, at(record.RootR, base+"share/kast/layout-v2")}},
		"selection names another version": {withRecords(VersionRecords{Contents(v.manifest),
			Contents(otherSelection), Contents(v.build), Contents([]byte("2\n"))}),
			Damaged{DamageRecordsDisagree, at(record.RootR, base+"share/kast/product-selection.json")}},
		"manifest digests another selection": {withRecords(VersionRecords{Contents(v.manifest),
			Contents([]byte(strings.Replace(string(v.selection), "TRACK_MAIN", "PINNED_BUILD", 1))), Contents(v.build),
			Contents([]byte("2\n"))}), Damaged{DamageRecordsDisagree, at(record.RootR, base+"share/kast/installation.json")}},
		"record bytes changed": {withRecords(VersionRecords{Contents(v.manifest), Contents(v.selection),
			Contents(append([]byte(" "), v.build...)), Contents([]byte("2\n"))}),
			Damaged{DamageRecordsDisagree, at(record.RootR, base+"share/kast/product-selection.json")}},
	}
	for name, c := range cases {
		expect(t, name, classify(t, c.o), c.want)
	}
}

// TestSelectionMustAgreeWithItsBuildRecord changes one bound fact at a time. The manifest digests the changed
// selection, so only the agreement with the build record can reject it.
func TestSelectionMustAgreeWithItsBuildRecord(t *testing.T) {
	v := consistentVersion(t)
	id, _ := record.ParseVersionID(versionHex)
	marker := []byte(record.LayoutMarker)
	for name, change := range map[string][2]string{
		"runtime":         {strings.Repeat("e", 64), strings.Repeat("d", 64)},
		"base":            {strings.Repeat("9", 64), strings.Repeat("7", 64)},
		"absent base":     {`"` + strings.Repeat("9", 64) + `"`, "null"},
		"source revision": {strings.Repeat("1", 40), strings.Repeat("2", 40)},
		"release tag":     {"developer-v0.0.42", "developer-v0.0.43"},
		"local policy":    {"TRACK_MAIN", "LOCAL"},
		"target":          {"linux-x86_64", "macos-aarch64"},
	} {
		selection := []byte(strings.Replace(string(v.selection), change[0], change[1], 1))
		if _, err := record.DecodeProductSelection(selection); err != nil {
			t.Fatalf("%s: the changed selection is not valid on its own: %v", name, err)
		}
		manifest, err := record.NewPayloadManifest(id, []record.PayloadFile{
			{Path: record.BuildRecordPath, SHA256: record.DigestOf(v.build), Bytes: int64(len(v.build)), Mode: record.ModeReadOnly},
			{Path: record.LayoutMarkerPath, SHA256: record.DigestOf(marker), Bytes: 2, Mode: record.ModeReadOnly},
			{Path: record.ProductSelectionPath, SHA256: record.DigestOf(selection), Bytes: int64(len(selection)), Mode: record.ModeReadOnly},
		}, nil)
		if err != nil {
			t.Fatal(err)
		}
		o := installed(t)
		o.Current = &VersionRecords{Contents(manifest.Encode()), Contents(selection), Contents(v.build), Contents(marker)}
		expect(t, name, classify(t, o), Damaged{DamageRecordsDisagree,
			at(record.RootR, "versions/"+versionHex+"/share/kast/product-selection.json")})
	}
}

func TestForeignNamesTheFirstEntryKastDidNotCreate(t *testing.T) {
	witness := Directory(WitnessPrefix+witnessHex, true)
	cases := map[string]struct {
		o    Observation
		want record.Location
	}{
		"user file in R": {Observation{Share: []Entry{rootDirectory()},
			Root: []Entry{File("notes.txt", true), File("a-notes.txt", true), Directory(StateName, true)}},
			at(record.RootR, "a-notes.txt")},
		"R is a link":           {Observation{Share: []Entry{Link(RootName, true, "/elsewhere")}}, at(record.RootHomeShare, "kast")},
		"R is not owned":        {Observation{Share: []Entry{Directory(RootName, false)}}, at(record.RootHomeShare, "kast")},
		"versions is a file":    {Observation{Share: []Entry{rootDirectory()}, Root: []Entry{File(VersionsName, true)}}, at(record.RootR, "versions")},
		"config is a link":      {Observation{Share: []Entry{rootDirectory()}, Root: []Entry{Link(ConfigName, true, "/x")}}, at(record.RootR, "config")},
		"current is not owned":  {Observation{Share: []Entry{rootDirectory()}, Root: []Entry{Link(CurrentName, false, "versions/"+versionHex)}}, at(record.RootR, "current")},
		"foreign version entry": {Observation{Share: []Entry{rootDirectory()}, Root: []Entry{Directory(VersionsName, true)}, Versions: []Entry{Directory("backup", true)}}, at(record.RootR, "versions/backup")},
		"witness beside a valid current": {func() Observation {
			o := installed(t)
			o.Share = append(o.Share, witness)
			return o
		}(), at(record.RootHomeShare, WitnessPrefix+witnessHex)},
	}
	for name, c := range cases {
		expect(t, name, classify(t, c.o), Foreign{c.want})
	}
}

func TestPrecedenceIsFixed(t *testing.T) {
	witnessID, _ := record.ParseVersionID(witnessHex)
	witness := Directory(WitnessPrefix+witnessHex, true)
	legacyJournal := File(".kast-uninstall-"+strings.Repeat("a", 64)+".json", true)
	damagedCurrent := Observation{Share: []Entry{rootDirectory(), witness}, Root: []Entry{File(CurrentName, true)}}
	cases := map[string]struct {
		o    Observation
		want Classification
	}{
		"legacy over foreign": {Observation{Share: []Entry{rootDirectory()},
			Root: []Entry{File("notes.txt", true), Directory("installation", true)}},
			Legacy{at(record.RootR, "installation")}},
		"legacy over residual": {Observation{Share: []Entry{legacyJournal, witness}},
			Legacy{at(record.RootHomeShare, legacyJournal.name)}},
		"share legacy before R legacy": {Observation{Share: []Entry{rootDirectory(), legacyJournal},
			Root: []Entry{File("management.json", true)}}, Legacy{at(record.RootHomeShare, legacyJournal.name)}},
		"foreign over residual": {Observation{Share: []Entry{rootDirectory(), witness},
			Root: []Entry{File("notes.txt", true)}}, Foreign{at(record.RootR, "notes.txt")}},
		"residual over damaged": {damagedCurrent, Residual{[]record.VersionID{witnessID},
			Damaged{DamageCurrentNotLink, at(record.RootR, "current")}}},
		"residual over incomplete (a stray writer recreated R/state)": {Observation{
			Share: []Entry{rootDirectory(), witness}, Root: []Entry{Directory(StateName, true)}},
			Residual{[]record.VersionID{witnessID}, Incomplete{}}},
		"current over incomplete leftovers": {func() Observation {
			o := installed(t)
			o.Root = append(o.Root, Directory(".staging-"+otherHex, true), Link(CurrentNextName, true, "x"))
			return o
		}(), nil},
		"removing trees and other names are ignored": {Observation{Share: []Entry{
			Directory(RemovingPrefix+otherHex, true), File("unrelated", false), File(LockName, true)}}, Absent{}},
	}
	for name, c := range cases {
		got := classify(t, c.o)
		if c.want == nil {
			if got.State() != LayoutCurrent {
				t.Errorf("%s: got %#v", name, got)
			}
			continue
		}
		expect(t, name, got, c.want)
	}
}

func TestObserveRejectsInconsistentObservations(t *testing.T) {
	v := consistentVersion(t)
	cases := map[string]struct {
		o    Observation
		want Defect
	}{
		"slash in name":             {Observation{Share: []Entry{File("a/b", true)}}, DefectEntryName},
		"duplicate name":            {Observation{Share: []Entry{File("a", true), Directory("a", true)}}, DefectDuplicateEntry},
		"R entries without R":       {Observation{Root: []Entry{Directory(StateName, true)}}, DefectRootEntries},
		"versions without versions": {Observation{Share: []Entry{rootDirectory()}, Versions: []Entry{Directory(versionHex, true)}}, DefectVersionEntries},
		"records without current": {Observation{Share: []Entry{rootDirectory()}, Current: v.records()},
			DefectCurrentRecords},
		"current without records": {Observation{Share: []Entry{rootDirectory()},
			Root:     []Entry{currentLink(), Directory(VersionsName, true)},
			Versions: []Entry{Directory(versionHex, true)}}, DefectCurrentRecords},
		"store entries without store": {Observation{RuntimeStore: []Entry{Directory(strings.Repeat("e", 64), true)}},
			DefectStoreEntries},
		"containers without store": {Observation{Containers: ContainersOccupied}, DefectContainers},
	}
	for name, c := range cases {
		if c.o.Containers == 0 {
			c.o.Containers = ContainersAbsent
		}
		if _, err := Observe(c.o); err != c.want {
			t.Errorf("%s: got %v, want %v", name, err, c.want)
		}
	}
}
