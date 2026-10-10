# Bileşenler ve durumları

Tüm ölçüler dp. Durum katmanları (Material 3): basılı `onSurface @12%`, odak `@12%` + 2dp accent.outline halka, devre dışı içerik `@38%` (metin için text3 kullan, alfa değil), hover yok (dokunmatik).

## Navigation bar
Yükseklik 80 + sistem inset. 4 öğe, her biri `1fr`. İkon 24, gösterge 64×32 pill `accent.container`, ikon `accent` dolu varyant; etiket labelSmall 11/16, seçili 600 ağırlık text1, pasif text2 ikon çizgi. Rozet: 6dp nokta `accent` (Kitaplık'ta yeni indirme), ilerleme halkası 2dp.
Durumlar: pasif · seçili · basılı (gösterge %12 koyulaşır) · odak.

## Mini oynatıcı
64 yükseklik, kenarlardan 12, nav barın 12 üstünde. Köşe 14. Zemin `surface3 @90%` + blur 20, kenarlık 1 `accent.outline @25%`, glow gölge. İç: kapak 48 (6dp köşe) · 12 boşluk · başlık titleSmall 14/600 + sanatçı bodySmall 12 text2 (tek satır ellipsis) · çalıyor çubukları 16 · play/pause 48 hit. Alt kenarda 2dp ilerleme, 8dp içeriden.
Durumlar: çalıyor · duraklatıldı (çubuklar durur) · tamponlanıyor (ilerleme belirsiz kayar) · yatay kaydırma (kapak + metin 40 % eşiğe kadar sürüklenir, sonra 250 ms slide) · yukarı kaydırma (Şimdi Çalıyor) · yok (içerik nav bar üstüne iner).

## Birincil buton (pill)
48 yükseklik, yatay 24 padding, pill. Zemin `accent`, metin `onAccent` titleMedium 15/700, isteğe bağlı 20 ikon + 8 boşluk.
Durumlar: normal · basılı (ölçek .97, 100 ms) · devre dışı (`surface3` zemin, text3) · yükleniyor (metin yerine 20 dairesel gösterge).

## İkincil buton (pill)
48, zemin `surface3`, metin text1 15/600. Basılı `@12%`.

## Çip (filtre)
36 yükseklik, yatay 12, köşe 10, labelLarge 14/500. Pasif: `surface2` zemin, 1 `outlineVariant`. Seçili: `accent.container` zemin, 1 `accent.outline @50%`, metin + ikon `accent`, 600 ağırlık. Önde 16 ikon isteğe bağlı. Basılı %12.

## Liste satırı (şarkı)
60 yükseklik (içerik 44). Kapak 44 (6 köşe) · 12 · başlık titleSmall 14/600 text1 (çalan şarkıda `accent`) + alt satır bodySmall 12 text2 · sağda: indirildi 16 ikon accent · süre monoMedium text3 · more 40 (hit 48).
Varyantlar: normal · çalan (başlık accent + 16 çubuk göstergesi kapağın üstünde `scrim @50%`) · önerilen (ÖNERİLEN rozet overline 9.5 `accent.container`/accent; sağda + ve gizle 40×40) · sürüklenen (Sıra: `surface3` zemin, 20dp köşe, elevation 6, ölçek 1.02) · kaydırılan (sağdan sola `error` zemin + trash ikonu) · devre dışı (engellenen: text3, kapak %38) · yükleniyor (shimmer: kapak kare + 2 bant).

## Kapak karosu (yatay şerit)
132 kapak (6 köşe) + 8 + başlık titleSmall 12.5/600 + alt 11.5 text3; şerit padding 20, boşluk 12. Küçük varyant 116. "Son çalınanlar" karosu: 56 yükseklik, `surface2`, 10 köşe, kapak 44 + metin 13/600; 2 sütun 8 boşluk.

## "Senin için yapıldı" kartı
168×104, `surface2`, 1 `surface3` kenarlık, 12 köşe, padding 12. Üst: overline 10 mono (renk karta özgü vurgu). Alt: headlineSmall 17/700 2 satır. Sağ üstte hilal işareti (90 daire + 90 offset daire) kart rengiyle.

## Rozetler
Kaynak rozeti: monoSmall 11, padding 5×10, köşe 6, `surface2` zemin, 1 `surface3`, text2. "Canlı": pill 20 yükseklik, `error` zemin beyaz metin + 6 nokta (1 s yanıp sönme). Sayı rozeti (podcast yeni bölüm): 18 min, pill, `accent`/onAccent, labelSmall.

## Arama kutusu
52 yükseklik, pill, `surface2`, 1 `outlineVariant`; odakta `accent.outline`. Ön ikon 24 text2, hint bodyLarge text3, temizle 40.

## Bottom sheet
Üst köşe 28, alt 0. `surface2`, sürükleme tutamacı 36×4 `outline` 14 üstten. Scrim `scrim`. Başlık headlineSmall 20 padding 20/24. Menü satırı 56, ikon 24 text2 + 16 + bodyLarge. Yıkıcı satırlar `error`.

## Segment (Şimdi Çalıyor alt sekmeleri)
44 yükseklik, pill, `surface2`, iç padding 4; seçili segment `surface3` pill, 13/600; pasif 13 text2.

## Snackbar
Min 48, `surface3`, 12 köşe, 16 padding, bodyMedium text1, aksiyon labelLarge accent. Nav bar + mini oynatıcı üstünde.

## Diyalog
`surface2`, 28 köşe, padding 24, başlık headlineSmall, gövde bodyMedium text2, aksiyonlar metin butonu (yıkıcı `error`).

## İlerleme çubuğu
4 yükseklik; track `text1 @12%`, tampon `accent.progressTrack`, çalınan `accent`; thumb 12 (sürüklerken 16). Süreler monoMedium text2, 8 altında.

## Yükleme iskeleti
`surface2` üzerinde `surface3` shimmer bandı 1200 ms. Kapaklar kare, metinler 12 yükseklik bant, 4 köşe.

## Boş durum
Ortalanmış: 64 ikon text3 (`surface2` 20 köşe kutuda 96) · 16 · headlineSmall · 8 · bodyMedium text2 max 280 genişlik · 24 · ikincil buton.

## Switch / Slider (Ayarlar)
Material 3 varsayılan geometri; track `accent`, thumb onAccent; pasif `surface3` / text3. Slider aktif `accent`, pasif `surface3`, değer etiketi mono.
