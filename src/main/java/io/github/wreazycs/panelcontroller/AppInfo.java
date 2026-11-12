package io.github.wreazycs.panelcontroller;

import java.time.LocalDate;
import java.util.List;

/**
 * Name, version, author and release notes: the single source for the About page, the release
 * notes page, the window title and the {@code version} command.
 *
 * <p>Rule: every change that alters behaviour bumps {@link #VERSION} (semantic versioning, beta
 * {@code 0.MINOR.PATCH}: new feature = MINOR, fix only = PATCH) and adds a release at the top of
 * {@link #RELEASES}, in the same commit.
 */
public final class AppInfo {
    public static final String NAME = "Panel Controller";
    public static final String VERSION = "0.5.0";
    public static final String STAGE = "beta";
    public static final String AUTHOR = "Samet Şafak";
    public static final String GITHUB = "https://github.com/wreazy-cs/panel-controller";
    public static final String TAGLINE = "PlatinMarket mağazaları için yayın, yedek ve kontrol aracı";

    public static final List<String> PURPOSE = List.of(
            "PlatinMarket, mağazalara yalnızca CSS ve JS ekleme izni veriyor. Kod panele elle yapıştırılıyor; panelde sürüm "
                    + "geçmişi, yedek, geri alma ve kod doğrulama yok.",
            "Panel kaydederken kodu bozabiliyor: HTML karakter kodlarını çözüyor, Türkçe karakterleri tek bayta indiriyor "
                    + "(03.08.2026'da bunun yüzünden sitenin tüm JS'i çalışmadı). Dosyalar bir yıl önbelleklendiği için yüklenen "
                    + "değişiklik görünmeyebiliyor.",
            "Panel Controller bu açığı kapatır: kodu küçük parçalar halinde yazdırıp birleştirir, panele gitmeden önce panelin "
                    + "yapacağı bozmaları taklit ederek doğrular, yüklemeden önce canlı halin yedeğini alır, hangi dosyanın "
                    + "yüklenmesi gerektiğini ve sırasını gösterir, bozulursa eski yedeğe hızlıca döndürür ve değişiklikleri git "
                    + "ile kaydeder.");

    public record Release(String version, LocalDate date, String title,
                          List<String> added, List<String> changed, List<String> fixed) {}

    /** Newest first. */
    public static final List<Release> RELEASES = List.of(
            new Release("0.5.0", LocalDate.of(2026, 11, 13), "Yeni arayüz ve Python Kumanda'nın iş akışı",
                    List.of(
                            "Sol menülü yeni arayüz: Yayın, Geri dön, Git, Görevler, Araçlar, Sürüm notları, Hakkında, Ayarlar.",
                            "Hazırla: yedek, derle ve doğrula tek tıkla, adım göstergesiyle; bir adım başarısız olursa durur.",
                            "Yayın tablosu: dosyalar yapıştırma sırasıyla (önce sayfa slotları, en son genel), canlıyla AYNI/FARKLI rozetiyle.",
                            "Yükleme sihirbazı: canlıdan farklı dosyaları sırayla panoya alır, her adımda ne yapılacağını söyler, "
                                    + "bitince canlıyı yeniden kontrol eder.",
                            "Git sayfası: değişen dosyaları seçip commit; derle ve doğrula temiz değilse push etmez. "
                                    + "Commit'ler -p ekiyle ve Panel Controller bağlantısıyla gider.",
                            "Görevler (tasks/), sayfa önizleme, test tezgahı ve oturum notu.",
                            "İşlem kartları: düz günlük yerine her işlemin sonucu, hatalar kod numarasıyla.",
                            "Hata kodları (PC-101 ...) ve uygulama içinde aranabilir kullanım kılavuzu.",
                            "Açık ve koyu tema.",
                            "Hakkında ve Sürüm notları sayfaları."),
                    List.of(
                            "Ayar dosyası kaldırıldı: mağaza CDN adresi ve sınırlar Ayarlar sayfasında, her proje için ayrı tutulur "
                                    + "(komut satırındaki --config sürüyor).",
                            "API anahtarı ve PIN işlemleri Ayarlar sayfasına taşındı.",
                            "Java 25 ve JavaFX 25 gerekiyor (kılavuzun tarayıcı bileşeni yeni Java sürümlerinde bunu istiyor).",
                            "Jar dosyasının adı sürümden bağımsız: target/panel-controller.jar.",
                            "Geri al: 'en son' yedek, o dosyayı içeren en yeni yedeği bulur."),
                    List.of(
                            "JS sözdizimi denetimi artık dosyanın panel kaydettikten sonraki halini (windows-1254) denetliyor; "
                                    + "Python sürümündeki davranış.")),
            new Release("0.4.0", LocalDate.of(2026, 11, 13), "Logo ve masaüstü kısayolu",
                    List.of("Uygulama logosu (pencere, görev çubuğu, diyaloglar).",
                            "Konsol penceresi açmayan masaüstü kısayolu (create-shortcut.ps1)."),
                    List.of(), List.of("Proje klasöründe kaynak yokken 'Derleme: güncel' yazması.")),
            new Release("0.3.0", LocalDate.of(2026, 11, 13), "PIN korumalı API anahtarı",
                    List.of("API anahtarı bellekte ya da isteğe bağlı olarak PIN ve Windows DPAPI ile korunan dosyada.",
                            "Açılışta kilit ekranı; 5 yanlış PIN'de kayıtlı anahtar silinir."),
                    List.of(), List.of()),
            new Release("0.2.0", LocalDate.of(2026, 11, 12), "İlk pencere",
                    List.of("JavaFX penceresi: durum kartları, slot tablosu, işlem düğmeleri.", "status komutu."),
                    List.of(), List.of()),
            new Release("0.1.0", LocalDate.of(2026, 11, 12), "Java'ya taşıma",
                    List.of("Derle, doğrula, yedek al, karşılaştır, geri al komutları. Derleme çıktısı Python sürümüyle bayt bayt aynı."),
                    List.of(), List.of()));

    /** The Python predecessor's history (Panel Kumanda, IC Elektronik repository). */
    public static final List<String> PREDECESSOR = List.of(
            "0.0.1 (19.09.2026) derle.py, dogrula.py, yedek-al.py, cdn-karsilastir.py: derleme hattı.",
            "0.0.2 (20.09.2026) tek tıkla yayın hazırlığı; CDN önbelleğini atlama.",
            "0.0.3 (22.09.2026) yerel test tezgahı.",
            "0.0.5 (26.09.2026) panel kopyası yorumsuz ve sıkıştırılmış; slotlara paylaştırma.",
            "0.1.0 – 0.4.1 (11.11.2026) Panel Kumanda penceresi: durum kartları, sıralı kopyalama, git, görevler, API denemesi.");

    private AppInfo() {}

    public static String title() {
        return NAME + " " + VERSION + " " + STAGE;
    }

    public static String credit() {
        return AUTHOR + " tarafından geliştirildi";
    }
}
