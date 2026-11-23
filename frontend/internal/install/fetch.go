package install

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"syscall"
	"time"
)

const (
	attempts         = 5
	retryDelay       = 2 * time.Second
	maximumRedirects = 10
	partSuffix       = ".part"
	lockName         = ".lock"
)

// downloadName is a file this package wrote: an asset's digest and name, complete or partial.
var downloadName = regexp.MustCompile(`^[0-9a-f]{64}-[A-Za-z0-9._-]+$`)

// permanent is a response that another attempt cannot change.
type permanent struct{ error }

func (i *Install) client() *http.Client {
	return &http.Client{
		Transport: i.Transport,
		CheckRedirect: func(request *http.Request, via []*http.Request) error {
			if request.URL.Scheme != "https" || len(via) >= maximumRedirects {
				return permanent{errors.New("unsafe redirect")}
			}
			return nil
		},
	}
}

// retry runs one transfer until it succeeds, fails permanently, or stops making progress.
func (i *Install) retry(ctx context.Context, what string, transfer func(context.Context) (advanced bool, err error)) error {
	for failed := 0; ; {
		if ctx.Err() != nil {
			return reject(Interrupted, "%s", what)
		}
		advanced, err := transfer(ctx)
		if err == nil {
			return nil
		}
		if ctx.Err() != nil {
			return reject(Interrupted, "%s", what)
		}
		var rejection *Rejection
		if errors.As(err, &rejection) {
			return rejection
		}
		var final permanent
		if failed++; errors.As(err, &final) || (failed >= attempts && !advanced) {
			return reject(SourceUnavailable, "%s: %v", what, err)
		}
		if advanced {
			failed = 0
		}
		i.Pause(retryDelay)
	}
}

// open starts one response. Its body fails if no byte arrives within the stall limit.
func (i *Install) open(ctx context.Context, url string, offset int64) (*http.Response, error) {
	ctx, cancel := context.WithCancelCause(ctx)
	stalled := time.AfterFunc(i.Stall, func() { cancel(errors.New("the connection stalled")) })
	release := func() { stalled.Stop(); cancel(nil) }
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		release()
		return nil, permanent{err}
	}
	request.Header.Set("User-Agent", "kast-install")
	if offset > 0 {
		request.Header.Set("Range", "bytes="+strconv.FormatInt(offset, 10)+"-")
	}
	response, err := i.client().Do(request)
	if err != nil {
		release()
		return nil, err
	}
	response.Body = &watched{ReadCloser: response.Body, reset: func() { stalled.Reset(i.Stall) }, release: release}
	switch status := response.StatusCode; {
	case status == http.StatusOK || (status == http.StatusPartialContent && offset > 0):
		return response, nil
	case status == http.StatusTooManyRequests || status == http.StatusRequestTimeout || status >= 500:
		err = fmt.Errorf("HTTP status %d", status)
	case status == http.StatusNotFound:
		err = reject(BuildNotFound, "%s is not published", url)
	default:
		err = permanent{fmt.Errorf("HTTP status %d", status)}
	}
	response.Body.Close()
	return nil, err
}

type watched struct {
	io.ReadCloser
	reset, release func()
}

func (w *watched) Read(buffer []byte) (int, error) {
	count, err := w.ReadCloser.Read(buffer)
	if count > 0 {
		w.reset()
	}
	return count, err
}

func (w *watched) Close() error {
	defer w.release()
	return w.ReadCloser.Close()
}

// read returns one bounded record.
func (i *Install) read(ctx context.Context, url string) (raw []byte, err error) {
	err = i.retry(ctx, url, func(ctx context.Context) (bool, error) {
		response, err := i.open(ctx, url, 0)
		if err != nil {
			return false, err
		}
		defer response.Body.Close()
		raw, err = io.ReadAll(io.LimitReader(response.Body, maximumRecord+1))
		if err == nil && len(raw) > maximumRecord {
			return false, reject(RecordInvalid, "%s is larger than %d bytes", url, maximumRecord)
		}
		return false, err
	})
	return raw, err
}

// store is the private download directory, held by one command.
type store struct {
	directory string
	lock      *os.File
}

func storeDirectory(home string) string {
	return filepath.Join(home, ".local", "share", ".kast-downloads")
}

// openStore creates the directory beneath a home it reaches without links, and takes its lock.
func openStore(home string) (*store, error) {
	if !filepath.IsAbs(home) {
		return nil, reject(StorageRejected, "HOME is not an absolute path: %q", home)
	}
	directory := storeDirectory(home)
	for _, path := range []string{filepath.Dir(filepath.Dir(directory)), filepath.Dir(directory), directory} {
		mode := os.FileMode(0o755)
		if path == directory {
			mode = 0o700
		}
		if err := os.Mkdir(path, mode); err != nil && !errors.Is(err, os.ErrExist) {
			return nil, reject(StorageRejected, "%v", err)
		}
		info, err := os.Lstat(path)
		if err != nil || !info.IsDir() {
			return nil, reject(StorageRejected, "%s is not a directory", path)
		}
	}
	lock, err := os.OpenFile(filepath.Join(directory, lockName), os.O_CREATE|os.O_RDWR|syscall.O_NOFOLLOW, 0o600)
	if err != nil {
		return nil, reject(StorageRejected, "%v", err)
	}
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		lock.Close()
		return nil, reject(DownloadBusy, "another Kast download holds %s", directory)
	}
	return &store{directory, lock}, nil
}

func (s *store) close() { s.lock.Close() }

func (s *store) path(asset Asset) string {
	return filepath.Join(s.directory, asset.SHA256+"-"+asset.Name)
}

// prune removes other downloads this package wrote.
func (s *store) prune(assets []Asset) error {
	keep := map[string]bool{}
	for _, asset := range assets {
		keep[filepath.Base(s.path(asset))] = true
	}
	entries, err := os.ReadDir(s.directory)
	for _, entry := range entries {
		if err == nil && downloadName.MatchString(entry.Name()) && !keep[entry.Name()] {
			err = os.Remove(filepath.Join(s.directory, entry.Name()))
		}
	}
	if err != nil {
		return reject(StorageRejected, "%v", err)
	}
	return nil
}

// complete reports whether the directory already holds the asset's exact bytes.
func complete(path string, asset Asset) bool {
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || info.Size() != asset.Bytes {
		return false
	}
	file, err := os.OpenFile(path, os.O_RDONLY|syscall.O_NOFOLLOW, 0)
	if err != nil {
		return false
	}
	defer file.Close()
	return matches(file, asset) == nil
}

func matches(file *os.File, asset Asset) error {
	hash := sha256.New()
	if _, err := io.Copy(hash, file); err != nil {
		return err
	}
	if hex.EncodeToString(hash.Sum(nil)) != asset.SHA256 {
		return errors.New("the bytes do not match the published digest")
	}
	return nil
}

// download leaves the asset's verified bytes in the store. An interruption keeps the part for the next command.
func (i *Install) download(ctx context.Context, s *store, tag string, asset Asset, meter *progress) error {
	part := s.path(asset) + partSuffix
	file, err := os.OpenFile(part, os.O_CREATE|os.O_RDWR|syscall.O_NOFOLLOW, 0o600)
	if err != nil {
		return reject(StorageRejected, "%v", err)
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() {
		return reject(StorageRejected, "%s is not a file", part)
	}
	offset := info.Size()
	if offset > asset.Bytes {
		if err := file.Truncate(0); err != nil {
			return reject(StorageRejected, "%v", err)
		}
		offset = 0
	}
	meter.add(offset)
	if offset < asset.Bytes {
		url := releases + "/" + tag + "/" + asset.Name
		err = i.retry(ctx, asset.Name, func(ctx context.Context) (bool, error) {
			before := offset
			err := i.transfer(ctx, url, file, asset, &offset, meter)
			return offset > before, err
		})
	}
	if err == nil {
		if _, err = file.Seek(0, io.SeekStart); err == nil {
			err = matches(file, asset)
		}
		if err != nil {
			err = reject(DownloadInvalid, "%s: %v", asset.Name, err)
		}
	}
	var rejection *Rejection
	if errors.As(err, &rejection) && rejection.Failure == DownloadInvalid {
		os.Remove(part)
	}
	if err != nil {
		return err
	}
	if err := os.Rename(part, s.path(asset)); err != nil {
		return reject(StorageRejected, "%v", err)
	}
	return nil
}

// transfer appends one response to the part. A response that does not continue exactly at offset restarts the file.
func (i *Install) transfer(ctx context.Context, url string, file *os.File, asset Asset, offset *int64,
	meter *progress) error {
	response, err := i.open(ctx, url, *offset)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	expected := fmt.Sprintf("bytes %d-%d/%d", *offset, asset.Bytes-1, asset.Bytes)
	if *offset > 0 && (response.StatusCode != http.StatusPartialContent ||
		response.Header.Get("Content-Range") != expected) {
		meter.add(-*offset)
		*offset = 0
		if err := file.Truncate(0); err != nil {
			return reject(StorageRejected, "%v", err)
		}
		if response.StatusCode != http.StatusOK {
			return errors.New("the server answered another range")
		}
	}
	buffer := make([]byte, 128<<10)
	for {
		count, err := response.Body.Read(buffer)
		if int64(count) > asset.Bytes-*offset {
			return reject(DownloadInvalid, "%s is larger than its record", asset.Name)
		}
		if count > 0 {
			if _, failure := file.WriteAt(buffer[:count], *offset); failure != nil {
				return reject(StorageRejected, "%v", failure)
			}
			*offset += int64(count)
			meter.add(int64(count))
		}
		switch {
		case errors.Is(err, io.EOF) && *offset == asset.Bytes:
			return nil
		case errors.Is(err, io.EOF):
			return io.ErrUnexpectedEOF
		case err != nil:
			return err
		}
	}
}
