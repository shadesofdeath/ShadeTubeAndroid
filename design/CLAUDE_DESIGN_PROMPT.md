ShadeTube adında bir Android müzik uygulaması için sıfırdan, özgün bir arayüz ve eksiksiz bir geliştirici handoff paketi tasarlamanı istiyorum. Uygulama Kotlin + Jetpack Compose ile yazılacak; tasarımın doğrudan koda çevrilebilir olması şart.

## Uygulama ne yapıyor
ShadeTube, kullanıcının Spotify hesabındaki kitaplığı (çalma listeleri, beğenilen şarkılar, kaydedilen albümler, takip edilen sanatçılar, kişisel ana sayfa akışı) gösteren, ama her şarkıyı YouTube'daki eşleşen kayıttan çalan bir müzik çalar. Reklam yok, Premium gerekmiyor. Spotify bağlamadan da açık müzik kataloğuyla (MusicBrainz) kullanılabiliyor. Ayrıca internet radyosu, podcast, yerel müzik dosyaları, MP3 indirme, senkronize şarkı sözleri ve dinleme istatistikleri var. Hiçbir sunucusu yok, veri toplamıyor; kullanıcı kitlesi müziği ciddiye alan, reklamdan bıkmış, "hafif ve hızlı" uygulama seven insanlar.

## Görsel kimlik — sen oluştur
- Spotify, YouTube Music veya jenerik Material 3 kopyası OLMASIN. Kendine ait, akılda kalan, karakterli bir kimlik istiyorum. Logoyu, uygulama ikonunu, renk paletini, tipografiyi, ikon setini, şekil dilini ve hareket dilini sen belirle.
- Koyu tema öncelikli; açık tema da tam karşılığıyla olacak. Sistem teması seçeneği.
- Vurgu rengi 3 modda çalışmalı: çalan şarkının albüm kapağından otomatik çıkarılan renk (varsayılan), kullanıcının seçtiği sabit renk, Material You (duvar kağıdı rengi). Bu yüzden tasarımı tek bir renge bağımlı kurma; ekranları en az iki farklı vurgu rengiyle göster ve kapaktan renk çıkarma kuralını (doygunluk/açıklık sınırları, vurgunun üstündeki yazının kontrast kuralı, türetilmiş tonlar) tanımla.
- Tipografi: Google Fonts'ta bulunan, OFL lisanslı fontlar seç (Android'de res/font ile kullanılacak). Süreler, sayaçlar, bitrate gibi teknik bilgiler için tabular rakamlı ikincil bir font kullanabilirsin.
- Material 3 davranışlarını kullan (bottom sheet, snackbar, navigation bar, predictive back, edge-to-edge, pull-to-refresh) ama görünüş tamamen ShadeTube'a ait olsun.
- Arayüz dili Türkçe. Uygulama 11 dili destekliyor (TR, EN, DE, ES, FR, PT-BR, RU, UK, ID, JA, KO); kritik bileşenlerin uzun Almanca/Rusça metinle taşmadığını da göster.

## Navigasyon
Alt navigation bar + her ekranda navigasyon barının üstünde duran mini oynatıcı. Sekme sayısını ve hangi bölümlerin nerede duracağını sen öner (Ana sayfa, Ara, Kitaplık, Radyo/Podcast, İndirilenler, İstatistikler, Ayarlar). Kararını gerekçesiyle yaz ve bir navigasyon haritası çiz.

## Ekranlar (412×915dp telefon, koyu tema; ana ekranlar açık temada da)
1. Splash (Android 12+ SplashScreen API uyumlu).
2. Karşılama / giriş: "Spotify'a bağlan" (Spotify'ın kendi giriş sayfası WebView'da açılır, uygulama şifreyi görmez) veya "Spotify olmadan keşfet". Bildirim ve müzik dosyası izin istekleri.
3. Ana sayfa: selamlama; Son çalınanlar; Sana özel (Daily Mix, Discover Weekly gibi Spotify listeleri); Senin için yapıldı (cihazda üretilen akıllı listeler: günlük mixler, Bu ayın favorileri, Yeni keşiflerin, Yeniden keşfet, Unutulan beğeniler, Tüm zamanların en iyileri, 2025'in en iyileri); Yeni çıkanlar; Popüler sanatçılar; Çalma listelerin; "X dinleyenler bunları da seviyor".
4. Ara: arama kutusu, son aramalar, tür/kategori karoları, sonuç sekmeleri (Şarkılar, Albümler, Sanatçılar, Listeler, Podcastler). Spotify/YouTube linki yapıştırılınca "Link algılandı — aç / çal" kartı.
5. Kitaplık: filtre çipleri (Listeler, Albümler, Sanatçılar, İndirilenler, Yerel, Podcastler), liste klasörleri, en üstte sabit Beğenilen Şarkılar, grid/liste görünümü, sıralama, "+" ile yeni liste veya içe aktar.
6. Çalma listesi detayı: kapak, başlık, sahibi, şarkı sayısı ve toplam süre; Çal, Karıştır, Geliştir, Çevrimdışı yap, Diğer. "Geliştir" açılınca listeye uyan önerilen şarkılar aralara karışır, "Önerilen" işaretiyle görünür; her birinde "listeye ekle" ve "gizle". Listede ara, sıralama, çevrimdışı senkron ilerlemesi, boş liste durumu.
7. Beğenilen Şarkılar (boş durum dahil).
8. Albüm detayı. 9. Sanatçı detayı: popüler şarkılar, Albümler/Tekliler, benzer sanatçılar, Takip et, Radyo başlat.
10. Mini oynatıcı: kapak, başlık/sanatçı, çal/duraklat, ince ilerleme çizgisi; yana kaydırınca sonraki/önceki, yukarı kaydırınca Şimdi Çalıyor.
11. Şimdi Çalıyor (tam ekran): büyük kapak; başlık, sanatçı, beğen; ilerleme çubuğu (tamponlanan kısım dahil) ve süreler; karıştır (ikinci dokunuşta "akıllı karıştır": sıraya öneri şarkılar karışır), önceki, çal/duraklat, sonraki, tekrar/tek şarkı tekrar. Kaynak bilgisi ("YouTube · 320 kbps · %94 eşleşme") ve "Yanlış eşleşme?" bağlantısı; hız (0.5×–3×), uyku zamanlayıcı, çıkış cihazı, paylaş. Alttan çekilen sekmeler: Sıradaki · Şarkı sözleri · İlgili.
12. Senkronize şarkı sözleri: Şimdi Çalıyor içinde (aktif satır öne çıkar, satıra dokununca oraya atlar, söz kaynağı yazar, zamanlama ±0.25 sn kaydırılabilir) ve tam ekran karaoke görünümü (satırlar söylendikçe dolar, kontroller kendiliğinden gizlenir). "Söz bulunamadı" ve "Enstrümantal" durumları.
13. Sıra: sürükle-bırak sıralama, kaydırarak kaldırma, akıllı karıştırmanın eklediği "Önerilen" şarkılar, Az önce çalanlar, Sırayı temizle / Dışa aktar.
14. YouTube kaynağını değiştir (bottom sheet): aday videolar (küçük resim, kanal, süre, eşleşme yüzdesi), önizle, seç.
15. Şarkı menüsü (bottom sheet): Sonra çal, Sıraya ekle, Listeye ekle, Beğen, İndir, Radyo başlat, Sanatçıya git, Albüme git, Kaynağı değiştir, Engelle (şarkı/sanatçı), Paylaş, Bağlantıyı kopyala, Çevrimdışından çıkar, İndirileni sil.
16. İnternet radyosu: türler, ülkeler, popüler istasyonlar, arama, favoriler, son dinlenenler; canlı yayın oynatma durumu ("Canlı" rozeti, şu an çalan şarkı, "Bağlanıyor…").
17. Podcastler: arama, ülkendeki popüler programlar, RSS adresiyle ekle, abonelikler (yeni bölüm sayısı rozeti), program detayı, bölüm detayı (açıklama, kaldığın yerden devam ilerlemesi, oynatıldı işaretle, indir).
18. İndirilenler: aktif indirmeler, çevrimdışı senkronize koleksiyonlar (ilerleme, Şimdi senkronla, Duraklat, Tekrar dene), hariç tutulan şarkılar, depolama kullanımı.
19. Yerel dosyalar: klasör ekle, şarkı / albüm / sanatçı görünümleri.
20. İstatistikler: 7 gün / 30 gün / Tüm zamanlar; toplam dinleme süresi, en çok dinlenen şarkılar/sanatçılar/albümler, gün × saat ısı haritası, Yıl özeti (paylaşılabilir 9:16 story kartı), "Spotify geçmişini içe aktar".
21. Ayarlar ve alt sayfaları: Spotify hesabı / çıkış; Bağlantılar (Last.fm, ListenBrainz); Oynatma (ses kalitesi, sonrakini önceden yükle, sponsor/intro atlama, akıllı karıştırma, sıra bitince benzer şarkılarla devam, yedek ses kaynağı); Ses (crossfade 0–12 sn, ses seviyesi eşitleme, hız); Engellenenler; Görünüm (tema, vurgu modu ve renk paleti, hareketi azalt, dil); İndirme (MP3 kalitesi, sözleri dosyaya yaz, klasör, yalnızca Wi-Fi, depolama sınırı); Yerel müzik; Depolama (önbelleği temizle); Hakkında (sürüm, güncelleme, lisanslar).
22. Ekolayzer: 10 bant, parmakla sürüklenen eğri, preamp, hazır ayarlar.
23. İçe/dışa aktarma: dosya seç → önizleme (bulunan şarkılar / okunamayan satırlar) → oluştur; YouTube playlist linkinden içe aktarma; dışa aktarma formatı seçimi (M3U8, CSV, XSPF, JSON).
24. Genel durumlar: yükleme iskeletleri (shimmer), boş durumlar, hata, çevrimdışı uyarısı, snackbar, onay diyaloğu, izin reddedildi.
25. Sistem yüzeyleri: medya bildirimi, kilit ekranı, ana ekran widget'ları (4×1 ve 4×2, Jetpack Glance ile yapılabilir), Android Auto göz atma listesi.
26. Tablet / katlanabilir (≥840dp): navigation rail + liste-detay düzeni; Şimdi Çalıyor'da kapak bir yanda, sözler/sıra diğer yanda. En az Ana sayfa ve Şimdi Çalıyor için.

## Handoff paketi
Bir geliştiricinin hiçbir şey sormadan uygulayabileceği bir paket:
- README: tasarım sisteminin kuralları, ekran haritası, navigasyon haritası, Compose'da nasıl uygulanacağına dair notlar (özel çizimler için: kapak glow'u, bulanık arka plan ve Android 12 altı fallback'i, ısı haritası, ekolayzer eğrisi, karaoke dolum efekti).
- Design token'ları (koyu + açık) JSON olarak: renkler, tipografi ölçeği (sp, ağırlık, satır yüksekliği, harf aralığı), spacing (dp, 4dp ızgara), köşe yarıçapları, elevation, motion (süreler, easing eğrileri, spring parametreleri). Ayrıca bunları uygulayan örnek bir Theme.kt (ColorScheme, Typography, Shapes + Material'de karşılığı olmayan token'lar için özel CompositionLocal).
- Uygulama ikonu: adaptive icon katmanları ayrı SVG olarak (foreground 108dp tuval / 66dp güvenli alan, background, Android 13 temalı ikon için monochrome), 512×512 Play Store ikonu, 1024×500 feature graphic. Tek renkli bildirim ikonu (24dp). Splash ikonu (SplashScreen ölçülerinde). Logo: işaret, yazı, tam logo, tek renk siyah/beyaz versiyonlar.
- İkon seti: 24dp grid, tutarlı çizgi kalınlığı, Android VectorDrawable'a çevrilebilir SVG (yalnızca path/rect/circle/g; filter, mask, text, style, use, clipPath yok), kebab-case isimler; alt navigasyon ikonlarının boş + dolu (seçili) çiftleri. En az şunlar: home, search, library, radio, podcast, stats, settings, user, play, pause, stop, next, prev, shuffle, shuffle-smart, repeat, repeat-one, queue, play-next, add-to-queue, lyrics, fullscreen, heart, heart-filled, download, downloaded, offline-sync, offline, wifi, playlist, album, artist, music-note, plus, minus, check, close, more-vertical, drag-handle, edit, trash, share, link, external-link, filter, sort, grid, list, folder, import, export, equalizer, speed, sleep-timer, speaker, headphones, bluetooth, volume-mute/low/mid/high, block, sparkle, swap-source, explicit, rss, live, history, calendar, clock, storage, info, warning, error, refresh, lock, logout, palette, chevron-up/down/left/right, arrow-back.
- Animasyonlar: play↔pause morph, "çalıyor" ekolayzer göstergesi, beğeni kalbi, indirme ilerlemesi — AnimatedVectorDrawable'a uygun (morph edilen path'ler aynı komut yapısında) ya da Lottie JSON; her animasyon için süre ve easing.
- Yer tutucu görseller: albüm, sanatçı, çalma listesi, podcast, radyo istasyonu.
- Bileşen sayfaları: her bileşenin tüm durumları (normal, basılı, odaklı, devre dışı, seçili, yükleniyor) ve dp ölçüleri.
- Tüm ekranlar ve ana ekranların ölçü/boşluk işaretli (redline) versiyonları: Ana sayfa, Çalma listesi, Mini oynatıcı, Şimdi Çalıyor.
- Tüm ekranlardaki Türkçe metinlerin listesi, strings.xml anahtar önerileriyle.

## Kısıtlar
- Dokunma hedefleri en az 48dp; WCAG AA kontrast (dinamik vurgu renginde de); %200 font ölçeğinde bozulmayan düzen; ikon butonları için erişilebilirlik etiketi önerileri.
- Kapaklar ve isimler sahte ama gerçekçi olsun; gerçek sanatçı fotoğrafı veya marka logosu kullanma (Spotify/YouTube kaynak bilgisi düz metin rozet olarak gösterilsin).

## Çalışma şekli
Önce görsel kimlik önerini (logo + uygulama ikonu, renk paleti, fontlar, birkaç örnek ikon) ve navigasyon önerini göster; ardından Ana sayfa, Şimdi Çalıyor ve Çalma listesi detayı ekranlarını koyu temada tasarla. Onayımdan sonra kalan ekranlara ve handoff paketinin tamamına geç.
