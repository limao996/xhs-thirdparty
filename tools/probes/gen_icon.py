"""Generate the launcher icon set.

Design: a bold golden open book on deep plum.

Why not a play button: it is the single most over-used mark in the store, and it
says nothing about this app. 小黄书 is a *book* — a two-page silhouette in gold on
a dark ground is distinctive, reads instantly at 48px (see
tools/icon_candidates.py for the comparison sheet), and the gold ties back to the
name.

Adaptive icons (API 26+) take a TRANSPARENT foreground on a colour background and
only guarantee the centre 66 of 108dp; the launcher may mask the rest. Legacy
icons (API < 26) keep the background baked in.
"""
import os
import pathlib
from PIL import Image, ImageDraw

# 仓库根 = tools/probes/<this file> 的上两级；图标集直接写回 app 模块资源目录。
RES = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/res"

PLUM_TOP = (78, 62, 110)      # #4E3E6E — clearly purple, not "almost black"
PLUM_BOTTOM = (44, 34, 72)    # #2C2248 — stays purple at 48px
GOLD = (255, 199, 61)
GOLD_SHADE = (214, 156, 30)     # outer page edge, gives the book depth

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def background(size: int) -> Image.Image:
    img = Image.new("RGB", (1, size))
    for y in range(size):
        t = y / max(1, size - 1)
        img.putpixel((0, y), tuple(round(PLUM_TOP[i] + (PLUM_BOTTOM[i] - PLUM_TOP[i]) * t) for i in range(3)))
    return img.resize((size, size), Image.BILINEAR).convert("RGBA")


def book(size: int, ratio: float) -> Image.Image:
    """The mark alone, on a transparent canvas, sized to `ratio` of the width."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    w = size * ratio
    h = w * 0.72
    cx, cy = size / 2, size / 2
    top = cy - h / 2
    spine = max(1.0, size * 0.014)

    # Two page panels, each tilted slightly away from the spine, so the silhouette
    # reads as an open book rather than a plain rectangle.
    for sign in (-1, 1):
        x_outer = cx + sign * w / 2
        d.polygon([
            (cx - sign * spine, top + h * 0.12),
            (x_outer, top),
            (x_outer, top + h),
            (cx - sign * spine, top + h * 0.88),
        ], fill=GOLD)
        d.polygon([
            (x_outer - sign * size * 0.022, top + h * 0.03),
            (x_outer, top),
            (x_outer, top + h),
            (x_outer - sign * size * 0.022, top + h * 0.97),
        ], fill=GOLD_SHADE)

    return img


def compose(size: int, with_background: bool, ratio: float) -> Image.Image:
    base = background(size) if with_background else Image.new("RGBA", (size, size), (0, 0, 0, 0))
    base.alpha_composite(book(size, ratio))
    return base


def main() -> None:
    for name, scale in DENSITIES.items():
        out = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(out, exist_ok=True)

        # adaptive foreground: transparent, mark inside the centre safe zone
        fg = round(108 * scale)
        compose(fg, with_background=False, ratio=0.52).save(os.path.join(out, "ic_launcher_fg.png"))

        # legacy icon: background baked in
        legacy = round(48 * scale)
        compose(legacy, with_background=True, ratio=0.62).save(os.path.join(out, "ic_launcher.png"))

        mask = Image.new("L", (legacy, legacy), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, legacy - 1, legacy - 1), fill=255)
        rnd = compose(legacy, with_background=True, ratio=0.54)
        rnd.putalpha(mask)
        rnd.save(os.path.join(out, "ic_launcher_round.png"))

        print(f"{name:8} fg={fg} legacy={legacy}")


if __name__ == "__main__":
    main()
