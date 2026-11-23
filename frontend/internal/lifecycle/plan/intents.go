package plan

import (
	"fmt"

	"github.com/amichne/kast/frontend/internal/lifecycle/failure"
	"github.com/amichne/kast/frontend/internal/lifecycle/layout"
	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

// Intent is one typed effect a plan asks the interpreter to perform. The set is sealed.
type Intent interface {
	fmt.Stringer
	intent()
}

func where(location record.Location) string {
	if location.Path.String() == "" {
		return location.Root.String() + ":."
	}
	return location.String()
}

func rootDirectory(root record.RootKind) record.Location { return record.Location{Root: root} }

// EntryKind is the closed set of store entries Go publishes.
type EntryKind uint8

const (
	EntryRuntime EntryKind = iota + 1
	EntryBase
)

func (k EntryKind) String() string {
	if k == EntryRuntime {
		return "runtime"
	}
	return "base"
}

// Store is the root the entry kind lives in.
func (k EntryKind) Store() record.RootKind {
	if k == EntryRuntime {
		return record.RootRuntimeStore
	}
	return record.RootBaseStore
}

func (k EntryKind) reservationPrefix() string {
	if k == EntryRuntime {
		return layout.RuntimeReservation
	}
	return layout.BaseReservation
}

// Entry names one store entry.
type Entry struct {
	Kind   EntryKind
	Digest record.Digest
}

// Location is the entry's directory.
func (e Entry) Location() record.Location { return record.At(e.Kind.Store(), e.Digest.String()) }

func reservation(kind EntryKind, id record.VersionID) record.Location {
	return record.At(kind.Store(), layout.Prefixed(kind.reservationPrefix(), id))
}

func staging(id record.VersionID) record.Location {
	return record.At(record.RootR, layout.Prefixed(layout.StagingPrefix, id))
}

func versionLocation(id record.VersionID) record.Location {
	return record.Location{Root: record.RootR, Path: record.VersionPath(id)}
}

// LockState is the closed set of states one byte range is set to.
type LockState uint8

const (
	LockUnlocked LockState = iota + 1
	LockShared
	LockExclusive
)

func (s LockState) String() string {
	return map[LockState]string{LockUnlocked: "UNLOCKED", LockShared: "SHARED", LockExclusive: "EXCLUSIVE"}[s]
}

// EnsureDirectory makes one directory with an exact mode unless an owned directory already exists there.
type EnsureDirectory struct {
	At   record.Location
	Mode uint32
}

// OpenLockFile opens S/kast.lock with O_CREAT|O_NOFOLLOW|O_CLOEXEC and checks its owner, link count and mode.
type OpenLockFile struct{}

// SetLock sets one byte range of S/kast.lock with F_SETLK; Bounded retries within the lock budget.
type SetLock struct {
	Range   failure.LockRange
	State   LockState
	Bounded bool
}

// ProveUnused rescans the user's processes and requires that none uses the tree.
type ProveUnused struct{ Tree record.Location }

// RenameAside renames a tree to S/.kast-removing-<id>, the inert name its deletion runs under.
type RenameAside struct {
	From     record.Location
	Removing record.VersionID
}

// DeleteRemoving deletes S/.kast-removing-<id> best effort; a tree that cannot be fully deleted is retained.
type DeleteRemoving struct{ Removing record.VersionID }

// Unlink removes one owned symbolic link.
type Unlink struct{ At record.Location }

// LockStoreLifetime takes Kotlin's store lifetime lock exclusively; UnlockStoreLifetime releases it.
type LockStoreLifetime struct{}

// UnlockStoreLifetime releases Kotlin's store lifetime lock.
type UnlockStoreLifetime struct{}

// EmptyTree deletes a directory's contents and keeps the directory.
type EmptyTree struct{ At record.Location }

// RemoveDirectory removes one empty directory.
type RemoveDirectory struct{ At record.Location }

// RenameRoot renames R to the uninstall witness S/.kast-uninstalled-<id>: the uninstall commit point.
type RenameRoot struct{ Witness record.VersionID }

// SyncDirectory fsyncs one directory.
type SyncDirectory struct{ At record.Location }

// SyncTree fsyncs every file and directory of a tree.
type SyncTree struct{ At record.Location }

// FullSync is the one F_FULLFSYNC before a publishing rename on macOS.
type FullSync struct{}

// VerifyVersion checks a version directory against its manifest (T3).
type VerifyVersion struct{ Version record.VersionID }

// VerifyEntry checks a store entry against its receipt and manifest (V2).
type VerifyEntry struct{ Entry Entry }

// VerifyHandoff checks the record against the handoff digest and the own executable against controlSha256 (V1).
type VerifyHandoff struct{}

// Download fetches one release asset into the handoff directory and checks it against the record.
type Download struct{ Asset record.AssetName }

// ReserveEntry creates the private reservation for a store entry.
type ReserveEntry struct {
	Kind        EntryKind
	Reservation record.VersionID
}

// ExtractEntry extracts and verifies an entry into its reservation and writes its receipts.
type ExtractEntry struct {
	Entry       Entry
	Reservation record.VersionID
}

// PublishEntry renames a reservation to its digest name without replacement.
type PublishEntry struct {
	Entry       Entry
	Reservation record.VersionID
}

// ExchangeEntry swaps a damaged entry with its staged replacement.
type ExchangeEntry struct {
	Entry       Entry
	Reservation record.VersionID
}

// CreateStaging makes R/.staging-<id>.
type CreateStaging struct{ Staging record.VersionID }

// ExtractControl extracts the Control payload from the bundle into the staging tree.
type ExtractControl struct{ Staging record.VersionID }

// RequireLayoutMarker requires share/kast/layout-v2 with content "2\n" in the staging tree.
type RequireLayoutMarker struct{ Staging record.VersionID }

// TrainArchive trains the class-data archive with java from the runtime entry.
type TrainArchive struct {
	Staging record.VersionID
	Runtime record.Digest
}

// WriteSelection writes product-selection.json into the staging tree.
type WriteSelection struct {
	Staging   record.VersionID
	Selection record.ProductSelection
}

// CopyBuildRecord writes the verified record bytes into the staging tree.
type CopyBuildRecord struct{ Staging record.VersionID }

// WritePayloadManifest writes installation.json for every file of the staging tree.
type WritePayloadManifest struct {
	Staging record.VersionID
	Version record.VersionID
}

// SealTree sets the payload's read-only modes.
type SealTree struct{ Staging record.VersionID }

// PublishVersion renames the staging tree to R/versions/<id> without replacement.
type PublishVersion struct {
	Staging record.VersionID
	Version record.VersionID
}

// LinkCurrentNext creates R/current.next naming a version.
type LinkCurrentNext struct{ Version record.VersionID }

// SwapCurrent renames R/current.next over R/current.
type SwapCurrent struct{}

// LinkPublicNext creates ~/.local/bin/kast.next-<id> with the fixed public target.
type LinkPublicNext struct{ Link record.VersionID }

// PlacePublic renames the link into ~/.local/bin/kast: without replacement when absent, plainly over the exact owned
// link otherwise.
type PlacePublic struct {
	Link    record.VersionID
	Replace bool
}

// Retire runs the outgoing version's managed-retire child, with --removal for uninstall.
type Retire struct {
	Version record.VersionID
	Removal bool
}

// StopSupervisorEntry stops one owned supervisor entry.
type StopSupervisorEntry struct {
	Supervisor failure.SupervisorKind
	Label      record.ServiceLabel
}

// AwaitSupervisorAbsent polls until a supervisor entry is absent, within the retirement deadline.
type AwaitSupervisorAbsent struct {
	Supervisor failure.SupervisorKind
	Label      record.ServiceLabel
}

// Terminate sends SIGTERM, waits within a bounded deadline, sends SIGKILL, and confirms the incarnation absent.
type Terminate struct{ Incarnation record.Incarnation }

// RemoveLoginAgent removes a login agent that matches its exact owned document.
type RemoveLoginAgent struct{ Label record.ServiceLabel }

// DeleteHandoff deletes the private handoff directory.
type DeleteHandoff struct{}

func (i EnsureDirectory) String() string { return fmt.Sprintf("mkdir %s %04o", where(i.At), i.Mode) }
func (OpenLockFile) String() string      { return "open-lock-file" }
func (i SetLock) String() string {
	if i.Bounded {
		return fmt.Sprintf("lock %s %s bounded", i.Range, i.State)
	}
	return fmt.Sprintf("lock %s %s", i.Range, i.State)
}
func (i ProveUnused) String() string { return "prove-unused " + where(i.Tree) }
func (i RenameAside) String() string {
	return "rename-aside " + where(i.From) + " " + layout.Prefixed(layout.RemovingPrefix, i.Removing)
}
func (i DeleteRemoving) String() string {
	return "delete " + layout.Prefixed(layout.RemovingPrefix, i.Removing)
}
func (i Unlink) String() string            { return "unlink " + where(i.At) }
func (LockStoreLifetime) String() string   { return "lock-store-lifetime" }
func (UnlockStoreLifetime) String() string { return "unlock-store-lifetime" }
func (i EmptyTree) String() string         { return "empty " + where(i.At) }
func (i RemoveDirectory) String() string   { return "rmdir " + where(i.At) }
func (i RenameRoot) String() string {
	return "rename-root " + layout.Prefixed(layout.WitnessPrefix, i.Witness)
}
func (i SyncDirectory) String() string { return "sync-dir " + where(i.At) }
func (i SyncTree) String() string      { return "sync-tree " + where(i.At) }
func (FullSync) String() string        { return "full-sync" }
func (i VerifyVersion) String() string { return "verify-version " + i.Version.String() }
func (i VerifyEntry) String() string   { return "verify-entry " + where(i.Entry.Location()) }
func (VerifyHandoff) String() string   { return "verify-handoff" }
func (i Download) String() string      { return "download " + i.Asset.String() }
func (i ReserveEntry) String() string  { return "reserve " + where(reservation(i.Kind, i.Reservation)) }
func (i ExtractEntry) String() string {
	return "extract " + i.Entry.Kind.String() + " " + where(reservation(i.Entry.Kind, i.Reservation))
}
func (i PublishEntry) String() string {
	return "publish " + where(reservation(i.Entry.Kind, i.Reservation)) + " " + where(i.Entry.Location())
}
func (i ExchangeEntry) String() string {
	return "exchange " + where(reservation(i.Entry.Kind, i.Reservation)) + " " + where(i.Entry.Location())
}
func (i CreateStaging) String() string  { return "create-staging " + where(staging(i.Staging)) }
func (i ExtractControl) String() string { return "extract-control " + where(staging(i.Staging)) }
func (i RequireLayoutMarker) String() string {
	return "require-layout-marker " + where(staging(i.Staging))
}
func (i TrainArchive) String() string {
	return "train-archive " + where(staging(i.Staging)) + " " + i.Runtime.String()
}
func (i WriteSelection) String() string  { return "write-selection " + where(staging(i.Staging)) }
func (i CopyBuildRecord) String() string { return "write-build-record " + where(staging(i.Staging)) }
func (i WritePayloadManifest) String() string {
	return "write-manifest " + where(staging(i.Staging)) + " " + i.Version.String()
}
func (i SealTree) String() string { return "seal " + where(staging(i.Staging)) }
func (i PublishVersion) String() string {
	return "publish-version " + where(staging(i.Staging)) + " " + where(versionLocation(i.Version))
}
func (i LinkCurrentNext) String() string { return "link R:current.next versions/" + i.Version.String() }
func (SwapCurrent) String() string       { return "swap R:current.next R:current" }
func (i LinkPublicNext) String() string {
	return "link PUBLIC_COMMAND:" + layout.Prefixed(layout.PublicCommandNextName, i.Link)
}
func (i PlacePublic) String() string {
	mode := "no-replace"
	if i.Replace {
		mode = "replace"
	}
	return "place-public " + layout.Prefixed(layout.PublicCommandNextName, i.Link) + " " + mode
}
func (i Retire) String() string {
	if i.Removal {
		return "retire " + i.Version.String() + " removal"
	}
	return "retire " + i.Version.String()
}
func (i StopSupervisorEntry) String() string {
	return "stop " + i.Supervisor.String() + " " + i.Label.String()
}
func (i AwaitSupervisorAbsent) String() string {
	return "await-absent " + i.Supervisor.String() + " " + i.Label.String()
}
func (i Terminate) String() string {
	return fmt.Sprintf("terminate %d@%d", i.Incarnation.PID, i.Incarnation.StartTime)
}
func (i RemoveLoginAgent) String() string { return "remove-login-agent " + i.Label.String() }
func (DeleteHandoff) String() string      { return "delete-handoff" }

func (EnsureDirectory) intent()       {}
func (OpenLockFile) intent()          {}
func (SetLock) intent()               {}
func (ProveUnused) intent()           {}
func (RenameAside) intent()           {}
func (DeleteRemoving) intent()        {}
func (Unlink) intent()                {}
func (LockStoreLifetime) intent()     {}
func (UnlockStoreLifetime) intent()   {}
func (EmptyTree) intent()             {}
func (RemoveDirectory) intent()       {}
func (RenameRoot) intent()            {}
func (SyncDirectory) intent()         {}
func (SyncTree) intent()              {}
func (FullSync) intent()              {}
func (VerifyVersion) intent()         {}
func (VerifyEntry) intent()           {}
func (VerifyHandoff) intent()         {}
func (Download) intent()              {}
func (ReserveEntry) intent()          {}
func (ExtractEntry) intent()          {}
func (PublishEntry) intent()          {}
func (ExchangeEntry) intent()         {}
func (CreateStaging) intent()         {}
func (ExtractControl) intent()        {}
func (RequireLayoutMarker) intent()   {}
func (TrainArchive) intent()          {}
func (WriteSelection) intent()        {}
func (CopyBuildRecord) intent()       {}
func (WritePayloadManifest) intent()  {}
func (SealTree) intent()              {}
func (PublishVersion) intent()        {}
func (LinkCurrentNext) intent()       {}
func (SwapCurrent) intent()           {}
func (LinkPublicNext) intent()        {}
func (PlacePublic) intent()           {}
func (Retire) intent()                {}
func (StopSupervisorEntry) intent()   {}
func (AwaitSupervisorAbsent) intent() {}
func (Terminate) intent()             {}
func (RemoveLoginAgent) intent()      {}
func (DeleteHandoff) intent()         {}

// StepName is the closed set of rows of the section 3 tables.
type StepName string

// Step is one row: its name and its primitive effects, in order.
type Step struct {
	Name    StepName
	Intents []Intent
}

// Result is the plan's terminal: an outcome or a failure, never both.
type Result struct {
	Outcome *failure.Outcome
	Failure *failure.Failure
}

// Plan is an ordered sequence of steps and its terminal result.
type Plan struct {
	Steps  []Step
	Result Result
}

// Intents flattens the steps.
func (p Plan) Intents() []Intent {
	var all []Intent
	for _, step := range p.Steps {
		all = append(all, step.Intents...)
	}
	return all
}

// builder accumulates steps and tracks the lock state its intents leave, so a rejection releases exactly what is
// held.
type builder struct {
	steps        []Step
	intent, main LockState
}

func (b *builder) step(name StepName, intents ...Intent) {
	if len(intents) == 0 {
		return
	}
	for _, intent := range intents {
		if lock, ok := intent.(SetLock); ok {
			if lock.Range == failure.RangeIntent {
				b.intent = lock.State
			} else {
				b.main = lock.State
			}
		}
	}
	if n := len(b.steps); n > 0 && b.steps[n-1].Name == name {
		b.steps[n-1].Intents = append(b.steps[n-1].Intents, intents...)
		return
	}
	b.steps = append(b.steps, Step{name, intents})
}

// release drops every byte still held, main first.
func (b *builder) release(name StepName) {
	if b.main == LockShared || b.main == LockExclusive {
		b.step(name, SetLock{failure.RangeMain, LockUnlocked, false})
	}
	if b.intent == LockShared || b.intent == LockExclusive {
		b.step(name, SetLock{failure.RangeIntent, LockUnlocked, false})
	}
}

// reject releases what is held and ends the plan with a failure.
func (b *builder) reject(name StepName, rejected failure.Failure) Plan {
	b.release(name)
	return b.fail(rejected)
}

func (b *builder) fail(rejected failure.Failure) Plan {
	return Plan{b.steps, Result{Failure: &rejected}}
}

func (b *builder) finish(outcome failure.Outcome) Plan {
	return Plan{b.steps, Result{Outcome: &outcome}}
}
