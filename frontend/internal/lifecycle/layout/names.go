// Package layout classifies one observed snapshot of the home paths into the closed set of layout states, with the
// fixed precedence Legacy, Foreign, Residual, Damaged, Current, Incomplete, Absent. The snapshot is a typed value
// that an effect adapter produces; this package reads nothing itself.
package layout

import (
	"strings"

	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

// Kast-owned names at the S level, and their prefixes.
const (
	RootName            = "kast"
	LockName            = "kast.lock"
	WorkspaceStoreName  = "kast-workspaces"
	BaseStoreName       = "kast-runtime-bases"
	LifetimeLockName    = ".kast-workspaces.lifetime.lock"
	RemovingPrefix      = ".kast-removing-"
	WitnessPrefix       = ".kast-uninstalled-"
	legacyUninstall     = ".kast-uninstall-"
	legacyReset         = ".kast-reset-"
	legacyInstall       = ".kast-install-"
	legacyResetLockTail = ".json.lock"
)

// Kast-named entries of R.
const (
	CurrentName     = "current"
	CurrentNextName = "current.next"
	VersionsName    = "versions"
	ConfigName      = "config"
	StateName       = "state"
	StagingPrefix   = ".staging-"
)

// Names inside the stores and the public command directory.
const (
	RuntimesName           = "runtimes"
	ContainersName         = "containers"
	RuntimeReservation     = ".kast-runtime-"
	BaseReservation        = ".kast-base-"
	PublicCommandName      = "kast"
	PublicCommandNextName  = "kast.next-"
	TmpAliasPrefix         = "kast-uds-"
	PublicCommandTargetTip = "kast/current/share/kast/libexec/kast-management"
)

// legacyRootNames are the six legacy markers inside R.
var legacyRootNames = []string{"installation", "management.json", "recovery", "shutdown.json", "management.lock",
	"activation.lock"}

// ShareKind is the closed treatment of one S-level name (section 2.1).
type ShareKind uint8

const (
	ShareOther ShareKind = iota + 1
	ShareRoot
	ShareLock
	ShareWorkspaceStore
	ShareBaseStore
	ShareLifetimeLock
	ShareRemoving
	ShareWitness
	ShareLegacyUninstallJournal
	ShareLegacyResetFence
	ShareLegacyInstallStage
	ShareLegacyResetLock
)

// Legacy reports a legacy marker.
func (k ShareKind) Legacy() bool {
	return k == ShareLegacyUninstallJournal || k == ShareLegacyResetFence || k == ShareLegacyInstallStage
}

// ShareName is an S-level name with its treatment and, for the Go-named trees, its identifier.
type ShareName struct {
	Kind ShareKind
	ID   record.VersionID
}

// ClassifyShareName names the treatment of one S-level entry.
func ClassifyShareName(name string) ShareName {
	switch name {
	case RootName:
		return ShareName{Kind: ShareRoot}
	case LockName:
		return ShareName{Kind: ShareLock}
	case WorkspaceStoreName:
		return ShareName{Kind: ShareWorkspaceStore}
	case BaseStoreName:
		return ShareName{Kind: ShareBaseStore}
	case LifetimeLockName:
		return ShareName{Kind: ShareLifetimeLock}
	}
	if id, ok := identified(name, RemovingPrefix); ok {
		return ShareName{ShareRemoving, id}
	}
	if id, ok := identified(name, WitnessPrefix); ok {
		return ShareName{ShareWitness, id}
	}
	if hexed, ok := strings.CutPrefix(name, legacyUninstall); ok && hexSuffix(hexed, ".json", 64) {
		return ShareName{Kind: ShareLegacyUninstallJournal}
	}
	if hexed, ok := strings.CutPrefix(name, legacyReset); ok {
		if hexSuffix(hexed, ".json", 32) {
			return ShareName{Kind: ShareLegacyResetFence}
		}
		if hexSuffix(hexed, legacyResetLockTail, 32) {
			return ShareName{Kind: ShareLegacyResetLock}
		}
	}
	if token, ok := strings.CutPrefix(name, legacyInstall); ok && uuid(token) {
		return ShareName{Kind: ShareLegacyInstallStage}
	}
	return ShareName{Kind: ShareOther}
}

// RootEntryKind is the closed treatment of one entry of R.
type RootEntryKind uint8

const (
	RootForeignName RootEntryKind = iota + 1
	RootCurrent
	RootCurrentNext
	RootVersions
	RootConfig
	RootState
	RootStaging
	RootLegacy
)

// RootEntryName is an entry of R with its treatment and, for a staging tree, its identifier.
type RootEntryName struct {
	Kind RootEntryKind
	ID   record.VersionID
}

// ClassifyRootName names the treatment of one entry of R.
func ClassifyRootName(name string) RootEntryName {
	switch name {
	case CurrentName:
		return RootEntryName{Kind: RootCurrent}
	case CurrentNextName:
		return RootEntryName{Kind: RootCurrentNext}
	case VersionsName:
		return RootEntryName{Kind: RootVersions}
	case ConfigName:
		return RootEntryName{Kind: RootConfig}
	case StateName:
		return RootEntryName{Kind: RootState}
	}
	for _, legacy := range legacyRootNames {
		if name == legacy {
			return RootEntryName{Kind: RootLegacy}
		}
	}
	if id, ok := identified(name, StagingPrefix); ok {
		return RootEntryName{RootStaging, id}
	}
	return RootEntryName{Kind: RootForeignName}
}

// Identified parses prefix + 16 lowercase hexadecimal characters.
func Identified(name, prefix string) (record.VersionID, bool) { return identified(name, prefix) }

func identified(name, prefix string) (record.VersionID, bool) {
	rest, ok := strings.CutPrefix(name, prefix)
	if !ok {
		return record.VersionID{}, false
	}
	return record.ParseVersionID(rest)
}

func hexSuffix(text, suffix string, length int) bool {
	hexed, ok := strings.CutSuffix(text, suffix)
	return ok && lowerHex(hexed, length)
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

// uuid admits the canonical lowercase 8-4-4-4-12 form that the legacy installer wrote.
func uuid(text string) bool {
	parts := strings.Split(text, "-")
	if len(parts) != 5 {
		return false
	}
	for i, length := range []int{8, 4, 4, 4, 12} {
		if !lowerHex(parts[i], length) {
			return false
		}
	}
	return true
}

// Prefixed names a Go-created tree or link: prefix plus the identifier.
func Prefixed(prefix string, id record.VersionID) string { return prefix + id.String() }
