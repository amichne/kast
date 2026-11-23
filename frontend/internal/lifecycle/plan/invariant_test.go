package plan

import (
	"fmt"
	"strings"
	"testing"

	"github.com/amichne/kast/frontend/internal/lifecycle/envelope"
	"github.com/amichne/kast/frontend/internal/lifecycle/failure"
	"github.com/amichne/kast/frontend/internal/lifecycle/layout"
	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

// The rules below are written from sections 3 and 7 of the specification, independently of the planner, and are
// checked on every prefix of every representative plan: a crash can stop a command after any intent.

// lockTracker follows the two bytes through a prefix.
type lockTracker struct{ intent, main LockState }

func (l *lockTracker) apply(intent Intent) {
	if lock, ok := intent.(SetLock); ok {
		if lock.Range == failure.RangeIntent {
			l.intent = lock.State
		} else {
			l.main = lock.State
		}
	}
}

func (l lockTracker) exclusive() bool { return l.intent == LockExclusive && l.main == LockExclusive }
func (l lockTracker) held() bool      { return l.main == LockShared || l.main == LockExclusive }

// destructive intents change or remove a name that current, a reader, or another command can observe.
func destructive(intent Intent) bool {
	switch intent.(type) {
	case RenameAside, DeleteRemoving, Unlink, EmptyTree, RemoveDirectory, RenameRoot, ExchangeEntry,
		PublishVersion, LinkCurrentNext, SwapCurrent, LinkPublicNext, PlacePublic, Retire, StopSupervisorEntry,
		Terminate, RemoveLoginAgent, LockStoreLifetime:
		return true
	}
	return false
}

// private intents write only the command's own staging tree, its reservations, or a new digest-named entry.
func private(intent Intent) bool {
	switch intent.(type) {
	case Download, ReserveEntry, ExtractEntry, SyncTree, FullSync, PublishEntry, SyncDirectory, CreateStaging,
		ExtractControl, RequireLayoutMarker, TrainArchive, WriteSelection, CopyBuildRecord, WritePayloadManifest,
		SealTree, EnsureDirectory, VerifyEntry, VerifyVersion, VerifyHandoff:
		return true
	}
	return false
}

// checkPrefix returns the first rule a prefix breaks.
func checkPrefix(intents []Intent, protected map[string]bool) error {
	var locks lockTracker
	// proven records ProveUnused locations since the last time the exclusive lock was not held.
	proven := map[record.Location]bool{}
	// contentSynced and parentPending implement the sync-order rule of section 7.
	contentSynced := map[string]bool{}
	var parentPending []string
	fullSynced := false
	// Own trees are private: deleting them needs only the main byte, because no other command's P5 can run.
	own := map[string]bool{}
	ownAside := map[record.VersionID]bool{}
	for i, intent := range intents {
		locks.apply(intent)
		if !locks.exclusive() {
			proven = map[record.Location]bool{}
		}
		if _, ok := intent.(SetLock); ok && len(parentPending) > 0 && !locks.held() {
			return fmt.Errorf("intent %d: the lock was released before the parent sync of %v", i, parentPending)
		}
		ownEffect := false
		switch effect := intent.(type) {
		case CreateStaging:
			own[where(staging(effect.Staging))] = true
		case ReserveEntry:
			own[where(reservation(effect.Kind, effect.Reservation))] = true
		case RenameAside:
			if own[where(effect.From)] {
				ownAside[effect.Removing], ownEffect = true, true
			}
		case DeleteRemoving:
			ownEffect = ownAside[effect.Removing]
		case EnsureDirectory:
			// P1 creates the home directories before the lock exists.
			ownEffect = effect.At.Root == record.RootHome
		}
		switch {
		case ownEffect && (locks.held() || isHomeDirectory(intent)):
		case destructive(intent) && !locks.exclusive():
			return fmt.Errorf("intent %d (%s) runs without both bytes exclusive", i, intent)
		case private(intent) && !locks.held():
			return fmt.Errorf("intent %d (%s) runs without the main byte", i, intent)
		}
		switch effect := intent.(type) {
		case ProveUnused:
			proven[effect.Tree] = true
		case RenameAside:
			if guardedTree(effect.From) && !proven[effect.From] {
				return fmt.Errorf("intent %d renames %s aside without a process-use proof under this lock", i, where(effect.From))
			}
			if protected[where(effect.From)] {
				return fmt.Errorf("intent %d deletes the bound tree %s", i, where(effect.From))
			}
		case EmptyTree:
			if !proven[effect.At] {
				return fmt.Errorf("intent %d empties %s without a process-use proof", i, where(effect.At))
			}
		case RenameRoot:
			if !proven[versionTreeOf(intents)] && versionTreeOf(intents) != (record.Location{}) {
				return fmt.Errorf("intent %d renames R before proving its versions unused", i)
			}
			parentPending = append(parentPending, "HOME_SHARE:.")
		case SyncTree:
			contentSynced[where(effect.At)] = true
			fullSynced = false
		case FullSync:
			fullSynced = true
		case PublishEntry:
			from := where(reservation(effect.Entry.Kind, effect.Reservation))
			if !contentSynced[from] || !fullSynced {
				return fmt.Errorf("intent %d publishes %s before its contents are synced", i, from)
			}
			if len(parentPending) > 0 {
				return fmt.Errorf("intent %d publishes before the parent sync of %v", i, parentPending)
			}
			parentPending = append(parentPending, where(rootDirectory(effect.Entry.Kind.Store())))
		case PublishVersion:
			from := where(staging(effect.Staging))
			if !contentSynced[from] || !fullSynced {
				return fmt.Errorf("intent %d publishes %s before its contents are synced", i, from)
			}
			if len(parentPending) > 0 {
				return fmt.Errorf("intent %d publishes before the parent sync of %v", i, parentPending)
			}
			parentPending = append(parentPending, "R:versions")
		case ExchangeEntry:
			if !contentSynced[where(reservation(effect.Entry.Kind, effect.Reservation))] {
				return fmt.Errorf("intent %d exchanges an unsynced replacement", i)
			}
			parentPending = append(parentPending, where(rootDirectory(effect.Entry.Kind.Store())))
		case SwapCurrent:
			if len(parentPending) > 0 {
				return fmt.Errorf("intent %d switches before the parent sync of %v", i, parentPending)
			}
			parentPending = append(parentPending, "R:.")
		case PlacePublic:
			parentPending = append(parentPending, "PUBLIC_COMMAND:.")
		case SyncDirectory:
			for j, pending := range parentPending {
				if pending == where(effect.At) {
					parentPending = append(parentPending[:j], parentPending[j+1:]...)
					break
				}
			}
		}
	}
	return nil
}

func isHomeDirectory(intent Intent) bool {
	ensure, ok := intent.(EnsureDirectory)
	return ok && ensure.At.Root == record.RootHome
}

// guardedTree is a version directory, a digest-named store entry, or a whole store: what the process-use invariant
// protects.
func guardedTree(location record.Location) bool {
	text := location.Path.String()
	switch location.Root {
	case record.RootR:
		return strings.HasPrefix(text, "versions/")
	case record.RootRuntimeStore, record.RootBaseStore:
		_, ok := record.ParseDigest(text)
		return ok
	case record.RootHomeShare:
		return text == layout.WorkspaceStoreName || text == layout.BaseStoreName
	}
	return false
}

func versionTreeOf(intents []Intent) record.Location {
	for _, intent := range intents {
		if proof, ok := intent.(ProveUnused); ok && strings.HasPrefix(proof.Tree.Path.String(), "versions/") {
			return proof.Tree
		}
	}
	return record.Location{}
}

// checkComplete applies the rules that only a finished plan must meet.
func checkComplete(plan Plan) error {
	var locks lockTracker
	for _, intent := range plan.Intents() {
		locks.apply(intent)
	}
	if locks.held() || locks.intent == LockExclusive || locks.intent == LockShared {
		return fmt.Errorf("the plan ends holding the lock")
	}
	if (plan.Result.Outcome == nil) == (plan.Result.Failure == nil) {
		return fmt.Errorf("the plan has %+v as its result", plan.Result)
	}
	created := map[string]bool{}
	gone := map[string]bool{}
	for i, intent := range plan.Intents() {
		switch effect := intent.(type) {
		case CreateStaging:
			created[where(staging(effect.Staging))] = true
		case ReserveEntry:
			created[where(reservation(effect.Kind, effect.Reservation))] = true
		case PublishVersion:
			delete(created, where(staging(effect.Staging)))
			gone[where(staging(effect.Staging))] = true
		case PublishEntry:
			delete(created, where(reservation(effect.Entry.Kind, effect.Reservation)))
			gone[where(reservation(effect.Entry.Kind, effect.Reservation))] = true
		case RenameAside:
			if gone[where(effect.From)] {
				return fmt.Errorf("intent %d renames %s, which an earlier intent already published", i, where(effect.From))
			}
			delete(created, where(effect.From))
			gone[where(effect.From)] = true
		}
	}
	if len(created) > 0 {
		return fmt.Errorf("the plan leaves its own trees %v", created)
	}
	return nil
}

// representative plans for every machine, including rejections after own trees exist.
func representativePlans(t *testing.T) map[string]struct {
	plan      Plan
	protected map[string]bool
} {
	t.Helper()
	plans := map[string]struct {
		plan      Plan
		protected map[string]bool
	}{}
	add := func(name string, plan Plan, protected ...string) {
		set := map[string]bool{}
		for _, location := range protected {
			set[location] = true
		}
		plans[name] = struct {
			plan      Plan
			protected map[string]bool
		}{plan, set}
	}
	newBound := []string{"RUNTIME_STORE:" + runtimeHex, "BASE_STORE:" + baseHex, "R:versions/" + id(1)}
	add("upgrade", Install(upgradeInput(t)), newBound...)
	add("install", Install(freshInput(t)), newBound...)
	already := upgradeInput(t)
	already.Handoff, already.RecordBytes = handoff(t, envelope.OperationUpgrade, buildRecord(t)), buildRecord(t)
	add("already current", Install(already))
	marker := upgradeInput(t)
	marker.LayoutMarker = false
	add("layout marker rejected", Install(marker))
	missing := freshInput(t)
	missing.LayoutMarker = false
	add("layout marker rejected after publishing entries", Install(missing))
	retire := upgradeInput(t)
	retire.Retirement.TimedOut = true
	add("retirement timed out", Install(retire))
	add("uninstall", Uninstall(uninstallInput(t)))
	forced := uninstallInput(t)
	forced.Force = true
	add("forced uninstall", Uninstall(forced))
	blocked := uninstallInput(t)
	blocked.Committed = []Process{{PID: 5, WorkingDirectory: paths(t).Root(record.RootR) + "/versions/" + oldHex}}
	add("uninstall blocked at the commit", Uninstall(blocked))
	return plans
}

// TestEveryPrefixOfEveryPlanKeepsTheInvariants enumerates every prefix: destructive intents run only with both
// bytes exclusive, private writes only under the main byte, every publishing rename follows the sync of its
// contents and a sync of its parent follows it before the next switch or release, every deletion of a version or
// entry follows its process-use proof under the same exclusive hold, and no bound tree is deleted.
func TestEveryPrefixOfEveryPlanKeepsTheInvariants(t *testing.T) {
	for name, c := range representativePlans(t) {
		intents := c.plan.Intents()
		if len(intents) == 0 {
			t.Errorf("%s: empty plan", name)
		}
		for k := 0; k <= len(intents); k++ {
			if err := checkPrefix(intents[:k], c.protected); err != nil {
				t.Errorf("%s, prefix %d of %d: %v", name, k, len(intents), err)
				break
			}
		}
		if err := checkComplete(c.plan); err != nil {
			t.Errorf("%s: %v", name, err)
		}
	}
}

// TestTheRulesCatchBrokenPlans proves the checker rejects each violation it claims to find.
func TestTheRulesCatchBrokenPlans(t *testing.T) {
	v := must(record.ParseVersionID(id(1)))
	s := must(record.ParseVersionID(id(2)))
	exclusive := []Intent{SetLock{failure.RangeIntent, LockExclusive, false}, SetLock{failure.RangeMain, LockExclusive, true}}
	old := versionLocation(must(record.ParseVersionID(oldHex)))
	broken := map[string][]Intent{
		"swap without the lock": {SwapCurrent{}},
		"publish before sync": append(append([]Intent{}, exclusive...), CreateStaging{s}, FullSync{},
			PublishVersion{s, v}),
		"switch before the parent sync": append(append([]Intent{}, exclusive...), CreateStaging{s}, SyncTree{staging(s)},
			FullSync{}, PublishVersion{s, v}, LinkCurrentNext{v}, SwapCurrent{}),
		"delete without proof": append(append([]Intent{}, exclusive...), RenameAside{old, v}),
		"proof from an earlier hold": append(append([]Intent{}, exclusive...), ProveUnused{old},
			SetLock{failure.RangeMain, LockShared, false}, SetLock{failure.RangeIntent, LockUnlocked, false},
			SetLock{failure.RangeIntent, LockExclusive, false}, SetLock{failure.RangeMain, LockExclusive, true},
			RenameAside{old, v}),
		"private write without the lock": {CreateStaging{s}},
	}
	for name, intents := range broken {
		if err := checkPrefix(intents, nil); err == nil {
			t.Errorf("%s was accepted", name)
		}
	}
	if err := checkPrefix(append(append([]Intent{}, exclusive...), ProveUnused{old}, RenameAside{old, v}),
		map[string]bool{where(old): true}); err == nil {
		t.Error("deleting a bound tree was accepted")
	}
	if err := checkComplete(Plan{Steps: []Step{{"A7", append(exclusive, CreateStaging{s})}},
		Result: Result{Outcome: &failure.Outcome{}}}); err == nil {
		t.Error("a plan that keeps its lock and staging tree was accepted")
	}
}
