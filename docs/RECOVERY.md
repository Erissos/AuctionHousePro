# Ödeme, teslimat ve ilan kurtarma

İlan durum değişikliği, borç makbuzu ve oluşan kredi/teslim kayıtları aynı SQL işlemindedir. Kredi kimlikleri benzersizdir; iptal, eski teklif iadesi ve satıcı claim işlemi tekrar para oluşturmaz. SQLite tek bağlantıyla çalışır; MySQL ayrı havuz kullanır. Mevcut veritabanı silinmez, yeni sütunlar/tablo/indeks eklenir.

Vault'un hesap işlemi SQL ile ortak bir atomik işlem değildir. Sağlayıcı hata verip sonucu belirsiz bırakırsa veya süreç para aktarımı sırasında kapanırsa ödeme `REVIEW` durumuna alınır. Otomatik tekrar yapılmaz; hesap hareketi incelemesi bekleyen oyuncunun yeni borçlandırması engellenir.

`auctionhousepro.admin` gerektiren kurtarma komutları konsoldan da kullanılabilir:

| Komut | Doğrulandıktan sonraki işlem |
|---|---|
| `/ah admin ledger` | İnceleme bekleyen para işlem kimliklerini listeler |
| `/ah admin ledger <işlem id> applied` | Sağlayıcı işlemi uygulamış: kredi kapanır; tamamlanmamış satışın borcu iade kuyruğuna alınır |
| `/ah admin ledger <işlem id> retry` | Sağlayıcı uygulamamış: kredi yeniden sıraya alınır; uygulanmamış borç reddedilir |
| `/ah admin delivery` | İnceleme bekleyen eşya teslim kayıtlarını listeler |
| `/ah admin delivery <id> applied` | Eşya verilmiş: teslim kaydı kapatılır |
| `/ah admin delivery <id> retry` | Eşya verilmemiş: teslim tekrar alınabilir yapılır |
| `/ah admin escrow` | İlan açma sırasında sonucu belirsiz eşya emanetlerini listeler |
| `/ah admin escrow <UUID> applied` | Eşya oyuncunun elinde kalmış/geri verilmiş: emanet kapanır |
| `/ah admin escrow <UUID> retry` | Eşya elden alınmış ve ilan oluşmamış: teslim kutusuna bir kez geri konur |

İşlem dosyalarını/veritabanını yedekleyin, sağlayıcı hesap geçmişi ve oyuncu envanteriyle karşılaştırın. `applied`/`retry` seçimi kesin kanıta dayanmalıdır; bu komutlar belirsiz bir sonucu tahmin etmez. `listing-escrow` dosyasındaki borç kimliği SQL makbuzuyla karşılaştırılır; SQL'de tamamlanmış ilan için ayrıca eşya iadesi oluşturulmaz.

Kapasitesi yetersiz envantere teslim kutusu hiçbir parça eklemez. Teslimden sonra SQL kaydı kapanamazsa kayıt tekrar verilemez; başlangıçta yönetici incelemesine alınır. Sunucu kapanışından önce yarım kalan, sonucu bilinen ücret işlemleri makbuzdan iade edilir.

Reload çalışan ödeme kuyruğunu yeniden kurtarma moduna almaz; menü/dil ayarları ve süre denetim aralığı yenilenir. Veritabanı türü veya bağlantı bilgisi değişikliği için sunucuyu normal biçimde yeniden başlatın.
