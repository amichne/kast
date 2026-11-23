// Package envelope defines the two documents that cross from a selecting binary to the release it installs: the
// frozen selection envelope, the only release metadata a selecting binary decodes, and the versioned handoff
// document. The envelope decoder is deliberately tolerant of unknown members, so a newer envelope never breaks an
// older binary; the handoff decoder is strict. The package performs no effects.
package envelope

import (
	"slices"

	"github.com/amichne/kast/frontend/internal/lifecycle/codec"
	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

// EnvelopeSchemaVersion is the frozen major version of the selection envelope.
const EnvelopeSchemaVersion = 1

// SupportedLayout is the installation layout this design defines.
const SupportedLayout = 2

const maximumControlBytes = int64(2) << 30

// Control is one target's standalone Control executable: its qualified digest and size.
type Control struct {
	sha256 record.Digest
	bytes  int64
}

// SHA256 is the executable's digest, equal to the record's qualification.controlSha256.
func (c Control) SHA256() record.Digest { return c.sha256 }

// Bytes is the executable's size.
func (c Control) Bytes() int64 { return c.bytes }

// SelectionEnvelope is lifecycle-envelope.json, or one entry of lifecycle-catalog.json.
type SelectionEnvelope struct {
	tag      record.ReleaseTag
	revision record.Revision
	record   record.Digest
	layout   int64
	handoff  []int64
	controls map[record.Target]Control
}

// ReleaseTag is the release the envelope describes.
func (e SelectionEnvelope) ReleaseTag() record.ReleaseTag { return e.tag }

// SourceRevision is the build's source revision.
func (e SelectionEnvelope) SourceRevision() record.Revision { return e.revision }

// RecordSHA256 is the digest of the release's build-record.json.
func (e SelectionEnvelope) RecordSHA256() record.Digest { return e.record }

// Layout is the installation layout the release's binary performs.
func (e SelectionEnvelope) Layout() int64 { return e.layout }

// Handoff lists the handoff schema versions the release's binary accepts, in envelope order.
func (e SelectionEnvelope) Handoff() []int64 { return slices.Clone(e.handoff) }

// Control returns the Control executable of one target.
func (e SelectionEnvelope) Control(target record.Target) (Control, bool) {
	control, ok := e.controls[target]
	return control, ok
}

// DecodeSelectionEnvelope decodes one envelope document. It still requires exactly one object with unique members
// and no trailing data, and it reads schemaVersion first; it ignores unknown members, and unknown targets in
// controls, at every level.
func DecodeSelectionEnvelope(raw []byte) (SelectionEnvelope, error) {
	object, err := codec.Document(raw, record.MaximumRecordBytes)
	if err != nil {
		return SelectionEnvelope{}, err
	}
	return DecodeSelectionEnvelopeObject(object)
}

// DecodeSelectionEnvelopeObject decodes an envelope already admitted as an object, such as a catalog entry.
func DecodeSelectionEnvelopeObject(object codec.Object) (SelectionEnvelope, error) {
	if _, err := object.SchemaVersion(EnvelopeSchemaVersion); err != nil {
		return SelectionEnvelope{}, err
	}
	tag, err := codec.Text(object, "releaseTag", record.ParseReleaseTag)
	if err != nil {
		return SelectionEnvelope{}, err
	}
	revision, err := codec.Text(object, "sourceRevision", record.ParseRevision)
	if err != nil {
		return SelectionEnvelope{}, err
	}
	digest, err := codec.Text(object, "recordSha256", record.ParseDigest)
	if err != nil {
		return SelectionEnvelope{}, err
	}
	layout, err := object.Int("layout")
	if err != nil {
		return SelectionEnvelope{}, err
	}
	if layout <= 0 {
		return SelectionEnvelope{}, codec.Reject(codec.InvalidValue, "layout")
	}
	handoffValues, err := object.Array("handoff")
	if err != nil {
		return SelectionEnvelope{}, err
	}
	handoff := make([]int64, 0, len(handoffValues))
	for i, value := range handoffValues {
		member := codec.Element("handoff", i)
		version, err := codec.IntValue(value, member)
		if err != nil {
			return SelectionEnvelope{}, err
		}
		if version <= 0 || slices.Contains(handoff, version) {
			return SelectionEnvelope{}, codec.Reject(codec.InvalidValue, member)
		}
		handoff = append(handoff, version)
	}
	controlsObject, err := object.Object("controls")
	if err != nil {
		return SelectionEnvelope{}, err
	}
	controls := map[record.Target]Control{}
	for _, name := range controlsObject.Members() {
		target, known := record.ParseTarget(name)
		if !known {
			continue
		}
		entry, err := controlsObject.Object(name)
		if err != nil {
			return SelectionEnvelope{}, err
		}
		sha, err := codec.Text(entry, "sha256", record.ParseDigest)
		if err != nil {
			return SelectionEnvelope{}, err
		}
		size, err := entry.Int("bytes")
		if err != nil {
			return SelectionEnvelope{}, err
		}
		if size <= 0 || size >= maximumControlBytes {
			return SelectionEnvelope{}, codec.Reject(codec.InvalidValue, entry.Member("bytes"))
		}
		controls[target] = Control{sha, size}
	}
	return SelectionEnvelope{tag, revision, digest, layout, handoff, controls}, nil
}
