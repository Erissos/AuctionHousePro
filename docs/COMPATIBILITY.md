# AuctionHousePro 1.1.0 — uyumluluk doğrulaması

**Tarih: 3 Ekim 2026.** Desteklenen aralık Paper 1.20.6–26.2. Derleme Paper 1.20.6 API ve Java 21 bytecode ile yapılır; `api-version: '1.20.6'` korunur. Paper 26.1 ve sonrasında sunucuyu Java 25 ile çalıştırın.

## Gerçek sunucuda doğrulanan sürümler

| Minecraft | Paper build | Java | Kontrol | Menü |
|---|---:|---|---:|---:|
| 1.20.6 | 151 | 21 | 43 | 23 |
| 1.21.1 | 133 | 21 | 43 | 23 |
| 1.21.4 | 232 | 21 | 43 | 23 |
| 1.21.11 | 132 | 21 | 43 | 23 |
| 26.1.2 | 74 | 25 | 43 | 23 |
| 26.2 | 129 | 25 | 43 | 23 |

Her sürümde **üç eklentiyi birlikte kapsayan 43 kontrol** ve **23 menü oluşturma** geçti. Toplam 258 kontrol, 138 menü ve sıfır hata. Tüm ara yama sürümleri ayrı ayrı çalıştırılmadı.

## Kapsam

- Gerçek Paper sunucuları; ayrı geçici dünyalar, gerçek registry erişimi, hayvanlar ve envanterler.
- Eklenti açılışı, sürüm metadata'sı, YAML okuma ve reload.
- İki ayrı UUID ile Türkçe/İngilizce dil izolasyonu, kısa/bölge/tireli kodlar ve eski dil komutları.
- Geçersiz dilin tercihi koruması, yetkiye göre tab tamamlama ve konsol help/reload.
- YAML tercihlerinin reload sonrası korunması; BetterAdvancements dilinin gerçek SQLite kaydı ve yeniden okunması.
- Yeni anahtarları içermeyen mevcut dil dosyalarının ve özelleştirmelerin korunması.
- Ses çözümü ve çağrıları, GUI parlaması, at/eşek/katır attribute işlemleri.
- AuctionHousePro NBT ve eski Base64 kayıtları; 1.20.6 eşyalarının sonraki sürümlerde metadata korunarak okunması.

Oyuncu alıcıları proxy nesneleridir; bir Minecraft istemcisi bağlanmadı. Gerçek oyuncu tıklamaları, tam alım/satım ve takas akışları, MySQL sunucusu ve tüm üçüncü taraf entegrasyonlar bu matrisin dışında kalır. Vault ve PlaceholderAPI bulunmadan açılış doğrulandı. Sabit `menus.yml`/`gui.yml` tasarım metinleri oyuncu diliyle otomatik çevrilmez.

## Doğrulanan dosya

Dosya: `AuctionHousePro-1.1.0.jar`

```text
SHA-256: 6a642e40b33e1a25969150d33c8256cadacd8d99d7b622761caab8bcea2606e6
```

GitHub Release, bu testte kullanılan JAR'ı ve `SHA256SUMS.txt` dosyasını içerir. Makineye özel yolları içermeyen test sonuçları release içindeki `compatibility.json` dosyasında yer alır. Sunucu/eklenti yükseltmesinden önce verileri yedekleyin ve eski JAR'ı sunucu durmuşken değiştirin.

## 1.1.1-SNAPSHOT düzeltme doğrulaması

Geliştirme buildinin güncel çalışma zamanı sonuçları çalışma alanındaki `verification/results.json` ve `verification/regression/results.json` dosyalarındadır. Önceki 1.1.0 sürümünün sonuçları `verification/release-1.1.0-results.json` olarak korunur. Yeni regresyon aracı eski dil dosyalarıyla yükseltmeyi, gerçek SQLite işlemlerini, gerçek envanterleri ve kontrollü Vault sağlayıcısıyla başarısız/eşzamanlı para-eşya işlemlerini sınar. Araç yalnızca ayrı geçici Paper dünyalarında çalıştırılır.

Ek doğrulamada gerçek MySQL 8.4.7 bağlantısı/şema geçişi ve ağ protokolü istemcileri de sınandı; sonuçlar `verification/regression/mysql-results.json` ve `verification/client/results.json` içindedir. 26.2 ağ istemcisi kontrolü istemci protokol desteği nedeniyle atlandı; Paper/SQL deneyi geçti. Grafik Minecraft istemcisi, canlı BetterEconomy/diğer üretim Vault sağlayıcıları ve her üçüncü taraf bölge koruma eklentisi kapsam dışındadır. Para sağlayıcısı ve Paper oyuncu envanteri eklenti SQL/YAML kaydıyla ortak atomik işlem sunmaz; belirsiz sonuçlar [kurtarma kurallarına](RECOVERY.md) göre incelemeye alınır.
