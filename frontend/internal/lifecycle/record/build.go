package record

import (
	"encoding/json"
	"slices"

	"github.com/amichne/kast/frontend/internal/lifecycle/codec"
)

// The build record is the release's own bytes, decoded strictly by the release's binary and never re-encoded. Its
// fields and value rules are the existing product record's (packaging/internal/installation/managed_release.py,
// parse_installed_product_record), with one strictness: the optional members runtimeBase and composition must be
// present, as the publisher always writes them, and may be an explicit null.

const (
	buildRepository         = "amichne/kast"
	buildWorkflow           = ".github/workflows/ci.yml"
	qualificationWorkflow   = ".github/workflows/composed-linux.yml"
	maximumAssetBytes       = int64(2) << 30
	localBuildRecordSchema  = 4
	macosBuildRecordSchema  = 5
	baseBuildRecordSchema   = 3
	legacyBuildRecordSchema = 2
)

// Asset is one release asset named by a record.
type Asset struct {
	name   AssetName
	sha256 Digest
	bytes  int64
}

// Name is the asset file name.
func (a Asset) Name() AssetName { return a.name }

// SHA256 is the asset digest.
func (a Asset) SHA256() Digest { return a.sha256 }

// Bytes is the asset size.
func (a Asset) Bytes() int64 { return a.bytes }

// Producer is the CI run that produced a build, or a local build.
type Producer struct{ local bool }

// Local reports a local build.
func (p Producer) Local() bool { return p.local }

// BuildTarget is one target of a build record.
type BuildTarget struct {
	target        Target
	bundle        Asset
	runtime       Asset
	receipt       Asset
	producer      Producer
	controlSHA256 Digest
	base          *Asset
}

// Target is the release target.
func (b BuildTarget) Target() Target { return b.target }

// Bundle is the composed setup bundle asset.
func (b BuildTarget) Bundle() Asset { return b.bundle }

// Runtime is the runtime archive asset; its digest names the runtime entry.
func (b BuildTarget) Runtime() Asset { return b.runtime }

// Receipt is the qualification receipt asset.
func (b BuildTarget) Receipt() Asset { return b.receipt }

// ControlSHA256 is the qualified digest of the target's Control executable.
func (b BuildTarget) ControlSHA256() Digest { return b.controlSHA256 }

// Base is the runtime base asset, if the build has one.
func (b BuildTarget) Base() (Asset, bool) {
	if b.base == nil {
		return Asset{}, false
	}
	return *b.base, true
}

// BuildRecord is an admitted build-record.json with its exact bytes.
type BuildRecord struct {
	schema         int64
	raw            []byte
	digest         Digest
	sourceRevision Revision
	sourceTree     Revision
	version        DeveloperVersion
	producer       Producer
	targets        []BuildTarget
}

// Bytes are the record's exact bytes.
func (r BuildRecord) Bytes() []byte { return slices.Clone(r.raw) }

// Digest is the SHA-256 digest of the record's bytes.
func (r BuildRecord) Digest() Digest { return r.digest }

// SchemaVersion is the record's schema version.
func (r BuildRecord) SchemaVersion() int64 { return r.schema }

// SourceRevision is the build's source revision.
func (r BuildRecord) SourceRevision() Revision { return r.sourceRevision }

// Version is the build version.
func (r BuildRecord) Version() DeveloperVersion { return r.version }

// Local reports a local build record.
func (r BuildRecord) Local() bool { return r.producer.local }

// Target returns one target's facts.
func (r BuildRecord) Target(target Target) (BuildTarget, bool) {
	for _, candidate := range r.targets {
		if candidate.target == target {
			return candidate, true
		}
	}
	return BuildTarget{}, false
}

// DecodeBuildRecord strictly decodes build-record.json and keeps its bytes.
func DecodeBuildRecord(raw []byte) (BuildRecord, error) {
	object, err := codec.Document(raw, MaximumRecordBytes)
	if err != nil {
		return BuildRecord{}, err
	}
	schema, err := object.SchemaVersion(legacyBuildRecordSchema, baseBuildRecordSchema, localBuildRecordSchema,
		macosBuildRecordSchema)
	if err != nil {
		return BuildRecord{}, err
	}
	repository, err := object.String("repository")
	if err != nil {
		return BuildRecord{}, err
	}
	if repository != buildRepository {
		return BuildRecord{}, codec.Reject(codec.InvalidValue, "repository")
	}
	revision, err := codec.Text(object, "sourceRevision", ParseRevision)
	if err != nil {
		return BuildRecord{}, err
	}
	tree, err := codec.Text(object, "sourceTree", ParseRevision)
	if err != nil {
		return BuildRecord{}, err
	}
	version, err := codec.Text(object, "version", ParseDeveloperVersion)
	if err != nil {
		return BuildRecord{}, err
	}
	producerObject, err := object.Object("producer")
	if err != nil {
		return BuildRecord{}, err
	}
	producer, err := decodeProducer(producerObject, buildWorkflow, schema == localBuildRecordSchema)
	if err != nil {
		return BuildRecord{}, err
	}
	targetValues, err := object.Array("targets")
	if err != nil {
		return BuildRecord{}, err
	}
	expected := 2
	if schema == localBuildRecordSchema || schema == macosBuildRecordSchema {
		expected = 1
	}
	if len(targetValues) != expected {
		return BuildRecord{}, codec.Reject(codec.InvalidValue, "targets")
	}
	targets := make([]BuildTarget, 0, len(targetValues))
	for i, value := range targetValues {
		target, err := decodeBuildTarget(value, codec.Element("targets", i), version)
		if err != nil {
			return BuildRecord{}, err
		}
		targets = append(targets, target)
	}
	if err := object.Closed(); err != nil {
		return BuildRecord{}, err
	}
	if err := admitBuildTargets(schema, producer, targets); err != nil {
		return BuildRecord{}, err
	}
	return BuildRecord{schema, slices.Clone(raw), DigestOf(raw), revision, tree, version, producer, targets}, nil
}

func admitBuildTargets(schema int64, producer Producer, targets []BuildTarget) error {
	for _, target := range targets {
		if target.producer.local != producer.local {
			return codec.Reject(codec.InvalidValue, "targets")
		}
	}
	if producer.local {
		return nil
	}
	required := []Target{TargetLinuxX8664, TargetMacOSAArch64}
	if schema == macosBuildRecordSchema {
		required = []Target{TargetMacOSAArch64}
	}
	for i, target := range targets {
		withBase := target.base != nil
		if target.target != required[i] ||
			withBase != (schema == baseBuildRecordSchema || schema == macosBuildRecordSchema) {
			return codec.Reject(codec.InvalidValue, codec.Element("targets", i))
		}
	}
	return nil
}

func decodeProducer(object codec.Object, workflow string, localAllowed bool) (Producer, error) {
	if presence, raw := object.Lookup("kind"); presence != codec.Absent {
		kind, err := codec.StringValue(raw, object.Member("kind"))
		if err != nil || kind != "LOCAL" || !localAllowed {
			return Producer{}, codec.Reject(codec.InvalidValue, object.Member("kind"))
		}
		return Producer{local: true}, object.Closed()
	}
	run, err := object.Int("runId")
	if err != nil {
		return Producer{}, err
	}
	attempt, err := object.Int("runAttempt")
	if err != nil {
		return Producer{}, err
	}
	named, err := object.String("workflow")
	if err != nil {
		return Producer{}, err
	}
	if run <= 0 || attempt <= 0 || named != workflow {
		return Producer{}, codec.Reject(codec.InvalidValue, object.Member("runId"))
	}
	return Producer{}, object.Closed()
}

// decodeAssetValue reads {name, sha256, bytes}; a non-empty expected name must match exactly.
func decodeAssetValue(asset codec.Object, expected string) (Asset, error) {
	named, err := codec.Text(asset, "name", ParseAssetName)
	if err != nil {
		return Asset{}, err
	}
	digest, err := codec.Text(asset, "sha256", ParseDigest)
	if err != nil {
		return Asset{}, err
	}
	size, err := asset.Int("bytes")
	if err != nil {
		return Asset{}, err
	}
	if (expected != "" && named.text != expected) || size <= 0 || size >= maximumAssetBytes {
		return Asset{}, codec.Reject(codec.InvalidValue, asset.Member("name"))
	}
	return Asset{named, digest, size}, asset.Closed()
}

func expectAsset(object codec.Object, member, expected string) (Asset, error) {
	asset, err := object.Object(member)
	if err != nil {
		return Asset{}, err
	}
	return decodeAssetValue(asset, expected)
}

var inputDigests = []string{"javaReleaseSha256", "dependencyLocksSha256", "compositionRecipeSha256", "donorSha256"}
var compositionDigests = []string{"baseSha256", "nativeRuntimeSha256", "nativeBundleSha256", "runtimeSha256"}

func decodeBuildTarget(raw json.RawMessage, member string, version DeveloperVersion) (BuildTarget, error) {
	object, err := codec.ObjectValue(raw, member)
	if err != nil {
		return BuildTarget{}, err
	}
	target, err := codec.Text(object, "target", ParseTarget)
	if err != nil {
		return BuildTarget{}, err
	}
	inputs, err := object.Object("inputs")
	if err != nil {
		return BuildTarget{}, err
	}
	for _, name := range inputDigests {
		if _, err := codec.Text(inputs, name, ParseDigest); err != nil {
			return BuildTarget{}, err
		}
	}
	if err := inputs.Closed(); err != nil {
		return BuildTarget{}, err
	}
	suffix := "-v" + version.String() + "-" + target.String()
	bundle, err := expectAsset(object, "bundle", "kast-composed-setup"+suffix+".tar.gz")
	if err != nil {
		return BuildTarget{}, err
	}
	runtime, err := expectAsset(object, "runtime", "kast-runtime"+suffix+".zip")
	if err != nil {
		return BuildTarget{}, err
	}
	qualification, err := object.Object("qualification")
	if err != nil {
		return BuildTarget{}, err
	}
	built := BuildTarget{target: target, bundle: bundle, runtime: runtime}
	composition, err := decodeQualification(qualification, &built)
	if err != nil {
		return BuildTarget{}, err
	}
	baseRaw, err := object.Nullable("runtimeBase")
	if err != nil {
		return BuildTarget{}, err
	}
	if (baseRaw == nil) != (composition == nil) {
		return BuildTarget{}, codec.Reject(codec.InvalidValue, object.Member("runtimeBase"))
	}
	if baseRaw != nil {
		baseObject, err := codec.ObjectValue(baseRaw, object.Member("runtimeBase"))
		if err != nil {
			return BuildTarget{}, err
		}
		base, err := decodeAssetValue(baseObject, "")
		if err != nil {
			return BuildTarget{}, err
		}
		if base.name.text != "kast-runtime-base-"+target.String()+"-"+base.sha256.String()+".zip" ||
			composition[0] != base.sha256 || composition[3] != runtime.sha256 {
			return BuildTarget{}, codec.Reject(codec.InvalidValue, object.Member("runtimeBase"))
		}
		built.base = &base
	}
	return built, object.Closed()
}

// decodeQualification fills the qualification facts and returns the composition witnesses, nil for an explicit null.
func decodeQualification(object codec.Object, built *BuildTarget) ([]Digest, error) {
	receipt, err := expectAsset(object, "receipt", "qualification-"+built.target.String()+".json")
	if err != nil {
		return nil, err
	}
	producerObject, err := object.Object("producer")
	if err != nil {
		return nil, err
	}
	producer, err := decodeProducer(producerObject, qualificationWorkflow, true)
	if err != nil {
		return nil, err
	}
	bundleSHA, err := codec.Text(object, "bundleSha256", ParseDigest)
	if err != nil {
		return nil, err
	}
	runtimeSHA, err := codec.Text(object, "runtimeSha256", ParseDigest)
	if err != nil {
		return nil, err
	}
	control, err := codec.Text(object, "controlSha256", ParseDigest)
	if err != nil {
		return nil, err
	}
	kind, err := object.String("kind")
	if err != nil {
		return nil, err
	}
	outcome, err := object.String("outcome")
	if err != nil {
		return nil, err
	}
	if bundleSHA != built.bundle.sha256 || runtimeSHA != built.runtime.sha256 || kind != "NATIVE" ||
		outcome != "COMPLETE" {
		return nil, codec.Reject(codec.InvalidValue, object.Member("kind"))
	}
	compositionRaw, err := object.Nullable("composition")
	if err != nil {
		return nil, err
	}
	var witnesses []Digest
	if compositionRaw != nil {
		composition, err := codec.ObjectValue(compositionRaw, object.Member("composition"))
		if err != nil {
			return nil, err
		}
		for _, name := range compositionDigests {
			digest, err := codec.Text(composition, name, ParseDigest)
			if err != nil {
				return nil, err
			}
			witnesses = append(witnesses, digest)
		}
		if err := composition.Closed(); err != nil {
			return nil, err
		}
	}
	built.receipt, built.producer, built.controlSHA256 = receipt, producer, control
	return witnesses, object.Closed()
}
