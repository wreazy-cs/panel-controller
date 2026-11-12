# Panel Controller

Command line tool for PlatinMarket-based online stores (unofficial, not affiliated).

PlatinMarket lets store owners add custom CSS and JS only by pasting code into the admin panel.
Doing this by hand is risky: a mistake can break a live store, and there is no built-in backup or
version history. Panel Controller makes that workflow safe:

- **build** many small source files into the few files the panel accepts (reproducible output)
- **validate** them before upload by imitating what the panel does on save
- **backup** the live version first, and **restore** it to the clipboard when something goes wrong
- **compare** local files with what is actually live

> **Status: 0.1, command line works.** Desktop interface (JavaFX) is planned.

## Requirements

Java 21 or newer, Maven. Node.js is optional (used for a JS syntax check).

## Build and run

```
mvn package
java -jar target/panel-controller-0.1.0-SNAPSHOT.jar --root <project dir> --config <file> <command>
```

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
| `backups` | Lists backups, newest first. |
| `copy <file>` | Copies an output file to the clipboard. |
| `restore <stamp\|latest> <file>` | Copies a backed-up file to the clipboard for pasting back. |

Typical flow: `check` → `backup` → paste into the panel → `compare`.

## Rules enforced by `build`

JS sources are pure ASCII; no HTML entities; everything fits windows-1254; every piece starts with a
meta line with a unique number; JS pieces end with `;` and have no multi-line strings or template
literals; CSS compression is re-parsed and must equal the uncompressed rules; the panel copy
contains no comments (the files are publicly readable from the CDN).

## Roadmap

1. ~~Skeleton, license, documentation~~
2. ~~Core library and command line: build, validate, backup, compare, restore, clipboard~~
3. Desktop interface (JavaFX)
4. Git integration and read-only API experiments

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).

"PlatinMarket" is a trademark of its respective owner. This project is
independent and has no official connection to it.
