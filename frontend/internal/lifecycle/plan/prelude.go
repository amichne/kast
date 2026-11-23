package plan

import (
	"strings"

	"github.com/amichne/kast/frontend/internal/lifecycle/failure"
	"github.com/amichne/kast/frontend/internal/lifecycle/layout"
	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

// Command is the closed set of lifecycle transactions a plan runs.
type Command uint8

const (
	CommandInstall Command = iota + 1
	CommandUpgrade
	CommandRepair
	CommandUninstall
	CommandForceUninstall
)

// PathState is the closed set of observations of one home directory.
type PathState uint8

const (
	PathMissing PathState = iota + 1
	PathOwnedDirectory
	PathRejected // a link, another file type, or a directory the user does not own
)

// FilesystemFact is the file system of one existing root.
type FilesystemFact struct {
	Root    record.RootKind
	Network bool
	Device  uint64
}

// HomeFacts are the P0 observations outside the layout: the effective user, the three home directories, and the
// file systems of S, R and both stores where they exist.
type HomeFacts struct {
	UserID      int
	Local       PathState
	Share       PathState
	Bin         PathState
	Filesystems []FilesystemFact
}

// Precheck is P0's decision: the user, home path and file system checks, then Legacy and Foreign, all with no
// effect.
func Precheck(facts HomeFacts, classification layout.Classification) *failure.Failure {
	stage := failure.StagePrecheck
	reject := func(rejected failure.Failure) *failure.Failure { return &rejected }
	if facts.UserID == 0 {
		return reject(failure.NewRootUserRejected(stage))
	}
	for _, directory := range []struct {
		state PathState
		path  string
	}{{facts.Local, ".local"}, {facts.Share, ".local/share"}, {facts.Bin, ".local/bin"}} {
		// P1 creates a missing share or bin directory; it never creates ~/.local.
		if directory.state == PathRejected || (directory.path == ".local" && directory.state != PathOwnedDirectory) {
			return reject(failure.NewHomePathRejected(stage, record.At(record.RootHome, directory.path)))
		}
	}
	for i, fact := range facts.Filesystems {
		location := rootDirectoryEvidence(fact.Root)
		if fact.Network {
			return reject(failure.NewFilesystemUnsupported(stage, location))
		}
		if i > 0 && fact.Device != facts.Filesystems[0].Device {
			return reject(failure.NewCrossDevice(stage, location))
		}
	}
	switch state := classification.(type) {
	case layout.Legacy:
		return reject(failure.NewLegacyLayout(stage, state.Marker))
	case layout.Foreign:
		return reject(failure.NewRootForeign(stage, state.Entry))
	}
	return nil
}

// rootDirectoryEvidence names a root by its entry in its parent, because evidence carries no absolute path.
func rootDirectoryEvidence(root record.RootKind) record.Location {
	switch root {
	case record.RootR:
		return record.At(record.RootHomeShare, layout.RootName)
	case record.RootRuntimeStore:
		return record.At(record.RootHomeShare, layout.WorkspaceStoreName)
	case record.RootBaseStore:
		return record.At(record.RootHomeShare, layout.BaseStoreName)
	default:
		return record.At(record.RootHome, ".local/share")
	}
}

// effective is the layout a command acts on: a residue's remainder once P5 finishes it.
func effective(classification layout.Classification) layout.Classification {
	if residual, ok := classification.(layout.Residual); ok {
		return residual.Remainder
	}
	return classification
}

var missingCurrent = record.At(record.RootR, layout.CurrentName)

// Admit is the admission under the lock (P4, then A1 or X1). Legacy and Foreign reject as in P0. A version whose
// records are newer than this binary rejects with RECORD_SCHEMA_UNSUPPORTED for every command (downgrade policy).
// Damaged needs --repair or --force. Upgrade and repair need an installation; uninstall needs an installation or a
// residue to finish.
func Admit(command Command, classification layout.Classification) *failure.Failure {
	stage := failure.StagePrelude
	reject := func(rejected failure.Failure) *failure.Failure { return &rejected }
	switch state := classification.(type) {
	case layout.Legacy:
		return reject(failure.NewLegacyLayout(stage, state.Marker))
	case layout.Foreign:
		return reject(failure.NewRootForeign(stage, state.Entry))
	}
	_, residual := classification.(layout.Residual)
	switch state := effective(classification).(type) {
	case layout.Damaged:
		if state.Reason == layout.DamageRecordSchemaUnsupported {
			return reject(failure.NewRecordSchemaUnsupported(stage, state.Location))
		}
		if command != CommandRepair && command != CommandForceUninstall {
			return reject(failure.NewLayoutDamaged(stage, state.Location))
		}
	case layout.Absent:
		if command == CommandUpgrade || command == CommandRepair ||
			((command == CommandUninstall || command == CommandForceUninstall) && !residual) {
			return reject(failure.NewLayoutDamaged(stage, missingCurrent))
		}
	}
	return nil
}

// prelude is P1 to P3: create a missing share or bin directory, open the lock file, then take both bytes
// exclusively, intent first.
func prelude(b *builder, facts HomeFacts) {
	if facts.Share == PathMissing {
		b.step("P1", EnsureDirectory{record.At(record.RootHome, ".local/share"), 0o700})
	}
	if facts.Bin == PathMissing {
		b.step("P1", EnsureDirectory{record.At(record.RootHome, ".local/bin"), 0o755})
	}
	b.step("P2", OpenLockFile{})
	b.step("P3", SetLock{failure.RangeIntent, LockExclusive, false}, SetLock{failure.RangeMain, LockExclusive, true})
}

// BoundSet is the set of store entries that a product selection names. It is unknown while current is damaged,
// and an unknown set binds every entry, so nothing is deleted on a guess.
type BoundSet struct {
	known   bool
	entries map[Entry]bool
}

// Bound is the union of the entries the selections bind: during a command, the current selection and the own
// staging tree's selection (decision D9 with F1).
func Bound(selections ...record.ProductSelection) BoundSet {
	set := BoundSet{true, map[Entry]bool{}}
	for _, selection := range selections {
		set.entries[Entry{EntryRuntime, selection.RuntimeArchive()}] = true
		if base, ok := selection.Base(); ok {
			set.entries[Entry{EntryBase, base}] = true
		}
	}
	return set
}

// UnknownBound is the bound set while the current selection cannot be read.
func UnknownBound() BoundSet { return BoundSet{} }

// Binds reports whether an entry is bound; an unknown set binds everything.
func (b BoundSet) Binds(entry Entry) bool { return !b.known || b.entries[entry] }

// Reservation is one of the command's own store reservations.
type Reservation struct {
	Kind EntryKind
	ID   record.VersionID
}

// Own are the command's own staging tree and reservations, which P5 spares.
type Own struct {
	Staging      *record.VersionID
	Reservations []Reservation
}

func (o Own) spares(kind EntryKind, id record.VersionID) bool {
	for _, own := range o.Reservations {
		if own.Kind == kind && own.ID == id {
			return true
		}
	}
	return false
}

// Candidates are the trees and links P5 selects: everything Kast named that neither current nor the own staging
// tree binds.
type Candidates struct {
	Staging      []record.VersionID
	CurrentNext  bool
	Versions     []record.VersionID
	PublicNext   []record.VersionID
	Removing     []record.VersionID
	Reservations []Reservation
	Entries      []Entry
}

// cleanupInput is what P5 decides from.
type cleanupInput struct {
	paths          Paths
	snapshot       layout.Snapshot
	classification layout.Classification
	bound          BoundSet
	own            Own
	processes      []Process
	ids            *fresh
}

// SelectForCleanup is P5's selection rule, without the ownership checks: the own staging tree and reservations are
// spared, the version current names is spared, every version is spared while current cannot be read, and store
// entries are selected only outside the bound set.
func SelectForCleanup(snapshot layout.Snapshot, classification layout.Classification, bound BoundSet,
	own Own) Candidates {
	var selected Candidates
	for _, entry := range snapshot.Root() {
		switch name := layout.ClassifyRootName(entry.Name()); name.Kind {
		case layout.RootStaging:
			if own.Staging == nil || *own.Staging != name.ID {
				selected.Staging = append(selected.Staging, name.ID)
			}
		case layout.RootCurrentNext:
			selected.CurrentNext = true
		}
	}
	if named, known := currentNames(classification); known {
		for _, entry := range snapshot.Versions() {
			if id, ok := record.ParseVersionID(entry.Name()); ok && (named == nil || id != *named) {
				selected.Versions = append(selected.Versions, id)
			}
		}
	}
	for _, entry := range snapshot.Bin() {
		if id, ok := layout.Identified(entry.Name(), layout.PublicCommandNextName); ok {
			selected.PublicNext = append(selected.PublicNext, id)
		}
	}
	for _, entry := range snapshot.Share() {
		if name := layout.ClassifyShareName(entry.Name()); name.Kind == layout.ShareRemoving {
			selected.Removing = append(selected.Removing, name.ID)
		}
	}
	for _, store := range []struct {
		kind    EntryKind
		entries []layout.Entry
	}{{EntryRuntime, snapshot.RuntimeStore()}, {EntryBase, snapshot.BaseStore()}} {
		for _, entry := range store.entries {
			if id, ok := layout.Identified(entry.Name(), store.kind.reservationPrefix()); ok {
				if !own.spares(store.kind, id) {
					selected.Reservations = append(selected.Reservations, Reservation{store.kind, id})
				}
				continue
			}
			if digest, ok := record.ParseDigest(entry.Name()); ok && !bound.Binds(Entry{store.kind, digest}) {
				selected.Entries = append(selected.Entries, Entry{store.kind, digest})
			}
		}
	}
	return selected
}

// currentNames returns the version current names when its records are readable; a damaged or absent current names
// none that P5 may rely on.
func currentNames(classification layout.Classification) (*record.VersionID, bool) {
	switch state := effective(classification).(type) {
	case layout.Current:
		return &state.Installed.Version, true
	case layout.Damaged:
		return nil, false
	default:
		return nil, true
	}
}

// cleanup is P5, the recovery rule: finish a residue in the section 3.5 order, then delete every unbound Kast-named
// leftover. Each deleted tree is first renamed aside; a version or store entry is deleted only after the process-use
// proof; a candidate that is not the user's, is a link, or is a transient link with a foreign target rejects with
// LEFTOVER_FOREIGN. The step name is the row that runs P5: P5 itself, A9, or A15.
func cleanup(b *builder, step StepName, in cleanupInput) *failure.Failure {
	stage := failure.StageCleanup
	foreign := func(location record.Location) *failure.Failure {
		rejected := failure.NewLeftoverForeign(stage, location)
		return &rejected
	}
	var aside []record.VersionID
	renameAside := func(name StepName, from record.Location) {
		id := in.ids.id()
		aside = append(aside, id)
		b.step(name, RenameAside{from, id})
	}
	residual, isResidual := in.classification.(layout.Residual)
	var stores []record.Location
	if isResidual {
		for _, name := range []string{layout.WorkspaceStoreName, layout.BaseStoreName} {
			if entry, ok := in.snapshot.ShareEntry(name); ok {
				location := record.At(record.RootHomeShare, name)
				if !entry.OwnedDirectory() {
					return foreign(location)
				}
				stores = append(stores, location)
			}
		}
	}
	candidates := SelectForCleanup(in.snapshot, in.classification, in.bound, in.own)
	if isResidual {
		// The stores are renamed aside whole, so their entries are not candidates of their own.
		candidates.Reservations, candidates.Entries = nil, nil
	}
	// Every check runs before the first effect, so a rejection leaves the tree as it was.
	type tree struct {
		location record.Location
		guarded  bool
	}
	var trees []tree
	var links []record.Location
	for _, id := range candidates.Staging {
		location := staging(id)
		if entry, _ := in.snapshot.RootEntry(location.Path.String()); !entry.Owned() || entry.Kind() == layout.KindLink {
			return foreign(location)
		}
		trees = append(trees, tree{location, false})
	}
	if candidates.CurrentNext {
		location := record.At(record.RootR, layout.CurrentNextName)
		entry, _ := in.snapshot.RootEntry(layout.CurrentNextName)
		target, link := entry.Target()
		if _, shaped := record.ParseCurrentTarget(target); !link || !entry.Owned() || !shaped {
			return foreign(location)
		}
		links = append(links, location)
	}
	for _, id := range candidates.PublicNext {
		name := layout.Prefixed(layout.PublicCommandNextName, id)
		location := record.At(record.RootPublicCommand, name)
		entry, _ := in.snapshot.BinEntry(name)
		if target, link := entry.Target(); !link || !entry.Owned() || target != in.paths.PublicTarget() {
			return foreign(location)
		}
		links = append(links, location)
	}
	for _, reserved := range candidates.Reservations {
		location := reservation(reserved.Kind, reserved.ID)
		if entry := storeEntry(in.snapshot, reserved.Kind, location.Path.String()); !entry.Owned() ||
			entry.Kind() == layout.KindLink {
			return foreign(location)
		}
		trees = append(trees, tree{location, false})
	}
	for _, id := range candidates.Versions {
		location := versionLocation(id)
		if entry, _ := in.snapshot.VersionEntry(id.String()); !entry.OwnedDirectory() {
			return foreign(location)
		}
		trees = append(trees, tree{location, true})
	}
	for _, unbound := range candidates.Entries {
		location := unbound.Location()
		if entry := storeEntry(in.snapshot, unbound.Kind, unbound.Digest.String()); !entry.OwnedDirectory() {
			return foreign(location)
		}
		trees = append(trees, tree{location, true})
	}
	for _, id := range candidates.Removing {
		location := record.At(record.RootHomeShare, layout.Prefixed(layout.RemovingPrefix, id))
		if entry, _ := in.snapshot.ShareEntry(location.Path.String()); !entry.Owned() || entry.Kind() == layout.KindLink {
			return foreign(location)
		}
	}
	var witnesses []record.Location
	if isResidual {
		for _, id := range residual.Witnesses {
			location := record.At(record.RootHomeShare, layout.Prefixed(layout.WitnessPrefix, id))
			if entry, _ := in.snapshot.ShareEntry(location.Path.String()); !entry.OwnedDirectory() {
				return foreign(location)
			}
			witnesses = append(witnesses, location)
		}
	}
	var guarded []string
	for _, candidate := range trees {
		if candidate.guarded {
			guarded = append(guarded, in.paths.Absolute(candidate.location))
		}
	}
	for _, location := range append(stores, witnesses...) {
		guarded = append(guarded, in.paths.Absolute(location))
	}
	if rejected, used := firstUse(in.processes, guarded); used {
		return rejected
	}

	if len(stores) > 0 {
		for _, store := range stores {
			b.step("R1", ProveUnused{store})
		}
		b.step("R1", LockStoreLifetime{})
		for _, store := range stores {
			renameAside("R1", store)
		}
		b.step("R1", UnlockStoreLifetime{})
	}
	if isResidual {
		residueLinks(b, in)
	}
	for _, location := range links {
		b.step(step, Unlink{location})
	}
	for _, candidate := range trees {
		if candidate.guarded {
			b.step(step, ProveUnused{candidate.location})
		}
		renameAside(step, candidate.location)
	}
	deleteStep := step
	if isResidual {
		deleteStep = "R3"
	}
	for _, id := range append(candidates.Removing, aside...) {
		b.step(deleteStep, DeleteRemoving{id})
	}
	for _, location := range witnesses {
		b.step("R4", ProveUnused{location}, EmptyTree{location}, RemoveDirectory{location})
	}
	return nil
}

// residueLinks is residue step 2: the exact owned public link, and each owned socket alias whose target lay in R.
func residueLinks(b *builder, in cleanupInput) {
	if entry, ok := in.snapshot.BinEntry(layout.PublicCommandName); ok {
		if target, link := entry.Target(); link && entry.Owned() && target == in.paths.PublicTarget() {
			b.step("R2", Unlink{record.At(record.RootPublicCommand, layout.PublicCommandName)})
		}
	}
	for _, entry := range in.snapshot.TmpAliases() {
		target, link := entry.Target()
		if hexed, ok := strings.CutPrefix(entry.Name(), layout.TmpAliasPrefix); ok && lowerHex(hexed, 32) && link &&
			entry.Owned() && within(target, in.paths.Root(record.RootR)) {
			b.step("R2", Unlink{record.At(record.RootTmpAlias, entry.Name())})
		}
	}
}

func lowerHex(text string, length int) bool {
	if len(text) != length {
		return false
	}
	for i := 0; i < len(text); i++ {
		if c := text[i]; (c < '0' || c > '9') && (c < 'a' || c > 'f') {
			return false
		}
	}
	return true
}

func storeEntry(snapshot layout.Snapshot, kind EntryKind, name string) layout.Entry {
	if kind == EntryRuntime {
		entry, _ := snapshot.RuntimeEntry(name)
		return entry
	}
	entry, _ := snapshot.BaseEntry(name)
	return entry
}

// Abandon is what a command performs before it returns any rejection once it has created its own staging tree or
// reservations: rename each aside and delete it.
func Abandon(own Own, ids IDSource, first int) []Intent {
	source := &fresh{ids, first}
	var intents, deletions []Intent
	var trees []record.Location
	if own.Staging != nil {
		trees = append(trees, staging(*own.Staging))
	}
	for _, reserved := range own.Reservations {
		trees = append(trees, reservation(reserved.Kind, reserved.ID))
	}
	for _, tree := range trees {
		id := source.id()
		intents = append(intents, RenameAside{tree, id})
		deletions = append(deletions, DeleteRemoving{id})
	}
	return append(intents, deletions...)
}
