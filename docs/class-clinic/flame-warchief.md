# Class clinic: Flame Warchief (the Warcryer line)

Taken through `docs/class-clinic.md` on 2026-11-13. Level 85, brain A, seed 4040, 1,170 battles a run (about 10 minutes: its fights are long). **Result: 35.5% → 38.5% (+3.1, 95% -0.8 to +7.0).** Clearly better against tanks (81% → 93%), daggers (2% → 23%) and archers (6% → 21%); melee (23%) and nukers (17%) unchanged.

A custom class: its totems (275xx) have no retail counterpart, so there was no retail play to hold the logs against. `docs/class-balance-85.md` ("Flame Warchief") had already named two of its faults: it walks to its target to plant the Totem of Flames, and it should plant Frost-Teeth before a melee arrives.

## 1. The class

From `skill_cards.py "Flame Warchief"`: a robe caster whose damage is Steal Essence (4 s cast, 2 s reuse, reach 900, drains) and its totems. The Totem of Flames burns what stands within 350 of it, and it stands at the Warchief's feet; Frost-Teeth slows what comes near; Blood steals life; Shatter blows the small totems up; the Great Totem of the Horde-Father plants three at once. Freezing Flame is a fire damage-over-time. Spirit Walk swaps it with one of its totems, Spirit Trance (5 s, x4 defences) paralyzes it while it heals. Dreaming Spirit (sleep) peels.

## 2. Baseline (`matrix-20261113-055911`)

| Against | tank | melee | dagger | archer | nuker | healer | buffer | **all** |
|---|---|---|---|---|---|---|---|---|
| Win % | 80.8 | 25.0 | 2.2 | 5.8 | 19.2 | 94.4 | 58.7 | **35.5 (33-38)** |

| Skill | Casts a battle (4 s each) | Damage per cast | Per cast second |
|---|---|---|---|
| Steal Essence | 21.9 | 1,108 | 1,952 |
| **Frost Flame** | **11.7** (lands 9%) | **41** | **73** |
| **Life Drain** | **10.3** | 269 | 476 |
| Freezing Flame | 7.9 (lands 30%) | 404 | 711 |
| Dreaming Spirit | 4.5 (lands 11%) | - | - |

Time on TOTEM_APPROACH: 22% of a fight against archers, 9% against daggers.

## 3. Diagnosis

| # | Fault | Evidence | Cause | Kind | Fix |
|---|---|---|---|---|---|
| 1 | 88 s of casting a battle on two leftover skills | Frost Flame 11.7 casts at 41 damage (magic level 30, lands 9%; the same DOT_ATTR slot as its own Freezing Flame, power 157), Life Drain 10.3 at 269: 8% of its damage | Attack list `1245,1090,1107` | Build data | `<attack>1245</attack>`. Steal Essence's 2 s reuse runs after its cast, so the gap is a short MAGE_HOLD; a Life Drain there would delay the next Steal Essence for less damage. Batch 1 |
| 2 | Enlightenment (+50% cast speed for 20 s) not in the build | `skill_cards.py`, "LEARNED BUT NOT IN THE BUILD"; every damage spell takes 4 s | Build data | Build data | In `buff` (its 10 min reuse makes it PvP-only). Batch 1 |
| 3 | It walks into a melee's reach, and after kiting archers, to plant the Totem of Flames | `battles/000635-WindRider-vs-FlameWarchief`: TOTEM_APPROACH from 859 to 9, dead from a Backstab at 22 s; 22% of an archer fight on TOTEM_APPROACH | `wantsCloserForFlames` walks up to any target out of the flames' reach | Class AI (`FakePlayerPvpOraclesWarchiefs`, only Totem Warchiefs) | In a PvP only up to a target that stands and casts (role MAGE, a mystic player). Batch 2 |
| 4 | Its totems go down once the melee is already on it | `matrix-20261113-062328`, `battles/000406-Duelist-vs-FlameWarchief`: Totem of Flames at gap -16, Frost-Teeth 2.5 s later | `isTotemWorth`: Frost-Teeth within 400, Flames within 250; a melee with Rush covers that during one Steal Essence | Class AI | Against a melee: Frost-Teeth within 600, Flames within 550. Tried in batch 3, taken out (no effect) |
| 5 | Spirit Walk and Spirit Trance logged as aborted 99-100% | 000406: "Spirit Walk ABORTED" at 31.2 s, and the gap jumps from 0 to 1,367: the swap worked | Their own effects end the cast (the swap, the self paralysis) | Harness | `FakeSimulationManager.jumpsBack` covers SpiritWalk and a self Paralyze |

## 4. The batches

### Batch 1: Steal Essence alone, Enlightenment (`matrix-20261113-061119`)

35.5% → 36.8% (+1.3, 95% -2.6 to +5.2); **daggers 2.2% → 12.2% (+10.0, +2.6 to +17.4) and archers 5.8% → 14.2% (+8.3, +0.8 to +15.9), clearly better**; no kind clearly worse. Frost Flame and Life Drain 22 → 0 casts, Freezing Flame 7.9 → 10.0, Enlightenment 1.0.

### Batch 2: no walk to plant Flames by a melee or an archer (`matrix-20261113-062328`)

36.8% → 38.5% (+1.8, 95% -2.2 to +5.7); **daggers 12.2% → 23.3% (+11.1, +0.1 to +22.2), clearly better**; no kind clearly worse. TOTEM_APPROACH gone against archers, daggers, melee and tanks (still against nukers and healers, as meant); damage dealt 406 → 447 a second.

Both batches against the baseline: 35.5% → 38.5% (+3.1, 95% -0.8 to +7.0); tanks 80.8 → 93.3 (+12.5), daggers 2.2 → 23.3 (+21.1), archers 5.8 → 20.8 (+15.0), each clearly better; melee 25.0 → 22.8 and nukers 19.2 → 16.7, no clear change.

### Batch 3: totems planted before a melee arrives (`matrix-20261113-063634`): taken out

38.5% → 39.3% (+0.8, 95% -3.2 to +4.7), no kind clearly better or worse; melee 22.8% → 23.9%. Frost-Teeth and Flames went down 0.3 times a battle more and the Flames dealt 287 more a battle: a melee with a gap closer is on the Warchief within a second of coming into 600 either way. Taken back out. The harness fix (fault 5) built with it stays: Spirit Walk and Spirit Trance aborted 99-100% → 0%.

## 5. Not fixed here, and next

- **Melee (24%)**: it loses the race standing (it deals 494 a second, takes 622). Damage per second is Steal Essence on a 2 s reuse after a 4 s cast; the Totem of Flames is the only other damage. What is left is a balance question (`docs/class-balance-85.md`), or making it keep out of reach longer (a root: it has none; Dreaming Spirit lands 11%).
- **Nukers (18%)**: out-damaged at range; Steal Essence's 900 reach is its edge and is already used.
