# Sürüm notları

## 1.1.1-SNAPSHOT - sibling product links (2026-10-05; source snapshot)

- Add optional protocol-1 Desperis sibling links, opt-in durable per-player language sync and source/event-specific live milestones.
- Add configurable product-specific cooperation and conflict rules; preserve standalone ownership, money receipts, lowest Paper API and existing custom settings.
- Add a documented feature/default matrix and compact three-version verification scope. No new release tag or GitHub Release; live achievement observations have no distributed delivery guarantee. Current verification: [compatibility](docs/COMPATIBILITY.md), [sanitized proof](docs/verification.json).


## 1.1.1-SNAPSHOT - geliştirme kaynağı düzeltmeleri

- Towny/GriefPrevention/WorldGuard/Lands/BentoBox/SuperiorSkyblock2 yerel koruma denetimleri, sağlayıcı/dünya/işlem ayarları ve hatalı yeniden yüklemede etkin ayarların korunması eklendi; config yorumları ve Desperis ürün başlıkları genişletildi.

- MySQL sütun/indeks geçişleri bağlı veritabanıyla sınırlandırılır; başka veritabanındaki şema geçişi yükseltmeyi engellemez.
- Ödeme sürerken süresi dolan ilanda teklif/satış/offer SQL aşamasında reddedilir; devam eden iade aynı işlem geleceğiyle beklenir.
- Başarılı teklif, satış ve claim istatistikleri yeniden kaydedilir.

- Para, teklif, satış, iptal ve claim değişiklikleri SQL durum koşullarıyla uygulanır; teslimatlar oyuncu/ilan bazında sıralanır.
- Aynı claim, iptal, teklif yanıtı veya yönetici iadesi ikinci para/eşya teslimatı oluşturamaz.
- Tam eşya kapasitesi yoksa teslim kutusu envantere hiçbir parça eklemez. Teslim kaydı koşullu olarak sahiplenilir.
- Elde tutulan slot/eşya ve ilan sınırı işlem sırasında tekrar doğrulanır; ücret reddedilen/başarısız ilanda telafi edilir.
- SQL işlemine bağlı borç makbuzları ve kredi kuyruğu; başarısız insert için kalıcı eşya emanet kaydı.
- Ekonomi sağlayıcısının sonucu belirsizse otomatik tekrar yerine yönetici incelemesi; açık ödeme incelemesi olan oyuncu için yeni borçlandırma engeli.
- Tüm aktif işlem yollarında süre/durum denetimi; sonlu tutar ve doğrudan `long` ilan kimliği doğrulaması.
- 13 dilde pencere başlığı ve oyuncuya özel yenileme; eski asenkron menü sonucunun kapatılmış/yeni ekranı açmasının engellenmesi.
- Bukkit/Vault işlemleri sunucu iş parçacığında; SQLite tek bağlantı, WAL ve bekleme süresi; teslim/ödeme şema geçişi.
- Menü sürükleme koruması, güvenli boyut/slot sınırları, atomik dil dosyası kaydı ve bozuk YAML denetimi.

## 1.1.0 — 3 Ekim 2026

- Paper 1.20.6–26.2 desteği; Paper 1.20.6 API, Java 21 bytecode ve `api-version: '1.20.6'` korunur.
- Oyuncuya özel, kalıcı dil tercihi; başka bir oyuncunun tercihini değiştirmez.
- Ortak `menu`, `help`, `language [kod]`, `reload` komutları; `lang` ve `locale` takma adları.
- Dil kodu verilmeden mevcut dil ve seçenekleri görüntüleme; kısa/bölge/tireli kod desteği; geçersiz kodda tercihi koruma.
- Konsoldan help/reload ve yetkiye göre tab tamamlama.
- Mevcut özelleştirilmiş dil dosyalarını koruyarak eksik mesajları paket içindeki çevirilerden tamamlama.
- Eski ses adları ve registry ses anahtarlarını destekleyen sürüm uyumlu ses çözümü.
- Mevcut UUID tabanlı `player-locales.yml` tercihleri korunur. `/ah locale tr_TR` ve `/ah admin reload` çalışmaya devam eder.
- Yeni eşya kayıtları DataVersion içeren `nbt:` biçiminde yazılır; eski satır sonlu Base64/Bukkit kayıtları okunur.
- Vault bulunmadığında eklenti açılır; ekonomi sağlayıcısı gerektiren işlemler kapalı kalır.

Altı gerçek Paper sürümünde üç eklentiyi birlikte kapsayan 43 kontrol/sürüm geçti. Ayrıntılar: [docs/COMPATIBILITY.md](docs/COMPATIBILITY-RELEASE-1.1.0.md).
