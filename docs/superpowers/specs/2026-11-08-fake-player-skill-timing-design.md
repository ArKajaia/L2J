# Fake players: skill timing like a good player

Design for making fake players use their existing skills the way a good PvP player does: reading the enemy, holding burst for an opening, chaining crowd control, interrupting casts, answering burst, and racing when they can out-damage.

Status: design approved in conversation on 2026-11-08; this spec awaits review before the implementation plan.

## 1. Goal and constraints

**Goal.** Fake players win more fights with the same builds, by changing **when and why** they use their skills.

**Constraints (from the request):**
- No changes to items, passives, skills or any balance number. Only decisions change.
- Fair information: a fake player decides only from what a player could see (§3).
- The level of play follows each fake player's personality (`FakePlayerPvpPersonality` skill and knowledge): skilled ones play the new way fully, weak ones react late, misjudge and sometimes waste skills.
- Judged on duels first (the class matrix), then party battles (the arena), then Team vs Team.

**What doesn't change:** movement, kiting paths, targeting and focus calls, weapon swaps, party brain decisions, the combo data in `data/FakePlayerPvp.xml`.

## 2. Where it lives

Everything goes into **brain B** first (`ai/FakePlayerPvpAIExperimental`), following `docs/fake-player-simulation-issues.md` §1. Brain A (`FakePlayerPvpAI`) is frozen until B wins and is promoted.

New core classes (Java 25), in `java/org/l2jmobius/gameserver/ai/fight/`:

| Class | One job | Depends on |
|---|---|---|
| `FakeFightRead` | Records what the fake player can see of its fight. Never acts. | Creature events, skill data |
| `FakeSkillScorer` | Gives every option a value and picks the best; holds the current plan. | `FakeFightRead`, `FakeSkillWeights`, the profile |
| `FakeReflexes` | Reacts to events between ticks, through the scorer. | `FakeFightRead`, `FakeSkillScorer` |
| `FakeSkillWeights` | The weight table, one row per role (`config/Custom/FakeSkillWeights.ini`), the only thing self-play tuning changes. | — |

One `FakeFightRead` and one `FakeSkillScorer` per fake player, created by brain B and dropped on death or despawn.

## 3. The fight read (`FakeFightRead`)

Records only what a player could see, for its target and every enemy fighting it:

- **Enemy casts.** Fed by `OnCreatureSkillUse` listeners on those enemies (added and removed as enemies come and go). Records skill, cast start, expected end (`getHitTime` + `getCoolTime`, with the caster's casting speed as shown by the cast bar), and target. Each cast is classed by meaning: `NUKE`, `CC`, `GAP_CLOSER`, `HEAL`, `BURST_BUFF`, `DEFENSIVE`, `CLEANSE`, `OTHER` (from the skill's effects and abnormal type, computed once per skill and cached).
- **Remembered cooldowns.** A seen use starts that skill's `getReuseDelay()` timer. Only seen skills are known; unseen ones count as "maybe ready".
- **Visible effects.** Buff and debuff icons on the enemy and on itself, grouped by meaning: `INVULNERABLE`, `REFLECT`, `CC_IMMUNE` (Resist Shock and other resist buffs, the short immunity after a stun, a stun seen not landing on someone with Unwavering Stance), `ROOTED`, `STUNNED`, `SILENCED`, `SLOWED`, `BURSTING`. Extends today's `noticeDefenses` (known defensive buffs) and keeps its expiry times.
- **HP trend.** Own and target HP % sampled every think over a rolling 4 s; gives damage per second taken and dealt.
- **Time to kill / time to die.** Time to kill = target HP ÷ my expected damage per second with my best skills in reach. Time to die = my HP ÷ incoming damage per second, plus the expected hit of any big cast started at me.
- **CC state.** On the target: what holds it, since when, roughly until when (`getAbnormalTime`).
- **Land-chance estimate.** From the skill's `getActivateRate()` and magic level, the enemy's class type (a rough CON / MEN profile per class group), visible resist icons, and a per-enemy record of landed and resisted attempts (players see "resisted" too). Never reads hidden stats.

**Personality (knowledge):** sets how many cooldowns it remembers, how soon it notices a new icon (a delay), and the noise on its estimates.

## 4. The skill scorer (`FakeSkillScorer`)

Replaces, in brain B's `thinkAttack`, the dice rolls that choose skills: the PvP skill chance, the debuff chance, and `pickSkill`'s 70/30 choice. Each think it lists the options (every ready skill on the target, auto attack, hold, step back) and gives each a value:

- **Damage:** expected damage over the time the option takes (cast time included, so it compares with auto attacks), reduced by visible blocks (invulnerable, reflect, known defensive buffs).
- **Control:** land chance × time held × what that time is worth now. Worth most when it interrupts a cast, stops a melee after an archer or mage, or holds the target while the burst is ready. Zero on a CC-immune target or when it would overlap a CC already on it.
- **Setup:** debuffs that raise later damage (Hex, Curse Gloom, P.Def and M.Def debuffs), worth more just before a burst.
- **Cooldown cost:** using a long cooldown now loses it for later. Big nukes and burst buffs are held unless the moment is good: target held, low, or without defenses up, or its answer (a cleanse, an invulnerability skill) remembered on cooldown.
- **Risk:** chance the cast is interrupted (a melee near, the enemy's stun or silence not remembered on cooldown).
- **Kill race:** while time to kill is clearly below time to die, cooldown cost drops to zero and damage outweighs everything else: finishing now is the good moment.

**Plans** the scorer can start, hold or drop: **burst window** (burst buffs → CC → biggest hits while it holds; only started if its CC is likely to land), **CC chain** (the next CC lined up to land as the last ends), **kite cycle** for archers (shoot, step, shoot). Existing combos become plans too.

**Personality (skill):** planning depth (a weak one doesn't hold burst, it uses its highest-damage skill), noise on values (sometimes the second best), reaction delay.

Weights come from `FakeSkillWeights` by role.

## 5. Reflexes (`FakeReflexes`)

Act when an event arrives, after a reaction delay from personality (about 150–250 ms skilled, 600–1000 ms weak). A reflex acts only if reacting is worth more than carrying on (asked of the scorer).

**Kill race first.** Before any reflex: if time to kill is clearly below time to die (margin by personality and role), the reflex is skipped and the offensive choice stands. A defensive or interrupt wins only if the race would be lost without it, or when it is nearly free (an instant interrupt). The race weighs most for nukers and other damage dealers; tanks and supports react more readily.

| Event | Reaction |
|---|---|
| Enemy starts a big nuke or CC at me | Interrupt with an instant stun / silence / knockback that lands in time; else step out of range or line of sight (archers and mages near cover); else a defensive if the hit is big enough. |
| Enemy starts a heal on a low target | Interrupt it, or Cancel / Mana Burn. |
| Enemy gap closer starts | An archer or mage readies its peel for when it arrives. |
| Enemy burst buff appears, or time to die drops below a few seconds | The defensive that fits the damage type and duration (Ultimate Defense, Sonic Barrier, Vengeance, Touch of Life, Mirage, Fake Death, Hide...). Replaces fixed HP thresholds. |
| A CC lands on me | Cleanse if it matters now (a root while a melee comes); otherwise keep the cleanse. |
| My CC lands on the target | Start the burst window if the burst is ready. |
| Enemy invulnerability or reflect expires | Go in at once. |
| CC on the target about to end | Line up the next CC, or step back, per plan. |

**Safety:** one reaction per event; never interrupts the last part of its own cast for anything less than lethal; a short cooldown per reflex; none while stunned or silenced. Each reflex is logged with its reason (§6).

## 6. Logging

Battle logs (`decisions.jsonl`) get new `Reason` values (`managers/simulation/Reason.java`) for scorer picks with their top value terms, plans started / used / dropped, and each reflex with its trigger and whether the race check skipped it. Both brains log the same way; brain A just never produces the new reasons.

`tools/simulation/skill_report.py <matrix or arena folders>` counts, per build and brain: enemy casts interrupted and casts that landed on it, CCs landed / wasted (immune, overlapping), burst windows started and completed, reflexes by type and race skips. It shows *why* B wins or loses.

## 7. Testing and rollout

Layers, each A/B tested before the next starts:

1. **Fight read only** (records, doesn't act). Its records match the battle logs; A against B stays about 50%.
2. **Scorer and plans** (burst windows, CC chains, kill race).
3. **Reflexes.**
4. **Self-play tuning** of `FakeSkillWeights`.

Measures:
- **Calibration:** A against A first, about 50%.
- **Duels:** mirror matrix, every build against itself, brain A against brain B (`//Simulate matrix 40 brain=A:B mirror`), 700 at once. B's win rate with its 95% interval, overall and per build.
- **Groups:** A/B arena, 100 battles per level at 40, 61 and 80 (`ab_test.py --parallel`).
- **Personality:** results split by skill and knowledge; skilled fake players should gain the most.
- **TvT:** brain B doesn't play TvT; covered once B is promoted to A, with a `//tvt batch` check.

**Promotion:** when B beats A in the matrix and the arena, B is copied over A as before (`docs/fake-player-simulation-issues.md` §1), and the results go into that document.

**Self-play tuning (last):** a script runs short mirror matrices with variants of one role's weights and keeps the best; the winner must also beat the untuned weights in the arena before it is kept.

## 8. Risks

- **Fake-vs-fake overfitting:** both sides are fake players. The fair-information rule and the arena check limit it; real-player feel is checked by playing.
- **CPU:** listeners and scoring per think. The scorer only runs in fights, options are few (a build has under ~20 skills), and the 700-at-once matrix is the load test.
- **Event listener leaks:** listeners are removed when an enemy leaves the fight read and on death / despawn; checked with a long arena run.
- **Scope:** large. The layers ship one at a time; each can stop there if the next doesn't win.
