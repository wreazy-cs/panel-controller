# Panel Controller

Command line tool for PlatinMarket-based online stores (unofficial, not affiliated).

PlatinMarket lets store owners add custom CSS and JS only by pasting code into the admin panel.
Doing this by hand is risky: a mistake can break a live store, and there is no built-in backup or
version history. Panel Controller makes that workflow safe:

- **build** many small source files into the few files the panel accepts (reproducible output)
- **validate** them before upload by imitating what the panel does on save
- **backup** the live version first, and **restore** it to the clipboard when something goes wrong
- **compare** local files with what is actually live

> **Status: 0.1.** Command line and desktop window both work.

## Requirements

Java 21 or newer, Maven. Node.js is optional (used for a JS syntax check).

## Build and run

```
mvn package
```

Command line:

```
java -jar target/panel-controller-0.1.0-SNAPSHOT.jar --root <project dir> --config <file> <command>
```

Desktop window (needs the JavaFX jars that `mvn package` copies to `target/lib`):

```
run-gui.cmd [--root <project dir>] [--config <file>]
```

The window shows four cards (last backup, build state, live difference, git), a table of the slots,
and buttons for every command. Before copying a file to the clipboard it warns if the output is
older than the sources, if a slot is over its target, or if there is no recent backup.
The chosen project folder and config file are remembered between runs.

## Project layout

```
<root>/kaynak/<slot>/*.css|*.js   source pieces (first line: /*@vt <no> | <title> | ezer: <what it overrides> */)
<root>/cikti/                     files to paste into the panel
<root>/veri/yedek/<stamp>/        backups of the live files
```

Directory names, the CDN URL and the slot list can be changed in a properties file, see
[panel-controller.properties.example](panel-controller.properties.example).

## Commands

| Command | What it does |
|---|---|
| `build [--readable\|--commented] [slot...]` | Builds the panel files. `--readable` / `--commented` make review copies that are never pasted. |
| `validate` | Panel simulation: HTML entities, windows-1254 round trip, budget, JS syntax, CSS balance, leftover comments. |
| `check` | `build` + `validate`. |
| `backup` | Downloads the live files. Fails if any file is missing, so you never upload without a backup. |
| `compare [--dir d]` | Diffs local files against the live CDN (cache-busting query on every request). |
| `status` | Slot sizes, whether output is current, backup age, git state. |
| `backups` | Lists backups, newest first. |
| `copy <file>` | Copies an output file to the clipboard. |
| `restore <stamp\|latest> <file>` | Copies a backed-up file to the clipboard for pasting back. |

Typical flow: `check` → `backup` → paste into the panel → `compare`.

## API key

Entered from the ⚙ menu (top right of the window). It is never written to the screen, the log or
the clipboard; only its last four characters are shown. There is no command line access to it.

- **"Remember" unticked:** kept in memory only, gone when the app closes.
- **"Remember" ticked:** you set a 4-digit PIN first. The key is stored in
  `%LOCALAPPDATA%\PanelController\vault.bin` and the app asks for the PIN on every start.
- **⚙ menu:** change PIN, remove PIN (deletes the stored key), lock now.
- **Forgot the PIN:** "PIN'imi unuttum" on the lock screen deletes the stored key; enter it again.

How it is protected:

- AES-256-GCM, with the key derived from the PIN (PBKDF2-HMAC-SHA256, 600,000 rounds, random salt)
- wrapped in Windows DPAPI: only your Windows account on this computer can open the file
- file access list: owner only; location outside OneDrive and outside any git repository
- 5 wrong PINs delete the stored key

Limits, stated plainly: 4 digits are 10,000 combinations. Software already running as your Windows
user can bypass the attempt counter and try them all. The PIN stops someone who sits down at the
app; it does not stop malware on your account. Persistent storage is Windows only; elsewhere the
key is kept for the session.

## Rules enforced by `build`

JS sources are pure ASCII; no HTML entities; everything fits windows-1254; every piece starts with a
meta line with a unique number; JS pieces end with `;` and have no multi-line strings or template
literals; CSS compression is re-parsed and must equal the uncompressed rules; the panel copy
contains no comments (the files are publicly readable from the CDN).

## Roadmap

1. ~~Skeleton, license, documentation~~
2. ~~Core library and command line: build, validate, backup, compare, restore, clipboard~~
3. ~~Desktop interface (JavaFX)~~
4. ~~API key storage (PIN + DPAPI)~~
5. Read-only API calls

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).

"PlatinMarket" is a trademark of its respective owner. This project is
independent and has no official connection to it.
