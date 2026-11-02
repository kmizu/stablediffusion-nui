"""Contact sheet of benchmark images: python contact_sheet.py OUT.jpg label=path ... (<= 1600 px wide)."""
import sys

from PIL import Image, ImageDraw

items = [a.split("=", 1) for a in sys.argv[2:]]
tiles = []
for label, path in items:
    im = Image.open(path).convert("RGB")
    tiles.append((label, im))
h = 480
tiles = [(l, im.resize((int(im.width * h / im.height), h))) for l, im in tiles]
total = sum(im.width for _, im in tiles)
scale = min(1.0, 1600 / total)
sheet = Image.new("RGB", (total, h), "white")
x = 0
for label, im in tiles:
    sheet.paste(im, (x, 0))
    ImageDraw.Draw(sheet).text((x + 6, 6), label, fill="red")
    x += im.width
if scale < 1:
    sheet = sheet.resize((int(total * scale), int(h * scale)))
sheet.save(sys.argv[1], quality=88)
print(sheet.size)
