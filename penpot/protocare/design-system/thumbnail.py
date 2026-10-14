"""Proto Care — renders the dashboard thumbnail of the Proto Care DS template.

    python thumbnail.py

Writes frontend/resources/images/thumbnails/template-protocare-ds.jpg
(1390x724, the size of the other template thumbnails). Colors come from
tokens.json so the card matches the design system.
"""
import json
import os
import re

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "frontend", "resources", "images", "thumbnails",
                   "template-protocare-ds.jpg")

with open(os.path.join(HERE, "tokens.json"), encoding="utf-8") as fh:
    doc = json.load(fh)

tokens = {}


def collect(node, path):
    for key, value in node.items():
        if key.startswith("$"):
            continue
        if isinstance(value, dict) and "$value" in value:
            tokens[".".join(path + [key])] = value["$value"]
        elif isinstance(value, dict):
            collect(value, path + [key])


for set_name in ("Primitives", "Semantic - Light"):
    collect(doc[set_name], [])


def color(name):
    value = tokens[name]
    match = re.fullmatch(r"\{(.+)\}", value) if isinstance(value, str) else None
    return color(match.group(1)) if match else value


def font(size, bold=False):
    names = ["segoeuib.ttf", "arialbd.ttf"] if bold else ["segoeui.ttf", "arial.ttf"]
    for name in names:
        for folder in ("C:/Windows/Fonts", "/usr/share/fonts/truetype/dejavu"):
            path = os.path.join(folder, name)
            if os.path.exists(path):
                return ImageFont.truetype(path, size)
    return ImageFont.load_default()


W, H = 1390, 724
img = Image.new("RGB", (W, H), color("palette.purple.900"))
d = ImageDraw.Draw(img)

# Title block
d.text((96, 88), "Proto Care DS", font=font(84, True), fill=color("color.text.inverse"))
d.text((100, 196), "Tokens · Componentes · Tela de teste para auditoria",
       font=font(34), fill=color("palette.purple.200"))

# Swatches
swatches = ["color.action.primary", "color.action.secondary", "color.feedback.success.bg",
            "color.feedback.error.bg", "color.feedback.warning.bg", "color.feedback.info.bg",
            "color.bg.surface", "color.text.primary"]
x = 100
for name in swatches:
    d.rounded_rectangle((x, 300, x + 120, 420), radius=20, fill=color(name),
                        outline=color("palette.purple.700"), width=3)
    x += 148

# Button + badge mock
d.rounded_rectangle((100, 500, 420, 580), radius=16, fill=color("color.action.primary"))
d.text((176, 518), "Continuar", font=font(36, True), fill=color("color.text.inverse"))
d.rounded_rectangle((460, 500, 780, 580), radius=16, fill=color("color.action.secondary"))
d.text((548, 518), "Cancelar", font=font(36, True), fill=color("color.action.primary"))
d.rounded_rectangle((820, 512, 1010, 568), radius=28, fill=color("color.feedback.success.bg"))
d.text((858, 520), "Sucesso", font=font(30, True), fill=color("color.feedback.success.fg"))
d.rounded_rectangle((1040, 512, 1230, 568), radius=28, fill=color("color.feedback.error.bg"))
d.text((1100, 520), "Erro", font=font(30, True), fill=color("color.feedback.error.fg"))

os.makedirs(os.path.dirname(OUT), exist_ok=True)
img.save(OUT, "JPEG", quality=88)
print("written", os.path.normpath(OUT))
