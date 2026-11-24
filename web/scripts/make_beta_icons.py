"""Makes the beta web app's icons ("ONIT Beta"): each of the web app's icons as is, with the dark
"BETA" pill of the Android beta's launcher icon (scripts/make_beta_icon.py: ink, a thin light
outline, white semibold Heebo) on its lower right. On the maskable icon it stays inside the safe
zone (the middle circle, 80% across), which every launcher shows.

Run after changing an icon: python3 web/scripts/make_beta_icons.py (needs Pillow). Reads
web/public/icons/*.png and writes the same names to web/beta/icons/, which the beta build puts in
place of the release's (see vite.config.ts).
"""
import pathlib
import sys

from PIL import Image, ImageDraw, ImageFilter

WEB = pathlib.Path(__file__).resolve().parent.parent
sys.dont_write_bytecode = True  # no __pycache__ in scripts/
sys.path.insert(0, str(WEB.parent / "scripts"))
from make_beta_icon import INK, SS, TRACKING, WHITE, font  # noqa: E402

# Per icon, as fractions of its side: the pill's centre, width and height, and the outline.
ICONS = {
    "favicon.png": (0.66, 0.80, 0.66, 0.30, 0.03),
    "apple-touch-icon.png": (0.70, 0.79, 0.40, 0.15, 0.012),
    "icon-192.png": (0.70, 0.79, 0.40, 0.15, 0.012),
    "icon-512.png": (0.70, 0.79, 0.40, 0.15, 0.012),
    "icon-maskable-512.png": (0.64, 0.68, 0.34, 0.13, 0.012),
}
CAP = 0.48  # the text's cap height, of the pill's height


def pill(size, cx, cy, w, h, outline):
    """The pill, its text and a soft shadow, as an RGBA layer of [size] px."""
    u = size * SS
    big = Image.new("RGBA", (u, u), (0, 0, 0, 0))
    x0, y0, x1, y1 = (cx - w / 2) * u, (cy - h / 2) * u, (cx + w / 2) * u, (cy + h / 2) * u
    o, r = outline * u, h / 2 * u
    shadow = Image.new("L", big.size, 0)
    ImageDraw.Draw(shadow).rounded_rectangle((x0 - o, y0 - o + 0.3 * o, x1 + o, y1 + o + 0.3 * o), r + o, fill=90)
    shadow = shadow.filter(ImageFilter.GaussianBlur(o))
    big.paste(Image.new("RGBA", big.size, INK + (255,)), (0, 0), shadow)
    d = ImageDraw.Draw(big)
    d.rounded_rectangle((x0 - o, y0 - o, x1 + o, y1 + o), r + o, fill=WHITE + (235,))
    d.rounded_rectangle((x0, y0, x1, y1), r, fill=INK + (255,))
    cap_px = CAP * h * u
    cap = font(100).getbbox("E")
    f = font(round(100 * cap_px / (cap[3] - cap[1])))
    track = TRACKING / 4.4 * cap_px  # the Android icon's tracking, for its cap height
    text = "BETA"
    widths = [f.getlength(c) for c in text]
    total = sum(widths) + track * (len(text) - 1)
    x, y = (x0 + x1 - total) / 2, (y0 + y1) / 2 - cap_px / 2 - f.getbbox("E")[1]
    for c, cw in zip(text, widths):
        d.text((x, y), c, font=f, fill=WHITE + (255,))
        x += cw + track
    return big.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    out_dir = WEB / "beta" / "icons"
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, geometry in ICONS.items():
        src = Image.open(WEB / "public" / "icons" / name).convert("RGBA")
        src.alpha_composite(pill(src.size[0], *geometry))
        src.save(out_dir / name, optimize=True)
        print((out_dir / name).relative_to(WEB.parent))
