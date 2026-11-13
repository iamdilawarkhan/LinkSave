#!/usr/bin/env python3
"""LinkSave v1.17 — launcher icon recomposition: BIGGER robot.

v1.16 fixed the invisible corner badges (moved inward into the safe zone)
but the robot ended up too small on the user's OnePlus home screen
(screenshot 2026-11-13 14:21 PKT: robot ~46% of displayed icon width).

Measured OnePlus behaviour (from that screenshot, badge centres in the
v1.16 foreground vs on-screen): OxygenOS displays the central ~294px of
the 432px adaptive layers (scale ~1.47x). Design zone used here is the
central 285px (73..359) for a safety margin; badges are kept fully inside
the conservative 66dp safe zone (84..348, 264px) as well.

This script rebuilds the foreground from the 1600px mascot source at
1.20x the v1.16 artwork scale (robot ~55% of displayed width, ~91% of
displayed height), badges re-composited at 52px from the official brand
PNGs, same positions/order as v1.16 (IG top-left, YT top-right, TikTok
bottom-left, FB bottom-right). Background layer is UNCHANGED (v1.15
blurred artwork). Legacy mipmap PNGs are regenerated the same way as
icon_gen.py (flatten bg+fg, resize; round = circle mask).

Outputs:
  logo_assets/icon_fg_v117.png
  app/src/main/res/drawable-xxxhdpi/ic_launcher_foreground.png
  app/src/main/res/mipmap-*/ic_launcher.png + ic_launcher_round.png
  /tmp/icon_v117_sim.png  (OnePlus 294 crop, conservative 264 crop,
                           circle mask, squircle mask, v1.16 comparison)
"""
import os

import numpy as np
from PIL import Image, ImageDraw

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(PROJ, "app/src/main/res")
ASSETS = os.path.join(PROJ, "logo_assets")
CANVAS = 432

MASCOT = ("/home/hatch/workspace/imagine_media/media-generation-linksave-"
          "logo-mascot-3d-arrow-0-6f3e5b0e-0413-4024-a5bd-eea4178e2f1b.webp")

SCALE = 1.20          # artwork scale relative to v1.16
V116_IMG = 284        # v1.16 full-source-image size in the 432 canvas
BADGE_D = 52          # badge diameter (v1.16: 56)
BADGE_CENTRES = [(110, 110), (322, 110), (110, 322), (322, 322)]
BADGE_FILES = ["instagram.png", "youtube.png", "tiktok.png", "facebook.png"]
LOGO_FRAC = 0.55      # logo size as fraction of badge diameter


def remove_grey_background(img):
    """Make the light-grey backdrop of the mascot source transparent.

    Background candidates: near-grey (low saturation) and bright. Keep
    only the connected region touching the border (flood fill from a
    corner), so the enclosed white artwork (arrow, play button, eye
    whites) is untouched.
    """
    a = np.array(img.convert("RGB")).astype(int)
    mx = a.max(axis=2)
    mn = a.min(axis=2)
    grey = ((mx - mn) < 14) & (mx > 195)
    # Border-connected grey component(s) only (scipy label; PIL's
    # floodfill silently filled nothing here, which left a white frame).
    from scipy import ndimage as ndi
    lab, _ = ndi.label(grey)
    border = set(np.unique(np.concatenate(
        [lab[0, :], lab[-1, :], lab[:, 0], lab[:, -1]]))) - {0}
    filled = np.isin(lab, list(border))
    rgba = np.array(img.convert("RGBA"))
    rgba[filled, 3] = 0
    return Image.fromarray(rgba, "RGBA")


def resize_premultiplied(img, size):
    """Resize RGBA with premultiplied alpha, so the (light-grey) RGB of
    transparent pixels cannot bleed a pale fringe into the tile edge
    during LANCZOS resampling."""
    a = np.array(img.convert("RGBA")).astype(np.float32)
    alpha = a[..., 3:4] / 255.0
    a[..., :3] *= alpha
    r = Image.fromarray(a.astype(np.uint8), "RGBA").resize(size, Image.LANCZOS)
    b = np.array(r).astype(np.float32)
    al = b[..., 3:4] / 255.0
    b[..., :3] = np.divide(b[..., :3], al, out=np.zeros_like(b[..., :3]),
                           where=al > 0.01)
    return Image.fromarray(np.clip(b, 0, 255).astype(np.uint8), "RGBA")


def make_foreground():
    src = Image.open(MASCOT)
    cut = remove_grey_background(src)
    size = int(round(V116_IMG * SCALE))
    art = resize_premultiplied(cut, (size, size))
    fg = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    off = (CANVAS - size) // 2
    fg.alpha_composite(art, (off, off))
    # Badges: white circles + brand logos.
    for (cx, cy), fn in zip(BADGE_CENTRES, BADGE_FILES):
        d = BADGE_D
        badge = Image.new("RGBA", (d * 4, d * 4), (0, 0, 0, 0))
        bd = ImageDraw.Draw(badge)
        bd.ellipse([0, 0, d * 4 - 1, d * 4 - 1], fill=(255, 255, 255, 255))
        badge = badge.resize((d, d), Image.LANCZOS)
        logo = Image.open(os.path.join(ASSETS, fn)).convert("RGBA")
        lw = int(round(d * LOGO_FRAC))
        # fit logo by its opaque bbox so glyphs (not padding) set the size
        bbox = logo.getbbox()
        logo = logo.crop(bbox)
        logo.thumbnail((lw, lw), Image.LANCZOS)
        badge.alpha_composite(logo, ((d - logo.width) // 2,
                                     (d - logo.height) // 2))
        fg.alpha_composite(badge, (cx - d // 2, cy - d // 2))
    return fg


def circle_mask(size):
    S = size * 4
    m = Image.new("L", (S, S), 0)
    ImageDraw.Draw(m).ellipse([0, 0, S - 1, S - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def squircle_mask(size, n=4.5):
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float64)
    nx = (xx - size / 2) / (size / 2)
    ny = (yy - size / 2) / (size / 2)
    d = (np.abs(nx) ** n + np.abs(ny) ** n) ** (1.0 / n)
    a = np.clip((1.0 - d) * size / 2, 0, 1)
    return Image.fromarray((a * 255).astype(np.uint8), "L")


def crop_centre(img, px):
    o = (CANVAS - px) // 2
    return img.crop((o, o, o + px, o + px))


def main():
    fg = make_foreground()
    fg.save(os.path.join(ASSETS, "icon_fg_v117.png"))
    fg.save(os.path.join(RES, "drawable-xxxhdpi/ic_launcher_foreground.png"))

    bg = Image.open(os.path.join(
        RES, "drawable-xxxhdpi/ic_launcher_background.png")).convert("RGBA")
    full = bg.copy()
    full.alpha_composite(fg)

    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96,
                 "xxhdpi": 144, "xxxhdpi": 192}
    for dens, px in densities.items():
        d = os.path.join(RES, "mipmap-" + dens)
        os.makedirs(d, exist_ok=True)
        comp = full.resize((px, px), Image.LANCZOS)
        comp.save(os.path.join(d, "ic_launcher.png"))
        rnd = comp.copy()
        rnd.putalpha(circle_mask(px))
        rnd.save(os.path.join(d, "ic_launcher_round.png"))

    # ---- simulations ----
    old_fg = Image.open(os.path.join(ASSETS, "icon_fg_v116.png")).convert("RGBA")
    old_full = bg.copy()
    old_full.alpha_composite(old_fg)

    S = 300  # preview tile size
    tiles = []
    labels = []
    for title, img in [
        ("v1.17 OnePlus crop (294px)", crop_centre(full, 294)),
        ("v1.16 OnePlus crop (294px)", crop_centre(old_full, 294)),
        ("v1.17 safe-zone crop (264px)", crop_centre(full, 264)),
        ("v1.17 circle mask", full.copy()),
        ("v1.17 squircle mask", full.copy()),
    ]:
        t = img.resize((S, S), Image.LANCZOS)
        if "circle" in title:
            t.putalpha(circle_mask(S))
        if "squircle" in title:
            t.putalpha(squircle_mask(S))
        tiles.append(t)
        labels.append(title)

    cols, rows = 3, 2
    pad, lab_h = 12, 34
    sheet = Image.new("RGB", (cols * (S + pad) + pad,
                              rows * (S + lab_h + pad) + pad), (238, 238, 238))
    dr = ImageDraw.Draw(sheet)
    for i, (t, lab) in enumerate(zip(tiles, labels)):
        r, c = divmod(i, cols)
        x = pad + c * (S + pad)
        y = pad + r * (S + lab_h + pad)
        sheet.paste(t, (x, y + lab_h), t)
        dr.text((x + 4, y + 8), lab, fill=(20, 20, 20))
    sheet.save("/tmp/icon_v117_sim.png")
    print("wrote foreground + legacy icons + /tmp/icon_v117_sim.png")
    print("fg size:", fg.size, "| badge centres:", BADGE_CENTRES,
          "| badge d:", BADGE_D, "| art size:", int(round(V116_IMG * SCALE)))


if __name__ == "__main__":
    main()
