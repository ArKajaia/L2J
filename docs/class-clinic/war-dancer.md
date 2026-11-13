# Class clinic: War Dancer (the Bladedancer line)

Taken through `docs/class-clinic.md` on 2026-11-13. Level 85, brain A, seed 4040, 30 battles against each of the 39 other builds (1,170 a run). The War Dancer (classId 107, variant War Dancer Light) is the Bladedancer that fights on its own; the Spectral Dancer is the same class as a party buffer (weight 0, see section 6).

**Result: 35.3% → 71.7% overall (+36.4).** Clearly better against tanks (33% → 62%), melee (21% → 61%), daggers (56% → 91%), archers (43% → 94%), nukers (15% → 51%) and buffers (49% → 93%). Build data only: no code changed, no other build reached, so no guard run was needed.

## 1. The class

From `skill_cards.py "War Dancer"` and the build in `dist/game/data/FakePlayerPvp.xml`:

- A dual-sword fighter in heavy armour. Its damage: **Deadly Strike** (power 4,640, 1.5 s, 5 s reuse, single target), Demonic Blade Dance (1,644, around it 150, P. Atk and M. Atk −30% on what it hits), Sting (1,218 and a bleed), Danse Macabre (3,500, around it 300, P. Def −20% and speed −30%, 2 min reuse), Poison Blade Dance (power 1 and a poison), and its normal attack with Blade Waltz (+8% attack speed, 20% of its hits slash everything around for 1,150 power).
- Debuffs from its level 74 kit: Hex, Power Break, Freezing Strike (3 s cast), Arrest; Confusion. Drain Health (a 3 s magic cast, M. Atk 7).
- Self: Defense Motion (P. Def +25%, M. Def +10%, evasion +3, 2 min), Deflect Magic (magic debuff resist ×2), Deflect Arrow, Ultimate Defense, and its dances on itself (Fire: crit damage +35%; Warrior, Fury, Vampire, Berserker and Blade Storm come with the FIGHTER buff list).
- Custom performances (`config/Custom/Performers.ini`): Dance of Torment (enemies within 400: attack and cast speed −12%, speed −20%) in a PvP. It works (it lands in every timeline); the report lists it as never cast because a toggle isn't a cast.

Retail play: no High Five PvP guide for the Bladedancer turned up (a search for one, and the Freya guide on [linedia.ru](https://linedia.ru/en/wiki/Bladedancer_(Freya)_guide_%E2%80%94_Spectral_Dancer) could not be fetched from here). The sources found describe it as a party class ("strengthen your own party and weaken the defenses of your party DDs' first targets", the same linedia.ru guide, from the search summary), with [l2db.info](https://l2db.info/high-five/classes?class=107) giving Deadly Strike as a level 25 skill with 4,640 power added to P. Atk. For a duel that leaves the kit itself as the yardstick: its strongest hits first, the dances on itself, and no 10%-landing debuffs.

## 2. Baseline (`matrix-20261113-130459`)

| Against | Battles | Win % (95%) | Fight s (won / lost) | Enemy HP left when it loses | Damage /s dealt / taken |
|---|---|---|---|---|---|
| **tank** | 120 | 32.5% (25-41) | 70 / 41 | 58% | 375 / 403 |
| **melee** | 330 | 21.2% (17-26) | 27 / 24 | 55% | 613 / 782 |
| **dagger** | 90 | 55.6% (45-65) | 21 / 22 | 30% | 751 / 813 |
| **archer** | 120 | 42.5% (34-51) | 54 / 70 | 47% | 226 / 314 |
| **nuker** | 270 | 14.8% (11-20) | 30 / 22 | 88% | 412 / 816 |
| **healer** | 90 | 99.4% (95-100) | 300 / - | - | 1065 / 3 |
| **buffer** | 150 | 49.0% (41-57) | 52 / 41 | 40% | 532 / 304 |
| **all** | 1,170 | 35.3% (33-38) | 47 / 28 | 63% | 621 / 393 |

Casts per battle: Sting 6.9, Arrest 3.5 (lands 11%; 26 a battle against healers), Demonic Blade Dance 3.2, **Hex 3.1 (lands 11%, opened every fight)**, Poison Blade Dance 2.9 (poison lands 11%), Freezing Strike 1.9 (34% aborted, lands 9%, 33 damage per cast second), Danse Macabre 1.0, Power Break 0.8 (lands 12%), Drain Health 0.7 (42% aborted, 41 damage per cast second). Normal attack: 61% of its damage.

## 3. Diagnosis

| # | Fault | Evidence | Cause | Kind | Fix |
|---|---|---|---|---|---|
| 1 | Its best single-target skill isn't in the build | Deadly Strike (power 4,640, 5 s reuse) under "LEARNED BUT NOT IN THE BUILD"; its strongest single hit was Sting (1,218) | Build data | Build data | First single-target skill in `attack`, in both combos. Batch 1 |
| 2 | It opens every fight with Hex from 600 and spends casts on debuffs that land 10% | Openings: "Hex > ..." in every kind. `000526-Doombringer-vs-WarDancer` (baseline): Hex resisted at 5.1 s, Arrest at 20 s; `001112-MaleSoulHound-vs-WarDancer`: stops at 574 to cast Hex (aborted), then Arrest instead of hitting | The "Hex and blade dance" combo opened with Hex; Hex, Power Break, Freezing Strike and Arrest in `debuff`, Arrest leading the Danse Macabre combo | Build data | Combos open with Demonic Blade Dance / Danse Macabre; `debuff` down to Hex (batch 1), then none (batch 2); Arrest only in `peel` |
| 3 | 3-second spells with M. Atk 7 | Drain Health 82 a cast, Freezing Strike 65; 34 to 54% aborted; `000526`: both cast at 8% and 1% HP | Build data | Build data | Out of `attack`, `debuff` and `peel`. Batch 1 |
| 4 | No self buffs | Defense Motion (P. Def +25%), Deflect Magic, Deflect Arrow learned and unused; the build had no `buff` list | Build data | Build data | `<buff>989,913,112</buff>`: lasting buffs, so they are on before the fight (`FakePlayerPvpManager.isPreFightBuff`). Batch 1 |
| 5 | Poison Blade Dance spends the moment it reaches a mage | `000060-FemaleSoulHound-vs-WarDancer` (batch 1): at gap 159 it casts Poison Blade Dance (aborted) before its Demonic Blade Dance (4,065); the poison lands 13%, the scorer counts it whole | Build data (the land chance the scorer assumes is the general finding in `docs/class-clinic.md`) | Build data | Out of `attack`. Batch 2 |
| 6 | Dance of Fire, its own dance, wasn't on | The FIGHTER buff list has Warrior, Fury, Vampire, Berserker, Blade Storm, not Fire (crit damage +35%) | Build data | Build data | In `buff` (pre-fight, like the others). Batch 2 |

Looked at and left alone: Dance of Medusa petrifies, and petrified targets are invulnerable (`Petrification`: PARALYZED and INVUL), which helps nobody in a duel. Counter buffs (Doombringer's Eye for Eye) are already played around (`Defense.COUNTER`, the daggers' batch 3).

## 4. The batches

Each against the run before it, same seed. "Clearly" means the 95% interval of the difference doesn't include zero.

### Batch 1: Deadly Strike, self buffs, no 3-second spells, no Hex opener (`matrix-20261113-131537`)

| Against | Before | After | Difference (95%) | Verdict |
|---|---|---|---|---|
| **tank** | 32.5% | 51.7% | +19.2 (+6.9 to +31.4) | **better** |
| **melee** | 21.2% | 41.8% | +20.6 (+13.7 to +27.5) | **better** |
| **dagger** | 55.6% | 77.8% | +22.2 (+8.8 to +35.6) | **better** |
| **archer** | 42.5% | 86.7% | +44.2 (+33.4 to +54.9) | **better** |
| **nuker** | 14.8% | 37.8% | +23.0 (+15.8 to +30.1) | **better** |
| **healer** | 99.4% | 100.0% | +0.6 (-1.0 to +2.1) | no clear change |
| **buffer** | 49.0% | 82.7% | +33.7 (+23.6 to +43.7) | **better** |
| **all** | 35.3% | 59.0% | +23.7 (+19.7 to +27.6) | **better** |

Skill use moved as meant: Deadly Strike 0 → 4.4 casts a battle (3,413 damage a battle), Arrest 3.5 → 0.03, Hex 3.1 → 1.5, Freezing Strike, Power Break and Drain Health gone. Damage taken 393 → 340 a second (Defense Motion).

### Batch 2: no Hex or Poison Blade Dance, Dance of Fire (`matrix-20261113-132616`, against batch 1)

| Against | Before | After | Difference (95%) | Verdict |
|---|---|---|---|---|
| **tank** | 51.7% | 61.7% | +10.0 (-2.5 to +22.5) | no clear change |
| **melee** | 41.8% | 61.2% | +19.4 (+11.9 to +26.9) | **better** |
| **dagger** | 77.8% | 91.1% | +13.3 (+2.9 to +23.7) | **better** |
| **archer** | 86.7% | 94.2% | +7.5 (+0.1 to +14.9) | **better** |
| **nuker** | 37.8% | 51.1% | +13.3 (+5.0 to +21.6) | **better** |
| **healer** | 100.0% | 100.0% | +0.0 | no clear change |
| **buffer** | 82.7% | 93.0% | +10.3 (+3.0 to +17.6) | **better** |
| **all** | 59.0% | 71.7% | +12.7 (+8.9 to +16.5) | **better** |

Split with a run without Dance of Fire (`matrix-20261113-133523`): dropping Hex and Poison Blade Dance alone **+7.3** (+3.4 to +11.2, melee +12.4, daggers +13.3 clearly), Dance of Fire on top **+5.4** (+1.6 to +9.1). Both kept. Damage dealt 663 → 704 a second; the time freed goes to its normal attack.

## 5. After (`matrix-20261113-132616`)

| Against | Win % (95%) | Enemy HP left when it loses | Damage /s dealt / taken |
|---|---|---|---|
| tank | 61.7% (53-70) | 24% | 477 / 345 |
| melee | 61.2% (56-66) | 35% | 1011 / 719 |
| dagger | 91.1% (83-95) | 25% | 923 / 548 |
| archer | 94.2% (88-97) | 47% | 218 / 122 |
| nuker | 51.1% (45-57) | 79% | 679 / 637 |
| healer | 100.0% (96-100) | - | 1530 / 6 |
| buffer | 93.0% (88-96) | 40% | 587 / 257 |
| **all** | **71.7% (69-74)** | 46% | 704 / 352 |

Casts per battle: Sting 4.8, Deadly Strike 3.6, Demonic Blade Dance 3.0, Danse Macabre 1.0. Per cast second: Danse Macabre 2,654, Demonic Blade Dance 1,726, Deadly Strike 1,471, Sting 1,290.

## 6. Not fixed here

- **The Soul Hounds** (Female 0%, Male 3%): speed 260 against 200, kept at 400 to 600 with binds and Blink; the War Dancer has no gap closer, dash or ranged attack. The nuker kind is lost with the enemy at 79% HP: not a tuning gap. A kit problem for `docs/class-balance-85.md` if anywhere.
- **Hell Knight (20%), Doombringer (17%)**: timelines show even trades lost on HP and defence (`000552-PhoenixKnight-vs-WarDancer`: Physical Mirror even sends Demonic Blade Dance and Sting back on it). Tank losses end with the tank at a median 24%: close.
- **Dance of Fire is recast in long fights** (2 min): 20 fights, 10% won; they are the long losing ones it runs out in. A 2.5 s cast, left as it is.
- **The Spectral Dancer** (the support build of the same class, weight 0) still has the old attack list (Poison Blade Dance, Drain Health, Hex, Power Break, Freezing Strike, no Deadly Strike). It is a buffer opponent in every other build's runs, so changing it moves their numbers; left for its own pass.

## 7. Files and runs

- `dist/game/data/FakePlayerPvp.xml`: the War Dancer build (attack, buff, peel, combos).
- Runs in `../build/server/game/log/simulation-matrix/`: `matrix-20261113-130459` (baseline), `matrix-20261113-131537` (batch 1), `matrix-20261113-132616` (batch 2), `matrix-20261113-133523` (batch 2 without Dance of Fire).
