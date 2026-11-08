# Fake Player Skill Timing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Brain B fake players choose and time their existing skills like good players: a fight read of what they can see, a skill scorer instead of dice rolls, and reflexes between ticks, each layer A/B tested before the next.

**Architecture:** A pure-logic core in `ai/fight/` (no server imports: testable with plain Java) holds the fight read, the weights, the scorer and the reflex decisions; a thin server adapter feeds it from creature events and effect lists; brain B (`FakePlayerPvpAIExperimental`) calls it. Brain A is untouched until promotion.

**Tech Stack:** Java 25 core (`java/`), Ant build, MariaDB-backed local server (`tools/simulation/local_server.sh`), Python 3 reports, the simulation web API.

**Spec:** `docs/superpowers/specs/2026-11-08-fake-player-skill-timing-design.md`

## Global Constraints

- No change to items, passives, skills or any balance number (skill XML, `FakePlayerPvp.xml` gear and combos, passive tree).
- Only brain B changes (`FakePlayerPvpAIExperimental`, `FakePartyBrainExperimental`, new `ai/fight/` classes). Brain A (`FakePlayerPvpAI`) stays byte-identical until Task 11.
- Fair information: decisions read only cast bars, buff/debuff icons, HP %, class, and cooldowns of skills seen used. No hidden stats, no `isSkillDisabled` on enemies.
- Personality: skill and knowledge are in [-1, 1]; level = (x + 1) / 2 in [0, 1].
- Reflex reaction delay: 75–125 ms at skill level 1, 300–500 ms at skill level 0, interpolated linearly.
- Kill race won when time to kill < 0.75 × time to die (default, `FakeSkillWeights`, margin widens with low skill).
- Burst reflex fires when time to die < 3 s (default, `FakeSkillWeights`).
- HP trend window: 4 s.
- Files in `ai/fight/` named `Fight*`, `Skill*`, `Reflex*` import only `java.*` (except `FightReadFeed` and `SkillFactsReader`, the server adapters).
- Comments and docs follow the repo's plain style (see `CLAUDE.md`, existing Javadoc).
- Simulation gates: A against A calibration first; mirror matrix `brain=A:B mirror`, 40 battles per build, 700 at once, level 85; arena 100 battles per level at 40, 61, 80. A layer passes when B's 95% interval is above 50% in the matrix and B is not below 50% in the arena (layer 1: both intervals contain 50%).

## Review Focus

1. **An enemy that leaves, dies or despawns mid-cast** — its listener is removed and its record dropped; no null target, no listener left on a dead creature. Test in Task 3 (`FightReadFeedTest` scenario run in the arena leak check) and Task 2 (`forget` drops the record).
2. **A target that is invulnerable for the whole fight** (Ultimate Defense spam, Sonic Barrier) — scorer must not divide by zero damage; time to kill is infinite and the race is never "won". Test in Task 5.
3. **Two reflex events in the same tick** (enemy cast start and own HP crash) — only the higher-valued reaction runs, and neither runs twice. Test in Task 8.
4. **A fake player with no skills of a kind** (no CC, no defensive, healers with no nukes) — scorer and plans fall back to auto attack / hold, never pick a null skill. Test in Task 5 and Task 6.
5. **Unknown or unclassifiable enemy skills** (servitor skills, item skills, Kamael transformations) — classified `OTHER`, never throw, never start a cooldown memory for skill id 0. Test in Task 1.

---

### Task 1: Test harness and skill classification

**Files:**
- Create: `tools/tests/run.sh` (compiles `java/org/l2jmobius/gameserver/ai/fight/*.java` without the two adapters, plus `tools/tests/src/**/*.java`, into the scratch build dir, runs every `*Test` class's `main`, exits non-zero on the first failure)
- Create: `tools/tests/src/Check.java` (`isTrue(boolean, String)`, `eq(Object, Object, String)`, `near(double, double, double, String)`; each throws `AssertionError`)
- Create: `java/org/l2jmobius/gameserver/ai/fight/SkillFacts.java`
- Create: `java/org/l2jmobius/gameserver/ai/fight/SkillMeaning.java`
- Create: `java/org/l2jmobius/gameserver/ai/fight/SkillMeanings.java`
- Create: `java/org/l2jmobius/gameserver/ai/fight/SkillFactsReader.java` (server adapter)
- Test: `tools/tests/src/SkillMeaningsTest.java`

**Interfaces:**
- Produces:
  - `record SkillFacts(int id, int level, boolean bad, boolean self, int castRange, int hitTimeMs, int coolTimeMs, int reuseMs, int abnormalTimeMs, String abnormalType, Set<String> effectTypes, boolean charge, double power, int activateRate, int magicLevel, String basicProperty)`
  - `enum SkillMeaning { NUKE, CC, GAP_CLOSER, HEAL, CLEANSE, CANCEL, DEBUFF, BURST_BUFF, DEFENSIVE, OTHER }`
  - `static EnumSet<SkillMeaning> SkillMeanings.classify(SkillFacts facts)`
  - `static Set<String> SkillMeanings.DEFENSIVE_ABNORMALS` (the abnormal types of brain B's known defenses, `getKnownDefenses`, copied as names)
  - `static SkillFacts SkillFactsReader.of(Skill skill)` (cached per skill hash code; `effectTypes` = names of the skill's effects' `EffectType`, `charge` = `getFlyType() == FlyType.CHARGE`)

Classification rules (a skill can have several meanings):
- `CC`: bad, and an effect type in {STUN, ROOT, SLEEP, PARALYZE, MUTE, FEAR} or abnormal type in {STUN, ROOT_PHYSICALLY, ROOT_MAGICALLY, SLEEP, PARALYZE, SILENCE, SILENCE_PHYSICAL, SILENCE_ALL, TURN_STONE}.
- `NUKE`: bad, power > 0, an effect type in {MAGICAL_ATTACK, PHYSICAL_ATTACK, PHYSICAL_ATTACK_HP_LINK, DEATH_LINK, HP_DRAIN}.
- `GAP_CLOSER`: `charge`, or effect type TELEPORT_TO_TARGET.
- `HEAL`: not bad, effect type in {HEAL, CPHEAL, REBALANCE_HP}.
- `CLEANSE`: not bad, effect type in {DISPEL, DISPEL_BY_SLOT}. `CANCEL`: bad, effect type in {DISPEL, DISPEL_BY_SLOT, STEAL_ABNORMAL}.
- `DEBUFF`: bad, abnormal time > 0, not CC.
- `DEFENSIVE`: not bad, abnormal type in `DEFENSIVE_ABNORMALS`.
- `BURST_BUFF`: not bad, self, reuse ≥ 60,000 ms, abnormal time > 0, not DEFENSIVE.
- `OTHER`: none of the above (and always for id ≤ 0).

- [ ] **Step 1: Write the failing test** `SkillMeaningsTest`:
  - Stun Shot-like facts (bad, effect STUN, abnormal STUN, power 1500) → contains `CC` and `NUKE`.
  - Shadow Step-like (charge false, effect TELEPORT_TO_TARGET) → `GAP_CLOSER`.
  - Hydro Blast-like (bad, MAGICAL_ATTACK, power 120, abnormal "NONE") → exactly `{NUKE}`.
  - Ultimate Defense-like (not bad, self, abnormal = first entry of `DEFENSIVE_ABNORMALS`) → `{DEFENSIVE}`, not `BURST_BUFF`.
  - Frenzy-like (not bad, self, reuse 300000, abnormal time 120000, abnormal "BERSERKER") → `{BURST_BUFF}`.
  - Facts with id 0 and effect STUN → `{OTHER}`.
- [ ] **Step 2: Run** `tools/tests/run.sh` — expected FAIL: `SkillMeanings` not found.
- [ ] **Step 3: Implement** the three pure files and `SkillFactsReader`.
- [ ] **Step 4: Run** `tools/tests/run.sh` — expected `SkillMeaningsTest: OK`, exit 0. Also `ant` builds (`tools/simulation/local_server.sh` build step) with no error.
- [ ] **Step 5: Commit** `Fake player skill timing: test harness and skill meanings`.

### Task 2: The fight read

**Files:**
- Create: `java/org/l2jmobius/gameserver/ai/fight/EffectMeaning.java`
- Create: `java/org/l2jmobius/gameserver/ai/fight/FightRead.java`
- Test: `tools/tests/src/FightReadTest.java`

**Interfaces:**
- Consumes: `SkillFacts`, `SkillMeaning`, `SkillMeanings.classify` (Task 1).
- Produces:
  - `enum EffectMeaning { INVULNERABLE, REFLECT, CC_IMMUNE, STUNNED, ROOTED, SLEEPING, SILENCED, SLOWED, BURSTING, DEFENDING }`
  - `record ActiveCast(int enemyId, SkillFacts skill, EnumSet<SkillMeaning> meanings, int targetId, long start, long end)`
  - `enum ClassGroup { HEAVY_FIGHTER, LIGHT_FIGHTER, ROBE_MAGE }` (rough CON / MEN profile for land chance)
  - `class FightRead`, constructed `FightRead(double knowledge, long seed)`:
    - `void onEnemyCast(int enemyId, SkillFacts skill, int targetId, long now, long end)`
    - `void onEffects(int creatureId, Map<EffectMeaning, Long> expiresAt, long now)` (snapshot; new meanings become visible only after the notice delay)
    - `void onHp(long now, int selfId, double selfHp, double selfMaxHp, int targetId, double targetHp, double targetMaxHp)`
    - `void onCcAttempt(int enemyId, int skillId, boolean landed)`
    - `void forget(int enemyId)`
    - `boolean isOnCooldown(int enemyId, int skillId, long now)`; `boolean isRemembered(int enemyId, int skillId)`
    - `ActiveCast currentCast(int enemyId, long now)` (null when none)
    - `boolean has(int creatureId, EffectMeaning meaning, long now)`; `long remaining(int creatureId, EffectMeaning meaning, long now)`
    - `double damageTakenPerSecond(long now)`; `double damageDealtPerSecond(long now)` (over the last 4,000 ms of HP samples)
    - `double timeToDie(long now, double extraIncoming)`; `double timeToKill(long now, double myDps)` (seconds; `Double.POSITIVE_INFINITY` when the rate is 0)
    - `double landChance(int enemyId, SkillFacts skill, ClassGroup group, long now)` (in [0, 1])

Values the tests pin:
- Memory: a fake player at knowledge level 1 remembers up to 12 enemy cooldowns, at level 0 up to 2 (linear); the oldest is dropped first.
- Notice delay for new effect meanings: 0 ms at knowledge level 1, 1,500 ms at level 0 (linear).
- Land chance: base = `activateRate / 100` (0.8 when activateRate ≤ 0) × group factor (STUN-type CC: HEAVY_FIGHTER 0.7, LIGHT_FIGHTER 0.85, ROBE_MAGE 1.0; SILENCE/SLEEP-type: ROBE_MAGE 0.7, others 1.0) × 0 if the enemy `has CC_IMMUNE` for a stun; then blended with the record: `(base × 4 + landed) / (4 + attempts)`. Estimate noise: ± up to 0.2 × (1 − knowledge level), seeded.

- [ ] **Step 1: Write the failing test** `FightReadTest`:
  - Cooldown: `onEnemyCast(7, facts(reuse 30000), …, now 1000, end 1500)` → `isOnCooldown(7, id, 20000)` true, at 31001 false.
  - Memory: knowledge level 0 (`new FightRead(-1, 1)`), three different skills seen → only the last two are remembered.
  - Notice: knowledge level 0, `onEffects(7, {STUNNED: 9000}, 1000)` → `has(7, STUNNED, 1200)` false, at 2600 true; knowledge level 1 → true at 1000.
  - Rates: HP samples self 1000 → 600 over 4 s → `damageTakenPerSecond` ≈ 100 (±1); `timeToDie(now, 0)` ≈ 6.0.
  - Infinite: no damage samples → `timeToDie` is `POSITIVE_INFINITY`.
  - Land chance: stun with activateRate 80 on HEAVY_FIGHTER at knowledge level 1 → ≈ 0.56; after `onCcAttempt` ×4 landed → (0.56×4+4)/8 = 0.78; enemy `has CC_IMMUNE` → 0.
  - `forget(7)` → `isRemembered(7, id)` false and `currentCast(7, now)` null.
- [ ] **Step 2: Run** `tools/tests/run.sh` — expected FAIL on `FightRead`.
- [ ] **Step 3: Implement** `EffectMeaning`, `ClassGroup` (nested or own file) and `FightRead`.
- [ ] **Step 4: Run** `tools/tests/run.sh` — expected all OK.
- [ ] **Step 5: Commit** `Fake player skill timing: the fight read`.

### Task 3: Feed the fight read in brain B (observe only), and gate 1

**Files:**
- Create: `java/org/l2jmobius/gameserver/ai/fight/FightReadFeed.java` (server adapter)
- Modify: `java/org/l2jmobius/gameserver/ai/FakePlayerPvpAIExperimental.java` (create the feed with the AI, update it each think in a fight, close it in `onActionDeath` and on despawn)
- Modify: `java/org/l2jmobius/gameserver/managers/simulation/Reason.java` (add `FIGHT_READ`)
- Modify: `tools/simulation/matrix.py` (add `--mirror`, passed as `mirror=1`)
- Create: `tools/simulation/skill_report.py` (first version: reads `decisions.jsonl` of matrix or arena battles, prints per build and brain the count of each `Reason`, and for `FIGHT_READ` the share of enemy casts seen and cooldowns remembered)

**Interfaces:**
- Consumes: `FightRead`, `SkillFactsReader.of` (Tasks 1–2).
- Produces: `class FightReadFeed`, `FightReadFeed(Attackable self, FakePlayerPvpPersonality personality)`; `void update(Creature target, Collection<Creature> enemies, long now)` (adds an `ON_CREATURE_SKILL_USE` `ConsumerEventListener` with owner = this feed on each new enemy, calls `forget` and removes listeners of enemies no longer listed; reads effect lists into `EffectMeaning` snapshots; samples HP); `void close()` (removes every listener it added: `removeListenerIf(l -> l.getOwner() == this)`); `FightRead read()`; `void noteOwnCc(Creature target, Skill skill, long now)` (checks 300 ms after the cast lands whether the target got the abnormal, then `onCcAttempt`).
- `ClassGroup` for an enemy: heavy armour or tank / warrior class → HEAVY_FIGHTER; dagger, archer, Kamael fighter → LIGHT_FIGHTER; mage classes → ROBE_MAGE (from `ClassId` / the fake player's build role).
- Brain B logs one `FIGHT_READ` decision every 2 s in a fight, with `seenCasts`, `remembered`, `ttk`, `ttd` fields. No other behavior change.

- [ ] **Step 1: Implement** `FightReadFeed`, the brain B wiring, `Reason.FIGHT_READ`, `matrix.py --mirror` and `skill_report.py`.
- [ ] **Step 2: Verify the build and the unit tests**: `tools/tests/run.sh` OK; `tools/simulation/local_server.sh` reaches "Server loaded".
- [ ] **Step 3: Calibrate**: `python3 tools/simulation/matrix.py --start --battles 40 --parallel 700 --brain A:A --mirror` — expected overall about 50%, 95% interval containing 50%.
- [ ] **Step 4: Gate 1**: same with `--brain A:B` — expected B's interval contains 50% (observe-only); `skill_report.py` shows `FIGHT_READ` lines for brain B with seen casts > 0 in at least 90% of battles.
- [ ] **Step 5: Leak check**: `python3 tools/simulation/ab_test.py --parallel 100 --levels 40,61,80 --rounds 100`; `jcmd <pid> GC.class_histogram | grep ConsumerEventListener` before and after: count back near its start value.
- [ ] **Step 6: Commit** `Fake player skill timing: brain B reads its fights (no behavior change)` with the gate numbers in the message, and record them in `docs/fake-player-simulation-issues.md`.

### Task 4: Weights

**Files:**
- Create: `java/org/l2jmobius/gameserver/ai/fight/SkillWeights.java`
- Create: `dist/game/config/Custom/FakeSkillWeights.ini`
- Modify: `java/org/l2jmobius/gameserver/config/ConfigLoader.java` (load it after `FakePlayerPvpConfig`, through a small `config/custom/FakeSkillWeightsConfig` that hands the file's lines to `SkillWeights.parse`)
- Test: `tools/tests/src/SkillWeightsTest.java`

**Interfaces:**
- Produces: `record SkillWeights(double damage, double control, double setup, double cooldownCost, double risk, double raceMargin, double burstReflexTtd, double noise, int planDepth)`; `static Map<String, SkillWeights> SkillWeights.parse(List<String> lines)` (keys `ROLE.name`, roles FIGHTER, TANK, DAGGER, ARCHER, MAGE; a missing key takes the default); `static SkillWeights SkillWeights.of(String role)`; `static SkillWeights.DEFAULT`.
- Defaults: damage 1.0, control 1.0, setup 0.6, cooldownCost 1.0, risk 1.0, raceMargin 0.75, burstReflexTtd 3.0, noise 0.15, planDepth 3. MAGE and ARCHER: risk 1.3; TANK: raceMargin 0.6 (reacts more readily).

- [ ] **Step 1: Write the failing test**: `parse(List.of("MAGE.risk = 1.5", "TANK.raceMargin=0.5", "bad line"))` → MAGE risk 1.5, MAGE raceMargin 0.75, TANK raceMargin 0.5, FIGHTER equals `DEFAULT`; the bad line is skipped.
- [ ] **Step 2: Run** `tools/tests/run.sh` — FAIL.
- [ ] **Step 3: Implement** `SkillWeights`, the ini (every key commented, defaults written out), the config loader hook.
- [ ] **Step 4: Run** `tools/tests/run.sh` — OK; server starts with the ini.
- [ ] **Step 5: Commit** `Fake player skill timing: weight table`.

### Task 5: The scorer's values

**Files:**
- Create: `java/org/l2jmobius/gameserver/ai/fight/SkillOption.java`
- Create: `java/org/l2jmobius/gameserver/ai/fight/ScoreContext.java`
- Create: `java/org/l2jmobius/gameserver/ai/fight/SkillScorer.java`
- Test: `tools/tests/src/SkillScorerTest.java`

**Interfaces:**
- Consumes: `FightRead`, `SkillWeights`, `SkillMeanings`.
- Produces:
  - `record SkillOption(Kind kind, SkillFacts skill, double expectedDamage, boolean inReach)` with `enum Kind { SKILL, AUTO_ATTACK, HOLD, STEP_BACK }`; `expectedDamage` is supplied by brain B (from the existing damage estimate it already uses for `isWorthCasting`).
  - `record ScoreContext(int selfId, int targetId, long now, double myDps, double autoAttackDps, double selfHpRatio, double targetHpRatio, boolean meleeNear, boolean burstReady, ClassGroup targetGroup, double skillLevel)`
  - `record Scored(SkillOption option, double value, Map<String, Double> terms)`
  - `class SkillScorer`, `SkillScorer(FightRead read, SkillWeights weights, long seed)`; `List<Scored> score(List<SkillOption> options, ScoreContext ctx)` (sorted best first); `boolean raceWon(ScoreContext ctx)`.

Value terms (each × its weight; the term names are the keys of `Scored.terms`, logged in Task 7):
- `damage` = expectedDamage ÷ ((hit + cool time) / 1000); auto attack = autoAttackDps; × 0 if target `INVULNERABLE`, × 0.3 if `REFLECT` for a nuke.
- `control` (CC only) = landChance × min(abnormal time, 5 s) × urgency, urgency = 3 if `currentCast(target)` is a NUKE/HEAL/CC and the CC is instant enough to land before its end (hit time < cast end − now), 2 if `meleeNear` and self is ranged, 2 if `burstReady`, else 1; 0 if the target `has` STUNNED/ROOTED/SLEEPING longer than this skill's hit time (overlap).
- `setup` (DEBUFF / CANCEL) = 1 if `burstReady` and the target lacks it, else 0.3.
- `cooldownCost` (reuse ≥ 30 s) = −(reuse / 60 s), halved when the target is held, low (< 35% HP) or has no DEFENDING/INVULNERABLE; 0 when `raceWon`.
- `risk` = −(hit time / 1000) when `meleeNear` and the enemy's stun or silence isn't remembered on cooldown.
- `raceWon` = timeToKill(now, myDps) < raceMargin × timeToDie(now, 0); never true when either is infinite.
- Noise: each value × (1 ± noise × (1 − skillLevel)), seeded.

- [ ] **Step 1: Write the failing tests**:
  - A casting enemy (NUKE, ends in 1,500 ms), options {instant stun 80%, nuke}, target LIGHT_FIGHTER → the stun is first.
  - Same target already STUNNED for 4 s → the stun's `control` term is 0 and the nuke is first.
  - Target `INVULNERABLE` → every damage term 0; best option is HOLD or AUTO_ATTACK, never a null skill.
  - Race: target at 10% HP, my dps high, my HP 60% → `raceWon` true and a 120 s reuse nuke has `cooldownCost` 0.
  - Only AUTO_ATTACK and HOLD offered (a support with nothing) → one of them returned.
  - skillLevel 1 → same order on two seeds; skillLevel 0 → order may differ (assert values differ between seeds).
- [ ] **Step 2: Run** `tools/tests/run.sh` — FAIL.
- [ ] **Step 3: Implement** the three files.
- [ ] **Step 4: Run** — OK.
- [ ] **Step 5: Commit** `Fake player skill timing: the skill scorer`.

### Task 6: Plans

**Files:**
- Create: `java/org/l2jmobius/gameserver/ai/fight/SkillPlan.java`
- Modify: `java/org/l2jmobius/gameserver/ai/fight/SkillScorer.java`
- Test: `tools/tests/src/SkillPlanTest.java`

**Interfaces:**
- Produces: `class SkillPlan` with `enum Type { BURST, CC_CHAIN, KITE, COMBO }`, `List<Integer> steps` (skill ids), `int next()`, `boolean advance(int castSkillId)`, `boolean expired(long now)`; in `SkillScorer`: `SkillPlan currentPlan()`, `void startPlan(SkillPlan plan, long now)`, `void dropPlan()`, and `score` adds a `plan` term (+2 × damage weight) to the option that is the plan's next step.
- Rules: BURST = [burst buffs ready] + [best CC with landChance ≥ 0.5] + [nukes by damage]; started only when planDepth ≥ 2 and the CC qualifies; dropped when the CC misses or after 8 s. CC_CHAIN = next CC queued for when `remaining(target, held) ≤` its hit time + 100 ms; planDepth ≥ 3. KITE = archer shot/step cycle marker (no skill ids; lets brain B's existing `archerKiteStep` run). COMBO = an existing `FakePlayerPvpCombo`'s steps, so the scorer can hold or drop it.
- planDepth from weights × skill level: effective depth = round(planDepth × skillLevel); depth 0 → no plans (weak players just use the best value).

- [ ] **Step 1: Write the failing tests**: burst plan built from {Frenzy-like buff, stun 80% on ROBE_MAGE, two nukes} → steps buff, stun, bigger nuke, smaller nuke; stun with 30% land chance → no BURST plan; `advance` with a wrong id returns false; `expired` after 8,001 ms; skillLevel 0 → `startPlan` refused (no current plan).
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement**.
- [ ] **Step 4: Run** — OK.
- [ ] **Step 5: Commit** `Fake player skill timing: burst, CC chain, kite and combo plans`.

### Task 7: Brain B chooses skills with the scorer, and gate 2

**Files:**
- Modify: `java/org/l2jmobius/gameserver/ai/FakePlayerPvpAIExperimental.java` — in `thinkAttack`, replace from the combo step through the attack-skill step (the `playCombo` call, the `debuffChance` roll and the `skillChance` roll, with their `pickSkill` calls) by: build `SkillOption`s from the DEBUFF and ATTACK skills that pass today's filters (`canCast`, `isWorthCasting`, `isBlocked`, `affordable`, `inReach` rules unchanged), auto attack and hold; `score`; use the best through the existing `useSkill` / normal attack paths. The emergency, cleanse, peel, rush, kite and archer-arrow steps above stay as they are in this task.
- Modify: `java/org/l2jmobius/gameserver/managers/simulation/Reason.java` (add `SCORED_SKILL`, `PLAN_START`, `PLAN_DROP`)
- Modify: `tools/simulation/skill_report.py` (casts interrupted by B / landed on B, CCs landed / wasted, burst plans started / completed, by build and brain)

**Interfaces:**
- Consumes: `SkillScorer`, `SkillPlan`, `SkillWeights.of(role)`, `FightReadFeed.read()`.
- `SCORED_SKILL` decision fields: `skill`, `value`, the top three `terms`.

- [ ] **Step 1: Implement** the replacement, the reasons and the report.
- [ ] **Step 2: Verify**: `tools/tests/run.sh` OK; server loads; a 2-battle matrix (`--battles 2 --builds "Archmage,Titan"`) finishes with `SCORED_SKILL` lines in B's logs.
- [ ] **Step 3: Gate 2**: `matrix.py --start --battles 40 --parallel 700 --brain A:B --mirror` — pass if B's overall 95% interval is above 50%. Then `ab_test.py --parallel 100 --levels 40,61,80 --rounds 100` — pass if B ≥ 50%.
- [ ] **Step 4: Report**: `skill_report.py` on both runs; write the results and the per-build table into `docs/fake-player-simulation-issues.md`. If the gate fails, stop here and report which builds lost and why (terms in the logs) before tuning anything.
- [ ] **Step 5: Commit** `Fake player skill timing: brain B picks skills by value` with the gate numbers.

### Task 8: Reflex decisions

**Files:**
- Create: `java/org/l2jmobius/gameserver/ai/fight/ReflexEvent.java`
- Create: `java/org/l2jmobius/gameserver/ai/fight/Reflexes.java`
- Test: `tools/tests/src/ReflexesTest.java`

**Interfaces:**
- Consumes: `FightRead`, `SkillScorer`, `ScoreContext`, `SkillOption`.
- Produces:
  - `enum ReflexEvent.Kind { ENEMY_CAST_AT_ME, ENEMY_HEAL, ENEMY_GAP_CLOSER, ENEMY_BURST, HP_CRASH, CC_ON_ME, MY_CC_LANDED, ENEMY_DEFENSE_ENDED, TARGET_CC_ENDING }`; `record ReflexEvent(Kind kind, int enemyId, SkillFacts skill, long at)`
  - `record Reaction(ReflexEvent event, SkillOption option, String why)`
  - `class Reflexes`, `Reflexes(FightRead read, SkillScorer scorer, SkillWeights weights)`; `Reaction decide(List<ReflexEvent> pending, ScoreContext ctx, List<SkillOption> options, SkillOption current)` (null = carry on); `static long reactionDelay(double skillLevel, java.util.Random rnd)` (75–125 ms at 1, 300–500 ms at 0, linear).
- Rules: kill race first (`scorer.raceWon(ctx)` → null unless the option is instant, hit time 0); an option must beat `current`'s value; one reaction per event (an event id seen twice → ignored); per-kind cooldown 1,500 ms; HP_CRASH fires when `timeToDie < burstReflexTtd` and prefers DEFENSIVE options; CC_ON_ME picks a CLEANSE only if ROOTED with `meleeNear` false for ranged self or STUNNED/SLEEPING; never when the fake player is stunned or silenced (ctx flag, add `boolean disabled` to `ScoreContext`).

- [ ] **Step 1: Write the failing tests**:
  - `reactionDelay(1, r)` in [75, 125]; `reactionDelay(0, r)` in [300, 500]; `reactionDelay(0.5, r)` in [187, 312].
  - ENEMY_CAST_AT_ME with a nuke and an instant stun available, race not won → stun.
  - Same, race won (target 5% HP) and stun with hit time 600 → null (keep nuking); with an instant stun (hit 0) → stun.
  - ENEMY_CAST_AT_ME and HP_CRASH in one call → one reaction, the higher value; the same events again → null.
  - HP_CRASH with no DEFENSIVE option → the best other option or null, never a null option inside a Reaction.
  - `disabled` → null.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement**.
- [ ] **Step 4: Run** — OK.
- [ ] **Step 5: Commit** `Fake player skill timing: reflex decisions with the kill race`.

### Task 9: Reflexes in brain B, and gate 3

**Files:**
- Modify: `java/org/l2jmobius/gameserver/ai/fight/FightReadFeed.java` (turn listener callbacks and HP / effect changes into `ReflexEvent`s; `List<ReflexEvent> drainEvents()`)
- Modify: `java/org/l2jmobius/gameserver/ai/FakePlayerPvpAIExperimental.java` (on an event, schedule `ThreadPool.schedule(this::onReflex, Reflexes.reactionDelay(skillLevel, rnd))`; `onReflex` builds the context, calls `decide`, and acts through `useSkill` / `castOnSelf` / the existing step-back; it may break its own cast only for HP_CRASH, and never in the last 30% of the cast; the old fixed-HP emergency step is replaced by HP_CRASH)
- Modify: `java/org/l2jmobius/gameserver/managers/simulation/Reason.java` (add `REFLEX`, `REFLEX_RACE_SKIP`)
- Modify: `tools/simulation/skill_report.py` (reflexes by kind, race skips, results split into skill-level thirds)

- [ ] **Step 1: Implement**.
- [ ] **Step 2: Verify**: unit tests OK; a short matrix shows `REFLEX` and `REFLEX_RACE_SKIP` in B's logs; no exceptions in `stdout.log`.
- [ ] **Step 3: Gate 3**: mirror matrix A:B (40, 700 at once) and arena (100 per level) as in Task 7. Pass as in Task 7, and the top skill third must gain more than the bottom third.
- [ ] **Step 4: Report** in `docs/fake-player-simulation-issues.md`.
- [ ] **Step 5: Commit** `Fake player skill timing: reflexes` with the gate numbers.

### Task 10: Self-play tuning of the weights

**Files:**
- Create: `tools/simulation/tune_weights.py`

**Interfaces:**
- Consumes: the server's `config/Custom/FakeSkillWeights.ini` (rewrites it between runs and restarts with `local_server.sh --no-build`), `matrix.py` API calls, `matrix_compare.py`.
- Procedure: for one role at a time, coordinate search over `damage, control, setup, cooldownCost, risk, raceMargin` at ×0.7 / ×1.3; each candidate is a focused mirror matrix (`brain=A:B mirror`, builds of that role only, 40 per build); keep a change only if B's win rate rises by more than the 95% interval half-width of the previous best; at most 3 passes per role. Writes `log/tuning/<time>/` with every run and the final ini.

- [ ] **Step 1: Implement** the script (`--role`, `--passes`, `--dry-run` printing the planned runs).
- [ ] **Step 2: Verify** `--dry-run --role MAGE` lists 12 runs with distinct ini contents.
- [ ] **Step 3: Run** it for each role; then the arena (100 per level) with the tuned ini against the untuned one (brain B both sides is not possible: compare B-tuned vs A and B-untuned vs A arena results). Keep the tuned values only if B-tuned ≥ B-untuned.
- [ ] **Step 4: Commit** the script and the kept `FakeSkillWeights.ini`, with the numbers in `docs/fake-player-simulation-issues.md`.

### Task 11: Promotion

**Files:**
- Modify: `java/org/l2jmobius/gameserver/ai/FakePlayerPvpAI.java` (becomes brain B's code, class name back)
- Modify: `docs/fake-player-simulation-issues.md` (promotion entry)

- [ ] **Step 1: Check** gates 2 and 3 (and Task 10 if done) passed; if not, don't promote and say why.
- [ ] **Step 2: Copy** `FakePlayerPvpAIExperimental` over `FakePlayerPvpAI` (rename the class), keep B as a fresh copy, as described in `docs/fake-player-simulation-issues.md` §1.
- [ ] **Step 3: Verify**: build, unit tests, a TvT batch `curl -X POST http://127.0.0.1:8788/api/tvt/batch -d 'count=10&level=80&a=legacy&b=best&size=30&minutes=5'` runs to the end with no exceptions, and an A-against-A mirror matrix is about 50%.
- [ ] **Step 4: Commit** `Fake players: promote the skill timing brain` with all gate numbers.
