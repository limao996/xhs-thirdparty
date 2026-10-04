"""Render candidate launcher icons side by side so one can actually be chosen.

An icon is judged at 48px on a wallpaper, so every candidate is drawn at 192px
and also at 48px on a dark strip — a mark that only reads large is not a mark.
"""
import os
import pathlib
from PIL import Image, ImageDraw, ImageFilter

# 仓库根 = tools/probes/<this file> 的上两级；对比图输出到 tools/out/（已 gitignore）。
OUT = pathlib.Path(__file__).resolve().parents[2] / "tools/out/icon_candidates.png"

PLUM = (32, 26, 38)
GOLD = (255, 199, 61)
ROSE = (211, 47, 92)
CREAM = (255, 246, 226)


def grad(size, top, bottom):
    img = Image.new("RGB", (1, size))
    for y in range(size):
        t = y / max(1, size - 1)
        img.putpixel((0, y), tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return img.resize((size, size), Image.BILINEAR)


def round_corners(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.size[0] - 1, img.size[1] - 1), radius, fill=255)
    out = img.copy()
    out.putalpha(mask)
    return out


def soft_polygon(size, points, blur):
    """A polygon with rounded corners (blur then re-threshold)."""
    s = size * 4
    m = Image.new("L", (s, s), 0)
    ImageDraw.Draw(m).polygon([(x * s, y * s) for x, y in points], fill=255)
    m = m.filter(ImageFilter.GaussianBlur(s * blur)).point(lambda v: 255 if v > 140 else 0)
    return m.resize((size, size), Image.LANCZOS)


# ---------------------------------------------------------------- candidates
def variant_a(size):
    """Golden open book, two pages, on plum."""
    base = grad(size, (44, 36, 52), PLUM).convert("RGBA")
    d = ImageDraw.Draw(base)
    w, h = size * 0.56, size * 0.40
    x, y = (size - w) / 2, (size - h) / 2
    # two page panels with a slight outward tilt, meeting at the spine
    for sign in (-1, 1):
        d.polygon([
            (size / 2, y + h * 0.10),
            (size / 2 + sign * w / 2, y),
            (size / 2 + sign * w / 2, y + h),
            (size / 2, y + h * 0.90),
        ], fill=GOLD)
    d.rectangle((size / 2 - size * 0.012, y, size / 2 + size * 0.012, y + h), fill=PLUM)
    return base


def variant_b(size):
    """Book with a bookmark ribbon, on plum."""
    base = grad(size, (44, 36, 52), PLUM).convert("RGBA")
    d = ImageDraw.Draw(base)
    bw, bh = size * 0.50, size * 0.58
    x, y = (size - bw) / 2, (size - bh) / 2
    d.rounded_rectangle((x, y, x + bw, y + bh), size * 0.05, fill=GOLD)
    # ribbon notch
    rw = bw * 0.26
    rx = x + bw * 0.18
    d.polygon([(rx, y), (rx + rw, y), (rx + rw, y + bh * 0.42),
               (rx + rw / 2, y + bh * 0.30), (rx, y + bh * 0.42)], fill=ROSE)
    # page block on the right edge
    d.rounded_rectangle((x + bw * 0.74, y + bh * 0.06, x + bw * 1.06, y + bh * 0.94),
                        size * 0.03, fill=CREAM)
    return base


def variant_c(size):
    """A tilted closed book — spine + cover, on plum."""
    base = grad(size, (44, 36, 52), PLUM).convert("RGBA")
    cover = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(cover)
    bw, bh = size * 0.56, size * 0.44
    x, y = (size - bw) / 2, (size - bh) / 2
    d.rounded_rectangle((x, y, x + bw, y + bh), size * 0.04, fill=GOLD)
    d.rounded_rectangle((x, y, x + bw * 0.16, y + bh), size * 0.04, fill=(214, 156, 30))
    for i in range(3):   # page edges on the right
        o = size * 0.035
        d.rounded_rectangle((x + bw + o * i, y + bh * 0.08, x + bw + o * (i + 1), y + bh * 0.92),
                            size * 0.012, fill=CREAM)
    cover = cover.rotate(-14, resample=Image.BICUBIC, center=(size / 2, size / 2))
    base.alpha_composite(cover)
    return base


def variant_d(size):
    """Golden page with a folded corner and a play cut-out."""
    base = grad(size, (44, 36, 52), PLUM).convert("RGBA")
    d = ImageDraw.Draw(base)
    pw = size * 0.50
    x, y = (size - pw) / 2, (size - pw) / 2
    fold = pw * 0.28
    d.polygon([(x, y), (x + pw - fold, y), (x + pw, y + fold), (x + pw, y + pw), (x, y + pw)], fill=GOLD)
    d.polygon([(x + pw - fold, y), (x + pw, y + fold), (x + pw - fold, y + fold)], fill=CREAM)
    hole = soft_polygon(size, [(0.40, 0.40), (0.40, 0.60), (0.62, 0.50)], 0.012)
    base.paste(Image.new("RGBA", base.size, PLUM + (255,)), (0, 0), hole)
    return base


VARIANTS = [("A 开页书", variant_a), ("B 书+书签", variant_b), ("C 斜放书", variant_c), ("D 折角页+播放", variant_d)]


def main():
    cell, pad = 192, 24
    cols = len(VARIANTS)
    sheet = Image.new("RGB", (cols * (cell + pad) + pad, cell + pad * 2 + 60), (24, 24, 28))
    for i, (name, fn) in enumerate(VARIANTS):
        icon = round_corners(fn(cell).convert("RGBA"), cell * 0.22)
        x = pad + i * (cell + pad)
        sheet.paste(icon, (x, pad), icon)
        small = icon.resize((48, 48), Image.LANCZOS)
        sheet.paste(small, (x + cell - 48, pad + cell + 12), small)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    sheet.save(OUT)
    print("wrote", OUT, sheet.size)


if __name__ == "__main__":
    main()
