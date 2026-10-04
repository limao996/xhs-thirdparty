"""Generate the README image assets.

Every image here is **drawn by this script** (or composed from the repo's real
launcher-icon PNGs). Nothing is a screenshot of the app: the hands-on shots taken
during development live in `docs/images/screenshots/` (not committed — see
.gitignore), and the README labels its own graphics as 自绘示意图.

Run:  python tools/make_readme_assets.py
Out:  docs/images/{hero,palette,icon-set,architecture}.png
"""
from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

REPO = Path(__file__).resolve().parents[1]
OUT = REPO / "docs" / "images"
OUT.mkdir(parents=True, exist_ok=True)

FONT_R = "C:/Windows/Fonts/msyh.ttc"
FONT_B = "C:/Windows/Fonts/msyhbd.ttc"
FONT_MONO = "C:/Windows/Fonts/consola.ttf"

# ---- the app's real brand colours (app/src/main/res + tools/probes/gen_icon.py) ----
PLUM_TOP = (78, 62, 110)      # #4E3E6E adaptive-icon background, top of gradient
PLUM_BOTTOM = (44, 34, 72)    # #2C2248 bottom of gradient
PLUM_GLOW = (122, 104, 170)
GOLD = (255, 199, 61)         # #FFC73D the book mark
GOLD_SHADE = (214, 156, 30)   # #D69C1E page edge
ROSE = (211, 47, 92)          # #D32F5C
CREAM = (255, 246, 226)       # #FFF6E2
INK = (26, 21, 33)
PAPER = (247, 245, 250)


def f(path: str, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(path, size)


def vgrad(size: tuple[int, int], top: tuple[int, int, int], bottom: tuple[int, int, int]) -> Image.Image:
    w, h = size
    strip = Image.new("RGB", (1, h))
    px = strip.load()
    for y in range(h):
        t = y / max(1, h - 1)
        px[0, y] = tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
    return strip.resize((w, h), Image.BILINEAR)


def glow(img: Image.Image, cx: int, cy: int, r: int, color: tuple[int, int, int], alpha: int) -> None:
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).ellipse((cx - r, cy - r, cx + r, cy + r), fill=color + (alpha,))
    img.alpha_composite(layer.filter(ImageFilter.GaussianBlur(r * 0.55)))


def quad(p0, p1, p2, steps=14):
    """Sample a quadratic Bézier so page edges can swoop instead of being flat."""
    pts = []
    for i in range(steps + 1):
        t = i / steps
        u = 1 - t
        pts.append((u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0],
                    u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1]))
    return pts


def book(d: ImageDraw.ImageDraw, cx: float, cy: float, size: float,
         fill=GOLD, shade=GOLD_SHADE) -> None:
    """A two-page open book silhouette — the same mark as the launcher icon."""
    hw, hh = size * 0.48, size * 0.34
    gap = max(2.0, size * 0.028)

    def page(sign: int):
        outer_top = (cx + sign * hw, cy - hh * 0.42)
        inner_top = (cx + sign * gap, cy - hh * 0.88)
        inner_bot = (cx + sign * gap, cy + hh * 0.72)
        outer_bot = (cx + sign * hw, cy + hh * 0.50)
        top = quad(outer_top, (cx + sign * hw * 0.55, cy - hh * 0.34), inner_top)
        bot = quad(inner_bot, (cx + sign * hw * 0.55, cy + hh * 0.06), outer_bot)
        return top + bot

    for sign in (-1, 1):
        poly = page(sign)
        d.polygon(poly, fill=fill)
        # a shaded band along the outer edge gives the page depth at 48px
        edge = poly[: len(poly) // 2 + 1]
        band = [(x - sign * hw * 0.13, y) for (x, y) in edge]
        d.polygon(list(reversed(edge)) + band, fill=shade)


def chip(d: ImageDraw.ImageDraw, x: int, y: int, text: str, font, pad=18, fill=(16, 11, 26, 120),
         outline=(255, 199, 61, 110), color=(255, 255, 255, 255)) -> int:
    w = d.textlength(text, font=font)
    h = font.size + pad
    d.rounded_rectangle((x, y, x + w + pad * 2, y + h), radius=h // 2, fill=fill, outline=outline, width=2)
    d.text((x + pad, y + h / 2), text, font=font, fill=color, anchor="lm")
    return int(w + pad * 2)


def hero() -> None:
    W, H = 1600, 520
    img = vgrad((W, H), PLUM_TOP, PLUM_BOTTOM).convert("RGBA")
    glow(img, 240, 90, 420, PLUM_GLOW, 110)
    glow(img, 1440, 470, 380, (150, 90, 130), 70)
    d = ImageDraw.Draw(img)

    # faint dot grid for texture
    for gx in range(0, W, 40):
        for gy in range(0, H, 40):
            if (gx // 40 + gy // 40) % 2 == 0:
                d.point((gx, gy), fill=(255, 255, 255, 16))

    book(d, 200, 250, 300)

    d.text((360, 150), "第三方小黄书", font=f(FONT_B, 84), fill=(255, 255, 255), anchor="lm")
    d.text((364, 222), "Third-party client · Kotlin + Jetpack Compose · M3 Expressive",
           font=f(FONT_MONO, 24), fill=(226, 214, 240), anchor="lm")
    d.line([(366, 262), (1300, 262)], fill=GOLD + (170,), width=3)

    x = 366
    for label in ("纯本地存储", "Room 缓存", "ExoPlayer · HLS", "WebDAV 备份", "指纹应用锁"):
        x += chip(d, x, 300, label, f(FONT_R, 24)) + 14

    d.text((366, 404), "v1.1.0  ·  minSdk 24  ·  targetSdk 37  ·  compileSdk 37",
           font=f(FONT_MONO, 22), fill=(196, 182, 216), anchor="lm")
    d.text((W - 40, H - 34), "自绘示意图 / hand-drawn banner",
           font=f(FONT_R, 20), fill=(255, 255, 255, 130), anchor="rm")

    img.convert("RGB").save(OUT / "hero.png")
    print("wrote", OUT / "hero.png")


SWATCHES = [
    ("#4E3E6E", "PLUM_TOP", "图标背景渐变上端 / 品牌紫", PLUM_TOP, (255, 255, 255)),
    ("#2C2248", "PLUM_BOTTOM", "图标背景渐变下端", PLUM_BOTTOM, (255, 255, 255)),
    ("#FFC73D", "GOLD", "书本标识 / 强调色", GOLD, INK),
    ("#D69C1E", "GOLD_SHADE", "书页外缘阴影", GOLD_SHADE, INK),
    ("#D32F5C", "ROSE", "图标候选稿用色", ROSE, (255, 255, 255)),
    ("#FFF6E2", "CREAM", "书页内页高光", CREAM, INK),
    ("#B3000000", "Scrim.strong", "视频底部信息条", (0, 0, 0), (255, 255, 255)),
    ("#66000000", "Scrim.chrome", "播放器控件压暗", (0, 0, 0), (255, 255, 255)),
]


def palette() -> None:
    W, H = 1400, 470
    img = Image.new("RGBA", (W, H), PAPER + (255,))
    d = ImageDraw.Draw(img)
    d.text((48, 46), "品牌色板", font=f(FONT_B, 40), fill=INK, anchor="lm")
    d.text((48, 88), "全部取自仓库内的真实资源：icons / tools/probes/gen_icon.py / ui/theme/Tokens.kt",
           font=f(FONT_R, 22), fill=(96, 88, 108), anchor="lm")

    cw, ch, gap, top = 300, 150, 22, 140
    for i, (hexv, name, desc, rgb, fg) in enumerate(SWATCHES):
        col, row = i % 4, i // 4
        x = 48 + col * (cw + gap)
        y = top + row * (ch + gap + 30)
        d.rounded_rectangle((x, y, x + cw, y + ch), radius=18, fill=rgb + (255,), outline=(255, 255, 255, 40), width=2)
        d.text((x + 20, y + ch - 26), hexv, font=f(FONT_MONO, 24), fill=fg, anchor="ls")
        d.text((x, y + ch + 8), name, font=f(FONT_B, 22), fill=INK, anchor="la")
        d.text((x, y + ch + 34), desc, font=f(FONT_R, 19), fill=(104, 96, 116), anchor="la")

    img.convert("RGB").save(OUT / "palette.png")
    print("wrote", OUT / "palette.png")


def icon_set() -> None:
    src = REPO / "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"
    ic = Image.open(src).convert("RGBA")
    W, H = 1400, 440
    img = Image.new("RGBA", (W, H), PAPER + (255,))
    d = ImageDraw.Draw(img)
    d.text((48, 46), "启动图标", font=f(FONT_B, 40), fill=INK, anchor="lm")
    d.text((48, 88), "真实资源 app/src/main/res/mipmap-*/ic_launcher.png（由 tools/probes/gen_icon.py 生成）",
           font=f(FONT_R, 22), fill=(96, 88, 108), anchor="lm")

    def rounded(im: Image.Image, radius_ratio: float) -> Image.Image:
        m = Image.new("L", im.size, 0)
        ImageDraw.Draw(m).rounded_rectangle((0, 0, im.size[0] - 1, im.size[1] - 1),
                                            radius=int(im.size[0] * radius_ratio), fill=255)
        o = im.copy()
        o.putalpha(m)
        return o

    def circ(im: Image.Image) -> Image.Image:
        m = Image.new("L", im.size, 0)
        ImageDraw.Draw(m).ellipse((0, 0, im.size[0] - 1, im.size[1] - 1), fill=255)
        o = im.copy()
        o.putalpha(m)
        return o

    big = ic.resize((240, 240), Image.LANCZOS)
    img.paste(rounded(big, 0.22), (70, 140), rounded(big, 0.22))
    img.paste(circ(ic.resize((160, 160), Image.LANCZOS)), (360, 180), circ(ic.resize((160, 160), Image.LANCZOS)))

    # 48px legibility check: light and dark strip
    strip_l = Image.new("RGBA", (300, 120), (228, 226, 234, 255))
    strip_d = Image.new("RGBA", (300, 120), (26, 21, 33, 255))
    sm = ic.resize((48, 48), Image.LANCZOS)
    strip_l.paste(sm, (24, 36), sm)
    strip_d.paste(sm, (24, 36), sm)
    strip_l.paste(sm.resize((72, 72), Image.LANCZOS), (120, 24), sm.resize((72, 72), Image.LANCZOS))
    strip_d.paste(sm.resize((72, 72), Image.LANCZOS), (120, 24), sm.resize((72, 72), Image.LANCZOS))
    img.paste(strip_l, (600, 150))
    img.paste(strip_d, (600, 296))
    d.text((600, 136), "48px / 72px 在浅色与深色桌面上的可辨识度", font=f(FONT_R, 21), fill=(96, 88, 108), anchor="ls")
    d.text((920, 190), "自适应图标前景\n只保证中心 66/108dp", font=f(FONT_R, 20), fill=(80, 72, 92), anchor="lm")
    d.text((920, 340), "金色开页书 = 名字里的「书」\n深紫底 = 与浅色桌面区分", font=f(FONT_R, 20), fill=(70, 62, 84), anchor="lm")

    img.convert("RGB").save(OUT / "icon-set.png")
    print("wrote", OUT / "icon-set.png")


def architecture() -> None:
    W, H = 1500, 780
    img = Image.new("RGBA", (W, H), PAPER + (255,))
    d = ImageDraw.Draw(img)
    d.text((48, 46), "架构总览", font=f(FONT_B, 40), fill=INK, anchor="lm")
    d.text((48, 88), "自绘示意图 —— 与 docs/ARCHITECTURE.md 一一对应（Compose 无 XML 布局）",
           font=f(FONT_R, 22), fill=(96, 88, 108), anchor="lm")

    def box(x, y, w, h, title, sub, fill, fg, radius=16, title_font=24, sub_font=18):
        d.rounded_rectangle((x, y, x + w, y + h), radius=radius, fill=fill, outline=(255, 255, 255, 40), width=2)
        d.text((x + w / 2, y + h / 2 - (14 if sub else 0)), title, font=f(FONT_B, title_font), fill=fg, anchor="mm")
        if sub:
            d.text((x + w / 2, y + h / 2 + 18), sub, font=f(FONT_R, sub_font), fill=fg, anchor="mm")

    def arrow(x1, y1, x2, y2, color=(120, 112, 136), label=None, dashed=False):
        d.line([(x1, y1), (x2, y2)], fill=color, width=3)
        ang = math.atan2(y2 - y1, x2 - x1)
        for s in (+1, -1):
            d.line([(x2, y2), (x2 - 14 * math.cos(ang - s * 0.42), y2 - 14 * math.sin(ang - s * 0.42))], fill=color, width=3)
        if label:
            d.text(((x1 + x2) / 2 + 10, (y1 + y2) / 2 - 14), label, font=f(FONT_R, 18), fill=color, anchor="lm")

    # UI layer
    box(70, 150, 1360, 120, "", "", (255, 255, 255, 255), INK)
    d.text((100, 180), "UI 层 —— Jetpack Compose（纯 Kotlin，无 XML 布局）", font=f(FONT_B, 24), fill=INK, anchor="lm")
    for i, (t, s) in enumerate([("HomeScreen", "推荐 / 发现 / 我的"),
                                ("DetailScreen", "图文 · 视频 · 评论"),
                                ("SearchScreen", "搜索历史 · 结果"),
                                ("ProfileScreen", "账号 · 缓存 · 设置"),
                                ("BackupScreen", "本地文件 · WebDAV")]):
        bx = 100 + i * 264
        box(bx, 200, 240, 56, t, s, (238, 234, 246, 255), (54, 44, 74), radius=12, title_font=20, sub_font=16)

    d.text((70, 292), "ui/screens/*.kt + ui/components/*.kt（XhsWaterfall 瀑布流、VideoPlayer、ImageGallery、ConfirmActionDialog…）",
           font=f(FONT_R, 19), fill=(110, 102, 124), anchor="lm")

    # ViewModel
    box(70, 336, 640, 96, "ViewModel 层", "ui/viewmodel/*.kt · RepoViewModelFactory 注入", PLUM_TOP + (255,), (255, 255, 255))
    box(790, 336, 640, 96, "导航 / 深链", "AppNavHost + Routes + DeepLink（xhstp://note）", PLUM_TOP + (255,), (255, 255, 255))
    arrow(390, 300, 390, 336, (120, 112, 136))
    arrow(1110, 300, 1110, 336, (120, 112, 136))

    # Repository
    box(70, 486, 1360, 90, "数据仓库", "data/XhsRepository.kt —— 单一数据源，Room 缓存 + 网络回填", (58, 46, 88, 255), (255, 255, 255))
    arrow(390, 432, 390, 486, (120, 112, 136))
    arrow(1110, 432, 1110, 486, (120, 112, 136))

    box(70, 626, 430, 110, "Room", "xhs_local.db v2\n收藏 · 历史 · 关注", (255, 255, 255, 255), INK)
    box(535, 626, 430, 110, "OkHttp + AES", "net/XhsApi · XhsCrypto\nAES/CBC 全量加密包体", (255, 255, 255, 255), INK)
    box(1000, 626, 430, 110, "WebDAV / 备份", "net/WebDavClient\nBackupManager（xhs/ 子目录）", (255, 255, 255, 255), INK)
    arrow(285, 576, 285, 626, (120, 112, 136))
    arrow(750, 576, 750, 626, (120, 112, 136))
    arrow(1215, 576, 1215, 626, (120, 112, 136))

    d.text((W - 48, 180), "自绘示意图", font=f(FONT_R, 20), fill=(150, 142, 166), anchor="rm")
    img.convert("RGB").save(OUT / "architecture.png")
    print("wrote", OUT / "architecture.png")


if __name__ == "__main__":
    hero()
    palette()
    icon_set()
    architecture()
