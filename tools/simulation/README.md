# Simulation tools

`ab_test.py` compares the AI brains; `matrix.py` turns a class-vs-class matrix into a win rate table.

## A/B tests

`ab_test.py` compares the two fake player AI brains (A = current, B = experimental) through the simulation web page's API. See `docs/fake-player-simulation-issues.md` for what the brains are and how a change is tested.

Needs Python 3 (standard library only), a running game server, at least 2 saved simulation points (`//SetSimulationPoint 1`, `//SetSimulationPoint 2`), and either `SimulationWebOpenAccess = True` in `config/Custom/FakeSimulation.ini` (test servers only) or a GM token: the `admin=...` part of a `//SimulationWeb` link, passed with `--token`.

```bash
# Calibration: A against A. Both should win about 50%; "By side" shows how much the points favor one team.
python tools/simulation/ab_test.py --brains A:A --swap --levels 40,60,80 --rounds 10

# The test: B against A, every level bracket.
python tools/simulation/ab_test.py --brains A:B --swap --levels 30,40,50,60,70,80 --rounds 10

# Report again on simulations that already ran (folder names from the web page or log/simulation).
python tools/simulation/ab_test.py --report 20261025-133126-393834156 20261025-134117-264484967
```

With `--swap` both teams get the same builds, the brains change sides every round, and a new composition comes every 2 rounds, so only the brain differs. B is better only if its 95% interval stays above 50%. About 100 rounds are needed before a 60% win rate is clearly above 50%.

## A/B arena: party battles in instances, many at once

`//Simulate ab [battles per level, 20] [levels=40,61,80] [parallel=100] [size=5] [brains=A:B] [time=300] [distance=1500] [seed=<n>]` (or `ab_test.py --parallel`) runs the A/B test as party battles that all fight at the same field, each in an instance of its own, `parallel` at once. Both teams of a battle get the same builds, gear and personalities, and every composition is fought twice with the brains on swapped sides, so only the brain differs. The compositions go through the levels in turn, so an arena stopped early covers every level about evenly.

```bash
# 100 battles per level at 40, 61 and 80, 100 at once (from the web with no GM: at the Cruma Marshlands field).
python tools/simulation/ab_test.py --parallel 100 --levels 40,61,80 --rounds 100
```

Output goes to `log/simulation-arena/arena-<time>/`: `results.csv` (one line per battle: level, composition, side, both brains, winner and its brain, reason, duration, decided-at, HP left, first death), `summary.txt` (wins by brain with their 95% interval, per level, by side; rewritten every 20 battles and at the end), and `battles/` with the logs of the newest 300 battles. `//SimulationStatus` and `//SimulationStop` (or `/api/simulation/arena/status`, `/arena/stop`) follow and stop it.

To watch the battles:
- **In game:** `//SimulationArena` opens a panel with the arena's progress, its wins so far and every battle running now. Each battle has a **Watch** button that takes the GM into its instance. The panel also has Next, Leave, Refresh and Stop. When the battle being watched ends, the GM moves on to the next one instead of being sent to town. The fake players leave the GM alone.
- **On the web page:** the **Arena** button shows the same things: progress, the summary so far, the running battles with each team's brain, members alive and HP, and the arenas run so far with their summaries. It can also start an arena. Watch buttons need the page opened through `//SimulationWeb` (with the GM online), since they move the character. The API is `/api/simulation/arena/battles`, `/arena/list`, `/arena/watch` (`run`, blank for the next battle) and `/arena/leave`.

`party_report.py <arena folder>` reports how the parties of an arena fight, per brain, from the battles it kept. It covers the healer race (who is hit first, who dies first and when, healers alive at the end), where each role stands at first contact, the team's spread, where the healer and the shooter stand at the first shot on the healer, the focus calls, and brain B's party-brain features (stances, cover, answers to shooters):

```bash
python tools/simulation/party_report.py build/server/game/log/simulation-arena/arena-20261028-191653
```

## Class-vs-class matrix

`//Simulate matrix 100` (in game; the number is the battles per pair, `//SimulationMatrix start battles=100` is the same) fights every pair of builds in 1v1 battles at the place where the GM stands, many at once. Every battle gets an instance of its own, so all of them share the same spot without seeing or hitting each other. The two fighters spawn `distance` apart along the way the GM faces. The GM teleport menu has **Simulation Field (Cruma Marshlands)** (20480, 104448, -3700): the flattest open field found in the geodata, ±50 in height over 3 km, with nothing blocking movement and no zone (water, peace, siege, boss...) on it.

```
//Simulate matrix [battles per pair, 20] [parallel=100] [level=85] [time=300] [distance=1000] [brain=A] [builds=Adventurer,Titan,...] [seed=<n>]
//SimulationMatrix start [battles=20] [parallel=100] [level=85] [time=300] [distance=1000] [brain=A] [builds=Adventurer,Titan,...] [seed=<n>]
//SimulationMatrix status | stop | resume [name] [parallel=<n>] | watch | leave | builds
//Simulate matrix 10 builds=all,Sagittarius_Heavy focus=Sagittarius_Heavy,Sagittarius   (a build tried against every build)
//Simulate matrix 40 brain=A:B mirror                                                    (an A/B test: every build against itself)
```

- By default every build of `data/FakePlayerPvp.xml` takes part, the gear variants aside: 38 builds, 703 pairs. 20 battles per pair is 14,060 battles; with 100 at once that is about 2 hours (a battle is 10 s of preparation, the fight, and 2 s of cleanup), with 1000 at once about 20 minutes. Memory: with a 12 GB heap the game server was killed on a 16 GB machine, twice at 1000 at once and once at 600 (the ZGC heap counts in full). A 10 GB heap (`-Xmx10g -XX:SoftMaxHeapSize=8g`) and 500 at once is what ran through; give it more memory for more battles at once. A matrix that dies goes on with `resume`.
- The battles go one round of every pair at a time, so a matrix stopped early still covers every pair evenly. The two builds of a pair change spots every battle.
- `watch` takes the GM into the instance of a running battle (again for the next one), `leave` back out. The fake players leave the GM alone.
- Output goes to `log/simulation-matrix/matrix-<time>/`: `results.csv` gets one line per battle (`battle,a,b,round,winner,reason,duration_ms,a_hp,b_hp,a_spot`, with `winner` set to `a`, `b` or `draw`). `matrix.csv` (the win rate of each row against each column, in percent, a draw counting half) and `matrix.txt` (the builds ranked) are rewritten every 50 battles and at the end. Battle logs are in `battles/`, and only the newest `SimulationMatrixKeepLogs` (300 by default, in `FakeSimulation.ini`) are kept.
- A matrix that was stopped or cut by a restart goes on with `resume`, from its `results.csv`. Battles cut by `stop` are played again.

`matrix.py` writes `matrix.csv` and a heat map page, `matrix.html`, from a matrix folder. It can also start, resume or follow a matrix through the web API (`SimulationWebOpenAccess` or `--token`; started from the web without a GM, a matrix fights at the Cruma Marshlands field):

```bash
python tools/simulation/matrix.py log/simulation-matrix/matrix-20261025-213156
python tools/simulation/matrix.py --start --battles 20 --parallel 100 --level 85
python tools/simulation/matrix.py --resume
```

## Race war: army against army, the dead coming back

`//Simulate war [races=all] [repeats=1] [size=50] [level=85] [parallel=2] [time=600] [respawn=10] [distance=2000] [seed=<n>]` (or `POST /api/simulation/war/start` with the same names; `/war/status`, `/war/stop`) fights an army of every race against an army of every other race, each battle in an instance of its own at the GM's place (from the web without a GM: the Cruma Marshlands field).

- An army is `size` fake players of one race in parties of up to 9, each party filled with the slots of a simulation party (tank, archer, healer, melee, nuker, buffer...) from its race's own builds. A race with no class for a slot takes the closest one it has, and each support class only once per party: an orc party gets one Doomcryer and no healer, Kamael parties have no support at all, and a dwarf army is all Fortune Seekers and Maestros. The parties of an army spare each other (single targets and areas), but each party's healers only look after their own party.
- A dead fighter comes back at its army's base after `respawn` seconds, with full HP, MP and CP and its buffs again (unless a healer resurrected it first). A battle lasts `time` seconds and the army with more kills wins it.
- With `repeats=2` every pair is fought twice, the races changing sides.
- Output goes to `log/simulation-war/war-<time>/`: `results.csv` (one line per battle: races, kills of each army, winner, damage and healing), `members.csv` (one line per fighter per battle: build, role, kills, deaths, damage, healing), `summary.txt` (standings, the race-vs-race matrix of kill shares, and every build's kills, deaths, damage and healing per battle; rewritten after every battle) and `battles/` with the full logs of each battle.
