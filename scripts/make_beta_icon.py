"""Makes the beta build's launcher foregrounds ("ONIT Beta"): the app's icon foreground as is, with
a small dark "BETA" pill on its lower right (ink, a thin light outline, white semibold text), like
the beta apps on the Play Store. The pill stays inside the adaptive icon's safe zone (the middle 66
of 108 units), which every launcher shows.

Run after changing the icon: python3 scripts/make_beta_icon.py (needs Pillow). Reads
app/src/main/res/drawable[-night]-*dpi/ic_launcher_foreground.png and writes
ic_launcher_foreground_beta.png next to each. The font is the app's own Heebo (assets/fonts). Tested with Pillow 12.3:
other versions may give slightly different bytes.
"""
import math
import pathlib
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
FONT = ROOT / "app/src/main/assets/fonts/heebo.ttf"

INK, WHITE = (0x0B, 0x0D, 0x1C), (0xFF, 0xFF, 0xFF)

# In icon units (the canvas is 108). The pill: its centre, size and outline.
PILL_CX, PILL_CY, PILL_W, PILL_H, OUTLINE = 68.5, 71.0, 22.0, 9.0, 1.0
CAP_H, TRACKING, WEIGHT = 4.4, 0.55, 600
SS = 4  # supersampling for smooth edges

# Every corner of the pill, outline included, must be inside the safe circle.
_r = PILL_H / 2 + OUTLINE
assert all(
    math.hypot(PILL_CX + sx * (PILL_W / 2 - PILL_H / 2) - 54, PILL_CY - 54) + _r <= 33
    for sx in (-1, 1)
), "the pill leaves the safe zone"


def font(px):
    f = ImageFont.truetype(str(FONT), px)
    f.set_variation_by_axes([WEIGHT])
    return f


def pill(size):
    """The pill, its text and a soft shadow, as an RGBA layer of [size] px."""
    u = size * SS / 108
    big = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
    x0, y0 = (PILL_CX - PILL_W / 2) * u, (PILL_CY - PILL_H / 2) * u
    x1, y1 = (PILL_CX + PILL_W / 2) * u, (PILL_CY + PILL_H / 2) * u
    o = OUTLINE * u
    # A soft shadow under it.
    shadow = Image.new("L", big.size, 0)
    ImageDraw.Draw(shadow).rounded_rectangle((x0 - o, y0 - o + 0.6 * u, x1 + o, y1 + o + 0.6 * u), PILL_H / 2 * u + o, fill=90)
    shadow = shadow.filter(ImageFilter.GaussianBlur(1.0 * u))
    big.paste(Image.new("RGBA", big.size, INK + (255,)), (0, 0), shadow)
    d = ImageDraw.Draw(big)
    # The light outline, then the ink pill inside it.
    d.rounded_rectangle((x0 - o, y0 - o, x1 + o, y1 + o), PILL_H / 2 * u + o, fill=WHITE + (235,))
    d.rounded_rectangle((x0, y0, x1, y1), PILL_H / 2 * u, fill=INK + (255,))
    # "BETA", semibold caps, centred on the cap height.
    cap = font(100).getbbox("E")
    f = font(round(100 * CAP_H * u / (cap[3] - cap[1])))
    text = "BETA"
    widths = [f.getlength(c) for c in text]
    total = sum(widths) + TRACKING * u * (len(text) - 1)
    x, y = (x0 + x1 - total) / 2, (y0 + y1) / 2 - CAP_H * u / 2 - f.getbbox("E")[1]
    for c, w in zip(text, widths):
        d.text((x, y), c, font=f, fill=WHITE + (255,))
        x += w + TRACKING * u
    return big.resize((size, size), Image.LANCZOS)


def beta(src):
    out = Image.open(src).convert("RGBA")
    out.alpha_composite(pill(out.size[0]))
    return out


if __name__ == "__main__":
    for src in sorted(RES.glob("drawable*/ic_launcher_foreground.png")):
        dst = src.with_name("ic_launcher_foreground_beta.png")
        beta(src).save(dst, optimize=True)
        print(dst.relative_to(ROOT))
