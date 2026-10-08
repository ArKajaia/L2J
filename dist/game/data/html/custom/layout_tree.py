#!/usr/bin/env python3
"""
Lays out the passive tree in data/passivetree/*.xml, Path of Exile style:
 - every notable cluster is a ring: the path enters the ring at its first small and the three smalls
   curve around it to the notable (those links are drawn as arcs),
 - the six sector spines are straight roads from the START to the archetype MASTER, with more than one way along
   every stretch (SECTOR_BRAID / place_sector_paths): three roads out of START, a wheel round the first stretch,
   then on each of the next two a lane through a notable on one side and a loop joining two clusters on the other,
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
R_PILLAR = 230  # the inner ring the six Nexus pillars sit on
NEXUS_TWIST = 40 / R_NEXUS  # degrees per unit of radius the Nexus arms curl by on their way in
R_MASTER = 720
R_JUNCTION = {1: 2850, 16: 2250, 39: 1650, 62: 1050}  # local id -> radius: J0 (outer) .. J3 (inner)
R_START = 3300

R_RIM = 4000  # the Outer Rim road
R_RIM_SPUR = [3475, 3650, 3825]  # the spur from each START out to its rim gate
RIM_BASE = 39000  # rim ids: 39000 + sector * 100 (+0 gate, +1.. spur, +10.. arc road towards the next sector)
BRANCH_STEP = 200  # spacing of the nodes on a branch off the outer edge (see place_branches)
REGION_BASE = 40000  # region ids: 40000 + region * 1000 + local id, region i sitting beyond the rim between sectors i and i+1
ROAD_IDS = range(33000, 34000)  # "Lifeblood Trail" nodes lengthening a hybrid's long links: spaced evenly along the link they split

RING_RADIUS = 105  # radius of a notable cluster's ring
RING_STEP = 70  # degrees between consecutive nodes on a ring

# What hangs off each junction and where: (local id of the first node, outward(+1)/inward(-1), side(+1 = towards the next sector, -1 = towards the previous one))
# (Everything else in a sector is laid out by place_sector_paths.)
JUNCTION_SLOTS = {
	62: [(77, -1, -1), (81, -1, 1)],
}
# Sector paths, in the sector's own frame: (u, v) with u the distance from the tree centre along the spine and v
# sideways, positive towards the next sector.
# START road: the middle road (2, 3) and two side roads (90 > 4 > 91, 92 > 9 > 93) through a cluster's entry small.
SECTOR_BRAID = {90: (3200, -165), 4: (3075, -230), 91: (2950, -165), 92: (3200, 165), 9: (3075, 230), 93: (2950, 165)}
SECTOR_BRAID_RINGS = {4: (-1, 1), 9: (1, -1)}  # entry small -> (side, curl)
# J0 > J1 wheel: the two clusters whose whole ring is a way round (entry small -> bulge), and the active skill pod
# inside it (approach, skill) relative to J0.
WHEEL_ARCS = {19: -330, 25: 330}
WHEEL_POD = [(-110, 90), (-235, 150)]
# J1 > J2 and J2 > J3: (from junction, lane entry small, lane bulge, loop's first ring, pod approach).
# The lane's spare small sits under its notable; the pod (approach, then keystone / skill) between lane and road.
# The loop is a pocket on the -1 side: a half circle (centre loop depth off the road, loop radius) opening towards it.
# Last Crossroads > Nexus, in the sectors that have one (local ids 94..103): a large HP / MP cluster shaped as an eye,
# two arcs (small > small > NOTABLE > small > small) either side of the road through the MASTER.
EYE_FIRST, EYE_BULGE = 94, 190
EYE_PODS = {77: (-100, -50), 78: (-210, -80), 81: (-150, 60)}  # where J3's pods sit inside the eye, relative to J3
STRETCHES = [(16, 48, 360, 31, 150, 230, 37), (39, 71, 320, 54, 120, 200, 60)]
LANE_SPARE_DROP = 130
STRETCH_POD = [(-250, 120), (-370, 120)]
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
	4: {52: (1985, 38, -1), 57: (765, 30, 1)},
	5: {52: (1385, 35, 1), 57: (1985, 22, 1)},
}
for _hi, _p in {0: (765, 30, 1), 1: (765, 30, -1), 2: (2000, 19.5, 1), 3: (765, 30, -1), 4: (2000, 19.5, -1), 5: (765, 30, 1)}.items():
	WEDGE_RINGS[_hi][65] = _p  # the third structure: an HP wheel or a conditional mastery

# Attribute shrines, straight out from each Rim Gate between the regions (ids SHRINE_BASE + sector * 100 + local):
# an eye from the gate to the shrine (arcs 1-5 and 6-10), the shrine 11, its capstone ring 12-15. Arcanist's shrine
# opens a second eye (16-20, 21-25) to the Summoner's Circle 26 and its ring 27-30 instead.
SHRINE_BASE = 47000
SHRINE_EYE_LENGTH, SHRINE_EYE_BULGE, SHRINE_RING_GAP = 600, 150, 215

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


def arc_orbit(a, b):
	"""The circle a link is drawn along: the orbit both ends share, or the (non-centre) orbit of one end when the other
	end sits exactly on that circle too (an arm of a wheel, eye or the Nexus running into its hub). None: straight."""
	if a.orbit and (a.orbit == b.orbit) and (abs(dist(a.orbit, a.pos) - dist(b.orbit, b.pos)) < 1):
		return a.orbit
	for x, y in ((a, b), (b, a)):
		if x.orbit and (x.orbit != (0.0, 0.0)) and (abs(dist(x.orbit, x.pos) - dist(x.orbit, y.pos)) < 1):
			return x.orbit
	return None


def sweep_arc(start, end, through, n):
	"""n points spread evenly along the circle arc from start to end that passes `through`, and the circle's centre."""
	(ax, ay), (bx, by), (cx, cy) = start, through, end
	d = 2 * ((ax * (by - cy)) + (bx * (cy - ay)) + (cx * (ay - by)))
	ux = ((((ax * ax) + (ay * ay)) * (by - cy)) + (((bx * bx) + (by * by)) * (cy - ay)) + (((cx * cx) + (cy * cy)) * (ay - by))) / d
	uy = ((((ax * ax) + (ay * ay)) * (cx - bx)) + (((bx * bx) + (by * by)) * (ax - cx)) + (((cx * cx) + (cy * cy)) * (bx - ax))) / d
	r = math.hypot(ax - ux, ay - uy)
	t0, tm, t1 = (math.atan2(p[1] - uy, p[0] - ux) for p in (start, through, end))
	half = (tm - t0 + math.pi) % (2 * math.pi) - math.pi
	full = (t1 - t0) % (2 * math.pi) if half > 0 else -((t0 - t1) % (2 * math.pi))
	return [(ux + (r * math.cos(t0 + ((full * k) / (n + 1)))), uy + (r * math.sin(t0 + ((full * k) / (n + 1))))) for k in range(1, n + 1)], (ux, uy)


def edge_points(a, b):
	"""A link as a polyline: an arc along arc_orbit(), else a straight segment."""
	pa, pb = a.pos, b.pos
	c = arc_orbit(a, b)
	if c:
		r = dist(c, pa)
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
	"""The ring that starts at `first`: first > s2 > s3 > notable, found whichever neighbour leads there (a keystone branch may go on past the notable)."""
	for start in adj[first]:
		path = [first] + chain_from(nodes, adj, start, first)
		if (len(path) >= 4) and (nodes[path[3]].type == "NOTABLE"):
			return path[:4]
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
	# Every arm inside the ring curls the same way, so the Nexus reads as a slow vortex: the angle of an arm grows by
	# NEXUS_TWIST degrees per unit of radius it runs inward. Each arm is one circle arc (its nodes share that orbit).
	twist = lambda r: NEXUS_TWIST * (R_NEXUS - r)
	for gate in range(9002, 9013, 2):  # pillar conduits: three smalls, then the pillar on the inner ring
		ang = 30 * (gate - 9001)
		path = chain_from(nodes, adj, [j for j in adj[gate] if j >= 9020][0], gate)
		end = polar(R_PILLAR, ang + twist(R_PILLAR))
		mid_r = (R_NEXUS + R_PILLAR) / 2
		points, orbit = sweep_arc(nodes[gate].pos, end, polar(mid_r, ang + twist(mid_r)), len(path) - 1)
		for i, p in zip(path, points + [end]):
			nodes[i].pos, nodes[i].orbit = p, orbit
	for gate in range(9001, 9013, 2):  # prismatic threads: two smalls, then the centre
		ang = 30 * (gate - 9001)
		spoke = [j for j in adj[gate] if j >= 9020][0]
		path = chain_from(nodes, adj, spoke, gate)[:2]
		points, orbit = sweep_arc(nodes[gate].pos, centre, polar(R_NEXUS / 2, ang + twist(R_NEXUS / 2)), len(path))
		for i, p in zip(path, points):
			nodes[i].pos, nodes[i].orbit = p, orbit

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

	# Attribute shrines beyond the rim.
	for si in range(6):
		base = SHRINE_BASE + (si * 100)
		u, v = frame(si * SPINE_DEG)
		at = lambda p: add((u[0] * p[0], u[1] * p[0]), v, p[1])
		hub_r = R_RIM
		for first_arc, hub_local, ring_local in ((1, 11, 12), (16, 26, 27)):
			if (base + hub_local) not in nodes:
				continue
			for k, side in enumerate((-1, 1)):
				points, orbit = arc_positions(hub_r, hub_r + SHRINE_EYE_LENGTH, side * SHRINE_EYE_BULGE, 5)
				for i, p in zip(range(base + first_arc + (5 * k), base + first_arc + (5 * k) + 5), points):
					nodes[i].pos, nodes[i].orbit = at(p), at(orbit)
			hub_r += SHRINE_EYE_LENGTH
			nodes[base + hub_local].pos = at((hub_r, 0))
			if (base + ring_local) in nodes:
				ring = [base + ring_local + j for j in range(4)]
				place_ring(nodes, ring, at((hub_r + SHRINE_RING_GAP, 0)), nodes[base + hub_local].pos, 1)

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

	# In the sectors with an eye, J3's pods sit inside it.
	for si, base in enumerate(SECTOR_BASES):
		if (base + EYE_FIRST) in nodes:
			u, v = frame(si * SPINE_DEG)
			r = math.hypot(*nodes[base + 62].pos)
			for local, (du, dv) in EYE_PODS.items():
				nodes[base + local].pos = add((u[0] * (r + du), u[1] * (r + du)), v, dv)
				nodes[base + local].orbit = None

	place_branches(nodes, adj)
	
	missing = [i for i, n in nodes.items() if n.pos is None]
	assert not missing, f"nodes without a position: {missing}"
	return adj


def place_branches(nodes, adj):
	"""Branches hung off the outer edge (the outer keystones: Nocturne, Riposte...): a run of nodes from a placed node, laid straight out from the tree centre, one every BRANCH_STEP."""
	placed = True
	while placed:
		placed = False
		for i in sorted(nodes):
			anchor = [j for j in adj[i] if nodes[j].pos is not None]
			if (nodes[i].pos is not None) or not anchor:
				continue
			a = nodes[anchor[0]].pos
			u, r = norm(a), math.hypot(*a)
			prev, cur, k = anchor[0], i, 1
			while cur is not None:
				nodes[cur].pos = (u[0] * (r + (BRANCH_STEP * k)), u[1] * (r + (BRANCH_STEP * k)))
				nodes[cur].orbit = None
				nxt = [j for j in adj[cur] if (j != prev) and (nodes[j].pos is None)]
				prev, cur, k = cur, (nxt[0] if nxt else None), k + 1
			placed = True


def arc_positions(ua, ub, bulge, n):
	"""n points spread evenly along the circle arc from (ua, 0) to (ub, 0) that swells `bulge` sideways at its middle, and the circle's centre."""
	mid, h = (ua + ub) / 2, abs(ua - ub) / 2
	c = ((bulge * bulge) - (h * h)) / (2 * bulge)
	radius = abs(bulge - c)
	ta = math.atan2(-c, ua - mid)
	half = (math.atan2(bulge - c, 0) - ta + math.pi) % (2 * math.pi) - math.pi
	return [(mid + (radius * math.cos(ta + ((2 * half * k) / (n + 1)))), c + (radius * math.sin(ta + ((2 * half * k) / (n + 1))))) for k in range(1, n + 1)], (mid, c)


def ring_from(nodes, adj, entry, junction):
	"""A cluster's nodes from its entry small (next to `junction`) along its own block: entry, second, notable, third..."""
	path = chain_from(nodes, adj, entry, junction)
	return [i for i in path if group_of(i) == group_of(entry) and nodes[i].a["name"] != "Crossroads"]


def place_sector_paths(nodes, adj):
	"""Everything that gives a sector its route choices (see SECTOR_BRAID, WHEEL_ARCS, STRETCHES and EYE_FIRST)."""
	for si, base in enumerate(SECTOR_BASES):
		u, v = frame(si * SPINE_DEG)
		at = lambda p: add((u[0] * p[0], u[1] * p[0]), v, p[1])
		radius_of = lambda local: math.hypot(*nodes[base + local].pos)

		def put(ids, points, orbit):
			for i, p in zip(ids, points):
				nodes[i].pos = at(p)
				nodes[i].orbit = None if orbit is None else at(orbit)

		# START road
		for local, p in SECTOR_BRAID.items():
			put([base + local], [p], None)
		for local, (side, curl) in SECTOR_BRAID_RINGS.items():
			p = SECTOR_BRAID[local]
			place_ring(nodes, ring_chain(nodes, adj, base + local), at((p[0] + (RING_RADIUS * 0.7071), p[1] + (side * RING_RADIUS * 0.7071))), at(p), curl)

		# J0 > J1 wheel: each side arc runs entry (at J1) > second > notable > third (at J0)
		j0, j1 = radius_of(1), radius_of(16)
		for first, bulge in WHEEL_ARCS.items():
			arc = [base + first] + chain_from(nodes, adj, base + first, base + 16)[1:4]
			points, orbit = arc_positions(j1, j0, bulge, len(arc))
			put(arc, points, orbit)
		approach = [j for j in adj[base + 1] if nodes[j].a["name"] == "Approach"][0]
		put([approach, [j for j in adj[approach] if j != base + 1][0]], [(j0 + du, dv) for du, dv in WHEEL_POD], None)

		# J1 > J2 and J2 > J3: lane, its spare small, the pod between lane and road, and the loop on the other side
		for (ja_local, lane_first, lane_bulge, loop_first, loop_depth, loop_radius, approach_local) in STRETCHES:
			ja = radius_of(ja_local)
			jb_id = [j for j in adj[base + lane_first] if nodes[j].a["name"] == "Crossroads"][0]
			jb = math.hypot(*nodes[jb_id].pos)
			entry = base + lane_first
			notable = [j for j in adj[entry] if nodes[j].type == "NOTABLE"][0]
			second = [j for j in adj[notable] if any(k == base + ja_local for k in adj[j])][0]
			spare = [j for j in adj[notable] if j not in (entry, second)][0]
			points, orbit = arc_positions(ja, jb, lane_bulge, 3)
			put([second, notable, entry], points, orbit)
			put([spare], [((ja + jb) / 2, lane_bulge - LANE_SPARE_DROP)], None)
			approach = base + approach_local
			put([approach, [j for j in adj[approach] if nodes[j].type in ("KEYSTONE", "ACTIVE_SKILL")][0]], [(ja + du, dv) for du, dv in STRETCH_POD], None)
			loop = ring_from(nodes, adj, base + loop_first, base + ja_local)
			assert len(loop) == 8, loop
			mid = (ja + jb) / 2
			put(loop, [(mid + (loop_radius * math.cos((math.pi * k) / 7)), -loop_depth - (loop_radius * math.sin((math.pi * k) / 7))) for k in range(8)], (mid, -loop_depth))

		# the eye round the MASTER
		if (base + EYE_FIRST) in nodes:
			filament = [j for j in adj[base + 79] if 9000 <= j < 9100][0]
			for k, side in enumerate((-1, 1)):
				arc = [base + EYE_FIRST + (5 * k) + i for i in range(5)]
				points, orbit = arc_positions(radius_of(62), math.hypot(*nodes[filament].pos), side * EYE_BULGE, len(arc))
				put(arc, points, orbit)


def chain_between(nodes, adj, a, b):
	"""The in-between nodes of the spine road from a to b."""
	for start in adj[a]:
		path, prev, cur = [], a, start
		while (cur != b) and (nodes[cur].name == "Pathway") and (len(path) < 5):
			path.append(cur)
			nxt = [j for j in adj[cur] if (j != prev) and (j < 20000) and ((nodes[j].name == "Pathway") or (j == b))]
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
