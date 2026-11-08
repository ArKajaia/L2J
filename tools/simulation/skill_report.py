"""
How brain A and brain B fake players use their skills, from the battle logs of a matrix or an arena
(decisions.jsonl in each battle folder, see tools/simulation/README.md).

    python tools/simulation/skill_report.py log/simulation-matrix/matrix-20261108-120000 [more folders]

Per build and brain: battles, decisions by reason (the most common ones), and for brain B's fight read
(FIGHT_READ lines, see docs/superpowers/specs/2026-11-08-fake-player-skill-timing-design.md) the share of
battles in which it saw enemy casts, and the average casts seen and cooldowns remembered.
"""
import argparse
import json
import os
from collections import Counter, defaultdict


def parse_kind(kind):
    fields = {}
    for part in (kind or "").split(";"):
        if "=" in part:
            key, value = part.split("=", 1)
            fields[key] = value
    return fields


def battle_brains(folder):
    """Object id -> brain, from run.json."""
    path = os.path.join(folder, "run.json")
    brains = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            run = json.loads(f.readline())
        for member in run.get("members", []):
            brains[member.get("id")] = member.get("brain", "A")
    return brains


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("folders", nargs="+")
    parser.add_argument("--top", type=int, default=8, help="reasons listed per build and brain")
    args = parser.parse_args()

    reasons = defaultdict(Counter)
    battles = Counter()
    read_battles = Counter()
    seen_battles = Counter()
    seen_sum = Counter()
    remembered_sum = Counter()
    for root in args.folders:
        battles_dir = os.path.join(root, "battles")
        for name in sorted(os.listdir(battles_dir)) if os.path.isdir(battles_dir) else []:
            folder = os.path.join(battles_dir, name)
            path = os.path.join(folder, "decisions.jsonl")
            if not os.path.exists(path):
                continue
            brains = battle_brains(folder)
            last_read = {}
            members = {}
            with open(path, encoding="utf-8") as f:
                for line in f:
                    try:
                        d = json.loads(line)
                    except ValueError:
                        continue
                    key = (d.get("build"), brains.get(d.get("id"), "A"))
                    members[d.get("id")] = key
                    reasons[key][d.get("reason")] += 1
                    if d.get("reason") == "FIGHT_READ":
                        last_read[d.get("id")] = parse_kind(d.get("kind"))
            for member_id, key in members.items():
                battles[key] += 1
                fields = last_read.get(member_id)
                if fields is not None:
                    read_battles[key] += 1
                    seen = int(fields.get("seen", 0))
                    seen_sum[key] += seen
                    remembered_sum[key] += int(fields.get("remembered", 0))
                    if seen > 0:
                        seen_battles[key] += 1

    for key in sorted(battles):
        build, brain = key
        line = f"{build} [{brain}]: {battles[key]} battles"
        if read_battles[key]:
            n = read_battles[key]
            line += f"; fight read in {n}, saw casts in {100 * seen_battles[key] / n:.0f}%, avg seen {seen_sum[key] / n:.1f}, remembered {remembered_sum[key] / n:.1f}"
        print(line)
        top = ", ".join(f"{r} {c}" for r, c in reasons[key].most_common(args.top))
        print("    " + top)
    total_read = sum(read_battles.values())
    if total_read:
        print(f"\nBrain B fight read: casts seen in {100 * sum(seen_battles.values()) / total_read:.0f}% of {total_read} fighter-battles.")


if __name__ == "__main__":
    main()
