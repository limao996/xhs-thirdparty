from pathlib import Path

from PIL import Image

# 仓库根 = tools/probes/<this file> 的上两级；预览图输出到 tools/out/（已 gitignore）。
REPO = Path(__file__).resolve().parents[2]
src = REPO / "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"

ic = Image.open(src).convert("RGBA").resize((192, 192), Image.LANCZOS)


def circ(im):
    m = Image.new("L", im.size, 0)
    from PIL import ImageDraw
    ImageDraw.Draw(m).ellipse((0, 0, im.size[0] - 1, im.size[1] - 1), fill=255)
    o = im.copy()
    o.putalpha(m)
    return o


sheet = Image.new("RGB", (420, 240), (230, 230, 235))
sheet.paste(circ(ic), (20, 24), circ(ic))
sm = circ(ic.resize((48, 48), Image.LANCZOS))
sheet.paste(sm, (240, 30), sm)
sheet.paste(sm, (240, 100), sm)  # 叠一遍看透明边缘
big = circ(ic.resize((64, 64), Image.LANCZOS))
sheet.paste(big, (320, 80), big)

out = REPO / "tools/out/icon_purple_preview.png"
out.parent.mkdir(parents=True, exist_ok=True)
sheet.save(out)
print("ok ->", out)
