# Class clinic: Female and Male Soul Hound Hybrid

The hybrid Soul Hounds taken through `docs/class-clinic.md`, 2026-11-13, after the nuker Soul Hound (`female-soul-hound.md`). Level 85, brain A, seed 4040, 30 battles against each of the 39 other builds (1,170 a run). The two share an AI and a kit; the Female one also has the Warder's Warp and Ultimate Escape. Both were run for every batch.

## 1. The class

From `skill_cards.py "Female Soul Hound Hybrid"` and the builds in `dist/game/data/FakePlayerPvp.xml`:

- A Soul Hound in a light (dagger) armour set with a rapier with Health, and a mace with Acumen on a hotkey (`caster="SOUL_MACE"`): P. Atk about 3,000 to 4,000, **M. Atk about 2,100 to 3,100** (the nuker build has 6,000 to 7,500), speed 217 to 234. Role FIGHTER; with the mace out the AI plays it as a mage (`chooseCaster`, `thinkAttack`).
- Against a melee player it always held the mace; against anyone else it held the rapier and took the mace out from 400 or farther for a mace combo.
- Rapier skills: Triple Thrust (power 3,069), Shining Edge (5,479, a blow that lands 20%), Double Thrust, and **Checkmate (5,180, only on a target under Death Mark), learned and never used**; Spread Wing (2,192, around it) also unused. Skill power is added to P. Atk, so Triple Thrust hits for 500 to 650 on a healer, while the rapier's **normal attack averages 2,100 a hit** (a third of all its damage).
- The rest of the kit is the nuker's (see `female-soul-hound.md`).

Retail play, from la2wiki's High Five Soul Breaker guide ([la2wiki.com](https://la2wiki.com/high-five/class/soul-breaker)) and a retail forum thread on Soul Breaker PvP from 2008 (Gracia, before High Five: [interlude.wiki archive](https://interlude.wiki/retail/t-198207-about-sb-pvp-guides.html)): against a warrior, "root/soul web, then death mark/SoP/rush/disarm/checkmate/SE/TT/blink and run back... if death mark landed you would have done a lot of damage with Checkmate. After you use blink nuke again with SoP/AC and try to kite". In phases: debuff, nuke and kite; close-range damage; Blink out and kite. Against a mage: close in and spam the rapier skills. Against an archer: stay on it. The guide: "Death Mark... Checkmate is a heavy blow that works only on a marked target"; levelling as "mark it, Soul of Pain while it walks in, finish with Checkmate and Triple Thrust".

## 2. Baselines

| Against | Female (`matrix-20261113-113944`) | Male (`matrix-20261113-114848`) |
|---|---|---|
| tank | 46.7% (38-56) | 40.0% (32-49) |
| melee | 32.1% (27-37) | 42.1% (37-48) |
| dagger | 62.2% (52-72) | 68.9% (59-78) |
| archer | 60.8% (52-69) | 65.8% (57-74) |
| nuker | 48.9% (43-55) | 46.7% (41-53) |
| healer | 99.4% (95-100) | 98.9% (94-100) |
| buffer | 74.7% (67-81) | 74.0% (66-80) |
| **all** | **53.4% (51-56)** | **55.9% (53-59)** |

Female, casts per battle: Soul of Pain 10.0, Triple Thrust 5.3 (0.0 against melee and daggers), Shining Edge 4.8, Shadow Bind 3.3 (lands 10%), Soul Vortex 3.1, Curse of Divinity 2.9 (1,763 per cast second, its best with Soul Strike's 1,910), Soul Rage 2.4 (191 a cast; **15.2 a battle against tanks**), Blink 1.2 (its stun lands 3 to 7%). Normal attack: 37% of its damage.

## 3. Diagnosis

| # | Fault | Evidence | Cause | Kind | Fix |
|---|---|---|---|---|---|
| 1 | None of the nuker Soul Hound's fixes | The hybrids' lists still had Abyssal Blaze before Curse of Divinity, no Soul Vortex Extinction, Aura Blast or Aura Cannon, no Lightning Shock, Hard March, Warp or Ultimate Escape, no `range` or `outrun` | Only the nuker builds were changed in its clinic | Build data | The port, batch 1 |
| 2 | With a melee on it, it stands in the melee's reach with the mace | Female 32% against melee; an enemy within 200 of it 44% of the fight. `battles/001037-FemaleSoulHoundHybrid-vs-Judicator`: the Judicator never more than 160 away for 20 s, the Hound casts Soul Rage four times for 122 each while it takes crits of 2,000. Against the Phoenix Knight (13%) its nukes hit for 1 to 870 | `chooseCaster` takes the mace out against any melee; with half a nuker's M. Atk it has no damage there, and its rapier (its best damage) stays away | Class AI behind a build attribute | `caughtMain`, batch 2 |
| 3 | Its signature combo is missing | Checkmate (5,180, needs Death Mark) learned, in no list; Death Mark lands 21% | Build data never listed it | Build data | In the attack list ahead of the rapier skills; its own condition keeps it off unmarked targets. Batch 2 |
| 4 | Soul Rage takes casts | 2.4 casts a battle at 191 (122 with the mace), 15.2 a battle against tanks | In the attack list | Build data | Out of the list. Batch 2 |
| - | Final Form is **not** a fault here | Female: won 63% of the 628 fights it transformed in, 42% of the others; Male 65% against 46%. The nuker won 3% | The transformation doubles P. Atk and P. Def, which a rapier fighter uses | - | Kept (the nuker's "no Final Form" rule is for role MAGE only) |

## 4. The batches

Each against the run before it, same seed. "Clearly" means the 95% interval of the difference doesn't include zero.

### Batch 1: the nuker Soul Hound's changes

Attack list ordered as the nuker's (Leopold, Soul Vortex Extinction, Aura Blast, Aura Cannon, Soul Strike, Soul Vortex, Curse of Divinity, Soul of Pain, then the rapier skills, then Abyssal Blaze and Soul Rage); `range="500"` and `outrun="true"` (both only act with the mace out, when the AI plays it as a mage); Lightning Shock in `peel`, Hard March in `toggle`; Warp and Ultimate Escape in `move` (Female only: the Male doesn't learn them); Soul Vortex Extinction in the mace's Soul Vortex combo. Final Form kept.

Female (`matrix-20261113-115903` against `-113944`):

| Against | Before | After | Difference (95%) | Verdict |
|---|---|---|---|---|
| **tank** | 46.7% | 70.0% | +23.3 (+11.2 to +35.5) | **better** |
| **melee** | 32.1% | 43.0% | +10.9 (+3.6 to +18.3) | **better** |
| **dagger** | 62.2% | 68.9% | +6.7 (−7.2 to +20.5) | no clear change |
| **archer** | 60.8% | 74.2% | +13.3 (+1.6 to +25.1) | **better** |
| **nuker** | 48.9% | 53.3% | +4.4 (−4.0 to +12.9) | no clear change |
| **healer** | 99.4% | 98.9% | −0.6 (−3.2 to +2.1) | no clear change |
| **buffer** | 74.7% | 84.0% | +9.3 (+0.2 to +18.4) | **better** |
| **all** | 53.4% | 62.9% | +9.5 (+5.6 to +13.5) | **better** |

Male (`matrix-20261113-120822` against `-114848`):

| Against | Before | After | Difference (95%) | Verdict |
|---|---|---|---|---|
| **tank** | 40.0% | 54.2% | +14.2 (+1.7 to +26.7) | **better** |
| **melee** | 42.1% | 41.1% | −1.1 (−8.6 to +6.5) | no clear change |
| **dagger** | 68.9% | 58.9% | −10.0 (−24.0 to +4.0) | no clear change |
| **archer** | 65.8% | 81.7% | +15.8 (+4.9 to +26.8) | **better** |
| **nuker** | 46.7% | 54.8% | +8.1 (−0.3 to +16.6) | no clear change |
| **healer** | 98.9% | 98.3% | −0.6 (−4.0 to +2.9) | no clear change |
| **buffer** | 74.0% | 82.0% | +8.0 (−1.3 to +17.3) | no clear change |
| **all** | 55.9% | 60.8% | +4.9 (+0.9 to +8.9) | **better** |

Skill use moved as meant (Female): Warp 0 → 2.5 casts a battle, Aura Blast 2.8, Aura Cannon 1.7 (2,753 damage a battle), Soul Vortex Extinction 0.9, Soul of Pain 10.0 → 5.2. Phoenix Knight 13% → 63%, Doom Oracle 17% → 60%. The Male gains less against melee: without Warp it has only Blink to get away with.

### Batch 2: the rapier when a melee has caught it, Checkmate, no Soul Rage

New build attribute `caughtMain` (`FakePlayerPvpBuild`, `FakePlayerPvpData`, `xsd/FakePlayerPvp.xsd`, documented at the top of `data/FakePlayerPvp.xml`): in `chooseCaster`, against a melee within 120 (kept until 300) while it has no jump ready (a `move` or `peel` skill with a Blink effect: Warp, Blink), it takes the rapier out; once a jump is ready it takes the mace out again, and the mage's pressed block jumps it away. Checkmate in the attack list ahead of Triple Thrust; Soul Rage out.

| Against | Female before | after | Difference (95%) | Male before | after | Difference (95%) |
|---|---|---|---|---|---|---|
| tank | 70.0% | 61.7% | −8.3 (−20.3 to +3.6) | 54.2% | 49.2% | −5.0 (−17.6 to +7.6) |
| melee | 43.0% | 44.2% | +1.2 (−6.4 to +8.8) | 41.1% | 44.4% | +3.3 (−4.2 to +10.9) |
| **dagger** | 68.9% | 82.2% | **+13.3 (+0.9 to +25.7)** | 58.9% | 77.8% | **+18.9 (+5.6 to +32.2)** |
| archer | 74.2% | 73.3% | −0.8 | 81.7% | 79.2% | −2.5 |
| nuker | 53.3% | 53.0% | −0.4 | 54.8% | 52.8% | −2.0 |
| healer | 98.9% | 99.4% | +0.6 | 98.3% | 99.4% | +1.1 |
| buffer | 84.0% | 81.3% | −2.7 | 82.0% | 75.7% | −6.3 (−15.6 to +2.9) |
| **all** | 62.9% | 62.9% | +0.0 (−3.9 to +4.0) | 60.8% | 61.2% | +0.4 (−3.5 to +4.4) |

Runs: Female `matrix-20261113-121845`, Male `matrix-20261113-122804`, each against its batch 1 run.

- Daggers clearly better on both builds; nothing else clear. Tanks down on both (not clear on either).
- The rule fires seldom: swaps to the rapier in 300 melee fights went from 185 to 308 (Female, who has Warp every 10 s besides Blink) and from 176 to 336 (Male, Blink only).
- When it fires the rapier phase is short and weak: in `battles/000101-FemaleSoulHoundHybrid-vs-Judicator` (28.8 to 33.9 s) it casts Triple Thrust (285), Soul of Pain (252, weaker without the mace's Acumen), a Shining Edge that misses and Aura Blast (460), and **no normal attack**, the rapier's strongest damage (300 to 2,100 a hit). Why the scorer leaves it is a general question (its normal-attack estimate), not done here.
- Checkmate: 0.3 to 0.4 casts a battle, about 300 damage a battle (Death Mark lands 21%, and a marked target in rapier reach is rare). Soul Rage: gone (1.1 to 1.3 casts, 200 to 270 damage a battle).
