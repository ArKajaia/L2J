#!/usr/bin/env python3
"""
Lays out the passive tree in data/passivetree/*.xml, Path of Exile style:
 - every notable cluster is a ring: the path enters the ring at its first small and the three smalls
   curve around it to the notable (those links are drawn as arcs),
 - the six sector spines are straight roads from the START to the archetype MASTER; each START sends three roads
   to the first junction (the middle one plain and shortest, the side ones a step longer through a cluster's entry),
   and beside the spine's three middle stretches runs a lane: a pass-through cluster (small > NOTABLE > small, with
   a spur) that costs one point more than the plain road but carries a notable (SECTOR_BRAID, SECTOR_LANES),
 - the bridges between neighbouring sectors are big circular roads around the tree centre,
 - the six hybrid sectors sit in the wedges between the arms, each built from one shared template,
 - the Outer Rim is one more circular road round the whole tree, reached by three spurs from every START (to the
   gate, and to the rim road on either side of it),
   with six regions beyond it, each shaped differently.

Only x/y and orbitX/orbitY are written; ids, links and stats are never touched, so it can be re-run
after editing the tree. orbitX/orbitY is the centre of the circle a node sits on: the web planner draws a
link between two nodes on the same circle as an arc instead of a straight line.

The script checks its own result (no crossing links, no nodes on top of each other or of a link) and
refuses to write the files if the layout is not clean.

Usage: python3 layout_tree.py [path/to/data/passivetree]
"""
import glob
import itertools
import math
import os
import re
import sys
import xml.etree.ElementTree as ET
from xml.sax.saxutils import quoteattr

DATA_DIR = [a for a in sys.argv[1:] if not a.startswith("--")][0] if [a for a in sys.argv[1:] if not a.startswith("--")] else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "passivetree")

# ------------------------------------------------------------------ layout constants
SECTOR_BASES = [1000, 2000, 3000, 4000, 5000, 6000]  # Vanguard, Juggernaut, Shadowblade, Deadeye, Arcanist, Hierophant
HYBRID_BASES = [30000, 30100, 30200, 30300, 30400, 30500]  # hybrid i sits between sector i and sector i+1
SPINE_DEG = 60  # sector i's spine points at i * 60 degrees (+y is down, as in the planner's SVG)

R_NEXUS = 520
R_MASTER = 720
R_JUNCTION = {1: 2850, 16: 2250, 39: 1650, 62: 1050}  # local id -> radius: J0 (outer) .. J3 (inner)
R_START = 3300

R_RIM = 4000  # the Outer Rim road
R_RIM_SPUR = [3475, 3650, 3825]  # the spur from each START out to its rim gate
RIM_BASE = 39000  # rim ids: 39000 + sector * 100 (+0 gate, +1.. spur, +10.. arc road towards the next sector)
REGION_BASE = 40000  # region ids: 40000 + region * 1000 + local id, region i sitting beyond the rim between sectors i and i+1
ROAD_IDS = range(33000, 34000)  # "Lifeblood Trail" nodes lengthening a hybrid's long links: spaced evenly along the link they split

RING_RADIUS = 105  # radius of a notable cluster's ring
RING_STEP = 70  # degrees between consecutive nodes on a ring

# What hangs off each junction and where: (local id of the first node, outward(+1)/inward(-1), side(+1 = towards the next sector, -1 = towards the previous one))
# (The START road and the +1 side of the three middle segments are laid out from SECTOR_BRAID and SECTOR_LANES instead.)
JUNCTION_SLOTS = {
	16: [(19, 1, -1), (31, -1, -1)],
	39: [(42, 1, -1), (54, -1, -1)],
	62: [(65, 1, -1), (77, -1, -1), (81, -1, 1)],
}
# The roads that give a sector its route choices, in the sector's own frame: (u, v) with u the distance from the tree
# centre along the spine and v sideways, positive towards the next sector.
#  - START road: three roads from START to J0. The middle one (2, 3) is plain; the two side roads (90 > 4 > 91 and
#    92 > 9 > 93) are a step longer and run through the entry small of a cluster, whose ring hangs outside the road.
SECTOR_BRAID = {90: (3200, -165), 4: (3075, -230), 91: (2950, -165), 92: (3200, 165), 9: (3075, 230), 93: (2950, 165)}
SECTOR_BRAID_RINGS = {4: (-1, 1), 9: (1, -1)}  # entry small -> (side, curl)
#  - Lanes: a pass-through cluster beside the plain road of each of the three middle segments, on the +1 side:
#    junction > first small > NOTABLE > second small > next junction, with a spur small off the notable and the
#    segment's pod (approach > keystone / active skill) off the first small. Local positions are relative to the
#    junction the lane leaves from, (first small, notable, second small, spur, approach, pod end).
SECTOR_LANES = {
	(1, 25): [(-130, 165), (-300, 235), (-470, 165), (-300, 385), (-80, 300), (-40, 430)],
	(16, 48): [(-130, 165), (-300, 235), (-470, 165), (-300, 385), (-150, 305), (-175, 440)],
	(39, 71): [(-130, 150), (-300, 205), (-470, 150), (-300, 340), (-120, 280), (-140, 400)],
}
# Spine roads: (from, to, radius of each in-between node)
SPINE_ROADS = [(0, 1, [3150, 3000]), (1, 16, [2650, 2450]), (16, 39, [2050, 1850]), (39, 62, [1450, 1250]), (62, 79, [885])]

# Hybrid template in a local frame anchored on the ring-1 bridge midpoint: u points away from the tree
# centre, v towards the next sector. Plain nodes: local id -> (u, v). Rings: first small -> (ring centre u, v, curl).
HYBRID_NODES = {
	1: (150, 0), 2: (300, 0), 3: (200, -640), 4: (200, 640), 5: (480, 0), 12: (760, 0),
	25: (900, -560), 26: (900, 560), 21: (1080, -680), 22: (1220, -760), 23: (1080, 680), 24: (1220, 760),
	27: (930, 170), 28: (1060, 260),
}
HYBRID_RINGS = {6: (480, -420, -1), 7: (480, 420, 1), 17: (1000, -220, 1)}
# Each hybrid's outer extension (trail 70, an HP wheel at 71, a conditional mastery at 75), mirrored (v -> -v) on every other hybrid.
HYBRID_EXTRA_NODES = {70: (1180, -40)}
HYBRID_EXTRA_RINGS = {71: (1400, -200, -1), 75: (1420, 200, 1)}
HYBRID_ACTIVE_POD = {25: [(1000, -840), (1050, -990)], 26: [(1000, 840), (1050, 990)]}  # approach 29 / active 30, off whichever Veteran's Path they hang from

# The space between two arms, inside ring 1, belongs to that wedge's hybrid (local ids 51+). Each wedge is laid
# out differently on purpose. Positions are (radius, degrees past the wedge's first spine); rings are
# first small -> (centre radius, degrees, curl).
WEDGE_NODES = {
	0: {51: (1820, 30), 56: (1200, 20), 61: (1230, 42), 62: (1440, 44)},
	1: {51: (1790, 20), 56: (1180, 40), 61: (1190, 24), 62: (1340, 20), 63: (1500, 17)},
	2: {51: (1800, 40), 56: (930, 30), 61: (1240, 19), 62: (1450, 17)},
	3: {51: (1210, 30), 56: (1800, 17), 61: (1820, 44), 62: (2040, 43)},
	4: {51: (1800, 33), 56: (930, 32), 61: (1240, 19.5), 62: (1390, 18.5), 63: (1530, 19.5)},
	5: {51: (1200, 42), 56: (1800, 27), 61: (1830, 45), 62: (2040, 47)},
}
for _hi, _p in {0: (925, 35), 1: (925, 25), 2: (1820, 21), 3: (925, 35), 4: (1820, 21), 5: (925, 25)}.items():
	WEDGE_NODES[_hi][64] = _p  # the third structure's entry
WEDGE_RINGS = {
	0: {52: (2010, 24, 1), 57: (1390, 27, -1)},
	1: {52: (1965, 29, 1), 57: (1370, 33, -1)},
	2: {52: (1990, 34, -1), 57: (760, 28, 1)},
	3: {52: (1400, 30, 1), 57: (1965, 24, -1)},
	4: {52: (1985, 38, -1), 57: (765, 35, 1)},
	5: {52: (1385, 35, 1), 57: (1985, 22, 1)},
}
for _hi, _p in {0: (765, 30, 1), 1: (765, 30, -1), 2: (2000, 19.5, 1), 3: (765, 30, -1), 4: (2000, 19.5, -1), 5: (765, 30, 1)}.items():
	WEDGE_RINGS[_hi][65] = _p  # the third structure: an HP wheel or a conditional mastery

# BEGIN REGIONS
# The six regions beyond the rim (region i between sectors i and i+1), each shaped differently: a fan, a winding
# road and a ladder, the other three mirrored. Positions are (radius, degrees past the region's first spine);
# rings are first small -> (centre radius, degrees, curl); REGION_ARCS lists the nodes that sit on a circle round
# the tree centre (their links are drawn as arcs).
REGION_NODES = {
	0: {1: (4180, 10), 2: (4180, 30), 3: (4180, 50), 4: (4300, 20), 5: (4300, 40), 6: (4430, 30), 7: (4790, 24), 8: (4790, 36), 40: (4400.0, 5.0), 41: (4600.0, 5.0), 42: (4800.0, 5.0), 43: (5000.0, 5.0), 44: (4980, 40.0), 45: (4980, 44.0), 46: (4980, 48.0), 47: (4980, 52.0), 50: (4640, 30), 51: (4830, 30), 52: (5030, 30), 60: (4205.6, 13.39), 61: (4245.7, 16.73), 62: (4285.9, 7.44), 63: (4205.6, 46.61), 64: (4245.7, 43.27), 65: (4320.0, 22.55), 66: (4348.4, 25.07), 67: (4385.1, 27.56), 68: (4320.0, 37.45), 69: (4348.4, 34.93), 70: (4385.1, 32.44), 71: (4708.5, 26.95), 72: (4882.0, 38.04), 73: (4708.5, 33.05)},
	1: {1: (4170, 10), 2: (4330, 13.4), 3: (4480, 16.9), 4: (4600, 20.5), 5: (4700, 24.25), 6: (4790, 28), 7: (4870, 31.75), 8: (4170, 40), 9: (4560, 33.1), 10: (4380, 37), 40: (4300.0, 6.25), 41: (4499.5, 5.58), 42: (4699.5, 4.96), 43: (4900.0, 4.4), 44: (4380.0, 43.75), 45: (4579.0, 44.66), 46: (4779.0, 45.49), 47: (4980.0, 46.25), 50: (4930, 35), 51: (5000, 38.1), 52: (5080, 41.25), 60: (4670.4, 30.49), 61: (4272.7, 41.92), 62: (4467.4, 35.01)},
	2: {1: (4170, 15), 2: (4330, 15), 3: (4170, 45), 4: (4330, 45), 5: (4480, 15), 6: (4480, 22.5), 7: (4480, 30), 8: (4480, 37.5), 9: (4480, 45), 10: (4990, 25.5), 40: (4660.0, 13.0), 41: (4805.6, 12.12), 42: (4952.3, 11.28), 43: (5100.0, 10.5), 44: (4660.0, 47.0), 45: (4805.6, 47.88), 46: (4952.3, 48.72), 47: (5100.0, 49.5), 50: (4690, 30), 51: (4880, 30), 52: (5080, 30), 60: (5004.5, 23.58), 61: (4931.2, 27.72)},
	3: {1: (4170, 50), 2: (4330, 46.6), 3: (4480, 43.1), 4: (4600, 39.5), 5: (4700, 35.75), 6: (4790, 32), 7: (4870, 28.25), 8: (4170, 20), 9: (4560, 26.9), 10: (4380, 23), 40: (4300.0, 53.75), 41: (4499.5, 54.42), 42: (4699.5, 55.04), 43: (4900.0, 55.6), 44: (4380.0, 16.25), 45: (4579.0, 15.34), 46: (4779.0, 14.51), 47: (4980.0, 13.75), 50: (4930, 25), 51: (5000, 21.9), 52: (5080, 18.75), 60: (4670.4, 29.51), 61: (4272.7, 18.08), 62: (4467.4, 24.99)},
	4: {1: (4180, 50), 2: (4180, 30), 3: (4180, 10), 4: (4300, 40), 5: (4300, 20), 6: (4430, 30), 7: (4790, 36), 8: (4790, 24), 40: (4400.0, 55.0), 41: (4600.0, 55.0), 42: (4800.0, 55.0), 43: (5000.0, 55.0), 44: (4980, 20.0), 45: (4980, 16.0), 46: (4980, 12.0), 47: (4980, 8.0), 50: (4640, 30), 51: (4830, 30), 52: (5030, 30), 60: (4205.6, 46.61), 61: (4245.7, 43.27), 62: (4285.9, 52.56), 63: (4205.6, 13.39), 64: (4245.7, 16.73), 65: (4320.0, 37.45), 66: (4348.4, 34.93), 67: (4385.1, 32.44), 68: (4320.0, 22.55), 69: (4348.4, 25.07), 70: (4385.1, 27.56), 71: (4708.5, 33.05), 72: (4882.0, 21.96), 73: (4708.5, 26.95)},
	5: {1: (4170, 45), 2: (4330, 45), 3: (4170, 15), 4: (4330, 15), 5: (4480, 45), 6: (4480, 37.5), 7: (4480, 30), 8: (4480, 22.5), 9: (4480, 15), 10: (4990, 34.5), 40: (4660.0, 47.0), 41: (4805.6, 47.88), 42: (4952.3, 48.72), 43: (5100.0, 49.5), 44: (4660.0, 13.0), 45: (4805.6, 12.12), 46: (4952.3, 11.28), 47: (5100.0, 10.5), 50: (4690, 30), 51: (4880, 30), 52: (5080, 30), 60: (5004.5, 36.42), 61: (4931.2, 32.28)},
}
REGION_RINGS = {
	0: {20: (4470, 14, -1), 24: (4540, 22, 1), 28: (4540, 38, -1), 32: (4470, 46, 1), 36: (4980, 20, 1)},
	1: {20: (4593, 12.2, 1), 24: (4217, 18, -1), 28: (4863, 19.35, 1), 32: (4437, 25.4, -1), 36: (5053, 26.85, 1)},
	2: {20: (4250, 24, 1), 24: (4720, 21, -1), 28: (4720, 39, 1), 32: (4250, 36, -1), 36: (5040, 20.5, 1)},
	3: {20: (4593, 47.8, -1), 24: (4217, 42, 1), 28: (4863, 40.65, -1), 32: (4437, 34.6, 1), 36: (5053, 33.15, -1)},
	4: {20: (4470, 46, 1), 24: (4540, 38, -1), 28: (4540, 22, 1), 32: (4470, 14, -1), 36: (4980, 40, -1)},
	5: {20: (4250, 36, -1), 24: (4720, 39, 1), 28: (4720, 21, -1), 32: (4250, 24, 1), 36: (5040, 39.5, -1)},
}
REGION_ARCS = {
	0: [44, 45, 46, 47],
	1: [],
	2: [5, 6, 7, 8, 9],
	3: [],
	4: [44, 45, 46, 47],
	5: [5, 6, 7, 8, 9],
}
# END REGIONS

# ------------------------------------------------------------------ XML in / out
class Node:
	def __init__(self, attrs, parents):
		self.a = dict(attrs)
		self.parents = parents
		self.pos = None
		self.orbit = None

	@property
	def id(self):
		return int(self.a["id"])

	@property
	def type(self):
		return self.a["type"]

	@property
	def name(self):
		return self.a["name"]


def load():
	nodes, order, headers = {}, {}, {}
	for path in sorted(glob.glob(os.path.join(DATA_DIR, "*.xml"))):
		fn = os.path.basename(path)
		headers[fn] = open(path, encoding="utf-8").read().split("\n")[1]
		order[fn] = []
		for el in ET.parse(path).getroot().findall("node"):
			node = Node(el.attrib, [int(r.get("nodeId")) for r in el.findall("requires")])
			nodes[node.id] = node
			order[fn].append(node.id)
	return nodes, order, headers


def coord(value):
	text = f"{value:.1f}"
	return "0.0" if text == "-0.0" else text


def save(nodes, order, headers):
	for fn, ids in order.items():
		lines = ['<?xml version="1.0" encoding="UTF-8"?>', headers[fn],
			'<list xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="../xsd/passivetree.xsd">']
		for i in ids:
			n = nodes[i]
			attrs = {}
			for k, v in n.a.items():
				if k in ("orbitX", "orbitY"):
					continue
				if k == "x":
					v = coord(n.pos[0])
				elif k == "y":
					v = coord(n.pos[1])
				attrs[k] = v
				if (k == "y") and n.orbit:
					attrs["orbitX"] = coord(n.orbit[0])
					attrs["orbitY"] = coord(n.orbit[1])
			text = " ".join(f"{k}={quoteattr(v)}" for k, v in attrs.items())
			if not n.parents:
				lines.append(f"\t<node {text} />")
			else:
				lines.append(f"\t<node {text}>")
				lines += [f'\t\t<requires nodeId="{p}" />' for p in n.parents]
				lines.append("\t</node>")
		lines.append("</list>")
		with open(os.path.join(DATA_DIR, fn), "w", encoding="utf-8", newline="\n") as fh:
			fh.write("\n".join(lines))


# ------------------------------------------------------------------ geometry
def polar(r, deg):
	t = math.radians(deg)
	return (r * math.cos(t), r * math.sin(t))


def add(p, q, k=1.0):
	return (p[0] + (q[0] * k), p[1] + (q[1] * k))


def norm(v):
	d = math.hypot(v[0], v[1])
	return (v[0] / d, v[1] / d)


def dist(p, q):
	return math.hypot(p[0] - q[0], p[1] - q[1])


def on_circle(center, radius, deg):
	return add(center, polar(radius, deg))


def angle_of(center, p):
	return math.degrees(math.atan2(p[1] - center[1], p[0] - center[0]))


def frame(deg):
	"""Radial (away from the tree centre) and tangential (towards larger angles) unit vectors at an angle."""
	t = math.radians(deg)
	return (math.cos(t), math.sin(t)), (-math.sin(t), math.cos(t))


def edge_points(a, b):
	"""A link as a polyline: an arc when both ends sit on the same circle, else a straight segment."""
	pa, pb = a.pos, b.pos
	if a.orbit and (a.orbit == b.orbit) and (abs(dist(a.orbit, pa) - dist(b.orbit, pb)) < 1):
		c, r = a.orbit, dist(a.orbit, pa)
		a0 = math.atan2(pa[1] - c[1], pa[0] - c[0])
		d = math.atan2(pb[1] - c[1], pb[0] - c[0]) - a0
		d = (d + math.pi) % (2 * math.pi) - math.pi
		steps = max(2, int(abs(d) * r / 40))
		return [(c[0] + (r * math.cos(a0 + ((d * k) / steps))), c[1] + (r * math.sin(a0 + ((d * k) / steps)))) for k in range(steps + 1)]
	return [pa, pb]


def segments_cross(p1, p2, p3, p4):
	def ccw(a, b, c):
		return ((c[1] - a[1]) * (b[0] - a[0])) - ((b[1] - a[1]) * (c[0] - a[0]))
	d1, d2, d3, d4 = ccw(p3, p4, p1), ccw(p3, p4, p2), ccw(p1, p2, p3), ccw(p1, p2, p4)
	return ((d1 > 0) != (d2 > 0)) and ((d3 > 0) != (d4 > 0))


def point_segment_distance(p, a, b):
	vx, vy = b[0] - a[0], b[1] - a[1]
	l2 = (vx * vx) + (vy * vy)
	t = 0 if l2 == 0 else max(0, min(1, (((p[0] - a[0]) * vx) + ((p[1] - a[1]) * vy)) / l2))
	return math.hypot(p[0] - (a[0] + (t * vx)), p[1] - (a[1] + (t * vy)))


# ------------------------------------------------------------------ quality checks
MIN_NODE_GAP = 60
MIN_EDGE_CLEARANCE = 35


def problems(nodes, adj, only=None):
	"""Counts crossings and crowding among placed nodes. With `only`, just the links/nodes touching those ids."""
	placed = {i for i, n in nodes.items() if n.pos}
	edges = {(min(i, j), max(i, j)) for i in placed for j in adj[i] if j in placed}
	polys = {e: edge_points(nodes[e[0]], nodes[e[1]]) for e in edges}
	mine = edges if only is None else {e for e in edges if (e[0] in only) or (e[1] in only)}
	crossings, crowded = [], []
	for e in mine:
		for f in edges:
			if (f == e) or ((only is not None) and (f in mine) and (f < e)) or ((only is None) and (f < e)) or (len(set(e) | set(f)) < 4):
				continue
			pe, pf = polys[e], polys[f]
			if (max(p[0] for p in pe) < min(p[0] for p in pf)) or (max(p[0] for p in pf) < min(p[0] for p in pe)) or (max(p[1] for p in pe) < min(p[1] for p in pf)) or (max(p[1] for p in pf) < min(p[1] for p in pe)):
				continue
			if any(segments_cross(a, b, c, d) for a, b in zip(pe, pe[1:]) for c, d in zip(pf, pf[1:])):
				crossings.append((e, f))
	check_nodes = placed if only is None else (only & placed)
	for i in check_nodes:
		p = nodes[i].pos
		for j in placed:
			if (j != i) and ((only is None and j < i) or (only is not None and (j not in only or j < i))) and (dist(p, nodes[j].pos) < MIN_NODE_GAP):
				crowded.append(("nodes", i, j))
		for e in edges:
			if i in e:
				continue
			pe = polys[e]
			if (min(q[0] for q in pe) - MIN_EDGE_CLEARANCE > p[0]) or (max(q[0] for q in pe) + MIN_EDGE_CLEARANCE < p[0]) or (min(q[1] for q in pe) - MIN_EDGE_CLEARANCE > p[1]) or (max(q[1] for q in pe) + MIN_EDGE_CLEARANCE < p[1]):
				continue
			if min(point_segment_distance(p, a, b) for a, b in zip(pe, pe[1:])) < MIN_EDGE_CLEARANCE:
				crowded.append(("edge", i, e))
	if only is not None:
		# links touching `only` must also keep clear of every other placed node
		for e in mine:
			pe = polys[e]
			for j in placed - set(e) - only:
				p = nodes[j].pos
				if (min(q[0] for q in pe) - MIN_EDGE_CLEARANCE > p[0]) or (max(q[0] for q in pe) + MIN_EDGE_CLEARANCE < p[0]) or (min(q[1] for q in pe) - MIN_EDGE_CLEARANCE > p[1]) or (max(q[1] for q in pe) + MIN_EDGE_CLEARANCE < p[1]):
					continue
				if min(point_segment_distance(p, a, b) for a, b in zip(pe, pe[1:])) < MIN_EDGE_CLEARANCE:
					crowded.append(("edge", j, e))
	return crossings, crowded


# ------------------------------------------------------------------ layout
def group_of(i):
	"""Which block of ids a node belongs to: a main sector (1000s), a bridge, hybrid or rim stretch (per 100), the Nexus (9000s), a region (per 1000 from 40000)."""
	if i >= REGION_BASE:
		return i // 1000
	return i // 100 if i >= 20000 else i // 1000


def chain_from(nodes, adj, first, came_from):
	"""Follows a path from `first` (away from `came_from`) while there is exactly one way on inside the same block, e.g. s1 > s2 > s3 > notable or approach > keystone."""
	path, prev, cur = [first], came_from, first
	while True:
		nxt = [j for j in adj[cur] if (j != prev) and (group_of(j) == group_of(first)) and (nodes[j].type != "START")]
		if len(nxt) != 1:
			return path
		prev, cur = cur, nxt[0]
		path.append(cur)


def ring_chain(nodes, adj, first):
	"""The ring that starts at `first`: first > s2 > s3 > notable, found whichever neighbour leads there."""
	for start in adj[first]:
		path = [first] + chain_from(nodes, adj, start, first)
		if (len(path) == 4) and (nodes[path[-1]].type == "NOTABLE"):
			return path
	raise AssertionError(f"no ring from {first}")


def place_ring(nodes, chain, center, entry_point, curl):
	"""Puts a chain on a ring: the first node faces `entry_point`, the rest follow round the ring."""
	start = angle_of(center, entry_point)
	for k, i in enumerate(chain):
		nodes[i].pos = on_circle(center, RING_RADIUS, start + (curl * RING_STEP * k))
		nodes[i].orbit = center


def walk(nodes, adj, start, group, first=None, keep=lambda j: True):
	"""The run of nodes of one id block leaving `start` (through `first` if given), in order, e.g. a bridge from its junction."""
	path, prev = [], start
	cur = first if first is not None else [j for j in adj[start] if group_of(j) == group][0]
	while True:
		path.append(cur)
		nxt = [j for j in adj[cur] if (j != prev) and (group_of(j) == group) and (j not in path) and keep(j)]
		if not nxt:
			return path
		prev, cur = cur, nxt[0]


def place_roads(nodes, adj, touching=None):
	"""Spaces the road nodes that lengthen a link evenly along it, once both ends are placed. Returns the ids placed."""
	placed, seen = set(), set()
	for i in sorted(nodes):
		if (i not in ROAD_IDS) or (i in seen):
			continue
		chain = [i]
		for side in (0, 1):
			end = chain[-1] if side else chain[0]
			prev, cur = end, [j for j in adj[end] if j not in chain][0]
			while cur in ROAD_IDS:
				chain = (chain + [cur]) if side else ([cur] + chain)
				prev, cur = cur, [j for j in adj[cur] if (j != prev) and (j not in chain)][0]
			chain = (chain + [cur]) if side else ([cur] + chain)
		seen.update(chain[1:-1])
		a, b = nodes[chain[0]].pos, nodes[chain[-1]].pos
		if (a is None) or (b is None) or ((touching is not None) and not ({chain[0], chain[-1]} & touching)):
			continue
		for k, j in enumerate(chain[1:-1], 1):
			nodes[j].pos = add(a, (b[0] - a[0], b[1] - a[1]), k / (len(chain) - 1))
			nodes[j].orbit = None
			placed.add(j)
	return placed


def place_straight(nodes, chain, origin, direction, first, step):
	for k, i in enumerate(chain):
		nodes[i].pos = add(origin, direction, first + (step * k))
		nodes[i].orbit = None


def layout(nodes):
	adj = {i: set() for i in nodes}
	for i, n in nodes.items():
		for p in n.parents:
			adj[i].add(p)
			adj[p].add(i)
	for n in nodes.values():
		n.pos, n.orbit = None, None
	centre = (0.0, 0.0)

	# Nexus: the filament ring around the centre, pillar conduits and master spokes running inward.
	for k in range(12):
		nodes[9001 + k].pos = polar(R_NEXUS, 30 * k)
		nodes[9001 + k].orbit = centre
	nodes[9019].pos = centre
	for gate in range(9002, 9013, 2):
		ang = 30 * (gate - 9001)
		path = chain_from(nodes, adj, [j for j in adj[gate] if j >= 9020][0], gate)
		for i, r in zip(path, (430, 340, 250, 165)):
			nodes[i].pos = polar(r, ang)
	for gate in range(9001, 9013, 2):
		ang = 30 * (gate - 9001)
		spoke = [j for j in adj[gate] if j >= 9020][0]
		for i, r in zip(chain_from(nodes, adj, spoke, gate)[:2], (360, 200)):
			nodes[i].pos = polar(r, ang)

	# Sector spines, junctions and the bridges between them.
	for si, base in enumerate(SECTOR_BASES):
		ang = si * SPINE_DEG
		nodes[base].pos = polar(R_START, ang)
		nodes[base + 79].pos = polar(R_MASTER, ang)
		for local, r in R_JUNCTION.items():
			nodes[base + local].pos = polar(r, ang)
			nodes[base + local].orbit = centre
		for a, b, radii in SPINE_ROADS:
			path = chain_between(nodes, adj, base + a, base + b)
			assert len(path) == len(radii), (base, a, b, path)
			for i, r in zip(path, radii):
				nodes[i].pos = polar(r, ang)
		# a bridge's nodes are spread evenly along its arc to the next sector's junction
		for ring, local in ((1, 16), (2, 39), (3, 62)):
			path = walk(nodes, adj, base + local, (20000 + (si * 1000) + (ring * 100)) // 100)
			for k, i in enumerate(path, 1):
				nodes[i].pos = polar(R_JUNCTION[local], ang + ((SPINE_DEG * k) / (len(path) + 1)))
				nodes[i].orbit = centre

	place_sector_paths(nodes, adj)

	# The Outer Rim: a spur from each START to its gate, then an arc road to the next gate.
	for si, base in enumerate(SECTOR_BASES):
		ang = si * SPINE_DEG
		gate = RIM_BASE + (si * 100)
		nodes[gate].pos = polar(R_RIM, ang)
		nodes[gate].orbit = centre
		for i, r in zip(range(gate + 1, gate + 4), R_RIM_SPUR):
			nodes[i].pos = polar(r, ang)
		road = walk(nodes, adj, gate, gate // 100, gate + 10, lambda j: j >= gate + 10)
		for k, i in enumerate(road, 1):
			nodes[i].pos = polar(R_RIM, ang + ((SPINE_DEG * k) / (len(road) + 1)))
			nodes[i].orbit = centre
	# The two side spurs out of each START (gate + 4.. and gate + 7..), landing on the rim road either side of the gate.
	for si, base in enumerate(SECTOR_BASES):
		gate = RIM_BASE + (si * 100)
		for first in (gate + 4, gate + 7):
			chain = [first + k for k in range(3)]
			land = [j for j in adj[chain[-1]] if j not in chain][0]
			a, b = nodes[base].pos, nodes[land].pos
			for k, i in enumerate(chain, 1):
				nodes[i].pos = add(a, (b[0] - a[0], b[1] - a[1]), k / 4)

	# The regions beyond the rim.
	for ri in range(6):
		start, base = ri * SPINE_DEG, REGION_BASE + (ri * 1000)
		for local, (r, deg) in REGION_NODES[ri].items():
			nodes[base + local].pos = polar(r, start + deg)
			nodes[base + local].orbit = centre if local in REGION_ARCS[ri] else None
		for first, (r, deg, curl) in REGION_RINGS[ri].items():
			ring = ring_chain(nodes, adj, base + first)
			entry = [nodes[j].pos for j in adj[base + first] if (nodes[j].pos is not None) and (j not in ring)]
			place_ring(nodes, ring, polar(r, start + deg), (sum(p[0] for p in entry) / len(entry), sum(p[1] for p in entry) / len(entry)), curl)

	# Hybrids: one template, rotated into each wedge.
	for hi, base in enumerate(HYBRID_BASES):
		ang = (hi * SPINE_DEG) + 30
		anchor = nodes[20000 + (hi * 1000) + 102].pos
		u, v = frame(ang)
		at = lambda p: add(add(anchor, u, p[0]), v, p[1])
		for local, p in HYBRID_NODES.items():
			nodes[base + local].pos = at(p)
		active_from = [j - base for j in adj[base + 29] if base < j <= base + 28][0]
		for local, p in zip((29, 30), HYBRID_ACTIVE_POD[active_from]):
			nodes[base + local].pos = at(p)
		flip = -1 if (hi % 2) else 1
		for local, (pu, pv) in HYBRID_EXTRA_NODES.items():
			nodes[base + local].pos = at((pu, pv * flip))
		rings = dict(HYBRID_RINGS)
		rings.update({first: (cu, cv * flip, curl * flip) for first, (cu, cv, curl) in HYBRID_EXTRA_RINGS.items()})
		for first, (cu, cv, curl) in rings.items():
			ring = ring_chain(nodes, adj, base + first)
			entry = [nodes[j].pos for j in adj[base + first] if (nodes[j].pos is not None) and (j not in ring)]
			place_ring(nodes, ring, at((cu, cv)), (sum(p[0] for p in entry) / len(entry), sum(p[1] for p in entry) / len(entry)), curl)
	place_roads(nodes, adj)

	# The in-between wedges inside ring 1.
	for hi, base in enumerate(HYBRID_BASES):
		start = hi * SPINE_DEG
		for local, (r, deg) in WEDGE_NODES[hi].items():
			nodes[base + local].pos = polar(r, start + deg)
		for first, (r, deg, curl) in WEDGE_RINGS[hi].items():
			ring = ring_chain(nodes, adj, base + first)
			entry = [nodes[j].pos for j in adj[base + first] if (nodes[j].pos is not None) and (j not in ring)]
			place_ring(nodes, ring, polar(r, start + deg), entry[0], curl)
	
	# Junction attachments: each slot's angle, distance and curl is chosen once for all six sectors
	# (so they stay identical by rotation), picking the cleanest candidate.
	for junction, slots in JUNCTION_SLOTS.items():
		for first, io, side in slots:
			best = None
			for alpha, d, curl in itertools.product((45, 35, 55, 25, 65), (240, 210, 270, 300), (1, -1)):
				placed_ids = set()
				for si, base in enumerate(SECTOR_BASES):
					placed_ids |= place_attachment(nodes, adj, base, junction, first, io, side, alpha, d, curl)
				placed_ids |= place_roads(nodes, adj, placed_ids)
				crossings, crowded = problems(nodes, adj, placed_ids)
				score = ((2 * len(crowded)) + len(crossings), abs(alpha - 45) + (abs(d - 240) / 10) + (0 if curl == 1 else 0.5))
				if (best is None) or (score < best[0]):
					best = (score, alpha, d, curl)
			_, alpha, d, curl = best
			if "--verbose" in sys.argv:
				print(f"junction {junction} slot {first}: angle {alpha}, distance {d}, curl {curl}, score {best[0]}")
			for si, base in enumerate(SECTOR_BASES):
				place_attachment(nodes, adj, base, junction, first, io, side, alpha, d, curl)
			place_roads(nodes, adj)

	missing = [i for i, n in nodes.items() if n.pos is None]
	assert not missing, f"nodes without a position: {missing}"
	return adj


def place_sector_paths(nodes, adj):
	"""The START road's three branches and the three lanes of every sector (see SECTOR_BRAID and SECTOR_LANES)."""
	for si, base in enumerate(SECTOR_BASES):
		u, v = frame(si * SPINE_DEG)
		at = lambda p: add((u[0] * p[0], u[1] * p[0]), v, p[1])
		for local, p in SECTOR_BRAID.items():
			nodes[base + local].pos = at(p)
			nodes[base + local].orbit = None
		for local, (side, curl) in SECTOR_BRAID_RINGS.items():
			entry = base + local
			ring = ring_chain(nodes, adj, entry)
			p = SECTOR_BRAID[local]
			place_ring(nodes, ring, at((p[0] + (RING_RADIUS * 0.7071), p[1] + (side * RING_RADIUS * 0.7071))), at(p), curl)
		for (junction, first), offsets in SECTOR_LANES.items():
			ju = nodes[base + junction].pos
			s1 = base + first
			notable = [j for j in adj[s1] if nodes[j].type == "NOTABLE"][0]
			s2 = [j for j in adj[notable] if (j != s1) and any(nodes[k].a["name"] == "Crossroads" for k in adj[j])][0]
			spur = [j for j in adj[notable] if j not in (s1, s2)][0]
			approach = [j for j in adj[s1] if nodes[j].a["name"] == "Approach"][0]
			pod = [j for j in adj[approach] if j != s1][0]
			r0 = math.hypot(ju[0], ju[1])
			for i, (du, dv) in zip((s1, notable, s2, spur, approach, pod), offsets):
				nodes[i].pos = at((r0 + du, dv))
				nodes[i].orbit = None
			# the lane itself curves round the plain road: its three nodes share one circle
			centre = circumcentre(nodes[s1].pos, nodes[notable].pos, nodes[s2].pos)
			for i in (s1, notable, s2):
				nodes[i].orbit = centre


def circumcentre(a, b, c):
	d = 2 * ((a[0] * (b[1] - c[1])) + (b[0] * (c[1] - a[1])) + (c[0] * (a[1] - b[1])))
	ux = (((a[0] ** 2) + (a[1] ** 2)) * (b[1] - c[1]) + ((b[0] ** 2) + (b[1] ** 2)) * (c[1] - a[1]) + ((c[0] ** 2) + (c[1] ** 2)) * (a[1] - b[1])) / d
	uy = (((a[0] ** 2) + (a[1] ** 2)) * (c[0] - b[0]) + ((b[0] ** 2) + (b[1] ** 2)) * (a[0] - c[0]) + ((c[0] ** 2) + (c[1] ** 2)) * (b[0] - a[0])) / d
	return (ux, uy)


def chain_between(nodes, adj, a, b):
	"""The in-between nodes of the spine road from a to b."""
	for start in adj[a]:
		path, prev, cur = [], a, start
		while (cur != b) and (nodes[cur].name == "Pathway") and (len(path) < 5):
			path.append(cur)
			nxt = [j for j in adj[cur] if (j != prev) and (j < 20000)]
			if len(nxt) != 1:
				break
			prev, cur = cur, nxt[0]
		if cur == b:
			return path
	raise AssertionError(f"no spine road {a} -> {b}")


def place_attachment(nodes, adj, base, junction, first, io, side, alpha, d, curl):
	si = SECTOR_BASES.index(base)
	jpos = nodes[base + junction].pos
	u, v = frame(si * SPINE_DEG)
	direction = norm(add((u[0] * io * math.cos(math.radians(alpha)), u[1] * io * math.cos(math.radians(alpha))), v, side * math.sin(math.radians(alpha))))
	chain = chain_from(nodes, adj, base + first, base + junction)
	if nodes[chain[-1]].type == "NOTABLE":
		place_ring(nodes, chain, add(jpos, direction, d), jpos, curl * side * io)
	else:
		# pods (approach > keystone / active skill) and function nodes: a short straight road
		place_straight(nodes, chain, jpos, direction, 120 + ((d - 240) / 2), 120)
	return set(chain)


def main():
	nodes, order, headers = load()
	adj = layout(nodes)
	crossings, crowded = problems(nodes, adj)
	for c in crossings:
		print("CROSSING", c, file=sys.stderr)
	for c in crowded:
		print("CROWDED", c, file=sys.stderr)
	if (crossings or crowded) and ("--force" not in sys.argv):
		sys.exit(f"layout is not clean: {len(crossings)} crossing(s), {len(crowded)} crowded spot(s) - files left untouched")
	save(nodes, order, headers)
	print(f"Laid out {len(nodes)} nodes.")


if __name__ == "__main__":
	main()
