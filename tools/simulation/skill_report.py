"""
How brain A and brain B fake players use their skills, from the battle logs of a matrix or an arena
(decisions.jsonl in each battle folder, see tools/simulation/README.md).

    python tools/simulation/skill_report.py log/simulation-matrix/matrix-20261108-120000 [more folders]

Per build and brain: battles, decisions by reason (the most common ones), and for brain B's fight read
(FIGHT_READ lines, see docs/superpowers/specs/2026-11-08-fake-player-skill-timing-design.md) the share of
battles in which it saw enemy casts, the average casts seen and cooldowns remembered, its crowd control
tried and landed, its scored skills, and its plans started and finished.
"""
import argparse
import csv
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


def skill_split(folders):
    """For mirror A/B matrices: brain B's win rate by its skill level (thirds of the personality skill -1..1), from the kept battles."""
    import re
    groups = {"low (< -1/3)": [0.0, 0], "mid": [0.0, 0], "high (> 1/3)": [0.0, 0]}
    for root in folders:
        results = {}
        path = os.path.join(root, "results.csv")
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8") as f:
            for row in csv.DictReader(f):
                results[row["battle"]] = row["winner"]
        battles_dir = os.path.join(root, "battles")
        for name in os.listdir(battles_dir) if os.path.isdir(battles_dir) else []:
            run_path = os.path.join(battles_dir, name, "run.json")
            if not os.path.exists(run_path):
                continue
            with open(run_path, encoding="utf-8") as f:
                run = json.loads(f.readline())
            winner = results.get(str(run.get("battle")))
            if winner not in ("a", "b", "draw"):
                continue
            for member in run.get("members", []):
                if member.get("brain") != "B":
                    continue
                m = re.search(r"skill ([-+]?[0-9.]+)", member.get("personality", ""))
                if not m:
                    continue
                skill = float(m.group(1))
                key = "low (< -1/3)" if skill < -1 / 3 else "high (> 1/3)" if skill > 1 / 3 else "mid"
                groups[key][0] += 1.0 if winner == "b" else 0.5 if winner == "draw" else 0.0
                groups[key][1] += 1
    print("\nBrain B by skill level (kept battles):")
    for key, (points, n) in groups.items():
        if n:
            p = points / n
            half = 1.96 * (p * (1 - p) / n) ** 0.5
            print(f"  {key:14} {100 * p:5.1f}% of {n} (95% +-{100 * half:.1f})")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("folders", nargs="+")
    parser.add_argument("--top", type=int, default=8, help="reasons listed per build and brain")
    parser.add_argument("--split", action="store_true", help="brain B's win rate by skill level (mirror A/B matrices)")
    args = parser.parse_args()
    if args.split:
        skill_split(args.folders)
        return

    reflexes = Counter()
    reasons = defaultdict(Counter)
    battles = Counter()
    read_battles = Counter()
    seen_battles = Counter()
    seen_sum = Counter()
    remembered_sum = Counter()
    cc_tried = Counter()
    cc_landed = Counter()
    plans_started = Counter()
    plans_done = Counter()
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
                    elif d.get("reason") in ("REFLEX", "REFLEX_RACE_SKIP"):
                        kind = (d.get("kind") or "").split(";")[0]
                        reflexes[(d.get("reason"), kind)] += 1
                    elif d.get("reason") == "PLAN_START":
                        plans_started[key] += 1
                    elif d.get("reason") == "PLAN_DROP" and (d.get("kind") or "").endswith(";done"):
                        plans_done[key] += 1
            for member_id, key in members.items():
                battles[key] += 1
                fields = last_read.get(member_id)
                if fields is not None:
                    read_battles[key] += 1
                    seen = int(fields.get("seen", 0))
                    seen_sum[key] += seen
                    remembered_sum[key] += int(fields.get("remembered", 0))
                    cc_tried[key] += int(fields.get("ccTried", 0))
                    cc_landed[key] += int(fields.get("ccLanded", 0))
                    if seen > 0:
                        seen_battles[key] += 1

    for key in sorted(battles):
        build, brain = key
        line = f"{build} [{brain}]: {battles[key]} battles"
        if read_battles[key]:
            n = read_battles[key]
            line += f"; fight read in {n}, saw casts in {100 * seen_battles[key] / n:.0f}%, avg seen {seen_sum[key] / n:.1f}, remembered {remembered_sum[key] / n:.1f}"
            if cc_tried[key]:
                line += f", CC landed {cc_landed[key]}/{cc_tried[key]}"
            if plans_started[key]:
                line += f", plans {plans_done[key]}/{plans_started[key]} finished"
        print(line)
        top = ", ".join(f"{r} {c}" for r, c in reasons[key].most_common(args.top))
        print("    " + top)
    if reflexes:
        print("\nReflexes (fired / skipped while winning the race), by event:")
        for (reason, kind), count in sorted(reflexes.items(), key=lambda x: -x[1])[:15]:
            print(f"  {reason:17} {kind:40} {count}")
    total_read = sum(read_battles.values())
    if total_read:
        print(f"\nBrain B fight read: casts seen in {100 * sum(seen_battles.values()) / total_read:.0f}% of {total_read} fighter-battles.")


if __name__ == "__main__":
    main()
