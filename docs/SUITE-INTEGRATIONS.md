# Desperis ürünlerinin birlikte çalışması

Dokuz ürün ayrı JAR, veri kaydı ve ödeme sistemini korur. Ortak çekirdek eklentisi kurmak gerekmez. Ortak özellikler `config.yml > suite-integrations` altında düzenlenir; bölge/claim/skyblock korumaları ayrı `integrations` bölümündedir. Ürün bağlantısı iki tarafta da açık olmalıdır. Eksik, eski protokollü veya kapalı bir ürün temel oyun işleyişini durdurmaz.

| Ürünler | Birlikte çalışma | Varsayılan |
| --- | --- | --- |
| Dokuz ürün | Oyuncunun açıkça seçtiği dili UUID ile destekleyen diğer ürünlere iletir. Desteklenmeyen dilde mevcut tercih korunur. | Dil eşitleme kapalı |
| StableMan → BetterAdvancements | İlk hayvan kaydı, tüketilen yem ve kaydedilen yavru özel başarı/görev hedeflerini ilerletebilir. | Canlı bildirim açık; örnek hedefler kapalı |
| AuctionHousePro → BetterAdvancements | Tamamlanan satış ve alım özel hedefleri ilerletebilir. Oluşturma/iptal/başarısız ödeme sayılmaz. | Canlı bildirim açık; örnek hedefler kapalı |
| PlayerBusiness → BetterAdvancements | Başarıyla kurulan işletme ve ödemesi tamamlanan iş emri özel hedefleri ilerletebilir. | Canlı bildirim açık; örnek hedefler kapalı |
| DynamicBounty → BetterAdvancements | Ödemesi tamamlanan av ödülü özel hedefleri ilerletebilir. | Canlı bildirim açık; örnek hedefler kapalı |
| SmartNPCWorkers → BetterAdvancements | Kaydı tamamlanan üretim materyal ve adet koşullu özel hedefleri ilerletebilir. | Canlı bildirim açık; örnek hedefler kapalı |
| ServerEventsEngine → BetterAdvancements | Uygun etkinlikte gerçek oyuncunun kesinleşen mob öldürmesi özel hedefleri ilerletebilir. | Canlı bildirim açık; örnek hedefler kapalı |
| DynamicDungeonGenerator / AdaptiveBosses → BetterAdvancements | Kesinleşen zindan bitişi veya bağımsız boss zaferi özel hedefleri ilerletebilir. Bağlı boss ayrıca boss zaferi üretmez. | Canlı bildirim açık; örnek hedefler kapalı |
| PlayerBusiness → AuctionHousePro | Uygun şirket üyeleri seviyeye göre ilan ücreti ve satış vergisi indirimi alabilir. VIP çarpanı ve bypass izinleri korunur. | İndirim kapalı |
| PlayerBusiness → SmartNPCWorkers | Şirket seviyesi kişisel işçi satın alma sınırına sınırlı bonus verebilir. Şirketten ayrılınca mevcut işçiler silinmez. | Sınır bonusu kapalı |
| SmartNPCWorkers → PlayerBusiness | Kargo menüsündeki **Uygun iş emirleri** düğmesi kargodaki sıradan malzemeleri isteyen açık emirleri gösterir. | Bağlantı ve izin varsa düğme açık |
| DynamicDungeonGenerator ↔ AdaptiveBosses | Zindan şablonu AdaptiveBosses savaş sistemini kullanabilir. Güncel grubun boss odasındaki üyeleri savaşır; bitiş ve ödülü DDG yönetir. | Ortak boss kapalı |
| DynamicDungeonGenerator ↔ DynamicBounty | Zindan katılımcılarının kaçış/kill büyümesi ve bounty yakalaması duraklar; avcı/hedef takibi gizlenir. | Çakışma önlemi açık |
| DynamicDungeonGenerator ↔ StableMan | Aktif zindanda hayvan çağırma, hayvana ışınlanma ve dış kargo kullanımı engellenir. Önceden açılan kargonun güvenli kapanışı sürer. | Çakışma önlemi açık |
| ServerEventsEngine ↔ Diğer ürünler | AB/DDG bossları, işçiler ve NPC'ler genel mob/drop etkilerinden ayrılır. DDG dünyasının sabit zamanı değiştirilmez. Sahipli hayvanlar ayrıca tercih ister. | Sahipli hayvan güç/drop tercihi kapalı |
| SmartNPCWorkers ↔ StableMan / BetterAdvancements | Claim sorgusu amacıyla üretilen aynı `BlockBreakEvent` nesnesi gerçek oyuncu kırması gibi XP/başarım vermez. Koruma iptalleri hâlâ geçerlidir. | Sahte ilerleme önlemi açık |

## Sunucu sahibinin ayarları

`suite-integrations.enabled` yeni ortak bağlantıları topluca kapatır. `providers.<ürünün-küçük-harf-kimliği>.enabled` tek ürünü seçer. `language-sync.enabled` eşitlemeyi açar; varsayılan kapalıdır, istemciye göre otomatik dil seçimi yayınlanmaz. Dil eşitlemesi ürünlerin desteklediği dil sayısını artırmaz. `milestones.enabled` canlı başarı bildirimlerini kapatır. Ürüne özgü tercih ve sınırlar `features` bölümünde açıklamalı olarak bulunur.

Örnekler: PlayerBusiness profil paylaşımını `features.business-profile-export` ile; AuctionHousePro şirket avantajlarını `features.auction-business-benefits` ile; SmartNPCWorkers şirket sınır bonusunu `features.workers-business-cap-bonus` ile yönetir. İndirim oranları 0–1 aralığındadır, üst sınır uygulanır. Vergi indirimi satıcı geliri talep ettiğinde güncel üyelik/seviye üzerinden hesaplanır; satış sırasında sabitlenmez. Şirket kasasına otomatik para yazılmaz.

İşçi kargosu otomatik teslim edilmez. Oyuncu önce eşyaları toplar, sonra PB'nin normal teslim/onay akışını kullanır. Özel metadata taşıyan eşyalar sıradan malzeme eşleştirmesine katılmaz. Her ürün sahiplik, izin, claim ve ödeme kontrollerini kendisi yapar.

Ortak boss için **DDG ve AB'de** `features.dungeon-bosses.enabled: true` gerekir. DDG'deki `templates.<kimlik>.boss.adaptive-template` bir AB şablon kimliği alır (örneğin `guardian`). Aynı boss tanımında `mythic-mob` kullanılamaz. Açık tercih verilmiş fakat AB/şablon kullanılamıyorsa giriş, dünya/ücret ayrılmadan reddedilir. Vanilla ve MythicMobs şablonları boş `adaptive-template` ile mevcut davranışlarını sürdürür. Bağlı bossun AB ödülü kapalıdır; kişisel bitiş ödüllerini DDG oluşturur.

## Başarı ve görev koşulları

BetterAdvancements `suite-achievements.yml` dosyası ilk kurulumda örnekleri sunar; `enabled: false` ile gelir. Açılınca var olan tanımlarla ID ve menü slotu çakışmaları doğrulanır. Kendi hedeflerinizi normal `CUSTOM` türünde, **hem `source` hem `event` koşulu** ile tanımlayın. Yönetici grant işlemi kullanılmaz; normal hedef, bağımlılık ve ödül kuralları geçerlidir.

| Kaynak (`source`) | Olay (`event`) |
| --- | --- |
| StableMan | `animal_registered`, `animal_fed`, `animal_bred` |
| AuctionHousePro | `auction_sale`, `auction_purchase` |
| PlayerBusiness | `business_created`, `business_order_completed` |
| DynamicBounty | `bounty_claim` |
| SmartNPCWorkers | `worker_produced` |
| ServerEventsEngine | `event_mob_kill` |
| DynamicDungeonGenerator | `dungeon_clear` |
| AdaptiveBosses | `boss_victory` |

Materyal, işçi türü, şablon veya işletme türü gibi ek koşullar ilgili ürünün `docs/SUITE-LINKS.md` belgesinde açıklanır. Gerçek, çevrimiçi oyuncular ilerleme alır. NPC ve sahte oyuncular hariçtir. Yönetilen moblar varsayılan olarak BA'nın genel `MOB_KILL` hedeflerine de sayılmaz; özel suite hedefleri kullanılır. İstenirse `features.betteradvancements-count-managed-mob-kills` ile genel öldürme sayımı açılır; NPC işçiler yine hariç tutulur.

Bu bağlantı canlı bir gözlemdir; tarihi tekrar oynatmaz ve offline başarı telafisi yapmaz. Aynı işlem makbuzu canlı alıcıda sınırlı bir önbellekle tekrar sayılmaz. Para/üretim işlemleri kendi kalıcı makbuzlarını korur; başarı gözlemi sunucu çöküşü boyunca tam bir outbox/teslim garantisi vermez. Alıcı hatası tamamlanmış alışverişi veya üretimi geri almaz.

## Kapanış ve kurtarma

Mevcut bağlı savaşın bitiş/iptal/temizlik çağrısı kayıtlı kaynak, oturum, oda ve boss kimliği ile doğrulanır; yeni bağlantıları kapatmak bu sahipli temizliği engellemez. AB bitiş sonucunu kalıcı kaydeder; DDG onayı yoksa sonuç denetlenebilir biçimde korunur ve başarı/ödül uydurulmaz. Yeniden açılış yarıda kalmış zindan oturumlarını kurtarma politikasına göre iptal eder; yüklenmemiş DDG dünyası AB'nin bağımsız savaşlarını kapatmaz. OS düzeyinde ani kapanış ve gerçek istemciyle bağlı boss savaşı bu değişikliğin kompakt doğrulamasında sertifikalandırılmamıştır.

Bağımlılık yönleri ve opsiyonel yükleme sırası [Paper plugin.yml belgesine](https://docs.papermc.io/paper/dev/plugin-yml/) göre tanımlanır; dokuz ürün arasında dairesel zorunlu bağımlılık oluşturulmaz. API hedefi Paper 1.20.6, bytecode Java 21 olarak korunur. Bu değişiklik için çalışma zamanı kapsamı yalnızca 1.20.6, 1.21.11 ve 26.2'dir; güncel kapsam [COMPATIBILITY.md](COMPATIBILITY.md), kişisel yolları/verileri içermeyen ürün kanıtı [verification.json](verification.json) içindedir.
