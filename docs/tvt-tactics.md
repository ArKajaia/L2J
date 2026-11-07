# Team vs Team: fake player tactics against a party with a healer

How the fake players of a Team vs Team match pick their targets and where their archers and mages stand, how that was measured, and what was found. The code is `ai/FakeTvtTactics` (the knobs), `ai/FakePlayerPvpAI` and `ai/FakePartyBrain` (brain A, which plays them) and `managers/TeamVsTeamBatch` (the measuring). TvT fake players only run brain A, so brain B (`FakePlayerPvpAIExperimental`, see `docs/fake-player-simulation-issues.md`) is untouched; outside Team vs Team every fake player plays `FakeTvtTactics.LEGACY`, which is exactly brain A as it was.

## The knobs

`TvTFakeTactics` in `config/Custom/TeamVsTeam.ini` (default `best`). A preset, optionally changed with `key=value` pairs after a colon (`best:healer=0.3`), or only pairs on top of `legacy`.

| Key | What it does | legacy |
|---|---|---|
| `healer` | How much better an enemy healer is as a target for a melee or buffer (on a score where 1 is full HP; lower is better) | 0.6 |
| `assassin` | The same for a dagger | 1.2 |
| `nukerHealer` | The same for a nuker or archer. One that weighs healers less than `healer` doesn't follow a party call on a healer | 0.6 |
| `nukerDps` | How much better an enemy damage dealer (not a tank, healer or buffer) is for a nuker or archer | 0 |
| `calls` | Whether a member calls its party onto an enemy healer (and onto the healer of a low enemy) | 1 |
| `standoff` | Archers and mages stay back: a target they'd have to walk up to counts the walk twice and the enemies on the way (+0.8 each, as brain B's #35), they don't walk past their party's front (the closest tank or melee) for up to `wait` ms, and a mage between casts holds at the edge of its reach (its attack skills' reach - 50, up to 900) instead of 600 from its target, stepping back when it is well inside that and ahead of its front | 0 |
| `wait` | With `standoff`: how long it waits behind the front before it walks up anyway | 0 (4000 with `standoff`) |

Presets: `legacy`, `focus` (healer ×2), `ignore` (nobody hunts healers, no healer calls, nukers +0.3 on damage dealers), `dps` (nukers and archers on damage dealers), `split` (daggers on healers, nukers and archers on damage dealers), `standoff`, `best` (the default, see the end).

## How it was measured

`//tvt batch [count] [level=] [a=] [b=] [size=] [minutes=] [map=]` (or `POST /api/tvt/batch` with the same names, `GET /api/tvt/batches`) starts `count` simulated matches at once, each in an instance of its own, tactics `a` against tactics `b`:

- Both teams of a match get the same builds at the same levels in the same order (mirrored), only their personalities are drawn apart.
- The tactics change sides every match, and every two matches play the same map.
- While a match fights, the batch counts for each team the damage each of its roles dealt to each role of the other, whom each member targets (once a second), its deaths by role, when its first healer died, and where its archers and mages stand (how far from the closest enemy, and how often closer to it than their party's closest melee: "ahead of the front").
- Output in `log/simulation-tvt/batch-<time>/`: `results.csv` (a line per team per match), `roles.csv` (damage and target time by attacker role and target role), `summary.txt`.
- `python tools/simulation/tvt_report.py <batch folders...>` puts batches together: head to head (wins with a 95% Wilson interval, and the kill share with its 95% interval), healer focus, deaths by role, and each role's damage and target time by target role.

All runs below: 10 matches at once, 30 against 30 (4 parties of tank, archer, healer, melee, nuker, buffer, melee, and a party of tank and archer), 5 minute fights, levels 79-81 unless said otherwise, on Ruins of Despair (the only map of the datapack set up for TvT). One batch of 10 matches is 600 fake players; the game server used about 60% of one core.

"Healer focus" is the share of a team's damage that went into enemy healers. Healers are 4 of 30 (13%) of a team.

## Round 1: healer priority, nukers on damage dealers, standoff (each against legacy)

| Tactics (vs legacy) | Wins | Kill share (95%) | Kills | Healer focus (dmg) | Healer focus (target time) | Own ranged deaths (archer + nuker) | Damage dealt |
|---|---|---|---|---|---|---|---|
| legacy (calibration, vs itself) | 5.5/10 | 49.6% (43-56) | 78 - 80 | 24% | 18% | 27 | 2.6M |
| focus: healer 1.2, dagger 2.4 | 0/10 | 36.0% (31-41) | 56 - 98 | 33% | 26% | 35 | 2.1M |
| healer 2.0, dagger 3.0 | 0/10 | 32.7% (29-37) | 49 - 102 | 36% | 28% | 36 | 1.8M |
| healer 0.3, dagger 0.6 | 6/10 | 55.1% (48-62) | 88 - 70 | 20% | 15% | 23 | 3.0M |
| ignore: healer 0, no healer calls, nukers +0.3 on damage dealers | 10/10 | 63.1% (59-68) | 97 - 56 | 8% | 6% | 17 | 3.4M |
| dps: nukers and archers healer 0, +0.4 on damage dealers | 9/10 | 65.5% (59-72) | 103 - 52 | 16% | 14% | 13 | 3.3M |
| split: dps, and daggers 1.6 on healers | 9/10 | 67.2% (62-72) | 117 - 56 | 15% | 16% | 13 | 3.7M |
| standoff | 7/10 | 53.6% (47-60) | 85 - 73 | 23% | 17% | 23 | 2.6M |

Healer focus and winning go the opposite way: the more a team hunts healers, the more it loses, and the trend is monotonic from 2.0 down to 0.

### Why hunting the healer loses here

- **It gets the hunters killed.** A party's healer stands behind its front (#30), so reaching it means walking past the enemy tanks and melee. With legacy tactics the archers and nukers spend about 30% and 28% of their time on an enemy healer; for the nukers only 18% of their damage lands there (the rest of that time goes in walking and lining up). They die 17.8 + 13.5 times a match. Pointed at the damage dealers instead (`dps`, `split`) they die 6.5 + 6.5 times.
- **It wastes damage.** Teams that ignored healers dealt 60-80% more damage in total than legacy in the same matches (3.3-3.7M against 2.0-2.1M), because what they shoot is within reach.
- **Losing the healer early doesn't decide the fight.** Against `ignore`, legacy's first healer died 77 s into the fight on average and `ignore`'s after 29 s, and `ignore` still won all 10. Healers heal themselves and each other through focus fire; a damage dealer has no such answer, and while the healer is chased the enemy's damage dealers keep hitting.
- **The score counts kills.** Every kill is worth the same, and a damage dealer killed is also damage the team stops taking.

So the best strategy against a party with a healer, for this AI, is to **kill its damage dealers first**, nukers and archers above all, and leave the healer to whoever happens to be next to it.

## Round 2: refining around split

| A | B | B's wins | B's kill share | Kills (B - A) | Notes |
|---|---|---|---|---|---|
| ignore | split | 4/10 | 43.9% | 68 - 86 | Daggers hunting healers costs too, head to head |
| split | split + standoff (wait 4 s) | 4.5/10 | 47.5% | 77 - 85 | Ranged ahead of the front 30% → 23%, 425 → 478 from the closest enemy, but less damage (3.0M → 2.8M) |
| split | split, melee healer 0, no calls | 6/10 | 48.9% | 71 - 74 | Neutral |
| split | split, nukers +0.8 on damage dealers | 4/10 | 44.0% | 68 - 86 | Too much; 0.3-0.4 is enough |
| legacy | split + standoff, **levels 61-65** | 10/10 | 79.0% | 136 - 36 | The gain is bigger at mid levels |

## Round 3: standing back, on top of ignoring healers

| A | B | B's wins | B's kill share | Kills (B - A) | B's ranged: distance to the closest enemy, ahead of the front |
|---|---|---|---|---|---|
| ignore | ignore + standoff, wait 4 s | 4.5/10 | 48.6% | 63 - 67 | 465 (A 425), 24% (A 28%) |
| ignore | ignore + standoff, no wait | 5/10 | 50.2% | 71 - 70 | 478 (A 419), 25% (A 26%) |
| ignore | split (second sample) | 4/10 | 48.5% | 67 - 71 | |

Standing back does what it should (the archers and mages stand 40-60 further from the closest enemy and less often ahead of their front), and against legacy it wins on its own (round 1). On top of good targeting it is neutral in kills: once the nukers shoot the damage dealers in front of them, they rarely need to walk anyway. Waiting for the front costs a little damage, so the default doesn't wait.

`ignore` against `split`, both samples: 12/20 for `ignore`, 157 - 134 kills. Daggers sent onto the healer don't pay either.

## Confirmation, and the default

`best`, first without the dagger priority (`ignore:standoff=1,wait=0`, `best-a0` in `docs/tvt-tactics-data/`), against legacy, one batch per bracket:

| Levels | Wins | Kill share | Kills | Healer focus (best / legacy) | Ranged ahead of the front (best / legacy) |
|---|---|---|---|---|---|
| 79-81 | 10/10 | 73.7% | 114 - 41 | 8% / 29% | 21% / 30% |
| 61-65 | 10/10 | 76.9% | 128 - 38 | 9% / 27% | 24% / 30% |
| 46-50 | 10/10 | 73.2% | 131 - 48 | 9% / 27% | 22% / 29% |
| All 30 | 30/30 (95%: 89-100) | 74.7% (71-78) | 124 - 42 | | |

Deaths by role per team per match over those 30: legacy lost 28.8 archers, 20.3 nukers and 39.2 melee, `best` 6.4, 4.2 and 12.5; legacy's healers died 9.2 times, `best`'s 11.3, and `best`'s first healer died sooner (33 s against 61 s). Its healers die a little more; everyone else lives, and that is what the score counts.

Then a small healer priority for the daggers only (`assassin=0.6`) against that, 30 matches at levels 79-81 and 61-65: the daggers' variant won 68% (95%: 50-82) with a kill share of 52% (46-58), and its own healers died 4.4 times a match against 6.3. A lean rather than a clear win, but it agrees with the first batch and it is the safer choice with real players in the match: a healer nobody ever comes for heals freely. It is the default:

**`best` = `ignore:assassin=0.6,standoff=1,wait=0`**: nobody calls the party onto a healer; melee and nukers don't prefer healers; daggers prefer them a little (0.6, half the legacy 1.2); nukers and archers prefer the enemy damage dealers (+0.3); archers and mages weigh the walk to a target twice and the enemies on the way, and a mage between casts holds at the edge of its reach.

The shipped preset (`best-a0.6` in the data), read from `TvTFakeTactics = best` on a rebuilt server, against legacy at levels 79-81: **10/10, 115 - 42 kills (73.0%, 95%: 68-78)**, healer focus 10% against 25%, archers and nukers lost 6.0 + 3.8 against 27.9 + 19.6.

## Limits

- One map: Ruins of Despair is the only map of the datapack set up for TvT. A map with more cover or narrower lanes may change how much standing back is worth.
- Fake players against fake players. Real players on the other team play differently (a real healer runs, a real party peels); the dagger priority is kept partly for that.
- Both teams get the same builds and levels, but not the same personalities (knowledge, teamplay...), which adds noise; 10 matches decide a large effect (healer focus) but not a small one (standoff on top of good targeting).
- Fights were 5 minutes rather than the event's 20, to run more matches; 20 minute matches weren't measured.
- Rounds 1 and 2 are summaries: their raw CSVs went with a rebuild (a full `ant` build deletes `build/server`). Round 3, the dagger test and the confirmation are in `docs/tvt-tactics-data/` (`results.csv`, `summary.txt` and the tactics of each batch; `python tools/simulation/tvt_report.py docs/tvt-tactics-data/batch-*`).

## Reproducing

```bash
# On a test server with SimulationWebOpenAccess = True (config/Custom/FakeSimulation.ini), or as a GM: //tvt batch 10 level=80 a=legacy b=best size=30 minutes=5
curl -X POST http://127.0.0.1:8788/api/tvt/batch -d 'count=10&level=80&a=legacy&b=best&size=30&minutes=5'
curl -X POST http://127.0.0.1:8788/api/tvt/batch --data-urlencode 'a=legacy' --data-urlencode 'b=legacy:healer=1.2,assassin=2.4,nukerHealer=1.2,name=focus'
python tools/simulation/tvt_report.py build/server/game/log/simulation-tvt/batch-*
```
