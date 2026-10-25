# Fake player AI: party play, navigation and engagement ranges

Plan for making roaming fake players (`FakePlayerPvpAI`) fight more like real players, alone and in parties.
Almost everything here is core code (`java/`, Java 25), so records and modern syntax are fine. The one exception is the simulation admin handler (§10), which is a script and must stay at the Java 8 language level.

---

## 1. What the code does today, and where it goes wrong

| Symptom | Cause in the code |
|---|---|
| **Stuck behind walls/pillars, "can't see" a player** | `moveToPawn(target, range)` does nothing when the fake player is already within `range` but has no line of sight. `Creature.moveToLocation` treats `distance - offset <= 0` as arrived (`Creature.java` ~line 4925 block, offset check). The LoS-failed branches all hit this: normal attack `FakePlayerPvpAI.java:929`, skill cast `castOrApproach` `:3076`, mage reposition `:920`, party support `castSupport` `:4681`. It stands still until `MONSTER_STUCK_TIMEOUT` (15 s) or `PLAYER_STUCK_TIMEOUT` (30 s) runs out. |
| **Kiting into walls** | `kiteStep` (`:3524`) only tries the straight line away from the target. If that's blocked it decides it's "cornered" and stops kiting for 3 s. `runAway` already tries a fan of angles (`FLEE_TURNS`), but kiting doesn't use it. Kiting also ignores where the party is and whether it keeps line of sight. |
| **Mages fight from too close** | A single constant, `MAGE_RANGE = 600` (`:94`), applies to every mage, even though Hydro Blast, Hurricane, Prominence and Death Spike all reach **900**. A Mystic Muse stands 600 away, which is inside every 600-range root, Sonic Blaster and Shackle. |
| **Archers/mages react late** | They only step back once an enemy is inside `FakePvpKiteDistance` (280). By then a dagger has used Shadow Step (600) and a Duelist has used Rush (400). |
| **Party splits up** | Each member chooses its own target with `chooseFocus` (`:1476`), which picks the weakest enemy in *its own* aggro list. In a fake-only party, `withPlayers == false` (`:597`), so each member runs `thinkFlee` on its own (`:746`) and flees in its own direction. `followLeader` only runs when idle (`thinkParty` runs from `thinkActive`). In a PvP each member chases up to `FakePvpChaseRange` (4000) with no tie to the group. |
| **No help for a member under pressure** | `FakePartyManager.onFakeAttacked` (`:1322`) gives the attacker some hate on the other members, and that's all. The healer only acts once HP drops below 75 %, the tank doesn't go to the member, and nobody peels the player attacking the healer. `findPartyTarget` only looks at **monsters** attacking members, not players. |
| **No sense of who can win** | `focusScore` (`:1515`) is HP ratio plus distance. It doesn't consider the fake player's own damage, the enemy's damage, the enemy's MP or CP, or the party's combined damage. It never decides "I can finish this, go in" versus "stay out". |

The good parts are worth keeping: the combo system, defensive-buff noticing, peel/rush/cleanse categories, the per-fake-player personality, and Spirit Walk/totem logic.

---

## 2. Engagement range table (per class)

Values come from this repo's skill data (`dist/game/data/stats/skills/**`, which matches the L2J H5 datapack) and the skill lists in `dist/game/data/FakePlayerPvp.xml`. Weapon ranges come from the item `pAtkRange` values: bow 500, crossbow 400, polearm 80, everything else 40. *Long Shot* (skill 113) adds +400 to **bows only**. Hawkeye, Silver Ranger and Phantom Ranger learn it, so their 3rd classes shoot at 900.

Columns:
- **Normal attack**: auto-attack range.
- **Primary**: the skill the class mostly fights with, and its cast range.
- **Engage**: where the class wants to stand (primary range minus a ~50 margin).
- **Threat**: how far it can hurt or lock you. This is the maximum reach of its attack, debuff, peel and gap-closer skills, and it's the distance *others* should respect.

### Mages
| Class (id) | Normal attack | Primary (range) | Engage | Threat | Notes |
|---|---|---|---|---|---|
| Archmage (94) | 40 | Prominence 1230 (900) | 850 | 900 | Aura Flare/Cannon 150, Rain of Fire 500 AoE, Aura Flash peel 200 |
| Mystic Muse (103) | 40 | Hydro Blast 1235 (900) | 850 | 900 | Ice Dagger, Frost Bolt and Solar Flare also 900 |
| Storm Screamer (110) | 40 | Hurricane 1239 (900) | 850 | 900 | Death Spike 900, Tempest 500 AoE |
| Soultaker (95) | 40 | Death Spike 1148 / Curse Death Link 1159 (900) | 850 | 900 | Meteor 600 AoE, Anchor 400, curses 600 |
| Soul Hound (132/133) | 40 | Abyssal Blaze 1433 (750) / Soul of Pain 1436 (700) | 650 | 900 | Soul Vortex and Aura Blast 900; binds 400; Blink peel |
| Dominator (115) | 40 | Steal Essence 1245 (900) | 550 | 900 | Seals are caster-centered 200 AoE, so it has to walk in to use them |
| Flame Warchief / Doomcryer (116) | 40 | Steal Essence 1245 / Freezing Flame 1244 (900) | 850 | 900 | Totem play (`FakePlayerPvpOraclesWarchiefs`) overrides this |
| Doom Oracle (98) | 40 | Prophecy of Doom 27530 (900) | 850 | 900 | Mana Burn 900, Dryad Root 600 |
| Hierophant (98) | 40 | Prophecy of Doom 27530 (900) | 850 | 900 | Support first |
| Cardinal (97) | 40 | Heals (600) | 600 behind tank | 600 | Might of Heaven 400; Sleep/Trance 600 |
| Eva's Saint (105) / Shillien Saint (112) | 40 | Heals/buffs (600) | 600 behind tank | 600 | Wind Shackle, Dryad Root 600 |

### Archers
| Class (id) | Normal attack | Primary (range) | Engage | Threat | Notes |
|---|---|---|---|---|---|
| Sagittarius (92) | 500 + 400 = **900** | auto-attack, Lethal/Double Shot (900) | 850 | 900 | Stun Shot 900 |
| Moonlight Sentinel (102) | **900** | auto-attack, Double Shot (900) | 850 | 900 | Arrow Rain 600 AoE, Entangle 600 |
| Ghost Sentinel (109) | **900** | auto-attack, Double Shot (900) | 850 | 900 | Fatal Counter 900 |
| Trickster (134) | 400 (crossbow) | Twin Shot 507 (900) | 850 (skills) / 380 (auto) | 900 | Long Shot doesn't apply to crossbows; Soul Shock 200, Warp |

> Check in game: `npc.getPhysicalAttackRange()` for a fake Sagittarius. Fake players use `NpcTemplate#getBaseAttackRange` (`CreatureStat.java:699`), so make sure Long Shot's +400 really applies to them. If it doesn't, they currently shoot at 500, which on its own would explain archers that feel too close.

### Tanks
| Class (id) | Normal attack | Primary (range) | Engage | Threat | Notes |
|---|---|---|---|---|---|
| Phoenix Knight (90) | 40 (bow 500 when swapped) | melee | 40 | 600 | Shackle 600, Divine Strike 400 |
| Hell Knight (91) | 40 | melee | 40 | 600 | Shackle/Hamstring/Horror 600, Insane Crusher 300 AoE |
| Eva's Templar (99) | 40 | melee | 40 | 600 | Entangle 600, Arrest 150 |
| Shillien Templar (106) | 40 | melee | 40 | 600 | Lightning Strike 400, Freezing Strike/Life Leech 600 |
| Sword Muse / Battle Bard (100) | 40 | melee | 40 | 600 | Entangle 600, Song of Silence 150 aura |

### Melee damage dealers
| Class (id) | Normal attack | Primary (range) | Engage | Threat | Notes |
|---|---|---|---|---|---|
| Adventurer (93) / Wind Rider (101) / Ghost Hunter (108) | 40 | blows (40) | 40 (behind) | **600** | Shadow Step 600 teleports behind; Throwing Dagger 300; Trick 400 / Switch 600 |
| Duelist (88) | 40 | Sonic skills (40) | 40 | 600 | Sonic Blaster/Sonic Rage 600, Rush 400 |
| Grand Khavatari (114) | 40 | Force skills (40) | 40 | 600 | Force Blaster/Raging Force 600, Rush 400 |
| Titan (113) | 40 | Fatal Strike (40) | 40 | 400 | Rush 400, Demolition Impact 500 cone |
| Dreadnought (89) | 80 (polearm) | Power Crush (80) | 80 | 600 | Rush Impact 600, Shock Blast 500 |
| Doombringer (131) | 40 | Dark Smash (40) | 40 | 600 | Rush Impact 600, Soul Rage 600, Dark Explosion 500 |
| Judicator (136) | 40 | thrusts (40) | 40 | 750 | Abyssal Blaze 750, Fallen Arrow 600, debuffs 600 |
| Fortune Seeker (117) | 40 | Lucky Strike (40) | 40 | 150 | No gap closer |
| Maestro (118) | 40 | Fatal Strike (40) | 40 | 600 | Rush Impact 600 |
| Spectral Dancer / War Dancer (107) | 40 | Blade dances (150-300 aura) | 100 | 600 | Danse Macabre 300 aura; Freezing Strike/Drain Health 600 |
| Battle Oracle (98, hybrid) | 40 | Prophecy of Doom (900), fights in melee | 40 | 900 | |

### How to store it
Store it as data with computed defaults, not as constants:

1. **Computed default**: at load (`FakePlayerPvpData`), for each build:
   - `engageRange` is the reach of the first ATTACK skill that is single-target with cast range > 100, or the weapon range for auto-attackers (with Long Shot added for bows).
   - `threatRange` is the max reach across ATTACK, DEBUFF, PEEL and RUSH, plus the weapon range.
2. **Override in XML**, for the cases the computation gets wrong (Dominator, healers, Trickster):
   ```xml
   <build name="Mystic Muse" ... engage="850" threat="900" primary="1235">
   ```
3. **New holder** `FakePlayerEngagement` (a record in `model/actor/holders/npc/`) with `engageRange`, `threatRange` and `primarySkillId`, kept on `FakePlayerPvpBuild`.
4. **Lookup for any creature**, `EngagementRanges.of(Creature)`:
   - A fake player uses its build.
   - A **real player** of a 3rd class uses the same table, keyed by `ClassId`.
   - A 1st/2nd class or unusual player is computed live from `player.getAllSkills()`: max cast range of offensive skills, plus `getPhysicalAttackRange()`.
   - A monster uses its attack range and its skill cast ranges.
   - The result is cached per player and refreshed on skill or weapon change.

---

## 3. Navigation fixes (do these first: big visible win, low risk)

**N1. A firing position instead of standing still.** Add `approach(target, range)` to `FakePlayerPvpAI` and use it everywhere `moveToPawn(target, range)` is used for attacking or casting:
- If out of range, behave like `moveToPawn` today (pathfinding is on: `PathFinding = 2`).
- If **in range but no LoS**:
  1. Sample about 12 points on a ring around the target at `min(range - 50, current distance)`.
  2. Keep the points that are reachable (`canMoveToTarget`, or a path exists) and can see the target (`canSeeTarget`).
  3. Move to the point closest to the fake player.
  4. If none qualifies, `moveToLocation(target)` with a small offset (about 40), so pathfinding takes it around the obstacle.
- Cache the chosen point for about 1.5 s so it doesn't re-sample every tick.

**N2. Stall detection.** Track the actor's position each think. If it has a move intention but has moved less than 20 units in 1.5 s, re-path, and on a second stall sidestep 150-250 units at a fan angle (reuse `FLEE_TURNS`). Then lower `MONSTER_STUCK_TIMEOUT` to about 8 s, because real progress no longer depends on it.

**N3. Kiting with a fan and a purpose.** Rewrite `kiteStep` so it:
- Tries the `FLEE_TURNS` angles, like `runAway` does.
- Scores each candidate on: distance from **all** enemies within threat range (not just the target), keeping LoS to the target (so it can keep shooting), moving toward the party anchor (§6), and staying out of dead ends (check that the point has more than one open direction).
- Only calls itself cornered when every angle fails.

**N4. Last-seen chase.** When the target breaks LoS (around a corner or pillar), remember `lastSeenLoc` and move there, as a player would, instead of re-issuing `moveToPawn` on the live target. Give up after the existing stuck timeout.

**N5. Seeing players.** Detection scans (`lookForPvp`, `isPvpThreatNear`, `findSpotEnemy`) should share a short-lived **party vision** list: anything one member sees (LoS and within 1200) is known to all members for about 5 s. That stops the case where one member fights a player the others "can't see" around a corner.

---

## 4. Keeping the right distance, and knowing when to commit

Replace `MAGE_RANGE` and the plain kite distance with a spacing rule driven by the table:

```
myEngage   = EngagementRanges.of(me).engageRange()
theirThreat = EngagementRanges.of(enemy).threatRange()   // max over nearby enemies targeting me or my party

if (I outrange them: myEngage > theirThreat + 100)
    standoff = clamp(theirThreat + 150, ..., myEngage)   // e.g. Mystic Muse vs Duelist: 750-850
else
    standoff = myEngage                                  // no point kiting a Sagittarius; use cover or commit
```

- **Ranged classes**: hold `standoff`. Step back (N3) when an enemy's gap-closer range plus its expected move this tick crosses `standoff`, not when it's already at 280. Against daggers, start peeling at about 650 (they Shadow Step from 600).
- **Melee classes**: don't walk straight into a 900-range zone covered by several ranged enemies. Close in when one of these holds:
  - the enemy is casting (`isCastingNow`, a 4-second Hydro Blast) or has its back turned,
  - a gap closer is ready (Rush, Shadow Step, Rush Impact),
  - a tank or ally is already in front,
  - or the commit rule below fires.
  Otherwise approach along cover, or wait at the edge of their threat range while the ranged allies work.

**Commit rule (walk in to get the kill).** Each think, for the current target:

```
myDps        = rolling average of damage I dealt to players in the last 10 s (fallback: estimate from P.Atk/M.Atk and skill power)
partyDps     = sum of myDps of party members whose target is the same enemy
ttkEnemy     = (enemy.currentHp + enemy.currentCp) / (myDps + partyDps)
enemyDps     = rolling damage this enemy dealt to my party (fallback: class estimate)
ttkMe        = (me.currentHp + me.currentCp) / max(enemyDps, 1)
reachTime    = max(0, gap - myEngage) / moveSpeed

commit if  ttkEnemy + reachTime < ttkMe * commitFactor   (commitFactor from personality: 0.6 careful … 1.2 reckless)
       or  enemy HP+CP < one primary-skill hit (estimated)
       or  enemy is rooted/stunned/sleeping and inside my gap-closer range
```

While committed:
- ignore standoff,
- use speed buffs and gap closers,
- extend the chase range (but see the party leash in §6),
- stop committing when the rule stops holding for 2 s, or own HP < 35 %.

Damage tracking is cheap: hook into the place damage is applied (`Creature.reduceCurrentHp` or the existing `CombatLogManager` path) and keep a small ring buffer per fake player and per enemy it fights.

---

## 5. "Cheat" knowledge: reading the opponent

Fake players get what a party member window or a target HP bar would show, plus a bit more. Put it behind a config so it can be tuned down:

`FakePvpOpponentKnowledge = 2`
- `0`: HP % only (today).
- `1`: exact HP/CP/MP.
- `2`: also cast bar and cooldowns.

| Read | Used for |
|---|---|
| Exact `currentHp` / `currentCp` / `maxHp` | Commit rule; focus choice (lowest *effective HP*, not HP %). Today CP is ignored, so a full-CP target looks as weak as a no-CP one. |
| `currentMp` vs the MP cost of the enemy's primary skill | A mage or healer below 2-3 casts of MP is harmless at range: walk in. A healer out of MP is a non-target; go for the damage dealer. |
| `isCastingNow()` + `getLastSkillCast()` + cast target | Interrupt (stun/silence, or step out of LoS) when the enemy is casting a big nuke **on a party member**. Melee use the cast time to close in. |
| `isSkillDisabled(gapCloser)` for the enemy's Rush/Shadow Step/Blink | If the dagger's Shadow Step is on cooldown, the archer can hold a tighter standoff. |
| Escape scroll being read | Burst or stun before it finishes. |

Keep the "noticing" of Ultimate Defense, Guts and so on probabilistic, as it is now: that's what makes them feel human. Exact numbers are the cheat; reactions stay a little slow (§7).

---

## 6. Party coordination

### New piece: `FakePartyTactics`
A per-party "brain" that runs once a second, using the 1 s tick that `FakePartyManager` already has (`UPDATE_INTERVAL`). It writes a small snapshot onto `FakePlayerParty`, so members don't each do O(n²) scans:

```java
record PartySnapshot(
    Location anchor,                 // leader position, or centroid of living members if the leader is away
    Creature focus,                  // shared kill target (null = none)
    Map<Integer, Double> pressure,   // member objectId -> pressure 0..n
    Creature pressuredMember,        // the member most in trouble (pressure > 1)
    Set<Creature> knownEnemies,      // party vision (N5)
    Stance stance)                   // ENGAGE, HOLD, RETREAT
```

### P1. Pressure, per member
```
pressure(m) = Σ over enemies e targeting m (e.getTarget() == m, and gap <= threat(e) + 100):
                  estimatedDps(e) / (m.currentHp + m.currentCp)
            + 0.5 * (HP lost in the last 3 s / m.maxHp)
            + 0.3 * (m is stunned/rooted/silenced ? 1 : 0)
```
A pressure above 1 means "dies in about 1-3 s at this rate". Monsters count as well, so a mage pulling 4 monsters is under pressure too.

> **Done early (after the first simulation run):** `FakePlayerPvpAI.guardBackline` applies to any fake tank or support in a party, not only in simulations.
> - **Peel divers.** An enemy within 450 of a mage, archer or healer and targeting it is a diver (a diver on a healer comes first). A tank switches to it and stuns or roots it. A support roots or sleeps it from where it stands.
> - **Stay near the squishies.** A tank no longer chases enemies more than 700 from its squishies. It takes the closest enemy within that range that is fighting the party, or holds 200 in front of the squishies (`GUARD_HOLD`). A support stays within about 450 of its party.
> - **Keep the chosen target.** Fake players no longer drop their target every time a different enemy hits them.
> - **New anomaly.** The monitor flags `TANK_OVERCOMMIT`: a tank more than 900 from its squishies while one of them is being dived.
> - **Target priority and party calls** (`chooseFocus`):
>   - A fake player in a party weighs every enemy in the fight, not only the ones that hit it.
>   - Enemy healers count as much more valuable targets than buffers, and buffers more than other enemies. Enemies the party is already hitting count extra (focus fire).
>   - A member that picks an enemy healer, or an enemy under 30% HP+CP, calls it for 10 s. Teammates within 1500 switch to it, unless its damage-blocking defenses make that pointless for them.
>   - Calls are logged as `focus_call` events. The monitor flags `HEALER_IGNORED`: an enemy healer within 1200 of a team for 20 s with nobody on that team targeting it.
> - **Six fixes after round `20261025-020905`:**
>   1. **Servitor at spawn.** A summoner's servitor is summoned before its AI's first think, and again at a simulation's fight start. A summon cast longer than 5 s isn't started within 3 s of spawning or with an enemy within 2000.
>   2. **Leader hand-off.** `FakePlayerParty.getActiveLeader()` hands the lead to the next fake player while the leader is stunned, rooted, under Ultimate Defense, or casting something of 3 s or more. Logged as `leader_change`.
>   3. **Diver choice.** A tank scores divers by distance to their victim. A diver on a healer comes first, and a melee diver beats a ranged one. It stays on its current diver only while that one is within 150 points of the best.
>   4. **Healer call over finish call.** No finish call on a target that an enemy healer within 900 can heal back up. That healer is called instead.
>   5. **Daggers dive the backline.** Daggers weigh healers twice as much, squishies more and tanks less. They keep Dash and Shadow Step for healers and squishies.
>   6. **Healers under attack.** A healer someone is on peels the attacker, heals itself below 85%, runs to its tank, and never attacks meanwhile (`HEALER_DEFEND`, `HEALER_RETREAT`). Archers and mages peel whoever is on their healer (`PEEL_FOR_ALLY`).
> - **Three fixes after round `20261025-022603`:**
>   - **Tanks don't follow party calls.** They still make calls, but they guard their squishies (`guardBackline`) and don't weigh healers higher.
>   - **Failed calls are dropped.** A call whose target hasn't lost 20% of its HP+CP within 8 s (healed through) is dropped, logged as `call_dropped`. That target isn't called again, and scores lower, for 10 s.
>   - **Enemy threat counts.** `focusScore` adds a threat term: an enemy's damage to the party's fake players so far, from their aggro lists, worth up to −0.4.
>   - **Leader hand-off only for long casts.** `getActiveLeader` only counts casts of 5 s or more as busy (summons), not 3 s fight casts.
> - **Three fixes after mirror round `20261025-023405`:**
>   - **Don't shoot the tank.** Everyone rates an enemy tank 0.35 lower (daggers keep their bigger penalty). Archers and mages weigh enemies up to 1800 away, to see the backline behind a tank.
>   - **Melee doesn't outrun its tank.** A party's melee damage dealer (not a dagger) holds by its party (`HOLD_FOR_TANK`) instead of charging an enemy more than 700 from it. It goes in once its own tank is within 400 of that enemy, or the enemy goes after the party.
>   - **Healers stay put in a fight.**
>     - No minor buffs (resistances, Regeneration, Agility, Bless Shield).
>     - Buffs only on members already in range: no walking to buff.
>     - A body is only raised with no enemy within 600 of it and within 900 of the party.
> - **Immobile defenders.** Archers and mages keep attacking an enemy that can't move (Ultimate Defense, Vengeance, a root) when they stand outside its reach: its auto attack range, its longest damage, debuff or peel skill, or its bow. If they're inside that reach, they step back out first.

### P2. What each role does when an ally is under pressure
| Role | Response |
|---|---|
| **Healer** | Pre-heals the pressured member at < 90 % (not < 75 %), before the lowest-HP member. Moves to keep LoS and stay within 600 of that member but outside the attackers' threat range. Uses its emergency skills (Balance Life etc.) on pressure, not only on HP. |
| **Tank** | Goes to the pressured member. On players: stuns, roots or Shackles the attacker. On monsters: aggro skills (Aggression, Hate Aura). Stands between the attacker and the member. |
| **Melee DPS** | Switches from the focus target to the attacker of the pressured member **if** that attacker is within ~400 of the member and within its own reach. That's the peel. Otherwise it keeps on the focus target. |
| **Ranged DPS / mage** | Uses its PEEL list (Stun Shot, Hamstring, Entangle, Sleep, Anchor, Shackle) on the pressured member's attacker. That's today's peel logic pointed at a different target. Then goes back to the focus. |
| **The pressured member** | Kites **toward the anchor or tank** (N3 scoring), not straight away from the enemy. A healer under pressure never runs off alone. |
| **Support/buffer** | Uses its emergency buffs on the pressured member if it has any (Battle Bard songs, Eva's Templar's...). |

Only 1-2 members peel; the rest keep up the damage. Assign the peelers in `FakePartyTactics`: the closest tank, then the closest member with a ready peel skill. That stops everyone from abandoning the kill.

### P3. Shared focus target
- `FakePartyTactics` picks `focus`: the enemy with the lowest **party** time-to-kill (§4 with summed party DPS), among enemies within the party's engagement area. It keeps the choice for 6 s unless the target goes invulnerable or out of reach, the same idea as `FOCUS_TIME` today but party-wide.
- `chooseFocus` in each member becomes: party focus, unless this member is assigned to peel, or the focus is out of its reach and there's a closer enemy hitting it.
- In parties with real players, the leader's target overrides this (`isPartyPrey` already does it for monsters; extend it to players).

### P4. Staying together
- **Leash to the anchor in PvP.** A member doesn't chase beyond `anchor ± 900` (melee) or `anchor ± 1100` (ranged), unless it's committed (§4) and the enemy is within 300 of the kill.
- **Party-level retreat instead of individual flee.** Stance switches to RETREAT when Σ enemy DPS on the party is clearly above the party's DPS and the party's average HP is under 45 %, or when 2+ members are dead. Then:
  - everyone runs in **one** direction (away from the enemy centroid, through `runAway`'s fan),
  - the healer and mages go first, the tank last and peeling,
  - Scroll of Escape only once out of sight, as today.
  - Today's individual `thinkFlee` is disabled for any party member, not only parties with players.
- **Formation while moving and fighting.** Melee goes in front of the anchor toward the enemy centroid, ranged sits at its standoff behind the anchor, and the healer at about 400 behind the tank. These are just target points fed to `approach` and kiting: no grid formation, which looks robotic.
- **Regroup before a pull.** The leader of a fake-only party doesn't start a new fight while a member is farther than 800 from it, or a support member is still buffing.
- **Party vision** (N5) plus `onFakeAttacked`: an attack on any member puts the enemy in `knownEnemies`, and members fighting monsters drop them for the player once the stance is ENGAGE.

### P5. Monsters (PvE parties)
The same pressure logic applies:
- the tank pulls monsters off a pressured mage,
- the healer heals on pressure,
- ranged members kill the monster on the healer first.
`findPartyTarget` should pick the monster attacking the **most pressured** member, not the closest one.

---

## 7. Keeping it human
- **Reaction delay** per personality (150-600 ms): a new peel assignment or focus switch only takes effect after it. Read from `FakePlayerPvpPersonality` so some fake players are sharp and some sloppy.
- **Mistakes**: a 5-10 % chance (personality-driven) to ignore a peel assignment or overcommit.
- **Party chat** on key events (it's already enabled with `FakePartyChat`): "help!" when pressure > 1.5, "on X" when the focus changes, "run" on RETREAT. Throttle it.

---

## 8. Config (FakeParty.ini / FakePlayerPvp.ini)
```
FakePvpOpponentKnowledge = 2        # 0 HP% only, 1 exact HP/CP/MP, 2 + casts and cooldowns
FakePvpCommitFactor = 0.6,1.2       # personality range for the commit rule
FakePartyPressureThreshold = 1.0
FakePartyMaxPeelers = 2
FakePartyPvpLeashMelee = 900
FakePartyPvpLeashRanged = 1100
FakePartyRetreatHpRatio = 0.45
FakePartyReactionDelay = 150,600
```

---

## 9. Order of work
0. **Simulation harness and logging (§10).** Build it first, so today's behavior can be measured before anything changes, and every later phase can be checked against it with the same seed and points.
1. **Navigation (§3, N1-N4).** Self-contained in `FakePlayerPvpAI`. Fixes the stuck-behind-walls problem for everyone. Also check the Long Shot range question from §2.
2. **Engagement data (§2).** `FakePlayerEngagement`, computed defaults, XML overrides, `EngagementRanges.of(Creature)` for players.
3. **Spacing and commit (§4) plus opponent read (§5).** Replace `MAGE_RANGE` and the kite trigger. Add the damage ring buffers.
4. **`FakePartyTactics` (§6)**: pressure, peel assignment, shared focus, anchor leash, party retreat, party vision.
5. **Human touches and config (§7-8).**

---

## 10. Party-vs-party simulation (testing and debugging)

> **Status: implemented (phase 0).** Code in `java/org/l2jmobius/gameserver/managers/simulation/`, admin handler in `dist/game/data/scripts/handlers/chat/commands/admin/AdminSimulation.java`, config in `config/Custom/FakeSimulation.ini`. Differences from the design below:
> - Each round writes to `log/simulation/<yyyyMMdd-HHmmss>-<seed>/round-NN/`. A `series.json` / `series.txt` with win rates goes in the folder above.
> - `IN_ENEMY_RANGE` isn't checked yet: it needs the engagement ranges from §2.
> - `//SimulationDraw` draws the auto attack range and the longest attack skill range until §2 provides engage/threat ranges.
> - While a round runs, the regions of its members are kept awake (`WorldRegion.keepActive`), so the teams think even far from the admin.
> - `//SimulationHelp` lists the commands.
> - Simulants spawn fully buffed, and are buffed again when the fight starts (`FakePlayerPvpManager.prepareSimulant`). They get:
>   - their build's `<buffs>` list from `FakePlayerPvp.xml`, even with `FakePvpBuffsEnabled = False`;
>   - their toggles;
>   - their own class buffs, except the long-cooldown ones (`FakePvpPvpOnlyReuse`) and the totems and prophecies, which are kept for the fight.
>
>   Each member's buffs are logged in `run.json` and as a `buffs` event at fight start.

### 10.1 Admin commands
| Command | What it does |
|---|---|
| `//SetSimulationPoint <n>` | Saves your position (x, y, z, heading, instance) as spawn point `n`. Running it again with the same `n` moves the point. |
| `//SimulationPoints` | Lists the points with distances between them. Draws them in-game with `ExServerPrimitive` (already in the repo): a circle at each point and a line between each pair, so you can see if a wall is in the way. |
| `//ClearSimulationPoint <n\|all>` | Removes one point, or all of them. |
| `//Simulate <size 1-9> [level] [mirror] [seed=<n>] [rounds=<n>] [time=<s>]` | Spawns one party of `size` at every point (at least 2 points) and starts the fight. Defaults: your level, random builds, a random seed (printed), 1 round, 300 s limit. |
| `//SimulationStatus` | Per team: alive/dead, HP of each member, kills, current stance/focus, and the anomalies found so far. |
| `//SimulationStop` | Ends the run now, writes the summary, and removes the fake players. |
| `//JoinParty <team> [role]` | You replace one fake player of that team (see §10.4). |
| `//LeaveParty` | You leave; the replaced fake player comes back at your position. |
| `//SimulationTrace` | On your target: prints its last 20 AI decisions (§10.5) to chat. Running it again turns it off. |
| `//SimulationDraw` | On your target: draws its engage and threat circles, plus lines to its target and its party focus. Updated every 500 ms. Running it again turns it off. |

Notes:
- **Command names and case.** `AdminCommandHandler.getHandler` matches the name as typed. Register lowercase names (`admin_setsimulationpoint` ...) in `AdminCommands.xml` (access level 100), and lowercase the name in `getHandler`. Then `//SetSimulationPoint` and `//setsimulationpoint` both work.
- **Storage.** Points are saved in `GlobalVariablesManager` (`SIM_POINT_<n>` = `x,y,z,heading,instance`), so they survive a restart.
- **Where the code goes.** The handler goes in `dist/game/data/scripts/handlers/chat/commands/admin/AdminSimulation.java`, registered in `MasterHandler`. It's a script, so it must be **Java 8** (no records, `var` or `case ->`). The handler only parses arguments and calls core code; everything else lives in core (Java 25).

### 10.2 Balanced teams
All teams in a run get **the same list of roles**. Each slot is filled from a pool of builds:

| Pool | Builds |
|---|---|
| Tank | Phoenix Knight, Hell Knight, Eva's Templar, Shillien Templar |
| Melee | Adventurer, Wind Rider, Ghost Hunter, Duelist, Grand Khavatari, Titan, Doombringer, Judicator, Dreadnought, Fortune Seeker, Maestro, War Dancer |
| Ranged | Sagittarius, Moonlight Sentinel, Ghost Sentinel, Trickster, Archmage, Mystic Muse, Storm Screamer, Soultaker, Soul Hounds, Flame Warchief, Doom Oracle |
| Utility: healer | Cardinal, Eva's Saint, Shillien Saint |
| Utility: buffer | Sword Muse, Spectral Dancer, Doomcryer, Hierophant, Battle Bard |

Your core party is **1 tank, 1 melee, 2 ranged, 1 utility (healer)**. Slots are filled in this order, so every size stays as balanced as it can:

| Size | Roles |
|---|---|
| 1 | Melee |
| 2 | Tank, Ranged |
| 3 | Tank, Ranged, Healer |
| 4 | Tank, Ranged, Healer, Melee |
| **5** | **Tank, Melee, Ranged, Ranged, Healer** |
| 6 | + Buffer |
| 7 | + Melee |
| 8 | + Ranged (archer if the team has none, otherwise a nuker) |
| 9 | + second Healer |

- Of the two ranged slots, one is an archer and one a nuker, so every team faces both kinds of range.
- **`mirror`** gives every team the exact same builds, which is the fairest test. Without it, each team picks within each pool.
- Every member of the run gets the same level, the same gear tier and no elite flag. Personality stays random, but it's logged.
- **`seed=<n>`** makes the setup reproducible: builds, gear and personalities, using `Rnd.seeded`, which `spawnClanMember` already uses. Combat rolls stay random, but the same seed gives the same teams, so before/after comparisons are fair.

### 10.3 How a run goes
1. **Spawn.** For each point, spawn the party around it. Reuse `FakePlayerPvpManager.spawnFakePlayer` and the party wiring from `FakePartyManager.onRoamingSpawn`. Each fake player's `FakePlayerPvpProfile` gets `simRunId` and `simTeamId`.
2. **Prep (10 s).** Supports buff the party, everyone casts its own buffs and toggles, and necromancers summon. Nobody moves toward the enemy yet.
3. **Fight.** The goal is to find and kill the other teams:
   - Each team's leader marches toward the nearest other team's centroid. Simulants "know" where the enemy is, which is part of the cheating.
   - The other members follow the leader with today's logic.
   - From the moment anyone sees an enemy, today's fight logic takes over (later, `FakePartyTactics`). This is deliberate: phase 0 measures **the current AI**.
4. **End.** The run ends when only one team has members alive, or at the time limit. In that case the winner is the team with the most HP left, and the result is marked "timeout". Dead fake players stay dead (a healer resurrecting them counts and is logged). Bodies are removed 10 s after the end.
5. **`rounds=<n>`** respawns the same teams (same seed) and repeats, then writes a combined summary of win rates by team, build and role.

Rules for simulants, enforced in one place (`thinkActive` returns early for `profile.isSimulant()`, like `isSpotFighter` does today):
- **Hostility.** Another team's simulant is a PvP enemy (`FakePlayerPvpManager.isPvpEnemy`). Same team is `isSameGroup`. They're flagged with script value 1, so there's no karma.
- **No distractions.** No hunting, taunting, greeting, talks, revenge trips or hotzone leaving. No Scroll of Escape and no logging off; running away is allowed and logged. They ignore monsters and non-simulant players, except an admin who joined.
- Roaming fake players and monsters ignore simulants.
- `//Simulate` warns when monsters or players are within 1500 of a point. Pick an empty spot, such as a closed area or an instance.

### 10.4 `//JoinParty <team> [role]`
- **Who gets replaced.** By default, the member whose role matches your class role (a tank admin replaces the tank). Otherwise the last DPS member. `role` (tank, melee, ranged, healer, buffer) chooses explicitly.
- **What happens.** The replaced fake player is despawned and logged with its build and HP, and you're teleported to where it stood.
- **Leadership stays with the AI.** Today, a party with a player host makes that player the leader, and the fake players switch to "follow the player" mode (`isFakeOnly() == false`). For a simulation the AI should keep leading. Add a fake-leader override to `FakePlayerParty`: you're a member that gets heals, buffs, peels and pressure tracking, and you're shown in the party window (the `FakePartySmallWindow*` packets already exist), but the fake leader still decides where the team goes.
  - `//JoinParty 2 lead` makes you the leader instead, to test how fake players follow a real player.
- **Your character state.** `startFight` skips GMs unless their access level can take aggro, and GMs are often invulnerable or hidden. Joining therefore:
  - turns off invul and hide, and makes you take aggro, for the run only,
  - flags you and heals you to full,
  - restores everything on `//LeaveParty` or when the run ends.
- Your damage, heals, deaths and positions are logged like any member's, marked `"admin":true`.
- You can join before `//Simulate` (you're placed in the team when it spawns) or during a run.

### 10.5 Logging
Each run writes a folder `log/simulation/<yyyyMMdd-HHmmss>-<seed>/`. The files are **JSON lines**, one object per line, so they're easy to grep and diff, and easy for me to read back:

| File | Contents |
|---|---|
| `run.json` | Points, size, level, seed, mirror flag, config values in effect. For each member: build, class, level, gear tier, personality values, skills, and engage/threat ranges once §2 exists. |
| `events.jsonl` | Everything that happens:<br>- `damage` (attacker, target, skill, amount, crit, HP/CP before and after)<br>- `heal`, `buff`, `debuff_landed`, `resisted`, `cancel`<br>- `cast_start` / `cast_end` / `cast_abort`<br>- `kill` / `death` / `resurrect`<br>- `target_change`, `intention_change`<br>- `flee_start`, `flee_end`<br>- `party_join` / `party_leave`<br>- `admin_join`, `admin_command` |
| `decisions.jsonl` | **The AI trace:** why each fake player did what it did (format below). Written when the decision changes, plus a heartbeat every 2 s while it stays the same. |
| `positions.jsonl` | Every 500 ms, for every member: x/y/z, heading, HP/CP/MP, target, intention, moving or not, move destination, path node count, whether it can see its target, distance to target, distance to its leader. This is enough to replay the fight or plot it. |
| `anomalies.jsonl` | Problems the monitor found (list below), each with the time window and the members involved. |
| `summary.json` + `summary.txt` | Winner and duration, a kill timeline, and per member: damage dealt/taken, heals, time alive, time spent in each decision, distance travelled. Plus anomaly counts by type. `summary.txt` is also shown to the admin in a window at the end of the run. |

**Decision trace format.** Every `return` in `thinkAttack`, `thinkActive`, `thinkParty` and `supportParty` gets a reason code. Instead of a bare `return`, call `trace(Reason.KITE_STEP, ...)`:

```json
{"t":51234,"id":268481234,"name":"Zerith","team":2,"role":"RANGED","build":"Mystic Muse",
 "reason":"APPROACH_NO_LOS","target":"Gorvak","gap":540,"los":false,"range":900,
 "hp":0.82,"targetHp":0.40,"moving":false,"dest":null,"focus":"Gorvak","stance":"ENGAGE"}
```

- Reason codes are an enum, e.g. `CAST`, `AUTO_ATTACK`, `APPROACH`, `APPROACH_NO_LOS`, `KITE_STEP`, `KITE_CORNERED`, `PEEL`, `RUSH`, `HOLD_OFF`, `WAIT_INVUL`, `FLEE`, `REGROUP`, `FOLLOW_LEADER`, `SUPPORT_HEAL`, `SUPPORT_BUFF`, `DROP_TARGET_STUCK`, `DROP_TARGET_LEASH`, `COMBO_STEP`, `POTION`, `IDLE`. Later phases add `COMMIT`, `STANDOFF` and `PEEL_FOR_ALLY`.
- **Overhead.** `FakeAiTrace.trace(...)` returns at once unless the fake player is a simulant or traced with `//SimulationTrace`, so roaming fake players pay nothing.
- **Writing.** All writes go into a queue drained by one background thread. AI threads never touch the disk.

**Anomaly monitor.** It runs every 500 ms on the position snapshots and the decision trace, and flags:

| Anomaly | Rule |
|---|---|
| `STUCK` | Has a target, moved < 20 units and dealt no damage for 3 s |
| `NO_LOS_IDLE` | In range of its target, no line of sight, not moving, for > 1 s (the bug from §1) |
| `PATH_FAIL` | Path search returned nothing, or the fallback route was used (hook in `Creature.moveToLocation`) |
| `SPLIT` | Farther than 1200 from its leader during a fight |
| `FLEE_ALONE` | Started fleeing while its party fights on |
| `IN_ENEMY_RANGE` | A ranged member inside an enemy melee's threat range for > 2 s (needs §2) |
| `PRESSURE_UNANSWERED` | A member took > 30 % of max HP in 3 s and no teammate changed target, cast a heal or peeled |
| `TARGET_FLIP` | Changed target more than 3 times in 5 s |
| `WASTED_CAST` | Cast something the target was immune to or that was blocked (Ultimate Defense, a mirror ...) |
| `IDLE_IN_FIGHT` | No action for > 2 s while an enemy within 1200 is fighting its party |
| `HEALER_DIED_FIRST` | The healer was the team's first death with nobody peeling for it |

Every anomaly line has the time window and the related decision lines, so a log tells both what happened and why the AI chose it. At the end of a run, `summary.txt` lists the worst anomalies first.

**Hooks needed in existing code:**
- Damage, heals, debuffs and resists: next to the existing `CombatLogManager` calls (`Creature.java:7421`, `EffectList.java:1585`, `Formulas.java:1547/1633`), guarded by "is a simulant or admin in a run".
- Deaths: `FakePlayerPvpAI.onActionDeath` and `Player.doDie`.
- Casts: `Creature.doCast` / `onMagicFinalizer` / `abortCast`.
- Path failures: the `findFallbackRoute` branch in `Creature.moveToLocation`.

### 10.6 Code layout
- **Core** (`java/org/l2jmobius/gameserver/managers/simulation/`):
  - `FakeSimulationManager`: points, runs, lifecycle, win check, 500 ms monitor tick.
  - `SimulationRun` and `SimulationTeam` (records are fine).
  - `SimulationComposer`: role slots to builds, seeded.
  - `SimulationLog`: async JSON-lines writer, one per run.
  - `SimulationMonitor`: anomaly rules.
  - `FakeAiTrace` plus a `Reason` enum, called from `FakePlayerPvpAI`.
- **Profile** (`FakePlayerPvpProfile`): `simRunId`, `simTeamId`, `isSimulant()`.
- **Party** (`FakePlayerParty`): the leader override and admin membership from §10.4.
- **Config** (`config/Custom/FakeSimulation.ini`):
  ```
  SimulationTimeLimit = 300
  SimulationPrepTime = 10
  SimulationPositionInterval = 500
  SimulationDecisionHeartbeat = 2000
  SimulationLogDir = log/simulation
  SimulationKeepRuns = 50
  ```
  `SimulationKeepRuns` deletes the oldest run folders beyond that number.

### 10.7 Scenarios to run (before and after each phase)
Run each with the same points and seed before and after a phase, and compare `summary.json` and anomaly counts:
- `//Simulate 5 mirror seed=1` on open ground. Baseline: today's `SPLIT`, `FLEE_ALONE` and `PRESSURE_UNANSWERED` counts.
- The same with a pillar or wall between the points. Checks `NO_LOS_IDLE`, `STUCK` and `PATH_FAIL` (phase 1 should bring them near zero).
- `//Simulate 2`, Mystic Muse vs a dagger. The Muse should hold about 850, not 600 (phase 3).
- `//JoinParty 1 ranged` and dive their healer yourself. The tank and a ranged member should peel within about 1 s, and nobody should flee alone (phase 4).
- `//Simulate 9 rounds=20`. Win rates by build or role show balance problems, and lets you watch think time for the load check.
