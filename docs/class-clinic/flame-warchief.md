# Class clinic: Flame Warchief (the Warcryer line)

Taken through `docs/class-clinic.md` on 2026-11-13. Level 85, brain A, seed 4040, 1,170 battles a run (about 10 minutes: its fights are long). **Result: 35.5% → 41.7% (+6.2, 95% +2.3 to +10.2).** Clearly better against tanks (81% → 94%), daggers (2% → 32%) and archers (6% → 23%); melee (25%) and nukers (23%) about where they were.

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
| 6 | It plants the Totem of Blood when anything hits it, an archer 700 away included | Reported from play; `matrix-20261113-062328`: 278 Blood totems against archers at a median gap of 714, 1,311 against nukers | `isTotemWorth`: Blood below 95% HP in a PvP. Blood gives life steal on normal hits (absorbDam), and the Warchief fights with spells | Class AI | Only with party members around. Batch 4 |
| 5 | Spirit Walk and Spirit Trance logged as aborted 99-100% | 000406: "Spirit Walk ABORTED" at 31.2 s, and the gap jumps from 0 to 1,367: the swap worked | Their own effects end the cast (the swap, the self paralysis) | Harness | `FakeSimulationManager.jumpsBack` covers SpiritWalk and a self Paralyze |

## 4. The batches

### Batch 1: Steal Essence alone, Enlightenment (`matrix-20261113-061119`)

35.5% → 36.8% (+1.3, 95% -2.6 to +5.2); **daggers 2.2% → 12.2% (+10.0, +2.6 to +17.4) and archers 5.8% → 14.2% (+8.3, +0.8 to +15.9), clearly better**; no kind clearly worse. Frost Flame and Life Drain 22 → 0 casts, Freezing Flame 7.9 → 10.0, Enlightenment 1.0.

### Batch 2: no walk to plant Flames by a melee or an archer (`matrix-20261113-062328`)

36.8% → 38.5% (+1.8, 95% -2.2 to +5.7); **daggers 12.2% → 23.3% (+11.1, +0.1 to +22.2), clearly better**; no kind clearly worse. TOTEM_APPROACH gone against archers, daggers, melee and tanks (still against nukers and healers, as meant); damage dealt 406 → 447 a second.

Both batches against the baseline: 35.5% → 38.5% (+3.1, 95% -0.8 to +7.0); tanks 80.8 → 93.3 (+12.5), daggers 2.2 → 23.3 (+21.1), archers 5.8 → 20.8 (+15.0), each clearly better; melee 25.0 → 22.8 and nukers 19.2 → 16.7, no clear change.

### Batch 3: totems planted before a melee arrives (`matrix-20261113-063634`): taken out

38.5% → 39.3% (+0.8, 95% -3.2 to +4.7), no kind clearly better or worse; melee 22.8% → 23.9%. Frost-Teeth and Flames went down 0.3 times a battle more and the Flames dealt 287 more a battle: a melee with a gap closer is on the Warchief within a second of coming into 600 either way. Taken back out. The harness fix (fault 5) built with it stays: Spirit Walk and Spirit Trance aborted 99-100% → 0%.

### Batch 4: Totem of Blood only for a party (`matrix-20261113-065253`): kept

38.5% → 41.7% (+3.2, 95% -0.8 to +7.1), no kind clearly worse; daggers +8.9, nukers +6.7. Blood 3.5 → 0 casts a battle; the time went to Steal Essence (+1.0) and the Totem of Flames (+0.5). Baseline to here: 35.5% → 41.7% (+6.2, 95% +2.3 to +10.2), clearly better.

### Batch 5: Shatter on a player caught by two explosions (`matrix-20261113-070438`): taken out

The play suggested: when a melee comes in among its totems, Shatter. 41.7% → 39.9% (-1.8, 95% -5.8 to +2.2), no clear change. Shatter only +0.1 casts a battle: it needs three totems up (the Totem Circle), and without the Totem of Blood (batch 4) three are rarely up at once; what it added (+302 damage a battle) the Totems of Flames lost (-627), their burn cut short.

### Batch 6: Spirit Walk onto a totem by the player, then Flames (`matrix-20261113-071606`): taken out

The play suggested: a totem left behind that the player ran past, or one near a player at range, is a way back in: swap with it and plant Flames there. 41.7% → 40.7% (-1.0, 95% -5.0 to +3.0), no clear change. It ran 616 times (Spirit Walk 0.6 → 1.1 casts a battle) and works as meant (`battles/000009-ShillienTemplar-vs-FlameWarchief`: the swap at a gap of 835, the Totem of Flames 0.8 s later at 172), but the Flames planted that way didn't move the fights, and the swap spends Spirit Walk's 30 s reuse, which is also its escape.

### Batches 7 to 9: fighting in the fire (taken out)

Asked for: the Totem of Flames has more in it than the AI got out of it. Measured first, with a script over the battle logs (pulses of each Totem of Flames against where the enemy stood): in `matrix-20261113-065253` the Flames burned its target in **44% of the pulses against melee, 24% against tanks, 53% against daggers**, and in 52% (melee) and 73% (tanks) of the pulses the enemy was out of the fire because **the Warchief itself had kited away from its totem**, the melee following it out. At full uptime one Totem of Flames burns a melee for about 200 a second, as much as Steal Essence. Each version below kept the melee in the fire; none won more fights, all against `matrix-20261113-065253` (41.7%):

| Batch | What it did | Fire uptime vs melee / tank / dagger | Win % (95%) | Damage /s vs melee: dealt / taken |
|---|---|---|---|---|
| 7 (`matrix-20261113-073745`) | Holding the fire: in reach of its own Totem of Flames against a melee, a kite step goes to the far side of the totem, or it stands and casts | 88% / 73% / 97% | 36.5% (-5.3, -9.2 to -1.3), **worse**; tanks -15.0, melee -6.5 clearly worse | 526 → 591 / 596 → 805 |
| 7b (`matrix-20261113-075921`) | The same, but each kite step is an arc around the totem (radius 200), away from the melee's side | 88% / 72% / 100% | 39.8% (-1.9, -5.9 to +2.1) | 526 → 563 / 596 → 706 |
| 8 (`matrix-20261113-081052`) | 7b, and it stacks Totems of Flames while it holds the fire (up to 3; each extra one burns for half) | 86% / 76% / 95% | 38.6% (-3.1, -7.1 to +0.9); tanks -9.2 clearly worse | 526 → 576 / 596 → 677 |
| 9 (`matrix-20261113-082214`) | 8, and Spirit Trance (x4 defences for 5 s) below 75% HP with a melee on it burning in two Totems of Flames | 86% / 77% / 96% | 40.3% (-1.4, -5.4 to +2.6); melee +2.5, daggers -11.1 | 526 → 566 / 596 → 686 |

What they show: the fire was there (Flames damage 5,692 → 7,715 a battle in batch 9, burns against melee 12 → 22 a battle), but every way of staying in it gave the melee more time on the Warchief than the fire paid back: a melee does about 800 a second to a Warchief that doesn't move away, the fire about 200 (two stacked, 300). Kiting away from the totem was worth more than the burns it cost. Spirit Trance from the buff list also went off at low HP against archers and nukers, paralyzed (disabled 10% → 19% of an archer fight).

The trade depends on tuning, not on the AI: `dist/game/config/Custom/OraclesWarchiefs.ini` documents defaults of FlameTotemRange 400 and FlameTotemPulse 1667 (a burn 20% more often) and runs 350 and 2000. With those, or a stronger burn, holding the fire may pay; a balance question for `docs/class-balance-85.md`. The code of batches 7 to 9 was not committed: `getHeldFire` (a Totem of Flames within FlameTotemRange - 50 of the Warchief, not about to fall, against a melee that isn't an archer or caster), a `kiteStep` that arcs 200 round that totem instead, stacking Flames while it holds one, and Spirit Trance in the fire below 75% HP.

## 5. Not fixed here, and next

- **Shatter and the Totem Circle.** Shatter needs three totems up; Blood was the cheap third one. A Shatter plan (plant Blood or Frost-Teeth as the third totem when a melee comes in among Flames, then Shatter) would need the two together; not tried.
- **The Totem of Flames' tuning** (batches 7 to 9): range 350 and a pulse every 2 s, where the config documents 400 and 1.667 s. At these numbers fighting in the fire loses to kiting away from it.
- **Melee (24%)**: it loses the race standing (it deals 494 a second, takes 622). Damage per second is Steal Essence on a 2 s reuse after a 4 s cast; the Totem of Flames is the only other damage. What is left is a balance question (`docs/class-balance-85.md`), or making it keep out of reach longer (a root: it has none; Dreaming Spirit lands 11%).
- **Nukers (18%)**: out-damaged at range; Steal Essence's 900 reach is its edge and is already used.
