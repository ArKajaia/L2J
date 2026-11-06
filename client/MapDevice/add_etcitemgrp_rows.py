"""
Adds the ten "Map (Tier N)" rows (object_id 18101-18110), the five unique maps (18111-18115) and the Primal Crucible's
Primal Essence (18116) and Titan Scroll (18117) to an EtcItemgrp.dat exported by
L2ClientDat as text (item_begin ... item_end, tab-separated key=value).

Each new row is a copy of the retail Pirate's Treasure Map row (object_id 5915), so every field
(mesh, texture, sounds, drop look) is a retail one and nothing has to be guessed. Only these change:
  object_id         18101 .. 18110
  icon              tiers 1-5 the ivory map, tiers 6-10 the yellow map (the same icons as the server XML)
  weight            0, like the server
  related_quest_id  cleared
Primal Essence and the Titan Scroll copy other retail rows the same way, keeping their icons: Blood of Eva (3835) and
Transformation Scroll: Zaken (17140).

  python add_etcitemgrp_rows.py EtcItemgrp.txt            -> writes EtcItemgrp.patched.txt
  python add_etcitemgrp_rows.py EtcItemgrp.txt --in-place -> overwrites EtcItemgrp.txt

Running it again on a file that already has the rows replaces them instead of adding them twice. The new rows go in
object_id order. Rows left by an older version of this script under the old ids 50101-50119 are removed.
"""

import sys

SOURCE_ID = 5915  # Pirate's Treasure Map
FIRST_ID = 18101
TIERS = 10
UNIQUE_IDS = (18111, 18112, 18113, 18114, 18115, 18118, 18119)  # The Crimson Coliseum, The Gilded Vault, The Ossuary of Echoes, The Gambler's Den, The Primal Crucible, The Ketra-Varka Front, The Tomb Raiders
COPIES = {18116: 3835, 18117: 17140}  # Primal Essence (Blood of Eva), Titan Scroll (Transformation Scroll: Zaken): new id -> retail row
OLD_IDS = range(50101, 50120)  # the ids these items had before: the client doesn't take items that high, so their rows are removed
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

	new_ids = set(range(FIRST_ID, FIRST_ID + TIERS)) | set(UNIQUE_IDS) | set(COPIES)
	replaced = sorted(i for i in (object_id(line) for line in lines) if i in new_ids)
	removed = sorted(i for i in (object_id(line) for line in lines) if i in OLD_IDS)
	lines = [line for line in lines if object_id(line) not in new_ids and object_id(line) not in OLD_IDS]
	source = next((line for line in lines if object_id(line) == SOURCE_ID), None)
	if source is None:
		sys.exit("Row not found for object_id %d - is this EtcItemgrp.dat?" % SOURCE_ID)

	ending = "\r\n" if source.endswith("\r\n") else "\n"
	if lines and not lines[-1].endswith(("\n", "\r\n")):
		lines[-1] += ending
	rows = [(FIRST_ID + tier - 1, ICONS["ivory" if tier <= 5 else "yellow"], source) for tier in range(1, TIERS + 1)]
	rows += [(item_id, ICONS["yellow"], source) for item_id in UNIQUE_IDS]
	for item_id, source_id in COPIES.items():
		copy = next((line for line in lines if object_id(line) == source_id), None)
		if copy is None:
			sys.exit("Row not found for object_id %d - is this EtcItemgrp.dat?" % source_id)
		rows.append((item_id, None, copy))
	new_rows = []
	for item_id, icon, base in rows:
		fields, keys = parse(base)
		values = {
			"object_id": str(item_id),
			"weight": "0",
			"related_quest_id": "{}",
		}
		if icon is not None:
			values["icon"] = icon
		for key, value in values.items():
			if key in keys:
				fields[keys[key]] = key + "=" + value
			elif key == "object_id":
				sys.exit("object_id %d has no object_id field" % object_id(base))
		row = "\t".join(fields) + ending
		new_rows.append((item_id, row))
		print(row.rstrip("\r\n"))

	# Each row goes in object_id order, after the last row with a lower id, like the retail rows round it.
	for item_id, row in sorted(new_rows):
		at = len(lines)
		for i in range(len(lines) - 1, -1, -1):
			other = object_id(lines[i])
			if other is not None and other < item_id:
				at = i + 1
				break
		lines.insert(at, row)

	if replaced:
		print("\nReplaced the rows already there for object_id " + ", ".join(map(str, replaced)) + " (an earlier run of this script).")
	if removed:
		print("Removed the old rows for object_id " + ", ".join(map(str, removed)) + " (the ids these items had before).")

	out = path if in_place else path.rsplit(".", 1)[0] + ".patched.txt"
	with open(out, "w", encoding=encoding, newline="") as f:
		f.writelines(lines)
	print("\nWritten to " + out)


if __name__ == "__main__":
	main()
