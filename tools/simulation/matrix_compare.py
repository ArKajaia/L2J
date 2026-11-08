"""
Compares two class-vs-class matrices (//SimulationMatrix, see tools/simulation/README.md): the overall win rate of every
build in the first one (the baseline) against the second one (the change), with the difference and its 95% interval.

    python tools/simulation/matrix_compare.py <baseline folder> <changed folder> [--builds "Archmage,Sagittarius"]
        [--pairs] [--markdown]

--builds marks the builds the change was made to (they are listed first). --pairs adds, for each marked build, how its
win rate against each opponent moved (10 battles a pair is noisy: read the big moves only). --markdown prints tables.
A draw counts half, as in matrix.py.
"""
import argparse
import csv
import math
import os
from collections import defaultdict


def load(folder):
    """{(build, opponent): [points, battles]} from the build's side, and the builds in order of appearance."""
    pairs = defaultdict(lambda: [0.0, 0])
    builds = []
    with open(os.path.join(folder, "results.csv"), encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if row["winner"] not in ("a", "b", "draw"):
                continue
            a, b = row["a"], row["b"]
            for x in (a, b):
                if x not in builds:
                    builds.append(x)
            score_a = 1.0 if row["winner"] == "a" else 0.0 if row["winner"] == "b" else 0.5
            pairs[(a, b)][0] += score_a
            pairs[(a, b)][1] += 1
            pairs[(b, a)][0] += 1 - score_a
            pairs[(b, a)][1] += 1
    return pairs, builds


def overall(pairs, build):
    points = sum(v[0] for (x, _), v in pairs.items() if x == build)
    games = sum(v[1] for (x, _), v in pairs.items() if x == build)
    return points, games


def delta_interval(p1, n1, p2, n2, z=1.96):
    """The difference p2 - p1 and its 95% interval (normal approximation)."""
    if n1 == 0 or n2 == 0:
        return 0.0, -1.0, 1.0
    d = p2 - p1
    se = math.sqrt(p1 * (1 - p1) / n1 + p2 * (1 - p2) / n2)
    return d, d - z * se, d + z * se


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("baseline")
    parser.add_argument("changed")
    parser.add_argument("--builds", default="", help="the builds the change was made to, separated by commas")
    parser.add_argument("--pairs", action="store_true", help="per opponent moves of the marked builds")
    parser.add_argument("--markdown", action="store_true")
    args = parser.parse_args()

    base, order = load(args.baseline)
    changed, _ = load(args.changed)
    marked = [b.strip() for b in args.builds.split(",") if b.strip()]
    rows = []
    for build in order:
        p1, n1 = overall(base, build)
        p2, n2 = overall(changed, build)
        w1 = p1 / n1 if n1 else 0
        w2 = p2 / n2 if n2 else 0
        d, lo, hi = delta_interval(w1, n1, w2, n2)
        rows.append((build, build in marked, w1, n1, w2, n2, d, lo, hi))
    rows.sort(key=lambda r: (not r[1], -r[6]))

    def sig(lo, hi):
        return "+" if lo > 0 else "-" if hi < 0 else ""

    if args.markdown:
        print("| Build | Changed | Without | With | Difference (95% interval) |")
        print("|---|---|---:|---:|---:|")
    else:
        print(f"{'build':26} {'chg':3} {'without':>14} {'with':>14} {'difference (95%)':>26}")
    for build, mark, w1, n1, w2, n2, d, lo, hi in rows:
        if args.markdown:
            print(f"| {build} | {'yes' if mark else ''} | {100 * w1:.1f}% ({n1}) | {100 * w2:.1f}% ({n2}) | {100 * d:+.1f} ({100 * lo:+.1f} to {100 * hi:+.1f}) {sig(lo, hi)} |")
        else:
            print(f"{build:26} {'*' if mark else '':3} {100 * w1:6.1f}% ({n1:4}) {100 * w2:6.1f}% ({n2:4}) {100 * d:+6.1f} ({100 * lo:+5.1f} to {100 * hi:+5.1f}) {sig(lo, hi)}")

    if marked:
        # The marked builds together, and against the unmarked ones only (their fights with each other change on both sides).
        for label, opponents in (("all opponents", None), ("unchanged opponents only", [b for b in order if b not in marked])):
            t1 = [0.0, 0]
            t2 = [0.0, 0]
            for build in marked:
                for (x, y), v in base.items():
                    if x == build and (opponents is None or y in opponents):
                        t1[0] += v[0]
                        t1[1] += v[1]
                for (x, y), v in changed.items():
                    if x == build and (opponents is None or y in opponents):
                        t2[0] += v[0]
                        t2[1] += v[1]
            if t1[1] and t2[1]:
                w1, w2 = t1[0] / t1[1], t2[0] / t2[1]
                d, lo, hi = delta_interval(w1, t1[1], w2, t2[1])
                print(f"\nChanged builds together, {label}: {100 * w1:.1f}% ({t1[1]}) -> {100 * w2:.1f}% ({t2[1]}), {100 * d:+.1f} ({100 * lo:+.1f} to {100 * hi:+.1f}) {sig(lo, hi)}")

    if args.pairs and marked:
        # Per opponent, summed over the marked builds: where the change wins or loses.
        print("\nBy opponent, the changed builds together:")
        per = []
        for opponent in order:
            t1 = [0.0, 0]
            t2 = [0.0, 0]
            for build in marked:
                if build == opponent:
                    continue
                v1 = base.get((build, opponent))
                v2 = changed.get((build, opponent))
                if v1:
                    t1[0] += v1[0]
                    t1[1] += v1[1]
                if v2:
                    t2[0] += v2[0]
                    t2[1] += v2[1]
            if t1[1] and t2[1]:
                w1, w2 = t1[0] / t1[1], t2[0] / t2[1]
                d, lo, hi = delta_interval(w1, t1[1], w2, t2[1])
                per.append((opponent, opponent in marked, w1, t1[1], w2, t2[1], d, lo, hi))
        per.sort(key=lambda r: -r[6])
        for opponent, mark, w1, n1, w2, n2, d, lo, hi in per:
            print(f"  vs {opponent:26} {'*' if mark else '':2} {100 * w1:6.1f}% ({n1:3}) -> {100 * w2:6.1f}% ({n2:3}) {100 * d:+6.1f} ({100 * lo:+5.1f} to {100 * hi:+5.1f}) {sig(lo, hi)}")


if __name__ == "__main__":
    main()
