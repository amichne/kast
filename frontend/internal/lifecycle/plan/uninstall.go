package plan

import (
	"github.com/amichne/kast/frontend/internal/lifecycle/failure"
	"github.com/amichne/kast/frontend/internal/lifecycle/layout"
	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

// UninstallInput holds every observation the uninstall machine decides from (section 3.3).
type UninstallInput struct {
	Force     bool
	Paths     Paths
	IDs       IDSource
	Home      HomeFacts
	Before    layout.Snapshot
	Locked    layout.Snapshot
	Processes []Process
	// Current is the verification of the retirement executables; Retirement is managed-retire --removal's result.
	Current    CurrentVerification
	Retirement RetirementObservation
	Forced     ForcedObservation
	// Committed is the process table at X3, after retirement.
	Committed []Process
}

// Uninstall plans uninstall and uninstall --force. The commit point is the rename of R to the witness; before it
// the installation and the stores are intact, so a rerun can still retire through Kotlin.
func Uninstall(in UninstallInput) Plan {
	var b builder
	ids := &fresh{source: in.IDs}
	command := CommandUninstall
	if in.Force {
		command = CommandForceUninstall
	}
	if rejected := Precheck(in.Home, layout.Classify(in.Before)); rejected != nil {
		return b.fail(*rejected)
	}
	// X1: P1 to P5.
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
	kind := failure.Uninstalled
	if in.Force {
		kind = failure.UninstalledWithoutUserState
	}
	if _, ok := in.Locked.ShareEntry(layout.RootName); !ok {
		// Only a residue existed, and P5 finished it.
		b.release("X5")
		return b.finish(failure.NewOutcome(kind))
	}
	current, hasCurrent := outgoing.(layout.Current)

	// X2: retire. Through Kotlin's managed-retire --removal when the current version verifies; forced otherwise
	// or with --force, which skips Kotlin's removal checks.
	var observations []failure.Observation
	if hasCurrent && !in.Force {
		version := current.Installed.Version
		b.step("X2", VerifyVersion{version}, VerifyEntry{Entry{EntryRuntime, currentSelection.RuntimeArchive()}})
		if in.Current.Retirement != nil {
			return b.reject("X2", failure.NewRetirementExecutableDamaged(failure.StageVerify,
				failureLocation(*in.Current.Retirement)))
		}
		b.step("X2", Retire{version, true})
		noted, rejected := ConfirmRetirement(in.Retirement)
		if rejected != nil {
			return b.reject("X2", *rejected)
		}
		observations = append(observations, noted...)
	} else if in.Force {
		steps, noted := ForcedRetirement(in.Paths, outgoingTrees(in.Paths, in.Locked, outgoing), in.Forced,
			in.Processes)
		for _, step := range steps {
			b.step("X2", step.Intents...)
		}
		observations = append(observations, noted...)
	}

	// X3: prove that no process uses any version or bound entry, then commit by renaming R to the witness.
	trees := committedTrees(in.Locked, outgoing)
	var absolute []string
	for _, tree := range trees {
		absolute = append(absolute, in.Paths.Absolute(tree))
	}
	if rejected, used := firstUse(in.Committed, absolute); used {
		return b.reject("X3", *rejected)
	}
	for _, tree := range trees {
		b.step("X3", ProveUnused{tree})
	}
	witness := ids.id()
	b.step("X3", RenameRoot{witness}, SyncDirectory{rootDirectory(record.RootHomeShare)})

	// X4: finish the residue that the commit created.
	_, residual := locked.(layout.Residual)
	residue, err := committedSnapshot(in.Paths, in.Locked, residual, witness)
	if err != nil {
		panic("plan: the committed snapshot of an admitted snapshot is inconsistent: " + err.Error())
	}
	if rejected := cleanup(&b, "X4", cleanupInput{in.Paths, residue, layout.Classify(residue), Bound(), Own{},
		in.Committed, ids}); rejected != nil {
		return b.reject("X4", *rejected)
	}
	// X5: release both bytes; S/kast.lock stays.
	b.release("X5")
	return b.finish(failure.NewOutcome(kind, observations...))
}

// failureLocation reads the location of a path failure, so the retirement verdict names the damaged file.
func failureLocation(damage failure.Failure) record.Location {
	if evidence, ok := damage.Evidence().(failure.PathEvidence); ok {
		return record.Location{Root: evidence.Root, Path: evidence.Path}
	}
	return record.At(record.RootR, layout.CurrentName)
}

// committedTrees are every version directory and every entry current binds, or every store entry when current
// cannot be read.
func committedTrees(snapshot layout.Snapshot, outgoing layout.Classification) []record.Location {
	var trees []record.Location
	for _, entry := range snapshot.Versions() {
		if id, ok := record.ParseVersionID(entry.Name()); ok {
			trees = append(trees, versionLocation(id))
		}
	}
	_, bound := boundOf(outgoing)
	for _, store := range []struct {
		kind    EntryKind
		entries []layout.Entry
	}{{EntryRuntime, snapshot.RuntimeStore()}, {EntryBase, snapshot.BaseStore()}} {
		for _, entry := range store.entries {
			if digest, ok := record.ParseDigest(entry.Name()); ok && bound.Binds(Entry{store.kind, digest}) {
				trees = append(trees, Entry{store.kind, digest}.Location())
			}
		}
	}
	return trees
}

// committedSnapshot is the layout right after X3: R is gone, the new witness exists, and what X1's P5 removed is
// gone too: removing trees, an earlier residue's witnesses, and, if X1 finished a residue, the stores and residue
// links.
func committedSnapshot(paths Paths, locked layout.Snapshot, residual bool, witness record.VersionID) (layout.Snapshot,
	error) {
	var share []layout.Entry
	for _, entry := range locked.Share() {
		switch layout.ClassifyShareName(entry.Name()).Kind {
		case layout.ShareRoot, layout.ShareRemoving, layout.ShareWitness:
		case layout.ShareWorkspaceStore, layout.ShareBaseStore:
			if !residual {
				share = append(share, entry)
			}
		default:
			share = append(share, entry)
		}
	}
	share = append(share, layout.Directory(layout.Prefixed(layout.WitnessPrefix, witness), true))
	observation := layout.Observation{Share: share, Containers: layout.ContainersAbsent}
	if !residual {
		observation.RuntimeStore, observation.BaseStore = locked.RuntimeStore(), locked.BaseStore()
		observation.Containers = locked.Containers()
	}
	var removed builder
	if residual {
		residueLinks(&removed, cleanupInput{paths: paths, snapshot: locked})
	}
	unlinked := map[record.Location]bool{}
	for _, intent := range removed.steps {
		for _, effect := range intent.Intents {
			unlinked[effect.(Unlink).At] = true
		}
	}
	for _, entry := range locked.Bin() {
		if !unlinked[record.At(record.RootPublicCommand, entry.Name())] {
			observation.Bin = append(observation.Bin, entry)
		}
	}
	for _, entry := range locked.TmpAliases() {
		if !unlinked[record.At(record.RootTmpAlias, entry.Name())] {
			observation.TmpAliases = append(observation.TmpAliases, entry)
		}
	}
	return layout.Observe(observation)
}
