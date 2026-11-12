<p align="center"><img src="docs/logo.svg" width="96" alt="Panel Controller logo"></p>

# Panel Controller

Desktop and command line tool for PlatinMarket-based online stores (unofficial, not affiliated).
Made by Samet Şafak.

PlatinMarket lets store owners add custom CSS and JS only by pasting code into the admin panel.
There is no backup, no version history and no validation, and the panel can corrupt code on save.
Panel Controller makes the upload workflow safe:

- **prepare**: back up the live files, build the small source pieces into the panel files, and
  simulate what the panel does on save; stop at the first problem
- **compare** with the live site and **paste in the right order** with a step-by-step wizard
  (page slots first, the general slot last)
- **restore** an earlier live version to the clipboard when something breaks
- **commit and push** with git, refusing to push when build or validation is not clean

> **Status: 0.6 beta.** The user interface is in Turkish.

## Install (end users)

Run `Panel Controller-<version>.exe`. It installs per user (no administrator rights), adds a desktop
and Start menu shortcut, and **brings its own Java**, so nothing else is required. On first start a
welcome window shows the system check: Git (Git page), Node.js (JS syntax check) and Python (test bench,
project scripts) are optional; a missing one can be installed with winget from there.

## Build (developers)

Requirements: JDK 25 or newer, Maven.

```
mvn package
powershell -ExecutionPolicy Bypass -File create-shortcut.ps1      # developer shortcut, runs from target/
powershell -ExecutionPolicy Bypass -File build-installer.ps1      # dist\Panel Controller-<version>.exe
```

`build-installer.ps1` needs WiX Toolset 3.14 on the PATH for the `.exe`/`.msi`
(`-Type app-image` builds a runnable folder without WiX). Do not run `mvn package` while the
developer build is open: it replaces the jar under the running app. The installed version is not affected.
`run-gui.cmd` starts the developer build with a console that shows errors.

On first start choose the project folder (the one that contains `kaynak/`) and enter the store's CDN
address under **Ayarlar** (settings). Settings are kept per project; no config file is needed.

## The window

| Page | What it is for |
|---|---|
| Yayın | Status cards, Prepare with a step indicator, file table in paste order with live state, upload wizard |
| Geri dön | Backups, newest first; copy a file back to the clipboard |
| Git | Pick changed files, commit (`13.11.2026-Persembe-14.05 message -p` + link to this app), push |
| Görevler | The project's `tasks/` folder by state |
| Araçlar | Page preview, local test bench (127.0.0.1 only), session notes |
| API | Read-only calls to the PlatinMarket Reform API (store info, exchange rates, brands, categories ...) |
| Sürüm notları | What the app is for and what changed in each version |
| Hakkında | Author, version, licence, components |
| Ayarlar | Project folder, CDN address, limits, theme (light/dark), API key and PIN |

Results appear as cards in the **İşlemler** panel instead of a log. Problems carry a code
(`PC-101` ... `PC-901`); clicking it opens the in-app **user guide** at that code. The guide has
searchable headings, diagrams and the full error table (generated from `Problem.java`).

Shortcuts: `F5` refresh and compare, `F1` guide, `Ctrl+1` ... `Ctrl+9` pages.

## PlatinMarket API

The Reform API is documented at developer.platinmarket.com:
`https://developer.platinmarket.com/reform/<controller>/<action>.json?access_token=<key>`. The key is
created in the store panel under Araçlar > API Anahtarları and is tied to an IP address. Errors come back
as HTTP 200 with `response.error.code` set; the app reports them as PC-710. **The API has no endpoints for
the theme CSS/JS slots**, so uploading stays manual (the upload wizard).

## Project layout

```
<root>/kaynak/<slot>/*.css|*.js   source pieces (first line: /*@vt <no> | <title> | ezer: <what it overrides> */)
<root>/cikti/                     files to paste into the panel
<root>/veri/yedek/<stamp>/        backups of the live files
<root>/veri/sayfalar/*.html       panel page contents (preview)
<root>/veri/oturumlar/            session notes
<root>/tasks/                     tasks: <date>_<slug>_<status>.md
```

## Command line

```
java -jar target/panel-controller.jar --root <project dir> --config <file> <command>
```

| Command | What it does |
|---|---|
| `prepare` | Backup + build + validate; stops at the first failure, then prints the paste order. |
| `build [--readable\|--commented] [slot...]` | Builds the panel files. `--readable` / `--commented` make review copies that are never pasted. |
| `validate` | Panel simulation: HTML entities, windows-1254 round trip, budget, JS syntax, CSS balance, leftover comments. |
| `check` | `build` + `validate`. |
| `backup` | Downloads the live files. Fails if any file is missing, so you never upload without a backup. |
| `compare [--dir d]` | Diffs local files against the live CDN (cache-busting query on every request). |
| `order` | Files to paste, in order. |
| `status` | Slot sizes, whether output is current, backup age, git state. |
| `backups` | Lists backups, newest first. |
| `copy <file>` | Copies an output file to the clipboard. |
| `restore <stamp\|latest> <file>` | Copies a backed-up file to the clipboard for pasting back. |
| `tasks [all]` | Active tasks. |
| `note <name>` | Opens today's session note with the start time. |
| `version` | Version and release notes. |

The command line reads the CDN address from a properties file (`--config`, see
[panel-controller.properties.example](panel-controller.properties.example)). There is deliberately no
command that touches the API key.

## API key

Entered under **Ayarlar**. It is never written to the screen, the log or the clipboard; only its last
four characters are shown.

- **"Remember" unticked:** kept in memory only, gone when the app closes.
- **"Remember" ticked:** you set a 4-digit PIN first. The key is stored in
  `%LOCALAPPDATA%\PanelController\vault.bin` and the app asks for the PIN on every start.
- Change PIN, remove PIN (deletes the stored key), lock now: also under Ayarlar.
- **Forgot the PIN:** "PIN'imi unuttum" on the lock screen deletes the stored key; enter it again.

How it is protected: AES-256-GCM with a key derived from the PIN (PBKDF2-HMAC-SHA256, 600,000 rounds,
random salt), wrapped in Windows DPAPI (only your Windows account on this computer can open it), file
access limited to the owner, stored outside OneDrive and git. 5 wrong PINs delete the stored key.

Limits, stated plainly: 4 digits are 10,000 combinations. Software already running as your Windows
user can bypass the attempt counter. The PIN stops someone who sits down at the app; it does not stop
malware on your account.

## Roadmap

1. ~~Core library and command line~~
2. ~~Desktop interface~~
3. ~~API key storage (PIN + DPAPI)~~
4. ~~Upload workflow from the Python predecessor: prepare, paste order, wizard, git, tasks, tools~~
5. ~~Read-only API calls, system check, Windows installer~~
6. API features beyond probing (e.g. low-stock report, product checks)

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).

"PlatinMarket" is a trademark of its respective owner. This project is
independent and has no official connection to it.
