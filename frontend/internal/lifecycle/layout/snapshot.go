package layout

import (
	"slices"
	"strings"

	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

// EntryKind is the closed set of file types an lstat reports.
type EntryKind uint8

const (
	KindDirectory EntryKind = iota + 1
	KindFile
	KindLink
	KindOther
)

// Entry is one directory entry as lstat saw it: its name, file type, whether the invoking user owns it, and for a
// symbolic link its exact target string.
type Entry struct {
	name   string
	kind   EntryKind
	owned  bool
	target string
}

// Directory is a directory entry.
func Directory(name string, owned bool) Entry { return Entry{name, KindDirectory, owned, ""} }

// File is a regular file entry.
func File(name string, owned bool) Entry { return Entry{name, KindFile, owned, ""} }

// Link is a symbolic link entry with its target string.
func Link(name string, owned bool, target string) Entry { return Entry{name, KindLink, owned, target} }

// Special is any other file type: a socket, a device, a pipe.
func Special(name string, owned bool) Entry { return Entry{name, KindOther, owned, ""} }

// Name is the entry name.
func (e Entry) Name() string { return e.name }

// Kind is the file type.
func (e Entry) Kind() EntryKind { return e.kind }

// Owned reports ownership by the invoking user.
func (e Entry) Owned() bool { return e.owned }

// Target is a link's target string.
func (e Entry) Target() (string, bool) { return e.target, e.kind == KindLink }

// OwnedDirectory reports an owned directory that is not a link.
func (e Entry) OwnedDirectory() bool { return e.kind == KindDirectory && e.owned }

// ReadState is the closed set of results of reading one record file.
type ReadState uint8

const (
	ReadMissing ReadState = iota + 1
	ReadNotRegular
	ReadBytes
)

// Read is one record file as the adapter read it, within the record's byte limit plus one.
type Read struct {
	state ReadState
	raw   []byte
}

// Missing is an absent record file.
func Missing() Read { return Read{state: ReadMissing} }

// NotRegular is a record path that is not a regular file.
func NotRegular() Read { return Read{state: ReadNotRegular} }

// Contents is a record file's bytes.
func Contents(raw []byte) Read { return Read{ReadBytes, slices.Clone(raw)} }

// VersionRecords are the record files of the version directory that current names.
type VersionRecords struct {
	Manifest  Read
	Selection Read
	Build     Read
	Marker    Read
}

// ContainerState is the observed state of S/kast-workspaces/containers.
type ContainerState uint8

const (
	ContainersAbsent ContainerState = iota + 1
	ContainersEmpty
	ContainersOccupied
)

// Observation is the adapter's raw report: the entries of S, of R, of R/versions, of both store entry directories,
// of the public command directory and the socket alias directory, the current version's records, and the container
// directory's state. Observe admits it into a Snapshot.
type Observation struct {
	Share        []Entry
	Root         []Entry
	Versions     []Entry
	Current      *VersionRecords
	RuntimeStore []Entry
	BaseStore    []Entry
	Containers   ContainerState
	Bin          []Entry
	TmpAliases   []Entry
}

// Defect is the closed set of reasons an observation is inconsistent. A defect is an adapter error, not a layout.
type Defect uint8

const (
	DefectEntryName Defect = iota + 1
	DefectDuplicateEntry
	DefectEntryKind
	DefectRootEntries
	DefectVersionEntries
	DefectCurrentRecords
	DefectStoreEntries
	DefectContainers
)

var defectNames = map[Defect]string{
	DefectEntryName: "ENTRY_NAME", DefectDuplicateEntry: "DUPLICATE_ENTRY", DefectEntryKind: "ENTRY_KIND",
	DefectRootEntries: "ROOT_ENTRIES", DefectVersionEntries: "VERSION_ENTRIES",
	DefectCurrentRecords: "CURRENT_RECORDS", DefectStoreEntries: "STORE_ENTRIES", DefectContainers: "CONTAINERS",
}

func (d Defect) Error() string { return "layout: inconsistent observation: " + defectNames[d] }

// Snapshot is an admitted, consistent observation. The zero value was never observed, so Classify refuses it rather
// than reporting Absent.
type Snapshot struct {
	observed                                                        bool
	share, root, versions, runtimeStore, baseStore, bin, tmpAliases []Entry
	current                                                         *VersionRecords
	containers                                                      ContainerState
}

func sortedEntries(entries []Entry) ([]Entry, error) {
	sorted := slices.Clone(entries)
	slices.SortFunc(sorted, func(a, b Entry) int { return strings.Compare(a.name, b.name) })
	for i, entry := range sorted {
		if entry.name == "" || entry.name == "." || entry.name == ".." || strings.ContainsAny(entry.name, "/\x00") {
			return nil, DefectEntryName
		}
		if entry.kind < KindDirectory || entry.kind > KindOther || (entry.kind != KindLink && entry.target != "") {
			return nil, DefectEntryKind
		}
		if i > 0 && sorted[i-1].name == entry.name {
			return nil, DefectDuplicateEntry
		}
	}
	return sorted, nil
}

func find(entries []Entry, name string) (Entry, bool) {
	index, found := slices.BinarySearchFunc(entries, name, func(entry Entry, target string) int {
		return strings.Compare(entry.name, target)
	})
	if !found {
		return Entry{}, false
	}
	return entries[index], true
}

// Observe admits an observation: names are single path elements and unique, a directory's entries appear only when
// that directory is an owned directory, and the current records appear exactly when current is an owned link to an
// owned version directory.
func Observe(o Observation) (Snapshot, error) {
	var s Snapshot
	lists := []struct {
		in  []Entry
		out *[]Entry
	}{{o.Share, &s.share}, {o.Root, &s.root}, {o.Versions, &s.versions}, {o.RuntimeStore, &s.runtimeStore},
		{o.BaseStore, &s.baseStore}, {o.Bin, &s.bin}, {o.TmpAliases, &s.tmpAliases}}
	for _, list := range lists {
		sorted, err := sortedEntries(list.in)
		if err != nil {
			return Snapshot{}, err
		}
		*list.out = sorted
	}
	rootEntry, _ := find(s.share, RootName)
	if !rootEntry.OwnedDirectory() && len(s.root) > 0 {
		return Snapshot{}, DefectRootEntries
	}
	versionsEntry, _ := find(s.root, VersionsName)
	if !versionsEntry.OwnedDirectory() && len(s.versions) > 0 {
		return Snapshot{}, DefectVersionEntries
	}
	_, named := currentVersion(s.root, s.versions)
	if named != (o.Current != nil) {
		return Snapshot{}, DefectCurrentRecords
	}
	if o.Current != nil {
		records := *o.Current
		for _, read := range []Read{records.Manifest, records.Selection, records.Build, records.Marker} {
			if read.state < ReadMissing || read.state > ReadBytes {
				return Snapshot{}, DefectCurrentRecords
			}
		}
		s.current = &records
	}
	workspace, _ := find(s.share, WorkspaceStoreName)
	base, _ := find(s.share, BaseStoreName)
	if (!workspace.OwnedDirectory() && len(s.runtimeStore) > 0) || (!base.OwnedDirectory() && len(s.baseStore) > 0) {
		return Snapshot{}, DefectStoreEntries
	}
	if o.Containers < ContainersAbsent || o.Containers > ContainersOccupied ||
		(!workspace.OwnedDirectory() && o.Containers != ContainersAbsent) {
		return Snapshot{}, DefectContainers
	}
	s.containers = o.Containers
	s.observed = true
	return s, nil
}

// currentVersion returns the version that an owned, well-shaped current link names, when that version directory
// exists as an owned directory.
func currentVersion(root, versions []Entry) (record.VersionID, bool) {
	current, ok := find(root, CurrentName)
	if !ok || current.kind != KindLink || !current.owned {
		return record.VersionID{}, false
	}
	target, ok := record.ParseCurrentTarget(current.target)
	if !ok {
		return record.VersionID{}, false
	}
	directory, ok := find(versions, target.Version().String())
	return target.Version(), ok && directory.OwnedDirectory()
}

// Share are the S-level entries, sorted by name.
func (s Snapshot) Share() []Entry { return slices.Clone(s.share) }

// Root are the entries of R, sorted by name.
func (s Snapshot) Root() []Entry { return slices.Clone(s.root) }

// Versions are the entries of R/versions, sorted by name.
func (s Snapshot) Versions() []Entry { return slices.Clone(s.versions) }

// RuntimeStore are the entries of S/kast-workspaces/runtimes, sorted by name.
func (s Snapshot) RuntimeStore() []Entry { return slices.Clone(s.runtimeStore) }

// BaseStore are the entries of S/kast-runtime-bases, sorted by name.
func (s Snapshot) BaseStore() []Entry { return slices.Clone(s.baseStore) }

// Bin are the entries of the public command directory, sorted by name.
func (s Snapshot) Bin() []Entry { return slices.Clone(s.bin) }

// TmpAliases are the socket alias entries, sorted by name.
func (s Snapshot) TmpAliases() []Entry { return slices.Clone(s.tmpAliases) }

// Containers is the container directory's state.
func (s Snapshot) Containers() ContainerState { return s.containers }

// ShareEntry finds an S-level entry.
func (s Snapshot) ShareEntry(name string) (Entry, bool) { return find(s.share, name) }

// RootEntry finds an entry of R.
func (s Snapshot) RootEntry(name string) (Entry, bool) { return find(s.root, name) }

// VersionEntry finds an entry of R/versions.
func (s Snapshot) VersionEntry(name string) (Entry, bool) { return find(s.versions, name) }

// RuntimeEntry finds an entry of the runtime store.
func (s Snapshot) RuntimeEntry(name string) (Entry, bool) { return find(s.runtimeStore, name) }

// BaseEntry finds an entry of the base store.
func (s Snapshot) BaseEntry(name string) (Entry, bool) { return find(s.baseStore, name) }

// BinEntry finds an entry of the public command directory.
func (s Snapshot) BinEntry(name string) (Entry, bool) { return find(s.bin, name) }
