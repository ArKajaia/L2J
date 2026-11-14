# Class clinic: Dominator (the Overlord line)

Taken through `docs/class-clinic.md` on 2026-11-14. Level 85, brain A, seed 4040, 30 battles against each of the 39 other builds (1,170 a run). The Dominator (classId 115) is the Overlord that fights; the Doomcryer is the Warcryer as a buffer.

**Result: 30.0% → 71.3% overall (+41.3, 95% +37.6 to +45.0).** Clearly better against tanks (5% → 94%), melee (18% → 84%), daggers (8% → 57%), archers (10% → 55%) and buffers (65% → 91%); nukers (31% → 33%) and healers (98% → 96%) where they were. Build data, a class AI rule only the Dominator reaches, and CP heals that work on fake players (guard run on the Duelist).

## 1. The class

From `skill_cards.py "Dominator"`: a robe caster with one damage spell, **Steal Essence** (4 s cast, 2 s reuse, reach 900, drains 80% of what it deals), and a kit of seals: debuffs around itself (target AURA, no reach of their own, area 200) at magic level 74 to 79. Seal of Despair (P. Atk −10%, speed −20%, M. Def −30%, attack speed −30%, 30 s reuse), Seal of Blockade (power 80 and no normal attacks for 10 s, 75 s reuse, lands 80% base), Seal of Disease (heals −50%, reach 600), Silence, Binding (root), Slow, Winter, Chaos, Poison, plus Dreaming Spirit (sleep), Fear and Madness. Its old attack spells (Frost Flame, Chill Flame, Venom, Aura Sink, Blaze Quake) are magic level 14 to 35. Chants and Pa'agrio buffs for a party; Arcane Power (M. Atk ×1.3) and Soul Guard (P. Def +653) toggles; Hammer Crush and Stun Attack with a blunt.

Retail play (High Five): a mass debuffer for a clan. The sources found describe the Overlord/Dominator as a buffer and mass debuffer whose key PvP tools are Seal of Disease, Despair and Blockade ([l2calendar.com](https://l2calendar.com/blog/high-five-best-classes)); Seal of Limit stacks with Suspension and Despair ([rezidentev.ucoz.ru](http://rezidentev.ucoz.ru/forum/73-238-1), from the search summary); Suspension against a caster or dagger that closes in ([forum.pmfun.com](https://forum.pmfun.com/viewtopic.php?t=419), a 2007 post, pre-High Five). No High Five duel guide turned up.

A full guide read afterwards: [maxcheaters.com, "[Guide] Lineage II: Dominator"](https://maxcheaters.com/topic/59811-guide-lineage-ii-dominator/) (2010; the author says it leaves out the C6 and later skills, so it predates High Five and its Seals of Despair and Blockade by level only). What it says, against what the clinic measured:

| The guide | At level 85 here | Done |
|---|---|---|
| Steal Essence "the most important (and sometimes only) attacking skill. If you don't use it, you're dead" | 81% of its damage at the baseline, 95% now | Its only attack (batch 1) |
| Against a melee: sleep first, then Seal of Binding ("once he gets rooted he has no chance"), then Despair or Suspension | Dreaming Spirit and Seal of Binding land 11% on a level 85 target in full buffs (the guide: Dreaming Spirit's land rate "is too big" on private servers) | Both out (batches 4, 5: +13.1 and melee +9.2); Despair kept in a combo started in reach (batch 3) |
| **"Heal CP / hit, heal CP / hit"** with Ritual of Life (CP 858) between drains; its basic rule | A fake player has no CP: its class CP is the top of its HP pool (`FakePvpIncludeCpInHp`), and every CP heal effect restored nothing on it | CP heals now restore a fake player's HP (batch 6), Honor of Pa'agrio as its heal (batch 7) |
| Seal of Suspension "one of my favourite skills": the target's reuse ×3 | Lands 43%, but a 4 s cast in the race with a dagger | Tried, daggers clearly worse (batch 8) |
| Seal of Gloom against a mage, to drain its MP | Fake players cast for free | Not used |
| Seal of Disease on healers ("you win") | It wins 96 to 99% against healers already | Kept |
| Soul Cry, Soul Guard, Hammer Crush in fighter gear | It fights in a robe with a mage blunt | Soul Guard only (batch 1) |

Yardstick for a duel: Steal Essence for the damage, the seals that land (Despair, Blockade, Disease) on a target in reach, nothing that lands 10%.

## 2. Baseline (`matrix-20261114-132505`)

| Against | Battles | Win % (95%) | Fight s (won / lost) | Enemy HP left when it loses | Damage /s dealt / taken | Enemy within 200 |
|---|---|---|---|---|---|---|
| **tank** | 120 | 5.0% (2-10) | 197 / 101 | 89% | 97 / 275 | 71% |
| **melee** | 360 | 18.1% (14-22) | 65 / 27 | 75% | 390 / 756 | 60% |
| **dagger** | 90 | 7.8% (4-15) | 52 / 18 | 91% | 254 / 1462 | 58% |
| **archer** | 120 | 10.0% (6-17) | 26 / 18 | 71% | 414 / 1847 | 6% |
| **nuker** | 240 | 30.8% (25-37) | 51 / 28 | 82% | 462 / 745 | 13% |
| **healer** | 90 | 98.3% (93-100) | 183 / 300 | 96% | 404 / 20 | 50% |
| **buffer** | 150 | 65.3% (57-72) | 87 / 56 | 67% | 402 / 271 | 58% |
| **all** | 1,170 | 30.0% (27-33) | 80 / 28 | 80% | 351 / 466 | 50% |

| Skill | Casts a battle | Lands | Damage per cast | Share of damage |
|---|---|---|---|---|
| Steal Essence | 15.1 | - | 1,308 | 81% |
| Frost Flame | 7.4 | 10% | 51 | 2% |
| Dreaming Spirit | 5.5 | 11% | - | - |
| Life Drain | 4.7 | - | 278 | 5% |
| Seal of Silence | 4.1 | 10% | - | - |
| Seal of Binding | 3.7 | 11% | - | - |
| Chant of Life (7 s cast) | 3.4 (9.0 against tanks) | - | - | - |
| Seal of Disease | 2.4 | 22% | - | - |
| Chill Flame | 1.9 | 11% | 23 | 0% |
| Fear | 1.8 | 11% | - | - |
| Seal of Despair, Seal of Blockade | 0, 0.2 | | | |

Openings: "Dreaming Spirit > Fear > Dreaming Spirit" against 62% of daggers and 25% of melee.

## 3. Diagnosis

| # | Fault | Evidence | Cause | Kind | Fix |
|---|---|---|---|---|---|
| 1 | Casts on spells of magic level 14 to 35 | Frost Flame 7.4 casts at 51 a cast, Chill Flame 23, Venom 6, Aura Sink none; Life Drain 278 against Steal Essence's 1,308 (same 4 s, reach 600 against 900) | `attack` listed them after Steal Essence; its 2 s reuse leaves a gap they filled | Build data | `<attack>1245</attack>`. Batch 1 |
| 2 | Debuffs that land 10% take a third of its casting | Dreaming Spirit, Silence, Binding, Fear 6 to 15% landed over thousands of tries; `000639-Dominator-vs-Duelist`: Dreaming Spirit, Silence, Dreaming Spirit, Seal of Disease, Fear in 18 s with a Duelist on it, all resisted, dead at 21 s | `debuff`, `peel` and the "Silence and drain" / "Bind and drain" combos | Build data | `debuff` down to Blockade, Despair, Disease; `peel` down to Dreaming Spirit and Seal of Binding (batch 1), then Binding alone (batch 4), then none (batch 5); the two combos out |
| 3 | A 7 s Chant of Life (heal over time, power 23) as its own heal, with a melee on it | 9 casts a battle against tanks; `000639`: cast at 50% HP with the Duelist at gap −16. Win 8.6% in the fights it is cast in | `heal` held it; the `groupHeal` copy kept casting it after batch 1 (3.4 a battle): every clinic fighter is in a party of one, and `supportParty` casts a group heal for one hurt member when the build has no `partyHeal` | Build data | Out of `heal` (batch 1) and `groupHeal` (batch 2). Only the Sword Muse also has a `groupHeal` without a `partyHeal`; the code was left alone |
| 4 | It walks from 900 into a mage's or an archer's reach to open a combo with a seal around itself | `matrix-20261114-133553`, `000030-Dominator-vs-DoomOracle`: `APPROACH COMBO Seal of Despair` from 929 down to 210, Dryad Root and Doom Strike there; nukers 31% → 18% (clearly worse) once batch 1 left the Despair combo as its only one | `playCombo` walks to cast any first step; an AURA has reach 0 | Class AI | `isComboReady`: a ranged build doesn't start a combo whose first step is an aura around itself (`isAuraAround`) unless the target is in its area. Batch 3 (with the combo back) |
| 5 | Soul Guard (P. Def +653) never used | "LEARNED BUT NOT IN THE BUILD"; it loses to physical damage with 1,300 P. Def | Build data | Build data | In `toggle` with Arcane Power. Batch 1 |

Not used, after reading their cards: Seal of Suspension (reuse ×3, lands 60% base at magic level 74, 4 s), Seal of Limit (recovery capped at 30%, 1.5 s; against healers it already wins 98%), Turn to Stone (8 s it can't act), Ritual of Life and the Heart / Honor of Pa'agrio (CP and HP heals of 172 to 858). Hammer Crush and Stun Attack were already skipped by `pickPeel` for a mage (0 casts).

## 4. The batches

### Batch 1: Steal Essence alone, the 10% seals and Chant of Life out, Soul Guard (`matrix-20261114-133553`)

30.0% → 44.1% (+14.2, 95% +10.3 to +18.1). Clearly better against tanks (+57.9), melee (+19.0), daggers (+22.2), archers (+31.7); **clearly worse against nukers (−12.9)**. Steal Essence 15.1 → 24.1 casts, damage taken 466 → 337 a second. Seal of Despair 0 → 3.1 and Seal of Blockade 0.2 → 1.7 casts (the Despair combo, now its only one, ran), Chant of Life still 3.4 (fault 3, `groupHeal`). The nuker loss is fault 4.

### Batch 2: no Despair combo, no `groupHeal` (`matrix-20261114-134821`)

44.1% → 44.8% (+0.6, no clear change): nukers +16.2 and archers +13.3, clearly better (no more walking in); **melee −10.1 and daggers −14.4, clearly worse**. Without the combo the scorer cast Seal of Despair 0.09 times a battle and Blockade 0.4: against a melee on it, those two seals were its best tools. Chant of Life 3.4 → 0.

### Batch 3: the Despair combo back, started only with the target in its area (`matrix-20261114-140219`): kept

Code: `isComboReady` in `FakePlayerPvpAI` and `FakePlayerPvpAIExperimental` (`isAuraAround`). Of all builds only the War Dancer (a melee) has a combo opening with an aura, so the rule reaches the Dominator alone.

44.8% → 53.9% (+9.1, 95% +5.1 to +13.1). Clearly better against melee (+19.3), daggers (+21.1), buffers (+14.3); no kind clearly worse (archers −4.2, nukers −5.0). Seal of Despair 0.09 → 1.48 casts (3.1 against tanks, 2.3 against melee, 0.0 against archers, 0.1 against nukers), Blockade 0.4 → 0.9.

| Against | Baseline | Batch 3 | Difference (95%) |
|---|---|---|---|
| tank | 5.0% | 75.8% | +70.8 (+62.2 to +79.4) |
| melee | 18.1% | 46.2% | +28.2 (+21.7 to +34.7) |
| dagger | 7.8% | 36.7% | +28.9 (+17.5 to +40.3) |
| archer | 10.0% | 50.8% | +40.8 (+30.4 to +51.3) |
| nuker | 30.8% | 29.2% | −1.7 (−9.9 to +6.5) |
| healer | 98.3% | 98.9% | +0.6 (−2.9 to +4.0) |
| buffer | 65.3% | 80.0% | +14.7 (+4.7 to +24.6) |
| **all** | **30.0%** | **53.9%** | **+23.9 (+20.1 to +27.8)** |

### Batch 4: Dreaming Spirit out of `peel` (`matrix-20261114-141516`): kept

53.9% → 67.0% (+13.1, 95% +9.1 to +17.0). Clearly better against tanks (+16.7), melee (+27.5), buffers (+10.7); no kind clearly worse. Dreaming Spirit 11.3 → 0 casts (it had been 22.5 a battle against tanks, 16.5 against melee, landing 11%); the time went to Steal Essence (+1.7) and Seal of Binding (+1.1). Damage dealt against melee 436 → 641 a second, the melee within 200 63% → 52% of the fight.

| Against | Baseline | Batch 4 | Difference (95%) |
|---|---|---|---|
| tank | 5.0% | 92.5% | +87.5 (+81.4 to +93.6) |
| melee | 18.1% | 73.8% | +55.7 (+49.7 to +61.7) |
| dagger | 7.8% | 48.9% | +41.1 (+29.4 to +52.8) |
| archer | 10.0% | 53.3% | +43.3 (+32.9 to +53.7) |
| nuker | 30.8% | 31.7% | +0.8 (−7.5 to +9.1) |
| healer | 98.3% | 96.7% | −1.7 (−6.2 to +2.9) |
| buffer | 65.3% | 90.7% | +25.3 (+16.4 to +34.3) |
| **all** | **30.0%** | **67.0%** | **+37.0 (+33.2 to +40.8)** |

### Batch 5: no `peel` at all (Seal of Binding out) (`matrix-20261114-142708`): kept on its own measure

67.0% → 69.7% (+2.7, 95% −1.1 to +6.5, no clear change overall); **melee +9.2 (+3.2 to +15.1), clearly better**; no kind clearly worse (daggers −3.3, −17.9 to +11.2). Seal of Binding 3.7 → 0 casts (lands 11%), Steal Essence +0.8, damage taken against melee 430 → 389 a second. Kept under the clinic's exception: the measure moved as meant and nothing got worse.

| Against | Baseline | Batch 5 (final) | Difference (95%) |
|---|---|---|---|
| tank | 5.0% | 93.3% | +88.3 (+82.4 to +94.3) |
| melee | 18.1% | 82.9% | +64.9 (+59.3 to +70.4) |
| dagger | 7.8% | 45.6% | +37.8 (+26.1 to +49.5) |
| archer | 10.0% | 55.0% | +45.0 (+34.6 to +55.4) |
| nuker | 30.8% | 30.4% | −0.4 (−8.7 to +7.8) |
| healer | 98.3% | 96.1% | −2.2 (−7.0 to +2.6) |
| buffer | 65.3% | 92.0% | +26.7 (+17.9 to +35.4) |
| **all** | **30.0%** | **69.7%** | **+39.7 (+36.0 to +43.4)** |

### Batch 6: CP heals restore a fake player's HP; Ritual of Life and Honor of Pa'agrio as its heals (`matrix-20261114-150831`)

From the guide's "heal CP / hit". Code: `FakePlayerPvpManager.restoreCpAsHp` / `getFoldedMaxCp`, called by the `CpHeal`, `CpHealPercent` and `CpHealOverTime` effects (scripts): on a fake player whose CP is folded into its HP, the CP a skill restores goes to its HP, at most its max CP a heal and no higher than its recoverable HP (Seal of Limit). Players are unchanged. Besides the Dominator it reaches the Duelist (Braveheart restores 1,000 CP: guard run below) and Victory of Pa'agrio's 20% (cast before a fight at full HP).

69.7% → 70.0% (+0.3, 95% −3.4 to +4.0), no kind clearly better or worse. The heal works (`000001-Archmage-vs-Dominator`: "heals Dominator for 858 with Ritual of Life", 39% → 42% HP), but Ritual of Life was cast 1.0 times a battle (5 s each, 3.0 against nukers) and **Honor of Pa'agrio never**: `castOnSelf` took SELF, ONE, PARTY and AURA skills, and Honor is PARTY_CLAN. The same timeline shows the cost of a 5 s heal: at 28% HP it cast Ritual of Life with the Archmage at 1%.

### Batch 7: Honor of Pa'agrio as its heal, cast on itself (`matrix-20261114-152044`): kept on its own measure

Code: `castOnSelf` (both brains) also takes a PARTY_CLAN skill, which takes in its caster. Only the Dominator has one in a list `castOnSelf` reads. Ritual of Life out of `heal` (5 s for 858).

Against batch 5 (69.7%): 71.3% (+1.6, 95% −2.1 to +5.3), no kind clearly better or worse; daggers 45.6% → 56.7% (+11.1, −3.4 to +25.6). Honor of Pa'agrio 0 → 0.97 casts a battle (2.7 against nukers, 1.1 against archers), 3% aborted. Kept: it is cast and heals as meant, no kind is worse, and a fake player's CP heals working was asked for.

**Guard: the Duelist** (Braveheart, 1,000 CP, is in its `heal` list), 15 battles an opponent, the CP heal scripts before (`matrix-20261114-153855`) and after (`matrix-20261114-153135`): 52.8% → 56.0% (+3.2, 95% −2.5 to +8.9), no kind clearly worse (buffers −4.0, the rest +0 to +6.7).

### Batch 8: Seal of Suspension in the Despair combo and `debuff` (`matrix-20261114-154611`): taken out

The guide's favourite: the target's reuse ×3 for 2 min, against fighters whose damage is skills. 71.3% → 68.7% (−2.6, 95% −6.3 to +1.1); **daggers 56.7% → 37.8% (−18.9, −33.2 to −4.6), clearly worse**; the rest within ±4. It landed 43% (more than the level 74 seals' 10%), cast 0.8 times a battle, and each cast was a Steal Essence less (−0.8): against a dagger, which kills it in 20 to 30 s, a 4 s seal is a race lost.

### Batch 9: heal each time Honor of Pa'agrio is ready (`matrix-20261114-160138`): taken out

Asked for: the guide's "heal CP / hit" taken literally. Build attribute `healOnCooldown` (`FakePlayerPvpBuild`, read in `thinkAttack` in both brains): the `heal` list is cast whenever it is ready and HP is under 98%, instead of under 50% half the time. 71.3% → 66.7% (−4.6, 95% −8.4 to −0.9), **clearly worse; melee 83.5% → 74.7% (−8.8, −14.7 to −2.8), clearly worse**; archers −10.8, daggers −12.2. Honor of Pa'agrio 1.0 → 9.6 casts a battle (18.7 against tanks), damage dealt 539 → 486 a second. A heal of 687 in 2.5 s takes the place of part of a Steal Essence, which drains about 1,100 back in 4 s and deals 1,385: Steal Essence is already its better heal. The attribute stays in the code, off for every build.

### Batch 10: heal only in Steal Essence's reuse (`matrix-20261114-161519`): taken out

Asked for, after batch 9: buffed, the Dominator casts in about 0.55 s, so Steal Essence (2 s reuse) leaves a gap after each cast that a heal could fill for free. Batch 9's timelines (`000017-Dominator-vs-Titan`) agree: each Honor of Pa'agrio went in that gap, Steal Essence followed 0.3 to 0.8 s later, and Steal Essence casts didn't fall (31.3 → 32.0). Its cost was elsewhere: it stood still to heal while a melee walked in (median gap against tanks 202 → 160), it healed at 94 to 97% HP (534 of 687 in one cast), and every heal set off the enemy's `ENEMY_HEAL` reflex. `healOnCooldown` now heals only with every attack on reuse (`areAttacksOnReuse`), under 90% HP, and no enemy within 300.

71.3% → 70.7% (−0.6, 95% −4.2 to +3.1), no kind clearly better or worse (daggers −11.1, archers −5.8, buffers +4.7, nukers +2.5). Honor of Pa'agrio 1.0 → 2.9 casts a battle (7.7 against nukers), Steal Essence 31.3 → 32.7, the melee within 200 of it 57% → 54%: the gap heals no longer cost anything, but they gain nothing either. Against a melee the gap is rarely free of an enemy within 300; against a nuker, a race of damage, 3% of its HP every few seconds doesn't change who falls first. Taken out (the attribute stays, off).

Final: batch 7.

| Against | Baseline | Final (batch 7) | Difference (95%) |
|---|---|---|---|
| tank | 5.0% | 94.2% | +89.2 |
| melee | 18.1% | 83.5% | +65.4 |
| dagger | 7.8% | 56.7% | +48.9 |
| archer | 10.0% | 55.0% | +45.0 |
| nuker | 30.8% | 33.3% | +2.5 |
| healer | 98.3% | 96.1% | −2.2 |
| buffer | 65.3% | 91.3% | +26.0 |
| **all** | **30.0%** | **71.3%** | **+41.3** |

## 5. Not fixed here, and next

- **Nukers (29%)**: it is out-damaged at range. Storm Screamer 0%, Soultaker 7%, Mystic Muse 10%, Archmage 13%: the wizards are the strongest builds of the clinic since their own clinic (`docs/class-clinic/wizards.md`), a balance question. Doom Oracle 13%: disabled 23% of the fight by its prophecies.
- **Party play**: Chant of Life is out of the build entirely, so a Dominator in a real party doesn't cast it either. Its party buffs are untouched. A `supportParty` that doesn't count a party of one would let it come back (it reaches the Sword Muse too: guard run).
- Seal of Limit (section 3) is untested.
- Pa'agrio's Fist (+800 max CP, then 800 CP) would need its max CP stat folded into a fake player's HP as well.
- **Daggers (46%)**: they stand on it 70% of the fight and it has no peel left that lands; a root or escape that works would be the next thing to look for (it has none in its kit).
- **Balance**: from 30% to 70% puts the Dominator among the stronger builds (Archmage 85%, Storm Screamer 91%, War Dancer 72%). Its 4 s Steal Essence was not the problem; the casts spent around it were.
