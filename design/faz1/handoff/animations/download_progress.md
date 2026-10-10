# İndirme ilerlemesi (Compose, Canvas)

Üç durum, tek composable `DownloadIndicator(state)`:

| Durum | Görünüm | Süre / easing |
|---|---|---|
| `Queued` | 24dp `ic_download`, text3 renginde | — |
| `Downloading(progress)` | 20dp halka, trackWidth 2dp, track = accent @24 %, arc = accent, başlangıç −90°; ortada 8dp kare (durdur) | progress `animateFloatAsState` 250 ms standard; `progress == null` ise belirsiz: 1200 ms linear dönen 270° arc |
| `Done` | halka 150 ms'de accent'e dolar → 100 ms sonra `ic_downloaded` 1→1.15→1 ölçek (heartPop spring) | toplam ≈ 400 ms |

Hata: halka `error` rengine 150 ms geçer, ikon `ic_refresh`, dokununca yeniden dener.

```kotlin
Canvas(Modifier.size(20.dp)) {
    val stroke = 2.dp.toPx()
    drawArc(track, 0f, 360f, false, style = Stroke(stroke))
    drawArc(accent, -90f, 360f * p, false, style = Stroke(stroke, cap = StrokeCap.Round))
    drawRect(accent, topLeft = center - Offset(4.dp.toPx(), 4.dp.toPx()), size = Size(8.dp.toPx(), 8.dp.toPx()))
}
```
