# L2J Mobius — Chaotic Throne: High Five

An open-source, Java server emulator for the MMORPG **Lineage II**, implementing the **Chaotic Throne: High Five (CT 2.6)** chronicle. The project reproduces the client/server protocol, world simulation, and content of that chronicle as two independent server processes plus a large data-driven content pack.

Licensed under the **GNU General Public License v3**. Project homepage: `l2jmobius.org`.

This document describes what the codebase actually *does* — its architecture and systems — rather than gameplay patch-note history. It intentionally omits tunable configuration values (rates, thresholds, ports, durations); every system below is configurable through the `.ini`/`.xml` files under `dist/game/config` and `dist/login/config`, but the numbers themselves are left to the operator.

## Table of Contents

1. [Custom (Non-Retail) Feature Set](#custom-non-retail-feature-set)
2. [Architecture at a Glance](#architecture-at-a-glance)
3. [Repository Layout](#repository-layout)
4. [Login & Authentication System](#login--authentication-system)
5. [Network Engine](#network-engine)
6. [World & Entity Model](#world--entity-model)
7. [Stat & Combat Calculation](#stat--combat-calculation)
8. [Skill System](#skill-system)
9. [Item & Economy System](#item--economy-system)
10. [Zone System](#zone-system)
11. [Instance Dungeon System](#instance-dungeon-system)
12. [AI System](#ai-system)
13. [Geodata & Pathfinding Engine](#geodata--pathfinding-engine)
14. [Clan, Residence & Siege Systems](#clan-residence--siege-systems)
15. [Olympiad, Heroes & Seven Signs](#olympiad-heroes--seven-signs)
16. [Minigames & Competitive Systems](#minigames--competitive-systems)
17. [Social & Grouping Systems](#social--grouping-systems)
18. [Community Board](#community-board)
19. [Scripting / Quest Engine](#scripting--quest-engine)
20. [Manager & Background Task Subsystems](#manager--background-task-subsystems)
21. [Security & Anti-Cheat](#security--anti-cheat)
22. [Caching](#caching)
23. [Admin Tools & Console](#admin-tools--console)
24. [Standalone Utilities](#standalone-utilities)
25. [Database Layer](#database-layer)
26. [Data-Driven Content Pack](#data-driven-content-pack)
27. [Build System & Requirements](#build-system--requirements)
28. [License](#license)

---

## Custom (Non-Retail) Feature Set

Beyond reproducing the High Five chronicle, this server layers on a substantial set of original, operator-toggleable systems — roughly 60 dedicated `Custom/*.ini` files plus dozens of supporting manager classes and runtime-compiled scripts. The eleven systems below were traced directly through their implementation; the remaining custom features are cataloged in the summary table at the end of this section.

### Fake Player Population & PvP

Twenty dedicated Java classes implement this feature, split across two subsystems that share infrastructure but serve different purposes:

- **Static "talkable" fake players** — `FakePlayersConfig`, `FakePlayerData`, `FakePlayerHolder`, `FakePlayerChatManager`/`FakePlayerChatHolder`. Pre-placed NPCs that stand, sit, or fish around town to simulate population and answer whispered chat.
- **Roaming PvP fake players** — `FakePlayerPvpManager`, `FakePlayerPvpFactory`, `FakePlayerPvpAI` (a ~2,500-line class), `FakePlayerPvpServitor`/`FakePlayerPvpServitorAI`, `FakePlayerPvpData`, and seven runtime-state/data-holder classes (`FakePlayerPvpProfile`, `FakePlayerPvpBuild`, `FakePlayerPvpPersonality`, `FakePlayerPvpCombo`, `FakePlayerPvpWeapon`, `FakePlayerPvpGearTier`). Dynamically spawned in place of ordinary world monsters, fully capable of PvP combat. The remaining classes round out the feature: an admin command set (`//fakepvp`, `//fakepvp_list`, `//fakepvp_clear`, `//fakechat`), a dedicated `FakePlayerInfo` network packet, and two config classes.

A roaming fake player is not a lightweight NPC approximation of a player, nor a real `Player` instance — it's spawned as an NPC/`Monster`-type template whose every stat is independently computed by re-implementing the real player stat formulas: base attributes and HP/MP/CP tables come from the same per-class/level data a real character uses, P.Atk/M.Atk/speed/crit come from its equipped weapon, defense mirrors the real armor stat-function pipeline including enchant bonuses, and it's granted every passive skill its class would have learned by that level plus armor-set and weapon item skills. Gear is drawn from level-tiered kits with level-gated enchant rolls; level, class/build, and gear are fixed at spawn time (weighted-random from a pool of data-defined class builds) and don't change over its lifetime, though a per-individual randomized "personality" nudges its aggression, chattiness, roaming range, and potion-use away from the server baseline so individuals don't play identically.

PvP engagement is triggered by five independent paths: being attacked, retaliating against a kill-stealer (chance scaled by its personality), opportunistically picking fights with PvP-flagged or karma (red) players it notices nearby, hunting down its own killer for a while after a delayed respawn, and — separately — a one-time greeting when it notices a new player. In a fight it plays tactically: focuses the weakest of multiple attackers, closes gaps with gap-closer skills, kites archers/mages, probabilistically learns to work around a target's defensive buffs, and runs data-defined skill combos (opener → positional attack → finisher). Individually-rolled "runners" flee a losing fight, can read a Scroll of Escape once genuinely unseen, and make a last stand if cornered.

Chat is simulated two ways: static fake players match incoming whispers against a rule table (exact/starts-with/contains matching, per-name or shared pools) and reply from a random canned line after a simulated typing delay; roaming PvP fake players broadcast randomly-chosen, personality-gated lines from eleven separate situational pools (kill-steal accusation, victory, death, fleeing, attacking a flagged/karma player, revenge return, first-sight greeting, and more).

The feature is deliberately built to be indistinguishable from a real character on every player-facing surface: `FakePlayerInfo` reimplements the actual `CHAR_INFO` network packet field-for-field (equipment, class, PvP flag/karma, clan, title, transform, noble/hero flags, and more), names are procedurally generated and checked against collisions with real characters, and its PvP flag runs through the same timer stages (flagged → blinking → clear) a real player's does. It sits to rest, drinks potions with the normal visual effect, and can be disarmed or transformed like a player. It is still a real `Npc` internally, though — anything that specifically iterates the server's actual player collection never includes it, and GM commands can list or target it directly.

### Rotating Hot Zones

A `HotZone` is a minimal zone type that just flags a creature as standing in hot-zone terrain; all the real logic lives in a hand-authored candidate pool (`dist/game/data/zones/custom_hotzones.xml`) of zone polygons purpose-built over real named hunting locations, grouped into level brackets whose monster levels were checked against the bracket at authoring time. A scheduled script (`RotatingHotZones`) periodically strips hot-zone buffs from everyone in the previous rotation's active zones, then for every level bracket randomly picks one candidate zone to become newly "active" and rolls a fresh random modifier for it, announcing the change to every online player and re-applying buffs to whoever is currently standing inside. A player who logs out inside an active zone and back in elsewhere has their buff reconciled against their real position on login, since zone enter/exit events don't fire during login.

Two independent bonus layers stack on an active hot zone: a flat zone-wide buff (one buff for players, a separate one for monsters), and a **rolled modifier** — one random themed risk/reward twist per zone per rotation (some purely positive, some trading a bonus for a real downside, like tougher/faster monsters in exchange for better loot, or a zone that drains player HP while halving monster HP). The modifier is a pure data enum where every field defaults to "no effect" and each variant only overrides what it cares about, so every consuming system (drop rates, monster stats, the champion spawn roll, archetype skill grants, crowd-control resistance) reads the same generic fields regardless of which modifier is active.

Two dedicated managers layer on top: a **coin drop** manager pays a bonus-currency reward (the same currency the Arena Challenge system uses) for player kills anywhere on hot-zone terrain, scaled by victim level and multiplied by the active modifier's coin bonus if any; and a **miniboss** manager counts kills per hot-zone-flagged zone and, once a threshold is reached, spawns a buffed clone of whichever monster template last died there, flagged so the client and other systems can identify it, and broadcasts a warning to everyone in the zone. Players get several forms of feedback: the rotation announcement, enter/exit chat lines, a dedicated teleporter NPC showing every bracket's current hot zone and its modifier with solo/party teleport options, and the miniboss spawn warning. A `.savehotzone` voiced command additionally lets an operator stand somewhere, scan nearby monster levels, and log a ready-to-paste zone-block suggestion — a scouting tool for expanding the candidate pool, not a player-facing mechanic.

### Arena Challenges & Arena Shop

This is two independently-coded scripts (`ArenaMaster`, `ArenaShop`) plus a core `ArenaSurvivalManager`, tied together only by a shared reward-currency convention rather than any direct code reference.

**The Arena Challenge** is solo PvE wave-survival against a single boss that can never actually be defeated. Talking to the Arena Master NPC and confirming spends an entry-fee item — charged only after every validation (system enabled, not already in another instance, a valid challenger pool configured) passes, so a misconfigured or closed arena never costs the player anything. A random challenger template is picked, a **fresh dynamic instance is created per run** (so multiple players can challenge concurrently without interfering with each other), the player is teleported in, buffed, and a death-guard begins polling once a second to snapshot their XP/SP and remaining buff durations in case of death.

The core loop intercepts the challenger's death entirely: instead of dying, it clears the wave, re-rolls a random passive skill sized to its new virtual level, reshuffles which active skills its AI personality currently grants it, fully heals, updates its nameplate, and starts an escalating "enrage" timer that punishes stalling. HP, attack, defense, and speed each grow by independent compounding per-wave multipliers (offense deliberately outpaces defense so the fight stays a damage race), and its effective level is overridden to a wave-scaled **virtual level** specifically to defeat the game's level-gap penalty — so even a low-level challenger template can land full-strength hits on a high-level character. There is no way to permanently defeat it; only the player's own death ends a run. On death, the payout is an escalating per-wave sum plus periodic milestone bonuses, the player's personal-best wave is updated if beaten, and their snapshotted XP/SP loss and buffs are restored once they're safely back outside the instance, which is then destroyed.

An open-world sibling, the **Wave Challenge**, reuses the exact same mechanics on a small percentage of ordinary monster spawns: a fixed-length version of the same escalating-wave, virtual-level pattern, except it does have a final wave — killing it for real converts its final virtual level back into an equivalent arena-wave reward and splits the payout among everyone who damaged it, ranked by contribution.

**The Arena Shop** is a stock multisell merchant with twelve category lists (paired recipe/part catalogs across ascending gear grades, plus materials and crystals) that all price their goods in the same currency item the Arena Challenge and Wave Challenge award — the *only* link between "challenge" and "shop," expressed purely as a shared item id rather than any code coupling. What the shop script itself actually adds is a keyword-search convenience layer on top of stock multisell (the retail multisell window has no built-in search): a `search <words>` command collects matching entries from all twelve source lists into a synthetic, runtime-only list and sends it through the same purchase path a normal category button uses, so search results are validated identically to a real page.

### Buff Skill Purchase (Player Buff Shops)

Lets any player become a "buff seller" through a voiced command (`.sellbuff`) that opens a browsable list of their own known skills intersected with a server-curated whitelist (`SellBuffData.xml`), letting them attach a self-chosen price to each. Going live (`sellbuffstart`) repurposes the existing private-store machinery rather than building a new one — the seller is seated and switched into a package-sell-type store with a custom title, so bystanders see exactly the normal private-store UI even though nothing is actually listed as an item for sale. Entry is blocked during fake death, Olympiad participation, event registration, karma/chaotic state, dueling, fishing, transformation, or while outside a peace zone — the same situational gates a normal store would respect.

A buyer simply walks up and opens the storefront to see the seller's buffs (icon, level, MP cost, price) with buy buttons for themselves or their pet. The purchase is resolved entirely server-side with no action from the seller: proximity, the seller's remaining MP (and any item their skill itself consumes), and the buyer's payment are all re-checked, payment is transferred, the seller's cost is deducted, and the skill is cast directly from seller to buyer — a direct server-side cast, not a queued player action. Pricing uses one configurable currency item shared by every seller on the server. Because nothing tangible changes hands, this is architecturally distinct from a normal trade or item-selling private store: it only borrows the private-store *type flag* and *packets* for its visual presentation, while the actual offer list, whitelist, and transaction logic are separate bypass-command code layered on top.

### Tiered Champion Monsters

Rolled once per spawn, gated to ordinary monsters within a configured level range (never quest NPCs, "undying" templates, raid bosses/minions, or fake players). The roll frequency itself is boosted for spawns inside hot-zone terrain, and boosted again if that zone's currently active modifier happens to be the champion-themed one — a direct cross-reference between the Champion and Hot Zone systems. Three tiers are tried in descending order (tier 3 first, then 2, then 1), each tier its own independent chance, so a monster becomes the highest tier it happens to roll rather than climbing tiers sequentially.

Each tier applies its own HP, attack, attack-speed, and XP/SP-reward multipliers, layered *on top of* a separate, older set of flat "legacy" champion bonuses (regen, drop chance/amount, adena bonus, an extra reward-item list) that still apply uniformly to a champion of any tier — so a champion's total bonus is the product of both layers, not one clean tier definition. A player who outlevels the champion by too much keeps the XP/SP and drop bonuses but loses eligibility for the bonus adena/item/medal drop specifically. Visually, tier 1 gets its own team color and a distinct "Ultra" title tag, while tiers 2 and 3 share the same team color and get "Elite" and a configurable title word respectively — so on screen, tiers 2 and 3 are distinguished only by their title text, not their aura color. An operator toggle can additionally make every champion, regardless of tier, passive instead of auto-aggressive, and champions are excluded from vitality consumption by default.

### Thief & Mage Monster Variants

Both are pure core-engine features — no NPC dialog, no quest hook, nothing in the script content pack at all — rolled at spawn time after the Champion and Wave Challenge rolls, mutually exclusive with each other and with the Champion/Wave-Challenge/Hot-Zone-Miniboss states, and each with their own per-spawn-point cooldown so a location can't immediately re-roll the same variant.

**Thief** monsters are not a stealth or theft mechanic in the literal sense — nothing is ever taken from a player. A tagged Thief fights exactly like the base monster it converted from, with no AI changes whatsoever. What's different is a hidden "bag" counter: every qualifying monster kill by anyone nearby (while the Thief is alive) fills its bag a little further, shown live as a fill-percentage on its nameplate. The only payoff is on the Thief's own death, where its adena drop (and only its adena drop — the rest of its loot table is untouched) is multiplied according to how full its bag was, rewarding players who let a Thief "cook" before killing it rather than sniping it fresh off spawn.

**Mage** monsters are the opposite — a real behavioral rewrite. Eligibility depends on whether the monster actually knows a qualifying offensive spell (from its own template or from its AI-archetype-granted skills), regardless of its base AI type, so even a nominally melee monster can qualify if it happens to know one. A tagged Mage gets a bonus to max MP and cast speed and, while it has mana, a dedicated AI routine takes over: it ignores melee entirely, always casts a ready spell rather than rolling the normal per-skill cast chance, closes distance only far enough to stay in range, and kites — stepping directly away with a validated movement step whenever a target closes inside its preferred range, alternating between retreating and casting rather than continuously backpedaling. Once its mana runs low it falls back to ordinary melee AI until regeneration brings it back above the threshold. Its normal kill-drop and spoil amounts are both scaled up, and its death unconditionally forces a bonus Sealed Cache drop on top of whatever the normal chance-based one would have given.

### Treasure Chests & Sealed Caches

These are two unrelated loot systems that happen to share the word "chest."

**Treasure Chests** (`Chest`, a `Monster` subclass with no separate AI class) sit as passive, motionless scenery until interacted with. Each chest spawn point actually holds two templates — a real chest and a "mimic" — with the real chest's client-visible model deliberately redirected to look identical to the mimic, so a player cannot tell them apart on sight. Attacking a real chest normally (instead of using the correct unlock method) makes it silently disappear with no reward, to respawn later; attacking a mimic makes it curse the attacker and fight back as an ordinary monster. Opening one properly requires casting a dedicated "Open Chest" skill effect (from a key item) at it: the same level-range check is applied identically whether the target is real or a mimic, so a failed attempt never reveals which one it was, before a real chest plays a special animation and yields a randomly-tiered (Common/Rare/Epic) selection of distinct crafting materials, while a mimic instead springs to attack (and can curse) the would-be key user. A woken mimic can optionally chase its attacker within a leash range before healing up and returning home.

**Sealed Caches** are unrelated — they're one of three outcomes of the "Lucky Loot" kill-reward system, which shares one per-player "Luck" stack counter across all three: **Lucky Streak** (not an item — the stack counter itself, built by chance on qualifying kills, reset on death, whose only effect is a drop-rate multiplier and a cosmetic title prefix), **Jackpot** (also not an item — spends accumulated Luck for a chance to re-roll a kill's entire normal drop table several extra times), and **Sealed Cache** (the only physical item of the three — its own independent per-kill chance, boosted but not gated by Luck stacks, that drops one item from a randomly-chosen Common/Rare/Epic tier). Mechanically a Sealed Cache is just a normal item drop reusing the game's existing extractable-lootbox item handler, so opening one is a simple inventory action — a completely separate code path from the Chest/mimic system above, with no skill, no NPC, and no key item involved. It can also be granted unconditionally by a Mage-variant monster's death (see above), on top of whatever the normal chance roll would have given.

### Passive Skill Tree

A Path-of-Exile-style node graph layered on top of normal class progression, allocated points for which are never stored as a counter but derived live from the character's current level each time they're needed — deliberately avoiding a stored "points remaining" value that could drift out of sync. Depending on configuration, each subclass can either get its own independent allocation on the shared graph, or all subclasses can pool into one shared total. Allocated nodes persist per character (and per subclass) in the database; removing a single node refunds its own cost but is only permitted if doing so wouldn't disconnect any other allocated node from its tree's root, verified with a live graph-connectivity check, while a full reset clears everything for a flat cost. Every allocation change funnels through one rebuild routine that strips only the skills the tree itself previously granted (never touching an identically-named skill the character's class already knows on its own), regrants exactly what the current allocation implies, and rebuilds the character's tree-derived stat bonuses from scratch — run after every allocation, reset, login, and subclass switch so the granted skills and the stat bonuses can never drift out of agreement with each other.

Nodes come in several types — a plain root, ordinary small bonuses, mid-tier "notable" nodes, rare and expensive keystones and masters, and nodes that grant a real, permanent skill exactly as if the class had taught it. A keystone isn't a separate mechanism so much as the node type that happens to carry the single most powerful (and priciest) bonus in a given area, often bundled with a deliberate drawback that — unlike ordinary positive bonuses, which are capped — is never softened. The tree is organized into six main archetype sectors with their own lore identities (tank, melee DPS, rogue/dagger DPS, archer, mage/summoner, and support/healer), each with a single entry point, plus six smaller hybrid sectors with no entry point of their own (reachable only by pathing in from an already-allocated neighbor) and a central hub sector that bridges all six main sectors together. A player's class only determines which sector the tree browser opens to by default and which single entry node they can take first — the actual allocation rule has no class restriction at all, so a player who spends enough points can eventually path from their starting archetype into any other sector via the hub or hybrid sectors.

Allocation itself doesn't happen through any in-game window — the in-client Community Board page for this feature is read-only (a summary of granted bonuses and skills, plus a reset button). Real allocation happens on a standalone web page served by an embedded HTTP server the feature starts on its own port, reachable only through a signed, time-limited link (or a short PIN-based exchange) handed to the player in-game, which talks back to the game server to list nodes and submit allocation changes.

### Random Monster Skill Assignment

Two genuinely separate systems implement this, not one — the codebase itself cross-references both:

- **Archetype-driven active skills** (part of the [AI System](#ai-system) described later): every monster lazily rolls one of nine behavioral archetypes (coward, aggressive, skillful, mage, fighter, supporter, rager, avenger, balanced), weighted by traits already on its template, and is granted a small, randomly-chosen set of real castable skills appropriate to that archetype (a support-flavored roll draws from heal/buff-type skills, a mage-flavored one from magic-damage skills, and so on) from a dedicated skill table, filtered by the monster's level. These skills are wired into the AI's own skill-selection logic, so they're genuinely used in combat — the effect is behavioral (the monster fights like its rolled personality) rather than a visible tag. Raid/grand bosses and fake players never roll this.
- **A separate random passive-skill pool**: independently of the above, an eligible monster can be granted a handful of skills drawn completely at random — with no category filtering at all — from one large, flat database-backed pool of monster-appropriate passive skills (weapon/armor masteries, elemental and status resistances, stat bonuses, and similar innate traits; explicitly *not* attack skills). How many it receives scales with its level (or its wave-scaled virtual level, for a monster mid-way through the Arena/Wave Challenge systems, which re-roll this on every wave), with a small independent chance to max it out entirely. These skills are granted exactly like a real skill but are deliberately never registered into the AI's active skill-selection logic, since the source pool is curated to be passive-only. The result is visible to players in two ways: a count is appended to the monster's nameplate, and once the granted count crosses certain thresholds the monster gains a persistent visual aura — one of three escalating effects, the strongest reserved for a fully "overloaded" roll — so a heavily-buffed instance stands out at a glance before a fight even starts.

Both systems clear and re-roll whatever they previously granted a given NPC object each time it (re)spawns, since NPC objects are recycled across respawns rather than recreated from scratch.

### Other Custom Features at a Glance

| Area | Features |
|---|---|
| **Automation & convenience** | Client auto-play, auto-potion use, offline shop/offline play, a bank/deposit system, warehouse sorting, a savable buff-"scheme" system, free (item-less) mount use |
| **Progression & balance** | Per-class balance adjustments, per-NPC stat multipliers, a "de-level" system, a Nobless-status grant NPC, transmogrification (cosmetic item appearance) |
| **Economy** | A premium-account system, zero-price merchant selling, configurable private-store range |
| **PvP & social** | PvP kill announcements and reward items, PvP-title name coloring, a "find PvP" locator, boss-kill announcements, a faction (open-world team) system, an in-game wedding/couple system |
| **World & spawns** | Randomized spawn variation, monster enrage-on-crowd-control, Hellbound zone status progression, configurable starting location and allowed player races |
| **Anti-bot & moderation** | Image CAPTCHA challenges, dual-box detection, bot/macro-client ("walker") detection, automated chat moderation, account-side secondary password change |
| **Server identity** | A configurable login welcome screen message, adjustable in-game server time, an online-player-count/info display, a custom starting title, and **multilingual support** — serving server messages and NPC dialogue in multiple languages from the same data pack |

---

## Architecture at a Glance

The system is split into two independently deployable Java server processes that share a common infrastructure library and a MySQL/MariaDB database:

```
                     ┌──────────────────┐
   game client  ───▶ │   LoginServer    │  authenticates accounts, hands out
                     │  (LoginServer.jar)│  session keys, lists game servers
                     └─────────┬────────┘
                               │ private control channel
                               │ (server registration, session hand-off,
                               │  bans, password/access changes)
                     ┌─────────▼────────┐
   game client  ───▶ │    GameServer    │  world simulation, combat, quests,
                     │  (GameServer.jar) │  economy, sieges, everything else
                     └─────────┬────────┘
                               │
                     ┌─────────▼────────┐
                     │  MySQL / MariaDB │  accounts, characters, clans,
                     │   (via HikariCP) │  items, castles, olympiad, ...
                     └──────────────────┘
```

- **`commons`** — infrastructure shared by both servers: the async network engine, configuration/XML-reader framework, database connection pooling, thread-pool management, cron-like scheduling, and shared Swing console utilities.
- **`loginserver`** — account authentication, session issuance, and the registry of connected game servers.
- **`gameserver`** — the actual MMO simulation: world, combat, AI, economy, sieges, quests, and every player-facing system.
- **`dist/`** — the runtime "datapack": per-subsystem `.ini`/`.xml` configuration, XML game-design data, compiled-at-startup Java content scripts, geodata, HTML dialog pages, SQL schema, and third-party libraries.
- The LoginServer and GameServer never share character data directly — the LoginServer only vouches that a session key belongs to an authenticated account; all gameplay state lives in the GameServer and the database.

## Repository Layout

```
java/org/l2jmobius/
├── commons/       shared network, config, database, threading, crypto, UI infrastructure
├── loginserver/   authentication server
├── gameserver/    the MMO game server
├── tools/         standalone admin utilities (DatabaseInstaller, AccountManager, GameServerRegister, Search)
└── log/           logging manager glue

dist/
├── game/          GameServer runtime tree (config/, data/, launch scripts)
├── login/         LoginServer runtime tree (config/, data/, launch scripts)
├── db_installer/  standalone database-installer runtime tree + SQL schema
├── libs/          bundled third-party jars
└── images/        icons / splash art

launcher/          IDE run configurations for the two servers and the search tool
build.xml          Ant build script
```

The `java/` tree currently holds roughly **1,950 Java source files**. Content and configuration are deliberately kept out of the compiled core and live entirely under `dist/`, so an operator can reshape huge parts of the server (quests, AI, drop lists, spawns, zones, item/skill definitions) without recompiling anything.

---

## Login & Authentication System

Authentication is a three-party handshake between the game client, the LoginServer, and the target GameServer.

**Client ↔ LoginServer.** On connect, the LoginServer draws a scrambled RSA keypair and a Blowfish session key from small pre-generated pools (avoiding per-connection key-generation cost) and sends them to the client in an `Init` packet, itself wrapped in a bootstrap cipher. From then on every packet is Blowfish-encrypted with an XOR checksum trailer for integrity. The client authenticates through a legacy GameGuard-style handshake, then submits an RSA-encrypted login/password block; the LoginServer decrypts it, hashes the password, and compares it against the stored hash. A single-active-session policy is enforced: an account already logged in (in memory on the LoginServer or reported online by any connected GameServer) has its stale session kicked before the new one proceeds. Per-account IP allow/deny lists are checked at the same step. On success the client receives a server list (filtered by its access level, and reflecting each GameServer's live status/population) and, on selecting one, a session key.

**Client ↔ GameServer hand-off.** The client connects directly to the chosen GameServer and presents its session key. The GameServer forwards that key to the LoginServer over their private control channel; the LoginServer confirms it matches the session it issued and the GameServer admits the player. The LoginServer never transmits character data — it only vouches for the session key — and it is kept informed of who is online/offline so future login attempts see accurate state.

**LoginServer ↔ GameServer control channel.** This is architecturally distinct from the client-facing network stack: a small number of GameServers connect over a length-prefixed blocking-socket protocol (rather than the async client engine), authenticated with its own separate RSA/Blowfish handshake. A GameServer registers with a numeric server id plus a secret "hex id" (originally provisioned by the `GameServerRegister` tool) and a list of host/subnet pairs, so the LoginServer can hand different clients the appropriate IP for their subnet. Once authenticated, the channel carries live server-status pushes, and administrative packets for access-level changes, password changes, and player kicks.

**Account protection is layered:**
- Permanent bans (negative access level) and time-boxed temporary bans (evaluated live at login time, no background sweep needed).
- Per-account IP allow/deny lists.
- Brute-force protection: failed login attempts are counted per source IP and escalate to a temporary IP ban.
- A static, reloadable operator ban list matched with subnet-prefix fallback (exact IP → progressively wider subnet), so one entry can ban a whole range.
- Server-wide lockdown (enabled / disabled / GM-only) independent of individual account state.
- An optional in-game secondary "PIN" authentication step at character selection (see [Security & Anti-Cheat](#security--anti-cheat)).

---

## Network Engine

**Transport layer** (`commons.network`) is a bespoke asynchronous engine built on true Java NIO.2 async channels (`AsynchronousSocketChannel`/`AsynchronousServerSocketChannel`/`AsynchronousChannelGroup` with `CompletionHandler` callbacks) rather than a classic selector loop:
- Reads are framed as a length header followed by exactly that many payload bytes, driven entirely by chained completion callbacks so no thread blocks on I/O.
- Once a packet is fully buffered and decrypted, execution of its business logic is handed off to a dedicated packet-executor thread pool, keeping the small I/O completion-thread pool free to keep servicing sockets.
- Outbound packets are queued per client and drained through a fairness scheduler so one chatty connection cannot starve others' traffic.
- Buffers are pooled, size-classed, direct `ByteBuffer`s to minimize garbage-collector pressure under many concurrent connections; a "broadcast" packet mode serializes once and reuses/re-encrypts the same buffer per recipient instead of re-serializing identical broadcast traffic.

**Game client protocol** (`gameserver.network`) layers the actual Lineage II wire protocol on top of that transport. Opcodes are declared in Java enums together with the connection states each is legal in (`CONNECTED`, `AUTHENTICATED`, `ENTERING`, `IN_GAME`, …); a packet sent in the wrong state is silently dropped rather than acted on. A reserved opcode byte escapes into an extended "Ex" opcode space (itself two-level in places, e.g. teleport bookmarks nest a further sub-id) so the protocol isn't limited to 256 message types. In total the client protocol spans on the order of **300 inbound** and **480+ outbound** packet types — the full High Five client protocol surface, roughly 800 distinct message types — backed by enormous localization tables for server-driven system messages and NPC dialogue strings (on the order of 10,000 and 18,000 entries respectively). Session encryption uses a stateful XOR stream cipher keyed per-session (handed to the client in the initial key packet); the very first packet (protocol version negotiation) is sent unencrypted and validated against a configurable accepted-version list, and a legacy GameGuard probe/response exchange is retained in the protocol for client compatibility.

**Flood protection** sits on top of the packet dispatcher: each connected client owns roughly fifteen independent counters, one per sensitive action category (item use, dice rolls, chat, multisell, mail, item drop, character selection, auctions, generic transactions, etc.). Exceeding a category's threshold can automatically kick, temp-ban, or jail the account, with GM accounts exempt and violations logged separately for review.

**Inter-server protocol.** A separate packet family implements the private GameServer↔LoginServer link described above (authentication, player-session hand-off, access-level/password changes, kicks, server-status pushes), independent of the client-facing stack.

---

## World & Entity Model

Every placeable object in the world derives from a common `WorldObject` base (identity, position, world region, event-listener wiring). The living/combat-capable branch is `Creature`, from which the full actor taxonomy fans out:

- **Playable actors** — `Player` (the player character), and `Summon` (pet/servitor base) specialized into `Pet` (→ `BabyPet`), `Servitor`, `Decoy`, and `TamedBeast`.
- **NPCs** (`Npc`) — split into `Attackable` (anything that builds threat and can be fought: `Monster` → `RaidBoss`, `GrandBoss`, `Chest`, treasure/event/festival variants; `Guard`; siege `Defender` → `FortCommander`) and `Folk` (non-aggressive town NPCs: merchants, teleporters, fishermen, warehouse keepers, trainers, village masters per race/class, doormen, auctioneers, class/scheme/Olympiad managers, and more).
- **Vehicles** — `Boat` and `AirShip` (→ `ControllableAirShip`), each with their own movement AI.
- **Siege/world furniture** — destructible siege `Door` objects, `Tower` (→ `ControlTower`, `FlameTower`), inert `StaticObject` decorations, and non-creature `Fence` obstacles consumed by the geodata layer.

Each actor type is backed by a template (`CreatureTemplate` → `NpcTemplate`/`PlayerTemplate`/`DoorTemplate`) carrying its XML-declared base attributes, and by a parallel `Stat`/`Status` class hierarchy that mirrors the actor tree — `Stat` classes own computed combat values, `Status` classes own regeneration and death bookkeeping.

The **data holder layer** (`gameserver.data`) that backs all of this is broad and consistently patterned — roughly ninety dedicated loader/holder classes turning XML or database tables into typed in-memory singletons at startup, covering NPCs, items, skills, spawns, doors/fences, teleports, multisell, buylists, recipes, enchanting, item sets, elemental attributes, experience curves, classes, hennas, fishing, pets, transformations, static objects, category metadata, map regions, siege scheduling, initial equipment/shortcuts, karma, admin access, fake players, localization, and more.

---

## Stat & Combat Calculation

Character and NPC combat power is not computed by one formula but by a **composable pipeline**: six root attributes (`STR`/`INT`/`DEX`/`WIT`/`CON`/`MEN`) each feed a per-level bonus curve; derived combat stats (max HP/MP/CP, P./M. Atk & Def, speed, critical rate, resistances, etc.) are each backed by a `Calculator` — an ordered table of pluggable `Func` objects (add/subtract/multiply/divide/set/enchant-scaling functions) executed in priority order. Equipment, skills, active effects, hennas, and armor sets each contribute their own functions into a creature's calculator set, which is recomputed on demand whenever any of those change. Higher-level combat resolution (hit/damage/critical outcomes) is centralized on top of these calculated stats.

---

## Skill System

Skills are data-defined (id, level, costs, ranges, abnormal timing, etc., loaded from XML) but their **behavior is pluggable**:

- A skill's operation mode — active, passive, toggle, channeled, continuous/self-continuous, dance/song, transformation, triggered, static-reuse, PvP-only, clan, hero/GM/Seven-Signs-restricted, and more — is expressed through classifier flags on the core `Skill` object.
- **Effects** (what a skill actually does) implement a common lifecycle contract (`canStart` / `onStart` / per-tick `onActionTime` / `onExit`, plus success-chance and stat-function contribution hooks) and are resolved **by name at load time** through a reflective effect-handler registry, rather than being compiled into the core engine. The concrete vocabulary — buffs/debuffs, damage-over-time, heals, the full crowd-control set (paralyze, root, sleep, fear, mute), dispel variants, resurrection, physical/magical attacks, stat drains, and more — lives as roughly **165 independent effect implementations** in the content pack.
- **Conditions** (whether a skill/effect may apply at all) are a similarly pluggable library of ~90 classes covering logical composition, player-state checks (level, CP, charges, castle/fort/clan-hall ownership, transformation/flight state), inventory/weapon checks, and game-state checks (chance rolls, time-of-day, distance).
- **Targeting** is two-axis: *who* can be selected (single, area, aura, clan/clan-member, command channel, corpse variants, enemy summon, ground-targeted, holy, flagpole, …) and *how broadly* the effect then applies (fan, point-blank, ring/range, party/party-pledge, and boss-specific scopes) — both resolved through their own pluggable handler registries (~34 target-type implementations).
- **Buff/debuff bookkeeping**: an aggregate per-creature effect list enforces stacking/replacement rules by abnormal category, tracks per-tick scheduling for each active effect, and distinguishes normal expiry from cancellation.
- **Channeling and toggles** are first-class: channeled skills track every simultaneous channeler for effects that scale with participation; toggles are persistent until explicitly turned off.
- **Learning and progression**: skill trees drive normal class learning, subclass certification, and class-transfer routes; a separate skill-enchant system lets a learned skill itself be leveled up further, with its own per-route success/cost tables and bonus items.

---

## Item & Economy System

Item **definitions** (`Weapon`, `Armor`, `EtcItem`, `Henna`) are separate from live, ownable **instances**. Built on top of that base:

- **Enchanting** — normal and blessed scroll types, per-grade enchant ceilings, named success-rate curves, and enchant-level-gated bonus "options" that unlock extra passive effects on specific equipment.
- **Augmentation** — life-stone-style item augmentation, persisted on the item instance.
- **Elemental attributes** — items can carry elemental attack/defense assignments, fed by a dedicated mapping of crafting materials to element and tier.
- **Item sets** — full/partial equipment-set bonuses applied through the same stat-function pipeline used elsewhere.
- **Item auctions** — a dedicated auction subsystem (auction/bid/state model) driving NPC-run scheduled auctions.
- **Storage** — player and pet inventories, player and clan warehouses, personal freight, in-game mail with attachments, and buy-back (refund) history all share a common item-container abstraction.
- **Private stores** — player-run buy/sell shops are a capability attached directly to the player character, with their own listing and request/response packet family.
- **Recipes & manufacturing** — a recipe/ingredient model run through a dedicated crafting manager.
- **Multisell & NPC shops** — exchange-style multisell lists and per-NPC buy lists, both XML-driven and both with scheduled restocking.
- **Regional pricing** — named price zones with their own tax rate, optionally tied to castle ownership so siege outcomes affect local merchant prices.

---

## Zone System

World geometry is organized into **zones**: a common base type carries pluggable geometry (cuboid, cylinder, or arbitrary polygon) and entry/exit hooks, spatially indexed for efficient broadcast. Roughly three dozen concrete zone types are implemented, including:

- **Combat framing** — peace zones, no-PvP zones, arena zones, siege zones, siegable-hall zones, Olympiad stadium zones, boss zones.
- **Residence geography** — castle, fort, and clan-hall zones, residence teleport zones, headquarters/outpost flag zones.
- **Environment/hazard** — water, swamp, damage, periodic-effect, and jail zones.
- **Movement control** — landing/no-landing zones, no-summon-friend zones.
- **Spawn control** — respawn zones, NPC spawn territories, no-restart zones.
- **Commerce/utility** — no-store zones, town zones.
- **Special-purpose** — generic script-hook zones, conditional zones, rotating "hot zones," fishing zones, derby-track zones, event zones (e.g. capture-the-flag).

---

## Instance Dungeon System

Instanced content gets its own isolated world copy: a live instance tracks its own player and NPC sets, per-instance-cloned doors, multiple entry points and a single exit, an optional PvP flag, a UI countdown timer, dead-player ejection timing, and automatic cleanup once empty. Reentry policy is modeled explicitly (the *shape* of the reset rule — fixed schedule vs. time-since-completion — is defined per instance template, independent of any specific duration), and instances can optionally strip buffs on entry/exit. A lighter "instance world" session object lets scripted dungeon logic track boss/quest-specific state without bloating the core instance object. The content pack ships instance families including **Kamaloka**, **Pailaka**, castle and fortress dungeons, Seven-Signs-quest dungeons, the **Seed of Destruction**, and numerous standalone raid/quest instances.

---

## AI System

AI is a single-inheritance state machine built around one high-level intention enum shared by every actor type: idle, active (alert/scan), rest, attack, cast, move-to, follow, pick-up, interact.

- A generic per-tick "thinking" base class specializes into **`AttackableAI`** (NPC/monster combat brain — aggro/threat accumulation, fear handling, attack/cast decision-making), **`PlayerAI`**, **`SummonAI`** (pet/servitor follow-and-assist behavior with obstacle avoidance), **`DoorAI`**, vehicle AI, and a dedicated siege-guard AI family.
- NPC combat behavior is governed by two independent, orthogonal layers: a coarse **AI type** (fighter, archer, balanced, mage, healer, "corpse") governing low-level targeting/range mechanics, and a **monster archetype** governing higher-level decision style — how readily a given monster flees, self-heals, supports allies, holds a grudge, or rages as it loses health. An archetype can be declared per-NPC or, left unset, is rolled and cached the first time it's needed, weighted by the NPC's own template (whether it has healing/buff skills, is a raid boss, its AI type, etc.).
- **Threat/aggro** is tracked per-aggressor as two independent counters (hate, for target selection, and damage, for contribution tracking); leader/minion group behavior lets minions spawn with and defend a leader monster.
- **Boss and quest-scripted behavior** plugs into this system through the same script hook API described in [Scripting / Quest Engine](#scripting--quest-engine) — every major raid and grand boss (Antharas, Valakas, Baium, Beleth, Core, Orfen, Queen Ant, Frintezza, Sailren, and others) has a dedicated AI script driving its actor directly, alongside hundreds of area- and utility-NPC AI scripts.

---

## Geodata & Pathfinding Engine

The geodata engine handles geodata loading, movement validation, line-of-sight, and spatial queries over a custom binary geodata format, stored as a grid of regions subdivided into blocks of three kinds — flat (uniform height), complex (per-cell height plus directional movement flags), and multilayer (multiple stacked height layers per cell, for true multi-floor geometry like bridges and buildings). Per-cell movement flags encode which of the four cardinal directions are physically open from that cell, the primitive that both line-of-movement checks and pathfinding are built on.

- **Line of sight** checks support object-to-object and object-to-location queries, including tolerance for elevated terrain seeing over short obstructions.
- **Line of movement** checks validate straight-line traversal, used both for direct movement and as a post-processing step in pathfinding.
- **Z-axis awareness** — height queries, nearest/next-lower/next-higher Z lookups, and spawn-height resolution let the engine handle multi-level terrain correctly.
- **Obstacles** — closed doors and world fences are consulted directly during region load and during LOS/movement checks, so they behave as real geodata obstacles.
- **Pathfinding** uses node-based **A\*** search over the geodata grid, with pooled, reusable search buffers to avoid per-call allocation, followed by a post-filtering ("string-pulling") pass that greedily collapses the raw grid path down to its real turning points wherever a direct line-of-movement check proves two non-adjacent waypoints are directly walkable.
- Regions with no loaded geodata resolve to a uniform "null region" object rather than requiring null-checks throughout calling code.

---

## Clan, Residence & Siege Systems

- **Clan** — membership, per-rank privilege bitflags, crests, and clan-wide bookkeeping. **Alliances** are modeled as a shared ally id across member clans (with an ally-dissolution penalty concept) rather than as a separate entity.
- **Castles and Forts** both extend a shared residence abstraction (ownership, upkeep, treasury-style capabilities) while keeping their distinct mechanics separate — castles add a full manor/seed production economy; forts add their own spawn and teleport-access rules.
- **Clan Halls** come in two acquisition flavors implemented as distinct subtypes: auction-acquired halls (bid-based) and siege-acquired ("conquerable") halls, captured through combat.
- **Sieges** — castle sieges, fort sieges, conquerable-hall sieges, and territory wars all implement one common "siegable" contract, giving a single polymorphic model for attacker/defender/owner clan participation, siege towers, combat flags, territory wards, and siege scheduling, despite their gameplay differences.
- Dedicated managers handle siege guard spawning for castles and forts, mercenary-ticket-based siege preparation, clan-hall auctions, and conquerable-hall siege orchestration independently of the castle/fort siege path.

---

## Olympiad, Heroes & Seven Signs

- **Olympiad** — a ranked 1v1/team combat metagame with its own participant tracking and match orchestration, supporting classed, non-classed, normal, and team match modes, run out of dedicated Olympiad stadium zones and an Olympiad-manager NPC.
- **Heroes** — Olympiad performance feeds a hero-title system with its own hero-only skills, a dedicated hero chat channel, and monuments/commemoration NPCs.
- **Seven Signs** — the classic Dawn-vs-Dusk faction competition system, including its recurring festival sub-game, with dedicated Dawn/Dusk priest NPCs and Seven-Signs-restricted skills and instance dungeons.

---

## Minigames & Competitive Systems

Beyond the core combat loop, the server implements a number of self-contained side systems: **Kratei's Cube** and the **Underground Coliseum** (level-bracketed PvP arenas with their own curator NPCs and reward tiers), **Handy's Block Checker**, **Monster Derby** racing with betting, a server-wide **lottery**, a **fishing** system (rods, bait, fish, and monsters, with its own zone type) including a recurring **fishing championship**, the **Dimensional Rift** (tiered team-based instanced combat), and the classic **cursed weapon** world-drop mechanic (a powerful, cursed item that a player can wield only temporarily before being forced to relinquish it).

---

## Social & Grouping Systems

**Parties** and **command channels** (multi-party raid groups) share a common player-group abstraction that other systems — targeting scopes, loot rules, experience sharing — build on. Beyond grouping, the server implements **duels** (1v1 and party duels with their own ready/result flow), a **GM petition** (help-ticket) queue, **in-game mail** with item attachments, a **friends list**, and an in-game **wedding/couple** system.

---

## Community Board

The in-client Community Board (the ".bbs" interface) is backed by a lightweight forum/topic/post data model (with clan, memo, and mail-flavored forum types alongside normal ones) and a set of interactive board pages served as cached HTML and routed through the same bypass-handling pipeline used for NPC dialogs: clan management, regional teleport/info, a home page with favorites/bookmarks, a friends list, personal memos, in-client mail, an item drop-source search utility, and management of the passive skill tree feature (see [Custom Feature Set](#custom-non-retail-feature-set)).

---

## Scripting / Quest Engine

Nearly all narrative and NPC-reactive content — quests, boss and area AI, seasonal events, instance logic, and the implementations behind every handler category below — is written as **plain Java source** under `dist/game/data/scripts` and compiled **in-process at server startup** using the JDK's own compiler API (so a full JDK, not just a JRE, is required to run the server), honoring an include/exclude filter that lets an operator disable whole script folders without touching the content itself. A load failure in one script is caught and logged individually without aborting the rest of the load.

Almost all of this content extends one large base class that acts as the integration seam between content and engine, exposing:
- A **registration API** — dozens of `addXxxId(...)` methods that opt specific NPC, item, or zone IDs into an event category (talk, first-talk, kill, attack, spawn, skill-see, spell-finished, trap-action, faction-call, aggro-range-enter, creature-see, zone-enter/exit, event-received, move-finished/route-finished, NPC-hate, summon-spawn/talk, item-bypass/talk, teleport, class-condition, and more) without touching core engine code.
- **Lifecycle callbacks** — `onTalk`, `onAttack`, `onKill`, `onDeath`, `onSpawn`, `onSkillSee`, `onSpellFinished`, `onAcquireSkill` (and related skill-teaching hooks), `onEnterZone`/`onExitZone`, `onOlympiadMatchFinish`, scripted-route callbacks, and a generic event hook for HTML-bypass-triggered logic — plus a self-contained quest-timer facility for delayed or repeating callbacks.
- **Runtime services** — direct access to cached NPC dialog HTML, database persistence helpers, the full manager layer, and every outbound packet type, so a script can drive dialog, cutscenes, rewards, and world-state changes entirely from content code.

The content pack compiled through this pipeline is large: on the order of **540 quest scripts**, **240 AI scripts** (boss and area behavior), **30 instance-dungeon scripts**, **25 custom-feature scripts**, **10 seasonal event scripts**, plus the class-transfer, vehicle-route, and conquerable-hall-siege scripts — roughly **1,300 Java source files** in total once the handler implementations below are included, all sharing the same base-class API.

**The handler system** (`gameserver.handler` defines the interfaces; the datapack under `handlers/` supplies the implementations, all registered at boot) is the other major pluggability layer:

| Category | Purpose | Approx. implementations |
|---|---|---|
| Admin commands | GM `//` commands (teleport, spawns, item/currency grants, server control, debugging) | ~85 |
| User commands | Client-UI-triggered player shortcuts (mount, `/loc`, `/time`, unstuck, party/siege/Olympiad info) | ~15 |
| Voiced commands | Player-typed `.command`s (banking, autoplay, offline shop, password change, premium status, wedding, DPS meter) | ~25 |
| Chat handlers | Delivery/range logic per chat channel (general, shout, trade, party, clan, alliance, whisper, hero, petition, battlefield) | 14 |
| Bypass handlers | Server-side link commands from NPC/HTML dialogs (shops, multisell, warehouse, freight, Olympiad, augmenting, observation) | ~30 |
| Community Board handlers | `.bbs` page logic (see [Community Board](#community-board)) | 10 |
| Item handlers | "What happens when this item is used" (shots, scrolls, dice, recipe books, teleport bookmarks, enchant items) | 30 |
| Skill effects | The buff/debuff/damage/heal/CC/transform/summon vocabulary every skill is built from | ~165 |
| Skill target types | Target-selection algorithms | ~34 |
| Action / shift-click handlers | Left-click and shift-click behavior per world-object type | ~16 |
| Punishment handlers | Executes ban / chat-ban / party-ban / jail | 3 |

Because scripts compile against the core engine (never the reverse), a handful of features that both sides need share are implemented as core managers with thin script-side wrappers rather than being split across the boundary.

---

## Manager & Background Task Subsystems

**Managers** (roughly 65 classes) own the live, in-memory state of nearly every subsystem discussed above, grouped loosely as:
- **World/spawn/environment** — day/night spawn and skill swapping, zone ownership, scripted NPC patrol routes, town lookups, raid/grand-boss spawn state, "hot zone" bonus-farming mechanics, special monster variants, monster-rage escalation, fake-player world population.
- **Siege/castle/fortress/territory** — castle and fort management, castle manor economy, siege and siege-guard orchestration, conquerable clan-hall sieges, territory wars, mercenary-ticket tracking.
- **Instances/minigames/events** — instance-template orchestration, Dimensional Rift, Seed of Destruction/Infinity, Kratei's Cube, Underground Coliseum, Monster Derby, lottery, Handy's Block Checker, fishing championship, temporary global drop-rate events.
- **Economy/items/progression** — item lifecycle and ground items, item auctions, recipes, the passive skill tree, raid-boss point tracking, PC-café point rewards, premium accounts, player-run buff shops, kill-reward jackpot mechanics, cursed weapons, unique ID allocation, a persistent global key/value variable store.
- **Social** — duels, GM petitions, mail, CAPTCHA challenges, anti-multibox/anti-feed tracking, weddings.
- **Vehicles** — airship and boat route/state management.
- **Server lifecycle** — daily resets, precautionary and scheduled restarts, and the central punishment dispatcher used uniformly by GM commands, flood protection, and bot detection.

**Task managers** (18 classes) are the periodic/background jobs layered on top: a central in-game clock driving day/night transitions, combat-stance expiry, batched AI think-cycles, follow-target and movement interpolation updates, mutual-visibility/perception checks feeding aggro, corpse/NPC decay, NPC respawn timers, idle social animations, PvP-flag expiry, item lifetime/auto-destroy/mana-drain timers, mail expiry, periodic autosave of online characters, scheduled shop restocking, and the server-side implementations of client auto-play, auto-potion, and generalized auto-use/macro behavior.

---

## Security & Anti-Cheat

- **Secondary (PIN) authentication** — an optional in-game numeric PIN independent of the account password, hashed before storage, with an attempt-limit lockout that triggers a login-server-issued temporary ban and an automated notification.
- **CAPTCHA** — image-based challenge/response for suspected automation, feeding into the punishment system on failure.
- **Anti-multibox / anti-feed tracking** — per-IP and per-character counters across regular play, Olympiad, and event contexts to detect dual-boxing and kill-feeding abuse.
- **Bot/macro-client detection** — chat-content heuristics that recognize known third-party bot/macro client signatures and report them directly to the punishment system, alongside cumulative chat-spam scoring that auto-applies chat bans.
- **Hardware fingerprinting** — the client's hardware profile (MAC address, OS version, CPU, GPU, memory layout) is captured and tied to account variables for ban-evasion tracking.
- **Flood protection** — see [Network Engine](#network-engine).
- **Central punishment engine** — a single dispatcher and a small set of pluggable handlers (ban, chat-ban, party-ban, jail) used uniformly by admin commands, flood protection, CAPTCHA failure, and bot detection alike.
- **Legacy anti-cheat handshake** — the original GameGuard-style probe/response exchange is retained in the protocol for client compatibility.
- **GM accountability** — every admin/GM command invocation is written to a dedicated, per-operator audit log independent of general server logs.
- **Protocol-level hardening** — per-opcode connection-state whitelisting (an out-of-state packet is silently dropped), a session cipher that only activates post-handshake, and a configurable accepted-client-protocol-version list.
- **Account-level protections** — see [Login & Authentication System](#login--authentication-system) for ban tiers, IP allow/deny lists, and brute-force protection.

---

## Caching

A deliberately small, focused cache layer: all NPC dialog and Community Board HTML pages are loaded into an in-memory map keyed by path (avoiding per-request disk I/O), can be hot-reloaded from the admin console without a restart, and gracefully fall back to live reads if disabled; a second small cache holds computed pairwise relationship state (PvP/clan/ally status and auto-attackable flags) between characters so it isn't recomputed on every status packet. Other subsystems (e.g. passive-tree stat bonuses) maintain their own local caches close to where they're used rather than in a shared cache package.

---

## Admin Tools & Console

Each server process can run a local Swing-based operator console in place of a plain stdout log, offering: graceful shutdown/restart with an operator-supplied delay and an abort option; hot-reload of configuration, GM access levels, HTML cache, multisell lists, buy lists, and the cash-shop catalog, without a full restart; server-wide announcements; a log viewer; and a status panel showing uptime, build info, and live online-player count. This is a local operator console, not a remote/web admin panel — remote and in-game administration instead goes through the GM admin-command handler system and the Community Board described above.

---

## Standalone Utilities

Four small dual-mode (GUI-or-console) tools sit alongside the two servers:
- **DatabaseInstaller** — creates the target database if missing (optionally backing up and dropping an existing one first) and runs the login and/or game SQL schema scripts in order, with per-file progress and error handling.
- **AccountManager** — creates, searches, edits (password reset, access-level change), and deletes accounts directly against the database, with a paginated account browser.
- **GameServerRegister** — registers or unregisters a GameServer identity against the login database, generating the secret "hex id" credential a GameServer presents during its authentication handshake.
- **Search** — a developer productivity tool for regex file-content search across the working tree, filterable by file extension.

---

## Database Layer

Database access is centralized through a **HikariCP** connection pool (self-tuning pool sizing, leak detection, connection validation, and optional startup self-tests that probe whether the database can really serve the configured pool size) talking to **MySQL/MariaDB** over the standard MySQL JDBC driver. SQL is written directly against MySQL syntax rather than through a vendor-abstracted ORM. A backup routine can shell out to `mysqldump` for timestamped, age-pruned dumps, invoked around login-server shutdown when enabled.

The shipped schema spans roughly **115 game-server tables** — covering accounts' game-side data, characters and every per-character subsystem (skills, subclasses, recipes, macros, shortcuts, hennas, friends, contacts, premium items, instance timers, item reuse state, offline trade/play), clans and every clan subsystem (privileges, notices, subpledges, wars, crests), castles/forts/clan-halls and their sieges and functions, item auctions, the manor economy, grand bosses and raid points, heroes and their diaries, the Olympiad, Seven Signs and its festival, cursed weapons, the Dimensional Rift, fishing championship, lottery, Monster Derby, wedding/couple data, custom mail, the passive skill tree, and global server variables — plus roughly **4 login-server tables** for accounts, per-account IP authentication rules, and registered game servers.

---

## Data-Driven Content Pack

Everything under `dist/game/data` and `dist/game/config` is loaded through a shared XML-reader framework that validates every file against a companion XSD schema (roughly 80 schemas ship with the pack) before parsing, so malformed content fails fast at load time rather than misbehaving at runtime. The pack includes:

- **Geodata** — the binary pathing/height data consumed by the [geodata engine](#geodata--pathfinding-engine), covering the game world region by region.
- **Spawns** — NPC spawn lists, organized per region/area.
- **Zones** — the geometry and parameters for every [zone type](#zone-system).
- **HTML** — every NPC dialog window and Community Board page, served through the HTML cache.
- **Instances** — per-dungeon templates for the [instance system](#instance-dungeon-system), organized by dungeon family.
- **Multisell & buylists** — exchange shops and NPC sell lists.
- **Teleporters** — NPC-driven teleport destination lists.
- **Map regions** — town/region boundaries used for respawn-point resolution and the world map.
- **Passive tree** — the node graph for the custom passive skill tree feature.
- **Stats** — base experience/HP/MP growth and related per-level tables.
- **Localization (`lang/`)** — server-message and NPC-string translations layered on top of the base client strings, enabling the [multilingual support](#custom-non-retail-feature-set) feature.

Configuration itself is split between the main `dist/game/config` (and `dist/login/config`) directories — one `.ini`/`.xml` file per core subsystem (server/network identity, database, threading, rates, PvP, player rules, floodprotection, NPCs, geo engine, grand bosses, Olympiad, sieges, territory wars, underground coliseum, access levels, admin command permissions, bot-report punishments, class-master, script include/exclude rules, secondary auth, siege scheduling, dynamic per-level XP/SP curves) — and a **`Custom/` overlay directory** of roughly 60 additional files for the non-retail feature set described next, keeping stock-chronicle configuration cleanly separated from added features.

---

## Build System & Requirements

- **Build tool**: Apache Ant (≥ 1.8.2).
- **Language/runtime**: Java 25 — the build hard-fails if a JDK25+ toolchain isn't detected. A full **JDK** (not just a JRE) is required at runtime as well, because game content scripts are compiled in-process at server startup.
- **Database**: MySQL or MariaDB, accessed through HikariCP connection pooling.
- **Garbage collector**: the shipped IDE run configurations target the Z Garbage Collector (`-XX:+UseZGC`).
- **Third-party dependencies** are deliberately minimal: a MySQL JDBC driver, the HikariCP connection pool, and an SLF4J logging facade/backend pairing (used mainly to quiet HikariCP's own logging) — no bundled scripting or bytecode-manipulation library, since the runtime script compiler is the JDK's own.
- A single `javac` pass compiles the entire `java/` source tree, which the `jar` target then partitions via include/exclude filesets into three independently runnable artifacts: **`LoginServer.jar`** (login server plus the account-manager and game-server-registration tools), **`GameServer.jar`** (the game server plus the developer search tool), and a minimal, mostly self-contained **`DatabaseInstaller.jar`**. A final build step assembles a complete `build/server` tree combining the compiled jars with the full `dist/` datapack, ready to deploy.

---

## License

This program is free software, licensed under the **GNU General Public License, version 3** (or, at your option, any later version). See the license header in `build.xml` for the full notice.
