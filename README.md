<p align="center"><img src="docs/logo.svg" width="96" alt="Panel Controller logo"></p>

# Panel Controller

Checks a PlatinMarket store's theme CSS and JS before they are pasted into the admin panel, and sends
the changes to git.

The upload is done by hand. PlatinMarket's API has no endpoint for theme files, so the app copies each
file to the clipboard and shows where to paste it.

## Install

Download the `.exe` from [Releases](https://github.com/wreazy-cs/panel-controller/releases/latest) and run
it. No administrator rights or Java needed.

On first start the app asks for two things, and the other pages stay closed until both are set:

1. the project folder (the one that contains `kaynak/`)
2. the address of the store's website, for example `example.com`. The app finds the CDN address in the page.
   If the store is closed to visitors (B2B), it asks for the CDN address instead:
   `https://platincdn.com/<store no>/tema/%s`

## Use

1. **Publish → Prepare**: backs up the live files, builds them and checks them.
2. Files that differ from the live site have an eye button that shows the changed lines. Paste the
   files into the panel in the order shown, then **Compare with live**.
3. **Git**: commit and push.

If something breaks, **Restore** copies an older live version to the clipboard.

## Build

JDK 25+ and Maven.

```
mvn package                                                   # app in target/
powershell -ExecutionPolicy Bypass -File build-installer.ps1  # installer in dist/ (needs WiX 3.14)
```

## Türkçe

PlatinMarket mağazasının tema CSS ve JS dosyalarını panele yapıştırmadan önce denetler ve değişiklikleri
git'e gönderir. Panele yükleme elle yapılır, çünkü PlatinMarket API'sinde tema dosyaları için uç nokta yok.

[Releases](https://github.com/wreazy-cs/panel-controller/releases/latest) sayfasından `.exe` dosyasını
indirip çalıştırın. İlk açılışta proje klasörü ve mağazanın site adresi istenir; uygulama CDN adresini
sayfadan kendisi bulur. Site ziyaretçilere kapalıysa (B2B) CDN adresi elle girilir. İkisi tamamlanmadan
diğer sayfalar açılmaz.

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). Montserrat font: SIL Open Font License 1.1.

Made by [wreazy-cs](https://github.com/wreazy-cs).
