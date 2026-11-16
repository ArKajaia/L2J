#!/usr/bin/env python3
"""Report on map simulations (MapSimulation, POST /api/mapdevice/simulate).

Reads runs.jsonl (and deaths.csv) of one or more batch folders under
build/server/game/log/simulation-maps/ and prints, for balancing the maps:

  - by tier and by medal count: how many maps were cleared, how far the others got,
    clear time, deaths, kill rate, damage, and what a player would have earned
    (experience, adena, item value and maps, per map and per minute);
  - tier x medals grids (share of the map slain, deaths);
  - each modifier against the maps of the same tier without it (kill rate, deaths);
  - what killed the runner, and the kinds of monster that took longest per kill.

  python3 tools/simulation/map_report.py <batch folder> [<batch folder>...]
  python3 tools/simulation/map_report.py --latest          (the newest batch)
"""
import csv
import glob
import json
import math
import os
import statistics
import sys
from collections import Counter, defaultdict

DEFAULT_ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', 'build', 'server', 'game', 'log', 'simulation-maps')


def load(folders):
    runs, deaths = [], []
    for folder in folders:
        path = os.path.join(folder, 'runs.jsonl')
        if not os.path.exists(path):
            print(f'No runs.jsonl in {folder}', file=sys.stderr)
            continue
        with open(path, encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if line:
                    runs.append(json.loads(line))
        dpath = os.path.join(folder, 'deaths.csv')
        if os.path.exists(dpath):
            with open(dpath, encoding='utf-8') as f:
                deaths.extend(csv.DictReader(f))
    return runs, deaths


def ok(run):
    return not str(run.get('result', '')).startswith('failed') and run.get('result') != 'stopped'


def cleared(run):
    return run.get('result') == 'completed'


def progress(run):
    total = max(1, run.get('monsters') or 1)
    return min(1.0, run.get('kills', 0) / total)


def per_min(value, run):
    minutes = run.get('minutes') or 0
    return value / minutes if minutes > 0 else 0


def mean(values):
    values = [v for v in values if v is not None]
    return statistics.fmean(values) if values else float('nan')


def fmt_big(x):
    if x != x:
        return '-'
    for unit, size in (('M', 1e6), ('k', 1e3)):
        if abs(x) >= size:
            return f'{x / size:.1f}{unit}'
    return f'{x:.0f}'


def group_table(runs, key, label):
    groups = defaultdict(list)
    for run in runs:
        groups[key(run)].append(run)
    header = f"{label:>6} {'maps':>4} {'clear':>6} {'slain':>6} {'min':>5} {'deaths':>6} {'kills/min':>9} {'dmg/min':>8} {'srv%':>5} {'exp/min':>8} {'exp/map':>8} {'adena/min':>9} {'value/min':>9} {'maps/map':>8}"
    print(header)
    print('-' * len(header))
    for k in sorted(groups):
        rs = groups[k]
        n = len(rs)
        dealt = sum(r.get('damageDealt', 0) for r in rs)
        servitor = sum(r.get('servitorDamage', 0) for r in rs)
        print(f"{k:>6} {n:>4} {100 * sum(map(cleared, rs)) / n:>5.0f}% {100 * mean([progress(r) for r in rs]):>5.0f}% "
              f"{mean([r['minutes'] for r in rs]):>5.1f} {mean([r['deaths'] for r in rs]):>6.1f} "
              f"{mean([per_min(r['kills'], r) for r in rs]):>9.2f} {fmt_big(mean([per_min(r.get('damageDealt', 0), r) for r in rs])):>8} "
              f"{100 * servitor / dealt if dealt else 0:>4.0f}% "
              f"{fmt_big(mean([per_min(r['exp'], r) for r in rs])):>8} {fmt_big(mean([r['exp'] for r in rs])):>8} "
              f"{fmt_big(mean([per_min(r['adena'], r) for r in rs])):>9} {fmt_big(mean([per_min(r['itemValue'], r) for r in rs])):>9} "
              f"{mean([r['maps'] for r in rs]):>8.2f}")


def grid(runs, value, title, fmt='{:>5.0f}'):
    tiers = sorted({r['tier'] for r in runs})
    medals = sorted({r['medals'] for r in runs})
    cells = defaultdict(list)
    for r in runs:
        cells[(r['tier'], r['medals'])].append(value(r))
    print(title)
    print('tier\\medals ' + ' '.join(f'{m:>5}' for m in medals))
    for t in tiers:
        row = []
        for m in medals:
            v = cells.get((t, m))
            row.append(fmt.format(mean(v)) if v else '    .')
        print(f'{t:>11} ' + ' '.join(row))


def modifier_table(runs):
    by_tier = defaultdict(list)
    for r in runs:
        by_tier[r['tier']].append(r)
    tier_kill = {t: mean([per_min(r['kills'], r) for r in rs]) for t, rs in by_tier.items()}
    tier_death = {t: mean([per_min(r['deaths'], r) * 10 for r in rs]) for t, rs in by_tier.items()}
    tier_progress = {t: mean([progress(r) for r in rs]) for t, rs in by_tier.items()}
    rows = defaultdict(lambda: {'n': 0, 'kill': [], 'death': [], 'prog': [], 'tiers': Counter()})
    for r in runs:
        for name, mtier in (r.get('modifiers') or {}).items():
            row = rows[name]
            row['n'] += 1
            row['tiers'][mtier] += 1
            row['kill'].append(per_min(r['kills'], r) / tier_kill[r['tier']] - 1 if tier_kill[r['tier']] else 0)
            row['death'].append(per_min(r['deaths'], r) * 10 - tier_death[r['tier']])
            row['prog'].append(progress(r) - tier_progress[r['tier']])
    print("Modifiers: maps that had it against the mean of their tier (positive deaths = deadlier, negative kill rate = slower).")
    print("Each map carries several modifiers, so read the big ones; fewer than ~8 maps is mostly noise.")
    print(f"{'modifier':<22} {'maps':>4} {'I/II/III':>9} {'kill rate':>9} {'deaths/10min':>12} {'slain':>7}")
    for name, row in sorted(rows.items(), key=lambda kv: -mean(kv[1]['death'])):
        t = row['tiers']
        print(f"{name:<22} {row['n']:>4} {t[1]:>3}/{t[2]}/{t[3]:<3} {100 * mean(row['kill']):>+8.0f}% {mean(row['death']):>+12.2f} {100 * mean(row['prog']):>+6.0f}%")


def killers(deaths):
    by_kind = Counter()
    by_variant = Counter()
    for d in deaths:
        kind = d.get('killer_kind') or d.get('killer') or '?'
        by_kind[(kind, d.get('tier'))] += 1
        champ = d.get('killer_champion', '0')
        variant = d.get('killer_variant') or ''
        tag = (f'champion {champ}' if champ not in ('', '0') else 'plain') + (f' {variant}' if variant else '')
        by_variant[tag] += 1
    print('What killed the runner (kind, tier):')
    for (kind, tier), n in by_kind.most_common(15):
        print(f'  {n:>4}  {kind} (T{tier})')
    print('By kind of monster: ' + ', '.join(f'{k} {n}' for k, n in by_variant.most_common()))


def slow_kinds(runs):
    stats = defaultdict(lambda: [0, 0, 0.0, 0, set()])
    for r in runs:
        for k in r.get('kinds', []):
            s = stats[(k['name'], r['tier'])]
            s[0] += k['killed']
            s[1] += k['deathsCaused']
            s[2] += k['exp']
            s[3] += 1
            s[4].add(k.get('lifted'))
    print('Kinds of monster that killed the runner most per kill (at least 3 deaths):')
    rows = [(name, tier, s) for (name, tier), s in stats.items() if s[1] >= 3]
    rows.sort(key=lambda x: -(x[2][1] / max(1, x[2][0])))
    for name, tier, s in rows[:15]:
        print(f"  T{tier:<2} {name:<32} killed {s[0]:>4}  deaths caused {s[1]:>3}  deaths/kill {s[1] / max(1, s[0]):.2f}  exp/kill {fmt_big(s[2] / max(1, s[0]))}{'  (lifted)' if True in s[4] else ''}")


def drops(runs):
    items = Counter()
    for r in runs:
        for name, count in (r.get('items') or {}).items():
            items[name] += count
    total_kills = sum(r['killsAll'] for r in runs) or 1
    print(f'Most dropped items (per 100 kills, over {total_kills} kills):')
    for name, count in items.most_common(15):
        print(f'  {100 * count / total_kills:>10.2f}  {name}')
    sources = Counter()
    tiers = Counter()
    for r in runs:
        sources.update(r.get('mapsBySource') or {})
        for t, n in (r.get('mapsByTier') or {}).items():
            tiers[int(t)] += n
    print('Maps dropped by source: ' + ', '.join(f'{k} {v}' for k, v in sources.most_common()) + '; by tier: ' + ', '.join(f'T{k} {tiers[k]}' for k in sorted(tiers)))


def main(argv):
    folders = [a for a in argv if not a.startswith('--')]
    if '--latest' in argv or not folders:
        candidates = sorted(glob.glob(os.path.join(DEFAULT_ROOT, 'maps-*')))
        if not candidates:
            print('No batch found under ' + os.path.abspath(DEFAULT_ROOT))
            return 1
        folders = [candidates[-1]]
    runs, deaths = load(folders)
    runs = [r for r in runs if ok(r)]
    if not runs:
        print('No finished map.')
        return 1
    builds = Counter(r['build'] for r in runs)
    results = Counter(r['result'] for r in runs)
    print(f"{len(runs)} maps from {', '.join(os.path.basename(os.path.normpath(f)) for f in folders)}; builds {dict(builds)}; results {dict(results)}")
    print()
    print('By tier (all medal counts):')
    group_table(runs, lambda r: r['tier'], 'tier')
    print()
    print('By medals (all tiers):')
    group_table(runs, lambda r: r['medals'], 'medals')
    print()
    grid(runs, lambda r: 100 * progress(r), 'Share of the map slain (%), tier x medals:')
    print()
    grid(runs, lambda r: r['deaths'], 'Deaths, tier x medals:', '{:>5.1f}')
    print()
    grid(runs, lambda r: r['minutes'] if cleared(r) else float('nan'), 'Minutes to clear (cleared maps only), tier x medals:', '{:>5.1f}')
    print()
    modifier_table(runs)
    print()
    killers(deaths)
    print()
    slow_kinds(runs)
    print()
    drops(runs)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
