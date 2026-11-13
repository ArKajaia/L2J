# Class clinic: Grand Khavatari (the Tyrant line)

Taken through `docs/class-clinic.md` on 2026-11-13, after the Duelist (`duelist.md`), whose shared fixes it already had: the energy skill estimate, charging with Raging Force, holding off out of reach and the combo walk limit took it from 60.3% to 69.2% in the Duelist's guard runs. Level 85, brain A, seed 4040, 1,170 battles a run (about 7.5 minutes a run).

**Result: 71.1% → 72.7% (+1.6, 95% -2.0 to +5.3), no clear change.** The one fault found in its own build was a stun that almost never landed; fixing it moved the casts and the damage as meant, but not the win rate by a margin a run can show.

## 1. The class

From `skill_cards.py "Grand Khavatari"`: a fist fighter with force charges (up to 8). Force of Destruction (2 charges, power 4,500, 15 s), Hurricane Assault (2, 3,196, 4 s), Force Blaster (1, 2,131, reaches 600), Punch of Doom (power 9,132, 5 min), Burning Fist, Raging Force (charges as it hits); Soul Breaker and Stunning Fist (stuns), Cripple; totems (one at a time), Zealot (only below 30% HP), Final Secret, Force Barrier (10 s invincible, 4 charges, ends on any action), Break Duress.

Retail play, from search results only (the guide pages found list skills, not tactics): Bison Spirit Totem with Zealot and Force Barrier as the burst ([pmfun forum](https://forum.pmfun.com/viewtopic.php?t=3094), [IGN wiki](https://www.ign.com/wikis/lineage-ii/Tyrant)), Force Blaster as the ranged attack ([strategywiki](https://strategywiki.org/wiki/Lineage_II/Tyrant)). None of them names the chronicle precisely.

## 2. Baseline (`matrix-20261113-052349`)

| Against | tank | melee | dagger | archer | nuker | healer | buffer | **all** |
|---|---|---|---|---|---|---|---|---|
| Win % | 56.7 | 56.2 | 82.2 | 75.8 | 67.8 | 100.0 | 93.3 | **71.1 (68-74)** |

Damage per cast second: Punch of Doom 7,259, Force of Destruction 6,227, Hurricane Assault 3,956, Force Blaster 3,486, Burning Fist 2,949, Raging Force 1,857, **Stunning Fist 1,488 (2.5 casts a battle, lands 3 to 12%)**. Never cast: Force Storm, Force Burst, Iron Punch, Power Strike.

## 3. Diagnosis

| # | Fault | Evidence | Cause | Kind | Fix |
|---|---|---|---|---|---|
| 1 | It opens with a stun that doesn't land | Stunning Fist 2.5 casts a battle, lands 6% on tanks, 8% on melee, 3% on daggers; Soul Breaker 0.6 | The "Stun punch" combo step `120\|281` takes the first one learned: Stunning Fist, magic level 36 (Soul Breaker: 74) and power 152 (Soul Breaker: 2,411). The debuff list had it first too | Build data | `<step skill="281">`, `<debuff>281,95</debuff>`. Batch 1 |
| 2 | The attack list leads with the area skills | `35,17,776,346,81,...`; Iron Punch and Power Strike (sword or blunt only) never cast | Build data | Build data | `81,776,284,54,280,346,35,17`. Batch 1 |
| 3 | Its stun resistance toggles are off | Fortitude (stun and paralysis 30%) and War Frenzy in "LEARNED BUT NOT IN THE BUILD" | Build data | Build data | `<toggle>222,335,424</toggle>`. Batch 1 |
| 4 | Force Barrier is broken by its next action | `battles/000569-GrandKhavatari-vs-Judicator` (batch 1): cast at 15.8 s, Maximum Focus Force at 16.5 s, the Judicator waited 0.3 s and went on | The skill ends on any action except moving | General AI (the Painkiller finding) | Not fixed here |

## 4. The batches

### Batch 1: Soul Breaker, attack list, toggles (`matrix-20261113-053233`): kept

| Against | tank | melee | dagger | archer | nuker | healer | buffer | **all** |
|---|---|---|---|---|---|---|---|---|
| Before | 56.7 | 56.2 | 82.2 | 75.8 | 67.8 | 100.0 | 93.3 | 71.1 |
| After | 65.0 | 58.8 | 85.6 | 75.8 | 69.6 | 99.4 | 88.7 | **72.7** |

+1.6 (95% -2.0 to +5.3), no kind clearly better or worse. Kept for its own measure: Stunning Fist 2.5 → 0 casts, Soul Breaker 0.6 → 2.8 (878 → 4,092 damage a battle), damage dealt 908 → 973 a second.

### Batch 2: Bison Spirit Totem first (`matrix-20261113-054242`): taken out

`<buff>292,425|...>`: Bison (P. Atk. x1.43, +400 critical rate, 60 s, 10 min reuse) at the start of a fight and Hawk (+100 critical rate, x1.3 critical damage) once it has run out. 72.7% → 75.0% (+2.4, 95% -1.2 to +5.9), no clear change; Bison was cast in every fight, but the damage per cast didn't move (Hurricane Assault 1,980 → 2,032, Burning Fist 1,438 → 1,324): its critical rate is mostly over the cap, and its P. Atk. about makes up for Hawk's critical damage. A change whose own measure didn't move goes back out.

## 5. Not fixed here, and next

- Force Barrier (fault 4) and Zealot: Zealot can only be cast below 30% HP and comes a hit before death (cast in 450 fights, 37% won). The barrier is the general Painkiller finding.
- Melee (59%): the Judicator in Final Form crits for 5,800; the Doombringer and Titan out-trade it. Not read in depth.
