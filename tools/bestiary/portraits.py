#!/usr/bin/env python3
"""
Makes the client's copies of the bestiary portraits: each <look>.webp (or .png, .jpg, .gif) the
bake page saved in data/html/custom/bestiary/looks becomes client/portraits/<look>.l2bp, a
128 x 128 DXT5 picture the game's Direct3D takes as it is, which custom.Bestiary.BestiaryPackets
sends to the client's bestiary window (client/Bestiary/PROTOCOL.txt, "Portraits"). A look is a
mesh with its skins, named by 8 hex digits (custom.Bestiary.BestiaryArt.lookId): every monster
that looks the same shares its portrait. Look 00000000 is the field the page puts behind every
portrait (bestiary-lib/backgrounds/portrait.webp, with the page's darkened edge), opaque, DXT1.

The server cannot read WebP, so the copies are made here; run this again after a bake (it only
redoes the portraits whose picture is newer than its copy, and drops copies of deleted ones).
A copy carries its picture's time in seconds, the version the page's portrait addresses carry.

File (.l2bp, little endian):
  char[4] "L2BP"
  int     format             1
  int     version            the picture's modification time, in seconds
  short   side               width = height, a multiple of 4
  byte    kind               1 DXT1 (opaque), 5 DXT5
  byte    0
  int     length             bytes that follow: side * side / 2 (DXT1) or side * side (DXT5)
  bytes   the blocks, rows of 4 x 4 pixel blocks, top to bottom

Usage: python tools/bestiary/portraits.py [art folder] [--force]
       (the art folder defaults to dist/game/data/html/custom/bestiary)
Needs Pillow 11.2 or newer (DDS writing) and numpy.
"""
import io
import os
import re
import struct
import sys

import numpy as np
from PIL import Image, ImageFilter

SIDE = 128
KIND_DXT1, KIND_DXT5 = 1, 5
PICTURE = re.compile(r"^([0-9a-f]{8})\.(webp|png|jpg|jpeg|gif)$")
COPY = re.compile(r"^([0-9a-f]{8})\.l2bp$")
FIELD = "00000000"
DDS_HEADER = 128


def blocks(image, kind):
	"""The DXT blocks of an image of SIDE x SIDE, as Pillow's DDS writer makes them."""
	out = io.BytesIO()
	image.save(out, "DDS", pixel_format="DXT5" if kind == KIND_DXT5 else "DXT1")
	data = out.getvalue()
	length = SIDE * SIDE // 2 if kind == KIND_DXT1 else SIDE * SIDE
	if data[:4] != b"DDS " or len(data) < DDS_HEADER + length:
		raise ValueError("Pillow wrote no DXT data (Pillow 11.2 or newer is needed)")
	return data[DDS_HEADER:DDS_HEADER + length]


def bleed(image):
	"""The colours of the monster spread into its transparent surroundings: DXT keeps one pair
	of colours a 4 x 4 block, so a block across the edge would otherwise take the (meaningless)
	colour of the transparent pixels and fringe the monster with it."""
	rgba = np.asarray(image, dtype=np.float32) / 255.0
	alpha = rgba[..., 3:4]
	colour = rgba[..., :3]
	filled = colour.copy()
	known = alpha[..., 0] > 0.02
	weight = alpha
	premultiplied = colour * alpha
	for radius in (1, 3, 8, 24):
		blur = lambda a: np.asarray(
			Image.fromarray(np.clip(a * 255.0, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(radius)),
			dtype=np.float32) / 255.0
		w = blur(weight[..., 0])[..., None]
		c = np.stack([blur(premultiplied[..., i]) for i in range(3)], axis=-1)
		spread = c / np.maximum(w, 1e-4)
		fill = (~known) & (w[..., 0] > 0.002)
		filled[fill] = spread[fill]
		known = known | fill
	out = np.concatenate([filled, alpha], axis=-1)
	return Image.fromarray(np.clip(out * 255.0 + 0.5, 0, 255).astype(np.uint8))


def portrait(path):
	image = Image.open(path).convert("RGBA")
	# Pillow resizes RGBA premultiplied: no dark halo from the transparent pixels.
	image = image.resize((SIDE, SIDE), Image.LANCZOS)
	return blocks(bleed(image), KIND_DXT5)


def field(path):
	"""The page's .portrait.pic background: the picture, cover-fitted, under
	radial-gradient(circle at 50% 45%, rgba(8,8,11,0) 48%, rgba(8,8,11,0.5) 100%)."""
	image = Image.open(path).convert("RGB")
	w, h = image.size
	side = min(w, h)
	image = image.crop(((w - side) // 2, (h - side) // 2, (w + side) // 2, (h + side) // 2))
	image = image.resize((SIDE, SIDE), Image.LANCZOS)
	pixels = np.asarray(image, dtype=np.float32)
	y, x = np.mgrid[0:SIDE, 0:SIDE].astype(np.float32) + 0.5
	cx, cy = 0.5 * SIDE, 0.45 * SIDE
	reach = max(np.hypot(cx - cornerX, cy - cornerY) for cornerX in (0, SIDE) for cornerY in (0, SIDE))
	t = np.clip((np.hypot(x - cx, y - cy) / reach - 0.48) / (1.0 - 0.48), 0, 1)[..., None] * 0.5
	pixels = pixels * (1 - t) + np.array([8, 8, 11], dtype=np.float32) * t
	image = Image.fromarray(np.clip(pixels + 0.5, 0, 255).astype(np.uint8)).convert("RGBA")
	return blocks(image, KIND_DXT1)


def write(target, version, kind, data):
	temp = target + ".tmp"
	with open(temp, "wb") as f:
		f.write(b"L2BP")
		f.write(struct.pack("<iiHBBi", 1, version, SIDE, kind, 0, len(data)))
		f.write(data)
	os.replace(temp, target)


def version_of(target):
	try:
		with open(target, "rb") as f:
			head = f.read(12)
		return struct.unpack_from("<i", head, 8)[0] if head[:4] == b"L2BP" and len(head) == 12 else -1
	except OSError:
		return -1


def main(argv):
	force = "--force" in argv
	args = [a for a in argv if not a.startswith("--")]
	art = args[0] if args else os.path.join("dist", "game", "data", "html", "custom", "bestiary")
	if not os.path.isdir(art):
		sys.exit(f"no such folder: {art}")
	out = os.path.join(art, "client", "portraits")
	os.makedirs(out, exist_ok=True)

	jobs = {}
	looks = os.path.join(art, "looks")
	for name in os.listdir(looks) if os.path.isdir(looks) else []:
		m = PICTURE.match(name)
		if m and m.group(1) != FIELD:
			jobs[m.group(1)] = os.path.join(looks, name)
	background = os.path.join(os.path.dirname(os.path.abspath(art)), "bestiary-lib", "backgrounds", "portrait.webp")

	made = kept = failed = 0
	for look, source in sorted(jobs.items()) + ([(FIELD, background)] if os.path.isfile(background) else []):
		version = int(os.path.getmtime(source))
		target = os.path.join(out, f"{look}.l2bp")
		if not force and version_of(target) == version:
			kept += 1
			continue
		try:
			if look == FIELD:
				write(target, version, KIND_DXT1, field(source))
			else:
				write(target, version, KIND_DXT5, portrait(source))
			made += 1
		except Exception as e:  # one bad picture must not stop the rest
			failed += 1
			print(f"{source}: {e}", file=sys.stderr)
		if (made + kept) % 500 == 0 and made:
			print(f"  {made + kept} of {len(jobs)}...")

	dropped = 0
	for name in os.listdir(out):
		m = COPY.match(name)
		if (m and m.group(1) != FIELD and m.group(1) not in jobs) or (not m and re.match(r"^\d+\.l2bp$", name)):
			os.remove(os.path.join(out, name))  # a look no longer baked, or a copy of the old per-monster layout
			dropped += 1
	size = sum(os.path.getsize(os.path.join(out, n)) for n in os.listdir(out) if n.endswith(".l2bp"))
	print(f"{made} made, {kept} up to date, {dropped} dropped, {failed} failed; "
		  f"{len(os.listdir(out))} files, {size / 1048576:.1f} MB in {out}")
	return 1 if failed else 0


if __name__ == "__main__":
	sys.exit(main(sys.argv[1:]))
