package reset

import (
	"errors"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"slices"
	"syscall"
)

// backupPrefix names backup directories beside the management root. No deletion target matches it.
const backupPrefix = "kast-reset-backup-"

// keepsakes are the files inside the deletion targets that a user would lose.
type keepsakes struct{ files, skipped []string }

// keepsakes finds client configuration and workspace change evidence. A Host's private home holds the mutation
// database at .kast/state (HostedChangeResources.kt): plans, receipts, and the source preimages of unsettled changes
// (SqliteMutationRecoveryDatabase.kt). container.json names the workspace it belongs to.
func (l *layout) keepsakes(found *inventory) (*keepsakes, error) {
	kept := &keepsakes{}
	for _, payload := range found.payloads {
		configuration := filepath.Join(payload, "config")
		if err := l.keep(kept, filepath.Join(configuration, "environment")); err != nil {
			return nil, err
		}
		overlays := filepath.Join(configuration, "workspaces")
		names, err := entries(overlays, found)
		if err != nil {
			return nil, err
		}
		for _, name := range names {
			if err := l.keep(kept, filepath.Join(overlays, name, "environment")); err != nil {
				return nil, err
			}
		}
	}
	for _, profile := range found.profiles {
		before := len(kept.files)
		if err := l.keep(kept, filepath.Join(profile, ".kast", "state")); err != nil {
			return nil, err
		}
		record := l.containerRecord(profile)
		if len(kept.files) > before && !slices.Contains(kept.files, record) {
			if err := l.keep(kept, record); err != nil {
				return nil, err
			}
		}
	}
	return kept, nil
}

// containerRecord is the record of the container a profile belongs to: the first directory beneath containers.
func (l *layout) containerRecord(profile string) string {
	containers := filepath.Join(l.workspaces, "containers")
	container := profile
	for filepath.Dir(container) != containers {
		container = filepath.Dir(container)
	}
	return filepath.Join(container, "container.json")
}

// keep adds the regular files at or beneath path. Links are named and never followed; their targets stay in place.
func (l *layout) keep(kept *keepsakes, path string) error {
	if !exists(path) {
		return nil
	}
	parent := filepath.Dir(path)
	if resolved, err := filepath.EvalSymlinks(parent); err != nil || resolved != parent {
		kept.skipped = append(kept.skipped, path+": reached through a link")
		return nil
	}
	err := filepath.WalkDir(path, func(file string, entry fs.DirEntry, err error) error {
		switch {
		case err != nil:
			return err
		case entry.IsDir():
		case !entry.Type().IsRegular():
			kept.skipped = append(kept.skipped, file+": not a regular file")
		default:
			kept.files = append(kept.files, file)
		}
		return nil
	})
	if err != nil {
		return reject(BackupRejected, "%v", err)
	}
	return nil
}

// backup copies every keepsake to one new directory and prints its path. It reads the keepsakes again, because stopping
// a Host can write its last evidence.
func (r *Reset) backup(paths *layout, found *inventory) error {
	kept, err := paths.keepsakes(found)
	if err != nil || len(kept.files) == 0 {
		return err
	}
	directory := filepath.Join(paths.share, backupPrefix+r.Now().UTC().Format("20060102T150405.000000000Z"))
	// The name changes only after every copy is durable, so a partial backup never looks complete.
	partial := directory + ".partial"
	if err := os.Mkdir(partial, 0o700); err != nil {
		return reject(BackupRejected, "%v", err)
	}
	for _, file := range kept.files {
		relative, err := filepath.Rel(paths.share, file)
		if err != nil {
			return reject(BackupRejected, "%v", err)
		}
		if err := copyFile(file, filepath.Join(partial, relative)); err != nil {
			return reject(BackupRejected, "%v", err)
		}
	}
	if err := os.Rename(partial, directory); err != nil {
		return reject(BackupRejected, "%v", err)
	}
	r.print("Backup: " + directory)
	return nil
}

func copyFile(source, destination string) (err error) {
	input, err := os.OpenFile(source, os.O_RDONLY|syscall.O_NOFOLLOW, 0)
	if err != nil {
		return err
	}
	defer input.Close()
	if err := os.MkdirAll(filepath.Dir(destination), 0o700); err != nil {
		return err
	}
	output, err := os.OpenFile(destination, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return err
	}
	defer func() { err = errors.Join(err, output.Close()) }()
	if _, err := io.Copy(output, input); err != nil {
		return err
	}
	return output.Sync()
}
