# AuctionHousePro: Desperis ürün bağlantıları

`suite-integrations.features.auction-business-benefits` varsayılan kapalıdır. AH'nin PlayerBusiness sağlayıcı bağlantısı ve PB'nin profil dışa aktarımı açıksa uygun üyeler işletme seviyesiyle avantaj alabilir. Yalnızca salt okunur `id/name/type/role/level` profili kullanılır; PB kasası veya ödeme kayıtları değiştirilmez.

Her indirim oranı `min(auction-business-maximum-discount, şirket seviyesi × ilgili discount-per-level)`dir. Oranlar 0–1: 0.02 yüzde 2, varsayılan azami 0.25 yüzde 25tir. Boş rol / tür listesi kimseyi kapsamaz. Profil okunamazsa normal ücretler geçerlidir.

İlan bedeline önce mevcut VIP / permission segment çarpanı, sonra şirket indirimi uygulanır. `auctionhousepro.bypass.fees` kontrolü korunur. Vergi de mevcut segment oranından sonra azaltılır; komisyon değişmez. İndirim bölgesel ticaret, eşya filtresi, sahiplik veya başka yetki kontrollerini atlamaz.

Şirket seviyesi ilan bedeli alınırken ve satış vergisi için satıcı gelirini **talep ederken** okunur. Satış anındaki seviye sabitlenmez. Satış ile talep arasında üyelik / seviye değişirse talep anındaki uygunluk geçerlidir. Mevcut seller-claim makbuzu aynı kredinin tekrar oluşturulmasını önler; avantaj ayrı bir para transferi değildir.

`features.auction-milestones`, başarılı `ACTIVE -> SOLD` geçişinden sonra ana thread'de satıcıya `auction_sale`, alıcıya `auction_purchase` yayınlar. `amount: 1` bir tamamlanmış satış demektir; `material` ve müzayede `type` kimlikleri ayrıntıdır. Ön olay, iptal, başarısız alım ve menü açma başarı sayılmaz.

Auction ID tabanlı makbuzlar canlı köprüde tekrar sayımı önler. İlgili gelişim alıcısı açıkken canlı ve en iyi çaba ile işlenir; eski satışlar taranıp yeniden başarı verilmez. Finansal outbox / offline telafi garantisi yoktur. Alıcı hatası satışı, para veya eşya teslimini değiştirmez.
