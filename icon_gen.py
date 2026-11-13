#!/usr/bin/env python3
"""LinkSave v1.14 — adaptive launcher icon artwork generator.

Generates, from the in-app orb colours (MainActivity.java: radial gradient
#A7F3D0 -> #34D399 -> #059669 -> #064E3B, highlight up-left):
  - res/drawable-xxxhdpi/ic_launcher_background.png  (432x432, opaque, full bleed)
  - res/drawable-xxxhdpi/ic_launcher_foreground.png  (432x432, transparent,
      white download arrow + tray centred in the 66dp safe zone)
  - res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png (legacy,
      full-bleed green square + arrow, NO black)
  - res/mipmap-{...}/ic_launcher_round.png (legacy round, circular crop)
Plus /tmp preview composites (circle + squircle masks) for visual checking.

Adaptive-icon facts baked in: layers are 108dp; only the centre 72dp is
ever visible; foreground content must stay inside the 66dp safe zone.
xxxhdpi = 4 px/dp, so layers are 432px. Shapes are drawn at 2x and
downscaled for smooth antialiasing.
"""
import math
import os

import numpy as np
from PIL import Image, ImageDraw

PROJ = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(PROJ, "app/src/main/res")

# Orb gradient stops from MainActivity.java (offset, RGB).
STOPS = [(0.00, (0xA7, 0xF3, 0xD0)),
         (0.35, (0x34, 0xD3, 0x99)),
         (0.70, (0x05, 0x96, 0x69)),
         (1.00, (0x06, 0x4E, 0x3B))]

SS = 2            # supersample factor for shape drawing
DP = 4            # px per dp at xxxhdpi
CANVAS = 108 * DP  # 432


def gradient_color(t):
    """t in [0,1] -> interpolated RGB from STOPS (numpy-friendly)."""
    offs = np.array([s[0] for s in STOPS])
    cols = np.array([s[1] for s in STOPS], dtype=np.float64)
    out = np.zeros(t.shape + (3,), dtype=np.float64)
    for ch in range(3):
        out[..., ch] = np.interp(t, offs, cols[:, ch])
    return out


def make_background(size=CANVAS):
    """Opaque radial-gradient background, orb geometry: gradient centre
    offset up-left (cx - 0.10r, cy - 0.24r), radius 0.72r, r = half canvas.
    Plus a soft specular glow upper-left like the in-app orb."""
    r = size / 2.0
    cx, cy = size / 2.0 - 0.10 * r, size / 2.0 - 0.24 * r
    grad_r = 0.72 * r
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float64)
    dist = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / grad_r
    rgb = gradient_color(np.clip(dist, 0, 1))
    # soft specular glow: white, alpha ~70 at centre, fading to 0.
    sx, sy = size * 0.36, size * 0.28
    srad = size * 0.16
    sdist = np.sqrt((xx - sx) ** 2 + (yy - sy) ** 2) / srad
    glow = np.clip(1.0 - sdist, 0, 1) ** 2 * 70
    rgb = rgb + glow[..., None]  # lighten toward white
    rgb = np.clip(rgb, 0, 255).astype(np.uint8)
    alpha = np.full((size, size), 255, dtype=np.uint8)
    return Image.fromarray(np.dstack([rgb, alpha]), "RGBA")


def rounded_rect(draw, box, radius, fill):
    draw.rounded_rectangle(box, radius=radius, fill=fill)


def make_foreground(size=CANVAS):
    """Transparent layer: white download arrow + tray, centred, inside the
    66dp safe zone. Drawn at 2x then downscaled for antialiasing."""
    S = size * SS
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    u = DP * SS  # px per dp at supersample scale
    cx = S / 2.0
    white = (255, 255, 255, 255)
    # Arrow group, vertically centred on the canvas (span 31.5dp..76.5dp).
    # Shaft: 14dp wide rounded-top bar, y 31.5dp..52.5dp.
    shaft_w = 14 * u
    shaft_top, shaft_bot = 31.5 * u, 52.5 * u
    rounded_rect(d, [cx - shaft_w / 2, shaft_top, cx + shaft_w / 2, shaft_bot],
                 radius=shaft_w / 2, fill=white)
    # Head: triangle, base 30dp wide at shaft bottom, apex at 64dp.
    head_base, head_apex = 52.5 * u, 64 * u
    head_half = 15 * u
    d.polygon([(cx - head_half, head_base), (cx + head_half, head_base),
               (cx, head_apex)], fill=white)
    # Tray: 44dp wide, 5dp tall rounded bar, y 71.5dp..76.5dp.
    tray_w, tray_h = 44 * u, 5 * u
    tray_top = 71.5 * u
    rounded_rect(d, [cx - tray_w / 2, tray_top, cx + tray_w / 2, tray_top + tray_h],
                 radius=tray_h / 2, fill=white)
    return img.resize((size, size), Image.LANCZOS)


def circle_mask(size):
    """Antialiased circular alpha mask (drawn 4x, downscaled)."""
    S = size * 4
    m = Image.new("L", (S, S), 0)
    ImageDraw.Draw(m).ellipse([0, 0, S - 1, S - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def squircle_mask(size, n=4.5):
    """Superellipse (squircle-ish) mask for the preview only."""
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float64)
    nx = (xx - size / 2) / (size / 2)
    ny = (yy - size / 2) / (size / 2)
    d = (np.abs(nx) ** n + np.abs(ny) ** n) ** (1.0 / n)
    a = np.clip((1.0 - d) * size / 2, 0, 1)  # ~1px feather
    return Image.fromarray((a * 255).astype(np.uint8), "L")


def main():
    bg = make_background()
    fg = make_foreground()
    assert bg.size == (CANVAS, CANVAS) and fg.size == (CANVAS, CANVAS)

    # --- adaptive layers (xxxhdpi drawables) ---
    draw_dir = os.path.join(RES, "drawable-xxxhdpi")
    os.makedirs(draw_dir, exist_ok=True)
    bg.save(os.path.join(draw_dir, "ic_launcher_background.png"))
    fg.save(os.path.join(draw_dir, "ic_launcher_foreground.png"))

    # --- legacy icons, all densities ---
    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96,
                 "xxhdpi": 144, "xxxhdpi": 192}
    for dens, px in densities.items():
        d = os.path.join(RES, "mipmap-" + dens)
        os.makedirs(d, exist_ok=True)
        comp = bg.resize((px, px), Image.LANCZOS)
        comp.alpha_composite(fg.resize((px, px), Image.LANCZOS))
        comp.save(os.path.join(d, "ic_launcher.png"))
        rnd = comp.copy()
        rnd.putalpha(circle_mask(px))
        rnd.save(os.path.join(d, "ic_launcher_round.png"))

    # --- previews: circle + squircle masks over the full 432 artwork ---
    full = bg.copy()
    full.alpha_composite(fg)
    prev = Image.new("RGBA", (CANVAS * 2 + 40, CANVAS + 20), (245, 245, 245, 255))
    circ = full.copy()
    circ.putalpha(circle_mask(CANVAS))
    prev.alpha_composite(circ, (10, 10))
    sq = full.copy()
    sq.putalpha(squircle_mask(CANVAS))
    prev.alpha_composite(sq, (CANVAS + 30, 10))
    prev.convert("RGB").save("/tmp/icon_preview.png")
    print("wrote layers + legacy icons + /tmp/icon_preview.png")


if __name__ == "__main__":
    main()
