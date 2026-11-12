# The class clinic: making one class's AI stronger against every kind of opponent

A repeatable way to take one fake player build, fight it against every other build, find from the battle logs what its AI does wrong against each kind of opponent (tank, melee, dagger, archer, nuker, healer, buffer), fix it, and prove the fix. One build at a time. The first case is the Female Soul Hound: `docs/class-clinic/female-soul-hound.md`. Read it once before doing a class: it shows every step on real data.

It is for the **micro of one class** in a duel: which skill, when, from where. Party tactics (focus, healers, formation) are tested with the arena and the TvT batches instead (`docs/fake-player-simulation-issues.md`, `docs/tvt-tactics.md`).

## What you need

- A game server: `tools/simulation/local_server.sh` (about 5 minutes for a build and start; `--no-build` to restart). No client, no login server.
- `tools/simulation/class_clinic.py` (run, report, timeline, compare) and `tools/simulation/skill_cards.py` (what each skill of a build does). Python 3, standard library only.
- Time: a full run is 1,170 battles and takes about 13 minutes (the healer fights go to the time limit); a run against one kind of opponent takes 3 to 5. A rebuild and restart takes 5.
- Disk: a full run keeps about 300 MB of battle logs in `../build/server/game/log/simulation-matrix/`. Delete old runs you no longer compare against.
- **Commit and push after every proven batch.** Cloud sessions restart; the working tree and the logs usually survive, the server process never does, and a new container has neither. Copy the numbers you need into the case file as you go.

## The loop

### 1. Know the class

- `python3 tools/simulation/skill_cards.py "<Build>"`: every skill of the build with its cast time, reuse, range, power, land rate and effects, its combos, and notes on weak or overlapping skills. Read the effects: they hold what the name doesn't say (Curse of Divinity does +30% damage per buff on the target; Final Form is a melee form; Painkiller ends on any action).
- How good players play it in retail High Five: search as `CLAUDE.md` says ("Research before changing game content") and write down five to ten lines: what it opens with, what it does against a fighter, an archer, a mage, a healer, and which skills define it. This is the yardstick the logs are held against.
- The build itself: its `<build>` in `dist/game/data/FakePlayerPvp.xml` (skill lists in order of preference, combos, peel, emergency, role, gear kit, buff list).

### 2. Baseline

```bash
python3 tools/simulation/class_clinic.py run "<Build>" --battles 30 --seed 4040
```

It fights the build against the 39 others at level 85, 30 battles each, every battle in its own instance, keeps every log, and writes `clinic.md` into the run's folder (printed at the end). **Keep the folder name: it is the "before" of every comparison.** Always use the same seed, so the same fighters (gear, personality) meet again in later runs.

### 3. Read the report, then the fights

`python3 tools/simulation/class_clinic.py report <folder> [--group nuker]` prints it again, whole or for one kind of opponent. In this order:

1. **Scorecard**: the win rate per kind of opponent with its 95% interval. The kinds under 50% are where the work is. "By opponent" shows whether a kind is lost to all of its builds or to one.
2. **Findings to check**: what the numbers flag. They are questions, not conclusions.
3. **Its skills**: casts per battle, aborted, landed, damage per cast and per second of casting, and the casts per kind of opponent. Look for: a strong skill cast seldom, a weak one cast often, a debuff that never lands cast every fight, a skill of the kit never cast.
4. **Self skills: the fights they are cast in**: a buff or a transformation that goes with losing is the first thing to read fights for.
5. **What the enemy does to it** and **where its time goes**: what kills it, what holds it, how long an enemy stands on it, how much it moves instead of acting.
6. **Its own build**: the same class with other keystones, gear or player skill. A gap there is a build problem (passive tree, gear), not an AI one: write it down, don't tune the AI around it.
7. **Battles to read**: `python3 tools/simulation/class_clinic.py timeline <battle folder>` prints one fight second by second, with what each side decided and why. **Read at least two losses per weak kind before you believe a finding.** The Soul Hound's worst fault (transforming into a melee form while ahead) was plain in the first timeline and invisible in the win rates.

Traps when reading:

- Fake players don't pay MP for skills. With the Mind Over Matter keystone their MP is a second health bar; "MP empty" is not "can't cast".
- "Between" time (about a quarter of a caster's fight) is the short pause between two actions that every build has (`SKILL_CHAIN_*`). "Waiting" is the one to look at.
- An emergency skill is cast by the side that is losing: its low win rate says little. A buff or transformation with a low win rate says a lot.
- Debuffs learned at 74 land 10 to 30% on a level 85 target in full buffs. That is the server's formula, not a bug; what matters is how often the AI spends a cast on them and whether it walks into danger to do so.
- A 1v1 between fake players can't judge everything: buff steals only work on real players (Steal Divinity does nothing to a fake player), and nobody heals or peels for anyone.
- Stats differ a lot between two fighters of the same build (gear level, enchant, keystones). Read several fights.

### 4. Write the diagnosis

In the case file (`docs/class-clinic/<build>.md`, copy the layout of the Soul Hound's): one row per fault with its **evidence** (numbers from the report, a battle folder), its **cause** (the line of code or data behind it) and its **kind**, which decides where the fix goes:

| Kind | Example | Where the fix goes |
|---|---|---|
| Build data | The attack list ranks a weak skill above a strong one; a combo opens with a silence on a warrior; the peel list holds a skill that peels nothing | The build in `dist/game/data/FakePlayerPvp.xml`. Cheapest and safest: only that build changes. Do these first |
| Class AI | Final Form for a caster; a class skill used at the wrong moment | `FakePlayerPvpAI`, by role or by what the skill does (its effects), never by class name. The same change in `FakePlayerPvpAIExperimental`, so the two brains don't drift apart |
| General AI | The damage estimate misses an effect; the land chance guess is far off; mages don't kite a slower melee | The same files, but it changes other builds too: it needs a guard run (step 6) |
| Harness | A log line that misleads (Blink logged as aborted) | `managers/simulation/`, both sides alike, no test needed |
| Not AI | The class is out-statted (M. Atk 6,000 against 22,000) | Write it in the case file under "not fixed here". Balance changes go through `docs/class-balance-85.md`, not through the clinic |

Check the Java level before writing code (`CLAUDE.md`, "Java versions"): `java/` is Java 25, the scripts under `dist/game/data/scripts/` are Java 8.

### 5. Fix one batch, then prove it

A batch is a few changes that belong together. Rebuild, restart, run again with the same seed, and compare with the baseline:

```bash
tools/simulation/local_server.sh
python3 tools/simulation/class_clinic.py run "<Build>" --battles 30 --seed 4040
python3 tools/simulation/class_clinic.py compare <baseline folder> <new folder>
```

`compare` gives the change per kind of opponent with its 95% interval, per opponent, and how the skill use moved. Rules:

- A kind is **better** only when the interval of its difference is above zero, **worse** when it is below. Anything else is "no clear change", however good the number looks.
- A batch is kept when the overall difference is clearly better and no kind is clearly worse. A batch that changes nothing is taken out again, not left in "because it can't hurt".
- Check in "Skill use" that the change did what you meant (the skill you promoted is cast more, the one you removed is gone). A win rate that moved for another reason is luck.
- How small a change a run can show, at 30 battles per opponent (a 95% interval of about plus or minus this many points):

| Kind | Battles a side | Shows a change of about |
|---|---|---|
| all | 1,170 | 4 points |
| melee (12 builds) | 360 | 7 |
| nuker (8) | 240 | 9 |
| buffer (5) | 150 | 11 |
| tank, archer (4) | 120 | 13 |
| dagger, healer (3) | 90 | 15 |

  For a small kind, run it alone with more battles on both sides: `class_clinic.py run "<Build>" --against archer --battles 80 --seed 4040` (and the same on the baseline code, since a run against one kind is a different set of battles than the full run's).
- While working on one kind, `--against <kind>` gives an answer in a few minutes. The batch still needs the full run before it is kept: a fix against archers can cost against melee.

Then read the new report like the first one and go round again. Stop when the kinds still under 50% are "not AI" (out-statted), or when two batches in a row show no clear change.

### 6. Guard: nobody else got worse

- A change to one build's data, or to code that only that build's skills reach, needs no guard.
- A change to shared code (anything another build can reach: the scorer, kiting, a rule by role) needs one: list the builds it can reach, and run the clinic for two of them before and after (`--battles 15` is enough to see damage). If it reaches most builds, compare two full matrices instead (`tools/simulation/matrix.py --start --battles 10 --seed 4040` on both, then `matrix_compare.py`).
- A change that is clearly general and promising (it would help every caster, every archer...) is better tested the way `docs/fake-player-simulation-issues.md` describes: in brain B only, against brain A, in a mirror matrix. Note it under "General findings" below instead of slipping it in with a class.

### 7. Record

- The case file: baseline, diagnosis table, each batch with its comparison table, what was not fixed and why, and the run folders.
- This document: a line in "Classes done", and anything the next class should know under "Lessons so far" or "General findings".
- `docs/class-balance-85.md` if you found a balance problem.
- Commit (the code, the data and the case file together) and push.

## What the report's numbers mean

| Number | Meaning |
|---|---|
| Win % (95%) | Wins plus half the draws, over battles, with its Wilson interval |
| Fight s (won / lost) | Median length of the fights it won and lost. Short losses: it is burst down. Long losses: it can't finish |
| HP left when it wins / enemy HP left when it loses | Medians of HP and CP left. A kind lost with the enemy at 20% is close: small gains flip it. One lost with the enemy at 70% needs another plan, not tuning |
| Damage /s dealt / taken | Over the time it was alive in the fight |
| Enemy within 200 | Share of the fight an enemy stood within 200 of it. For an archer or mage, time a melee spent on it |
| Disabled, rooted | Share of the fight it could not act, or not move |
| Waiting | Standing on a hold or wait decision with no cast, attack or move |
| Hits first | Share of the fights in which it dealt the first damage |
| Landed | For a skill with a debuff: landed against resisted |
| Per cast second | Damage divided by the time spent casting the skill: which skills pay |

The kinds of opponent are fixed in `class_clinic.py` (`GROUPS`); a build that is missing there takes its kind from its role.

## Lessons so far

Faults of one class that other classes may share. Check for them first.

- **A transformation or stance that changes how the class fights** (Final Form): does the AI still have its damage afterwards? The report's "Self skills" table shows it at once.
- **The order of the attack list.** A skill is worth at least its place in the list allows (`LIST_RANK_FACTOR`), so a weak skill listed early is cast a lot. Order the list by "Per cast second" from the report, not by habit.
- **Damage the estimate doesn't see.** `estimateDamage` knows power, M. Atk and P. Atk. A skill whose effect multiplies its damage (by the target's buffs, by its HP, by souls) is undervalued until the estimate learns its effect.
- **Debuffs that don't fit the target.** A silence on a warrior, a root on an archer. Combos take `mageTarget="true"` and `disabledTarget="true"` on a step; `isWastedSilence` covers pure silences (kept for a tank under a defensive stance and anyone casting a spell).
- **Standing just outside a skill's reach.** A mage holds 600 from its target, which leaves it at about 660 to 675: its skills with a reach of 600 are "out of reach" and it falls back on whatever reaches. The build attribute `range` moves where it stands.
- **A follow-up skill that only exists in a combo.** If it needs a debuff the first skill puts on (Soul Vortex Extinction), put it in the attack list too: its own condition keeps it off targets without the debuff.

## General findings (not yet done: they change every class)

These came out of a class's clinic but reach beyond it. Each needs brain B and a mirror matrix, or a guard run.

| Finding | Evidence | Idea |
|---|---|---|
| The land chance the scorer assumes is the skill's base rate (80% for Voice Bind), far from what lands (10%) | Soul Hound: Voice Bind 10%, Shadow Bind 10%, Death Mark 22%, Surrender to Dark 24% over thousands of tries | `FightRead.landChance`: start from the server's formula without the roll (level difference, the target's stat and resists), blurred by the fake player's knowledge |
| The list floor (`LIST_RANK_FACTOR`) lifts plain damage skills whose estimate is exact | Abyssal Blaze (power 46) valued like a skill of power 90 | Apply the floor only to skills with effects the estimate can't see (damage over time, curses, prophecies) |
| A mage faster than the melee on it doesn't use its speed | Soul Hound speed 250 against 170: an enemy within 200 of it 36% of the fight against melee, one kite step every 3 to 5 s | Kite in earnest when clearly faster or the enemy is slowed: run until out of reach, cast until it is nearly back |
| Painkiller-type invincibility that ends on any action is cast and broken by the next cast | Cast in 241 fights, 10% won; the enemy waited 1 to 2 s | After casting it: move out of reach (melee) or wait while the enemy's burst buffs run, instead of casting |
| Buff steals do nothing against fake players | `StealAbnormal` only works on a player: Steal Divinity is never worth a cast in a simulation | Let it work on fake players, then value it by what it would take |

## Classes done

| Build | Case file | Before | After | Date |
|---|---|---|---|---|
| Female Soul Hound | `docs/class-clinic/female-soul-hound.md` | 50.6% | see the case file | 2026-11-12 |
