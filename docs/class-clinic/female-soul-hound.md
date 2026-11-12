# Class clinic: Female Soul Hound (nuker build)

The first class taken through `docs/class-clinic.md`, 2026-11-12. Level 85, brain A, seed 4040, 30 battles against each of the 39 other builds (1,170 a run). The Male Soul Hound has the same kit and got the same data changes; it has not had a run of its own.

**Result: 50.6% → 64.6% overall (+14.0, 95% +10.1 to +18.0).** Clearly better against tanks (72% → 94%), melee (51% → 67%), daggers (79% → 92%), archers (29% → 55%) and healers (37% → 53%). Against nukers 31% → 36%, not a clear change: it is out-statted there (section 3).

## 1. The class

From `skill_cards.py "Female Soul Hound"` and the build in `dist/game/data/FakePlayerPvp.xml`:

- A caster in light armour with a rapier: M. Atk about 6,000 to 7,500 where a robe nuker has 22,000, but every cast takes 0.55 s (the cap) and it runs at 245 to 258, faster than every melee build (168 to 230).
- Damage: Soul Vortex (slows, 15 s reuse), Soul Vortex Extinction (only on a target under Soul Vortex), Soul Strike, Aura Blast (15 s each), Leopold (45 s), Soul of Pain (3 s), Curse of Divinity (4 s, **+30% damage for every buff on the target**: about 2.2 times a Soul of Pain on a buffed player), and three weak leftovers from the low levels (Abyssal Blaze power 46, Fallen Arrow 22, Dark Explosion 23, against Soul of Pain's 108).
- Control: Voice Bind (silence 20 s), Shadow Bind (root 15 s), both reach 400; Soul Web (area slow); Death Mark (−23% P. Def and M. Def); Blink (stuns around it and jumps back 400). All learned by 74, so they land little at 85.
- Self: Soul to Empower, Lightning Barrier (paralyzes attackers), Enlightenment (20 s burst), Furious Soul; Painkiller (8 s invincible, **ends on any action**), Arcane Shield; Soul Cleanse; Final Form (a **melee** transformation).

Retail High Five play, from a class guide and a retail forum thread on Soul Breaker PvP ([la2wiki.com](https://la2wiki.com/high-five/class/soul-breaker), [interlude.wiki retail forum archive](https://interlude.wiki/retail/t-198207-about-sb-pvp-guides.html)): read the enemy first. A mage or healer gets Voice Bind, a fighter gets Shadow Bind and is nuked while it stands, anyone in full buffs gets Steal Divinity, as often as it is ready. Against warriors: don't let them close; root or Soul Web, Death Mark, nuke, Blink away and kite. Against daggers: cut their movement first. Against archers: "these guys really hurt us", get close and interrupt Stun Shot. The forum thread is about the rapier (hybrid) way; the guide covers both.

## 2. Baseline (`matrix-20261112-033029`)

| Against | Battles | Win % (95%) | Fight s (won / lost) | Enemy HP left when it loses | Damage /s dealt / taken | Enemy within 200 |
|---|---|---|---|---|---|---|
| **tank** | 120 | 71.7% (63-79) | 78 / 70 | 72% | 325 / 214 | 42% |
| **melee** | 360 | 50.6% (45-56) | 27 / 29 | 39% | 767 / 447 | 39% |
| **dagger** | 90 | 78.9% (69-86) | 20 / 24 | 62% | 785 / 490 | 47% |
| **archer** | 120 | 29.2% (22-38) | 19 / 43 | 47% | 238 / 349 | 63% |
| **nuker** | 240 | 30.8% (25-37) | 32 / 32 | 59% | 576 / 515 | 17% |
| **healer** | 90 | 36.7% (27-47) | 96 / 300 | 92% | 726 / 40 | 0% |
| **buffer** | 150 | 74.0% (66-80) | 43 / 66 | 68% | 658 / 182 | 13% |
| **all** | 1,170 | 50.6% (48-53) | 31 / 38 | 56% | 601 / 262 | 25% |

Its skills, casts per battle: Soul of Pain 11.4, **Abyssal Blaze 9.2 (220 damage a cast, 5% of its damage)**, Soul Vortex 4.8, **Voice Bind 4.6 (lands 10%)**, Soul Strike 4.3, Aura Blast 4.1, **Curse of Divinity 3.4 (1,156 a cast)**, Shadow Bind 2.5 (lands 10%), Death Mark 2.1 (lands 24%), Leopold 1.3. Never cast: Steal Divinity, the rapier skills, the area nukes.

## 3. Diagnosis

| # | Fault | Evidence | Cause | Kind | Fix |
|---|---|---|---|---|---|
| 1 | It turns into a melee fighter in the middle of a fight it is winning | It won **3% of the 188 fights it cast Final Form in**, 60% of the 982 others (59 of 120 fights against archers, 80 of 240 against nukers). In `battles/*GhostSentinel-vs-FemaleSoulHound`: at 67% HP against the archer's 72% it transforms, then chases a kiting archer for a minute with Nail Attack (380 a hit; its nukes did 1,000 to 2,000) | `thinkAttack` casts the build's `transform` skill below 70% HP for any Kamael | Class AI | A build that fights with its spells (role MAGE) doesn't transform. Batch 1 |
| 2 | Its best quick nuke is cast a third as often as its worst | Curse of Divinity 3.4 casts a battle at 2,100 damage per cast second; Abyssal Blaze 9.2 at 400 | The attack list had Abyssal Blaze before Curse of Divinity, and a skill is worth at least its place in the list (`LIST_RANK_FACTOR`). `estimateDamage` didn't know Curse of Divinity's +30% per buff | Build data, general AI (the estimate, which only this skill's effect reaches) | List reordered by damage per cast second; the estimate counts the target's buffs for `MagicalDamageByAbnormal`. Batch 1 |
| 3 | It silences warriors | Voice Bind 3.1 casts a battle against melee, 3.9 against tanks, and it walks from 675 to 375 to do it | The "Voice bind" combo opened with it against anyone, the peel list holds it, and nothing told the scorer a silence needs a caster | Build data, general AI | `mageTarget="true"` on the combo step; `isWastedSilence` in both skill pickers and the peel: no pure silence on a player that doesn't fight with magic, except while it casts a spell or on a tank under a defensive stance (it heals itself behind it) |
| 4 | Against archers and nukers it stands just outside Curse of Divinity's reach | Median gap 661 against archers; Curse of Divinity (reach 600) 0.5 casts a battle there, Abyssal Blaze (750) 3.1 | A ranged fake player drops every option out of reach while one is in reach, so from 661 it never steps in for the better skill | Class AI behind a build attribute | `range="500"`: the build keeps the options that reach from its own distance, and the scorer weighs the short walk. Batch 3 (the first try, batch 2, did nothing) |
| 5 | Soul Vortex Extinction, its best cast, only exists inside a combo | 24% of the Soul Vortexes that landed were not followed by it | Not in the attack list | Build data | Added to the list; its own condition keeps it off a target without Soul Vortex. Batch 2 |
| 6 | It stands in a melee's reach although it is much faster | An enemy within 200 of it 39 to 42% of the fight against tanks and melee; one kite step every 3 to 5 s (`_nextKiteTime`), which a melee walks back in under a second | A pressed mage casts on ("a nuker wins by out-damaging the melee"), which holds for a robe nuker and not for one with a quarter of the M. Atk and 50% more speed | Class AI behind a build attribute | `outrun="true"`: while it is behind (its HP the lower share) against a clearly slower melee on it, it runs out of reach, casts while the melee walks back, and runs again. Ahead, it stands and trades. Batches 3 and 4 |
| 7 | Blink was logged as an aborted cast every time | "Blink is aborted 100% of the time" in the first report | Its own effect calls `abortCast` after it landed | Harness | `FakeSimulationManager.onCastAbort` skips it |

Not fixed here:

- **Nukers (36%): out-statted, not outplayed.** Against Archmage, Mystic Muse, Storm Screamer and Soultaker it deals 740 to 810 a second and takes 1,300 to 1,440, with 18,000 HP against 14,000. It hits first in 87% of those fights and loses the race anyway. What would turn them is a silence that lands (Voice Bind lands 11%: the level 74 skill against level 85 targets) or Steal Divinity (which does nothing to a fake player). See `docs/class-balance-85.md` for the M. Atk gap.
- **Healers (timeouts).** A healer heals back 90% of its damage; most of those fights end at the time limit on a few percent of HP. A duel can't judge this.
- **Painkiller** is broken by its next cast (the skill ends on any action): cast in 241 fights of batch 1, 10% won. Using it to walk away or to wait out a burst is a general finding (`docs/class-clinic.md`).
- **Debuffs that rarely land** still take casts (Shadow Bind 2.2 a battle at 10%, Death Mark 1.6 at 22%, Voice Bind on casters 2 to 23 a battle at 10%). The scorer believes the skills' base rates; general finding.
- **Its own build:** fighters with the Last Light keystone won 30% (69 battles) and with Spell Echo 39% (150) against 52% overall, and the Laevateinn rapier (the gear a few levels behind) 40%. Passive tree and gear, not AI.

## 4. The batches

Each against the run before it, same seed. "Clearly" means the 95% interval of the difference doesn't include zero.

### Batch 1: no Final Form, Curse of Divinity, no silence on fighters (`matrix-20261112-082419`)

| Against | Before | After | Difference (95%) | Verdict |
|---|---|---|---|---|
| **tank** | 71.7% | 82.5% | +10.8 (+0.3 to +21.4) | **better** |
| **melee** | 50.6% | 68.9% | +18.3 (+11.3 to +25.4) | **better** |
| **dagger** | 78.9% | 90.0% | +11.1 (+0.6 to +21.6) | **better** |
| **archer** | 29.2% | 40.8% | +11.7 (−0.3 to +23.6) | no clear change |
| **nuker** | 30.8% | 31.2% | +0.4 (−7.9 to +8.7) | no clear change |
| **healer** | 36.7% | 43.9% | +7.2 (−7.1 to +21.5) | no clear change |
| **buffer** | 74.0% | 78.7% | +4.7 (−4.9 to +14.3) | no clear change |
| **all** | 50.6% | 60.6% | +10.0 (+6.0 to +14.0) | **better** |

Skill use moved as meant: Curse of Divinity 3.4 → 8.4 casts a battle (3,934 → 10,000 damage a battle), Abyssal Blaze 9.2 → 4.9, Voice Bind 4.6 → 3.0, Nail Attack gone. Damage dealt 601 → 764 a second.

### Batch 2: a standing distance, Soul Vortex Extinction in the list (`matrix-20261112-142744`)

Overall 60.6% → 60.4% (−0.2, −4.2 to +3.7): nothing. The report showed why: against archers the median gap was still 686 and Curse of Divinity still 0.6 casts a battle. The standing distance was read by the code that places a mage between casts, which a mage with any skill in reach never gets to. **The win rate said "no effect"; the skill table said "the change never ran".** Extinction went from 1.03 to 1.22 casts a battle, as meant, and stays. The distance was reworked in batch 3.

### Batch 3: the distance where the skill is chosen, and outrunning a slower melee (`matrix-20261112-143822`, against batch 1)

| Against | Before | After | Difference (95%) | Verdict |
|---|---|---|---|---|
| **tank** | 82.5% | 94.2% | +11.7 (+3.7 to +19.7) | **better** |
| **melee** | 68.9% | 66.4% | −2.5 (−9.3 to +4.3) | no clear change |
| **dagger** | 90.0% | 91.1% | +1.1 (−7.4 to +9.7) | no clear change |
| **archer** | 40.8% | 55.8% | +15.0 (+2.5 to +27.5) | **better** |
| **nuker** | 31.2% | 35.6% | +4.4 (−4.1 to +12.8) | no clear change |
| **healer** | 43.9% | 55.0% | +11.1 (−3.4 to +25.6) | no clear change |
| **buffer** | 78.7% | 82.7% | +4.0 (−4.9 to +12.9) | no clear change |
| **all** | 60.6% | 65.0% | +4.3 (+0.4 to +8.2) | **better** |

- The distance works now: against archers Curse of Divinity 0.5 → 2.0 casts a battle and Abyssal Blaze 3.1 → 1.2.
- Outrunning won the tanks (Phoenix Knight 53% → 93%) and did nothing against melee: damage taken fell (437 → 326 a second) and so did damage dealt (956 → 664). `battles/000486-Titan-vs-FemaleSoulHound` shows why: a Titan Rushes every 4 s, so the Hound never got far enough to turn and cast, and ran for a minute without a single cast.

### Batch 4: a run the melee catches is given up (`matrix-20261112-150002`, against batch 3)

A run has failed when the melee is suddenly 150 closer than it was during the run (a gap closer) or when it lasts 8 s. The Hound then stands and fights for 12 s times its failed runs (60 s at most) before it tries again.

| Against | Before | After | Difference (95%) | Verdict |
|---|---|---|---|---|
| **tank** | 94.2% | 94.2% | +0.0 (−5.9 to +5.9) | no clear change |
| **melee** | 66.4% | 67.2% | +0.8 (−6.0 to +7.7) | no clear change |
| **dagger** | 91.1% | 92.2% | +1.1 (−7.0 to +9.2) | no clear change |
| **archer** | 55.8% | 55.0% | −0.8 (−13.4 to +11.7) | no clear change |
| **nuker** | 35.6% | 36.2% | +0.6 (−8.0 to +9.2) | no clear change |
| **healer** | 55.0% | 52.8% | −2.2 (−16.8 to +12.3) | no clear change |
| **buffer** | 82.7% | 78.3% | −4.3 (−13.3 to +4.6) | no clear change |
| **all** | 65.0% | 64.6% | −0.3 (−4.2 to +3.5) | no clear change |

No clear change anywhere, which is what this batch was for: the tanks stay at 94%, and against melee the Hound casts again instead of running (damage dealt 664 → 847 a second, an enemy within 200 of it 38% → 33% of the fight) at the same win rate as batch 1's standing and trading. A first version of this batch (`matrix-20261112-144940`) gave a run up after 4.5 s whatever happened; it brought the melee fights back to 71% and dropped the tanks from 94% to 85% (clearly worse: Phoenix Knight 93% → 73%), because a tank never catches it and the long runs were the point. Only the full run showed that.

## 5. Before and after

Baseline `matrix-20261112-033029` against the last run `matrix-20261112-150002`, 1,170 battles each:

| Against | Before | After | Difference (95%) | Verdict | Damage /s dealt | Damage /s taken | Enemy within 200 |
|---|---|---|---|---|---|---|---|
| **tank** | 71.7% | 94.2% | +22.5 (+13.4 to +31.6) | **better** | 325 → 414 | 214 → 198 | 42% → 27% |
| **melee** | 50.6% | 67.2% | +16.7 (+9.6 to +23.8) | **better** | 767 → 847 | 447 → 389 | 39% → 33% |
| **dagger** | 78.9% | 92.2% | +13.3 (+3.2 to +23.4) | **better** | 785 → 949 | 490 → 405 | 47% → 37% |
| **archer** | 29.2% | 55.0% | +25.8 (+13.8 to +37.9) | **better** | 238 → 975 | 349 → 1075 | 63% → 0% |
| **nuker** | 30.8% | 36.2% | +5.4 (−3.0 to +13.9) | no clear change | 576 → 749 | 515 → 585 | 17% → 3% |
| **healer** | 36.7% | 52.8% | +16.1 (+1.8 to +30.4) | **better** | 726 → 816 | 40 → 39 | 0% → 0% |
| **buffer** | 74.0% | 78.3% | +4.3 (−5.3 to +14.0) | no clear change | 658 → 795 | 182 → 167 | 13% → 12% |
| **all** | 50.6% | 64.6% | +14.0 (+10.1 to +18.0) | **better** | 601 → 763 | 262 → 276 | 25% → 14% |

Three full runs with the last three versions of the code gave 65.0%, 65.9% and 64.6% overall: the run-to-run noise is about a point.

## 6. What changed

- `ai/FakePlayerPvpAI.java` and `ai/FakePlayerPvpAIExperimental.java` (the same in both): no transformation for role MAGE; `estimateDamage` for `MagicalDamageByAbnormal`; `isWastedSilence`; options that reach from the build's `range` are kept; `outrun`.
- `dist/game/data/FakePlayerPvp.xml`, Male and Female Soul Hound: the attack list reordered with Soul Vortex Extinction added, `mageTarget` on the Voice Bind combo step (and Curse of Divinity in that combo instead of Abyssal Blaze), `range="500"`, `outrun="true"`. The two attributes are documented at the top of the file and in `xsd/FakePlayerPvp.xsd`.
- `model/actor/holders/npc/FakePlayerPvpBuild.java`, `data/xml/FakePlayerPvpData.java`: the two attributes.
- Guard: the code changes are reached only through the Soul Hound builds' data (`range`, `outrun`, their `transform` skill, Curse of Divinity's effect), except `isWastedSilence`, which applies to every build with a pure silence against a target that doesn't fight with magic. It only removes casts that do nothing; no guard run was made for it.

## 7. Next for this class

- A run for the Male Soul Hound (same kit, same data changes) to confirm the gains carry over.
- The hybrid Soul Hound builds keep Final Form (role FIGHTER): they deserve their own clinic.
- The general findings in `docs/class-clinic.md` (land chance, Painkiller, buff steals on fake players) are what is left against nukers.
