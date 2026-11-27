package install

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"compress/gzip"
	"encoding/json/v2"
	"io/fs"
	"slices"
	"testing"
)

// testBuild is one build's real archives, small enough for a test, with the facts its record names.
type testBuild struct {
	version, target string
	// files are the Control payload's files and modes, as the archive holds them.
	files map[string]payloadMember
	// runtime lists every composed runtime entry; base names the entries the base archive holds.
	runtime []runtimeMember
	base    map[string]bool
	// mutate changes the archives' members before they are packed, for rejection cases.
	mutate func(*archives)
	// local packs a local build: one runtime archive without a base, and local producers.
	local bool
}

type payloadMember struct {
	data string
	mode fs.FileMode
}

type runtimeMember struct {
	path, data, link string
	executable       bool
}

// archives are the packed members, by archive.
type archives struct {
	runtime, base map[string][]byte
	marker        []byte
}

const prefix = "host/Kast.app/Contents"

func newProduct(version, mark string) *testBuild {
	return &testBuild{
		version: version, target: "macos-aarch64",
		files: map[string]payloadMember{
			"bin/kast":                           {"#!/bin/sh\n# " + mark + "\n", 0o755},
			"bin/kast-query":                     {"#!/bin/sh\n", 0o755},
			"bin/kast-mcp":                       {"#!/bin/sh\n", 0o755},
			"bin/kast-codex":                     {"#!/bin/sh\n", 0o755},
			"lib/kast-cli.jar":                   {"cli " + mark, 0o644},
			"share/kast/libexec/kast-management": {"management " + mark, 0o755},
			"share/kast/a/x":                     {"a", 0o644},
			"share/kast/a-b/x":                   {"a-b", 0o644},
			catalogPath: {`{"control":{"mainClass":"Main","classPath":["lib/kast-cli.jar"],` +
				`"javaHomes":{"macos-aarch64":"` + prefix + `/jbr/Contents/Home"},"sharedArchive":"lib/control.jsa",` +
				`"archiveTraining":["--help"]}}`, 0o644},
		},
		runtime: []runtimeMember{
			{path: prefix + "/lib/base.jar", data: "base"},
			{path: prefix + "/jbr/Contents/Home/bin/java", data: "java", executable: true},
			{path: prefix + "/third-party-plugins/kast-managed-headless/lib/kast-managed-headless.jar", data: "boot " + mark},
			{path: "components/plugins/kast-ide-hosted/lib/hosted.jar", data: "hosted " + mark},
			{path: prefix + "/current", link: "lib"},
		},
		base: map[string]bool{prefix + "/lib/base.jar": true, prefix + "/jbr/Contents/Home/bin/java": true},
	}
}

// assets packs the archives and returns them by published name, with the record's target entry.
func (p *testBuild) assets(t *testing.T) (map[string]any, map[string][]byte) {
	t.Helper()
	var entries []any
	packed := &archives{runtime: map[string][]byte{}, base: map[string][]byte{}}
	var bootstrap string
	for _, member := range p.runtime {
		data := member.data
		entry := map[string]any{"type": "FILE", "path": member.path, "bytes": len(data), "sha256": digest([]byte(data)),
			"executable": member.executable}
		if member.link != "" {
			data = member.link
			entry = map[string]any{"type": "SYMLINK", "path": member.path, "target": member.link}
		}
		if member.path == prefix+"/third-party-plugins/kast-managed-headless/lib/kast-managed-headless.jar" {
			bootstrap = digest([]byte(data))
		}
		entries = append(entries, entry)
		if p.base[member.path] {
			packed.base[member.path] = []byte(data)
		} else {
			packed.runtime[member.path] = []byte(data)
		}
	}
	packed.base[baseManifestName] = []byte(`{"kind":"KAST_RUNTIME_BASE_V1"}`)
	base := packZip(t, packed.base)
	baseAsset := map[string]any{"name": "kast-runtime-base-" + p.target + "-" + digest(base) + ".zip", "sha256": digest(base),
		"bytes": len(base)}
	packed.marker = encodeTest(t, map[string]any{"schemaVersion": 1, "kind": "KAST_RUNTIME_COMPOSITION_V1",
		"target": p.target, "base": baseAsset})
	manifest := map[string]any{"schemaVersion": 1, "kind": "KAST_RUNTIME_V1", "version": p.version, "platform": p.target,
		"platformBuild": "262.1.2", "distributionSha256": digest([]byte("donor")), "bootstrapSha256": bootstrap,
		"files": entries}
	packed.runtime[runtimeManifestName] = encodeTest(t, manifest)
	packed.runtime[runtimeMarkerName] = packed.marker
	if p.mutate != nil {
		p.mutate(packed)
		base = packZip(t, packed.base)
		baseAsset = map[string]any{"name": "kast-runtime-base-" + p.target + "-" + digest(base) + ".zip",
			"sha256": digest(base), "bytes": len(base)}
	}
	if p.local {
		// A local build's runtime archive holds every file and names no base.
		for name, data := range packed.base {
			if name != baseManifestName {
				packed.runtime[name] = data
			}
		}
		delete(packed.runtime, runtimeMarkerName)
	}
	runtime := packZip(t, packed.runtime)
	control := packTar(t, p.files)
	bundle := packTar(t, map[string]payloadMember{
		"setup-kast/manifest.json": {"{}", 0o644},
		"setup-kast/assets/kast-control-v" + p.version + "-" + p.target + ".tar.gz": {string(control), 0o644},
	})
	receipt := []byte(`{"qualification":"COMPLETE"}`)
	asset := func(name string, raw []byte) map[string]any {
		return map[string]any{"name": name, "sha256": digest(raw), "bytes": len(raw)}
	}
	other := digest([]byte("other"))
	document := map[string]any{
		"target": p.target,
		"inputs": map[string]any{"javaReleaseSha256": other, "dependencyLocksSha256": other,
			"compositionRecipeSha256": other, "donorSha256": other},
		"bundle":  asset("kast-composed-setup-v"+p.version+"-"+p.target+".tar.gz", bundle),
		"runtime": asset("kast-runtime-v"+p.version+"-"+p.target+".zip", runtime),
		"qualification": map[string]any{
			"receipt":      asset("qualification-"+p.target+".json", receipt),
			"producer":     map[string]any{"runId": 1, "runAttempt": 1, "workflow": ".github/workflows/composed-linux.yml"},
			"bundleSha256": digest(bundle), "runtimeSha256": digest(runtime),
			"controlSha256": digest([]byte(p.files[managementPath].data)), "kind": "NATIVE", "outcome": "COMPLETE",
			"composition": map[string]any{"baseSha256": digest(base), "nativeRuntimeSha256": other,
				"nativeBundleSha256": other, "runtimeSha256": digest(runtime)},
		},
		"runtimeBase": baseAsset,
	}
	if p.local {
		delete(document, "runtimeBase")
		qualification := document["qualification"].(map[string]any)
		delete(qualification, "composition")
		qualification["producer"] = map[string]any{"kind": "LOCAL"}
		return document, map[string][]byte{
			document["bundle"].(map[string]any)["name"].(string):  bundle,
			document["runtime"].(map[string]any)["name"].(string): runtime,
			"qualification-" + p.target + ".json":                 receipt,
		}
	}
	return document, map[string][]byte{
		document["bundle"].(map[string]any)["name"].(string):  bundle,
		document["runtime"].(map[string]any)["name"].(string): runtime,
		"qualification-" + p.target + ".json":                 receipt,
		baseAsset["name"].(string):                            base,
	}
}

func packZip(t *testing.T, members map[string][]byte) []byte {
	t.Helper()
	var out bytes.Buffer
	writer := zip.NewWriter(&out)
	for _, name := range sortedKeys(members) {
		file, err := writer.Create(name)
		if err == nil {
			_, err = file.Write(members[name])
		}
		if err != nil {
			t.Fatal(err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	return out.Bytes()
}

func packTar(t *testing.T, members map[string]payloadMember) []byte {
	t.Helper()
	var out bytes.Buffer
	compressed := gzip.NewWriter(&out)
	writer := tar.NewWriter(compressed)
	for _, name := range sortedKeys(members) {
		member := members[name]
		header := &tar.Header{Name: name, Mode: int64(member.mode), Size: int64(len(member.data)), Typeflag: tar.TypeReg}
		if err := writer.WriteHeader(header); err != nil {
			t.Fatal(err)
		}
		if _, err := writer.Write([]byte(member.data)); err != nil {
			t.Fatal(err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := compressed.Close(); err != nil {
		t.Fatal(err)
	}
	return out.Bytes()
}

func sortedKeys[V any](values map[string]V) []string {
	var keys []string
	for key := range values {
		keys = append(keys, key)
	}
	slices.Sort(keys)
	return keys
}

func encodeTest(t *testing.T, value any) []byte {
	t.Helper()
	raw, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return raw
}
