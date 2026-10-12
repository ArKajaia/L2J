"""
Patches the Dragon Coin, Champion Medal and Sealed Cache rows of an EtcItemgrp.dat
exported by L2ClientDat as text (item_begin ... item_end, tab-separated key=value).

Only the fields below change; every other field (meshes, textures, sounds...) stays
as the retail row has it, so nothing has to be guessed.

  python patch_etcitemgrp.py EtcItemgrp.txt            -> writes EtcItemgrp.patched.txt
  python patch_etcitemgrp.py EtcItemgrp.txt --in-place -> overwrites EtcItemgrp.txt

The patched rows are printed so you can check them before saving the .dat.
"""

import sys

# object_id -> fields to set. Weights match the server's item XML (no weight = 0).
# related_quest_id is cleared: these are no longer quest or event items.
PATCHES = {
	3481: {  # Dragon Coin (was Gold Dragon, a Coins of Magic coin)
		"icon": "{[icon.etc_coins_gold_i00]}",
		"weight": "0",
		"related_quest_id": "{}",
	},
	6393: {  # Champion Medal (was Event - Glittering Medal)
		"icon": "{[icon.etc_event_glitter_medal_i00]}",
		"weight": "0",
		"related_quest_id": "{}",
	},
	6492: {  # Sealed Cache (Common) (was Small Green Treasure Chest)
		"icon": "{[icon.etc_treasure_box_i07]}",
		"weight": "100",
		"related_quest_id": "{}",
	},
	6499: {  # Sealed Cache (Rare) (was Blue Treasure Chest)
		"icon": "{[icon.etc_treasure_box_i01]}",
		"weight": "100",
		"related_quest_id": "{}",
	},
	6509: {  # Sealed Cache (Epic) (was Big Purple Treasure Chest)
		"icon": "{[icon.etc_treasure_box_i04]}",
		"weight": "100",
		"related_quest_id": "{}",
	},
}

# The Dragon Coin lies on the ground and drops with the sound of Adena (object_id 57),
# copied from the Adena row of the same file.
COPY_FROM = {
	3481: (57, ("drop_type", "drop_anim_type", "drop_radius", "drop_height", "drop_mesh", "drop_texture", "droptexnum", "drop_sound")),
}


def parse(line):
	fields = line.rstrip("\r\n").split("\t")
	return fields, {f.split("=", 1)[0]: i for i, f in enumerate(fields) if "=" in f}


def object_id(line):
	if not line.startswith("item_begin"):
		return None
	fields, keys = parse(line)
	try:
		return int(fields[keys["object_id"]].split("=", 1)[1])
	except (KeyError, ValueError):
		return None


def main():
	if len(sys.argv) < 2:
		print(__doc__)
		sys.exit(1)
	path = sys.argv[1]
	in_place = "--in-place" in sys.argv[2:]
	raw = open(path, "rb").read()
	encoding = "utf-16" if raw[:2] in (b"\xff\xfe", b"\xfe\xff") else "utf-8-sig" if raw[:3] == b"\xef\xbb\xbf" else "utf-8"
	lines = raw.decode(encoding).splitlines(keepends=True)

	rows = {object_id(line): i for i, line in enumerate(lines)}
	missing = [i for i in list(PATCHES) + [src for src, _ in COPY_FROM.values()] if i not in rows]
	if missing:
		sys.exit("Rows not found for object_id " + ", ".join(map(str, missing)) + " - is this EtcItemgrp.dat?")

	for oid, values in PATCHES.items():
		values = dict(values)
		if oid in COPY_FROM:
			src, keys = COPY_FROM[oid]
			src_fields, src_keys = parse(lines[rows[src]])
			for key in keys:
				if key in src_keys:
					values[key] = src_fields[src_keys[key]].split("=", 1)[1]
		fields, keys = parse(lines[rows[oid]])
		for key, value in values.items():
			if key not in keys:
				sys.exit("object_id %d has no %s field" % (oid, key))
			fields[keys[key]] = key + "=" + value
		ending = "\r\n" if lines[rows[oid]].endswith("\r\n") else "\n"
		lines[rows[oid]] = "\t".join(fields) + ending
		print(lines[rows[oid]].rstrip("\r\n"))

	out = path if in_place else path.rsplit(".", 1)[0] + ".patched.txt"
	with open(out, "w", encoding=encoding, newline="") as f:
		f.writelines(lines)
	print("\nWritten to " + out)


if __name__ == "__main__":
	main()
