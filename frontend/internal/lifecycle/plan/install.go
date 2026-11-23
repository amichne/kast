package plan

import (
	"github.com/amichne/kast/frontend/internal/lifecycle/codec"
	"github.com/amichne/kast/frontend/internal/lifecycle/envelope"
	"github.com/amichne/kast/frontend/internal/lifecycle/failure"
	"github.com/amichne/kast/frontend/internal/lifecycle/layout"
	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

// Verification is the result of one completed check: verified, or damaged with its failure. The zero value was
// never performed, so reading it is a defect rather than a success.
type Verification struct {
	performed bool
	damage    *failure.Failure
}

// Verified is a check that passed.
func Verified() Verification { return Verification{performed: true} }

// DamagedBy is a check that failed.
func DamagedBy(damage failure.Failure) Verification { return Verification{true, &damage} }

func (v Verification) damaged() *failure.Failure {
	if !v.performed {
		panic("plan: a verification was not performed")
	}
	return v.damage
}

// CurrentVerification is A3's observation of the current version: its payload against its manifest, the subset of
// retirement executables (T3, V2), and its bound runtime and base entries. Base is Verified without a bound base.
type CurrentVerification struct {
	Payload    Verification
	Retirement Verification
	Runtime    Verification
	Base       Verification
}

func (c CurrentVerification) first() *failure.Failure {
	var first *failure.Failure
	for _, check := range []Verification{c.Payload, c.Retirement, c.Runtime, c.Base} {
		if damage := check.damaged(); damage != nil && first == nil {
			first = damage
		}
	}
	return first
}

// EntryObservation is A6's observation of one entry the new selection binds: missing, verified, or damaged. The zero
// value was never observed.
type EntryObservation struct {
	missing bool
	check   Verification
}

// EntryMissing is an entry that does not exist.
func EntryMissing() EntryObservation { return EntryObservation{missing: true} }

// EntryPresent is an entry that exists, with its verification.
func EntryPresent(check Verification) EntryObservation { return EntryObservation{check: check} }

// InstallInput holds every observation the install, upgrade and repair machine decides from, in the order the
// machine reaches them. Observations a rejected plan never reaches are ignored.
type InstallInput struct {
	Operation envelope.Operation
	Host      record.Target
	Paths     Paths
	IDs       IDSource
	Home      HomeFacts
	// Before is P0's snapshot, taken with no lock.
	Before layout.Snapshot
	// Locked is P4's snapshot, under both bytes exclusively; Processes is the process table at that point.
	Locked    layout.Snapshot
	Processes ProcessTable
	// Current is A3's verification of the current version.
	Current CurrentVerification
	// Handoff, RecordBytes and OwnExecutable are A5's inputs: the handoff document, the record bytes in the handoff
	// directory, and the digest of the running executable.
	Handoff       envelope.Handoff
	RecordBytes   []byte
	OwnExecutable record.Digest
	// RuntimeManifest is the digest of the runtime archive's manifest.json; Runtime and Base are A6's observations.
	RuntimeManifest record.Digest
	Runtime         EntryObservation
	Base            EntryObservation
	// LayoutMarker reports A7's marker check on the extracted payload.
	LayoutMarker bool
	// Relocked and RelockedProcesses are A9's snapshot and process table, under both bytes again. A table from the
	// first hold proves nothing under the second.
	Relocked          layout.Snapshot
	RelockedProcesses ProcessTable
	// Retirement is A10's normal retirement; Forced is the observation forced retirement plans from.
	Retirement RetirementObservation
	Forced     ForcedObservation
	// Final is the process table after A10, for A11's exchanges and A15's deletions.
	Final ProcessTable
}

func commandOf(operation envelope.Operation) Command {
	switch operation {
	case envelope.OperationUpgrade:
		return CommandUpgrade
	case envelope.OperationRepair:
		return CommandRepair
	default:
		return CommandInstall
	}
}

// admittedHandoff is A5's pure part: the record decodes strictly, matches the handoff digest, has the host target,
// and names the running executable's digest, which the handoff also names.
func admittedHandoff(in InstallInput) (record.BuildRecord, record.BuildTarget, *failure.Failure) {
	reject := func(rejected failure.Failure) (record.BuildRecord, record.BuildTarget, *failure.Failure) {
		return record.BuildRecord{}, record.BuildTarget{}, &rejected
	}
	stage := failure.StageHandoff
	location := record.At(record.RootHandoff, record.BuildRecordAsset.String())
	observed := record.DigestOf(in.RecordBytes)
	if observed != in.Handoff.RecordSHA256() {
		return reject(failure.NewRecordDigestMismatch(stage, record.BuildRecordAsset, in.Handoff.RecordSHA256(), observed))
	}
	build, err := record.DecodeBuildRecord(in.RecordBytes)
	if err != nil {
		if cause, _ := codec.CauseOf(err); cause == codec.SchemaUnsupported {
			return reject(failure.NewRecordSchemaUnsupported(stage, location))
		}
		return reject(failure.NewRecordInvalid(stage, location))
	}
	target, ok := build.Target(in.Host)
	if !ok {
		return reject(failure.NewBuildUnavailable(stage))
	}
	control := record.ControlAsset(in.Host)
	if target.ControlSHA256() != in.Handoff.ControlSHA256() {
		return reject(failure.NewControlDigestMismatch(stage, control, target.ControlSHA256(), in.Handoff.ControlSHA256()))
	}
	if in.OwnExecutable != target.ControlSHA256() {
		return reject(failure.NewControlDigestMismatch(stage, control, target.ControlSHA256(), in.OwnExecutable))
	}
	return build, target, nil
}

// Install plans install, upgrade and repair as one machine (section 3.2, steps A1 to A15; H1 and H2 run in the
// selecting binary).
func Install(in InstallInput) Plan {
	ids := &fresh{source: in.IDs}
	b := builder{ids: ids}
	command := commandOf(in.Operation)
	if rejected := Precheck(in.Home, layout.Classify(in.Before)); rejected != nil {
		return b.fail(*rejected)
	}
	// A1: P1 to P5.
	prelude(&b, in.Home)
	locked := layout.Classify(in.Locked)
	if rejected := Admit(command, locked); rejected != nil {
		return b.reject("P4", *rejected)
	}
	outgoing := effective(locked)
	currentSelection, bound := boundOf(outgoing)
	if rejected := cleanup(&b, "P5", cleanupInput{in.Paths, in.Locked, locked, bound, Own{}, in.Processes, ids}); rejected != nil {
		return b.reject("P5", *rejected)
	}
	_, residual := locked.(layout.Residual)
	current, hasCurrent := outgoing.(layout.Current)
	_, damaged := outgoing.(layout.Damaged)

	// A2: the public command is absent or the exact owned link; a first install needs an empty container store.
	if rejected := publicCommandAdmitted(in.Paths, in.Locked); rejected != nil {
		return b.reject("A2", *rejected)
	}
	if !hasCurrent && !damaged && !residual && in.Locked.Containers() == layout.ContainersOccupied {
		return b.reject("A2", failure.NewWorkspaceStoreOccupied(failure.StagePrelude,
			record.At(record.RootHomeShare, layout.WorkspaceStoreName+"/"+layout.ContainersName)))
	}
	if _, ok := in.Locked.ShareEntry(layout.RootName); !ok {
		b.step("A2", EnsureDirectory{rootDirectory(record.RootR), 0o700}, SyncDirectory{rootDirectory(record.RootHomeShare)})
	}
	if _, ok := in.Locked.RootEntry(layout.VersionsName); !ok {
		b.step("A2", EnsureDirectory{record.At(record.RootR, layout.VersionsName), 0o700},
			SyncDirectory{rootDirectory(record.RootR)})
	}

	// A3: verify the current version and the entries it binds.
	if hasCurrent {
		version := current.Installed.Version
		b.step("A3", VerifyVersion{version}, VerifyEntry{Entry{EntryRuntime, currentSelection.RuntimeArchive()}})
		if base, ok := currentSelection.Base(); ok {
			b.step("A3", VerifyEntry{Entry{EntryBase, base}})
		}
		if damage := in.Current.first(); damage != nil && command != CommandRepair {
			return b.reject("A3", *damage)
		}
		if command != CommandRepair && currentSelection.RecordSHA256() == in.Handoff.RecordSHA256() {
			return alreadyCurrent(&b, in, ids)
		}
	}

	// A4: downgrade main to shared in place, then release intent.
	b.step("A4", SetLock{failure.RangeMain, LockShared, false}, SetLock{failure.RangeIntent, LockUnlocked, false})
	// A5: verify the handoff.
	b.step("A5", VerifyHandoff{})
	build, target, rejected := admittedHandoff(in)
	if rejected != nil {
		return b.reject("A5", *rejected)
	}
	identity := in.Handoff.Selection().Identity()
	newVersion, stagingID := ids.id(), ids.id()
	var base *record.Digest
	if asset, ok := target.Base(); ok {
		digest := asset.SHA256()
		base = &digest
	}
	selection := record.NewProductSelection(newVersion, in.Host, build.Digest(), identity, build.SourceRevision(),
		target.Runtime().SHA256(), in.RuntimeManifest, base)

	// A6: publish each missing bound entry; stage a replacement for a damaged one under --repair.
	own := Own{}
	type replacement struct {
		entry       Entry
		reservation record.VersionID
	}
	var replacements []replacement
	var published []Entry
	type wanted struct {
		entry       Entry
		asset       record.AssetName
		observation EntryObservation
	}
	var entries []wanted
	if asset, ok := target.Base(); ok {
		entries = append(entries, wanted{Entry{EntryBase, asset.SHA256()}, asset.Name(), in.Base})
	}
	entries = append(entries, wanted{Entry{EntryRuntime, target.Runtime().SHA256()}, target.Runtime().Name(), in.Runtime})
	for _, want := range entries {
		store := rootDirectory(want.entry.Kind.Store())
		if !want.observation.missing {
			b.step("A6", VerifyEntry{want.entry})
			damage := want.observation.check.damaged()
			if damage == nil {
				continue
			}
			if command != CommandRepair {
				b.step("A6", Abandon(own, in.IDs, ids.next)...)
				return b.reject("A6", *damage)
			}
		}
		id := ids.id()
		own.Reservations = append(own.Reservations, Reservation{want.entry.Kind, id})
		ensureStore(&b, in.Locked, residual, want.entry.Kind)
		b.step("A6", Download{want.asset}, ReserveEntry{want.entry.Kind, id}, ExtractEntry{want.entry, id},
			SyncTree{reservation(want.entry.Kind, id)})
		if !want.observation.missing {
			replacements = append(replacements, replacement{want.entry, id})
			continue
		}
		b.step("A6", FullSync{}, PublishEntry{want.entry, id}, SyncDirectory{store})
		published = append(published, want.entry)
		// A published reservation no longer exists; the bound set protects the entry it became.
		own.Reservations = own.Reservations[:len(own.Reservations)-1]
	}

	// A7: stage the Control payload privately.
	stagingTree := staging(stagingID)
	own.Staging = &stagingID
	b.step("A7", CreateStaging{stagingID}, Download{target.Bundle().Name()}, ExtractControl{stagingID},
		RequireLayoutMarker{stagingID})
	if !in.LayoutMarker {
		b.step("A7", Abandon(own, in.IDs, ids.next)...)
		return b.reject("A7", failure.NewReleaseLayoutUnsupported(failure.StageStage))
	}
	b.step("A7", TrainArchive{stagingID, target.Runtime().SHA256()}, WriteSelection{stagingID, selection},
		CopyBuildRecord{stagingID}, WritePayloadManifest{stagingID, newVersion}, SealTree{stagingID},
		SyncTree{stagingTree})

	// A8: take both bytes exclusively again.
	b.step("A8", SetLock{failure.RangeIntent, LockExclusive, false}, SetLock{failure.RangeMain, LockExclusive, true})

	// A9: P5 again, sparing the own tree; the bound set includes the own selection (F1).
	relocked := layout.Classify(in.Relocked)
	if rejected := relockAdmitted(command, outgoing, relocked); rejected != nil {
		b.step("A9", Abandon(own, in.IDs, ids.next)...)
		return b.reject("A9", *rejected)
	}
	nineBound := UnknownBound()
	if bound.known {
		if hasCurrent {
			nineBound = Bound(currentSelection, selection)
		} else {
			nineBound = Bound(selection)
		}
	}
	if rejected := cleanup(&b, "A9", cleanupInput{in.Paths, in.Relocked, relocked, nineBound, own,
		in.RelockedProcesses, ids}); rejected != nil {
		b.step("A9", Abandon(own, in.IDs, ids.next)...)
		return b.reject("A9", *rejected)
	}

	// A10: retire the outgoing version: through its own code, or forced when that code cannot be trusted.
	var observations []failure.Observation
	if hasCurrent || damaged {
		forced := damaged || in.Current.Retirement.damaged() != nil
		if forced {
			steps, noted := ForcedRetirement(in.Paths, outgoingTrees(in.Paths, in.Relocked, outgoing), in.Forced,
				in.RelockedProcesses)
			for _, step := range steps {
				b.step("A10", step.Intents...)
			}
			observations = append(observations, noted...)
		} else {
			b.step("A10", Retire{current.Installed.Version, false})
			noted, rejected := ConfirmRetirement(in.Retirement)
			if rejected != nil {
				b.step("A10", Abandon(own, in.IDs, ids.next)...)
				return b.reject("A10", *rejected)
			}
			observations = append(observations, noted...)
		}
	}

	// A11: swap each damaged entry with its replacement; the damaged copy is left at the reservation name.
	// An exchange replaces a store entry, so the process-use invariant applies to it too.
	var replacedTrees []string
	for _, replaced := range replacements {
		replacedTrees = append(replacedTrees, in.Paths.Absolute(replaced.entry.Location()))
	}
	if len(replacedTrees) > 0 {
		if rejected, used := firstUse(in.Final, failure.StagePublish, replacedTrees); used {
			b.step("A11", Abandon(own, in.IDs, ids.next)...)
			return b.reject("A11", *rejected)
		}
	}
	for _, replaced := range replacements {
		b.step("A11", ProveUnused{replaced.entry.Location()}, FullSync{},
			ExchangeEntry{replaced.entry, replaced.reservation},
			SyncDirectory{rootDirectory(replaced.entry.Kind.Store())})
	}
	// A12: publish the version directory.
	b.step("A12", FullSync{}, PublishVersion{stagingID, newVersion},
		SyncDirectory{record.At(record.RootR, layout.VersionsName)})
	// A13: the switch. The full sync makes the published version durable before current can name it.
	b.step("A13", LinkCurrentNext{newVersion}, FullSync{}, SwapCurrent{}, SyncDirectory{rootDirectory(record.RootR)})
	// A14: ensure the public command.
	ensurePublic(&b, "A14", in.Paths, in.Relocked, ids)
	// A15: delete the outgoing version and every entry outside the new bound set, under the process-use invariant.
	var exchanged []Reservation
	for _, replaced := range replacements {
		exchanged = append(exchanged, Reservation{replaced.entry.Kind, replaced.reservation})
	}
	if rejected := afterSwitch(&b, in, selection, newVersion, published, exchanged, ids); rejected != nil {
		return b.reject("A15", *rejected)
	}
	b.step("A15", DeleteHandoff{})
	b.release("A15")
	return b.finish(failure.NewOutcome(outcomeOf(command, hasCurrent || damaged), observations...))
}

// ensureStore creates a store's directories when P4 did not see them, or when P5 renamed them aside with a residue.
// The runtime store's runtimes directory is not observed, so it is always ensured.
func ensureStore(b *builder, locked layout.Snapshot, residual bool, kind EntryKind) {
	parent := layout.WorkspaceStoreName
	if kind == EntryBase {
		parent = layout.BaseStoreName
	}
	if _, ok := locked.ShareEntry(parent); !ok || residual {
		b.step("A6", EnsureDirectory{record.At(record.RootHomeShare, parent), 0o700})
	}
	if kind == EntryRuntime {
		b.step("A6", EnsureDirectory{rootDirectory(record.RootRuntimeStore), 0o700})
	}
}

func outcomeOf(command Command, replaced bool) failure.OutcomeKind {
	switch {
	case command == CommandRepair:
		return failure.Repaired
	case replaced:
		return failure.Upgraded
	default:
		return failure.Installed
	}
}

// boundOf is the selection current binds and the bound set it implies; damaged makes the set unknown.
func boundOf(outgoing layout.Classification) (record.ProductSelection, BoundSet) {
	switch state := outgoing.(type) {
	case layout.Current:
		return state.Installed.Selection, Bound(state.Installed.Selection)
	case layout.Damaged:
		return record.ProductSelection{}, UnknownBound()
	default:
		return record.ProductSelection{}, Bound()
	}
}

// relockAdmitted requires that nothing changed the installation between A4 and A8: no other writer can take the
// main byte exclusively meanwhile, so any change fails closed.
func relockAdmitted(command Command, outgoing, relocked layout.Classification) *failure.Failure {
	if rejected := Admit(command, relocked); rejected != nil {
		return rejected
	}
	// A2 creates R, so an absent layout is Incomplete by A9.
	sameState := relocked.State() == outgoing.State() ||
		(outgoing.State() == layout.LayoutAbsent && relocked.State() == layout.LayoutIncomplete)
	before, wasCurrent := outgoing.(layout.Current)
	after, isCurrent := relocked.(layout.Current)
	if !sameState || wasCurrent != isCurrent ||
		(isCurrent && before.Installed.Version != after.Installed.Version) {
		rejected := failure.NewLayoutDamaged(failure.StagePrelude, missingCurrent)
		return &rejected
	}
	return nil
}

func publicCommandAdmitted(paths Paths, snapshot layout.Snapshot) *failure.Failure {
	entry, ok := snapshot.BinEntry(layout.PublicCommandName)
	if !ok {
		return nil
	}
	if target, link := entry.Target(); link && entry.Owned() && target == paths.PublicTarget() {
		return nil
	}
	rejected := failure.NewPublicCommandForeign(failure.StagePrelude,
		record.At(record.RootPublicCommand, layout.PublicCommandName))
	return &rejected
}

// ensurePublic creates the public link when it is absent; the exact owned link already has the fixed target.
func ensurePublic(b *builder, name StepName, paths Paths, snapshot layout.Snapshot, ids *fresh) {
	if _, ok := snapshot.BinEntry(layout.PublicCommandName); ok && publicCommandAdmitted(paths, snapshot) == nil {
		return
	}
	link := ids.id()
	b.step(name, LinkPublicNext{link}, PlacePublic{link, false}, SyncDirectory{rootDirectory(record.RootPublicCommand)})
}

// alreadyCurrent ends an unchanged install or upgrade: verify the handoff, ensure the public link, release.
func alreadyCurrent(b *builder, in InstallInput, ids *fresh) Plan {
	b.step("A5", VerifyHandoff{})
	if _, _, rejected := admittedHandoff(in); rejected != nil {
		return b.reject("A5", *rejected)
	}
	ensurePublic(b, "A14", in.Paths, in.Locked, ids)
	b.step("A15", DeleteHandoff{})
	b.release("A15")
	return b.finish(failure.NewOutcome(failure.AlreadyCurrent))
}

// outgoingTrees are the absolute trees forced retirement looks for processes in: the outgoing version and the
// entries it binds, or, when current cannot be read, every version and both stores.
func outgoingTrees(paths Paths, snapshot layout.Snapshot, outgoing layout.Classification) []string {
	if current, ok := outgoing.(layout.Current); ok {
		selection := current.Installed.Selection
		trees := []string{paths.Absolute(versionLocation(current.Installed.Version)),
			paths.Absolute(Entry{EntryRuntime, selection.RuntimeArchive()}.Location())}
		if base, ok := selection.Base(); ok {
			trees = append(trees, paths.Absolute(Entry{EntryBase, base}.Location()))
		}
		return trees
	}
	var trees []string
	for _, entry := range snapshot.Versions() {
		if id, ok := record.ParseVersionID(entry.Name()); ok {
			trees = append(trees, paths.Absolute(versionLocation(id)))
		}
	}
	return append(trees, paths.Root(record.RootRuntimeStore), paths.Root(record.RootBaseStore))
}

// afterSwitch is A15's P5: current names the new version and binds only the new selection's entries.
func afterSwitch(b *builder, in InstallInput, selection record.ProductSelection, newVersion record.VersionID,
	published []Entry, exchanged []Reservation, ids *fresh) *failure.Failure {
	bound := Bound(selection)
	var trees []record.Location
	for _, entry := range in.Relocked.Versions() {
		if id, ok := record.ParseVersionID(entry.Name()); ok && id != newVersion && entry.OwnedDirectory() {
			trees = append(trees, versionLocation(id))
		}
	}
	seen := map[Entry]bool{}
	for _, store := range []struct {
		kind    EntryKind
		entries []layout.Entry
	}{{EntryRuntime, in.Relocked.RuntimeStore()}, {EntryBase, in.Relocked.BaseStore()}} {
		for _, entry := range store.entries {
			if digest, ok := record.ParseDigest(entry.Name()); ok && entry.OwnedDirectory() {
				seen[Entry{store.kind, digest}] = true
				if !bound.Binds(Entry{store.kind, digest}) {
					trees = append(trees, Entry{store.kind, digest}.Location())
				}
			}
		}
	}
	for _, entry := range published {
		if !seen[entry] && !bound.Binds(entry) {
			trees = append(trees, entry.Location())
		}
	}
	var absolute []string
	for _, tree := range trees {
		absolute = append(absolute, in.Paths.Absolute(tree))
	}
	if rejected, used := firstUse(in.Final, failure.StageCleanup, absolute); used {
		return rejected
	}
	var aside []record.VersionID
	for _, tree := range trees {
		id := ids.id()
		aside = append(aside, id)
		b.step("A15", ProveUnused{tree}, RenameAside{tree, id})
	}
	for _, replaced := range exchanged {
		id := ids.id()
		aside = append(aside, id)
		b.step("A15", RenameAside{reservation(replaced.Kind, replaced.ID), id})
	}
	for _, id := range aside {
		b.step("A15", DeleteRemoving{id})
	}
	return nil
}
