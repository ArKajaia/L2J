#!/usr/bin/env python3
"""Reports on batches of simulated Team vs Team matches (//tvt batch, POST /api/tvt/batch).

Usage:
    python tools/simulation/tvt_report.py <batch folder> [<batch folder> ...]
    python tools/simulation/tvt_report.py build/server/game/log/simulation-tvt/batch-*

For every pair of tactics that met: wins (a draw counts half) with a 95% interval, kills and deaths, and how
each played: the share of its damage that went into enemy healers ("healer focus"), the share of its members'
time on an enemy healer, its healers' deaths, where its archers and mages stood, and what each of its roles hit.
Standard library only.
"""
import csv
import math
import sys
from collections import defaultdict
from pathlib import Path

ROLES = ["TANK", "MELEE", "ARCHER", "NUKER", "HEALER", "BUFFER"]
DPS = {"MELEE", "ARCHER", "NUKER"}


def wilson(wins, n):
    if n == 0:
        return (0.0, 0.0)
    z = 1.96
    p = wins / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (100 * (c - h), 100 * (c + h))


def fnum(text):
    return float(text) if text not in ("", None) else None


def mean(values):
    values = [v for v in values if v is not None]
    return sum(values) / len(values) if values else float("nan")


def main(folders):
    rows, roles = [], []
    for folder in folders:
        path = Path(folder)
        if not (path / "results.csv").exists():
            print(f"skip {folder}: no results.csv", file=sys.stderr)
            continue
        with open(path / "results.csv", newline="") as f:
            rows += list(csv.DictReader(f))
        if (path / "roles.csv").exists():
            with open(path / "roles.csv", newline="") as f:
                roles += list(csv.DictReader(f))
    if not rows:
        print("No results.")
        return

    # Head to head: per pair of tactics.
    pairs = defaultdict(list)
    for r in rows:
        pairs[(r["tactics"], r["opponent"])].append(r)
    print("== Head to head (wins of the first; a draw counts half) ==")
    print(f"{'tactics':<24} {'vs':<24} {'n':>4} {'win%':>6} {'95% CI':>13} {'kill share%':>11} {'95% CI':>13} {'kills':>7} {'deaths':>7}")
    seen = set()
    for (a, b), rs in sorted(pairs.items()):
        if (b, a) in seen:
            continue
        seen.add((a, b))
        n = len(rs)
        w = sum(float(r["result"]) for r in rs)
        lo, hi = wilson(w, n)
        shares = [float(r["score"]) / max(1.0, float(r["score"]) + float(r["opponent_score"])) for r in rs]
        m = sum(shares) / n
        sd = math.sqrt(sum((x - m) ** 2 for x in shares) / max(1, n - 1))
        half = 1.96 * sd / math.sqrt(n)
        print(f"{a:<24} {b:<24} {n:>4} {100 * w / n:>6.1f} {lo:>6.1f}-{hi:<6.1f} {100 * m:>11.1f} {100 * (m - half):>6.1f}-{100 * (m + half):<6.1f} {mean([fnum(r['score']) for r in rs]):>7.1f} {mean([fnum(r['deaths']) for r in rs]):>7.1f}")

    # How each tactics played.
    by = defaultdict(list)
    for r in rows:
        by[r["tactics"]].append(r)
    print()
    print("== How each tactics played (means per team per match) ==")
    print(f"{'tactics':<24} {'n':>4} {'heal dmg%':>9} {'heal tgt%':>9} {'own healer deaths':>17} {'1st healer death s':>18} {'rng dist':>8} {'rng ahead%':>10} {'damage':>9}")
    for t, rs in sorted(by.items()):
        print(f"{t:<24} {len(rs):>4} {mean([fnum(r['healer_damage_pct']) for r in rs]):>9.1f} {mean([fnum(r['healer_target_pct']) for r in rs]):>9.1f} "
              f"{mean([fnum(r['healer_deaths']) for r in rs]):>17.2f} {mean([fnum(r['first_healer_death_s']) for r in rs]):>18.0f} "
              f"{mean([fnum(r['ranged_distance']) for r in rs]):>8.0f} {mean([fnum(r['ranged_ahead_pct']) for r in rs]):>10.1f} {mean([fnum(r['damage']) for r in rs]):>9.0f}")

    # Deaths by role.
    print()
    print("== Deaths by role (per team per match) ==")
    print(f"{'tactics':<24} " + " ".join(f"{x.lower():>7}" for x in ROLES))
    for t, rs in sorted(by.items()):
        sums = defaultdict(float)
        for r in rs:
            for part in r["deaths_by_role"].split():
                k, v = part.split("=")
                sums[k.upper()] += float(v)
        print(f"{t:<24} " + " ".join(f"{sums[x] / len(rs):>7.2f}" for x in ROLES))

    # What each role hit.
    dmg = defaultdict(lambda: defaultdict(float))
    tgt = defaultdict(lambda: defaultdict(float))
    for r in roles:
        dmg[(r["tactics"], r["attacker_role"])][r["target_role"]] += float(r["damage"])
        tgt[(r["tactics"], r["attacker_role"])][r["target_role"]] += float(r["target_seconds"])
    print()
    print("== Share of each role's damage by target role, % (healer focus by role) ==")
    print(f"{'tactics':<24} {'attacker':<8} " + " ".join(f"{x.lower():>7}" for x in ROLES) + f" {'dps':>7}")
    for (t, a), d in sorted(dmg.items()):
        total = sum(d.values()) or 1
        print(f"{t:<24} {a.lower():<8} " + " ".join(f"{100 * d[x] / total:>7.1f}" for x in ROLES) + f" {100 * sum(d[x] for x in DPS) / total:>7.1f}")
    print()
    print("== Share of each role's targeting time by target role, % ==")
    print(f"{'tactics':<24} {'attacker':<8} " + " ".join(f"{x.lower():>7}" for x in ROLES))
    for (t, a), d in sorted(tgt.items()):
        total = sum(d.values()) or 1
        print(f"{t:<24} {a.lower():<8} " + " ".join(f"{100 * d[x] / total:>7.1f}" for x in ROLES))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1:])
