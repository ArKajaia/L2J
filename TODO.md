# TODO / FIXME Audit

This is a snapshot of the unfinished work (`TODO`/`FIXME` markers) in the project, grouped by how much it affects basic gameplay. There are about 784 markers: about 330 in Java/scripts and about 450 in XML/SQL data, mostly `TODO: Must be confirmed` on NPC/skill values.

Java paths are relative to `java/org/l2jmobius/gameserver/` unless stated otherwise. Script paths are under `dist/game/data/scripts/`.

## Tier 1 — Basic features that are broken or stubbed (players will notice)

| Area | Location | What's missing |
|---|---|---|
| ~~Movement~~ ✅ | `network/clientpackets/MoveWithDelta.java` | Done: the delta is applied to the server-side position and goes through the same checks as `MoveToLocation` (`MoveToLocation.requestMove`) |
| ~~Movement / Z axis~~ ✅ | `model/actor/Creature.java`; `skill/effects/EnemyCharge.java`, `ThrowUp.java`; `instances/CrystalCaverns.java` | Done: approach offsets use the 3D-correct horizontal reach (`LocationUtil.calculateHorizontalReach`), ground movement in `updatePosition` uses horizontal distance like the client, the vertical-flight distance bug (`Math.pow(dz, 2)`) is fixed, and knockback distance no longer grows with height difference |
| Pet item use | `clientpackets/RequestPetUseItem.java:42` | Packet only partly read (`readLong/readInt` commented out) |
| Private store (sell) | `clientpackets/RequestPrivateStoreManageSell.java:34`; `RequestPrivateStoreSell.java:61-62`; `SetPrivateStoreListBuy.java:60` | Packet fields unread or not analysed. This can desync store setup |
| Pet feeding | `actor/tasks/player/PetFeedTask.java:92` | Food is taken only from the player's inventory, not the pet's |
| Pet death penalty | `actor/instance/Pet.java:1186` | "Need Correct Penalty". Pet XP loss on death is a guess |
| Karma | `actor/Player.java:5571` | `FIXME: Karma reduction tempfix` in PvP/PK karma logic |
| Combat formulas | `model/stats/Formulas.java:599, 1454, 1633, 1914, 1961` | Positional bonus values unconfirmed, one formula marked "CHECK/FIX THIS FORMULA UP!!", Matk/Mdef bonus "pending", crit counting unverified |
| Buff immunity | `model/stats/Stat.java:136` | `BUFF_IMMUNITY` stat not implemented |
| Fishing | `model/fishing/Fish.java:56-66, 273`; `skill/effects/Reeling.java:101`, `Pumping.java:101` | Bite rate, length, guts check and cheating probability are loaded but unused. The rod grade bonus formula is guessed |
| Olympiad | `olympiad/AbstractOlympiadGame.java:197` | `FIXME: Make sure player has no buffs` before a match (fairness bug). 3v3 buffer spawn bug (`OlympiadGameTeams.java:290`) |
| Guards | `actor/instance/Guard.java:167, 175` | "Fix normal targeting" |
| NPC template fields | `data/xml/NpcData.java:218-430` | `accuracy`, `reuseDelay`, `distance`, `width`, `evasion`, `hitTime`, `exCrtEffect`, `sNpcPropHpRate` are parsed but not implemented, so NPC skills and attacks ignore them |
| Restart point | `clientpackets/RequestRestartPoint.java:280` | Agathion resurrection (case 6) not handled |
| Crafting | `managers/RecipeManager.java:349` | Crafting animation packet broken |
| Experience | `actor/stat/PlayerStat.java:933, 982` | Nevit's hunting bonus not implemented |
| Variables persistence | `variables/PlayerVariables.java:137`, `AccountVariables.java:132`, `ItemVariables.java:155` | `FIXME: May store after server shutdown`. Risk of data loss or race on shutdown |
| Login security | `loginserver/GameServerThread.java:310` | `isBannedGameserverIP()` always returns `false` |

## Tier 2 — Social / economy features that are incomplete

- **Community Board** (`handlers/bypass/communityboard/`): `MailBoard.java:54`, `MemoBoard.java:55`, `RegionBoard.java:189`, `HomeBoard.java:376` (count returns 0), `ClanBoard.java:272, 301` (search and clan BBS), and `CommunityBoardHandler.java:158` (`_bbspos`). Mail, memo and region boards are empty.
- **Castle economy**: `ai/others/CastleChamberlain.java:809, 818, 880`. `%next_tax_rate%` and `%tax_income_reserved%` are hardcoded to `0`. `handlers/items/Seed.java:80` uses the map region instead of the tax zone.
- **Map regions**: all 28 `dist/game/data/mapregion/*.xml` files say the `castle` attribute should come from TaxZones.
- **Clan / alliance messages**: `Clan.java:1776, 1933, 2499`; `RequestAnswerJoinAlly.java:69`; `RequestPledgeSetAcademyMaster.java:112`; `RequestSurrenderPledgeWar.java:77` ("Implement or cleanup"); `PledgeShowMemberListAll.java:52` (wrong packet structure for sub-pledges).
- **Party / duel / contacts**: `Duel.java:856, 970`; `ContactList.java:34, 158`; `RequestOustFromPartyRoom.java:80`; `ExPartyRoomMember.java:68`; `RequestExAskJoinMPCC.java:147`.
- **Premium drops**: `NpcTemplate.java:908-1363`, mirrored in `DropSearchBoard.java` and `NpcViewMod.java`. Premium rates are not applied to herbs and raids.
- **Enchant / attribute pricing**: `Elementals.java:59, 78` (higher stones); `ExShowBaseAttributeCancelWindow.java:55` and `AbstractRefinePacket.java:294` (S80/S84 prices); `EnchantItemHPBonusData.java:46` (hardcoded modifier that should be in config).
- **Subclass / village master**: `VillageMaster.java:449, 662` (retail messages); `custom/Validators/SubClassSkills.java:37` ("Rewrite").

## Tier 3 — Sieges, residences and large systems

- **Fortress siege control room**: `FortSiege.java:601, 672`; `ExShowFortressSiegeInfo.java:87, 91`; `ExShowFortressMapInfo.java:72, 80`. The 5th (control) room is emulated.
- **HQ zone**: `zone/type/HqZone.java:39-51`. All four `setParameter` branches (castle, fort, clan hall, territory) are empty, so HQ zones aren't bound to residences.
- **Siege guards**: `Attackable.java:969`; `SiegeGuardAI.java:307`; `FortSiegeGuardAI.java:321`. Healer casting rules are missing. `clanhall_siege_guards.sql:395-785` needs random spawn by zone.
- **Territory War**: `TerritoryWarSuperClass.java:81` (`return 0` stub); `RequestJoinDominionWar.java:102` (no punishment); `Player.java:5466`.
- **Clan hall**: `ExClientPackets.java:162` (`REQUEST_AGIT_ACTION` not handled); `ClanHallAuction.java:76`; `FortressOfResistance.java:95`.
- **Seven Signs**: `SevenSigns.java:1623, 1669`; `SevenSignsFestival.java:64, 1951`.
- **Mercenary tickets**: `MercTicketManager.java:50, 67` (values not in `siege.properties`, limits not retail-like).
- **Cursed weapons**: `CursedWeaponsManager.java:192, 286`; `CursedWeapon.java:376`.

## Tier 4 — Content scripts (quests, instances, events, AI)

- **Instances**: CrystalCaverns (missing traps and HTML, lines 74/845/902/917/929), SSQMonasteryOfSilence (area debuffs missing, 346-376), PailakaInjuredDragon (171/245/280/374), DarkCloudMansion, Kamaloka:505, RimKamaloka (no leaderboard), HeartInfinityAttack:774.
- **Quests**: Q00016 (Altars AI incomplete), Q10286/Q10287 (missing HTML), Q00648 (outdated HF rewards), Q00620:397 (always-true random), AbstractSagaQuest:442 (dead `if`), Q00335:686 (dead code), Q00421, Q00417, Q00381, Q10271, Q10272, Q00348 (missing question mark), Q00255 Tutorial:1784.
- **Area AI**: BeastFarm/FeedableBeasts (trained baby beasts not implemented, spice consumption), FantasyIsle TalentShow, SelMahumSquad, ForgeOfTheGods (needs zone spawn support), FourSepulchers, Hellbound (AnomicFoundry, TullyWorkshop, Natives).
- **Events**: SavingSanta (58, 539, 796), CharacterBirthday:105.
- **Skill effects**: SoulEating, EnergyDamage, CallPc:177 (7S dungeon), MagicalDamage:61 and HpDrain:59 (hardcoded cubic skill 4050), SummonNpc:111 (signets), SummonCubic:87, Sow:93/99, Cubic.java:72/587.

## Tier 5 — Project-custom code (recent additions in this fork)

- `config/custom/ClassTransferConfig.java:37, 44`: level thresholds are hardcoded and should be read from a `.properties` file like other `*Config` classes.
- `custom/ClassTransferMaster/ClassTransferMaster.java:116, 159, 222, 276, 280`: "verify getPlayerClass()/getId()" and "verify destroyItemByItemId signature" notes. These look like unverified generated code and should be checked against the real APIs.
- `custom/FakePlayers/PvpFlaggingStopTask.java:30`: should move into `Creature`.
- `handlers/MasterHandler.java:585`: voiced commands have no config toggles.

## Tier 6 — Code health / noise (low priority)

- 34 `// TODO Auto-generated method stub` lines in `handlers/chat/commands/voiced/*.java` and `SellBuffCommandHandler.java`. These are IDE leftovers and can be deleted.
- Refactor notes: `EffectList.java:251, 955, 1779, 1860`, `Player.java:695, 793, 7852, 11137, 15226`, `World.java:179, 215`, `Creature.java:1932, 2328, 3603, 4277`, `RaidBossSpawnManager.java:344`, `ScriptManager.java:192`, `Instance.java:470`, `MerchantPriceConfigTable.java:44`, `BotReportTable.java:57`, `RecipeData.java:63`, `AdminShowQuests.java:39`.
- "Retail message" placeholders: `Attackable.java:269`, `OlympiadManager.java:342`, `ControllableAirShip.java:141`, `Player.java:6818`, and others.
- Data XML: 304 × `TODO: Must be confirmed` and 29 × `FIXME: value unconfirmed` (mostly `stats/npcs/32800-32899.xml`, `25700-25799.xml`, `18900-18999.xml`); 9 × `TODO: Require Support` and 3 × `Needs Support` in skills; zone minZ/maxZ per node (`zones/clan_hall.xml`, `castle_hall.xml`, `castle_siege.xml`); `stats/items/documentation.txt:148` (remove empty `<for>`).

---

## Suggested order of work for "basic game features"
1. ~~**Movement**: implement `MoveWithDelta` and improve Z-axis handling~~ (done).
2. **Pets**: fix `RequestPetUseItem` packet reading, let `PetFeedTask` take food from the pet's inventory, and correct the pet death penalty.
3. **Private stores**: finish reading the `RequestPrivateStoreManageSell` and `SetPrivateStoreListBuy` packets.
4. **Combat correctness**: Formulas.java items, `BUFF_IMMUNITY`, and the unimplemented NPC template fields from `NpcData`.
5. **Olympiad fairness**: strip buffs before a match.
6. **Persistence safety**: the shutdown FIXMEs in `*Variables`.
7. **Community Board**: Mail, Memo and Region boards.
8. **Castle tax display**: CastleChamberlain values and tax zones.
9. **Cleanup**: remove the auto-generated stubs and verify the ClassTransferMaster APIs and config.

To refresh this list: `grep -rIn --exclude-dir=.git -E "TODO|FIXME" java dist`
