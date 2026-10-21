# Dual Earbuds logo

**The mark.** A single source dot sits in the middle. Two mirrored sound arcs come out of it, one teal and one blue. Each arc ends in a round earbud. The band is open at the top: it is one sound split into two, so one source plays to two listeners. The mark uses only flat fills (three paths built from circles and arc bands), so the same geometry works in SVG, Android and WPF.

## Files

| File | What it is |
|---|---|
| `mark.svg` | App icon: the mark on a rounded-square navy tile (512 × 512 viewBox, corner radius 112, subtle top-to-bottom navy gradient). |
| `mark-transparent.svg` | The mark only, no tile, on the same 512 grid. Use it on dark surfaces. |
| `wordmark.svg` | Tile mark + "Dual **Earbuds**" on a navy plate (990 × 288). Text is live SVG text: Inter → Segoe UI → Roboto → Helvetica Neue → Arial → sans-serif. |
| `social-card.svg` | GitHub social preview, 1280 × 640: mark, name, tagline "One sound. Every pair of earbuds." |
| `png/mark-{16,32,48,64,128,256,512}.png` | Raster renders of `mark.svg`. |
| `png/mark-transparent-512.png` | Raster of `mark-transparent.svg`. |
| `png/social-card-1280x640.png` | Upload this under GitHub → Settings → Social preview. |
| `png/wordmark.png` | Raster of `wordmark.svg` (990 × 288). |
| `android-foreground.xml` | Android VectorDrawable for the adaptive-icon foreground. The viewport is 108 × 108, and the mark stays inside the 66 dp safe zone (its farthest point is about 31.5 dp from the centre). |
| `android-background-color.txt` | Adaptive-icon background colour (`#0B1220`). |
| `wpf-geometry.txt` | The mark as WPF path markup in a 24 × 24 space. Each line is `<fill hex>\|<markup>`, and the lines are drawn in order. Lines that start with `//` are comments. |

### Android

```xml
<!-- res/mipmap-anydpi-v26/ic_launcher.xml -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>  <!-- #0B1220 -->
    <foreground android:drawable="@drawable/ic_launcher_foreground"/> <!-- android-foreground.xml -->
</adaptive-icon>
```

### WPF

```csharp
foreach (var line in File.ReadLines("wpf-geometry.txt").Where(l => l.Length > 0 && !l.StartsWith("//")))
{
    var parts = line.Split('|');
    canvas.Children.Add(new Path {   // canvas is 24x24, wrapped in a Viewbox
        Fill = (Brush)new BrushConverter().ConvertFrom(parts[0]),
        Data = Geometry.Parse(parts[1]) });
}
```

## Colours

| Role | Hex |
|---|---|
| Navy (tile, backgrounds) | `#0B1220` |
| Navy, tile gradient top | `#16233D` |
| Teal accent (left arc, source dot, "Earbuds") | `#14E0C2` |
| Blue secondary (right arc) | `#3B82F6` |
| Text on navy | `#F1F5F9` |
| Muted text (tagline) | `#94A3B8` |

The left side is always teal and the right side is always blue. Do not swap them, recolour them, or add effects such as outlines, shadows or glows to the mark itself.

## Clear space

Take the diameter of the centre source dot as **x** (92 units on the 512 grid, about 18% of the tile). Keep at least **1x** of empty space around the tile or the transparent mark, and around the full wordmark. Nothing else may enter that area.

## Minimum size

- Tile icon (`mark.svg`): **16 px**. It was checked by eye at 16, 32 and 48 px: the two colours and the centre dot stay distinct. Below 16 px, do not use the mark.
- Transparent mark: **20 px**. Use it only on a dark background (navy or darker). On light backgrounds, use the tile.
- Wordmark: **160 px wide**, which keeps the text at about 12 px high. Below that, use the tile icon alone.

## Regenerating PNGs

```bash
for s in 16 32 48 64 128 256 512; do rsvg-convert -w $s -h $s mark.svg -o png/mark-$s.png; done
rsvg-convert -w 1280 -h 640 social-card.svg -o png/social-card-1280x640.png
```

(The committed PNGs were rendered with CairoSVG. Social card and wordmark text render in Inter if it is installed.)
