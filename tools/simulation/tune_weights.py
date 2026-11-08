"""
Self-play tuning of the fake player skill weights (config/Custom/FakeSkillWeights.ini, see
docs/superpowers/specs/2026-11-08-fake-player-skill-timing-design.md).

For one role at a time it tries each weight at x0.7 and x1.3 (coordinate search): each try is a mirror matrix
of that role's builds, brain A against brain B (the one that reads the weights), and a change is kept only if
B's win rate rises by more than the 95% interval half-width of the best so far. The server reads the
rewritten file through POST /api/simulation/weights/reload, so nothing restarts between tries.

    python tools/simulation/tune_weights.py --role MAGE --dry-run
    python tools/simulation/tune_weights.py --role MAGE --passes 1 --battles 40
    python tools/simulation/tune_weights.py --role ALL

Needs a running server with SimulationWebOpenAccess (tools/simulation/local_server.sh) or --token.
Every try and the final file go to <server>/log/tuning/<time>/.
"""
import argparse
import csv
import math
import os
import re
import shutil
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from matrix import Api, follow  # noqa: E402

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ROLES = ["FIGHTER", "TANK", "DAGGER", "ARCHER", "MAGE"]
# The defaults of SkillWeights (and FakeSkillWeights.ini).
DEFAULTS = {"damage": 1.0, "control": 1.0, "setup": 0.6, "cooldownCost": 1.0, "risk": 1.0, "raceMargin": 0.75, "burstReflexTtd": 3.0, "noise": 0.15, "planDepth": 3}
ROLE_DEFAULTS = {"MAGE": {"risk": 1.3}, "ARCHER": {"risk": 1.3}, "TANK": {"raceMargin": 0.6}}
TUNED_KEYS = ["damage", "control", "setup", "cooldownCost", "risk", "raceMargin"]


def read_weights(path):
    """{role: {key: value}} from a weights file, the defaults where it sets nothing."""
    weights = {role: dict(DEFAULTS, **ROLE_DEFAULTS.get(role, {})) for role in ROLES}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                m = re.match(r"\s*([A-Za-z]+)\.([A-Za-z]+)\s*=\s*([-0-9.]+)\s*$", line)
                if m and m.group(1).upper() in weights and m.group(2) in DEFAULTS:
                    weights[m.group(1).upper()][m.group(2)] = float(m.group(3))
    return weights


def write_weights(path, weights, header):
    """Writes every weight that differs from its default."""
    lines = [f"# {header}", "# Written by tools/simulation/tune_weights.py; see dist/game/config/Custom/FakeSkillWeights.ini for what each key does.", ""]
    for role in ROLES:
        for key, value in weights[role].items():
            if abs(value - DEFAULTS[key]) > 1e-9:
                lines.append(f"{role}.{key} = {round(value, 4)}")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def role_builds(role):
    """The builds of a role in data/FakePlayerPvp.xml (their gear variants aside)."""
    with open(os.path.join(REPO, "dist", "game", "data", "FakePlayerPvp.xml"), encoding="utf-8") as f:
        return [m.group(1) for m in re.finditer(r'<build name="([^"]+)"[^>]*\brole="' + role + '"', f.read())]


def ab_result(folder):
    """(B's win rate, battles) from a mirror matrix's ab.txt."""
    with open(os.path.join(folder, "ab.txt"), encoding="utf-8") as f:
        for line in f:
            m = re.match(r"All: A (\d+), B (\d+), draws (\d+)", line)
            if m:
                a, b, d = (int(x) for x in m.groups())
                n = a + b + d
                return (b + d / 2) / n if n else 0.5, n
    raise SystemExit(f"No result in {folder}/ab.txt")


def half_width(p, n):
    return 1.96 * math.sqrt(max(p * (1 - p), 1e-6) / max(n, 1))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--role", required=True, help="FIGHTER, TANK, DAGGER, ARCHER, MAGE or ALL")
    parser.add_argument("--passes", type=int, default=1, help="passes over the weights per role (at most 3)")
    parser.add_argument("--battles", type=int, default=40, help="battles per build in each matrix")
    parser.add_argument("--min-battles", type=int, default=400, help="at least this many battles per matrix (a role with few builds fights more per build)")
    parser.add_argument("--parallel", type=int, default=700)
    parser.add_argument("--seed", default="4040")
    parser.add_argument("--keys", default=",".join(TUNED_KEYS))
    parser.add_argument("--factors", default="0.7,1.3")
    parser.add_argument("--server", default=os.path.join(os.path.dirname(REPO), "build", "server", "game"), help="the game server folder")
    parser.add_argument("--url", default="http://127.0.0.1:8788")
    parser.add_argument("--token")
    parser.add_argument("--dry-run", action="store_true", help="print the planned tries and stop")
    args = parser.parse_args()

    roles = ROLES if args.role.upper() == "ALL" else [args.role.upper()]
    keys = [k.strip() for k in args.keys.split(",") if k.strip()]
    factors = [float(x) for x in args.factors.split(",")]
    ini = os.path.join(args.server, "config", "Custom", "FakeSkillWeights.ini")
    weights = read_weights(ini)

    if args.dry_run:
        for role in roles:
            print(f"{role}: builds {', '.join(role_builds(role))}")
            print(f"  try 0 (baseline): {weights[role]}")
            n = 0
            for key in keys:
                for factor in factors:
                    n += 1
                    print(f"  try {n}: {role}.{key} = {round(weights[role][key] * factor, 4)} (x{factor})")
            print(f"  baseline + {n} tries per pass, {min(3, args.passes)} pass(es)")
        return

    api = Api(args.url, args.token)
    reload_api = Api(args.url, args.token)
    reload_api.base = args.url.rstrip("/") + "/api/simulation/weights/"
    out = os.path.join(args.server, "log", "tuning", time.strftime("%Y%m%d-%H%M%S"))
    os.makedirs(out, exist_ok=True)
    shutil.copy(ini, os.path.join(out, "start.ini")) if os.path.exists(ini) else None
    log = open(os.path.join(out, "runs.csv"), "w", newline="", encoding="utf-8")
    writer = csv.writer(log)
    writer.writerow(["role", "pass", "key", "value", "b_rate", "battles", "kept", "matrix"])

    def run(role, label):
        write_weights(ini, weights, f"Tuning {role}: {label}")
        reload_api.call("reload", {}, post=True)
        builds = role_builds(role)
        params = {"battles": max(args.battles, math.ceil(args.min_battles / max(1, len(builds)))), "parallel": args.parallel, "level": 85, "brain": "A:B", "mirror": "1", "builds": ",".join(builds), "seed": args.seed}
        print("\n".join(api.call("start", params, post=True)["lines"][:1]), flush=True)
        folder = follow(api)
        return ab_result(folder) + (os.path.basename(folder),)

    for role in roles:
        best, n, folder = run(role, "baseline")
        writer.writerow([role, 0, "baseline", "", f"{best:.4f}", n, True, folder])
        log.flush()
        print(f"{role} baseline: B {100 * best:.1f}% ({n})", flush=True)
        for p in range(1, min(3, args.passes) + 1):
            changed = False
            for key in keys:
                for factor in factors:
                    old = weights[role][key]
                    weights[role][key] = old * factor
                    rate, n2, folder = run(role, f"{key} x{factor}")
                    kept = rate > best + half_width(best, n)
                    writer.writerow([role, p, key, round(weights[role][key], 4), f"{rate:.4f}", n2, kept, folder])
                    log.flush()
                    print(f"{role} pass {p}: {key} = {weights[role][key]:.3f}: B {100 * rate:.1f}% ({n2}){' kept' if kept else ''}", flush=True)
                    if kept:
                        best, n, changed = rate, n2, True
                        break
                    weights[role][key] = old
            if not changed:
                break
    write_weights(ini, weights, "Tuned")
    reload_api.call("reload", {}, post=True)
    shutil.copy(ini, os.path.join(out, "final.ini"))
    log.close()
    print(f"Done: {out}/final.ini (also in use on the server). Copy it to dist/game/config/Custom/FakeSkillWeights.ini to keep it.")


if __name__ == "__main__":
    main()
