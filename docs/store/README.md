# Store assets

Files here are the Play listing's visual package. They are **assets, not
evidence**: the gate verdicts behind them live in
`docs/progress/phase10-evidence/`, and the claims they illustrate are the ones
`docs/STORE-LISTING.md` makes.

| File | Requirement | Source |
|---|---|---|
| `icon-512.png` | 512x512 PNG, the listing icon | upstream OpenCode icon, `packages/desktop/icons/prod/icon.png` at the pinned commit |
| `feature-graphic-1024x500.png` | 1024x500 PNG | composed locally (see below) |
| `screenshots/*.png` | at least 2, 320-3840 px per side, aspect ratio at most 2:1 | captured **on a device** from the app running, by `phase10/scripts/70-device-screenshots.sh` (CI emulator at 1080x1920) or `phase10/scripts/90-real-device-signed.sh` (the signed build on the owner's phone - prefer these) |

## How the feature graphic is made

No image editor, no design tool, and nothing hand-drawn: the wordmark is
upstream's own asset and the composite is two ImageMagick commands, so anyone can
reproduce it byte-for-byte from the pinned sources.

```bash
# 1. upstream wordmark (from packages/console/app/src/asset/brand/opencode-brand-assets.zip
#    at the pinned commit - light-on-transparent variant, so it reads on the light plate)
W=opencode-wordmark-light.png
convert -size 1024x500 xc:'#F7F8FB' \
  \( "$W" -resize 560x \) -gravity center -geometry +0-78 -composite \
  -font DejaVu-Sans -pointsize 32 -fill '#14181F' -gravity center \
    -annotate +0+52  'The coding agent that runs on your phone.' \
  -font DejaVu-Sans -pointsize 22 -fill '#5A6273' -gravity center \
    -annotate +0+104 'Real shell, files and Git, on-device. No PC, no account.' \
  -depth 8 feature-graphic-1024x500.png

# 2. the listing icon is upstream's, unmodified
cp prod/icon.png icon-512.png
```

The plate colour (`#F7F8FB`) and text colours (`#14181F`, `#5A6273`) are this
app's own light-theme roles from `ui/theme/Color.kt` (`PaperBackground`,
`PaperOnSurface`, `PaperOnSurfaceMuted`) - the graphic and the app look like the
same product, which is the point. No third-party app's colours or illustrations
are used: see `docs/BRANDING.md` for why that distinction is enforced here.

## Rule for screenshots

If a UI change alters any screen, the screenshots are stale the moment it lands.
Re-shoot rather than edit: `60-store-assets.sh` validates dimensions and ratios,
and the only way it accepts an image is if a real device produced it.
