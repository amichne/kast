package install

import (
	"fmt"

	"github.com/amichne/kast/frontend/internal/reset"
)

// Failure is the closed set of reasons an invocation does not finish.
type Failure string

const (
	UsageRejected       Failure = "USAGE_REJECTED"
	PlatformUnsupported Failure = "PLATFORM_UNSUPPORTED"
	SourceUnavailable   Failure = "SOURCE_UNAVAILABLE"
	BuildNotFound       Failure = "BUILD_NOT_FOUND"
	CommitAmbiguous     Failure = "COMMIT_AMBIGUOUS"
	ReleaseUnsupported  Failure = "RELEASE_UNSUPPORTED"
	RecordInvalid       Failure = "RECORD_INVALID"
	DownloadInvalid     Failure = "DOWNLOAD_INVALID"
	DownloadBusy        Failure = "DOWNLOAD_BUSY"
	StorageRejected     Failure = "STORAGE_REJECTED"
	Interrupted         Failure = "INTERRUPTED"

	NotInstalled          Failure = "NOT_INSTALLED"
	InstallationDamaged   Failure = "INSTALLATION_DAMAGED"
	PinnedBuild           Failure = "PINNED_BUILD"
	LocalArtifactRequired Failure = "LOCAL_ARTIFACT_REQUIRED"
	ActivationRejected    Failure = "ACTIVATION_REJECTED"
	RecoveryRequired      Failure = "RECOVERY_REQUIRED"
)

// next is the one action each failure offers the reader.
var next = map[Failure]string{
	UsageRejected:       "Run `kast install --dry-run` to see the latest stable build.",
	PlatformUnsupported: "Use a Mac with Apple silicon, or Linux on x86-64.",
	SourceUnavailable:   "Check the network connection, then run the same command again. Completed bytes are kept.",
	BuildNotFound:       "Run `kast versions` to list the builds you can select.",
	CommitAmbiguous:     "Pass the full 40-character revision.",
	ReleaseUnsupported:  "Select `--channel preview`.",
	RecordInvalid:       "Run the same command again. If it repeats, the published build is damaged; select another one.",
	DownloadInvalid:     "Run the same command again. The invalid file was removed.",
	DownloadBusy:        "Wait for the other download to end, then run the same command again.",
	StorageRejected:     "Free space or repair the named path, then run the same command again.",
	Interrupted:         "Run the same command again to continue.",

	NotInstalled:          "To install it:\n  " + reset.InstallCommand,
	InstallationDamaged:   "Run `kast reset` to see what it would remove, then install again.",
	PinnedBuild:           "Select `--channel stable` or `--channel preview` to follow a channel again.",
	LocalArtifactRequired: "Install from the local directory again, or select a published build.",
	ActivationRejected:    "Run the same command again. If it repeats, run `kast reset` to see what it would remove.",
	RecoveryRequired: "Run `kast upgrade` again to finish or undo it. If it repeats, run `kast reset` to see what it would " +
		"remove.",
}

// Rejection is one failure, what it concerns, and the next action.
type Rejection struct {
	Failure Failure
	Detail  string
	Next    string
	// reported means that standard output already holds the failure's document.
	reported bool
}

func (r *Rejection) Error() string { return string(r.Failure) + ": " + r.Detail }

func reject(failure Failure, format string, values ...any) error {
	return &Rejection{Failure: failure, Detail: fmt.Sprintf(format, values...), Next: next[failure]}
}
