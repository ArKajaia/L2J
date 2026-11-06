"""
Adds the ten "Map (Tier N)" rows (object_id 50101-50110) and the three unique maps (50111-50113) to an EtcItemgrp.dat exported by
L2ClientDat as text (item_begin ... item_end, tab-separated key=value).

Each new row is a copy of the retail Pirate's Treasure Map row (object_id 5915), so every field
(mesh, texture, sounds, drop look) is a retail one and nothing has to be guessed. Only these change:
  object_id         50101 .. 50110
  icon              tiers 1-5 the ivory map, tiers 6-10 the yellow map (the same icons as the server XML)
  weight            0, like the server
  related_quest_id  cleared

  python add_etcitemgrp_rows.py EtcItemgrp.txt            -> writes EtcItemgrp.patched.txt
  python add_etcitemgrp_rows.py EtcItemgrp.txt --in-place -> overwrites EtcItemgrp.txt

Running it again on a file that already has the rows replaces them instead of adding them twice.
"""

import sys

SOURCE_ID = 5915  # Pirate's Treasure Map
FIRST_ID = 50101
TIERS = 10
UNIQUE_IDS = (50111, 50112, 50113)  # The Crimson Coliseum, The Gilded Vault, The Ossuary of Echoes
ICONS = {
	"ivory": "{[icon.etc_map_ivory_i00]}",
	"yellow": "{[icon.etc_map_yellow_i00]}",
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

	new_ids = set(range(FIRST_ID, FIRST_ID + TIERS)) | set(UNIQUE_IDS)
	lines = [line for line in lines if object_id(line) not in new_ids]
	source = next((line for line in lines if object_id(line) == SOURCE_ID), None)
	if source is None:
		sys.exit("Row not found for object_id %d - is this EtcItemgrp.dat?" % SOURCE_ID)

	ending = "\r\n" if source.endswith("\r\n") else "\n"
	if lines and not lines[-1].endswith(("\n", "\r\n")):
		lines[-1] += ending
	rows = [(FIRST_ID + tier - 1, ICONS["ivory" if tier <= 5 else "yellow"]) for tier in range(1, TIERS + 1)]
	rows += [(item_id, ICONS["yellow"]) for item_id in UNIQUE_IDS]
	for item_id, icon in rows:
		fields, keys = parse(source)
		values = {
			"object_id": str(item_id),
			"icon": icon,
			"weight": "0",
			"related_quest_id": "{}",
		}
		for key, value in values.items():
			if key in keys:
				fields[keys[key]] = key + "=" + value
			elif key == "object_id":
				sys.exit("object_id %d has no object_id field" % SOURCE_ID)
		row = "\t".join(fields) + ending
		lines.append(row)
		print(row.rstrip("\r\n"))

	out = path if in_place else path.rsplit(".", 1)[0] + ".patched.txt"
	with open(out, "w", encoding=encoding, newline="") as f:
		f.writelines(lines)
	print("\nWritten to " + out)


if __name__ == "__main__":
	main()
