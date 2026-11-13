<p align="center"><img src="docs/logo.svg" width="96" alt="Panel Controller logo"></p>

# Panel Controller

Panel Controller is a Windows tool for stores that run on PlatinMarket. It checks the theme's CSS and
JS files before they are pasted into the admin panel and keeps the project's changes in git. It is an
independent project and is not affiliated with PlatinMarket.

[Türkçe](#türkçe)

## Why

PlatinMarket only accepts custom CSS and JS as text pasted into the admin panel. The panel keeps no
backup or history, and on save it can change characters in the code, which breaks the site without
any warning. This tool takes the risky parts out of that routine.

Before an upload it downloads a backup of the live files, joins the source pieces in `kaynak/` into the
files the panel expects, and then checks those files the way the panel would treat them. If anything
would break, it stops and tells you which file and why. It also compares your files with the ones on
the live site, so you only paste what has actually changed, in the right order. When something goes
wrong after an upload, you can copy an older live version from a backup and paste it back. Finally,
the Git page commits and pushes the project, and it refuses to push while the build or the check
reports a problem.

The upload itself is still done by hand. PlatinMarket's API (the Reform API) has no endpoint for theme
CSS and JS files, so there is no way for the app to send them to the panel. It copies each file to the
clipboard instead and tells you where to paste it.

## Install

Download the latest `.exe` from the [Releases](https://github.com/wreazy-cs/panel-controller/releases/latest)
page and run it. It installs for the current user, so you do not need administrator rights, and it
comes with its own Java. Git, Node.js and Python are optional. Settings has a system check that shows
which of them are installed and what each one is used for.

On first start, open Settings, choose the project folder (the one that contains `kaynak/`), enter the
store's CDN address in the form `https://platincdn.com/<store no>/tema/%s` and save. The interface and
the user guide are available in Turkish and English; the language is also chosen in Settings and takes
effect after a restart.

## Using it

The app has five pages. Publish is where the daily work happens: it shows the state of the backup, the
live site, the build and git, runs the checks and lists the files in the order they should be pasted.
Restore lists the backups. Git lets you pick changed files, write a message, commit and push. Settings
holds the project folder, the CDN address, the size limits, the language and the theme. About shows
the version and the release notes.

Every result appears as a card in the Activity panel on the right. Problems have a code such as
`PC-112`; clicking the code opens the user guide at the explanation. `F5` refreshes and compares with
the live site, `F1` opens the guide and `Ctrl+1` to `Ctrl+5` switch between the pages.

## Building from source

You need JDK 25 or newer and Maven.

```
mvn package
powershell -ExecutionPolicy Bypass -File create-shortcut.ps1
powershell -ExecutionPolicy Bypass -File build-installer.ps1
```

The first command builds the app into `target/`, the second puts a desktop shortcut to that build on
your desktop, and the third builds the installer into `dist/`. The installer needs WiX Toolset 3.14 on
the PATH; with `-Type app-image` it builds a runnable folder without WiX. Close the app before running
`mvn package`, because the build replaces the jar the running app is using.

The interface texts are in `src/main/resources/i18n`, the user guide in `src/main/resources/guide` and
the release notes in `release-notes_tr.txt` and `release-notes_en.txt`. Some earlier pages (tasks,
tools, API) are hidden in this version; setting `PanelApp.EXTRA_PAGES` to `true` brings them back.

## Command line

The same checks can be run without the window:

```
java -jar target/panel-controller.jar --root <project folder> --config <file> <command>
```

`prepare` does the whole routine (backup, build, check) and prints the paste order. `build`,
`validate`, `check`, `backup` and `compare` run the steps one at a time. `order` prints the paste order,
`status` shows file sizes and the state of the project, `backups` lists the backups, `copy <file>` and
`restore <backup> <file>` put a file on the clipboard, and `version` prints the version. The CDN address
is read from a properties file; see
[panel-controller.properties.example](panel-controller.properties.example).

## Türkçe

Panel Controller, PlatinMarket altyapısını kullanan mağazalar için bir Windows aracıdır. Temanın CSS ve
JS dosyalarını yönetim paneline yapıştırılmadan önce kontrol eder ve projedeki değişiklikleri git ile
kaydeder. PlatinMarket ile resmî bir bağı yoktur.

Yüklemeden önce canlı dosyaların yedeğini alır, `kaynak/` klasöründeki parçaları panelin beklediği
dosyalara birleştirir ve bu dosyaları panelin kayıt sırasında yapacağı değişikliklere göre denetler.
Bir sorun varsa hangi dosyada ve neden olduğunu söyleyip durur. Yerel dosyaları canlı sitedekilerle de
karşılaştırır; böylece yalnızca değişen dosyaları doğru sırayla yapıştırırsınız. Panele yükleme elle
yapılır, çünkü PlatinMarket API'sinde (Reform API) tema CSS ve JS dosyaları için bir uç nokta yoktur.

Kurmak için [Releases](https://github.com/wreazy-cs/panel-controller/releases/latest) sayfasındaki
`.exe` dosyasını indirip çalıştırın. Yönetici izni ve ayrıca Java gerekmez. İlk açılışta Ayarlar'dan
proje klasörünü seçip mağazanın CDN adresini girin. Arayüz dili de Ayarlar'dan değiştirilir.

## License

Copyright 2026 Samet Şafak. Licensed under the Apache License 2.0; see [LICENSE](LICENSE) and
[NOTICE](NOTICE). The bundled Montserrat font is licensed under the SIL Open Font License 1.1
([OFL.txt](src/main/resources/fonts/OFL.txt)).

PlatinMarket is a trademark of its owner.
