package layout

import (
	"bytes"

	"github.com/amichne/kast/frontend/internal/lifecycle/codec"
	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

// LayoutObservation is the closed set of layout states (section 1.4).
type LayoutObservation uint8

const (
	LayoutAbsent     LayoutObservation = iota + 1 // no R, no witness, no legacy marker
	LayoutIncomplete                              // R holds only Kast-named entries and no current link
	LayoutCurrent                                 // current names a Kast-named V whose three records decode and agree
	LayoutDamaged                                 // current exists but its target, name or records fail
	LayoutResidual                                // an uninstall witness S/.kast-uninstalled-<id> exists
	LayoutLegacy                                  // any legacy marker exists
	LayoutForeign                                 // R holds an entry with no Kast name, or a witness beside a valid current
)

var observationNames = map[LayoutObservation]string{
	LayoutAbsent: "ABSENT", LayoutIncomplete: "INCOMPLETE", LayoutCurrent: "CURRENT", LayoutDamaged: "DAMAGED",
	LayoutResidual: "RESIDUAL", LayoutLegacy: "LEGACY", LayoutForeign: "FOREIGN",
}

func (l LayoutObservation) String() string { return observationNames[l] }

// Classification is the sealed result of Classify; its dynamic type is one of the seven state variants.
type Classification interface {
	State() LayoutObservation
	classification()
}

// Absent: nothing of Kast's layout exists.
type Absent struct{}

// Incomplete: R holds only Kast-named entries and no current link.
type Incomplete struct{}

// Installed is a current version whose three records decode and agree.
type Installed struct {
	Version   record.VersionID
	Manifest  record.PayloadManifest
	Selection record.ProductSelection
	Build     record.BuildRecord
}

// Current: current names an installed version.
type Current struct{ Installed Installed }

// DamageReason is the closed set of checks that make a current link damaged.
type DamageReason uint8

const (
	DamageCurrentNotLink DamageReason = iota + 1
	DamageCurrentTarget
	DamageVersionMissing
	DamageRecordMissing
	DamageRecordInvalid
	DamageRecordSchemaUnsupported
	DamageRecordsDisagree
	DamageLayoutMarker
)

// Damaged: current exists but its target, its version or its records fail one check, at one R-relative location.
type Damaged struct {
	Reason   DamageReason
	Location record.Location
}

// Residual: uninstall witnesses exist. Remainder is what the layout is once the residue is finished: the
// classification of R alone, which is never Current, because a witness beside a valid current is Foreign.
type Residual struct {
	Witnesses []record.VersionID
	Remainder Classification
}

// Legacy: a legacy marker exists at Marker.
type Legacy struct{ Marker record.Location }

// Foreign: Entry is not Kast's, or a witness exists beside a valid current.
type Foreign struct{ Entry record.Location }

func (Absent) State() LayoutObservation     { return LayoutAbsent }
func (Incomplete) State() LayoutObservation { return LayoutIncomplete }
func (Current) State() LayoutObservation    { return LayoutCurrent }
func (Damaged) State() LayoutObservation    { return LayoutDamaged }
func (Residual) State() LayoutObservation   { return LayoutResidual }
func (Legacy) State() LayoutObservation     { return LayoutLegacy }
func (Foreign) State() LayoutObservation    { return LayoutForeign }
func (Absent) classification()              {}
func (Incomplete) classification()          {}
func (Current) classification()             {}
func (Damaged) classification()             {}
func (Residual) classification()            {}
func (Legacy) classification()              {}
func (Foreign) classification()             {}

// Classify maps a snapshot to exactly one state with the fixed precedence Legacy, Foreign, Residual, Damaged,
// Current, Incomplete, Absent. Within one state, the first offending name in byte order is reported.
func Classify(s Snapshot) Classification {
	if !s.observed {
		panic("layout: the snapshot was not observed")
	}
	if marker, ok := legacyMarker(s); ok {
		return Legacy{marker}
	}
	if entry, ok := foreignEntry(s); ok {
		return Foreign{entry}
	}
	inner := classifyRoot(s)
	witnesses := witnessIDs(s)
	if len(witnesses) > 0 {
		if inner.State() == LayoutCurrent {
			return Foreign{record.At(record.RootHomeShare, Prefixed(WitnessPrefix, witnesses[0]))}
		}
		return Residual{witnesses, inner}
	}
	return inner
}

func legacyMarker(s Snapshot) (record.Location, bool) {
	for _, entry := range s.share {
		if ClassifyShareName(entry.name).Kind.Legacy() {
			return record.At(record.RootHomeShare, entry.name), true
		}
	}
	for _, entry := range s.root {
		if ClassifyRootName(entry.name).Kind == RootLegacy {
			return record.At(record.RootR, entry.name), true
		}
	}
	return record.Location{}, false
}

// locate names an entry beneath a root. A file name is arbitrary bytes, so a name that evidence cannot carry is
// reported as its containing directory.
func locate(root record.RootKind, relative string, container record.Location) record.Location {
	if path, ok := record.ParseRelativePath(relative); ok {
		return record.Location{Root: root, Path: path}
	}
	return container
}

// foreignEntry finds what Kast did not create: R itself as anything but an owned directory, an unnamed entry of R,
// a structural directory of another type or owner, current owned by someone else, or an entry of R/versions that is
// not a version name.
func foreignEntry(s Snapshot) (record.Location, bool) {
	root, ok := find(s.share, RootName)
	if !ok {
		return record.Location{}, false
	}
	if !root.OwnedDirectory() {
		return record.At(record.RootHomeShare, RootName), true
	}
	for _, entry := range s.root {
		switch ClassifyRootName(entry.name).Kind {
		case RootForeignName:
			return locate(record.RootR, entry.name, record.At(record.RootHomeShare, RootName)), true
		case RootVersions, RootConfig, RootState:
			if !entry.OwnedDirectory() {
				return record.At(record.RootR, entry.name), true
			}
		case RootCurrent:
			if !entry.owned {
				return record.At(record.RootR, entry.name), true
			}
		}
	}
	for _, entry := range s.versions {
		if _, ok := record.ParseVersionID(entry.name); !ok {
			return locate(record.RootR, VersionsName+"/"+entry.name, record.At(record.RootR, VersionsName)), true
		}
	}
	return record.Location{}, false
}

func witnessIDs(s Snapshot) []record.VersionID {
	var ids []record.VersionID
	for _, entry := range s.share {
		if name := ClassifyShareName(entry.name); name.Kind == ShareWitness {
			ids = append(ids, name.ID)
		}
	}
	return ids
}

func classifyRoot(s Snapshot) Classification {
	if _, ok := find(s.share, RootName); !ok {
		return Absent{}
	}
	current, ok := find(s.root, CurrentName)
	if !ok {
		return Incomplete{}
	}
	here := record.At(record.RootR, CurrentName)
	if current.kind != KindLink {
		return Damaged{DamageCurrentNotLink, here}
	}
	target, ok := record.ParseCurrentTarget(current.target)
	if !ok {
		return Damaged{DamageCurrentTarget, here}
	}
	version := target.Version()
	if _, named := currentVersion(s.root, s.versions); !named {
		return Damaged{DamageVersionMissing, record.Location{Root: record.RootR, Path: record.VersionPath(version)}}
	}
	installed, damage := admitVersion(version, *s.current)
	if damage != nil {
		return *damage
	}
	return Current{installed}
}

func admitVersion(version record.VersionID, records VersionRecords) (Installed, *Damaged) {
	base := record.VersionPath(version)
	located := func(reason DamageReason, relative record.RelativePath) *Damaged {
		return &Damaged{reason, record.Location{Root: record.RootR, Path: base.Join(relative)}}
	}
	read := func(raw Read, relative record.RelativePath) ([]byte, *Damaged) {
		switch raw.state {
		case ReadMissing:
			return nil, located(DamageRecordMissing, relative)
		case ReadNotRegular:
			return nil, located(DamageRecordInvalid, relative)
		default:
			return raw.raw, nil
		}
	}
	decoded := func(err error, relative record.RelativePath) *Damaged {
		if cause, _ := codec.CauseOf(err); cause == codec.SchemaUnsupported {
			return located(DamageRecordSchemaUnsupported, relative)
		}
		return located(DamageRecordInvalid, relative)
	}
	manifestRaw, damage := read(records.Manifest, record.PayloadManifestPath)
	if damage != nil {
		return Installed{}, damage
	}
	manifest, err := record.DecodePayloadManifest(manifestRaw)
	if err != nil {
		return Installed{}, decoded(err, record.PayloadManifestPath)
	}
	selectionRaw, damage := read(records.Selection, record.ProductSelectionPath)
	if damage != nil {
		return Installed{}, damage
	}
	selection, err := record.DecodeProductSelection(selectionRaw)
	if err != nil {
		return Installed{}, decoded(err, record.ProductSelectionPath)
	}
	buildRaw, damage := read(records.Build, record.BuildRecordPath)
	if damage != nil {
		return Installed{}, damage
	}
	build, err := record.DecodeBuildRecord(buildRaw)
	if err != nil {
		return Installed{}, decoded(err, record.BuildRecordPath)
	}
	if records.Marker.state != ReadBytes || !bytes.Equal(records.Marker.raw, []byte(record.LayoutMarker)) {
		return Installed{}, located(DamageLayoutMarker, record.LayoutMarkerPath)
	}
	if disagreement, ok := disagree(version, manifest, selection, selectionRaw, build); ok {
		return Installed{}, located(DamageRecordsDisagree, disagreement)
	}
	return Installed{version, manifest, selection, build}, nil
}

// disagree checks that the three records describe one version: both name the directory, the selection binds the
// record's bytes, target, revision, version, runtime and base, and the manifest digests both records and the marker.
func disagree(version record.VersionID, manifest record.PayloadManifest, selection record.ProductSelection,
	selectionRaw []byte, build record.BuildRecord) (record.RelativePath, bool) {
	if manifest.Version() != version {
		return record.PayloadManifestPath, true
	}
	target, hasTarget := build.Target(selection.Target())
	if selection.Version() != version || selection.RecordSHA256() != build.Digest() || !hasTarget ||
		selection.SourceRevision() != build.SourceRevision() ||
		selection.RuntimeArchive() != target.Runtime().SHA256() ||
		build.Local() != (selection.Identity().Policy() == record.PolicyLocal) {
		return record.ProductSelectionPath, true
	}
	if developer, ok := selection.Identity().Tag().Developer(); ok && developer != build.Version() {
		return record.ProductSelectionPath, true
	}
	baseAsset, hasBase := target.Base()
	bound, binds := selection.Base()
	if hasBase != binds || (hasBase && baseAsset.SHA256() != bound) {
		return record.ProductSelectionPath, true
	}
	expected := []struct {
		path record.RelativePath
		raw  []byte
	}{
		{record.BuildRecordPath, build.Bytes()},
		{record.ProductSelectionPath, selectionRaw},
		{record.LayoutMarkerPath, []byte(record.LayoutMarker)},
	}
	for _, file := range expected {
		entry, listed := manifest.File(file.path)
		if !listed || entry.SHA256 != record.DigestOf(file.raw) || entry.Bytes != int64(len(file.raw)) ||
			entry.Mode != record.ModeReadOnly {
			return record.PayloadManifestPath, true
		}
	}
	return record.RelativePath{}, false
}
