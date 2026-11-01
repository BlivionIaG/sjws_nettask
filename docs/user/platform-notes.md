# Platform notes

* Names are matched as the file system matches them: on case-insensitive file systems (macOS default,
  Windows) `/HELLO.TXT` serves `hello.txt`; on Linux it is a 404. Unicode NFC/NFD variants likewise
  follow the file system. Containment and the dotfile rule hold either way.
* `%2F` is decoded before path resolution, so it acts as `/` (it cannot escape the root).
* Where the file system has no file keys (Windows), the "file was swapped between resolve and send"
  check falls back to the size check during transfer.
* Files are opened relative to the document root one directory at a time without following symlinks
  (`SecureDirectoryStream` on Linux; `openat` with `O_NOFOLLOW` on macOS), so a directory swapped for a
  symlink between resolve and send is never followed. Where neither path is available (Windows; startup
  then prints a `WARN:` line), the server opens the path without following a final symlink and re-checks
  its real path (containment) and file key afterwards; that narrows but does not close the race, so don't
  serve a tree that untrusted users can write to on such a platform.
* **Unlistable directories.** Opening relative to a directory needs read permission on it. Files below
  a directory the server can search but not read (mode `--x`, e.g. `711`), or below such a root, are
  therefore answered **403** by default. `--allow-unlistable-dirs` serves them through the path-based
  open described above (startup prints a `WARN:`). That is **weaker against local writers**: anyone who
  can write in the document root can create their own `--x` directory and race a symlink swap below it
  to get files from outside the root served. Use it only if no untrusted user can write in the root.
* Only regular files are served: a FIFO, device or socket is refused (404) after a type check made
  without following links, also when it was swapped in after the request was resolved. A narrow window
  remains between that check and the open (Java can't open non-blocking): someone with write access
  in the document root who swaps a FIFO in at exactly that moment, e.g. in a tight rename loop, can
  block the thread serving that request until the FIFO is opened for writing. If the file can't be
  opened after the response was decided (removed, swapped, permissions changed), the client gets a
  regular 404/403/500 response instead of a dropped connection.
