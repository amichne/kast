// Package selection resolves the user's selector over selection envelopes: exactly one valid selector, the envelope
// it names, the release layout, the host target's Control executable, and a handoff schema both binaries speak.
// It performs no effects; the interpreter fetches what Source names.
package selection

import (
	"slices"
	"strings"

	"github.com/amichne/kast/frontend/internal/lifecycle/envelope"
	"github.com/amichne/kast/frontend/internal/lifecycle/failure"
	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

// Flags are the selector options as given: nil is an absent option, which differs from an empty value.
type Flags struct {
	Channel *string
	Version *string
	Commit  *string
}

// Selector is the sealed, validated selector.
type Selector interface{ selector() }

// StableChannel follows the newest stable release; it is also the default.
type StableChannel struct{}

// PreviewChannel follows the newest preview build.
type PreviewChannel struct{}

// PinnedVersion pins one stable release.
type PinnedVersion struct{ Version record.SemVer }

// PinnedCommit pins the preview build whose source revision starts with the prefix: 12 or 40 lowercase hexadecimal
// characters.
type PinnedCommit struct{ Prefix string }

func (StableChannel) selector()  {}
func (PreviewChannel) selector() {}
func (PinnedVersion) selector()  {}
func (PinnedCommit) selector()   {}

// Parse admits exactly one valid selector, or none for the stable default.
func Parse(flags Flags) (Selector, *failure.Failure) {
	stage := failure.StageSelection
	reject := func(rejected failure.Failure) (Selector, *failure.Failure) { return nil, &rejected }
	given := 0
	for _, value := range []*string{flags.Channel, flags.Version, flags.Commit} {
		if value != nil {
			given++
		}
	}
	switch {
	case given > 1:
		return reject(failure.NewSelectorsConflict(stage))
	case flags.Version != nil:
		version, ok := record.ParseSemVer(*flags.Version)
		if !ok {
			return reject(failure.NewSelectorInvalid(stage))
		}
		return PinnedVersion{version}, nil
	case flags.Commit != nil:
		prefix := *flags.Commit
		if (len(prefix) != 12 && len(prefix) != 40) || strings.Trim(prefix, "0123456789abcdef") != "" {
			return reject(failure.NewSelectorInvalid(stage))
		}
		return PinnedCommit{prefix}, nil
	case flags.Channel != nil:
		switch *flags.Channel {
		case "stable":
			return StableChannel{}, nil
		case "preview":
			return PreviewChannel{}, nil
		}
		return reject(failure.NewSelectorInvalid(stage))
	default:
		return StableChannel{}, nil
	}
}

// Source is what the interpreter must fetch for a selector: the stable release's envelope asset, or the preview
// envelope catalog. Stable without a tag means the newest stable release.
type Source struct {
	Catalog bool
	Tag     *record.ReleaseTag
}

// SourceOf names what a selector needs.
func SourceOf(selector Selector) Source {
	switch chosen := selector.(type) {
	case PinnedVersion:
		tag := record.StableTag(chosen.Version)
		return Source{Tag: &tag}
	case StableChannel:
		return Source{}
	default:
		return Source{Catalog: true}
	}
}

// Candidates are the fetched envelopes. A stable source has the tag the release names and its envelope, nil when
// the release has none; a catalog source has the catalog's entries, newest first.
type Candidates struct {
	Tag      record.ReleaseTag
	Envelope *envelope.SelectionEnvelope
	Catalog  []envelope.SelectionEnvelope
}

// Resolved is a selection ready for the handoff: the envelope, its identity, the Control executable for the host,
// and the handoff schema both binaries speak.
type Resolved struct {
	Envelope  envelope.SelectionEnvelope
	Selection envelope.Selection
	Control   envelope.Control
	Handoff   int64
}

// HostTarget maps a Go platform to a release target (PLATFORM_UNSUPPORTED otherwise).
func HostTarget(goos, goarch string) (record.Target, *failure.Failure) {
	switch {
	case goos == "darwin" && goarch == "arm64":
		return record.TargetMacOSAArch64, nil
	case goos == "linux" && goarch == "amd64":
		return record.TargetLinuxX8664, nil
	}
	rejected := failure.NewPlatformUnsupported(failure.StageSelection)
	return 0, &rejected
}

// Resolve picks the envelope a selector names, then decides RELEASE_LAYOUT_UNSUPPORTED before anything runs, finds
// the host's Control executable, and chooses the highest handoff schema in common (HANDOFF_UNSUPPORTED otherwise).
func Resolve(selector Selector, candidates Candidates, host record.Target, handoffs []int64) (Resolved,
	*failure.Failure) {
	stage := failure.StageSelection
	reject := func(rejected failure.Failure) (Resolved, *failure.Failure) { return Resolved{}, &rejected }
	var chosen envelope.SelectionEnvelope
	var policy record.Policy
	switch picked := selector.(type) {
	case StableChannel, PinnedVersion:
		if _, stable := candidates.Tag.Stable(); !stable {
			return reject(failure.NewCatalogInvalid(stage))
		}
		if version, pinned := picked.(PinnedVersion); pinned && candidates.Tag != record.StableTag(version.Version) {
			return reject(failure.NewCatalogInvalid(stage))
		}
		if candidates.Envelope == nil {
			return reject(failure.NewReleaseLayoutUnsupported(stage))
		}
		if candidates.Envelope.ReleaseTag() != candidates.Tag {
			return reject(failure.NewCatalogInvalid(stage))
		}
		chosen, policy = *candidates.Envelope, record.PolicyTrackStable
		if _, pinned := picked.(PinnedVersion); pinned {
			policy = record.PolicyPinnedBuild
		}
	case PreviewChannel, PinnedCommit:
		var revisions []string
		for _, entry := range candidates.Catalog {
			if _, preview := entry.ReleaseTag().Developer(); !preview ||
				slices.Contains(revisions, entry.SourceRevision().String()) {
				return reject(failure.NewCatalogInvalid(stage))
			}
			revisions = append(revisions, entry.SourceRevision().String())
		}
		commit, pinned := picked.(PinnedCommit)
		var matches []envelope.SelectionEnvelope
		for _, entry := range candidates.Catalog {
			if !pinned || strings.HasPrefix(entry.SourceRevision().String(), commit.Prefix) {
				matches = append(matches, entry)
			}
		}
		switch {
		case len(matches) == 0:
			return reject(failure.NewBuildUnavailable(stage))
		case pinned && len(matches) > 1:
			return reject(failure.NewCommitAmbiguous(stage))
		}
		chosen, policy = matches[0], record.PolicyTrackMain
		if pinned {
			policy = record.PolicyPinnedBuild
		}
	default:
		return reject(failure.NewSelectorInvalid(stage))
	}
	if chosen.Layout() != envelope.SupportedLayout {
		return reject(failure.NewReleaseLayoutUnsupported(stage))
	}
	control, ok := chosen.Control(host)
	if !ok {
		return reject(failure.NewBuildUnavailable(stage))
	}
	identity, ok := record.NewIdentity(chosen.ReleaseTag(), policy, chosen.ReleaseTag().PublicVersionOf())
	if !ok {
		return reject(failure.NewCatalogInvalid(stage))
	}
	best := int64(0)
	for _, version := range chosen.Handoff() {
		if slices.Contains(handoffs, version) && version > best {
			best = version
		}
	}
	if best == 0 {
		return reject(failure.NewHandoffUnsupported(failure.StageHandoff))
	}
	return Resolved{chosen, envelope.NewSelection(identity, chosen.SourceRevision()), control, best}, nil
}
