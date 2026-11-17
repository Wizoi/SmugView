#!/usr/bin/env python3
"""Store screenshots with a short caption above the real app screen.

Usage: python scripts/caption-screenshots.py <sizes: play|iphone|ipad> <out_dir> <shot.png|jpg>::<headline>::<subline> ...

The screenshot is the real screen, scaled and placed under the caption (it may run off the bottom edge); nothing is drawn over it. The
canvas sizes are the ones the stores accept: Play phone 1080x1920, iPhone 1206x2622, iPad 13-inch 2064x2752. Output is flat (no alpha).
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

SIZES = {
    # canvas, caption block height, screenshot width, corner radius, headline px, subline px
    "play":   ((1080, 1920), 400, 900, 56, 76, 40),
    "iphone": ((1206, 2622), 520, 1000, 64, 92, 48),
    "ipad":   ((2064, 2752), 600, 1500, 80, 112, 58),
}
BG_TOP = (11, 13, 18)
BG_BOTTOM = (20, 26, 40)
ACCENT = (47, 139, 255)
FG = (255, 255, 255)
MUTED = (170, 180, 195)
FONTS = "C:/Windows/Fonts/"


def font(name, px):
    return ImageFont.truetype(FONTS + name, px)


def wrap(draw, text, fnt, width):
    """Wraps to [width]; a headline that falls on two lines is balanced so the second line is not a single stray word."""
    def plain(w):
        lines, line = [], ""
        for word in text.split():
            trial = (line + " " + word).strip()
            if draw.textlength(trial, font=fnt) <= w or not line:
                line = trial
            else:
                lines.append(line)
                line = word
        if line:
            lines.append(line)
        return lines
    best = plain(width)
    if len(best) == 2:
        for w in range(int(width), int(width * 0.5), -10):
            cand = plain(w)
            if len(cand) != 2:
                break
            best = cand
    return best


def make(kind, shot, head, sub, out):
    (w, h), cap_h, shot_w, radius, hpx, spx = SIZES[kind]
    canvas = Image.new("RGB", (w, h), BG_TOP)
    d = ImageDraw.Draw(canvas)
    for y in range(h):  # a soft vertical gradient, darker at the top where the words are
        t = y / (h - 1)
        d.line([(0, y), (w, y)], fill=tuple(int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3)))
    margin = int(w * 0.08)
    fh, fs = font("segoeuib.ttf", hpx), font("segoeui.ttf", spx)
    y = int(cap_h * 0.20)
    d.rectangle([margin, y - 28, margin + 90, y - 20], fill=ACCENT)  # a short accent bar above the headline
    for line in wrap(d, head, fh, w - 2 * margin):
        d.text((margin, y), line, font=fh, fill=FG)
        y += int(hpx * 1.15)
    y += int(spx * 0.4)
    for line in wrap(d, sub, fs, w - 2 * margin):
        d.text((margin, y), line, font=fs, fill=MUTED)
        y += int(spx * 1.3)
    src = Image.open(shot).convert("RGB")
    sh = int(src.height * shot_w / src.width)
    src = src.resize((shot_w, sh), Image.LANCZOS)
    mask = Image.new("L", src.size, 0)
    fits = cap_h + src.height <= h  # a screen that fits keeps its rounded bottom corners; one that runs off the edge bleeds
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, src.width - 1, src.height - 1 if fits else src.height * 2], radius=radius, fill=255)
    x = (w - shot_w) // 2
    shadow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([x, cap_h, x + shot_w, cap_h + sh], radius=radius, fill=(0, 0, 0, 150))
    canvas = Image.alpha_composite(canvas.convert("RGBA"), shadow.filter(ImageFilter.GaussianBlur(24))).convert("RGB")
    canvas.paste(src, (x, cap_h), mask)
    ImageDraw.Draw(canvas).rounded_rectangle([x, cap_h, x + shot_w - 1, cap_h + sh], radius=radius, outline=(70, 80, 100), width=3)
    canvas.save(out, "PNG" if out.endswith(".png") else "JPEG", **({} if out.endswith(".png") else {"quality": 95}))
    return canvas.size


def main():
    kind, out_dir, items = sys.argv[1], sys.argv[2], sys.argv[3:]
    os.makedirs(out_dir, exist_ok=True)
    for i, item in enumerate(items, 1):
        shot, head, sub = item.split("::")
        out = os.path.join(out_dir, f"{i:02d}-{os.path.splitext(os.path.basename(shot))[0]}.png")
        print(out, make(kind, shot, head, sub, out))


if __name__ == "__main__":
    main()
