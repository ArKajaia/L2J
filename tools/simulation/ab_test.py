"""
A/B test of the fake player AI brains (see docs/fake-player-simulation-issues.md).

Runs one simulation per level through the simulation web page's API (//SimulationWeb), waits for each one, then
prints how each brain did: win rate with its 95% interval, wins by side, when the rounds were decided, how often
a healer died first, and the anomalies of each brain.

    python tools/simulation/ab_test.py --brains A:B --swap --levels 30,40,50,60,70,80 --rounds 10
    python tools/simulation/ab_test.py --report 20261025-133126-393834156 20261025-134117-264484967

The API needs a GM's link (pass its admin=... token with --token) unless SimulationWebOpenAccess is on.
"""
import argparse
import json
import math
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict


class Api:
    def __init__(self, base, token):
        self.base = base.rstrip("/") + "/api/simulation/"
        self.token = token

    def call(self, path, params=None, post=False):
        url = self.base + path
        data = None
        if params and not post:
            url += "?" + urllib.parse.urlencode(params)
        elif post:
            data = urllib.parse.urlencode(params or {}).encode()
        request = urllib.request.Request(url, data=data, method="POST" if post else "GET")
        if self.token:
            request.add_header("Authorization", "Bearer " + self.token)
        for attempt in range(10):
            try:
                with urllib.request.urlopen(request, timeout=60) as response:
                    return json.loads(response.read().decode("utf-8"))
            except urllib.error.HTTPError as e:
                if e.code in (401, 403, 404):
                    raise SystemExit(f"{path}: HTTP {e.code} {e.read().decode('utf-8', 'replace')}")
                time.sleep(5)
            except (urllib.error.URLError, TimeoutError):
                time.sleep(5)
        raise SystemExit(f"{path}: the server doesn't answer")


def wilson(wins, games, z=1.96):
    if games == 0:
        return 0.0, 1.0
    p = wins / games
    denominator = 1 + z * z / games
    center = (p + z * z / (2 * games)) / denominator
    margin = z * math.sqrt(p * (1 - p) / games + z * z / (4 * games * games)) / denominator
    return max(0.0, center - margin), min(1.0, center + margin)


def run_series(api, args, level):
    while api.call("status").get("running"):
        time.sleep(10)
    params = {"size": args.size, "level": level, "rounds": args.rounds, "time": args.time, "brains": args.brains, "swap": "1" if args.swap else "0", "mirror": "1" if args.mirror else "0", "points": args.points, "swapPoints": "1" if args.swap_points else "0"}
    if args.seed:
        params["seed"] = args.seed
    answer = api.call("start", params, post=True)
    series = answer.get("series")
    print(f"level {level}: " + " / ".join(answer.get("lines", [])), flush=True)
    if not series:
        return None
    done = -1
    while True:
        time.sleep(10)
        status = api.call("status")
        if not status.get("running") or status.get("series") != series:
            break
        if status.get("round") != done:
            done = status.get("round")
            print(f"  round {done}/{status.get('rounds')}", flush=True)
    return series


def report(api, names):
    runs = {r["name"]: r for r in api.call("runs")["runs"]}
    total = defaultdict(lambda: [0, 0])
    sides = defaultdict(lambda: [0, 0])
    healer_first = Counter()
    first_deaths = Counter()
    decided = []
    anomalies = defaultdict(Counter)
    by_level = []
    for name in names:
        run = runs.get(name)
        if run is None:
            print(f"{name}: not found")
            continue
        level_wins = defaultdict(lambda: [0, 0])
        for result in run["results"]:
            data = api.call("round", {"series": name, "round": result["round"]})
            summary = data.get("summary")
            if not summary:
                continue
            brain_of = {t["id"]: t.get("brain", "A") for t in summary["teams"]}
            size = {t["id"]: sum(1 for m in t["members"] if m.get("active", True)) for t in summary["teams"]}
            for team, brain in brain_of.items():
                won = team == summary["winner"]
                for table in (total, level_wins):
                    table[brain][1] += 1
                    table[brain][0] += won
                sides[team][1] += 1
                sides[team][0] += won
            for t in summary["teams"]:
                for m in t["members"]:
                    anomalies[brain_of[t["id"]]].update(m.get("anomalies") or {})
            kills = summary.get("kills") or []
            fight_start = data["run"]["config"]["prepTime"] * 1000
            if kills:
                first = kills[0]
                first_deaths[brain_of.get(first["victimTeam"], "?")] += 1
                if first.get("victimRole") == "HEALER":
                    healer_first[brain_of.get(first["victimTeam"], "?")] += 1
            if summary.get("decidedAt") is not None:
                decided.append(summary["decidedAt"] / 1000)
            elif kills:
                alive = dict(size)
                for kill in kills:
                    alive[kill["victimTeam"]] = alive.get(kill["victimTeam"], 1) - 1
                    if alive[kill["victimTeam"]] <= 1:
                        decided.append((kill["t"] - fight_start) / 1000)
                        break
        by_level.append((run["level"], name, dict(level_wins)))

    print()
    print("Per level (wins/games):")
    for level, name, wins in sorted(by_level):
        print(f"  level {level:2} {name}: " + ", ".join(f"{b} {w}/{g}" for b, (w, g) in sorted(wins.items())))
    print()
    print("By brain:")
    for brain, (wins, games) in sorted(total.items()):
        low, high = wilson(wins, games)
        print(f"  {brain}: {wins}/{games} = {wins / max(1, games):.0%} (95% {low:.0%}-{high:.0%}), first death {first_deaths[brain]}, healer died first {healer_first[brain]}")
    print("By side (team = point):")
    for team, (wins, games) in sorted(sides.items()):
        print(f"  team {team}: {wins}/{games} = {wins / max(1, games):.0%}")
    if decided:
        print(f"Decided (a team down to its last member) after {sum(decided) / len(decided):.0f} s on average")
    print("Anomalies per brain:")
    for brain, counts in sorted(anomalies.items()):
        print(f"  {brain}: " + ", ".join(f"{k} {v}" for k, v in counts.most_common()))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default="http://127.0.0.1:8788", help="the simulation web server (PassiveTreeWebPort)")
    parser.add_argument("--token", help="the admin=... token of a //SimulationWeb link (not needed with SimulationWebOpenAccess)")
    parser.add_argument("--levels", default="30,40,50,60,70,80")
    parser.add_argument("--rounds", type=int, default=10, help="rounds per level (even with --swap: each composition is played from both sides)")
    parser.add_argument("--size", type=int, default=5)
    parser.add_argument("--time", type=int, default=300, help="time limit of a round, in seconds")
    parser.add_argument("--brains", default="A:B", help="the brain of each team: A:A, A:B, B:B...")
    parser.add_argument("--swap", action="store_true", help="A/B test: mirrored teams, brains swap sides every round, new teams every 2 rounds")
    parser.add_argument("--mirror", action="store_true", help="same builds in every team (implied by --swap)")
    parser.add_argument("--seed", help="the seed (blank: random)")
    parser.add_argument("--points", default="saved")
    parser.add_argument("--swap-points", action="store_true", help="each team spawns at the next team's point (with --seed: the same teams on the other side)")
    parser.add_argument("--report", nargs="+", metavar="SERIES", help="only report on these finished simulations (folder names)")
    args = parser.parse_args()

    api = Api(args.url, args.token)
    if args.report:
        report(api, args.report)
        return
    if args.swap and args.rounds % 2:
        print("--swap plays each composition twice: use an even number of rounds", file=sys.stderr)
    names = []
    for level in [int(x) for x in args.levels.split(",") if x.strip()]:
        series = run_series(api, args, level)
        if series:
            names.append(series)
    report(api, names)


if __name__ == "__main__":
    main()
