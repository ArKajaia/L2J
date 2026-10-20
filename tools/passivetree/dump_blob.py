#!/usr/bin/env python3
"""
Prints a passive tree definition (PassiveTreeBlob, the client's System\\PassiveTree.cache
without its 48-byte header, or the cache file itself) and checks it against the layout
in client/PassiveTree/PROTOCOL.txt: every field in range, nothing left over.

Usage: python tools/passivetree/dump_blob.py <file> [--nodes]
"""
import hashlib
import struct
import sys

TYPES = ["START", "SMALL", "NOTABLE", "KEYSTONE", "MASTER", "FUNCTION", "ACTIVE_SKILL"]
MAX_NODES, MAX_PARENTS, MAX_GLYPHS, MAX_STRING = 2048, 32, 256, 4096


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

	def string(self):
		n = self.take("H")
		if n > MAX_STRING:
			raise ValueError(f"a string of {n} units at byte {self.pos - 2}")
		raw = self.data[self.pos:self.pos + (2 * n)]
		if len(raw) != 2 * n:
			raise ValueError(f"truncated string at byte {self.pos}")
		self.pos += 2 * n
		return raw.decode("utf-16-le")


def parse(data):
	r = Reader(data)
	tree = {"format": r.take("i"), "version": r.take("i"), "respec": r.take("q"), "resetItem": r.take("i"),
		"resetCount": r.take("q"), "resetText": r.string(), "templates": r.take("B")}
	if tree["format"] != 1:
		raise ValueError(f"format {tree['format']}")
	tree["caps"] = [(r.string(), r.take("d")) for _ in range(r.take("H"))]
	tree["sectors"] = [r.string() for _ in range(r.take("B"))]
	tree["groups"] = [[r.string() for _ in range(r.take("B"))] for _ in range(r.take("B"))]
	glyphs = r.take("H")
	if glyphs > MAX_GLYPHS:
		raise ValueError(f"{glyphs} glyphs")
	tree["glyphs"] = [(r.string(), r.string()) for _ in range(glyphs)]
	count = r.take("H")
	if count > MAX_NODES:
		raise ValueError(f"{count} nodes")
	nodes = []
	for _ in range(count):
		n = {"id": r.take("i"), "type": r.take("B"), "cost": r.take("B"), "sector": r.take("B"), "x": r.take("i") / 10, "y": r.take("i") / 10}
		if n["type"] >= len(TYPES) or n["sector"] >= len(tree["sectors"]):
			raise ValueError(f"node {n['id']}: type {n['type']}, sector {n['sector']}")
		has_orbit = r.take("B")
		if has_orbit not in (0, 1):
			raise ValueError(f"node {n['id']}: hasOrbit {has_orbit}")
		n["orbit"] = (r.take("i") / 10, r.take("i") / 10) if has_orbit else None
		n["name"], n["icon"], n["effect"], n["description"] = r.string(), r.string(), r.string(), r.string()
		n["skillId"] = r.take("i")
		parents = r.take("B")
		if parents > MAX_PARENTS:
			raise ValueError(f"node {n['id']}: {parents} links")
		n["parents"] = [r.take("i") for _ in range(parents)]
		nodes.append(n)
	tree["nodes"] = nodes
	if r.pos != len(data):
		raise ValueError(f"{len(data) - r.pos} bytes after the last node")
	ids = {n["id"] for n in nodes}
	if len(ids) != len(nodes):
		raise ValueError("a node id is used twice")
	missing = [(n["id"], p) for n in nodes for p in n["parents"] if p not in ids]
	if missing:
		raise ValueError(f"links to missing nodes: {missing[:5]}")
	return tree


def main():
	data = open(sys.argv[1], "rb").read()
	if data[:4] == b"L2PT":  # the client's cache: "L2PT", int format, int version, byte[32] sha256, int size, then the definition
		size = struct.unpack_from("<i", data, 44)[0]
		if hashlib.sha256(data[48:]).digest() != data[12:44] or size != len(data) - 48:
			sys.exit("the cache's hash or size does not match its definition")
		data = data[48:]
	tree = parse(data)
	links = sum(len(n["parents"]) for n in tree["nodes"])
	print(f"format {tree['format']}, version {tree['version']}, {len(data)} bytes, sha256 {hashlib.sha256(data).hexdigest()}")
	print(f"respec {tree['respec']} Adena/point; reset {tree['resetCount']} x item {tree['resetItem']} ({tree['resetText']!r}); {tree['templates']} templates")
	print(f"{len(tree['caps'])} caps, {len(tree['sectors'])} sectors, {len(tree['groups'])} exclusive groups, {len(tree['glyphs'])} glyphs")
	print(f"{len(tree['nodes'])} nodes, {links} links, {(len(data) + 16383) // 16384} chunks of 16384")
	if "--nodes" in sys.argv[2:]:
		for n in tree["nodes"]:
			print(f"  {n['id']:6} {TYPES[n['type']]:12} {tree['sectors'][n['sector']]:12} {n['name']!r} {n['effect']!r} -> {n['parents']}")


if __name__ == "__main__":
	main()
