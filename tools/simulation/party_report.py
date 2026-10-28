"""
How the parties of an A/B arena fight, per brain, from the battle logs it kept (log/simulation-arena/<arena>/battles):
the healer race (who is hit first, who dies first and when), where each role stands at first contact, how spread
out a team is, and how long brain B's party brain spends in each stance.

    python tools/simulation/party_report.py build/server/game/log/simulation-arena/arena-20261028-191653

Both brains are measured the same way, so the numbers of brain A are the baseline for those of brain B.
"""
import argparse
import bisect
import json
import math
import os
from collections import Counter, defaultdict

ROLES = ("TANK", "MELEE", "ARCHER", "NUKER", "HEALER")


def load_lines(path):
    lines = []
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                try:
                    lines.append(json.loads(line))
                except ValueError:
                    pass
    except OSError:
        pass
    return lines


def median(values):
    values = sorted(values)
    return values[len(values) // 2] if values else None


class Brain:
    def __init__(self):
        self.teams = 0
        self.wins = 0
        self.first_hit = Counter()  # role of ours hit first
        self.first_death = Counter()  # role of ours that died first, when ours died first
        self.died_first = 0
        self.death_times = defaultdict(list)  # role -> seconds after fight start
        self.healer_survived = 0
        self.closest_at_contact = Counter()  # role closest to the enemy at first contact
        self.healer_behind = []  # how far behind our front-most member the healer stands at contact
        self.spread = []  # team spread at contact
        self.stance_time = Counter()  # stance -> ms
        self.party_engages = 0  # members that joined the fight on the party's call (SIM_ENGAGE kind PARTY)
        self.calls = Counter()  # focus calls by why (HEALER, FINISH, SHOOTER)
        self.kinds = Counter()  # decisions of brain B's party features: COVER moves and holds, SHOOTER peels and tank charges
        self.healer_gap = []  # at the first archer or nuker shot on our healer: the healer's distance to its nearest teammate
        self.shooter_gap = []  # ... and the shooter's distance to our nearest member


def analyse(arena):
    root = os.path.join(arena, "battles")
    brains = defaultdict(Brain)
    battles = 0
    for name in sorted(os.listdir(root)):
        folder = os.path.join(root, name)
        try:
            run = json.load(open(os.path.join(folder, "run.json"), encoding="utf-8"))
            summary = json.load(open(os.path.join(folder, "summary.json"), encoding="utf-8"))
        except (OSError, ValueError):
            continue
        team_brain = {int(k): v for k, v in run["teamBrains"].items()}
        role = {m["id"]: m["role"] for m in run["members"]}
        team = {m["id"]: m["team"] for m in run["members"]}
        events = load_lines(os.path.join(folder, "events.jsonl"))
        positions = load_lines(os.path.join(folder, "positions.jsonl"))
        start = next((e["t"] for e in events if e.get("type") == "fight_start"), None)
        end = next((e["t"] for e in events if e.get("type") == "round_end"), None)
        if start is None:
            continue
        battles += 1
        winner = summary.get("winner", 0)
        for t in (1, 2):
            b = brains[team_brain[t]]
            b.teams += 1
            b.wins += winner == t

        # The healer race.
        damage = [e for e in events if e.get("type") == "damage" and e.get("attackerTeam") != e.get("targetTeam") and e.get("targetId") in role]
        if damage:
            first = damage[0]
            brains[team_brain[first["targetTeam"]]].first_hit[role[first["targetId"]]] += 1
        kills = summary.get("kills", [])
        if kills:
            b = brains[team_brain[kills[0]["victimTeam"]]]
            b.died_first += 1
            b.first_death[kills[0].get("victimRole", "?")] += 1
        dead_healers = set()
        for k in kills:
            brains[team_brain[k["victimTeam"]]].death_times[k.get("victimRole", "?")].append((k["t"] - start) / 1000)
            if k.get("victimRole") == "HEALER":
                dead_healers.add(k["victimTeam"])
        for t in (1, 2):
            if t not in dead_healers and any(r == "HEALER" and team[i] == t for i, r in role.items()):
                brains[team_brain[t]].healer_survived += 1

        # Where everyone stands at first contact.
        if damage and positions:
            latest = {}
            frames = []
            for p in positions:
                if p.get("id") in team:
                    latest[p["id"]] = (p["x"], p["y"], p.get("alive", True))
                    frames.append((p["t"], dict(latest)))
            times = [f[0] for f in frames]
            frame = frames[max(0, bisect.bisect_right(times, damage[0]["t"] - 500) - 1)][1]
            for t in (1, 2):
                enemies = [v for i, v in frame.items() if team[i] != t and v[2]]
                mine = [(i, v) for i, v in frame.items() if team[i] == t and v[2]]
                if not enemies or len(mine) < 2:
                    continue
                ex = sum(v[0] for v in enemies) / len(enemies)
                ey = sum(v[1] for v in enemies) / len(enemies)
                distance = {i: math.hypot(v[0] - ex, v[1] - ey) for i, v in mine}
                closest = min(distance, key=distance.get)
                b = brains[team_brain[t]]
                b.closest_at_contact[role[closest]] += 1
                for i, d in distance.items():
                    if role[i] == "HEALER":
                        b.healer_behind.append(d - distance[closest])
                cx = sum(v[0] for _, v in mine) / len(mine)
                cy = sum(v[1] for _, v in mine) / len(mine)
                b.spread.append(max(math.hypot(v[0] - cx, v[1] - cy) for _, v in mine))

        # Brain B's stances: the time in each, from its party_stance events.
        stance = {}
        since = {}
        for e in events:
            if e.get("type") == "party_stance":
                t = e["team"]
                if t in stance:
                    brains[team_brain[t]].stance_time[stance[t]] += e["t"] - since[t]
                stance[t] = e["to"]
                since[t] = e["t"]
        for t in stance:
            brains[team_brain[t]].stance_time[stance[t]] += max(0, (end or since[t]) - since[t])
        for d in load_lines(os.path.join(folder, "decisions.jsonl")):
            if d.get("team") not in team_brain:
                continue
            if d.get("reason") == "SIM_ENGAGE" and d.get("kind") == "PARTY":
                brains[team_brain[d["team"]]].party_engages += 1
            elif d.get("kind") in ("COVER", "SHOOTER"):
                brains[team_brain[d["team"]]].kinds[d["reason"] + " " + d["kind"]] += 1
        for e in events:
            if e.get("type") == "focus_call" and e.get("team") in team_brain:
                brains[team_brain[e["team"]]].calls[e.get("why")] += 1

        # The first archer or nuker shot on each healer: how far the healer stood from its team, and the shooter from it.
        if positions:
            latest = {}
            frames = []
            for p in positions:
                if p.get("id") in team:
                    latest[p["id"]] = (p["x"], p["y"])
                    frames.append((p["t"], dict(latest)))
            times = [f[0] for f in frames]
            shot = set()
            for e in damage:
                victim = e["targetId"]
                if role.get(victim) != "HEALER" or role.get(e.get("attackerId")) not in ("ARCHER", "NUKER") or victim in shot:
                    continue
                shot.add(victim)
                frame = frames[max(0, bisect.bisect_right(times, e["t"]) - 1)][1]
                healer, shooter = frame.get(victim), frame.get(e["attackerId"])
                mates = [v for i, v in frame.items() if team[i] == team[victim] and i != victim]
                if healer and shooter and mates:
                    b = brains[team_brain[team[victim]]]
                    b.healer_gap.append(min(math.hypot(healer[0] - v[0], healer[1] - v[1]) for v in mates))
                    b.shooter_gap.append(min(math.hypot(shooter[0] - v[0], shooter[1] - v[1]) for v in mates))
    return battles, brains


def pct(part, whole):
    return f"{100 * part / whole:.0f}%" if whole else "-"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("arena", help="an arena folder (log/simulation-arena/arena-...)")
    args = parser.parse_args()
    battles, brains = analyse(args.arena)
    names = sorted(brains)
    print(f"{os.path.basename(os.path.normpath(args.arena))}: {battles} battles with logs")
    rows = [("wins", lambda b: f"{b.wins}/{b.teams} ({pct(b.wins, b.teams)})")]
    rows.append(("first hit is our healer", lambda b: f"{b.first_hit['HEALER']}/{sum(b.first_hit.values())} ({pct(b.first_hit['HEALER'], sum(b.first_hit.values()))})"))
    rows.append(("lost a member first", lambda b: f"{b.died_first}/{b.teams} ({pct(b.died_first, b.teams)})"))
    rows.append(("  ... and it was the healer", lambda b: f"{b.first_death['HEALER']}/{b.died_first} ({pct(b.first_death['HEALER'], b.died_first)})"))
    rows.append(("healer alive at the end", lambda b: f"{b.healer_survived}/{b.teams} ({pct(b.healer_survived, b.teams)})"))
    for r in ROLES:
        rows.append((f"{r.lower()} dies after (median s)", lambda b, r=r: f"{median(b.death_times[r]):.1f}" if b.death_times[r] else "-"))
    rows.append(("healer behind the front (median)", lambda b: f"{median(b.healer_behind):.0f}" if b.healer_behind else "-"))
    rows.append(("spread at contact (median)", lambda b: f"{median(b.spread):.0f}" if b.spread else "-"))
    for r in ROLES:
        rows.append((f"closest at contact: {r.lower()}", lambda b, r=r: pct(b.closest_at_contact[r], sum(b.closest_at_contact.values()))))
    rows.append(("first shot on healer: healer to team", lambda b: f"{median(b.healer_gap):.0f}" if b.healer_gap else "-"))
    rows.append(("  ... shooter to our team", lambda b: f"{median(b.shooter_gap):.0f}" if b.shooter_gap else "-"))
    rows.append(("joined on the party's call", lambda b: str(b.party_engages)))
    rows.append(("focus calls", lambda b: ", ".join(f"{k.lower()} {v}" for k, v in b.calls.most_common()) or "-"))
    rows.append(("cover / shooter answers", lambda b: ", ".join(f"{k.lower()} {v}" for k, v in b.kinds.most_common()) or "-"))
    rows.append(("time in stance", lambda b: ", ".join(f"{s.lower()} {pct(v, sum(b.stance_time.values()))}" for s, v in b.stance_time.most_common()) or "-"))
    width = max(len(label) for label, _ in rows)
    print(" " * width + "".join(f"  {'brain ' + n:>22}" for n in names))
    for label, value in rows:
        print(f"{label:<{width}}" + "".join(f"  {value(brains[n]):>22}" for n in names))


if __name__ == "__main__":
    main()
