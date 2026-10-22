#!/usr/bin/env python3
"""
Prints a bestiary catalog (custom.Bestiary.BestiaryBlob, or the client's System\\Bestiary.cache
that //bestiary_export writes) and checks it against the layout in client/Bestiary/PROTOCOL.txt:
every field in range, nothing left over. Also prints what each part of it weighs.

Usage: python tools/bestiary/dump_blob.py <file> [--monsters]
"""
import hashlib
import struct
import sys

MAX_MONSTERS, MAX_AREAS, MAX_REGIONS, MAX_FORMS, MAX_STRING, MAX_BLOB = 8192, 4096, 1024, 32, 256, 2 << 20
POINT_UNIT = 16
CACHE_HEADER = 44
TYPES = ["monster", "raid boss", "grand boss"]


class Reader:
	def __init__(self, data):
		self.data, self.pos = data, 0

	def take(self, fmt):
		size = struct.calcsize("<" + fmt)
		if self.pos + size > len(self.data):
			raise ValueError(f"truncated at byte {self.pos}")
		value = struct.unpack_from("<" + fmt, self.data, self.pos)
		self.pos += size
		return value[0] if len(value) == 1 else value

	def string(self, limit=MAX_STRING):
		n = self.take("H")
		if n > limit:
			raise ValueError(f"a string of {n} units at byte {self.pos - 2}")
		raw = self.data[self.pos:self.pos + (2 * n)]
		if len(raw) != 2 * n:
			raise ValueError(f"truncated string at byte {self.pos}")
		self.pos += 2 * n
		return raw.decode("utf-16-le", errors="replace")


def parse(data):
	if len(data) > MAX_BLOB:
		raise ValueError(f"{len(data)} bytes")
	r = Reader(data)
	sizes = {}
	cat = {"format": r.take("i"), "flags": r.take("i")}
	if cat["format"] != 1:
		raise ValueError(f"format {cat['format']}")
	if cat["flags"] & ~7:
		raise ValueError(f"flags {cat['flags']:#x}")
	tiers = []
	for _ in range(r.take("B")):
		tiers.append({"name": r.string(), "kills": r.take("i"), "bossKills": r.take("i"), "drop": r.take("i"),
			"bossDrop": r.take("i"), "regionExp": r.take("i"), "slayerDamage": r.take("i"), "slayerDefense": r.take("i")})
	if not 1 <= len(tiers) <= 8:
		raise ValueError(f"{len(tiers)} tiers")
	cat["tiers"], cat["slayerMin"] = tiers, r.take("i")
	forms = r.take("B")
	if forms > MAX_FORMS:
		raise ValueError(f"{forms} forms")
	cat["forms"] = [(r.take("i"), r.string()) for _ in range(forms)]
	cat["races"] = [r.string() for _ in range(r.take("B"))]
	sizes["header, tiers, forms, races"] = r.pos

	start = r.pos
	regions = r.take("H")
	if regions > MAX_REGIONS:
		raise ValueError(f"{regions} regions")
	cat["regions"] = [r.string() for _ in range(regions)]
	areas = r.take("H")
	if areas > MAX_AREAS:
		raise ValueError(f"{areas} areas")
	cat["areas"] = []
	for _ in range(areas):
		a = {"id": r.take("i"), "region": r.take("H"), "level": r.take("B"), "name": r.string()}
		if a["region"] >= max(1, regions):
			raise ValueError(f"area {a['id']}: region {a['region']}")
		cat["areas"].append(a)
	sizes["regions, hunting grounds"] = r.pos - start

	count = r.take("H")
	if count > MAX_MONSTERS:
		raise ValueError(f"{count} monsters")
	monsters, point_bytes, name_bytes = [], 0, 0
	start = r.pos
	for _ in range(count):
		m = {"id": r.take("i"), "displayId": r.take("i"), "level": r.take("B"), "race": r.take("B"), "type": r.take("B"),
			"height": r.take("H"), "radius": r.take("H")}
		before = r.pos
		m["name"] = r.string()
		name_bytes += r.pos - before
		if m["race"] >= len(cat["races"]) or m["type"] > 2 or m["id"] <= 0 or m["displayId"] < 0:
			raise ValueError(f"monster {m['id']}: race {m['race']}, type {m['type']}, display {m['displayId']}")
		m["areas"] = [r.take("H") for _ in range(r.take("B"))]
		if any(a >= areas for a in m["areas"]):
			raise ValueError(f"monster {m['id']}: an area out of range")
		before = r.pos
		m["points"] = [(r.take("h") * POINT_UNIT, r.take("h") * POINT_UNIT) for _ in range(r.take("B"))]
		point_bytes += r.pos - before
		monsters.append(m)
	sizes["monsters (fixed fields, areas)"] = r.pos - start - point_bytes - name_bytes
	sizes["monster names"] = name_bytes
	sizes["spawn points"] = point_bytes
	start = r.pos
	cat["cellSize"] = r.take("H")
	if cat["cellSize"] == 0:
		raise ValueError("a land cell size of 0")
	cat["land"] = [(r.take("h"), r.take("h"), r.take("H")) for _ in range(r.take("H"))]
	sizes["land"] = r.pos - start
	start = r.pos
	glyphs = r.take("H")
	if glyphs > 64:
		raise ValueError(f"{glyphs} glyphs")
	cat["glyphs"] = [(r.string(), r.string(), r.string(4096)) for _ in range(glyphs)]
	sizes["glyphs"] = r.pos - start
	if r.pos != len(data):
		raise ValueError(f"{len(data) - r.pos} bytes after the last glyph")
	if len({m["id"] for m in monsters}) != len(monsters):
		raise ValueError("a monster id is listed twice")
	cat["monsters"], cat["sizes"] = monsters, sizes
	return cat


def main():
	data = open(sys.argv[1], "rb").read()
	if data[:4] == b"L2BE":  # the client's cache: "L2BE", int format, byte[32] sha256, int size, then the definition
		size = struct.unpack_from("<i", data, 40)[0]
		if hashlib.sha256(data[CACHE_HEADER:]).digest() != data[8:40] or size != len(data) - CACHE_HEADER:
			sys.exit("the cache's hash or size does not match its definition")
		data = data[CACHE_HEADER:]
	cat = parse(data)
	flags = [n for b, n in ((1, "showNames"), (2, "showAreas"), (4, "craftGoals")) if cat["flags"] & b]
	print(f"format {cat['format']}, {len(data)} bytes, sha256 {hashlib.sha256(data).hexdigest()}, flags {' '.join(flags) or '-'}")
	for t in cat["tiers"]:
		print(f"  tier {t['name']!r}: {t['kills']} kills ({t['bossKills']} boss), drop +{t['drop']}% (+{t['bossDrop']}%), "
			f"region EXP {t['regionExp']}%, slayer {t['slayerDamage']}%/{t['slayerDefense']}%")
	print(f"{len(cat['forms'])} forms, {len(cat['races'])} races, {len(cat['regions'])} regions, {len(cat['areas'])} hunting grounds")
	points = sum(len(m["points"]) for m in cat["monsters"])
	bosses = sum(1 for m in cat["monsters"] if m["type"])
	print(f"{len(cat['monsters'])} monsters ({bosses} bosses), {points} spawn points, {len(cat['land'])} land cells of {cat['cellSize']}, {len(cat['glyphs'])} glyphs, "
		f"{(len(data) + 16383) // 16384} chunks of 16384")
	for part, size in cat["sizes"].items():
		print(f"  {part:32} {size:8} bytes")
	# What the client holds once parsed (shared/bestiary_blob.h): 40-byte monster records, 4-byte points, names, areas.
	parsed = len(cat["monsters"]) * 64 + points * 4 + sum(2 * len(m["name"]) + 2 * len(m["areas"]) for m in cat["monsters"]) + len(cat["areas"]) * 48
	print(f"about {parsed // 1024} KB in the client once parsed")
	if "--monsters" in sys.argv[2:]:
		for m in cat["monsters"]:
			look = f" looks like {m['displayId']}" if m["displayId"] else ""
			print(f"  {m['id']:6} lv{m['level']:3} {TYPES[m['type']]:10} {cat['races'][m['race']]:12} {m['name']!r}{look} "
				f"areas {[cat['areas'][a]['name'] for a in m['areas']]} {len(m['points'])} points")


if __name__ == "__main__":
	main()
