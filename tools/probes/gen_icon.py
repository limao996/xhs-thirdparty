"""Generate the launcher icon set.

Design（保持不变）: a bold golden open book on deep plum.

Why not a play button: it is the single most over-used mark in the store, and it
says nothing about this app. 小黄书 is a *book* — a two-page silhouette in gold on
a dark ground is distinctive, reads instantly at 48px (see
tools/icon_candidates.py for the comparison sheet), and the gold ties back to the
name.

2026-10-10 只做了一件事：**去锯齿**（用户反馈"icon 的锯齿也太严重了吧"）。
设计、比例、配色一个像素都没动，改的是产出方式：

1. 所有 PNG 在 `SS = 4` 倍画布上绘制后 LANCZOS 缩小 —— 原来的写法是直接在最终尺寸上
   `ImageDraw.polygon/rounded_rectangle/ellipse`，斜边、圆角、圆形的边缘全是阶梯。
2. 自适应图标的前景色/单色层改用**矢量**（`drawable/ic_launcher_foreground.xml` /
   `ic_launcher_monochrome.xml`）：这个 mark 本来就只有 4 个多边形，转成 path 后任何尺寸、
   任何屏幕密度都不会有锯齿，PNG 那层就不需要了（原先的 `ic_launcher_fg.png` 已删除）。
   旧版图标（API < 26 的 `android:icon`/`roundIcon` 兜底）仍然是 PNG，靠第 1 条变平滑。

几何只有一份：`polys()` 给出 0..1 归一化坐标，PNG 与矢量都用它，避免两边走样。
"""
import os
import pathlib
from PIL import Image, ImageDraw

# 仓库根 = tools/probes/<this file> 的上两级；图标集直接写回 app 模块资源目录。
ROOT = pathlib.Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"

PLUM_TOP = (78, 62, 110)      # #4E3E6E — clearly purple, not "almost black"
PLUM_BOTTOM = (44, 34, 72)    # #2C2248 — stays purple at 48px
GOLD = (255, 199, 61)
GOLD_SHADE = (214, 156, 30)   # outer page edge, gives the book depth

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
SS = 4                        # 超采样倍数（1 = 旧行为，直接画在最终尺寸上）
FG_RATIO = 0.52               # 自适应前景里 mark 的宽度占比
LEGACY_RATIO = 0.62
ROUND_RATIO = 0.54
LEGACY_INSET = 0.09


def polys(ratio: float) -> list:
    """mark 的四个多边形（归一化 0..1 坐标），按绘制顺序返回 [(点列表, 是否深色), ...]。"""
    w = ratio
    h = w * 0.72
    top = 0.5 - h / 2
    spine = 0.014
    off = 0.022
    out = []
    for sign in (-1, 1):
        x_outer = 0.5 + sign * w / 2
        page = [
            (0.5 - sign * spine, top + h * 0.12),
            (x_outer, top),
            (x_outer, top + h),
            (0.5 - sign * spine, top + h * 0.88),
        ]
        shade = [
            (x_outer - sign * off, top + h * 0.03),
            (x_outer, top),
            (x_outer, top + h),
            (x_outer - sign * off, top + h * 0.97),
        ]
        out.append((page, False))
        out.append((shade, True))
    return out


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
    for points, dark in polys(ratio):
        d.polygon([(x * size, y * size) for x, y in points], fill=GOLD_SHADE if dark else GOLD)
    return img


def compose(size: int, with_background: bool, ratio: float) -> Image.Image:
    """size 是**最终**尺寸；内部按 SS 倍绘制再缩小，所以边是平滑的。"""
    big = size * SS
    base = background(big) if with_background else Image.new("RGBA", (big, big), (0, 0, 0, 0))
    base.alpha_composite(book(big, ratio))
    return base.resize((size, size), Image.LANCZOS)


def legacy_icon(size: int, ratio: float, inset_ratio: float = LEGACY_INSET) -> Image.Image:
    """Legacy (API < 26) icon: rounded square, inset with transparent padding.

    Why the padding: Material 的旧版图标规范要求内容不要铺满整块 48dp 画布（lint 的
    `IconLauncherShape` 就是这么判的），否则在圆形/方形遮罩下会贴着边。原来这里直接
    把渐变铺满 48×48，于是 lint 报了 5 条。改成"内容缩到 82%、外面留透明边、圆角矩形"。
    """
    big = size * SS
    canvas = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    inner = max(1, round(big * (1 - 2 * inset_ratio)))
    bg = background(inner)
    mask = Image.new("L", (inner, inner), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, inner - 1, inner - 1), radius=int(inner * 0.22), fill=255
    )
    bg.putalpha(mask)
    off = (big - inner) // 2
    canvas.alpha_composite(bg, (off, off))
    canvas.alpha_composite(book(inner, ratio), (off, off))
    return canvas.resize((size, size), Image.LANCZOS)


def round_icon(size: int, ratio: float = ROUND_RATIO) -> Image.Image:
    big = size * SS
    img = compose(big, with_background=True, ratio=ratio)
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, big - 1, big - 1), fill=255)
    img.putalpha(mask)
    return img.resize((size, size), Image.LANCZOS)


# ---------------- 矢量层（自适应图标用，任何尺寸都没有锯齿）----------------
def _path(points, scale: float) -> str:
    pts = [f"{x * scale:.3f},{y * scale:.3f}" for x, y in points]
    return "M" + " L".join(pts) + " Z"


def _hex(c) -> str:
    return "#%02X%02X%02X" % c


def write_vectors() -> None:
    view = 108.0
    paths, mono = [], []
    for points, dark in polys(FG_RATIO):
        data = _path(points, view)
        paths.append(f'    <path android:fillColor="{_hex(GOLD_SHADE if dark else GOLD)}" '
                     f'android:pathData="{data}" />')
        mono.append(f'    <path android:fillColor="#FFFFFFFF" android:pathData="{data}" />')

    def doc(body: str, comment: str) -> str:
        return ('<?xml version="1.0" encoding="utf-8"?>\n<!--\n' + comment +
                '\n由 tools/probes/gen_icon.py 生成（矢量，无锯齿），改图标请改那个脚本再跑一次。\n-->\n'
                '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
                '    android:width="108dp"\n    android:height="108dp"\n'
                '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
                + "\n".join(body) + "\n</vector>\n")

    (RES / "drawable/ic_launcher_foreground.xml").write_text(
        doc(paths, "  自适应图标前景：金色摊开的书（矢量，任意尺寸都平滑）。"), encoding="utf-8")
    (RES / "drawable/ic_launcher_monochrome.xml").write_text(
        doc(mono, "  主题图标（Android 13+）单色层：系统按壁纸取色重绘。"), encoding="utf-8")

    adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n'
                '<!--\n  自适应图标（API 26+）：深紫渐变底 + **矢量**前景（以前是 PNG 前景，48px 下边缘有锯齿）。\n'
                '  由 tools/probes/gen_icon.py 生成。\n-->\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@drawable/ic_launcher_bg" />\n'
                '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
                '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
                '</adaptive-icon>\n')
    (RES / "mipmap-anydpi/ic_launcher.xml").write_text(adaptive, encoding="utf-8")
    print("vectors -> drawable/ic_launcher_{foreground,monochrome}.xml + mipmap-anydpi/ic_launcher.xml")


def main() -> None:
    for name, scale in DENSITIES.items():
        out = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(out, exist_ok=True)
        legacy = round(48 * scale)
        legacy_icon(legacy, ratio=LEGACY_RATIO).save(os.path.join(out, "ic_launcher.png"))
        round_icon(legacy).save(os.path.join(out, "ic_launcher_round.png"))
        print(f"{name:8} legacy={legacy}")
    write_vectors()

    # 对照图：旧 PNG（直接画在最终尺寸上）与新 PNG（超采样）并排放大
    out = ROOT / "tools/out"
    out.mkdir(parents=True, exist_ok=True)
    sheet = Image.new("RGB", (620, 250), (20, 16, 26))
    sheet.paste(legacy_icon(192, LEGACY_RATIO).convert("RGB"), (18, 30))
    sheet.paste(round_icon(192).convert("RGB"), (222, 30), round_icon(192))
    sheet.paste(compose(192, False, FG_RATIO).convert("RGB"), (426, 30), compose(192, False, FG_RATIO))
    sheet.resize((1240, 500), Image.NEAREST).save(out / "icon-preview.png")
    print("preview ->", out / "icon-preview.png")


if __name__ == "__main__":
    main()
