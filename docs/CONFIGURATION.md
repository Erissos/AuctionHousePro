# AuctionHousePro yapılandırması

Desperis ürünü · Geliştirici: **erisos** · [Resmî site](https://desperis.com) · Yapılandırma sürümü: **1.1.1-SNAPSHOT**.

Ana ayarlar [`config.yml`](../src/main/resources/config.yml) içinde Türkçe yorumlarla açıklanır. Paketlenmiş dosya yeni kurulumun örneğidir; çalışan sunucuda `plugins/AuctionHousePro/config.yml` düzenlenir. Dosyayı UTF-8 kaydedin ve girintide boşluk kullanın. YAML anahtarları, Material / EntityType / Sound kodları ve kalıcı kimlikler çevrilmez.

## Genel kurallar

- Her zaman değerin yanında yazılan birimi kullanın. `20 tick ≈ 1 saniye`, `1000 ms = 1 saniye`; sunucu gecikirse tick tabanlı süre de uzar.
- HEX renklerini tırnak içinde yazın; `#` tırnaksız olursa YAML yorumu başlayabilir.
- Düzenlemeden önce config ve önemli kalıcı verilerin yedeğini alın. Veritabanı türü, dosya adı, dünya veya içerik kimliği değişikliği kayıtlardaki veriyi otomatik taşımaz.
- Normal ayarlar için `/ah reload` kullanılır. Veritabanı / dünya altyapısı veya başka sağlayıcının kurulması gibi başlangıçla ilişkili değişikliklerden sonra kontrollü sunucu yeniden başlatması yapın.
- Mevcut dosya özelleştirmeleri korunur. Yeni varsayılanları görmek için paketlenmiş örneğe bakın; tüm configi silip yeniden oluşturmak kendi ayarlarınızı kaybettirir.

## Ürüne özgü ayarlar

`database.type` için `sqlite` veya `mysql` seçilir. SQLite varsayılan dosyası `auctions.db`dir. Türü / dosya adını değiştirmek mevcut ilanların yeni veritabanına taşınmasını sağlamaz. MySQL sunucusu, katalog, kullanıcı, parola ve JDBC parametrelerini barındırma ortamınıza göre düzenleyin. Örnek bağlantı parametreleri TLS kullanmaz; havuz bağlantı sınırlarının MySQL limitine uyduğundan emin olun.

Parasal ilan / teklif / alım işlemleri Vault ve etkin bir ekonomi sağlayıcısı gerektirir. `auction.listing-fee` sabit ilan bedelidir. `tax-rate: 0.02` yüzde 2 vergi, `commission-rate: 0.05` yüzde 5 komisyon demektir; ikisi de satıcıya ödenen brüt tutardan düşülür. Yüzde 2 için bu alanlara `2` yazılmaz. Süre listeleri dakika, son an uzatma ayarları saniye, teklif / ilan beklemeleri milisaniye kullanır.

`allowed-durations-minutes` seçenekleri min / max süre içinde kalmalıdır. Varsayılan 1440 dakika bir gün, üst sınır 10080 dakika yedi gündür. `anti-snipe-window-seconds` bitişe yakın teklif aralığını, `anti-snipe-extension-seconds` eklenen süreyi belirler. `max-active-listings` oyuncu başına, `max-search-results` bir arama sonucu için sınırdır.

`restrictions.blacklist-materials` her zaman engellenen malzemeleri tanımlar. İzin listesi açıksa yalnızca o listedekiler kabul edilir; açık ve boş izin listesi hiçbir eşyayı kabul etmez. NBT engelleri kesin veri yolu / anahtarlarla çalışır. Görünen eşya adlarını çevirmek bu kodları değiştirmek anlamına gelmez.

`localization` varsayılan ve eksik anahtar için yedek dili belirler. Webhook ayarları isteğe bağlıdır; `webhook.yml` ile ilişkisini kurulumunuzda inceleyin. Webhook URL paylaşmayın. `performance.refresh-ticks: 20` yaklaşık bir saniye, `expire-check-ticks: 40` yaklaşık iki saniyedir. `preload-active-auctions` eski uyumluluk alanıdır; bu sürümde ayrı ön yükleme yolu bu değeri okumaz. Ticaret entegrasyonu ilan, teklif, alım gibi yeni oyuncu işlemlerini denetler; borç / teslimat kurtarma akışlarını bir bölgeye hapsederek parasal tutarlılığı bozmaz.

## Koruma ve core entegrasyonları

`integrations` ortak bölümü Towny Advanced, GriefPrevention, WorldGuard, Lands, BentoBox ve SuperiorSkyblock2 için açma / kapatma, dünya listeleri ve işlem denetimleri içerir. Kurulu ve etkin olmayan sağlayıcı normalde atlanır. Örneğin `required-providers: [towny]` Townynin kurulu / etkin olmadığı kurulumda koruma gerektiren işlemlerin serbest geçmesini önler. Etkin bir sağlayıcının API hatası ise `fail-closed` kuralına tabidir. Birden fazla etkin koruma aynı konumu kapsıyorsa birinin reddi yeterlidir.

`fail-closed: true` etkin sağlayıcının API hatasında ilgili işlemi reddeder. `bypass-permission` boşken hiçbir permission entegrasyonu atlatmaz. Bir bypass izni tanımlamak oyuncunun işletme / hayvan / eşya sahibi olma şartını ortadan kaldırmaz. `wilderness` yalnızca sağlayıcının yönettiği dünyada alan dışı davranıştır; normal oyun dünyalarını bir SkyBlock eklentisi var diye ada saymaz.

`integrations.worlds.allowed: []` ortak katmanda tüm dünyalar, `blocked: []` ortak yasak yok anlamına gelir. Engelli dünya listesi izin listesinden önceliklidir. Ürünün kendi dünya kısıtlamaları ayrıca geçerlidir. İsimler kesin dünya adlarıdır; joker karakter eşlemesi yoktur.

`checks` sadece bu ürünün gerçekten yaptığı işlemlere uygulanır. Örneğin bir ticaret ürünü için blok kırma anahtarının var olması ürünün blok kırdığı anlamına gelmez. Bir kontrolü kapatmak sağlayıcının o işlemle ilgili denetimini bilerek kaldırır; eklentinin kendi sahiplik ve izin kuralları devam eder. Desteklenen API eşlemeleri ve doğrulama sınırları [entegrasyon rehberinde](INTEGRATIONS.md) bulunur.

## Dil, görünüm ve telemetri

Varsayılan dil oyuncunun kişisel tercihinden farklıdır. Dil / menü görünümü ayrı kaynak dosyalarında yönetilir; eşya kodlarını çevirmek yerine görünen metinleri düzenleyin. Bu üründe olmayan ayarları eklemek çalışma davranışını değiştirmez; paketlenmiş config ve ayrı dil / menü dosyalarındaki mevcut seçenekleri kullanın.
