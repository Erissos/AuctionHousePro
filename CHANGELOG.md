# Sürüm notları

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

Altı gerçek Paper sürümünde üç eklentiyi birlikte kapsayan 43 kontrol/sürüm geçti. Ayrıntılar: [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).
