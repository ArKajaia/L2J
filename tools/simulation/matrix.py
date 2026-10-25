"""
Class-vs-class win rate matrix of the fake players (//SimulationMatrix, see tools/simulation/README.md).

Reads the results.csv of a matrix and writes its win rate matrix as matrix.csv and a heat map page, matrix.html
(the win rate of each row against each column, a draw counting half, with the number of battles and a 95% interval
on hover). It can also start or resume a matrix through the simulation web API and follow it.

    python tools/simulation/matrix.py log/simulation-matrix/matrix-20261025-211412
    python tools/simulation/matrix.py --start --battles 20 --parallel 100 --level 85
    python tools/simulation/matrix.py --resume matrix-20261025-211412
    python tools/simulation/matrix.py --follow

The API needs a GM's link (pass its admin=... token with --token) unless SimulationWebOpenAccess is on.
"""
import argparse
import csv
import html
import json
import math
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict


class Api:
    def __init__(self, base, token):
        self.base = base.rstrip("/") + "/api/simulation/matrix/"
        self.token = token

    def call(self, path, params=None, post=False):
        url = self.base + path
        data = urllib.parse.urlencode(params or {}).encode() if post else None
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


def read_builds(folder):
    """The builds in matrix order (matrix.properties), else in the order results.csv names them."""
    path = os.path.join(folder, "matrix.properties")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                if line.startswith("builds="):
                    # java.util.Properties escapes some characters (\\' is not one of them, but stay safe).
                    return [b.replace("\\", "") for b in line.strip()[7:].split("|")]
    return None


def load(folder):
    """{(a, b): [wins of a, wins of b, draws]}, the builds, and some totals."""
    pairs = defaultdict(lambda: [0, 0, 0])
    reasons = defaultdict(int)
    durations = []
    side = [0, 0]
    order = []
    with open(os.path.join(folder, "results.csv"), encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            a, b, winner = row["a"], row["b"], row["winner"]
            for build in (a, b):
                if build not in order:
                    order.append(build)
            pairs[(a, b)][0 if winner == "a" else 1 if winner == "b" else 2] += 1
            reasons[row["reason"]] += 1
            durations.append(int(row["duration_ms"]) / 1000)
            if winner in ("a", "b"):
                won_spot = row["a_spot"] if winner == "a" else ("2" if row["a_spot"] == "1" else "1")
                side[0 if won_spot == "1" else 1] += 1
    builds = read_builds(folder) or order
    return pairs, builds, reasons, durations, side


def score(pairs, a, b):
    """(wins of a counting a draw half, battles) between a and b."""
    if (a, b) in pairs:
        w, l, d = pairs[(a, b)]
        return w + d / 2, w + l + d
    if (b, a) in pairs:
        l, w, d = pairs[(b, a)]
        return w + d / 2, w + l + d
    return 0.0, 0


def color(rate):
    """Red (0%) through a light middle (50%) to green (100%)."""
    if rate is None:
        return "transparent"
    t = max(0.0, min(1.0, rate))
    if t < 0.5:
        k = t / 0.5
        r, g, b = 200, int(60 + 160 * k), int(60 + 160 * k)
    else:
        k = (t - 0.5) / 0.5
        r, g, b = int(220 - 170 * k), int(220 - 40 * k), int(220 - 150 * k)
    return f"rgb({r},{g},{b})"


def write(folder):
    pairs, builds, reasons, durations, side = load(folder)
    overall = {}
    for a in builds:
        wins = games = 0
        for b in builds:
            if a != b:
                w, g = score(pairs, a, b)
                wins += w
                games += g
        overall[a] = (wins / games if games else 0.0, games)
    ranked = sorted(builds, key=lambda b: -overall[b][0])

    with open(os.path.join(folder, "matrix.csv"), "w", encoding="utf-8", newline="") as f:
        out = csv.writer(f)
        out.writerow(["class"] + ranked + ["overall", "games"])
        for a in ranked:
            row = [a]
            for b in ranked:
                w, g = score(pairs, a, b)
                row.append("" if a == b or g == 0 else f"{100 * w / g:.1f}")
            row += [f"{100 * overall[a][0]:.1f}", overall[a][1]]
            out.writerow(row)

    total = sum(reasons.values())
    name = os.path.basename(os.path.abspath(folder))
    rows = []
    for a in ranked:
        cells = [f'<th class="row">{html.escape(a)}</th>']
        for b in ranked:
            if a == b:
                cells.append('<td class="self"></td>')
                continue
            w, g = score(pairs, a, b)
            if g == 0:
                cells.append('<td class="none"></td>')
                continue
            rate = w / g
            low, high = wilson(w, g)
            tip = f"{a} vs {b}: {100 * rate:.0f}% of {g} battles (95% {100 * low:.0f}-{100 * high:.0f}%)"
            cells.append(f'<td style="background:{color(rate)}" title="{html.escape(tip)}">{100 * rate:.0f}</td>')
        rate, games = overall[a]
        cells.append(f'<td class="overall" style="background:{color(rate)}">{100 * rate:.1f}</td>')
        rows.append("<tr>" + "".join(cells) + "</tr>")
    header = "".join(f'<th class="col"><div>{html.escape(b)}</div></th>' for b in ranked)
    median = sorted(durations)[len(durations) // 2] if durations else 0
    summary = (f"{total} battles, {len(builds)} builds. Ends: " + ", ".join(f"{k} {v}" for k, v in sorted(reasons.items(), key=lambda x: -x[1]))
               + f". Median fight {median:.0f} s. Wins from spot 1 / spot 2: {side[0]} / {side[1]}.")
    page = f"""<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Class Win Rates</title>
<style>
:root {{ --bg: #fff; --fg: #1d1d1f; --muted: #6e6e73; --line: #d2d2d7; }}
@media (prefers-color-scheme: dark) {{ :root {{ --bg: #1c1c1e; --fg: #f2f2f7; --muted: #a1a1a6; --line: #3a3a3c; }} }}
body {{ background: var(--bg); color: var(--fg); font: 13px/1.4 system-ui, sans-serif; margin: 16px; }}
p {{ color: var(--muted); max-width: 70em; }}
.wrap {{ overflow: auto; }}
table {{ border-collapse: collapse; }}
td {{ width: 30px; height: 22px; text-align: center; font-variant-numeric: tabular-nums; color: #1d1d1f; border: 1px solid var(--bg); }}
td.self {{ background: var(--line); }}
td.overall {{ width: 44px; font-weight: 600; }}
th.row {{ text-align: right; padding-right: 6px; white-space: nowrap; font-weight: 500; position: sticky; left: 0; background: var(--bg); }}
th.col {{ height: 130px; vertical-align: bottom; padding: 0; }}
th.col div {{ writing-mode: vertical-rl; transform: rotate(180deg); white-space: nowrap; font-weight: 500; padding: 4px 0; }}
</style></head><body>
<h1>Class win rates: {html.escape(name)}</h1>
<p>Each cell is how often the row's build beat the column's build in a 1v1 at the matrix's level (a draw counts half). The rows are ordered by overall win rate, the last column. Hover a cell for its battle count and 95% interval. {html.escape(summary)}</p>
<div class="wrap"><table><tr><th></th>{header}<th class="col"><div>overall</div></th></tr>
{"".join(rows)}
</table></div></body></html>
"""
    with open(os.path.join(folder, "matrix.html"), "w", encoding="utf-8") as f:
        f.write(page)

    print(summary)
    for i, b in enumerate(ranked, 1):
        print(f"{i:2}. {b:20} {100 * overall[b][0]:5.1f}% ({overall[b][1]} battles)")
    print("Written: " + os.path.join(folder, "matrix.csv") + ", " + os.path.join(folder, "matrix.html"))


def follow(api):
    """Prints the progress of the running matrix until it ends, and returns its folder."""
    folder = None
    last = None
    while True:
        status = api.call("status")
        matrix = status.get("matrix")
        if not matrix:
            return folder
        folder = matrix["dir"]
        if matrix["done"] != last:
            last = matrix["done"]
            print(" / ".join(status["lines"][:2]), flush=True)
        time.sleep(30)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("folder", nargs="?", help="a matrix folder (log/simulation-matrix/matrix-...) to write the matrix of")
    parser.add_argument("--url", default="http://127.0.0.1:8788", help="the simulation web server (PassiveTreeWebPort)")
    parser.add_argument("--token", help="the admin=... token of a //SimulationWeb link (not needed with SimulationWebOpenAccess)")
    parser.add_argument("--start", action="store_true", help="start a matrix (at the GM's place, else the simulation field by Cruma Marshlands), follow it, then write it")
    parser.add_argument("--resume", metavar="NAME", nargs="?", const="", help="resume a matrix (the newest one without a name), follow it, then write it")
    parser.add_argument("--follow", action="store_true", help="follow the running matrix, then write it")
    parser.add_argument("--battles", type=int, default=20, help="battles per pair")
    parser.add_argument("--parallel", type=int, default=100, help="battles at once")
    parser.add_argument("--level", type=int, default=85)
    parser.add_argument("--time", type=int, default=300, help="time limit of a battle, in seconds")
    parser.add_argument("--distance", type=int, default=1000, help="how far apart the two spawn")
    parser.add_argument("--brain", default="A")
    parser.add_argument("--builds", help="build names separated by commas (default: every build)")
    parser.add_argument("--seed")
    parser.add_argument("--place", help="x,y,z[,heading] instead of the default place")
    args = parser.parse_args()

    if args.folder:
        write(args.folder)
        return

    api = Api(args.url, args.token)
    if args.start:
        params = {"battles": args.battles, "parallel": args.parallel, "level": args.level, "time": args.time, "distance": args.distance, "brain": args.brain}
        if args.builds:
            params["builds"] = args.builds
        if args.seed:
            params["seed"] = args.seed
        if args.place:
            values = args.place.split(",")
            params.update(dict(zip(["x", "y", "z", "heading"], values)))
        answer = api.call("start", params, post=True)
        print("\n".join(answer["lines"]))
    elif args.resume is not None:
        params = {"parallel": args.parallel} if args.parallel else {}
        if args.resume:
            params["name"] = args.resume
        answer = api.call("resume", params, post=True)
        print("\n".join(answer["lines"]))
    elif not args.follow:
        parser.print_help()
        return

    folder = follow(api)
    if folder and os.path.exists(os.path.join(folder, "results.csv")):
        write(folder)
    elif folder:
        print(f"Finished. Run: python {sys.argv[0]} <the matrix folder on the server> ({folder})")


if __name__ == "__main__":
    main()
