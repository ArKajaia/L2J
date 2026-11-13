# Class clinic: the archers (Ghost Sentinel, Moonlight Sentinel)

Taken through `docs/class-clinic.md` on 2026-11-13. Level 85, brain A, seed 4040, 30 battles against each of the 39 other builds (1,170 a run). The four archers (Sagittarius, Moonlight Sentinel, Ghost Sentinel, Trickster) share their AI (role ARCHER). The two weakest of the old matrix, the Ghost Sentinel and the Moonlight Sentinel, were run for every batch; the changes are in the role's code, so the Sagittarius and Trickster get them too, unmeasured.

The question asked: archers should play differently by their range keystone (Far Shot: more damage from far, so stand far; Point Blank: the reverse), and should use few skills, their normal attack being worth more.

**Result:**

| Build | Before | After | Difference (95%) |
|---|---|---|---|
| Ghost Sentinel | 55.3% | 59.7% | +4.4 (+0.4 to +8.4), **better** |
| Moonlight Sentinel | 48.7% | 55.8% | +7.1 (+3.1 to +11.2), **better** |

Batches 1 and 2 kept. Batch 3 (Point Blank offered to fake archers) was clearly worse for both and is not kept (section 4).

## 1. The class

- **Range.** Bows reach 1,000 at 85 (500 and Long Shot's +400, `attackRange` in each battle's `run.json`). Rapid Fire (Moonlight Sentinel, 2 min) gives +20% attack speed and +100 P. Atk with a bow and **halves the reach**. Snipe (+200 reach, roots its user) is in no build.
- **Range keystones** (`PassiveMechanics.rangeMultiplier`): Far Shot scales bow damage from −30% at 150 or closer to +30% at 900 or farther (from the target's centre), Point Blank the other way round; they can't be taken together. Fake archers took Far Shot (weight 1.6 in `FakePvpKeystones.ARCHER`) in 72 to 76% of the fights, never Point Blank.
- **Skills.** Already rare: since issue #29 (`docs/fake-player-simulation-issues.md`) an archer shoots arrows while its hit chance is 75% or more. The normal attack was 95 to 97% of their damage at baseline. The skills cast were Stun Shot as a peel (lands 8%), Dead Eye or Rapid Fire before the fight, Ultimate Evasion, and the Moonlight Sentinel's Elemental Heal (5 s cast, aborted 66%). Nothing to cut there: what the user asked for was already the behaviour.
- Retail: a search for a High Five archer PvP guide (Rapid Fire, Snipe, arrows against skills) found nothing for the chronicle, only Interlude bow guides. The yardstick is the kit and the keystones themselves.

## 2. Baseline

Ghost Sentinel `matrix-20261113-155143`, Moonlight Sentinel `matrix-20261113-160005`.

| Against | Ghost Sentinel | Moonlight Sentinel |
|---|---|---|
| tank | 56.7% | 61.7% |
| melee | 41.9% | 42.8% |
| dagger | 12.2% | 3.3% |
| archer | 45.6% | 30.0% |
| nuker | 57.0% | 34.1% |
| healer | 100.0% | 99.4% |
| buffer | 88.0% | 86.7% |
| **all** | **55.3%** | **48.7%** |

Far from the old matrix (27.5% and 26.4%, `docs/class-balance-85.md`): the AI work since then (#20, #21, #29, the scorer) had already lifted them.

**Where the arrows are shot from** (`tools/simulation/archer_range.py`, new: the distance to the target at each normal-attack hit, and the factor the archer's range keystone gave there):

| Against | Ghost Sentinel with Far Shot: median distance, factor | Without | Moonlight Sentinel with Far Shot | Without |
|---|---|---|---|---|
| tank | 532, 0.98 | 426 | 538, 0.97 | 540 |
| melee | 202, **0.89** | 194 | 194, **0.82** | 190 |
| archer | 844, 1.24 | 844 | – | – |
| nuker | 725, 1.17 | 710 | 647, 1.10 | 643 |
| all | 547, **0.99** | 541 | 519, **0.95** | 558 |

An archer with Far Shot stood exactly where one without it did: on average Far Shot cost it damage. The Moonlight Sentinel shot 0% of its arrows from 900 or farther: its Rapid Fire had halved its reach.

## 3. Diagnosis

| # | Fault | Evidence | Cause | Kind | Fix |
|---|---|---|---|---|---|
| 1 | The range keystone doesn't change where it stands | Table above; `000432-StormScreamer-vs-GhostSentinel`: opens from 920, the mage walks in to 574, a #21 reload step takes it to 724 and it trades there until it dies | Nothing in the AI reads the keystone: it closes to its bow's reach and stays wherever its target puts it | Class AI (role ARCHER) | Batch 1 |
| 2 | Rapid Fire throws Far Shot away | Moonlight Sentinel: Rapid Fire 1.1 casts a battle, 0% of arrows from 900+, factor 0.95 overall | Rapid Fire halves the reach; it was in the `buff` list for every fight | Class AI | Batch 1: skipped by a Far Shot archer, unless its target fights in melee |
| 3 | A melee walking at it isn't slowed | Hamstring Shot (−30% speed, 60 s, lands 29 to 49%) cast 0.03 times a battle; with a melee on it 56 to 61% of the fight, 80% of its arrows from under 525 | Arrows first (#29) keeps debuffs for a target its arrows miss; the walk-in (about 5 s from 1,000) is free time | Class AI | Batch 2 |
| 4 | Elemental Heal (5 s) aborted 66% (Moonlight Sentinel) | 0.7 to 0.9 casts a battle, 85 to 100% aborted against nukers and archers | A 5 s self heal under ranged fire | Build data | Not changed (see section 5) |

## 4. The batches

### Batch 1: play for the range keystone, no Rapid Fire against a ranged target

`FakePlayerPvpAI.rangeStep` (and the same in brain B): after each shot, in the bow's reuse, an archer with **Far Shot** steps back towards 940 from a target that isn't a melee coming at it (a melee that is stunned or rooted counts as not coming); one with **Point Blank** steps in to 250 of a target that doesn't fight in melee. Against a melee coming at it the kite rules decide, as before. A Far Shot archer skips a buff that shortens its reach (`shortensReach`: a `pAtkRange` multiplier below 1, Rapid Fire) unless its target fights in melee.

The first version (steps of up to 250) moved the archer as meant (Far Shot factor against nukers 1.10 → 1.26) and won nothing: its damage per second against nukers rose 6% for the Moonlight Sentinel and **fell 9%** for the Ghost Sentinel (1,865 → 1,701), because the steps cost shots; skipping Rapid Fire in every fight cost damage against melee, who are shot from 200 anyway (`matrix-20261113-160936`, `matrix-20261113-161824`). Kept version (1b): a step only as long as it walks before the bow is ready again (the shot's time plus the bow's reuse after the shot started, about half a second), Rapid Fire kept against melee.

| Build, run | tank | melee | dagger | archer | nuker | healer | buffer | all |
|---|---|---|---|---|---|---|---|---|
| Ghost Sentinel `matrix-20261113-162748` | +5.8 | −1.7 | −4.4 | +8.9 | +5.2 | 0 | +2.7 | +2.0 (−2.1 to +6.0) |
| Moonlight Sentinel `matrix-20261113-163550` | +3.3 | +1.9 | +3.3 | +1.1 | **+8.5 (+0.3 to +16.7)** | −0.6 | +3.3 | +3.6 (−0.4 to +7.7) |

Its own measure moved: Far Shot factor against nukers 1.17 → 1.21 (Ghost Sentinel), 1.10 → 1.21 (Moonlight Sentinel), damage per second dealt 542 → 574 and 499 → 513. No kind clearly worse: kept.

### Batch 2: a slow on a melee walking in

`useSlowOpener`: from beyond 600, while the melee after it can't reach it before the cast is off (`getSafeCastTime`), an archer casts the first slow of its `debuff` or `peel` list that isn't on the target yet (`isSlow`: a `runSpd` multiplier below 1). Hamstring Shot for the three elves and humans; the Moonlight Sentinel's Entangle reaches 600 and so never qualifies.

| Build, run | tank | melee | dagger | archer | nuker | healer | buffer | all |
|---|---|---|---|---|---|---|---|---|
| Ghost Sentinel `matrix-20261113-164600` | +3.3 | +2.8 | +4.4 | −2.2 | +3.3 | 0 | +2.3 | +2.4 (−1.6 to +6.4) |
| Moonlight Sentinel `matrix-20261113-165417` | +5.8 | +5.8 | +6.7 | +2.2 | +2.6 | +1.1 | −2.0 | +3.5 (−0.5 to +7.5) |

Hamstring Shot 0.02 → 0.93 and 0.04 → 1.04 casts a battle, 0 to 4% aborted. Kept. Against the baselines both builds are now clearly better (table at the top).

### Batch 3: Point Blank in the archers' keystone list

`FakePvpKeystones.ARCHER` with `Point Blank@BOW*1.6` beside Far Shot, so that some fake archers would play the close style of batch 1. **Clearly worse, taken back out.**

| Build, run | tank | melee | dagger | archer | nuker | healer | buffer | all |
|---|---|---|---|---|---|---|---|---|
| Ghost Sentinel `matrix-20261113-211914` | **−18.3** | −5.6 | −1.1 | −3.3 | **−12.6** | −1.1 | −2.3 | **−7.2 (−11.2 to −3.2)** |
| Moonlight Sentinel `matrix-20261113-212747` | −0.8 | −7.2 | +4.4 | −2.2 | **−11.9** | 0 | −3.3 | **−5.3 (−9.3 to −1.3)** |

The Ghost Sentinels that took Point Blank won 47.5% (219 fights), Far Shot 54.4%. Point Blank's step in didn't get it close: against nukers its arrows still flew from a median 688 (factor 0.86), against archers from 844 (0.76); a mage keeps its distance, and nobody fights an archer at 250 but a melee, against whom the arrows already came from about 200 (factor 1.11). It also took the place of other keystones in the tree: Unwavering Stance went from 852 fights to 427. The Point Blank step stays in the code: it does nothing until an archer has the keystone, a player's tree included if a fake player ever copies one.

## 5. Not fixed here

- **Daggers** (7 to 13%) and close melee: a dagger is on the archer 55 to 61% of the fight and kills it in about 22 s. Out of the range keystone's reach.
- **Elemental Heal** (Moonlight Sentinel): aborted most of the time, but cast by the side that is losing; not tested.
- **Snipe** (+200 reach, +199 P. Atk, +20% critical rate for 2 min, no moving) is in no build. With a 1,000 reach, Far Shot is already full at 900; Snipe's damage would only pay against a target that can't reach it.
- **Sagittarius and Trickster** were not run. Both get the role's changes; the Trickster's crossbow counts for the range keystones too (`PassiveMechanics.bowDamageMultiplier`), and its `debuff` list has no slow (Real Target, Death Mark, Betrayal Mark, Surrender to Dark, Soul Shock), so batch 2 doesn't reach it.

## 6. Files and runs

- `java/org/l2jmobius/gameserver/ai/FakePlayerPvpAI.java` and `FakePlayerPvpAIExperimental.java`: `rangeStep`, `shortensReach`, `useSlowOpener`, `isSlow`.
- `tools/simulation/archer_range.py`: where the arrows are shot from, by kind of opponent and range keystone.
- Runs in `../build/server/game/log/simulation-matrix/`: listed in each batch.
