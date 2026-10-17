package custom.Bestiary;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.sun.net.httpserver.HttpExchange;

import org.l2jmobius.commons.util.SimpleJson;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.config.custom.BestiaryConfig;
import org.l2jmobius.gameserver.data.sql.CharInfoTable;
import org.l2jmobius.gameserver.data.xml.ClassListData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.managers.BestiaryManager;
import org.l2jmobius.gameserver.managers.CraftingGoalManager;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.npc.DropType;
import org.l2jmobius.gameserver.model.actor.holders.npc.DropGroupHolder;
import org.l2jmobius.gameserver.model.actor.holders.npc.DropHolder;
import org.l2jmobius.gameserver.model.actor.holders.player.ClassInfoHolder;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.craftinggoal.CraftingGoal;
import org.l2jmobius.gameserver.model.craftinggoal.CraftingGoalLine;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.itemcontainer.Inventory;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.stats.TraitType;

import custom.PassiveSkillTree.PassiveTreeApiServer;

/**
 * The bestiary page and its API, served by the passive tree's web server. A player opens it with ".bestiary", which hands out a link with the same signed token as ".treelink"; the page works on any class and also after the player logged off, until the token expires.
 * <ul>
 * <li>GET /bestiary.html - the page</li>
 * <li>GET /api/bestiary/collection - the whole collection with the kills and tiers of the character's account, and its region EXP. Monsters not found yet come without their name (unless BestiaryShowUndiscoveredNames)</li>
 * <li>GET /api/bestiary/monster?id=... - one monster, as much of it as the account's tier shows, with its drop bonus</li>
 * <li>GET /api/bestiary/ranking - the hunters who found the most monsters</li>
 * <li>POST /api/bestiary/track - points the character's radar at a monster (id=...), when the character is in the world</li>
 * <li>GET /api/bestiary/farming?spoil=0|1 - where to farm what the character's crafting goals still miss ({@link BestiaryFarming}), when the character is in the world: the goals and their counts live with it</li>
 * <li>GET /api/bestiary/map - the land of the map, public</li>
 * </ul>
 * The monsters' 3D models, skins and portraits, and the GM bake page, are served by {@link BestiaryArt}. Without them the page draws a portrait of each monster's race.
 * <ul>
 * </ul>
 * @author Claude
 */
public class BestiaryWeb
{
	private static final Path HTML_FILE = Path.of("data/html/custom/bestiary.html");
	private volatile String _mapJson;

	/** The weapons a monster can be weak to or resist (the traits of type 1). */
	private static final TraitType[] WEAPON_TRAITS =
	{
		TraitType.SWORD,
		TraitType.BLUNT,
		TraitType.DAGGER,
		TraitType.POLE,
		TraitType.FIST,
		TraitType.DUAL,
		TraitType.DUALFIST,
		TraitType.DUALDAGGER,
		TraitType.BOW,
		TraitType.CROSSBOW,
		TraitType.RAPIER,
		TraitType.ANCIENTSWORD
	};
	/** The debuffs a monster can resist, be weak to or be immune to (traits of type 3 that skills land). */
	private static final TraitType[] RESIST_TRAITS =
	{
		TraitType.POISON,
		TraitType.BLEED,
		TraitType.HOLD,
		TraitType.SLEEP,
		TraitType.SHOCK,
		TraitType.PARALYZE,
		TraitType.DERANGEMENT,
		TraitType.ROOT_PHYSICALLY,
		TraitType.ROOT_MAGICALLY,
		TraitType.GUST
	};

	private BestiaryWeb()
	{
	}

	/**
	 * Adds the bestiary's paths to the passive tree's web server. Called once its server is running.
	 */
	public void register()
	{
		if (!BestiaryConfig.ENABLED)
		{
			return;
		}

		// The region EXP bonus counts the monsters of each region as the page groups them.
		BestiaryManager.getInstance().setRegionSource(() -> BestiaryCatalog.getInstance().getRegionMonsters());

		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		server.addRoute("/bestiary.html", "GET", false, exchange -> server.sendPage(exchange, HTML_FILE));
		server.addRoute("/api/bestiary/collection", "GET", false, this::handleCollection);
		server.addRoute("/api/bestiary/monster", "GET", false, this::handleMonster);
		server.addRoute("/api/bestiary/ranking", "GET", false, this::handleRanking);
		server.addRoute("/api/bestiary/map", "GET", false, this::handleMap);
		server.addRoute("/api/bestiary/track", "POST", true, this::handleTrack);
		server.addRoute("/api/bestiary/farming", "GET", false, this::handleFarming);
		BestiaryArt.getInstance().register(server);
	}

	/**
	 * @param player the player
	 * @param npcId the monster to open the page on, or 0
	 * @return the address of the player's bestiary
	 */
	public static String getUrl(Player player, int npcId)
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		return server.getPageUrl("bestiary.html") + "?token=" + server.generateToken(player.getObjectId(), player.getClassIndex()) + (npcId > 0 ? "&monster=" + npcId : "");
	}

	// ---------------------------------------------------------------------------------------------
	// Collection
	// ---------------------------------------------------------------------------------------------

	private void handleCollection(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		final int charId = server.authenticateCharacter(exchange);
		if (charId < 0)
		{
			return;
		}

		final BestiaryCatalog catalog = BestiaryCatalog.getInstance();
		final BestiaryManager manager = BestiaryManager.getInstance();
		final Map<Integer, BestiaryManager.Entry> entries = manager.getEntries(charId);
		final BestiaryManager.ServerStats serverStats = manager.getServerStats();

		final StringBuilder json = new StringBuilder(256 * 1024);
		json.append('{');
		appendCharacter(json, charId);
		json.append(",\"tierNames\":").append(strings(BestiaryConfig.TIER_NAMES));
		json.append(",\"tiers\":").append(ints(BestiaryConfig.TIERS));
		json.append(",\"bossTiers\":").append(ints(BestiaryConfig.BOSS_TIERS));
		json.append(",\"dropBonus\":").append(ints(BestiaryConfig.DROP_BONUS));
		json.append(",\"bossDropBonus\":").append(ints(BestiaryConfig.BOSS_DROP_BONUS));
		json.append(",\"regionExpBonus\":").append(ints(BestiaryConfig.REGION_EXP_BONUS));
		json.append(",\"showNames\":").append(BestiaryConfig.SHOW_UNDISCOVERED_NAMES);
		json.append(",\"showAreas\":").append(BestiaryConfig.SHOW_UNDISCOVERED_AREAS);
		json.append(",\"craftGoals\":").append(BestiaryFarming.isAvailable());

		json.append(",\"regions\":").append(strings(catalog.getRegions().toArray(new String[0])));
		final Map<String, Double> regionExp = manager.getRegionExp(entries);
		json.append(",\"regionExp\":[");
		for (int i = 0; i < catalog.getRegions().size(); i++)
		{
			json.append(i > 0 ? "," : "").append(String.format(Locale.US, "%.3f", regionExp.getOrDefault(catalog.getRegions().get(i), 1.0)));
		}
		json.append(']');
		json.append(",\"areas\":[");
		boolean first = true;
		for (BestiaryCatalog.Area area : catalog.getAreas())
		{
			json.append(first ? "" : ",").append("{\"id\":").append(area.getId());
			json.append(",\"r\":").append(catalog.getRegions().indexOf(area.getRegion()));
			json.append(",\"n\":").append(SimpleJson.quote(area.getName()));
			json.append(",\"lv\":").append(Math.round(area.getAverageLevel())).append('}');
			first = false;
		}
		json.append(']');

		long kills = 0;
		int found = 0;
		json.append(",\"monsters\":[");
		first = true;
		for (BestiaryCatalog.Kind kind : catalog.getMonsters().values())
		{
			final NpcTemplate template = kind.getTemplate();
			final BestiaryManager.Entry entry = entries.get(template.getId());
			final int killCount = entry != null ? entry.kills() : 0;
			final int tier = BestiaryManager.getTier(template, killCount);
			kills += killCount;
			found += tier > 0 ? 1 : 0;

			json.append(first ? "" : ",");
			appendSummary(json, template, entry, tier);
			if ((tier > 0) || BestiaryConfig.SHOW_UNDISCOVERED_AREAS)
			{
				json.append(",\"a\":").append(ints(kind.getAreas()));
			}
			json.append('}');
			first = false;
		}
		json.append(']');

		// Monsters met outside the collection (instances, quests): only the account's own.
		json.append(",\"elsewhere\":[");
		first = true;
		for (Map.Entry<Integer, BestiaryManager.Entry> entry : entries.entrySet())
		{
			if (catalog.getMonsters().containsKey(entry.getKey()))
			{
				continue;
			}
			final NpcTemplate template = NpcData.getInstance().getTemplate(entry.getKey());
			if (template == null)
			{
				continue;
			}
			json.append(first ? "" : ",");
			appendSummary(json, template, entry.getValue(), BestiaryManager.getTier(template, entry.getValue().kills()));
			json.append('}');
			first = false;
		}
		json.append(']');

		json.append(",\"found\":").append(found);
		json.append(",\"total\":").append(catalog.getMonsters().size());
		json.append(",\"kills\":").append(kills);
		json.append(",\"rank\":").append(serverStats.getRank(manager.getAccountName(charId)));
		json.append(",\"hunters\":").append(serverStats.hunters().size());
		json.append(",\"statsTime\":").append(serverStats.time());
		appendForms(json);
		appendBounties(json, charId);
		appendSlayer(json, entries);
		json.append('}');
		server.sendJson(exchange, 200, json.toString());
	}

	/** The rare forms of the systems that are enabled: {b: bit, n: name}. */
	private static void appendForms(StringBuilder json)
	{
		final int enabled = BestiaryManager.getEnabledVariants();
		json.append(",\"forms\":[");
		boolean first = true;
		for (int i = 0; i < BestiaryManager.VARIANT_NAMES.length; i++)
		{
			if ((enabled & (1 << i)) != 0)
			{
				json.append(first ? "" : ",").append("{\"b\":").append(1 << i).append(",\"n\":").append(SimpleJson.quote(BestiaryManager.VARIANT_NAMES[i])).append('}');
				first = false;
			}
		}
		json.append(']');
	}

	/** Today's bounties of the account, how much they count and pay, and when the next ones come. */
	private static void appendBounties(StringBuilder json, int charId)
	{
		final BestiaryManager.BountyBoard board = BestiaryManager.getInstance().getBountyBoard(charId);
		json.append(",\"bountyOn\":").append(BestiaryConfig.BOUNTY_ENABLED);
		json.append(",\"bountyReset\":").append(board.resetAt());
		json.append(",\"bountyMult\":").append(BestiaryConfig.BOUNTY_KILL_MULTIPLIER);
		json.append(",\"bountyAdena\":").append(BestiaryConfig.BOUNTY_ADENA_PER_LEVEL);
		json.append(",\"bounties\":[");
		for (int i = 0; i < board.bounties().size(); i++)
		{
			final BestiaryManager.Bounty bounty = board.bounties().get(i);
			json.append(i > 0 ? "," : "").append("{\"id\":").append(bounty.npcId()).append(",\"k\":").append(bounty.progress()).append(",\"goal\":").append(bounty.goal()).append('}');
		}
		json.append(']');
	}

	/** The account's slayer bonus against each race with enough monsters, and the bonus of each tier. */
	private static void appendSlayer(StringBuilder json, Map<Integer, BestiaryManager.Entry> entries)
	{
		json.append(",\"slayerDamage\":").append(ints(BestiaryConfig.SLAYER_DAMAGE_BONUS));
		json.append(",\"slayerDefense\":").append(ints(BestiaryConfig.SLAYER_DEFENSE_BONUS));
		json.append(",\"slayerMin\":").append(BestiaryConfig.SLAYER_MIN_MONSTERS);
		json.append(",\"slayer\":[");
		boolean first = true;
		for (BestiaryManager.Slayer slayer : BestiaryManager.getInstance().getSlayer(entries).values())
		{
			json.append(first ? "" : ",").append("{\"r\":").append(SimpleJson.quote(slayer.race().name()));
			json.append(",\"n\":").append(slayer.monsters());
			json.append(",\"f\":").append(slayer.found());
			json.append(",\"dmg\":").append(String.format(Locale.US, "%.2f", slayer.damage()));
			json.append(",\"def\":").append(String.format(Locale.US, "%.2f", slayer.defense())).append('}');
			first = false;
		}
		json.append(']');
	}

	private void appendCharacter(StringBuilder json, int charId)
	{
		final Player player = World.getInstance().getPlayer(charId);
		final String name = player != null ? player.getName() : CharInfoTable.getInstance().getNameById(charId);
		json.append("\"me\":{\"id\":").append(charId);
		json.append(",\"name\":").append(SimpleJson.quote(name != null ? name : ""));
		json.append(",\"online\":").append(player != null);
		if (player != null)
		{
			json.append(",\"level\":").append(player.getLevel());
			json.append(",\"cls\":").append(SimpleJson.quote(className(player.getPlayerClass().getId())));
		}
		json.append('}');
	}

	/** The fields of a monster on the collection's grid, without the closing brace. */
	private void appendSummary(StringBuilder json, NpcTemplate template, BestiaryManager.Entry entry, int tier)
	{
		json.append("{\"id\":").append(template.getId());
		if ((tier > 0) || BestiaryConfig.SHOW_UNDISCOVERED_NAMES)
		{
			json.append(",\"n\":").append(SimpleJson.quote(template.getName()));
		}
		json.append(",\"l\":").append(template.getLevel());
		json.append(",\"r\":").append(SimpleJson.quote(template.getRace().name()));
		json.append(",\"t\":").append(typeCode(template));
		json.append(",\"h\":").append(Math.round(template.getFCollisionHeight()));
		if (entry != null)
		{
			json.append(",\"k\":").append(entry.kills()).append(",\"g\":").append(tier).append(",\"f\":").append(entry.firstKill());
			final int variants = entry.variants() & BestiaryManager.getEnabledVariants();
			if (variants != 0)
			{
				json.append(",\"v\":").append(variants);
			}
		}
		if (tier >= 2)
		{
			json.append(",\"e\":").append(BestiaryCatalog.getInstance().getAttackElement(template));
		}
		if ((tier > 0) && template.isAggressive())
		{
			json.append(",\"ag\":1");
		}
		appendLook(json, template.getId());
	}

	// ---------------------------------------------------------------------------------------------
	// One monster
	// ---------------------------------------------------------------------------------------------

	private void handleMonster(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		final int charId = server.authenticateCharacter(exchange);
		if (charId < 0)
		{
			return;
		}

		final int npcId = parseInt(server.readParams(exchange).get("id"));
		final BestiaryCatalog.Kind kind = BestiaryCatalog.getInstance().getMonsters().get(npcId);
		final BestiaryManager.Entry entry = BestiaryManager.getInstance().getEntries(charId).get(npcId);
		final NpcTemplate template = NpcData.getInstance().getTemplate(npcId);

		// A monster outside the collection is only shown to who found it, so the page can't be used to look through every npc.
		if ((template == null) || ((kind == null) && (entry == null)))
		{
			server.sendText(exchange, 404, "application/json", "{\"error\":\"no such monster in your bestiary\"}");
			return;
		}

		final int kills = entry != null ? entry.kills() : 0;
		final int tier = BestiaryManager.getTier(template, kills);
		final int[] tiers = BestiaryManager.getTiers(template);

		final StringBuilder json = new StringBuilder(8 * 1024);
		json.append("{\"id\":").append(npcId);
		json.append(",\"tier\":").append(tier);
		json.append(",\"kills\":").append(kills);
		json.append(",\"tiers\":").append(ints(tiers));
		json.append(",\"l\":").append(template.getLevel());
		json.append(",\"r\":").append(SimpleJson.quote(template.getRace().name()));
		json.append(",\"t\":").append(typeCode(template));
		json.append(",\"inCollection\":").append(kind != null);
		json.append(",\"dropBonus\":").append(BestiaryManager.getDropBonus(template, tier));
		json.append(",\"dropBonuses\":").append(ints(BestiaryManager.isBoss(template) ? BestiaryConfig.BOSS_DROP_BONUS : BestiaryConfig.DROP_BONUS));
		appendLook(json, npcId);
		if (entry != null)
		{
			json.append(",\"first\":").append(entry.firstKill()).append(",\"last\":").append(entry.lastKill());
			json.append(",\"v\":").append(entry.variants() & BestiaryManager.getEnabledVariants());
			if (entry.bestTime() > 0)
			{
				final String by = CharInfoTable.getInstance().getNameById(entry.bestChar());
				json.append(",\"best\":").append(entry.bestTime()).append(",\"bestBy\":").append(SimpleJson.quote(by != null ? by : ""));
			}
		}
		if (BestiaryManager.isBoss(template) && (tier > 0))
		{
			appendBossKills(json, npcId);
		}
		if ((tier > 0) || BestiaryConfig.SHOW_UNDISCOVERED_NAMES)
		{
			json.append(",\"n\":").append(SimpleJson.quote(template.getName()));
		}
		if ((kind != null) && ((tier > 0) || BestiaryConfig.SHOW_UNDISCOVERED_AREAS))
		{
			json.append(",\"a\":").append(ints(kind.getAreas()));
			json.append(",\"points\":[");
			for (int i = 0; i < kind.getPoints().size(); i++)
			{
				final int[] point = kind.getPoints().get(i);
				json.append(i > 0 ? "," : "").append('[').append(point[0]).append(',').append(point[1]).append(']');
			}
			json.append(']');
		}

		if (tier >= 1)
		{
			appendDiscovered(json, template, kind);
		}
		if (tier >= 2)
		{
			appendStudied(json, template);
		}
		if (tier >= 3)
		{
			appendMastered(json, template, BestiaryManager.getDropBonus(template, tier));
		}
		json.append('}');
		server.sendJson(exchange, 200, json.toString());
	}

	/** Name, size, vitals, rewards, respawn, and the server-wide numbers. */
	private void appendDiscovered(StringBuilder json, NpcTemplate template, BestiaryCatalog.Kind kind)
	{
		final BestiaryCatalog.Stats stats = BestiaryCatalog.getInstance().getStats(template);
		if ((template.getTitle() != null) && !template.getTitle().isEmpty())
		{
			json.append(",\"title\":").append(SimpleJson.quote(template.getTitle()));
		}
		json.append(",\"aggressive\":").append(template.isAggressive());
		json.append(",\"hp\":").append(stats.hp);
		json.append(",\"mp\":").append(stats.mp);
		json.append(",\"exp\":").append(Math.round(template.getExp() * RatesConfig.RATE_XP));
		json.append(",\"sp\":").append(Math.round(template.getSP() * RatesConfig.RATE_SP));
		json.append(",\"radius\":").append(Math.round(template.getFCollisionRadius()));
		json.append(",\"height\":").append(Math.round(template.getFCollisionHeight()));
		json.append(",\"weapon\":").append(SimpleJson.quote(template.getBaseAttackType() != null ? template.getBaseAttackType().name() : ""));
		json.append(",\"live\":").append(stats.live);
		if (kind != null)
		{
			json.append(",\"spawns\":").append(kind.getSpawnCount());
			json.append(",\"respawn\":[").append(kind.getRespawnMin()).append(',').append(kind.getRespawnMax()).append(']');
		}

		final BestiaryManager.ServerStats serverStats = BestiaryManager.getInstance().getServerStats();
		final BestiaryManager.MonsterStats monster = serverStats.monsters().get(template.getId());
		json.append(",\"server\":{\"hunters\":").append(serverStats.hunters().size());
		if (monster != null)
		{
			json.append(",\"kills\":").append(monster.kills()).append(",\"found\":").append(monster.hunters());
			if (monster.firstFinder() != null)
			{
				json.append(",\"firstBy\":").append(SimpleJson.quote(monster.firstFinder())).append(",\"firstAt\":").append(monster.firstKill());
			}
			if (monster.bestTime() > 0)
			{
				json.append(",\"best\":").append(monster.bestTime());
			}
		}
		json.append(",\"time\":").append(serverStats.time()).append('}');
	}

	/** Combat stats, elements and skills. */
	private void appendStudied(StringBuilder json, NpcTemplate template)
	{
		final BestiaryCatalog.Stats stats = BestiaryCatalog.getInstance().getStats(template);
		json.append(",\"stats\":{");
		json.append("\"pAtk\":").append(stats.pAtk);
		json.append(",\"mAtk\":").append(stats.mAtk);
		json.append(",\"pDef\":").append(stats.pDef);
		json.append(",\"mDef\":").append(stats.mDef);
		json.append(",\"pAtkSpd\":").append(stats.pAtkSpd);
		json.append(",\"mAtkSpd\":").append(stats.mAtkSpd);
		json.append(",\"run\":").append(stats.runSpeed);
		json.append(",\"walk\":").append(stats.walkSpeed);
		json.append(",\"accuracy\":").append(stats.accuracy);
		json.append(",\"evasion\":").append(stats.evasion);
		json.append(",\"critical\":").append(stats.critical);
		json.append(",\"range\":").append(stats.attackRange);
		json.append('}');
		json.append(",\"attackElement\":").append(stats.attackElement);
		json.append(",\"attackElementValue\":").append(stats.attackElementValue);
		json.append(",\"defenseElements\":").append(ints(stats.defenseElements));
		appendTactics(json, template, stats);

		json.append(",\"skills\":[");
		final List<Skill> skills = new ArrayList<>(template.getSkills().values());
		skills.sort((a, b) -> a.isPassive() != b.isPassive() ? (a.isPassive() ? 1 : -1) : a.getName().compareTo(b.getName()));
		boolean first = true;
		for (Skill skill : skills)
		{
			json.append(first ? "" : ",").append("{\"id\":").append(skill.getId());
			json.append(",\"lv\":").append(skill.getLevel());
			json.append(",\"n\":").append(SimpleJson.quote(skill.getName()));
			json.append(",\"p\":").append(skill.isPassive());
			json.append(",\"d\":").append(skill.isDebuff()).append('}');
			first = false;
		}
		json.append(']');
	}

	/**
	 * How it fights and what it is weak to: aggro and help ranges, how it moves, its AI, the weapons that hurt it more or less, and the debuffs it resists.
	 */
	private static void appendTactics(StringBuilder json, NpcTemplate template, BestiaryCatalog.Stats stats)
	{
		json.append(",\"tactics\":{");
		json.append("\"aggro\":").append(template.isAggressive() ? template.getAggroRange() : 0);
		json.append(",\"help\":").append(!template.getClans().isEmpty() ? template.getClanHelpRange() : 0);
		json.append(",\"moves\":").append(template.canMove());
		json.append(",\"walks\":").append(template.isRandomWalkEnabled());
		json.append(",\"ai\":").append(SimpleJson.quote(template.getAIType() != null ? template.getAIType().name() : ""));

		// A weapon's damage is multiplied by 2 minus the defence against it (Formulas.calcWeaponTraitBonus).
		json.append(",\"weapons\":[");
		boolean first = true;
		for (TraitType trait : WEAPON_TRAITS)
		{
			final int percent = Math.round((1 - stats.defenceTraits[trait.ordinal()]) * 100);
			if (Math.abs(percent) >= 5)
			{
				json.append(first ? "" : ",").append("{\"t\":").append(SimpleJson.quote(trait.name())).append(",\"v\":").append(percent).append('}');
				first = false;
			}
		}
		json.append(']');

		json.append(",\"resists\":[");
		first = true;
		for (TraitType trait : RESIST_TRAITS)
		{
			final boolean immune = stats.traitsInvul[trait.ordinal()] > 0;
			final int percent = Math.round((stats.defenceTraits[trait.ordinal()] - 1) * 100);
			if (immune || (Math.abs(percent) >= 5))
			{
				json.append(first ? "" : ",").append("{\"t\":").append(SimpleJson.quote(trait.name())).append(",\"v\":").append(percent);
				json.append(immune ? ",\"immune\":1}" : "}");
				first = false;
			}
		}
		json.append("]}");
	}

	/** The last kills of a raid or grand boss across the server. */
	private static void appendBossKills(StringBuilder json, int npcId)
	{
		final List<BestiaryManager.BossKill> kills = BestiaryManager.getInstance().getBossKills(npcId);
		json.append(",\"bossKills\":[");
		for (int i = 0; i < kills.size(); i++)
		{
			final BestiaryManager.BossKill kill = kills.get(i);
			json.append(i > 0 ? "," : "").append("{\"at\":").append(kill.time());
			json.append(",\"d\":").append(kill.duration());
			json.append(",\"by\":").append(SimpleJson.quote(kill.killer()));
			json.append(",\"clan\":").append(SimpleJson.quote(kill.clan()));
			json.append(",\"p\":").append(kill.partySize()).append('}');
		}
		json.append(']');
	}

	/**
	 * Drops and spoils, at the server's rates and with the account's bestiary bonus for the items it applies to (without anyone's premium or other bonuses).
	 * @param dropBonus the account's drop bonus for the monster (percent)
	 */
	private void appendMastered(StringBuilder json, NpcTemplate template, int dropBonus)
	{
		final boolean boss = BestiaryManager.isBoss(template);
		final BestiaryManager.ObservedDrops observed = BestiaryConfig.DROP_STATS_ENABLED ? BestiaryManager.getInstance().getObservedDrops(template.getId()) : null;
		final Map<Integer, BestiaryManager.ObservedItem> observedDrops = byItem(observed != null ? observed.drops() : null);
		final Map<Integer, BestiaryManager.ObservedItem> observedSpoils = byItem(observed != null ? observed.spoils() : null);
		json.append(",\"drops\":[");
		boolean first = true;
		if (template.getDropGroups() != null)
		{
			int group = 0;
			for (DropGroupHolder dropGroup : template.getDropGroups())
			{
				group++;
				for (DropHolder drop : dropGroup.getDropList())
				{
					first = appendDrop(json, first, drop, drop.getChance() * (dropGroup.getChance() / 100), boss, group, dropBonus, observedDrops);
				}
			}
		}
		if (template.getDropList() != null)
		{
			for (DropHolder drop : template.getDropList())
			{
				first = appendDrop(json, first, drop, drop.getChance(), boss, 0, dropBonus, observedDrops);
			}
		}
		appendObservedOnly(json, first, observedDrops);
		json.append(']');

		json.append(",\"spoils\":[");
		first = true;
		if (template.getSpoilList() != null)
		{
			for (DropHolder drop : template.getSpoilList())
			{
				first = appendDrop(json, first, drop, drop.getChance(), boss, 0, 0, observedSpoils);
			}
		}
		appendObservedOnly(json, first, observedSpoils);
		json.append(']');

		if (observed != null)
		{
			json.append(",\"observed\":{\"drops\":").append(observed.dropRolls()).append(",\"spoils\":").append(observed.spoilRolls()).append(",\"min\":").append(BestiaryConfig.DROP_STATS_MIN_KILLS).append('}');
		}
	}

	/** @return the observed items by id; each one is taken out as its row is written, so what is left never was in the drop list */
	private static Map<Integer, BestiaryManager.ObservedItem> byItem(List<BestiaryManager.ObservedItem> items)
	{
		final Map<Integer, BestiaryManager.ObservedItem> byItem = new LinkedHashMap<>();
		if (items != null)
		{
			for (BestiaryManager.ObservedItem item : items)
			{
				byItem.put(item.itemId(), item);
			}
		}
		return byItem;
	}

	/** Items that came out of the drops or spoil although the lists don't have them (events, other systems' extras): no chance, only how often. */
	private static void appendObservedOnly(StringBuilder json, boolean first, Map<Integer, BestiaryManager.ObservedItem> observed)
	{
		for (BestiaryManager.ObservedItem seen : observed.values())
		{
			final ItemTemplate item = ItemData.getInstance().getTemplate(seen.itemId());
			if (item == null)
			{
				continue;
			}
			json.append(first ? "" : ",").append("{\"i\":").append(item.getId());
			appendItem(json, item);
			json.append(",\"x\":1,\"on\":").append(seen.times()).append(",\"oa\":").append(seen.amount()).append('}');
			first = false;
		}
	}

	private static void appendItem(StringBuilder json, ItemTemplate item)
	{
		json.append(",\"n\":").append(SimpleJson.quote(item.getName()));
		json.append(",\"g\":").append(SimpleJson.quote(item.getCrystalType() != null ? item.getCrystalType().name() : "NONE"));
		json.append(",\"k\":").append(SimpleJson.quote(itemKind(item)));
	}

	private boolean appendDrop(StringBuilder json, boolean first, DropHolder drop, double baseChance, boolean boss, int group, int dropBonus, Map<Integer, BestiaryManager.ObservedItem> observed)
	{
		final ItemTemplate item = ItemData.getInstance().getTemplate(drop.getItemId());
		if (item == null)
		{
			return first;
		}

		final double[] rates = rates(drop, item, boss);
		json.append(first ? "" : ",").append("{\"i\":").append(item.getId());
		appendItem(json, item);
		// An item in two groups: its count is the item's, so it goes on its first row.
		final BestiaryManager.ObservedItem seen = observed.remove(item.getId());
		if (seen != null)
		{
			json.append(",\"on\":").append(seen.times()).append(",\"oa\":").append(seen.amount());
		}
		json.append(",\"mn\":").append(Math.max(1, Math.round(drop.getMin() * rates[1])));
		json.append(",\"mx\":").append(Math.max(1, Math.round(drop.getMax() * rates[1])));
		// The bestiary's bonus is for the items, as NpcTemplate rolls it: not adena or herbs, nor spoils (the caller gives them none).
		final boolean boosted = (dropBonus > 0) && (item.getId() != Inventory.ADENA_ID) && !item.hasExImmediateEffect();
		json.append(",\"c\":").append(String.format(Locale.US, "%.4f", Math.min(100, baseChance * rates[0] * (boosted ? 1 + (dropBonus / 100.0) : 1))));
		if (boosted)
		{
			json.append(",\"b\":1");
		}
		if (group > 0)
		{
			json.append(",\"gr\":").append(group);
		}
		json.append('}');
		return false;
	}

	/** @return {chance multiplier, amount multiplier} of the server's rates for this drop, as the Community Board's drop search reads them */
	static double[] rates(DropHolder drop, ItemTemplate item, boolean boss)
	{
		if (drop.getDropType() == DropType.SPOIL)
		{
			return new double[]
			{
				RatesConfig.RATE_SPOIL_DROP_CHANCE_MULTIPLIER,
				RatesConfig.RATE_SPOIL_DROP_AMOUNT_MULTIPLIER
			};
		}

		final int itemId = item.getId();
		double chance = 1;
		if (RatesConfig.RATE_DROP_CHANCE_BY_ID.get(itemId) != null)
		{
			chance = RatesConfig.RATE_DROP_CHANCE_BY_ID.get(itemId);
		}
		else if (item.hasExImmediateEffect())
		{
			chance = RatesConfig.RATE_HERB_DROP_CHANCE_MULTIPLIER;
		}
		else if (boss)
		{
			chance = RatesConfig.RATE_RAID_DROP_CHANCE_MULTIPLIER;
		}
		else
		{
			chance = RatesConfig.RATE_DEATH_DROP_CHANCE_MULTIPLIER;
		}

		double amount = 1;
		if (RatesConfig.RATE_DROP_AMOUNT_BY_ID.get(itemId) != null)
		{
			amount = RatesConfig.RATE_DROP_AMOUNT_BY_ID.get(itemId);
		}
		else if (item.hasExImmediateEffect())
		{
			amount = RatesConfig.RATE_HERB_DROP_AMOUNT_MULTIPLIER;
		}
		else if (boss)
		{
			amount = RatesConfig.RATE_RAID_DROP_AMOUNT_MULTIPLIER;
		}
		else
		{
			amount = RatesConfig.RATE_DEATH_DROP_AMOUNT_MULTIPLIER;
		}

		if ((itemId == Inventory.ADENA_ID) && (chance > 100))
		{
			chance = 100;
		}
		return new double[]
		{
			chance,
			amount
		};
	}

	private static String itemKind(ItemTemplate item)
	{
		switch (item.getType2())
		{
			case ItemTemplate.TYPE2_WEAPON:
			{
				return "weapon";
			}
			case ItemTemplate.TYPE2_SHIELD_ARMOR:
			{
				return "armor";
			}
			case ItemTemplate.TYPE2_ACCESSORY:
			{
				return "jewel";
			}
			case ItemTemplate.TYPE2_QUEST:
			{
				return "quest";
			}
			case ItemTemplate.TYPE2_MONEY:
			{
				return "adena";
			}
			default:
			{
				return item.hasExImmediateEffect() ? "herb" : "etc";
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Ranking, map, pictures
	// ---------------------------------------------------------------------------------------------

	private void handleRanking(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		final int charId = server.authenticateCharacter(exchange);
		if (charId < 0)
		{
			return;
		}

		final BestiaryManager.ServerStats serverStats = BestiaryManager.getInstance().getServerStats();
		final String account = BestiaryManager.getInstance().getAccountName(charId);
		final List<BestiaryManager.Hunter> hunters = serverStats.hunters();
		final StringBuilder json = new StringBuilder(4 * 1024);
		json.append("{\"total\":").append(BestiaryCatalog.getInstance().getMonsters().size());
		json.append(",\"hunters\":").append(hunters.size());
		json.append(",\"time\":").append(serverStats.time());
		json.append(",\"top\":[");
		for (int i = 0; i < Math.min(BestiaryConfig.RANKING_SIZE, hunters.size()); i++)
		{
			appendHunter(json.append(i > 0 ? "," : ""), hunters.get(i), i + 1, account);
		}
		json.append(']');
		final int rank = serverStats.getRank(account);
		if (rank > BestiaryConfig.RANKING_SIZE)
		{
			appendHunter(json.append(",\"me\":"), hunters.get(rank - 1), rank, account);
		}
		json.append('}');
		server.sendJson(exchange, 200, json.toString());
	}

	/** A hunter of the ranking: its highest character, never its account. */
	private void appendHunter(StringBuilder json, BestiaryManager.Hunter hunter, int rank, String account)
	{
		json.append("{\"rank\":").append(rank);
		json.append(",\"name\":").append(SimpleJson.quote(hunter.name()));
		json.append(",\"level\":").append(hunter.level());
		json.append(",\"cls\":").append(SimpleJson.quote(className(hunter.classId())));
		json.append(",\"found\":").append(hunter.found());
		json.append(",\"kills\":").append(hunter.kills());
		json.append(",\"me\":").append(hunter.account().equalsIgnoreCase(account)).append('}');
	}

	/** Points the radar of the character (who must be in the world) at the nearest of a monster. */
	private void handleTrack(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		final int charId = server.authenticateCharacter(exchange);
		if (charId < 0)
		{
			return;
		}

		final int npcId = parseInt(server.readParams(exchange).get("id"));
		final Player player = World.getInstance().getPlayer(charId);
		final BestiaryTracker.Result result;
		if ((player == null) || !player.isOnline())
		{
			result = new BestiaryTracker.Result(false, "Log in with this character to track a monster.");
		}
		else
		{
			result = BestiaryTracker.track(player, npcId);
			player.sendMessage(result.message());
		}
		server.sendJson(exchange, 200, "{\"ok\":" + result.tracking() + ",\"msg\":" + SimpleJson.quote(result.message()) + "}");
	}

	// ---------------------------------------------------------------------------------------------
	// Farming for crafting goals
	// ---------------------------------------------------------------------------------------------

	/**
	 * Where to farm what each crafting goal of the character still misses. Spoils count when asked (spoil=1), by default when the character knows Spoil. Monsters and hunting grounds go by id: the page has them from the collection.
	 */
	private void handleFarming(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		final int charId = server.authenticateCharacter(exchange);
		if (charId < 0)
		{
			return;
		}

		final Player player = World.getInstance().getPlayer(charId);
		final boolean online = (player != null) && player.isOnline();
		final StringBuilder json = new StringBuilder(16 * 1024);
		json.append("{\"on\":").append(BestiaryFarming.isAvailable()).append(",\"online\":").append(online);
		if (!BestiaryFarming.isAvailable() || !online)
		{
			server.sendJson(exchange, 200, json.append('}').toString());
			return;
		}

		final String param = server.readParams(exchange).get("spoil");
		final boolean spoiler = BestiaryFarming.canSpoil(player);
		final boolean spoils = param != null ? "1".equals(param.trim()) : spoiler;
		json.append(",\"level\":").append(player.getLevel());
		json.append(",\"spoiler\":").append(spoiler);
		json.append(",\"spoil\":").append(spoils);
		json.append(",\"levelsAbove\":").append(BestiaryConfig.FARMING_LEVELS_ABOVE);
		json.append(",\"goals\":[");
		boolean first = true;
		for (CraftingGoal goal : CraftingGoalManager.getInstance().getGoals(player))
		{
			json.append(first ? "" : ",");
			appendGoalPlan(json, BestiaryFarming.getInstance().plan(player, goal, spoils));
			first = false;
		}
		json.append("]}");
		server.sendJson(exchange, 200, json.toString());
	}

	private static void appendGoalPlan(StringBuilder json, BestiaryFarming.GoalPlan plan)
	{
		final CraftingGoal goal = plan.goal();
		json.append("{\"recipe\":").append(goal.getRecipeListId());
		json.append(",\"item\":").append(goal.getProductId());
		json.append(",\"name\":").append(SimpleJson.quote(CraftingGoalManager.describe(goal)));
		json.append(",\"done\":").append(CraftingGoal.countCompleteComponents(plan.lines()));
		json.append(",\"total\":").append(CraftingGoal.countComponents(plan.lines()));
		json.append(",\"ready\":").append(CraftingGoal.isReady(plan.lines()));
		json.append(",\"farmable\":").append(plan.farmable());

		json.append(",\"res\":[");
		for (int i = 0; i < plan.resources().size(); i++)
		{
			final BestiaryFarming.Resource resource = plan.resources().get(i);
			final CraftingGoalLine line = resource.line();
			final ItemTemplate item = ItemData.getInstance().getTemplate(line.getItemId());
			json.append(i > 0 ? "," : "").append("{\"i\":").append(line.getItemId());
			if (item != null)
			{
				appendItem(json, item);
			}
			json.append(",\"have\":").append(line.getHave()).append(",\"need\":").append(line.getNeeded());
			json.append(",\"mat\":").append(line.isMaterial()).append(",\"cr\":").append(line.isCraftable());
			json.append(",\"u\":").append(resource.unfound());
			json.append(",\"rg\":[");
			for (int j = 0; j < resource.hints().size(); j++)
			{
				final BestiaryFarming.Hint hint = resource.hints().get(j);
				json.append(j > 0 ? "," : "").append("{\"n\":").append(SimpleJson.quote(hint.region())).append(",\"m\":").append(hint.monsters()).append(",\"lv\":").append(hint.level()).append(",\"h\":").append(hint.higher()).append('}');
			}
			json.append(']');
			json.append(",\"spots\":[");
			for (int j = 0; j < resource.spots().size(); j++)
			{
				final BestiaryFarming.Spot spot = resource.spots().get(j);
				json.append(j > 0 ? "," : "").append("{\"id\":").append(spot.template().getId());
				json.append(",\"pk\":").append(String.format(Locale.US, "%.4f", spot.perKill()));
				json.append(",\"k\":").append(spot.kills());
				json.append(",\"c\":").append(spot.counted());
				appendYield(json, "d", spot.drop());
				appendYield(json, "s", spot.spoil());
				json.append('}');
			}
			json.append("]}");
		}
		json.append(']');

		json.append(",\"grounds\":[");
		for (int i = 0; i < plan.grounds().size(); i++)
		{
			final BestiaryFarming.Ground ground = plan.grounds().get(i);
			json.append(i > 0 ? "," : "").append("{\"a\":").append(ground.area().getId());
			json.append(",\"s\":").append(String.format(Locale.US, "%.2f", ground.score()));
			json.append(",\"best\":[");
			boolean first = true;
			for (Map.Entry<Integer, BestiaryFarming.Spot> best : ground.best().entrySet())
			{
				json.append(first ? "" : ",").append("{\"r\":").append(best.getKey()).append(",\"id\":").append(best.getValue().template().getId()).append(",\"k\":").append(best.getValue().kills()).append('}');
				first = false;
			}
			json.append("]}");
		}
		json.append("]}");
	}

	/** A drop or spoil of a spot: {c: chance, mn, mx, b: 1 with the account's drop bonus}. */
	private static void appendYield(StringBuilder json, String key, BestiaryFarming.Yield yield)
	{
		if (yield == null)
		{
			return;
		}
		json.append(",\"").append(key).append("\":{\"c\":").append(String.format(Locale.US, "%.4f", yield.chance()));
		json.append(",\"mn\":").append(yield.min()).append(",\"mx\":").append(yield.max());
		json.append(yield.boosted() ? ",\"b\":1}" : "}");
	}

	/** The land never changes while the server runs: worked out once. Public, it only tells where monsters spawn. */
	private void handleMap(HttpExchange exchange) throws IOException
	{
		String json = _mapJson;
		if (json == null)
		{
			final StringBuilder sb = new StringBuilder(64 * 1024);
			sb.append("{\"cell\":").append(BestiaryCatalog.MAP_CELL).append(",\"land\":[");
			boolean first = true;
			for (int[] cell : BestiaryCatalog.getInstance().getLand())
			{
				sb.append(first ? "" : ",").append('[').append(cell[0]).append(',').append(cell[1]).append(',').append(cell[2]).append(']');
				first = false;
			}
			sb.append("]}");
			json = sb.toString();
			_mapJson = json;
		}
		PassiveTreeApiServer.getInstance().sendJson(exchange, 200, json);
	}

	/**
	 * The monster's look, for every tier: a monster not found yet shows as the silhouette of its portrait or model.
	 * @param json where it goes
	 * @param npcId the monster
	 */
	private static void appendLook(StringBuilder json, int npcId)
	{
		final BestiaryArt art = BestiaryArt.getInstance();
		final long portrait = art.getPortraitVersion(npcId);
		if (portrait > 0)
		{
			json.append(",\"img\":").append(portrait);
		}
		if (art.hasModel(npcId))
		{
			json.append(",\"m\":1");
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------------------------

	/** @return 0 a monster, 1 a raid boss, 2 a grand boss */
	private static int typeCode(NpcTemplate template)
	{
		return template.isType("GrandBoss") ? 2 : template.isType("RaidBoss") ? 1 : 0;
	}

	private static String className(int classId)
	{
		final ClassInfoHolder info = ClassListData.getInstance().getClass(classId);
		return info != null ? info.getClassName() : "";
	}

	private static String ints(int[] values)
	{
		final StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < values.length; i++)
		{
			sb.append(i > 0 ? "," : "").append(values[i]);
		}
		return sb.append(']').toString();
	}

	private static String ints(Set<Integer> values)
	{
		final StringBuilder sb = new StringBuilder("[");
		boolean first = true;
		for (Integer value : values)
		{
			sb.append(first ? "" : ",").append(value);
			first = false;
		}
		return sb.append(']').toString();
	}

	private static String strings(String[] values)
	{
		final StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < values.length; i++)
		{
			sb.append(i > 0 ? "," : "").append(SimpleJson.quote(values[i]));
		}
		return sb.append(']').toString();
	}

	private static int parseInt(String value)
	{
		if (value == null)
		{
			return 0;
		}
		try
		{
			return Integer.parseInt(value.trim());
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	public static BestiaryWeb getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final BestiaryWeb INSTANCE = new BestiaryWeb();
	}
}
