#!/usr/bin/env python3
"""Where an archer's arrows are shot from, and what its range keystone makes of that.

Point Blank and Far Shot (passive tree) scale bow damage with the distance to the target: Far Shot from -30% at 150 or
closer to +30% at 900 or farther, Point Blank the other way round (PassiveMechanics.rangeMultiplier). This reads the
battles of a class clinic run and prints, per kind of opponent and per range keystone, how far the focus build stood
from its target when its normal attacks hit, and the damage factor its keystone gave at that distance.

    python3 tools/simulation/archer_range.py <matrix folder> [<matrix folder> ...]
"""
import bisect
import json
import math
import os
import statistics
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from class_clinic import GROUP_OF, GROUP_ORDER  # noqa: E402

POINT_BLANK_RANGE = 150
LONG_RANGE = 900


def keystone_of(member):
    keystones = (member.get("passives") or {}).get("keystones") or []
    if "Far Shot" in keystones:
        return "Far Shot"
    if "Point Blank" in keystones:
        return "Point Blank"
    return "none"


def factor(keystone, distance):
    far = max(0.0, min(1.0, (distance - POINT_BLANK_RANGE) / (LONG_RANGE - POINT_BLANK_RANGE)))
    if keystone == "Far Shot":
        return 1 + 0.3 * (2 * far - 1)
    if keystone == "Point Blank":
        return 1 + 0.3 * (1 - 2 * far)
    return 1.0


def read_battle(path, focus):
    try:
        run = json.load(open(os.path.join(path, "run.json")))
    except (OSError, ValueError):
        return None
    members = run.get("members") or []
    me = next((m for m in members if m.get("build") == focus), None)
    if me is None or len(members) != 2:
        return None
    enemy = next(m for m in members if m is not me)
    track = defaultdict(list)  # id -> [(t, x, y, z)]
    with open(os.path.join(path, "positions.jsonl")) as f:
        for line in f:
            p = json.loads(line)
            track[p["id"]].append((p["t"], p["x"], p["y"], p["z"]))
    times = {k: [s[0] for s in v] for k, v in track.items()}

    def where(member_id, t):
        samples = track.get(member_id)
        if not samples:
            return None
        i = bisect.bisect_left(times[member_id], t)
        i = min(max(i, 0), len(samples) - 1)
        if i > 0 and abs(samples[i - 1][0] - t) < abs(samples[i][0] - t):
            i -= 1
        return samples[i]

    shots = []
    with open(os.path.join(path, "events.jsonl")) as f:
        for line in f:
            e = json.loads(line)
            if e.get("type") != "damage" or e.get("attackerId") != me["id"] or e.get("skill") or e.get("dot"):
                continue
            a = where(me["id"], e["t"])
            b = where(enemy["id"], e["t"])
            if a and b:
                shots.append((math.dist(a[1:], b[1:]), e.get("amount", 0)))
    return {"group": GROUP_OF.get(enemy.get("build"), "?"), "keystone": keystone_of(me), "shots": shots}


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    for folder in sys.argv[1:]:
        battles = os.path.join(folder, "battles")
        focus = None
        rows = defaultdict(list)
        try:
            with open(os.path.join(folder, "matrix.properties")) as f:
                focus = next((line.split("=", 1)[1].strip() for line in f if line.startswith("focus=")), None)
        except OSError:
            pass
        if not focus:
            print("No focus build in", os.path.join(folder, "matrix.properties"))
            continue
        for name in sorted(os.listdir(battles)):
            battle = read_battle(os.path.join(battles, name), focus)
            if battle:
                rows[(battle["group"], battle["keystone"])].append(battle)
                rows[("all", battle["keystone"])].append(battle)
        print(f"\n{os.path.basename(os.path.normpath(folder))}: {focus}\n")
        print("| Against | Keystone | Battles | Arrows | Median distance | Under 525 | 900 or more | Keystone factor |")
        print("|---|---|---|---|---|---|---|---|")
        for group in GROUP_ORDER + ["all"]:
            for keystone in ("Far Shot", "Point Blank", "none"):
                battles_ = rows.get((group, keystone))
                if not battles_:
                    continue
                shots = [s for b in battles_ for s in b["shots"]]
                if not shots:
                    continue
                d = [s[0] for s in shots]
                f = statistics.mean(factor(keystone, x) for x in d)
                print(f"| {group} | {keystone} | {len(battles_)} | {len(shots)} | {statistics.median(d):.0f} | "
                      f"{sum(x < 525 for x in d) / len(d):.0%} | {sum(x >= 900 for x in d) / len(d):.0%} | {f:.3f} |")


if __name__ == "__main__":
    main()
