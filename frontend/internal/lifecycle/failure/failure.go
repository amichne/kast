// Package failure is the single closed failure type of the lifecycle commands, and the separate closed union of
// their outcomes. Each code has exactly one constructor, which takes that code's own evidence, and Failure has only
// package-private fields, so a code paired with the wrong evidence cannot be built. No evidence carries an absolute
// path, payload bytes, or an environment value. The package performs no effects.
package failure

import (
	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

// Code is the closed set of lifecycle failure codes, one per check.
type Code uint8

const (
	SelectorInvalid Code = iota + 1
	SelectorsConflict
	PlatformUnsupported
	CatalogUnavailable
	CatalogInvalid
	CommitAmbiguous
	BuildUnavailable
	ReleaseLayoutUnsupported
	HandoffUnsupported
	HandoffInvalid
	RecordDigestMismatch
	RecordInvalid
	RecordSchemaUnsupported
	AssetUnavailable
	AssetSizeMismatch
	AssetDigestMismatch
	QualificationInvalid
	ArchiveEntryRejected
	BundleInventoryInvalid
	ControlDigestMismatch
	LocalArtifactRequired
	RuntimeEntryDamaged
	BaseEntryDamaged
	PayloadManifestInvalid
	PayloadFileDamaged
	RetirementExecutableDamaged
	HomeInvalid
	HomePathRejected
	RootUserRejected
	FilesystemUnsupported
	CrossDevice
	RootForeign
	LegacyLayout
	LayoutDamaged
	PublicCommandForeign
	WorkspaceStoreOccupied
	LeftoverForeign
	LockFileRejected
	LockHeld
	ProcessStillAlive
	RetirementRejected
	RetirementTimedOut
	RemovalBlocked
	SupervisorStillActive
	FilesystemRejected
)

var codeNames = names[Code]{
	SelectorInvalid: "SELECTOR_INVALID", SelectorsConflict: "SELECTORS_CONFLICT",
	PlatformUnsupported: "PLATFORM_UNSUPPORTED", CatalogUnavailable: "CATALOG_UNAVAILABLE",
	CatalogInvalid: "CATALOG_INVALID", CommitAmbiguous: "COMMIT_AMBIGUOUS", BuildUnavailable: "BUILD_UNAVAILABLE",
	ReleaseLayoutUnsupported: "RELEASE_LAYOUT_UNSUPPORTED", HandoffUnsupported: "HANDOFF_UNSUPPORTED",
	HandoffInvalid: "HANDOFF_INVALID", RecordDigestMismatch: "RECORD_DIGEST_MISMATCH",
	RecordInvalid: "RECORD_INVALID", RecordSchemaUnsupported: "RECORD_SCHEMA_UNSUPPORTED",
	AssetUnavailable: "ASSET_UNAVAILABLE", AssetSizeMismatch: "ASSET_SIZE_MISMATCH",
	AssetDigestMismatch: "ASSET_DIGEST_MISMATCH", QualificationInvalid: "QUALIFICATION_INVALID",
	ArchiveEntryRejected: "ARCHIVE_ENTRY_REJECTED", BundleInventoryInvalid: "BUNDLE_INVENTORY_INVALID",
	ControlDigestMismatch: "CONTROL_DIGEST_MISMATCH", LocalArtifactRequired: "LOCAL_ARTIFACT_REQUIRED",
	RuntimeEntryDamaged: "RUNTIME_ENTRY_DAMAGED", BaseEntryDamaged: "BASE_ENTRY_DAMAGED",
	PayloadManifestInvalid: "PAYLOAD_MANIFEST_INVALID", PayloadFileDamaged: "PAYLOAD_FILE_DAMAGED",
	RetirementExecutableDamaged: "RETIREMENT_EXECUTABLE_DAMAGED", HomeInvalid: "HOME_INVALID",
	HomePathRejected: "HOME_PATH_REJECTED", RootUserRejected: "ROOT_USER_REJECTED",
	FilesystemUnsupported: "FILESYSTEM_UNSUPPORTED", CrossDevice: "CROSS_DEVICE", RootForeign: "ROOT_FOREIGN",
	LegacyLayout: "LEGACY_LAYOUT", LayoutDamaged: "LAYOUT_DAMAGED", PublicCommandForeign: "PUBLIC_COMMAND_FOREIGN",
	WorkspaceStoreOccupied: "WORKSPACE_STORE_OCCUPIED", LeftoverForeign: "LEFTOVER_FOREIGN",
	LockFileRejected: "LOCK_FILE_REJECTED", LockHeld: "LOCK_HELD", ProcessStillAlive: "PROCESS_STILL_ALIVE",
	RetirementRejected: "RETIREMENT_REJECTED", RetirementTimedOut: "RETIREMENT_TIMED_OUT",
	RemovalBlocked: "REMOVAL_BLOCKED", SupervisorStillActive: "SUPERVISOR_STILL_ACTIVE",
	FilesystemRejected: "FILESYSTEM_REJECTED",
}

func (c Code) String() string               { return codeNames[c] }
func (c Code) MarshalText() ([]byte, error) { return codeNames.marshal(c) }

// Codes lists every code in declaration order.
func Codes() []Code {
	codes := make([]Code, 0, len(codeNames))
	for code := SelectorInvalid; code <= FilesystemRejected; code++ {
		codes = append(codes, code)
	}
	return codes
}

// EvidenceKind is the closed set of evidence shapes.
type EvidenceKind uint8

const (
	KindNone EvidenceKind = iota + 1
	KindAssetDigest
	KindPath
	KindFilesystem
	KindTransfer
	KindHolder
	KindProcess
	KindChild
	KindSupervisor
)

// evidenceOf binds each code to exactly one evidence kind (section 5).
var evidenceOf = map[Code]EvidenceKind{
	SelectorInvalid: KindNone, SelectorsConflict: KindNone, PlatformUnsupported: KindNone,
	CatalogUnavailable: KindTransfer, CatalogInvalid: KindNone, CommitAmbiguous: KindNone, BuildUnavailable: KindNone,
	ReleaseLayoutUnsupported: KindNone, HandoffUnsupported: KindPath, HandoffInvalid: KindPath,
	RecordDigestMismatch: KindAssetDigest, RecordInvalid: KindPath, RecordSchemaUnsupported: KindPath,
	AssetUnavailable: KindTransfer, AssetSizeMismatch: KindAssetDigest, AssetDigestMismatch: KindAssetDigest,
	QualificationInvalid: KindNone, ArchiveEntryRejected: KindPath, BundleInventoryInvalid: KindPath,
	ControlDigestMismatch: KindAssetDigest, LocalArtifactRequired: KindNone, RuntimeEntryDamaged: KindPath,
	BaseEntryDamaged: KindPath, PayloadManifestInvalid: KindPath, PayloadFileDamaged: KindPath,
	RetirementExecutableDamaged: KindPath, HomeInvalid: KindNone, HomePathRejected: KindPath,
	RootUserRejected: KindNone, FilesystemUnsupported: KindPath, CrossDevice: KindPath, RootForeign: KindPath,
	LegacyLayout: KindPath, LayoutDamaged: KindPath, PublicCommandForeign: KindPath, WorkspaceStoreOccupied: KindPath,
	LeftoverForeign: KindPath, LockFileRejected: KindPath, LockHeld: KindHolder, ProcessStillAlive: KindProcess,
	RetirementRejected: KindChild, RetirementTimedOut: KindChild, RemovalBlocked: KindChild,
	SupervisorStillActive: KindSupervisor, FilesystemRejected: KindFilesystem,
}

// EvidenceOf is the one evidence kind a code carries.
func EvidenceOf(code Code) EvidenceKind { return evidenceOf[code] }

// evidence is sealed: only this package's variants implement it.
type evidence interface {
	kind() EvidenceKind
	valid() bool
}

// AssetDigestEvidence names an asset and the digest expected and observed.
type AssetDigestEvidence struct {
	Asset    record.AssetName `json:"asset"`
	Expected record.Digest    `json:"expected"`
	Observed record.Digest    `json:"observed"`
}

// PathEvidence names one location.
type PathEvidence struct {
	Root record.RootKind     `json:"root"`
	Path record.RelativePath `json:"path"`
}

// FilesystemEvidence names a location, the primitive and its cause.
type FilesystemEvidence struct {
	Root      record.RootKind     `json:"root"`
	Path      record.RelativePath `json:"path"`
	Operation FsOperation         `json:"operation"`
	Cause     FsCause             `json:"cause"`
	Errno     int                 `json:"errno"`
}

// TransferEvidence names an asset and the transfer cause.
type TransferEvidence struct {
	Asset      record.AssetName `json:"asset"`
	Cause      TransferCause    `json:"cause"`
	HTTPStatus int              `json:"httpStatus"`
}

// HolderEvidence is one conflicting holder that F_GETLK reported; there may be others.
type HolderEvidence struct {
	Range  LockRange        `json:"range"`
	Mode   LockMode         `json:"mode"`
	Holder record.ProcessID `json:"holder"`
}

// ProcessEvidence is a process that uses a tree, and how.
type ProcessEvidence struct {
	Holder record.ProcessID `json:"holder"`
	Use    ProcessUse       `json:"use"`
}

// ChildEvidence is the retirement child's exit status and closed reason.
type ChildEvidence struct {
	ExitCode ExitCode                `json:"exitCode"`
	Reason   record.RetirementReason `json:"reason"`
}

// SupervisorEvidence is a supervisor entry that remains.
type SupervisorEvidence struct {
	Supervisor SupervisorKind      `json:"supervisor"`
	Label      record.ServiceLabel `json:"label"`
	State      UnitState           `json:"state"`
}

// NoEvidence is the evidence of a code that needs none.
type NoEvidence struct{}

func (AssetDigestEvidence) kind() EvidenceKind { return KindAssetDigest }
func (PathEvidence) kind() EvidenceKind        { return KindPath }
func (FilesystemEvidence) kind() EvidenceKind  { return KindFilesystem }
func (TransferEvidence) kind() EvidenceKind    { return KindTransfer }
func (HolderEvidence) kind() EvidenceKind      { return KindHolder }
func (ProcessEvidence) kind() EvidenceKind     { return KindProcess }
func (ChildEvidence) kind() EvidenceKind       { return KindChild }
func (SupervisorEvidence) kind() EvidenceKind  { return KindSupervisor }
func (NoEvidence) kind() EvidenceKind          { return KindNone }

// Failure is one rejected lifecycle check.
type Failure struct {
	code     Code
	stage    Stage
	evidence evidence
}

// Code is the failed check.
func (f Failure) Code() Code { return f.code }

// Stage is the stage the check ran in.
func (f Failure) Stage() Stage { return f.stage }

// Evidence is the code's own evidence value; its dynamic type is the code's evidence variant.
func (f Failure) Evidence() any { return f.evidence }

func (f Failure) Error() string { return f.code.String() + " at " + f.stage.String() }

func build(code Code, stage Stage, value evidence) Failure {
	if evidenceOf[code] != value.kind() || !stage.valid() || !value.valid() || !reasonFits(code, value) {
		panic("failure: " + code.String() + " built with invalid evidence or stage")
	}
	return Failure{code, stage, value}
}

// reasonFits binds the child codes to their reasons: Kotlin's removal checks block removal, the deadline times
// out, and every other reason rejects retirement.
func reasonFits(code Code, value evidence) bool {
	child, ok := value.(ChildEvidence)
	if !ok {
		return true
	}
	switch code {
	case RemovalBlocked:
		return child.Reason.Removal()
	case RetirementTimedOut:
		return child.Reason == record.ReasonDeadlineExceeded
	default:
		return !child.Reason.Removal() && child.Reason != record.ReasonDeadlineExceeded
	}
}

func validLocation(root record.RootKind, path record.RelativePath) bool {
	return root.String() != "" && path.String() != ""
}

func (e AssetDigestEvidence) valid() bool { return e.Asset.String() != "" }
func (e PathEvidence) valid() bool        { return validLocation(e.Root, e.Path) }
func (e FilesystemEvidence) valid() bool {
	return validLocation(e.Root, e.Path) && operationNames[e.Operation] != "" && fsCauseNames[e.Cause] != "" &&
		(e.Cause == CauseUnmappedErrno) == (e.Errno != 0)
}
func (e TransferEvidence) valid() bool {
	return e.Asset.String() != "" && transferNames[e.Cause] != "" &&
		(e.Cause == TransferHTTPStatus) == (e.HTTPStatus != 0)
}
func (e HolderEvidence) valid() bool {
	return rangeNames[e.Range] != "" && lockModeNames[e.Mode] != "" && e.Holder >= 0
}
func (e ProcessEvidence) valid() bool { return e.Holder > 0 && useNames[e.Use] != "" }
func (e ChildEvidence) valid() bool   { return e.Reason.String() != "" }
func (e SupervisorEvidence) valid() bool {
	return supervisorNames[e.Supervisor] != "" && e.Label.Kind() != 0 && unitStateNames[e.State] != ""
}
func (NoEvidence) valid() bool { return true }

func at(location record.Location) PathEvidence { return PathEvidence{location.Root, location.Path} }

// One constructor per code. Each takes the stage and exactly that code's evidence.

func NewSelectorInvalid(stage Stage) Failure   { return build(SelectorInvalid, stage, NoEvidence{}) }
func NewSelectorsConflict(stage Stage) Failure { return build(SelectorsConflict, stage, NoEvidence{}) }
func NewPlatformUnsupported(stage Stage) Failure {
	return build(PlatformUnsupported, stage, NoEvidence{})
}
func NewCatalogUnavailable(stage Stage, asset record.AssetName, transfer Transfer) Failure {
	return build(CatalogUnavailable, stage, TransferEvidence{asset, transfer.cause, transfer.status})
}
func NewCatalogInvalid(stage Stage) Failure   { return build(CatalogInvalid, stage, NoEvidence{}) }
func NewCommitAmbiguous(stage Stage) Failure  { return build(CommitAmbiguous, stage, NoEvidence{}) }
func NewBuildUnavailable(stage Stage) Failure { return build(BuildUnavailable, stage, NoEvidence{}) }
func NewReleaseLayoutUnsupported(stage Stage) Failure {
	return build(ReleaseLayoutUnsupported, stage, NoEvidence{})
}
func NewHandoffUnsupported(stage Stage, location record.Location) Failure {
	return build(HandoffUnsupported, stage, at(location))
}
func NewHandoffInvalid(stage Stage, location record.Location) Failure {
	return build(HandoffInvalid, stage, at(location))
}
func NewRecordDigestMismatch(stage Stage, asset record.AssetName, expected, observed record.Digest) Failure {
	return build(RecordDigestMismatch, stage, AssetDigestEvidence{asset, expected, observed})
}
func NewRecordInvalid(stage Stage, location record.Location) Failure {
	return build(RecordInvalid, stage, at(location))
}
func NewRecordSchemaUnsupported(stage Stage, location record.Location) Failure {
	return build(RecordSchemaUnsupported, stage, at(location))
}
func NewAssetUnavailable(stage Stage, asset record.AssetName, transfer Transfer) Failure {
	return build(AssetUnavailable, stage, TransferEvidence{asset, transfer.cause, transfer.status})
}
func NewAssetSizeMismatch(stage Stage, asset record.AssetName, expected, observed record.Digest) Failure {
	return build(AssetSizeMismatch, stage, AssetDigestEvidence{asset, expected, observed})
}
func NewAssetDigestMismatch(stage Stage, asset record.AssetName, expected, observed record.Digest) Failure {
	return build(AssetDigestMismatch, stage, AssetDigestEvidence{asset, expected, observed})
}
func NewQualificationInvalid(stage Stage) Failure {
	return build(QualificationInvalid, stage, NoEvidence{})
}
func NewArchiveEntryRejected(stage Stage, location record.Location) Failure {
	return build(ArchiveEntryRejected, stage, at(location))
}
func NewBundleInventoryInvalid(stage Stage, location record.Location) Failure {
	return build(BundleInventoryInvalid, stage, at(location))
}
func NewControlDigestMismatch(stage Stage, asset record.AssetName, expected, observed record.Digest) Failure {
	return build(ControlDigestMismatch, stage, AssetDigestEvidence{asset, expected, observed})
}
func NewLocalArtifactRequired(stage Stage) Failure {
	return build(LocalArtifactRequired, stage, NoEvidence{})
}
func NewRuntimeEntryDamaged(stage Stage, location record.Location) Failure {
	return build(RuntimeEntryDamaged, stage, at(location))
}
func NewBaseEntryDamaged(stage Stage, location record.Location) Failure {
	return build(BaseEntryDamaged, stage, at(location))
}
func NewPayloadManifestInvalid(stage Stage, location record.Location) Failure {
	return build(PayloadManifestInvalid, stage, at(location))
}
func NewPayloadFileDamaged(stage Stage, location record.Location) Failure {
	return build(PayloadFileDamaged, stage, at(location))
}
func NewRetirementExecutableDamaged(stage Stage, location record.Location) Failure {
	return build(RetirementExecutableDamaged, stage, at(location))
}
func NewHomeInvalid(stage Stage) Failure { return build(HomeInvalid, stage, NoEvidence{}) }
func NewHomePathRejected(stage Stage, location record.Location) Failure {
	return build(HomePathRejected, stage, at(location))
}
func NewRootUserRejected(stage Stage) Failure { return build(RootUserRejected, stage, NoEvidence{}) }
func NewFilesystemUnsupported(stage Stage, location record.Location) Failure {
	return build(FilesystemUnsupported, stage, at(location))
}
func NewCrossDevice(stage Stage, location record.Location) Failure {
	return build(CrossDevice, stage, at(location))
}
func NewRootForeign(stage Stage, location record.Location) Failure {
	return build(RootForeign, stage, at(location))
}
func NewLegacyLayout(stage Stage, location record.Location) Failure {
	return build(LegacyLayout, stage, at(location))
}
func NewLayoutDamaged(stage Stage, location record.Location) Failure {
	return build(LayoutDamaged, stage, at(location))
}
func NewPublicCommandForeign(stage Stage, location record.Location) Failure {
	return build(PublicCommandForeign, stage, at(location))
}
func NewWorkspaceStoreOccupied(stage Stage, location record.Location) Failure {
	return build(WorkspaceStoreOccupied, stage, at(location))
}
func NewLeftoverForeign(stage Stage, location record.Location) Failure {
	return build(LeftoverForeign, stage, at(location))
}
func NewLockFileRejected(stage Stage, location record.Location) Failure {
	return build(LockFileRejected, stage, at(location))
}
func NewLockHeld(stage Stage, lockRange LockRange, mode LockMode, holder record.ProcessID) Failure {
	return build(LockHeld, stage, HolderEvidence{lockRange, mode, holder})
}
func NewProcessStillAlive(stage Stage, holder record.ProcessID, use ProcessUse) Failure {
	return build(ProcessStillAlive, stage, ProcessEvidence{holder, use})
}
func NewRetirementRejected(stage Stage, exit ExitCode, reason record.RetirementReason) Failure {
	return build(RetirementRejected, stage, ChildEvidence{exit, reason})
}
func NewRetirementTimedOut(stage Stage, exit ExitCode, reason record.RetirementReason) Failure {
	return build(RetirementTimedOut, stage, ChildEvidence{exit, reason})
}
func NewRemovalBlocked(stage Stage, exit ExitCode, reason record.RetirementReason) Failure {
	return build(RemovalBlocked, stage, ChildEvidence{exit, reason})
}
func NewSupervisorStillActive(stage Stage, supervisor SupervisorKind, label record.ServiceLabel,
	state UnitState) Failure {
	return build(SupervisorStillActive, stage, SupervisorEvidence{supervisor, label, state})
}
func NewFilesystemRejected(stage Stage, location record.Location, operation FsOperation, cause FsError) Failure {
	return build(FilesystemRejected, stage,
		FilesystemEvidence{location.Root, location.Path, operation, cause.cause, cause.errno})
}
