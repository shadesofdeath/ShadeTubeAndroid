# ShadeTube — Tasarım Handoff

Kotlin + Jetpack Compose için tasarım paketi. Bu klasördeki her şey koda doğrudan çevrilebilir.

> **Kapsam notu.** Bu paket Faz 1'i içerir: tam görsel kimlik, token'lar, Theme.kt, 88 ikon, marka/ikon varlıkları, animasyonlar, yer tutucular, Türkçe metinler (tüm ekranlar için) ve üç ana ekranın hi-fi tasarımı + redline'ı (Ana sayfa, Şimdi Çalıyor, Liste detayı, Mini oynatıcı). Kalan ekranlar (4–26) `SCREENS.md`'de bileşen ve davranış düzeyinde spesifikasyon olarak yazılıdır; hi-fi görselleri Faz 2'de eklenecektir.

## Klasörler

| Klasör | İçerik |
|---|---|
| `tokens/tokens.json` | Renk (koyu + açık + vurgu kuralı), tipografi ölçeği (sp), spacing (4dp), köşe, elevation, motion |
| `compose/Theme.kt` | ColorScheme, Typography, Shapes, `ShadeAccent` CompositionLocal, vurgu sınırlama |
| `icons/` | 88 SVG, 24dp, `ic_` + snake_case; nav ikonlarının `_filled` çiftleri. Yalnızca path/rect/circle/g → Android Studio Vector Asset importer |
| `brand/` | Logo (işaret, yazı, tam, mono), adaptive icon katmanları, bildirim ikonu, splash, 512 Play Store, 1024×500 feature graphic |
| `animations/` | AVD XML: play↔pause morph, çalıyor çubukları, beğeni kalbi; indirme ilerlemesi Compose notu |
| `placeholders/` | Albüm, sanatçı, liste, podcast, radyo yer tutucuları (200dp, nötr) |
| `strings/strings.xml` | Tüm ekranların Türkçe metinleri, anahtar önerileriyle |
| `COMPONENTS.md` | Bileşenler, durumları, dp ölçüleri |
| `REDLINES.md` | Ana sayfa, Liste detayı, Mini oynatıcı, Şimdi Çalıyor ölçüleri |
| `SCREENS.md` | 26 ekranın tamamı için spesifikasyon |
| `screens/` | Faz 1 hi-fi ekranları (HTML, tarayıcıda açılır; Tweaks ile vurgu modu değiştirilebilir) |

---

## 1. Kimlik kuralları

**Kavram: Gölge.** Arayüz nötr, koyu bir sahnedir. Tek renk kaynağı çalan şarkının kapağıdır; bu renk yüzeylere "gölge" gibi düşer (glow, ince kenarlıklar, ilerleme çubukları, seçili durumlar). Marka hiçbir renge bağlı değildir.

- **Kapaklar keskin kalır (6dp), yüzeyler yumuşar (12/20dp), sheet'ler alttan yükselir (28dp üst / 0 alt).**
- **Vurgu rengi asla büyük alan doldurmaz** — yalnızca: birincil buton, aktif ikon, ilerleme, seçili nav göstergesi, rozet, glow. Arka planlar hep nötr.
- **Metin her zaman tam opaklıkta**: text1 / text2 / text3 tokenları; alfa ile soluklaştırma yok (kontrast garantisi).
- **Kaynak bilgisi düz metin rozet**: `YouTube · 320 kbps · %94 eşleşme` — marka logosu yok.
- Emoji yok. Resimli ikon yok; set yalnızca çizgi (1.75dp) + oynatma kontrollerinde dolu.

### Vurgu rengi (3 mod)
`AccentMode.FromArtwork` (varsayılan) · `Fixed` (12 hazır + özel) · `MaterialYou`.
Kural `tokens.json → color.accentRule` ve `Theme.kt → clampAccent`:
1. `Palette` ile Vibrant → DarkVibrant → Muted; kapağın %8'inden azını kaplayanlar elenir.
2. OKLCH'e çevir. Chroma < 0.06 ise nötr fallback.
3. L'yi koyu temada 0.72–0.84, açıkta 0.45–0.58 aralığına sıkıştır.
4. on-accent: L > 0.60 → ink (#0E0F13), değilse beyaz. Her iki durumda ≥ 4.5:1.
5. Türetilmiş: container %18, glow %35, progressTrack %24, outline %50.
6. Parça değişince `animateColorAsState` 600 ms standard easing.

### Tipografi
Bricolage Grotesque (başlık, display) · Instrument Sans (gövde) · JetBrains Mono `tnum` (süre, bitrate, sayaç). Hepsi OFL; `res/font/` içine statik ağırlıklar. Ölçek `tokens.json → typography.scale`; `includeFontPadding = false`.
%200 font ölçeğinde: satırlar `heightIn(min = 60.dp)` ile büyür, başlıklar `maxLines = 2` + ellipsis, nav etiketleri `maxLines = 1`, çipler yatay kaydırır.

### Hareket
Standart 250 ms `cubic(0.2,0,0,1)`; vurgulu 400 ms `cubic(0.05,0.7,0.1,1)`; spring'ler `ShadeMotion`. Mini → Şimdi Çalıyor paylaşımlı kapak geçişi (`SharedTransitionLayout`). "Hareketi azalt" açıkken glow, paylaşımlı geçiş ve spring kapanır → 150 ms fade.

---

## 2. Navigasyon

**4 sekme:** Ana sayfa · Ara · Kitaplık · Yayın (Radyo + Podcast). Mini oynatıcı dört sekmede de nav barının üstünde.

- 5 sekme 412dp'de Almanca/Rusça etiketlerle kırpılır; 4 sekmede etiket başına ~103dp.
- **İndirilenler / Yerel** = Kitaplık filtre çipleri. Aktif indirme varsa Kitaplık ikonunda 2dp ilerleme halkası.
- **İstatistikler / Ayarlar** = sağ üst avatar → Profil ekranı (ayda birkaç kez bakılan şeyler sekme kapmaz).
- Predictive back: detay ekranları `PredictiveBackHandler` ile kapak hedefine doğru küçülür; Şimdi Çalıyor aşağı iner (mini oynatıcıya).

```
Ana sayfa ─ Liste / Albüm / Sanatçı detayı ─ Tümünü gör
Ara ─ Sonuçlar(5 sekme) ─ Link kartı → İçe aktar
Kitaplık ─ Beğenilenler ─ Filtreler ─ Klasörler ─ + (Yeni liste / İçe aktar)
Yayın ─ [Radyo | Podcast] ─ Program → Bölüm ─ RSS ekle
Avatar → Profil ─ İstatistikler ─ Ayarlar ─ Ekolayzer ─ Engellenenler ─ Hakkında
Mini oynatıcı ↑ Şimdi Çalıyor ─ Sıradaki · Sözler · İlgili ─ Karaoke ─ Kaynağı değiştir ─ Şarkı menüsü
```

---

## 3. Compose uygulama notları

### Edge-to-edge
`enableEdgeToEdge()`; `Scaffold(contentWindowInsets = WindowInsets(0))`; nav bar 80dp + `navigationBarsPadding()`; mini oynatıcı `bottom = 80 + 12dp`.

### Kapak glow'u
```kotlin
Modifier.drawBehind {
    drawCircle(Brush.radialGradient(listOf(accent.glow, Color.Transparent), radius = size.minDimension * .9f),
        center = center + Offset(0f, size.height * .1f))
}
```
Kapak için: `Modifier.shadow(30.dp, RoundedCornerShape(8.dp), ambientColor = accent.glow, spotColor = accent.glow)` (API 28+ renkli gölge; altında glow'u drawBehind gradyanı taşır).

### Bulanık arka plan (Şimdi Çalıyor)
API 31+: `Modifier.blur(60.dp)` uygulanmış, 1.2× ölçekli kapak `Image` + üstüne `surface0 @ 70%`.
API < 31 fallback: `drawBehind` radial gradient — merkez `accent.glow`, kenar `surface0` (tasarımda gösterilen de budur; iki yol görsel olarak aynı hedefe gider).

### Mini oynatıcı
`AnchoredDraggable` dikey (collapsed / expanded) + yatay `swipeable` (önceki/sonraki, eşik 40 %). `SharedTransitionLayout` ile kapak (`key = "cover"`) ve başlık taşınır. Arka plan `surface3 @ 90%` + `blur(20.dp)`; API < 31'de düz `surface3`.

### İlerleme çubuğu (tampon dahil)
`Canvas` 4dp: track `text1 @12%` → buffered `accent.progressTrack` → played `accent`; thumb 12dp, sürüklerken 16dp (spring heartPop). `Slider` yerine özel çizim; a11y için `Modifier.progressSemantics`.

### Isı haritası (İstatistikler)
7 × 24 `Canvas`; hücre `(width-6*2dp)/24`, boşluk 2dp, köşe 2dp. Renk: `lerp(surface2, accent, value.pow(.7f))`. Dokununca tooltip `Popup`.

### Ekolayzer eğrisi
10 bant, `Canvas`: noktalar arası Catmull-Rom → cubicTo. Eğri altı `accent @ 18%` dolgu, çizgi 2dp accent, tutamaklar 20dp (hit 48dp). `detectDragGestures` en yakın bandı yakalar; `-12..+12 dB`.

### Karaoke dolum efekti
Aktif satır `Text` iki kez: altta `text3`, üstte `accent`; üstteki `Modifier.drawWithContent { clipRect(right = size.width * progress) { this@drawWithContent.drawContent() } }`. `progress` = (now − lineStart)/(lineEnd − lineStart). Kontroller 3 sn hareketsizlikte 250 ms fade.

### Çalıyor göstergesi
`rememberInfiniteTransition` 3 çubuk, scaleY .35↔1, 1000/800/1100 ms, `transformOrigin(0.5, 1)`. Duraklatınca scaleY .35'e 250 ms iner.

### Shimmer iskeletleri
`surface2` üstünde `surface3` bant, 1200 ms linear, 20° eğim; listeler 6 satır, kartlar 3 adet.

### Snackbar
`surface3`, 12dp köşe, text1, aksiyon accent; nav bar + mini oynatıcı üstünde (`bottom = 80 + 64 + 24dp`).

### Erişilebilirlik
Tüm ikon butonları `contentDescription` (`strings.xml` `_cd` anahtarları). Minimum 48dp hit; 40dp görsel ikon butonları `minimumInteractiveComponentSize()` ile 48'e dolar. Seçili nav öğesi `selected = true` semantiği. İlerleme `progressSemantics`.

### Widget (Glance) ve bildirim
Widget 4×1: kapak 48 + başlık/sanatçı + prev/play/next. 4×2: kapak 96 + ilerleme + 5 kontrol. Arka plan `surface2`, vurgu Material You (`GlanceTheme`). Bildirim: `MediaStyle`, küçük ikon `ic_notification`, aksiyonlar prev/play-pause/next/like.

### Android Auto
`MediaBrowserService` kökü: Ana sayfa (Son çalınanlar, Sana özel, Senin için yapıldı), Kitaplık (Beğenilenler, Listeler, Albümler, İndirilenler), Radyo (Favoriler, Son dinlenenler). Grid stili kapaklı öğeler.

### Tablet / ≥ 840dp
`NavigationRail` (4 öğe + üstte avatar); liste-detay `ListDetailPaneScaffold`. Şimdi Çalıyor: sol 45 % kapak+kontroller, sağ 55 % sözler/sıra sekmeleri.
