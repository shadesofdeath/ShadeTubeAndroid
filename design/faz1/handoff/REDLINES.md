# Redline — ana ekranlar (412×915dp, koyu)

Tüm değerler dp. Durum çubuğu 24 (edge-to-edge, içerik altına uzanır). Sistem gesture alanı 24. Ekran kenar boşluğu 20. Bölüm boşluğu 24.

## Ana sayfa
```
y=0    Durum çubuğu 24 (mono 13 text2)
y=46   Başlık bloğu: "Cumartesi" bodySmall 13 text2 · "İyi akşamlar, Deniz" headlineLarge 28/700 · sağda avatar 40 (pill)
y=104  "Son çalınanlar" titleMedium 15/600 · 10 alt boşluk
y=130  2×2 karo ızgara: karo 56 yükseklik, 8 boşluk; kapak 44 (6 köşe) + 10 + 13/600
y=250  "Sana özel" + "Tümünü gör" labelMedium 12 accent · 10
y=276  Kapak şeridi: 132 kapak + 8 + 2 satır metin (12.5/600, 11.5 text3) · boşluk 12 · sol 20, sağ taşar
y=460  "Senin için yapıldı" 15/600 + alt yazı 12 text3 · 10
y=498  Kart şeridi 168×104 · boşluk 12
y=626  "Yeni çıkanlar" · kapak 116 şeridi
y=759  Mini oynatıcı 64 (12 kenarlardan, alt 92)
y=827  Navigation bar 88 (80 + 8 inset gösterimi) — gerçek cihazda 80 + navigationBars inset
```
Dokunma hedefleri: karo 56 ✓, "Tümünü gör" 48 hit (`minimumInteractiveComponentSize`), avatar 40 görsel / 48 hit.

## Çalma listesi detayı
```
y=46   Üst bar 48: geri 48 · sağda ara 48 + more 48 (padding 8)
y=102  Başlık bloğu: kapak 148 (6 köşe, glow 24/50) · 16 · başlık headlineMedium 26/700 · 10 · sahip avatar 20 + 13/600 + "Spotify" rozet mono 10 · 8 · meta monoMedium 12 text2
y=270  Butonlar 48: Çal (accent pill) · 10 · Karıştır (surface3 pill) — 1:1
y=330  Çip şeridi 36: Geliştir (seçili) · Çevrimdışı · Sırala · Diğer — boşluk 8, yatay kaydırma
y=380  Senkron kartı 52: surface1, 10 köşe, 10×12 padding; metin 12 + sayaç mono; 3dp ilerleme accent; "Duraklat" 12/600 accent
y=440  Şarkı satırları 60 (kapak 44, süre mono 12 text3, more 40)
       Önerilen satırda: ÖNERİLEN rozet (overline 9.5, padding 2×6, 4 köşe) · sağda + (40) ve gizle (40)
y=759  Mini oynatıcı · y=827 Nav bar
```
Boş liste: liste yerine boş durum bileşeni (kapak bloğu ve butonlar kalır; Çal/Karıştır devre dışı).

## Mini oynatıcı (ayrıntı)
```
Genişlik 388 (412 − 2×12) · yükseklik 64 · köşe 14
İç padding: sol 8, sağ 12 · kapak 48 · boşluk 12
Metin bloğu: başlık 14/600 (satır 18) + sanatçı 12 (satır 16), tek satır ellipsis
Çalıyor çubukları: 3×3dp, boşluk 2, yükseklik 16, sağdan 4
Play/pause: 48×48, ikon 26
İlerleme: 2dp, sol/sağ 8 içeriden, alt kenarda
Kenarlık 1 accent @25% · gölge 0 12 40 −12 accent @45%
```

## Şimdi Çalıyor
```
y=46   Üst bar 48: chevron-down 48 · orta: overline 11 "ÇALMA LİSTESİNDEN" + 13/600 liste adı · more 48
y=116  Kapak 348×348 (8 köşe), sol/sağ 32; gölge 0 30 70 −20 accent @55%
y=488  Başlık satırı: headlineSmall 22/700 + sanatçı·albüm 14 text2 · sağda beğen 48 (ikon 26)
y=550  İlerleme 4dp (sol/sağ 32) · 8 · süreler mono 12 text2 (2:23 / 5:12)
y=586  Kontroller (sol/sağ 24, space-between): shuffle-smart 48 · prev 56 (ikon 30) · play 76 (pill accent, ikon 34 onAccent) · next 56 · repeat 48
y=678  Kaynak satırı: rozet mono 11 (5×10) + "Yanlış eşleşme?" 12/600 accent — ortalı, boşluk 10
y=714  İkincil araçlar (sol/sağ 40, space-between): hız · uyku · çıkış · paylaş — her biri 56 geniş, 22 ikon + 10 etiket (mono/sans), aktif olanlar accent
y=827  Alt pill 44 (sol/sağ 20, alt 24): Sıradaki · Şarkı sözleri · İlgili — 3 eşit segment; sürükleme tutamacı 36×4 üstte 14
```
Sheet açılınca (yukarı sürükleme): kapak 348 → 56 (başlık satırına taşınır), kontroller 56 yüksekliğe sıkışır, içerik sekmesi kalan alanı alır; 400 ms emphasized.

Uzun metin kontrolü (DE/RU, %200 ölçek): başlık 2 satır + ellipsis; kontroller satırı sabit yükseklik; ikincil araç etiketleri `maxLines=1`, `autoSize` 10→8sp; nav etiketleri tek satır.
