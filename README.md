# AuctionHousePro

AuctionHousePro, Paper ve Spigot 1.20+ sunucuları için geliştirilmiş modern bir açık artırma eklentisidir. Oyuncular eşya listeleyebilir, teklif verebilir, hemen satın al kullanabilir, süresi dolan veya satılan açık artırmaları teslim alabilir ve kendi istemci dili için locale seçebilir.

Eklenti; SQLite ve MySQL desteği, Vault tabanlı ekonomi entegrasyonu, Discord webhook bildirimleri, çoklu dil sistemi, GUI odaklı kullanım ve diğer eklentilerin entegre olabilmesi için basit bir API/Event yüzeyi sunar.

## Öne Çıkan Özellikler

- GUI tabanlı açık artırma tarayıcısı, oyuncu ilanları ve claim ekranları
- Teklif verme ve buy now akışlarını aynı sistemde kullanabilen hibrit açık artırmalar
- Anti-snipe koruması ile son saniye tekliflerinde süre uzatma
- Listeleme ücreti, vergi ve komisyon ayarları
- Maksimum aktif ilan sınırı, arama sonucu limiti ve cooldown kontrolleri
- Kara liste / beyaz liste ve NBT anahtar kısıtları ile eşya filtreleme
- SQLite ve MySQL veritabanı desteği
- Vault ekonomi entegrasyonu
- Discord webhook üzerinden nadir ilan ve yüksek satış bildirimleri
- Oyuncu bazlı dil seçimi ve çoklu locale desteği
- Diğer eklentiler için API ve Bukkit event sınıfları

## Gereksinimler

- Java 21
- Paper veya Spigot 1.20+
- Vault
- Vault ile uyumlu bir ekonomi eklentisi

Not: Vault ekonomi sağlayıcısı bulunamazsa eklenti yüklenir ancak para gerektiren işlemler fiilen kullanılamaz.

## Desteklenen Diller

Varsayılan olarak aşağıdaki locale dosyaları paketle gelir:

- en_US
- tr_TR
- es_ES
- zh_CN
- ru_RU
- de_DE
- fr_FR
- ar_SA
- cs_CZ
- el_GR
- bg_BG
- nl_NL
- sv_SE

Oyuncular oyun içinden dil değiştirebilir:

```text
/ah locale tr_TR
```

## Kurulum

1. Sunucunuzun Java 21 ile çalıştığını doğrulayın.
2. Sunucuda Paper veya Spigot 1.20+ kullandığınızdan emin olun.
3. Vault ve ekonomi eklentinizi kurun.
4. Oluşan jar dosyasını plugins klasörüne koyun.
5. Sunucuyu başlatın.
6. İlk açılıştan sonra oluşan yapılandırma dosyalarını düzenleyin.
7. Gerekirse /ah admin reload komutunu kullanın veya sunucuyu yeniden başlatın.

## Derleme

Projeyi kaynak koddan derlemek için:

```bash
mvn clean package
```

Oluşan çıktı dosyası target klasörü altında yer alır.

Bu proje Maven kullanır ve varsayılan olarak Java 21 release hedefi ile derlenir.

## Hızlı Başlangıç

Sunucuyu ilk kez ayağa kaldırırken genellikle sadece şu alanları düzenlemeniz yeterlidir:

```yml
database:
  type: sqlite

localization:
  default-locale: en_US
  fallback-locale: en_US

discord:
  enabled: false
  webhook-url: ''
```

MySQL kullanmak isterseniz database.type değerini mysql yapın ve mysql bölümünü doldurun.

## Yapılandırma Rehberi

Ana yapılandırma dosyası src/main/resources/config.yml içeriğinin sunucuya kopyalanmış halidir.

### Veritabanı

```yml
database:
  type: sqlite
  sqlite-file: auctions.db
  mysql:
    host: localhost
    port: 3306
    database: auctionhouse
    username: root
    password: change-me
```

- sqlite: Tek sunuculu hızlı kurulum için uygundur.
- mysql: Ağ yapısı, paylaşımlı altyapı veya daha merkezi yönetim isteyen kurulumlar için uygundur.
- pool ayarları bağlantı havuzunu kontrol eder.

### Açık Artırma Ayarları

```yml
auction:
  default-duration-minutes: 1440
  allowed-durations-minutes: [60, 180, 720, 1440, 4320]
  min-duration-minutes: 15
  max-duration-minutes: 10080
  max-active-listings: 30
  listing-fee: 25.0
  tax-rate: 0.02
  commission-rate: 0.05
  anti-snipe-window-seconds: 45
  anti-snipe-extension-seconds: 60
```

- default-duration-minutes: Oyuncu süre yazmazsa kullanılacak varsayılan süredir.
- min-duration-minutes / max-duration-minutes: Komutla verilebilecek süre alt ve üst sınırlarıdır.
- max-active-listings: Oyuncu başına aktif ilan limiti.
- listing-fee: İlan açılırken çekilecek ücret.
- tax-rate: Satış sonrası vergi oranı.
- commission-rate: Satış sonrası komisyon oranı.
- anti-snipe-window-seconds: Açık artırma bitimine bu kadar az kaldığında yeni teklif gelirse uzatma mekanizması tetiklenir.
- anti-snipe-extension-seconds: Uzatma gerçekleştiğinde eklenecek süredir.

### Kısıtlamalar

```yml
restrictions:
  blacklist-materials:
    - BEDROCK
    - BARRIER
  whitelist-enabled: false
  whitelist-materials: []
  nbt-blocked-keys:
    - PublicBukkitValues.illegal_plugin:illegal
```

- blacklist-materials: Yasaklı materyaller.
- whitelist-enabled: true yapılırsa sadece whitelist-materials içindeki eşyalar listelenebilir.
- nbt-blocked-keys: Belirli veri anahtarları taşıyan itemları engellemek için kullanılır.

### Yerelleştirme

```yml
localization:
  default-locale: en_US
  fallback-locale: en_US
```

- default-locale: Yeni oyuncular için varsayılan dil.
- fallback-locale: Bir çeviri anahtarı bulunamazsa kullanılacak yedek dil.

### Discord Webhook

Discord bildirimleri hem config.yml hem de webhook.yml üzerinden yönetilebilir.

```yml
discord:
  enabled: false
  webhook-url: ''
  notify-rare-listings: true
  notify-high-sales: true
```

webhook.yml içinde bildirim başlığı ve açıklaması özelleştirilebilir:

```yml
messages:
  rare-listing:
    title: 'Rare listing'
    description: '<seller> listed <item> for <price>'
```

### Performans ve Sesler

- refresh-ticks: GUI yenileme sıklığı için kullanılır.
- expire-check-ticks: Süresi dolan açık artırmaların kontrol aralığıdır.
- preload-active-auctions: Aktif açık artırmaları önceden belleğe alma davranışını kontrol eder.
- sounds bölümü menü seslerini özelleştirir.

## Komutlar

Ana komut:

```text
/auctionhouse
```

Kısa aliaslar:

```text
/ah
/auc
```

Alt komutlar:

| Komut | Açıklama |
| --- | --- |
| /ah | Ana açık artırma menüsünü açar |
| /ah help | Yardım metnini gösterir |
| /ah sell <price> [buyNow] [30m\|2h\|1d] [increment] | Elde tutulan eşyayı satışa çıkarır |
| /ah bid <id> <amount> | İlanda teklif verir |
| /ah buy <id> | İlanı buy now fiyatı ile satın alır |
| /ah claim [id] | Kazanılan veya iade edilen içerikleri teslim alır |
| /ah locale <code> | Oyuncu dilini değiştirir |
| /ah listings | Oyuncunun ilanlarını açar |
| /ah claims | Claim ekranını açar |
| /ah search <query> | Arama yapar |
| /ah admin reload | Yapılandırma ve dil dosyalarını yeniden yükler |
| /ah admin remove <id> | İlanı yönetici yetkisiyle kaldırır |

### Komut Örnekleri

```text
/ah sell 2500
/ah sell 2500 5000 12h 250
/ah bid 17 3250
/ah buy 17
/ah claim
/ah locale de_DE
/ah search diamond sword
```

## Yetkiler

| Yetki | Açıklama | Varsayılan |
| --- | --- | --- |
| auctionhousepro.use | Ana kullanım izni | true |
| auctionhousepro.admin | Yönetici komutları | op |
| auctionhousepro.bypass.fees | Listeleme ücretini bypass eder | op |
| auctionhousepro.locale | Dil değiştirme için ayrılmış yetki | true |

Not: Mevcut komut akışında genel kullanım kontrolü auctionhousepro.use üzerinden yapılır. auctionhousepro.locale düğümü plugin.yml içinde tanımlıdır ve izin sisteminizde ayrıca yönetilebilir.

## Oyun İçi Davranışlar

### Teklif Sistemi

- Oyuncu kendi ilanına teklif veremez.
- Geçersiz veya çok düşük teklifler reddedilir.
- Önceki en yüksek teklif sahibi varsa bakiyesi iade edilir.
- Bitişe yakın tekliflerde anti-snipe uzatması uygulanır.

### Buy Now Sistemi

- Sadece buy now fiyatı tanımlı ilanlarda kullanılabilir.
- Satın alma sonrası ilan satıldı durumuna geçer.
- Önceki teklif sahibi varsa teklif tutarı iade edilir.

### Claim Sistemi

- Satılan ilan gelirleri, kazanılan eşyalar veya iade edilen içerikler claim akışı ile alınır.
- /ah claim tek bir ilan kimliği ile ya da toplu şekilde çalıştırılabilir.

## API ve Geliştirici Kullanımı

Eklenti, diğer pluginlerin açık artırma servislerine erişebilmesi için basit bir API sağlar.

### Servise Erişim

```java
AuctionService service = AuctionHouseProApi.provider();
```

### Ana API Metotları

- createAuction
- placeBid
- buyNow
- cancelAuction
- search
- playerListings
- claimable
- cachedAuction
- openBrowser

### Yayınlanan Eventler

- AuctionCreateEvent
- AuctionBidEvent
- AuctionCancelEvent
- AuctionExpireEvent
- AuctionWinEvent

Create, bid ve cancel eventleri cancellable yapıdadır. Böylece başka eklentiler işlem tamamlanmadan önce müdahale edebilir.

## Proje Yapısı

```text
src/main/java/com/auctionhousepro/
  api/           -> Harici kullanım için API ve eventler
  command/       -> Komut işleyicileri
  config/        -> Konfigürasyon erişimi
  database/      -> Repository ve veritabanı yönetimi
  discord/       -> Discord webhook entegrasyonu
  economy/       -> Vault ekonomi katmanı
  gui/           -> Menü ve envanter arayüzleri
  i18n/          -> Locale yönetimi
  listener/      -> Bukkit listener sınıfları
  model/         -> Veri modelleri ve enumlar
  service/       -> İş mantığı
  util/          -> Yardımcı sınıflar
```

## Sorun Giderme

### Vault veya ekonomi bulunamıyor

- Vault kurulu mu kontrol edin.
- Bir ekonomi sağlayıcısının yüklü ve aktif olduğundan emin olun.
- Sunucu loglarında economy provider uyarısını kontrol edin.

### Çeviriler görünmüyor

- localization.default-locale ve fallback-locale değerlerini kontrol edin.
- Lang dosyalarının plugins klasörü altındaki ilgili dizinde oluştuğunu doğrulayın.
- /ah admin reload komutunu çalıştırın.

### Discord webhook çalışmıyor

- discord.enabled değerinin true olduğundan emin olun.
- Geçerli bir Discord webhook URL girin.
- Sunucu loglarında HTTP hata kodlarını kontrol edin.

### MySQL bağlantısı başarısız

- Host, port, kullanıcı adı ve parola değerlerini kontrol edin.
- Veritabanı sunucusunun erişilebilir olduğundan emin olun.
- Gerekirse mysql.parameters değerini ortamınıza göre düzenleyin.

## Lisans ve Katkı

Projeye katkı vermeden önce kodlama standartlarını, Paper API uyumluluğunu ve mevcut yapılandırma anahtarlarını korumaya dikkat edin. Özellikle locale dosyalarında yeni anahtar ekleniyorsa tüm dillerin fallback davranışı göz önünde bulundurulmalıdır.
