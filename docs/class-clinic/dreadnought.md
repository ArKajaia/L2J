# Class clinic: Dreadnought (the Warlord line)

Taken through `docs/class-clinic.md` on 2026-11-14. Level 85, brain A, seed 4040, 30 battles against each of the 39 other builds (1,170 a run). Build data only: no code changed, no other build reached, so no guard run was needed.

**Result: 52.7% → 67.9% overall (+15.2, 95% +11.3 to +19.1).** Clearly better against tanks (41% → 71%), melee (55% → 73%), archers (43% → 58%), nukers (34% → 45%), healers (81% → 92%) and buffers (83% → 92%); daggers 51% → 64%, no clear change.

## 1. The class

From `skill_cards.py "Dreadnought"` and the build in `dist/game/data/FakePlayerPvp.xml`:

- A polearm fighter in heavy armour, range 80 (twice the other fighters'): Cursed Pierce (power 3,849) and Power Crush (3,288) are its damage, both 3 s reuse; its normal attack is 37 to 69% of its damage depending on the opponent. Stuns: Shock Blast (2.5 s cast, lands 14%), Rush Impact (a 600 gap closer, lands 13%), Shock Stomp, Thunder Storm. Self: War Cry, Lionheart (shock/hold/sleep resist), Final Secret (+30% PvP damage), Battle Roar, toggles Vicious Stance and War Frenzy.
- Retail play: a web search for a High Five Dreadnought PvP guide found nothing usable. The only relevant hit, the Lineage 2 wiki page for Tyrr Dreadnought, calls the class not optimal for PvP but a utility class with strong crowd control. The kit itself is the yardstick: polearm damage, crowd control, toggles up.

## 2. Baseline (`matrix-20261114-102918`)

| Against | Battles | Win % (95%) | Enemy HP left when it loses | Damage /s dealt / taken |
|---|---|---|---|---|
| tank | 120 | 40.8% (32-50) | 25% | 367 / 449 |
| melee | 330 | 55.2% (50-60) | 26% | 1060 / 1218 |
| dagger | 90 | 50.6% (40-61) | 49% | 716 / 1348 |
| archer | 120 | 43.3% (35-52) | 33% | 392 / 786 |
| nuker | 270 | 33.9% (29-40) | 70% | 615 / 903 |
| healer | 90 | 81.1% (72-88) | 73% | 1389 / 267 |
| buffer | 150 | 82.7% (76-88) | 29% | 724 / 459 |
| **all** | 1,170 | 52.7% (50-56) | 41% | 778 / 695 |

Casts per battle: Cursed Pierce 7.5, Power Crush 6.7, **Shock Blast 2.6 (lands 14%, opens every combo)**, Rush Impact 1.5 (lands 13%).

## 3. Diagnosis

| # | Fault | Evidence | Cause | Kind | Fix |
|---|---|---|---|---|---|
| 1 | Two toggles it has learned aren't on | Focus Attack (critical damage ×1.3 with a polearm, accuracy +6) and Polearm Accuracy under "LEARNED BUT NOT IN THE BUILD"; toggles cost no fight time | `toggle` was 312,424 | Build data | `<toggle>312,424,317,422</toggle>`. Batch 1 |
| 2 | No emergency skill | Revival (full heal at 10% HP, 15 min reuse) learned and unused; the build had no `emergency` | Build data | Build data | `<emergency>181</emergency>`. Batch 1 |
| 3 | Shock Blast opens every combo and lands 14% | 2.6 casts a battle, 1,222 damage per cast second against 1,584 for Cursed Pierce; lands 5% on tanks | The "Shock crush" combo's first required step | Build data | Step is `optional` and `mageTarget`: only a caster is worth the cast (a stunned caster can't cast). Batch 1 |
| 4 | A combo that can never start | "Stun and crush" opens with Stun Attack, which needs a blunt weapon | Build data | Build data | Removed. Batch 1 |

Looked at and left alone:
- **Nukers (45%)**: `000001-Archmage-vs-Dreadnought` (batch 1): the Archmage opens from 874 and bursts 4,015 + 9,340 + 4,556 in 3 s; the Dreadnought is at 33% before its Rush Impact lands, heals with Battle Roar, and dies at 15.8 s with the Archmage at 70%. Not an AI gap: the Archmage is the strongest build of the clinic (`docs/class-clinic/wizards.md`).
- **Revival** is cast 0.24 times a battle and aborted 24% of those: it needs HP at 10% or lower, and a burst takes the Dreadnought from 19% to 0 inside the cast.
- **Dread Pool** (area fear, 90% base land), **Provoke** (+20% polearm damage taken for 10 s), Braveheart (a CP heal of 1,000) and Thrill Fight (speed −20%) not tried. A feared target runs out of reach of a 157-speed fighter.

## 4. The batches

### Batch 1: toggles, Revival, Shock Blast only against casters (`matrix-20261114-104028`, against the baseline)

| Against | Before | After | Difference (95%) | Verdict |
|---|---|---|---|---|
| **tank** | 40.8% | 70.8% | +30.0 (+18.0 to +42.0) | **better** |
| **melee** | 55.2% | 73.2% | +18.0 (+10.8 to +25.2) | **better** |
| **dagger** | 50.6% | 63.9% | +13.3 (-1.0 to +27.7) | no clear change |
| **archer** | 43.3% | 57.9% | +14.6 (+2.1 to +27.1) | **better** |
| **nuker** | 33.9% | 44.6% | +10.7 (+2.6 to +18.9) | **better** |
| **healer** | 81.1% | 92.2% | +11.1 (+1.3 to +20.9) | **better** |
| **buffer** | 82.7% | 92.0% | +9.3 (+1.9 to +16.8) | **better** |
| **all** | 52.7% | 67.9% | +15.2 (+11.3 to +19.1) | **better** |

Skill use moved as meant: Shock Blast 2.6 → 1.2 casts a battle, Revival 0 → 0.24. The batch was not split, so how much of the +15 came from Revival, the toggles (their critical damage isn't a cast, and damage dealt stayed 778 → 770 a second) or the Shock Blast change is not known. Time spent disabled fell 14% → 10%, and the fights against tanks and healers gained most.

### Batch 2: a bow against a target it can't catch (`matrix-20261114-105117`, against batch 1): not kept

`bow="BOW"` on the build (the tanks' and Grand Khavatari's weapon swap). Overall +0.6 (-3.2 to +4.4), every kind "no clear change" (archer +5.8, nuker +3.3). Taken out.

## 5. After (`matrix-20261114-104028`)

| Against | Win % | Damage /s dealt / taken |
|---|---|---|
| tank | 70.8% | 370 / 467 |
| melee | 73.2% | 1096 / 1211 |
| dagger | 63.9% | 745 / 1540 |
| archer | 57.9% | 361 / 743 |
| nuker | 44.6% | 619 / 911 |
| healer | 92.2% | 1604 / 299 |
| buffer | 92.0% | 768 / 502 |
| **all** | **67.9%** | 770 / 744 |

## 6. Not fixed here

- **Nukers** (Archmage 25%, Storm Screamer 30%, Mystic Muse 35%): out-statted, see section 3.
- **Hell Knight (23%)**, the one tank still lost: not read.
- **Daggers (64%)**: the interval is wide at 90 battles; a run with `--against dagger --battles 80` would settle it.
- The three toggles/Revival/Shock Blast changes were not measured apart.

## 7. Files and runs

- `dist/game/data/FakePlayerPvp.xml`: the Dreadnought build (toggles, emergency, "Shock crush" combo).
- Runs in `../build/server/game/log/simulation-matrix/`: `matrix-20261114-102918` (baseline), `matrix-20261114-104028` (batch 1, kept), `matrix-20261114-105117` (batch 2, not kept).
