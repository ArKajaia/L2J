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

## Class-vs-class matrix

`//SimulationMatrix start` (in game) fights every pair of builds in 1v1 battles at the place where the GM stands, many at once. Every battle gets an instance of its own, so all of them share the same spot without seeing or hitting each other. The two fighters spawn `distance` apart along the way the GM faces. The GM teleport menu has **Simulation Field (Cruma Marshlands)** (20480, 104448, -3700): the flattest open field found in the geodata, ±50 in height over 3 km, with nothing blocking movement and no zone (water, peace, siege, boss...) on it.

```
//SimulationMatrix start [battles=20] [parallel=100] [level=85] [time=300] [distance=1000] [brain=A] [builds=Adventurer,Titan,...] [seed=<n>]
//SimulationMatrix status | stop | resume [name] [parallel=<n>] | watch | leave | builds
```

- By default every build of `data/FakePlayerPvp.xml` takes part, the gear variants aside: 38 builds, 703 pairs. 20 battles per pair is 14,060 battles; with 100 at once that is about 2 hours (a battle is 10 s of preparation, the fight, and 2 s of cleanup).
- The battles go one round of every pair at a time, so a matrix stopped early still covers every pair evenly. The two builds of a pair change spots every battle.
- `watch` takes the GM into the instance of a running battle (again for the next one), `leave` back out. The fake players leave the GM alone.
- Output goes to `log/simulation-matrix/matrix-<time>/`: `results.csv` gets one line per battle (`battle,a,b,round,winner,reason,duration_ms,a_hp,b_hp,a_spot`, with `winner` set to `a`, `b` or `draw`). `matrix.csv` (the win rate of each row against each column, in percent, a draw counting half) and `matrix.txt` (the builds ranked) are rewritten every 50 battles and at the end. Battle logs are in `battles/`, and only the newest 300 are kept.
- A matrix that was stopped or cut by a restart goes on with `resume`, from its `results.csv`. Battles cut by `stop` are played again.

`matrix.py` writes `matrix.csv` and a heat map page, `matrix.html`, from a matrix folder. It can also start, resume or follow a matrix through the web API (`SimulationWebOpenAccess` or `--token`; started from the web without a GM, a matrix fights at the Cruma Marshlands field):

```bash
python tools/simulation/matrix.py log/simulation-matrix/matrix-20261025-213156
python tools/simulation/matrix.py --start --battles 20 --parallel 100 --level 85
python tools/simulation/matrix.py --resume
```
