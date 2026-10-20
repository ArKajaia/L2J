#!/usr/bin/env python3
"""
Merges the passive tree's region files (dist/game/data/passivetree/*.xml) into one
dist/game/data/passivetree/PassiveTree.xml. Run once.

Every <node> element is copied as the source has it, attributes and <requires> order
included; each former file starts with one comment line naming its region. The script
checks the merged file against the sources (node and link counts, and a canonical dump
of every node's attributes and links) before it deletes the region files and the old,
unused dist/game/data/PassiveTree.xml.

The layout is the one PassiveTreeEditor.toXml() writes, so a save of the unchanged tree
from the admin editor gives the same file back apart from the version.

Usage: python tools/passivetree/merge_tree.py [--keep]   (--keep: write, check, delete nothing)
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DATA = os.path.join(ROOT, "dist", "game", "data")
TREE_DIR = os.path.join(DATA, "passivetree")
OUTPUT = os.path.join(TREE_DIR, "PassiveTree.xml")
LEGACY = os.path.join(DATA, "PassiveTree.xml")
EXPECTED_NODES = 1520
EXPECTED_REQUIRES = 3348

LIST_LINE = '<list version="1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="../xsd/passivetree.xsd">'
HEADER = "<!-- Passive tree: {count} nodes. x/y are authoritative layout coords. -->"


def region_comment(header_line, file_name):
	"""'<!-- Duskwood: 49 nodes, an outer region ... Deadeye. x/y are ... -->' -> ' Duskwood: an outer region ... Deadeye. '"""
	m = re.match(r"^<!--\s*(.*?)\s*-->$", header_line.strip())
	text = m.group(1) if m else os.path.splitext(file_name)[0]
	text = re.sub(r"\s*x/y are authoritative layout coords\.\s*$", "", text)
	text = re.sub(r"^(.*?): \d+ nodes(?:, |\. ?|$)", r"\1: ", text).strip()
	text = text[:-1] if text.endswith(":") else text
	return " " + text + " "


def node_lines(text, name):
	"""The lines between <list ...> and </list>, as they are."""
	lines = text.replace("\r\n", "\n").split("\n")
	start = next(i for i, line in enumerate(lines) if line.startswith("<list"))
	end = max(i for i, line in enumerate(lines) if line.strip() == "</list>")
	body = lines[start + 1:end]
	for line in body:
		if line.strip() and not re.match(r"^\t(<node |\t<requires |</node>)", line):
			sys.exit(f"{name}: unexpected line {line!r}")
	return body


def canonical(root):
	dump = {}
	for el in root.iter("node"):
		node_id = int(el.get("id"))
		if node_id in dump:
			sys.exit(f"node {node_id} is in the tree twice")
		dump[node_id] = (tuple(el.attrib.items()), tuple(int(r.get("nodeId")) for r in el.findall("requires")))
	return dump


def main():
	keep = "--keep" in sys.argv[1:]
	sources = sorted(p for p in glob.glob(os.path.join(TREE_DIR, "*.xml")) if os.path.basename(p) != "PassiveTree.xml")
	if not sources:
		sys.exit("no region files in " + TREE_DIR)

	out, before = [], {}
	for path in sources:
		name = os.path.basename(path)
		text = open(path, encoding="utf-8").read()
		header = text.replace("\r\n", "\n").split("\n")[1]
		out.append("\t<!--" + region_comment(header, name).replace("--", "- -") + "-->")
		out += node_lines(text, name)
		for node_id, value in canonical(ET.parse(path).getroot()).items():
			if node_id in before:
				sys.exit(f"node {node_id} is in two files")
			before[node_id] = value

	merged = "\n".join(['<?xml version="1.0" encoding="UTF-8"?>', HEADER.format(count=len(before)), LIST_LINE] + out + ["</list>"])
	after = canonical(ET.fromstring(merged.encode("utf-8")))
	requires = sum(len(links) for _, links in after.values())
	if len(after) != EXPECTED_NODES or requires != EXPECTED_REQUIRES:
		sys.exit(f"counts: {len(after)} nodes, {requires} links (expected {EXPECTED_NODES} and {EXPECTED_REQUIRES})")
	if after != before:
		sys.exit("the merged tree differs from the region files")

	with open(OUTPUT, "w", encoding="utf-8", newline="\n") as fh:
		fh.write(merged)
	print(f"wrote {OUTPUT}: {len(after)} nodes, {requires} links from {len(sources)} files")
	if keep:
		return
	for path in sources + ([LEGACY] if os.path.exists(LEGACY) else []):
		os.remove(path)
		print("removed " + os.path.relpath(path, ROOT))


if __name__ == "__main__":
	main()
