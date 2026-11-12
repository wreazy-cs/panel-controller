package io.github.wreazycs.panelcontroller;

/**
 * Every problem the app can report, with a stable code (shown in the app and explained in the
 * user guide's error table, which is generated from this list).
 */
public enum Problem {
    // ---- build (kaynak/ -> cikti/)
    JS_NON_ASCII("PC-101", Severity.ERROR, "JS kaynağında ASCII dışı karakter",
            "Panel dosyayı tek baytlık kodlamayla saklıyor ve HTML karakter kodlarını çözüyor. JS içindeki Türkçe harf ya da özel "
                    + "işaret kaydederken bozulabilir; bozuk bir JS dosyası sitedeki tüm betikleri durdurur.",
            "Harfi \\uXXXX kaçışıyla yaz (ç = \\u00e7, ğ = \\u011f, ı = \\u0131, ö = \\u00f6, ş = \\u015f, ü = \\u00fc)."),
    HTML_ENTITY("PC-102", Severity.ERROR, "Kaynakta HTML karakter kodu (entity)",
            "Panel kaydederken &#39; gibi kodları gerçek karaktere çeviriyor. JS içinde bu bir tırnağı kapatıp dosyayı bozar "
                    + "(03.08.2026'da sitenin tüm JS'i bu yüzden çalışmadı).",
            "Kodu kaldır: karakteri doğrudan yaz, JS'te \\u kaçışını kullan."),
    NOT_CP1254("PC-103", Severity.ERROR, "Panelin saklayamayacağı karakter",
            "Panel windows-1254 (Türkçe tek bayt) kullanıyor. ≤, →, emoji gibi karakterler kaydedince '?' olur.",
            "≤ yerine <=, → yerine -> yaz; emoji ve özel tırnakları kaldır."),
    META_MISSING("PC-104", Severity.ERROR, "Parçanın ilk satırında meta satırı yok",
            "Her kaynak parçası /*@vt <no> | <başlık> | ezer: <neyin üstüne yazıyor> */ satırıyla başlamalı. Numara, kuralın hangi "
                    + "parçadan geldiğini bulmak için kullanılır.",
            "Dosyanın en üstüne meta satırı ekle; numara diğer parçalarla çakışmasın."),
    DUPLICATE_NUMBER("PC-105", Severity.ERROR, "Aynı parça numarası iki kez kullanılmış",
            "Parça numaraları (IC-VT-<no>) tüm slotlarda tek olmalı, yoksa arama yanlış parçayı gösterir.",
            "Parçalardan birine kullanılmayan bir numara ver."),
    JS_NO_SEMICOLON("PC-106", Severity.ERROR, "JS parçası ';' ile bitmiyor",
            "Parçalar birleştirilince sondaki ifade bir sonraki parçanın başıyla birleşip hataya dönüşebilir.",
            "Parçanın son ifadesinin sonuna ; koy."),
    JS_MULTILINE("PC-107", Severity.ERROR, "JS'te satıra yayılan metin ya da ters tırnak",
            "Panel kopyasında satır başlarındaki boşluklar silinir. Birden çok satıra yayılan bir metin ya da `...` şablonu bu "
                    + "yüzden değişir.",
            "Metni tek satırda tut ya da + ile birleştir; ters tırnak yerine tek/çift tırnak kullan."),
    CSS_COMPRESS_MISMATCH("PC-108", Severity.ERROR, "CSS sıkıştırma denetimi tutmadı",
            "Sıkıştırılmış CSS yeniden okununca kaynakla aynı kuralları vermedi. Bu bir güvenlik kilidi: anlamı değişebilecek bir "
                    + "çıktı panele gitmesin diye derleme durur.",
            "Parçadaki alışılmadık yazımı (iç içe yorum, kapanmamış tırnak) düzelt. Sorun sürerse hatayı bildir."),
    CSS_PARSE("PC-109", Severity.ERROR, "CSS okunamadı",
            "Kapanmayan bir { ya da bitmemiş bir ifade var.",
            "Parçadaki süslü parantezleri ve tırnakları eşleştir."),
    COMMENT_LEFT("PC-110", Severity.ERROR, "Panel kopyasında yorum kaldı",
            "Tema dosyaları CDN'den herkese açık okunuyor; açıklamalar yalnızca kaynakta kalmalı.",
            "Hatayı bildir: derleyicinin yorum silmesi bu dosyada çalışmamış."),
    BUDGET_EXCEEDED("PC-111", Severity.ERROR, "Toplam karakter bütçesi aşıldı",
            "Panelin toplam kayıt sınırı yaklaşık 355.000 karakter. Aşılırsa panel kaydetmeyebilir.",
            "Kullanılmayan kuralları sil ya da sayfaya özel kodu sayfa slotlarına taşı."),
    SLOT_OVER_TARGET("PC-112", Severity.WARNING, "Slot hedefi aşıldı",
            "Slot başına hedef 65.000 karakter. Panelin bunu zorladığı doğrulanmadı; uyarıdır.",
            "Mümkünse sayfaya özel kodu ilgili sayfa slotuna taşı."),

    // ---- panel simulation (cikti/)
    OUT_HTML_ENTITY("PC-201", Severity.ERROR, "Çıktıda HTML karakter kodu",
            "Panel bu kodu çözüp dosyayı bozar (bkz. PC-102).",
            "Kaynaktaki kodu kaldırıp yeniden derle."),
    OUT_NOT_CP1254("PC-202", Severity.ERROR, "Çıktıda panelin saklayamayacağı karakter",
            "Kaydedince '?' olacak (bkz. PC-103).",
            "Kaynakta karakteri değiştirip yeniden derle."),
    OUT_JS_SYNTAX("PC-203", Severity.ERROR, "JS sözdizimi hatalı",
            "Node.js dosyanın panel kaydettikten sonraki halini denetledi ve hata buldu. Bu dosya yüklenirse sitedeki betikler çalışmaz.",
            "Hatanın satırına bak, kaynağı düzeltip yeniden derle."),
    OUT_COMMENT("PC-204", Severity.ERROR, "Çıktıda yorum var",
            "Dosyalar CDN'den herkese açık okunuyor (bkz. PC-110).",
            "Yeniden derle; sürerse hatayı bildir."),
    OUT_BRACES("PC-205", Severity.ERROR, "CSS süslü parantezleri dengesiz",
            "{ ve } sayısı eşit değil; bir kuraldan sonrası tümüyle yok sayılabilir.",
            "Kaynağı düzeltip yeniden derle."),
    OUT_COMMENT_MARKERS("PC-206", Severity.ERROR, "CSS yorum işaretleri dengesiz",
            "/* ve */ sayısı eşit değil; dosyanın geri kalanı yorum sanılabilir.",
            "Kaynağı düzeltip yeniden derle."),
    OUT_JS_NON_ASCII("PC-207", Severity.WARNING, "Çıktıdaki JS'te ASCII dışı karakter",
            "Yorum içindeyse sorun değil; metin içindeyse panel bozabilir.",
            "Metin içindeyse \\u kaçışı kullan."),
    NODE_MISSING("PC-208", Severity.WARNING, "Node.js bulunamadı",
            "JS sözdizimi denetimi atlandı; diğer denetimler yapıldı.",
            "Node.js kur (nodejs.org) ve uygulamayı yeniden başlat."),
    OUTPUT_EMPTY("PC-209", Severity.ERROR, "Çıktı klasörü boş",
            "Doğrulanacak dosya yok.",
            "Önce Derle."),

    // ---- live site / backup
    CDN_NOT_SET("PC-301", Severity.ERROR, "Mağaza CDN adresi girilmemiş",
            "Yedek ve canlı karşılaştırma için canlı dosyaların adresi gerekiyor.",
            "Ayarlar > Proje > Mağaza CDN adresi alanını doldur (örnek: https://platincdn.com/1234/tema/%s)."),
    CDN_UNREADABLE("PC-302", Severity.ERROR, "Canlı dosya okunamadı",
            "CDN'den yanıt gelmedi ya da hata döndü. İnternet bağlantısı, adres ya da CDN sorunu olabilir.",
            "İnternet bağlantısını ve Ayarlar'daki CDN adresini kontrol et, sonra yeniden dene."),
    BACKUP_INCOMPLETE("PC-303", Severity.ERROR, "Yedek eksik",
            "Bazı canlı dosyalar indirilemedi. Yedeksiz yükleme yapılırsa bir sorun çıktığında geri dönülemez.",
            "Bağlantıyı düzeltip yeniden yedek al; yedek tamamlanmadan panele yükleme."),
    LIVE_DIFFERENT("PC-304", Severity.INFO, "Canlıdan farklı",
            "Yerel çıktı canlıdakiyle aynı değil: ya henüz yüklenmedi ya da önbellek eski sürümü gösteriyor.",
            "Yükleme sihirbazıyla yükle. Yükledikten sonra hâlâ farklıysa önce önbelleği şüphelen (Ctrl+Shift+R, gizli sekme)."),
    LOCAL_MISSING("PC-305", Severity.INFO, "Yerelde yok, canlıda var",
            "Canlıda içerik olan bir slotun yerel çıktısı yok.",
            "Slot gerçekten boşalacaksa panelde de boşalt; değilse kaynakları kontrol et."),

    // ---- git
    NOT_A_REPO("PC-401", Severity.ERROR, "Proje bir git deposu değil",
            "Commit ve push için proje klasörünün git ile izlenmesi gerekiyor.",
            "Klasörü git ile başlat ya da doğru proje klasörünü seç."),
    NOTHING_SELECTED("PC-402", Severity.ERROR, "Commit için dosya seçilmedi",
            "Hangi değişikliklerin commit'e gireceği seçilmeli.",
            "Listeden en az bir dosya işaretle."),
    EMPTY_MESSAGE("PC-403", Severity.ERROR, "Commit mesajı boş",
            "Mesaj, değişikliğin neden yapıldığını anlatır; tarih ve saat otomatik eklenir.",
            "Kısa bir açıklama yaz."),
    COMMIT_FAILED("PC-404", Severity.ERROR, "Commit yapılamadı",
            "git commit hata döndürdü. Ayrıntılı çıktıda nedeni yazar (örneğin kullanıcı adı ayarlı değil).",
            "Ayrıntılı çıktıya bak; gerekirse git config user.name / user.email ayarla."),
    PUSH_BLOCKED("PC-405", Severity.ERROR, "Push durduruldu: derleme ya da doğrulama temiz değil",
            "Hatalı kod GitHub'a gitmesin diye push öncesi derle ve doğrula çalışır; biri başarısız olunca push yapılmaz.",
            "İşlem kartındaki hatayı düzeltip yeniden dene."),
    PUSH_FAILED("PC-406", Severity.ERROR, "Push yapılamadı",
            "GitHub değişikliği kabul etmedi ya da bağlantı kurulamadı (yetki, ağ ya da uzakta yeni commit var).",
            "Ayrıntılı çıktıya bak. 'rejected' yazıyorsa önce uzak değişiklikleri çekmek gerekir."),
    GIT_MISSING("PC-407", Severity.ERROR, "git bulunamadı",
            "Bilgisayarda git kurulu değil ya da PATH'te değil.",
            "git-scm.com'dan git'i kur."),

    // ---- key vault
    WRONG_PIN("PC-501", Severity.WARNING, "Yanlış PIN",
            "Girilen PIN kayıtlı anahtarı açmadı. Her yanlış giriş sayılır.",
            "Doğru PIN'i gir. Unuttuysan kilit ekranındaki 'PIN'imi unuttum' ile anahtarı silip yeniden gir."),
    VAULT_WIPED("PC-502", Severity.WARNING, "5 yanlış PIN: kayıtlı anahtar silindi",
            "Güvenlik için kayıtlı anahtar silindi.",
            "Ayarlar > API anahtarı ile anahtarı yeniden gir."),
    VAULT_UNREADABLE("PC-503", Severity.ERROR, "Kayıtlı anahtar açılamıyor",
            "Dosya başka bir Windows hesabından ya da bilgisayardan kopyalanmış veya bozulmuş.",
            "Kayıtlı anahtarı silip yeniden gir."),

    // ---- tools
    PYTHON_MISSING("PC-601", Severity.ERROR, "Python bulunamadı",
            "Projenin test tezgahı betiği Python ile yazılmış.",
            "Python'u kur (python.org) ya da tezgahı kullanmadan önizlemeyi kullan."),
    BENCH_SCRIPT_MISSING("PC-602", Severity.ERROR, "Projede test tezgahı betiği yok",
            "Tezgah sayfası projeye özel; arac/tezgah-yap.py bekleniyor.",
            "Sunucu yine çalışır: kaynak dosyalarına http://127.0.0.1:8732/ altından erişebilirsin."),
    PORT_IN_USE("PC-603", Severity.ERROR, "Yerel sunucu portu kullanımda",
            "8732 numaralı port başka bir program tarafından kullanılıyor (eski bir tezgah sunucusu olabilir).",
            "Diğer programı kapat ya da bilgisayarı yeniden başlat."),
    UNEXPECTED("PC-901", Severity.ERROR, "Beklenmeyen hata",
            "Uygulamada öngörülmemiş bir durum oluştu. Ayrıntı hata günlüğüne yazıldı.",
            "%LOCALAPPDATA%\\PanelController\\error.log dosyasını ekleyerek bildir.");

    public enum Severity { ERROR, WARNING, INFO }

    private final String code;
    private final Severity severity;
    private final String title;
    private final String meaning;
    private final String fix;

    Problem(String code, Severity severity, String title, String meaning, String fix) {
        this.code = code;
        this.severity = severity;
        this.title = title;
        this.meaning = meaning;
        this.fix = fix;
    }

    public String code() { return code; }
    public Severity severity() { return severity; }
    public String title() { return title; }
    public String meaning() { return meaning; }
    public String fix() { return fix; }
}
