# Class clinic: Titan (the Destroyer line)

Taken through `docs/class-clinic.md` on 2026-11-14. Level 85, brain A, seed 4040, 30 battles against each of the 39 other builds (1,170 a run).

**Result: 49.4% → 54.1% overall (+4.7, 95% +0.7 to +8.8).** Tanks 20% → 31%, melee 62% → 68%, daggers 42% → 53%, archers 26% → 33%, buffers 73% → 83% (clearly better), nukers 30% → 27% (no clear change), healers 100%. Two batches of build data and one change to how every melee values its normal attack against a target that reflects damage (guarded, section 4).

## 1. The class

From `skill_cards.py "Titan"` and the build in `dist/game/data/FakePlayerPvp.xml`:

- A two-handed sword fighter in heavy armour that fights mostly with its normal attack (59% of its damage before the clinic). Skills: Crush of Doom (power 6,872, 7 s reuse), Fatal Strike (3,653, 3 s), Armor Crush (3,077 and a 9 s stun that lands about 11% at 85, P. Def and M. Def ×0.7 on the target), Demolition Impact (7,475, 75 s), Rush (gap closer, reach 400). Skill power is added to P. Atk (`docs/class-clinic.md`, lessons), so Fatal Strike hits for about two normal attacks.
- Self: Rage (P. Atk ×1.64 with a sword, P. Def ×0.8), Lionheart, Final Secret, and the low-HP kit: **Frenzy** (P. Atk ×1.77, HP 60% or lower), **Guts** (P. Def ×3, HP 30% or lower), **Zealot** (attack speed ×1.3, critical rate +100, HP 30% or lower), Over the Body (emergency), Battle Roar, **Braveheart** (+1,000 CP, which is a fake player's HP).
- Frenzy and Guts share the abnormal type `PINCH`: Guts at 30% takes Frenzy's place.
- Retail play: a web search for a High Five Titan PvP guide found nothing usable (Interlude and Classic pages only). The yardstick is the kit: the Titan is the class of the low-HP buffs (Frenzy, Guts, Zealot), and of hitting with skills rather than standing in a tank's reflect.

## 2. Baseline (`matrix-20261114-173221`)

| Against | Battles | Win % (95%) | Enemy HP left when it loses | Damage /s dealt / taken | Enemy within 200 |
|---|---|---|---|---|---|
| tank | 120 | 20.0% (14-28) | 36% | 835 / 1076 | 78% |
| melee | 330 | 61.7% (56-67) | 34% | 1284 / 1374 | 61% |
| dagger | 90 | 42.2% (33-53) | 59% | 874 / 1988 | 61% |
| archer | 120 | 26.2% (19-35) | 80% | 289 / 1131 | 42% |
| nuker | 270 | 30.0% (25-36) | 83% | 641 / 1282 | 45% |
| healer | 90 | 100.0% (96-100) | - | 1857 / 382 | 79% |
| buffer | 150 | 73.3% (66-80) | 27% | 1019 / 856 | 69% |
| **all** | 1,170 | 49.4% (47-52) | 55% | 927 / 1152 | 59% |

Casts per battle: Fatal Strike 2.2, Armor Crush 1.7, Crush of Doom 1.2 (7 s reuse in a 22 s fight), Demolition Impact 0.7, Power Smash 0.5, Power Strike 0.4. Normal attack 59% of its damage. **Guts never cast.**

## 3. Diagnosis

| # | Fault | Evidence | Cause | Kind | Fix |
|---|---|---|---|---|---|
| 1 | Guts never cast | 0 casts in 1,170 battles; the report's skill table has no Guts row | `<buff>94,176\|139,...`: "a\|b" keeps only the first learned skill, and Frenzy is learned | Build data | `176,139`: Frenzy from 60% HP, Guts from 30% (it takes Frenzy's place). Batch 1 |
| 2 | Braveheart not in the build | "LEARNED BUT NOT IN THE BUILD"; a CP heal restores a fake player's HP since the Dominator's clinic | Build data | Build data | `<heal>121,440</heal>`. Batch 1 |
| 3 | Power Smash and Power Strike valued near Fatal Strike | Trace with the new estimate fields (`matrix-20261114-173951`, `000001-Titan-vs-PhoenixKnight`): Power Smash `est=595` against Fatal Strike `est=627`, then hits for 293 to 411, a normal attack's damage | `LIST_RANK_FACTOR` lifts a skill to its place in the list times the best estimate (Demolition Impact) | Build data | Out of the attack list. Batch 1 |
| 4 | Guts cast against mages | Batch 1: nukers 30.0% → 22.4% (-7.6, -15.0 to -0.2, clearly worse); Guts (P. Def) replaces Frenzy (P. Atk) at 30% HP against a target whose damage is spells | `defendsAgainst` skipped M. Def buffs against fighters, not P. Def buffs against mages | Class AI (by effect: a buff whose only stat is P. Def; only the Titan's build has one) | `defendsAgainst`: a P. Def-only buff doesn't defend against a pure mage (`isMage`). Batch 2 |
| 5 | Normal attacks valued at full damage against a target that reflects them | `matrix-20261114-174339`, `000047-HellKnight-vs-Titan`: each of the Titan's normal hits comes back at 44% (713 → 315, 359 → 157, 192 → 84); its skills don't (`Creature.onHitTimer` reflects normal attacks only). The scorer still ranked the normal attack first | `estimateAutoAttackDps` didn't know `REFLECT_DAMAGE_PERCENT`. Tanks have it, and so does anyone with the passive tree's reflect nodes | General AI (every melee build) | The normal attack's estimate is cut by the share reflected (not for a bow, at most 90%). Batch 3, with a guard |

Looked at and left alone:

- **The scorer's estimates.** In the first timeline read (`000321-ShillienTemplar-vs-Titan`) the scorer gave Fatal Strike 0.27 of a normal attack. The trace fields added for this (`est=`, `auto=`) showed, on the fights after, estimates within about 15% of the hits: Fatal Strike `est=627` hit 562, the normal attack `auto=1118/s` dealt about 960 a second. Kept the fields in the trace (both brains): they show what a pick stood on.
- **Archers (33%)**: they hold the Titan at 400 to 550, just outside Rush (400): `000999-Titan-vs-Trickster` walks from 3.3 to 19.3 s without a cast at a gap of 371 to 550 (speed 160 against 220). The Titan has no other gap closer or speed skill; the Dreadnought's bow (`bow="BOW"`) gave no change. Not tried.
- **Nukers (27%)**: `000471-StormScreamer-vs-Titan`: the wizard's Arcane Shield takes the Titan's hits in MP (a 5,220 Demolition Impact crit leaves it at 96% HP). Out-statted, as in the Dreadnought's case.
- **Physical Mirror** sends Armor Crush's stun back (`000047-HellKnight-vs-Titan`, 28.0 s: "Armor Crush LANDS on Titan"). The scorer knows a reflect only for nukes (`EffectMeaning.REFLECT`, skills 351, 916, 6282). A general finding (section 7).
- **Zealot** (2 s cast at 30% HP, fights won 26% where it is cast) and the low-HP chain (Guts, Zealot, Over the Body: 5.5 s of casts at 30%): an emergency kit is cast by the side that is losing; not tried.

## 4. The batches

Each against the run before it, same seed.

### Batch 1: Guts, Braveheart, no Power Smash or Power Strike (`matrix-20261114-174339`, against the baseline)

| Against | Before | After | Difference (95%) | Verdict |
|---|---|---|---|---|
| tank | 20.0% | 21.7% | +1.7 (-8.6 to +11.9) | no clear change |
| melee | 61.7% | 63.5% | +1.8 (-5.6 to +9.2) | no clear change |
| **dagger** | 42.2% | 60.0% | +17.8 (+3.4 to +32.2) | **better** |
| archer | 26.2% | 22.9% | -3.3 (-14.2 to +7.6) | no clear change |
| **nuker** | 30.0% | 22.4% | -7.6 (-15.0 to -0.2) | **worse** |
| healer | 100.0% | 98.9% | -1.1 (-3.3 to +1.1) | no clear change |
| buffer | 73.3% | 80.0% | +6.7 (-2.9 to +16.2) | no clear change |
| **all** | 49.4% | 50.1% | +0.7 (-3.3 to +4.8) | no clear change |

Skill use moved as meant: Guts 0 → 0.27 a battle, Braveheart 0 → 0.61, Power Smash and Power Strike gone. Nukers clearly worse: fault 4.

### Batch 2: no Guts against a mage (`matrix-20261114-175112`, against batch 1)

| Against | Before | After | Difference (95%) | Verdict |
|---|---|---|---|---|
| tank | 21.7% | 22.9% | +1.2 (-9.3 to +11.8) | no clear change |
| melee | 63.5% | 63.6% | +0.2 (-7.2 to +7.5) | no clear change |
| dagger | 60.0% | 52.8% | -7.2 (-21.7 to +7.2) | no clear change |
| **archer** | 22.9% | 34.6% | +11.7 (+0.3 to +23.0) | **better** |
| nuker | 22.4% | 27.6% | +5.2 (-2.1 to +12.5) | no clear change |
| healer | 98.9% | 100.0% | +1.1 (-1.1 to +3.3) | no clear change |
| buffer | 80.0% | 80.7% | +0.7 (-8.3 to +9.7) | no clear change |
| **all** | 50.1% | 52.3% | +2.2 (-1.9 to +6.2) | no clear change |

Batches 1 and 2 against the baseline: +2.9 (-1.1 to +7.0), no kind clearly worse (nukers -2.4, archers +8.3, daggers +10.6). Kept, as their own measures moved (Guts cast, not against mages; Braveheart cast; the weak skills gone).

### Batch 3: a normal attack is worth less against a target that reflects it (`matrix-20261114-175723`, against batch 2)

| Against | Before | After | Difference (95%) | Verdict |
|---|---|---|---|---|
| tank | 22.9% | 30.8% | +7.9 (-3.3 to +19.1) | no clear change |
| melee | 63.6% | 67.6% | +3.9 (-3.3 to +11.2) | no clear change |
| dagger | 52.8% | 53.3% | +0.6 (-14.0 to +15.1) | no clear change |
| archer | 34.6% | 32.5% | -2.1 (-14.0 to +9.9) | no clear change |
| nuker | 27.6% | 26.9% | -0.7 (-8.2 to +6.8) | no clear change |
| healer | 100.0% | 100.0% | +0.0 | no clear change |
| buffer | 80.7% | 82.7% | +2.0 (-6.8 to +10.8) | no clear change |
| **all** | 52.3% | 54.1% | +1.8 (-2.2 to +5.9) | no clear change |

Skill use moved as meant, and against every kind, not only tanks: many fighters reflect damage through the passive tree. Fatal Strike 2.3 → 4.4 casts a battle, Armor Crush 1.9 → 2.9, Crush of Doom 1.4 → 2.1; the normal attack 59% → 35% of its damage. **Damage taken 1,125 → 986 a second** (healers 382 → 230, buffers 854 → 680, melee 1,420 → 1,164) for damage dealt 902 → 873. Hell Knight 10% (baseline) → 40%.

### Guard

Batch 3 reaches every build that fights with a melee normal attack. Two of them, 15 battles against each opponent (585 a run), on the code before batch 3 and after it:

| Build | Before | After | Difference (95%) | Kinds clearly worse | What moved |
|---|---|---|---|---|---|
| Dreadnought | 56.1% (`matrix-20261114-181816`) | 59.9% (`matrix-20261114-180254`) | +3.8 (-1.8 to +9.5) | none | Power Crush 5.5 → 9.1 and Cursed Pierce 6.1 → 9.1 casts a battle; damage taken 904 → 784 a second |
| Phoenix Knight | 76.7% (`matrix-20261114-182447`) | 74.9% (`matrix-20261114-180926`) | -1.8 (-6.7 to +3.1) | none | Tribunal 14.4 → 16.3, Shield Strike 10.8 → 12.2 |

No build clearly worse; kept.

## 5. Before and after

Baseline `matrix-20261114-173221` against `matrix-20261114-175723`, 1,170 battles each:

| Against | Before | After | Difference (95%) | Verdict | Damage /s dealt | Damage /s taken |
|---|---|---|---|---|---|---|
| tank | 20.0% | 30.8% | +10.8 (-0.1 to +21.8) | no clear change | 835 → 771 | 1076 → 885 |
| melee | 61.7% | 67.6% | +5.9 (-1.4 to +13.2) | no clear change | 1284 → 1243 | 1374 → 1164 |
| dagger | 42.2% | 53.3% | +11.1 (-3.4 to +25.6) | no clear change | 874 → 772 | 1988 → 1603 |
| archer | 26.2% | 32.5% | +6.2 (-5.2 to +17.7) | no clear change | 289 → 324 | 1131 → 1095 |
| nuker | 30.0% | 26.9% | -3.1 (-10.8 to +4.5) | no clear change | 641 → 565 | 1282 → 1158 |
| healer | 100.0% | 100.0% | +0.0 | no clear change | 1857 → 1778 | 382 → 230 |
| **buffer** | 73.3% | 82.7% | +9.3 (+0.0 to +18.6) | **better** | 1019 → 917 | 856 → 680 |
| **all** | 49.4% | 54.1% | **+4.7 (+0.7 to +8.8)** | **better** | 927 → 873 | 1152 → 986 |

By opponent, clearly better: Hell Knight 10% → 40%, Sword Muse 70% → 100%, Ghost Sentinel 30% → 57%.

## 6. What changed

- `dist/game/data/FakePlayerPvp.xml`, Titan: `<attack>777,315,190</attack>`, `<buff>94,176,139,287,420,917</buff>`, `<heal>121,440</heal>`.
- `ai/FakePlayerPvpAI.java` and `ai/FakePlayerPvpAIExperimental.java` (the same in both): `defendsAgainst` leaves a P. Def-only buff out against a pure mage; `estimateAutoAttackDps` takes off the share of a melee normal attack the target reflects (`REFLECT_DAMAGE_PERCENT`, `REFLECT_CAP`); the scorer's trace adds `est=<damage>/<cast ms>` and `auto=<normal attack damage a second>`.

## 7. Not fixed here, and next

- **Nukers (27%)** and **archers (33%)**: section 3. The archers hold it just outside Rush's reach; a slow melee with one 400 gap closer has no answer in its kit.
- **Physical Mirror and debuffs.** A target under Physical Mirror (Hell Knight) sends Armor Crush's stun back; the scorer only knows reflects that hurt a nuke. A general finding: a debuff or stun is not worth casting on a target with a skill reflect for physical skills on. **Done** in shared code (`docs/class-clinic.md`, General findings): a physical debuff without damage is left out, Armor Crush keeps its damage with no control value. Leaving Armor Crush out as well made Titan vs Hell Knight clearly worse (33% → 0%): the reflected stun takes the Titan rarely (2 in 52 casts).
- **Zealot and the low-HP chain** (section 3).
