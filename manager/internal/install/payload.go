package install

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json/v2"
	"io"
	"io/fs"
	"os"
	"path/filepath"
)

const (
	manifestSchema  = 3
	maximumManifest = int64(64) << 20
)

// admitPayload proves that the installed files are exactly the recorded ones, before this process runs any of them.
// activate in managed_product_installation.py records every file beneath these directories.
func admitPayload(installation string) error {
	damaged := func(detail string) error { return reject(InstallationDamaged, "%s", detail) }
	if resolved, err := filepath.EvalSymlinks(installation); err != nil || resolved != installation {
		return damaged("the installation is missing or reached through a link")
	}
	raw, err := readRegular(filepath.Join(installation, "installation.json"), maximumManifest)
	if err != nil {
		return damaged("the installation manifest is unreadable: " + err.Error())
	}
	var manifest struct {
		SchemaVersion    int    `json:"schemaVersion"`
		InstallationRoot string `json:"installationRoot"`
		PayloadFiles     []struct {
			Path   string      `json:"path"`
			SHA256 string      `json:"sha256"`
			Mode   fs.FileMode `json:"mode"`
		} `json:"payloadFiles"`
	}
	if json.Unmarshal(raw, &manifest) != nil || manifest.SchemaVersion != manifestSchema ||
		manifest.InstallationRoot != installation {
		return damaged("the installation manifest is not one this command admits")
	}
	expected := map[string]bool{}
	for _, file := range manifest.PayloadFiles {
		if !filepath.IsLocal(file.Path) || expected[file.Path] {
			return damaged("the installation manifest lists an invalid path")
		}
		expected[file.Path] = true
		path := filepath.Join(installation, file.Path)
		if resolved, err := filepath.EvalSymlinks(path); err != nil || resolved != path {
			return damaged(file.Path + " is missing or reached through a link")
		}
		info, err := os.Lstat(path)
		if err != nil || !info.Mode().IsRegular() || info.Mode().Perm() != file.Mode {
			return damaged(file.Path + " changed")
		}
		if sum, err := fileDigest(path); err != nil || "sha256:"+sum != file.SHA256 {
			return damaged(file.Path + " changed")
		}
	}
	for _, directory := range []string{"bin", "lib", "share"} {
		if extra := unlisted(installation, directory, expected); extra != "" {
			return damaged(extra + " is not part of the installation")
		}
	}
	return nil
}

// unlisted returns the first file beneath a directory that the manifest does not list.
func unlisted(installation, directory string, expected map[string]bool) (extra string) {
	_ = filepath.WalkDir(filepath.Join(installation, directory), func(path string, entry fs.DirEntry, err error) error {
		relative, _ := filepath.Rel(installation, path)
		if err != nil {
			extra = relative
			return fs.SkipAll
		}
		if !entry.IsDir() && !expected[filepath.ToSlash(relative)] {
			extra = relative
			return fs.SkipAll
		}
		return nil
	})
	return extra
}

func fileDigest(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	sum := sha256.New()
	if _, err := io.Copy(sum, file); err != nil {
		return "", err
	}
	return hex.EncodeToString(sum.Sum(nil)), nil
}
