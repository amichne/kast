package failure

import (
	"strconv"

	"github.com/amichne/kast/frontend/internal/lifecycle/codec"
)

// names is the closed spelling table shared by every enumeration of this package.
type names[E comparable] map[E]string

func (n names[E]) parse(text string) (E, bool) {
	for value, name := range n {
		if name == text {
			return value, true
		}
	}
	var zero E
	return zero, false
}

func (n names[E]) marshal(value E) ([]byte, error) {
	name, ok := n[value]
	if !ok {
		return nil, codec.Reject(codec.InvalidValue, "")
	}
	return []byte(name), nil
}

// Stage is the closed set of lifecycle stages a failure occurs in.
type Stage uint8

const (
	StagePrecheck Stage = iota + 1
	StagePrelude
	StageSelection
	StageHandoff
	StageDownload
	StageVerify
	StageStage
	StagePublish
	StageRetire
	StageSwitch
	StageCleanup
	StageRemove
)

var stageNames = names[Stage]{
	StagePrecheck: "PRECHECK", StagePrelude: "PRELUDE", StageSelection: "SELECTION", StageHandoff: "HANDOFF",
	StageDownload: "DOWNLOAD", StageVerify: "VERIFY", StageStage: "STAGE", StagePublish: "PUBLISH",
	StageRetire: "RETIRE", StageSwitch: "SWITCH", StageCleanup: "CLEANUP", StageRemove: "REMOVE",
}

func (s Stage) String() string               { return stageNames[s] }
func (s Stage) MarshalText() ([]byte, error) { return stageNames.marshal(s) }
func parseStage(text string) (Stage, bool)   { return stageNames.parse(text) }
func (s Stage) valid() bool                  { return stageNames[s] != "" }

// FsOperation is the closed set of file-system primitives an effect performs.
type FsOperation uint8

const (
	OpOpen FsOperation = iota + 1
	OpMkdir
	OpWrite
	OpSync
	OpRename
	OpRenameNoReplace
	OpRenameExchange
	OpSymlink
	OpUnlink
	OpRmdir
	OpChmod
	OpLock
	OpStat
	OpStatfs
	OpExec
)

var operationNames = names[FsOperation]{
	OpOpen: "OPEN", OpMkdir: "MKDIR", OpWrite: "WRITE", OpSync: "SYNC", OpRename: "RENAME",
	OpRenameNoReplace: "RENAME_NO_REPLACE", OpRenameExchange: "RENAME_EXCHANGE", OpSymlink: "SYMLINK",
	OpUnlink: "UNLINK", OpRmdir: "RMDIR", OpChmod: "CHMOD", OpLock: "LOCK", OpStat: "STAT", OpStatfs: "STATFS",
	OpExec: "EXEC",
}

func (o FsOperation) String() string               { return operationNames[o] }
func (o FsOperation) MarshalText() ([]byte, error) { return operationNames.marshal(o) }

// FsCause is the closed set of file-system failure causes. UNMAPPED_ERRNO carries the errno number exactly.
type FsCause uint8

const (
	CausePermission FsCause = iota + 1
	CauseNoSpace
	CauseReadOnly
	CauseExists
	CauseNotFound
	CauseNotEmpty
	CauseNameTooLong
	CauseIO
	CauseUnmappedErrno
)

var fsCauseNames = names[FsCause]{
	CausePermission: "PERMISSION", CauseNoSpace: "NO_SPACE", CauseReadOnly: "READ_ONLY", CauseExists: "EXISTS",
	CauseNotFound: "NOT_FOUND", CauseNotEmpty: "NOT_EMPTY", CauseNameTooLong: "NAME_TOO_LONG", CauseIO: "IO",
	CauseUnmappedErrno: "UNMAPPED_ERRNO",
}

func (c FsCause) String() string               { return fsCauseNames[c] }
func (c FsCause) MarshalText() ([]byte, error) { return fsCauseNames.marshal(c) }

// MaximumErrno bounds an unmapped errno number.
const MaximumErrno = 4095

// FsError is a file-system cause with its errno: zero for a mapped cause, the exact number for UNMAPPED_ERRNO.
type FsError struct {
	cause FsCause
	errno int
}

// Mapped is a mapped cause; it rejects UNMAPPED_ERRNO, which needs its number.
func Mapped(cause FsCause) (FsError, bool) {
	if cause == CauseUnmappedErrno || fsCauseNames[cause] == "" {
		return FsError{}, false
	}
	return FsError{cause, 0}, true
}

// Unmapped carries an errno number from 1 to MaximumErrno.
func Unmapped(errno int) (FsError, bool) {
	if errno < 1 || errno > MaximumErrno {
		return FsError{}, false
	}
	return FsError{CauseUnmappedErrno, errno}, true
}

// Cause is the cause.
func (e FsError) Cause() FsCause { return e.cause }

// Errno is the unmapped errno number, zero for a mapped cause.
func (e FsError) Errno() int { return e.errno }

// TransferCause is the closed set of transfer failure causes.
type TransferCause uint8

const (
	TransferConnection TransferCause = iota + 1
	TransferTLS
	TransferTimeout
	TransferHTTPStatus
	TransferTooLarge
	TransferRedirectRejected
	TransferTruncated
)

var transferNames = names[TransferCause]{
	TransferConnection: "CONNECTION", TransferTLS: "TLS", TransferTimeout: "TIMEOUT",
	TransferHTTPStatus: "HTTP_STATUS", TransferTooLarge: "TOO_LARGE", TransferRedirectRejected: "REDIRECT_REJECTED",
	TransferTruncated: "TRUNCATED",
}

func (c TransferCause) String() string               { return transferNames[c] }
func (c TransferCause) MarshalText() ([]byte, error) { return transferNames.marshal(c) }

// Transfer is a transfer cause with its HTTP status: zero unless the cause is HTTP_STATUS.
type Transfer struct {
	cause  TransferCause
	status int
}

// TransferFailed is a cause other than HTTP_STATUS.
func TransferFailed(cause TransferCause) (Transfer, bool) {
	if cause == TransferHTTPStatus || transferNames[cause] == "" {
		return Transfer{}, false
	}
	return Transfer{cause, 0}, true
}

// TransferStatus is an HTTP status from 100 to 599.
func TransferStatus(status int) (Transfer, bool) {
	if status < 100 || status > 599 {
		return Transfer{}, false
	}
	return Transfer{TransferHTTPStatus, status}, true
}

// Cause is the cause.
func (t Transfer) Cause() TransferCause { return t.cause }

// HTTPStatus is the status, zero unless the cause is HTTP_STATUS.
func (t Transfer) HTTPStatus() int { return t.status }

// LockRange is the closed set of byte ranges of S/kast.lock.
type LockRange uint8

const (
	RangeIntent LockRange = iota + 1
	RangeMain
)

var rangeNames = names[LockRange]{RangeIntent: "INTENT", RangeMain: "MAIN"}

func (r LockRange) String() string               { return rangeNames[r] }
func (r LockRange) MarshalText() ([]byte, error) { return rangeNames.marshal(r) }

// LockMode is the closed set of record-lock modes a holder can have.
type LockMode uint8

const (
	ModeShared LockMode = iota + 1
	ModeExclusive
)

var lockModeNames = names[LockMode]{ModeShared: "SHARED", ModeExclusive: "EXCLUSIVE"}

func (m LockMode) String() string               { return lockModeNames[m] }
func (m LockMode) MarshalText() ([]byte, error) { return lockModeNames.marshal(m) }

// ProcessUse is the closed set of ways a process uses a tree.
type ProcessUse uint8

const (
	UseExecutable ProcessUse = iota + 1
	UseArgument
	UseWorkingDirectory
)

var useNames = names[ProcessUse]{
	UseExecutable: "EXECUTABLE", UseArgument: "ARGUMENT", UseWorkingDirectory: "WORKING_DIRECTORY",
}

func (u ProcessUse) String() string               { return useNames[u] }
func (u ProcessUse) MarshalText() ([]byte, error) { return useNames.marshal(u) }

// SupervisorKind is the closed set of user service supervisors.
type SupervisorKind uint8

const (
	SupervisorSystemd SupervisorKind = iota + 1
	SupervisorLaunchd
)

var supervisorNames = names[SupervisorKind]{SupervisorSystemd: "SYSTEMD", SupervisorLaunchd: "LAUNCHD"}

func (s SupervisorKind) String() string               { return supervisorNames[s] }
func (s SupervisorKind) MarshalText() ([]byte, error) { return supervisorNames.marshal(s) }

// UnitState is the closed set of states a supervisor entry can still be in: the systemd ActiveState values of a
// loaded unit, and LISTED for a launchd job that launchctl still lists.
type UnitState uint8

const (
	StateActive UnitState = iota + 1
	StateReloading
	StateInactive
	StateFailed
	StateActivating
	StateDeactivating
	StateMaintenance
	StateRefreshing
	StateListed
)

var unitStateNames = names[UnitState]{
	StateActive: "ACTIVE", StateReloading: "RELOADING", StateInactive: "INACTIVE", StateFailed: "FAILED",
	StateActivating: "ACTIVATING", StateDeactivating: "DEACTIVATING", StateMaintenance: "MAINTENANCE",
	StateRefreshing: "REFRESHING", StateListed: "LISTED",
}

func (s UnitState) String() string               { return unitStateNames[s] }
func (s UnitState) MarshalText() ([]byte, error) { return unitStateNames.marshal(s) }

// ExitCode is a child's exit status from 0 to 255, or -1 when a signal ended it.
type ExitCode struct{ value int }

// ParseExitCode admits -1 to 255.
func ParseExitCode(value int) (ExitCode, bool) {
	if value < -1 || value > 255 {
		return ExitCode{}, false
	}
	return ExitCode{value}, true
}

// Value is the status.
func (e ExitCode) Value() int { return e.value }

// MarshalJSON writes the status as a number.
func (e ExitCode) MarshalJSON() ([]byte, error) { return []byte(strconv.Itoa(e.value)), nil }
