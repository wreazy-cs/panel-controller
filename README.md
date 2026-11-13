<p align="center"><img src="docs/logo.svg" width="96" alt="Panel Controller logo"></p>

# Panel Controller

Checks and git for PlatinMarket store themes (unofficial, not affiliated). Made by Samet Şafak.

[Türkçe](#türkçe) · [Download](https://github.com/wreazy-cs/panel-controller/releases/latest)

PlatinMarket stores get custom CSS and JS only by pasting code into the admin panel. There is no
backup, no history and no validation, and the panel can corrupt code on save. Panel Controller:

- backs up the live files
- builds the small source pieces into the files the panel accepts
- simulates what the panel does on save and stops at the first problem
- compares local files with the live site and shows which file to paste, in which order
- copies an earlier live version back to the clipboard
- commits and pushes the project with git (refuses to push when build or validation is not clean)

**Uploading stays manual.** The PlatinMarket API (Reform API) has no endpoint for the theme CSS and JS
files, so the app cannot send a file to the panel. It copies the files to the clipboard in order.

> **Status: 0.7 beta.** Interface and user guide in Turkish and English (Settings → Language).

## Install

Download `Panel Controller-<version>.exe` from
[Releases](https://github.com/wreazy-cs/panel-controller/releases) and run it. It installs per user
(no administrator rights), adds desktop and Start menu shortcuts and brings its own Java.
Git, Node.js and Python are optional; Settings → System check shows what is missing.

First start: Settings → choose the project folder (the one that contains `kaynak/`), enter the store's
CDN address (`https://platincdn.com/<store no>/tema/%s`), Save.

## The window

| Page | |
|---|---|
| Publish | Status cards, Prepare (backup + build + validate), file table in paste order, upload wizard |
| Restore | Backups, newest first; copy a file back to the clipboard |
| Git | Pick changed files, commit, push |
| Settings | Project folder, CDN address, limits, language, theme, system check |
| About | What the app does, release notes, licence |

Results appear as cards in the Activity panel. Problems carry a code (`PC-101` ...); clicking it opens
the user guide at that code. Shortcuts: `F5` refresh and compare, `F1` guide, `Ctrl+1` ... `Ctrl+5` pages.

## Build

Requirements: JDK 25 or newer, Maven.

```
mvn package
powershell -ExecutionPolicy Bypass -File create-shortcut.ps1      # developer shortcut, runs from target/
powershell -ExecutionPolicy Bypass -File build-installer.ps1      # dist\Panel Controller-<version>.exe
```

`build-installer.ps1` needs WiX Toolset 3.14 on the PATH (`-Type app-image` works without it).
Do not run `mvn package` while the developer build is open: it replaces the jar under the running app.

Texts live in `src/main/resources/i18n/messages_tr.properties` and `messages_en.properties`, the guide in
`guide/guide_tr.html` and `guide_en.html`, release notes in `release-notes_tr.txt` and `release-notes_en.txt`.
`PanelApp.EXTRA_PAGES` turns the hidden pages (Tasks, Tools, API, API key) back on.

## Project layout

```
<root>/kaynak/<slot>/*.css|*.js   source pieces (first line: /*@vt <no> | <title> | ezer: <what it overrides> */)
<root>/cikti/                     files to paste into the panel
<root>/veri/yedek/<stamp>/        backups of the live files
```

## Command line

```
java -jar target/panel-controller.jar --root <project dir> --config <file> <command>
```

| Command | What it does |
|---|---|
| `prepare` | Backup + build + validate; stops at the first failure, then prints the paste order |
| `build [--readable\|--commented] [slot...]` | Builds the panel files |
| `validate` | Panel simulation: HTML entities, windows-1254, budget, JS syntax, CSS balance, comments |
| `check` | `build` + `validate` |
| `backup` | Downloads the live files |
| `compare [--dir d]` | Compares local files with the live CDN |
| `order` | Files to paste, in order |
| `status` | Slot sizes, output state, backup age, git state |
| `backups` | Lists backups |
| `copy <file>` | Copies an output file to the clipboard |
| `restore <stamp\|latest> <file>` | Copies a backed-up file to the clipboard |
| `version` | Version and release notes |

The CDN address comes from a properties file (`--config`, see
[panel-controller.properties.example](panel-controller.properties.example)).

---

## Türkçe

PlatinMarket mağaza temaları için kontrol ve git aracı (resmî değildir). Samet Şafak tarafından geliştirildi.

PlatinMarket'te özel CSS ve JS yalnızca yönetim paneline kod yapıştırarak eklenir. Yedek, geçmiş ve
denetim yoktur; panel kaydederken kodu bozabilir. Panel Controller:

- canlı dosyaları yedekler
- küçük kaynak parçalarını panelin kabul ettiği dosyalara derler
- panelin kayıtta yaptığını taklit eder, ilk sorunda durur
- yerel dosyaları canlıyla karşılaştırır, hangi dosyanın hangi sırayla yapıştırılacağını gösterir
- eski bir canlı sürümü panoya geri kopyalar
- projeyi git ile commit edip gönderir (derleme veya doğrulama temiz değilse göndermez)

**Yükleme elle yapılır.** PlatinMarket API'sinde (Reform API) tema CSS ve JS dosyaları için uç nokta
yoktur; uygulama panele dosya gönderemez, dosyaları sırayla panoya kopyalar.

**Kurulum:** [Releases](https://github.com/wreazy-cs/panel-controller/releases) sayfasından
`Panel Controller-<sürüm>.exe` dosyasını indirip çalıştırın. Yönetici izni ve Java gerekmez.
İlk açılışta Ayarlar → proje klasörünü seçin (`kaynak/` klasörünü içeren), mağaza CDN adresini girin, Kaydedin.
Dil Ayarlar'dan seçilir; uygulama yeniden başlatılınca uygulanır.

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). Montserrat font: SIL Open Font
License 1.1 ([OFL.txt](src/main/resources/fonts/OFL.txt)).

"PlatinMarket" is a trademark of its respective owner. This project is independent and has no
official connection to it.
