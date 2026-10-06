/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package custom.MasterBlacksmith;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.w3c.dom.Document;
import org.w3c.dom.Node;

import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.enums.AbsorbCrystalType;
import org.l2jmobius.gameserver.data.holders.LevelingSoulCrystalInfo;
import org.l2jmobius.gameserver.data.holders.SoulCrystal;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.LevelUpCrystalData;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.data.xml.MultisellData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.managers.GrandBossManager;
import org.l2jmobius.gameserver.managers.RaidBossSpawnManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.npc.RaidBossStatus;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.item.enums.BodyPart;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.item.type.CrystalType;
import org.l2jmobius.gameserver.model.multisell.Entry;
import org.l2jmobius.gameserver.model.multisell.Ingredient;
import org.l2jmobius.gameserver.model.multisell.ListContainer;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.model.script.QuestState;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.network.serverpackets.ExShowBaseAttributeCancelWindow;
import org.l2jmobius.gameserver.network.serverpackets.ExShowVariationCancelWindow;
import org.l2jmobius.gameserver.network.serverpackets.ExShowVariationMakeWindow;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * Master Blacksmith: every blacksmith service in one NPC (special abilities, dualswords, Mammon's weapon exchanges, seals, augments, attributes, crafting) plus a Soul Crystal / Special Ability wiki.
 * <ul>
 * <li>The wiki is built from the live data: the SA multisells (weapon + soul crystal + gemstones = weapon with SA), the SA text of each weapon (sa_effects.xml, from the retail item descriptions) and LevelUpCrystalData (which monsters raise which crystal stage, with what chance and how the
 * soul is shared in a party).</li>
 * <li>Searching "rsk haste", "focus", "dragon slayer" or initials like "sod" lists the matching weapons and abilities. A weapon page shows its three abilities, which crystal each needs and what it does; the leveling plan lists, step by step, the monsters and raid bosses that raise the crystal,
 * the chance, the party rule and where they are (with a radar mark).</li>
 * <li>Bestowing the chosen ability opens a one-entry multisell built from the real SA list, in inventory-only mode so the weapon keeps its enchant level like at any blacksmith.</li>
 * </ul>
 */
public class MasterBlacksmith extends Quest
{
	private static final int NPC_ID = 900010;
	private static final String BYPASS = "bypass -h Script MasterBlacksmith ";

	/** Runtime multisell ids (not backed by an XML file). */
	private static final int BESTOW_LIST_ID = 900010;
	private static final int DUALS_B_LIST_ID = 900011;
	/** The lists of the town blacksmiths and the Blacksmith of Mammon that add a special ability. */
	private static final int[] SA_LISTS =
	{
		1005, // C and B grade (town blacksmiths)
		311262510, // A grade (Blacksmith of Mammon)
		311262501, // S grade (Blacksmith of Mammon)
	};
	/** B-grade dualswords are split over two retail lists (different blacksmiths); the NPC offers both as one. */
	private static final int[] DUALS_B_LISTS =
	{
		1006,
		1007
	};

	// Soul crystal leveling (quest 350).
	private static final String Q350 = "Q00350_EnhanceYourWeapon";
	private static final int Q350_MIN_LEVEL = 40;
	private static final int[] Q350_NPCS =
	{
		30115, // Jurek
		30194, // Gideon
		30856, // Winonin
	};
	private static final int RED = 0;
	private static final int GREEN = 1;
	private static final int BLUE = 2;
	private static final String[] COLOR_NAMES =
	{
		"Red",
		"Green",
		"Blue"
	};
	private static final String[] COLOR_HTML =
	{
		"FF7766",
		"77DD77",
		"77AAFF"
	};

	// Window size and palette (same card style as the arena master and the hotzone teleporter).
	private static final int WINDOW_WIDTH = 470;
	private static final int WINDOW_HEIGHT = 760;
	private static final int CONTENT_WIDTH = 440;
	private static final String BG_BAR = "1A1A1A";
	private static final String BG_CARD = "111111";
	private static final String COLOR_VALUE = "E6C35C";
	private static final String COLOR_POSITIVE = "66CC66";
	private static final String COLOR_NEGATIVE = "FF6666";
	private static final String COLOR_MUTED = "A0A0A0";
	private static final String COLOR_HINT = "707070";
	private static final String COLOR_SA = "FFCC66";

	private static final int RESULTS_PER_PAGE = 8;
	private static final int SOURCES_PER_PAGE = 9;
	private static final int MIN_QUERY_LENGTH = 2;
	private static final int MAX_QUERY_LENGTH = 40;

	/** A service of the retail blacksmiths: a multisell, opened like the retail dialogs open it. */
	private static final class Service
	{
		final String label;
		final int listId;
		final boolean inventoryOnly;
		
		Service(String label, int listId, boolean inventoryOnly)
		{
			this.label = label;
			this.listId = listId;
			this.inventoryOnly = inventoryOnly;
		}
	}

	private static final Map<String, List<Service>> SERVICES = new LinkedHashMap<>();
	static
	{
		SERVICES.put("Special Abilities", List.of( //
			new Service("Bestow SA: C/B grade", 1005, true), //
			new Service("Bestow SA: A grade", 311262510, true), //
			new Service("Bestow SA: S grade", 311262501, true), //
			new Service("Remove an SA", 311262509, true)));
		SERVICES.put("Weapons", List.of( //
			new Service("Dualsword: D/C grade", 1001, false), //
			new Service("Dualsword: B grade", DUALS_B_LIST_ID, false), //
			new Service("Dualsword: A grade", 311262502, false), //
			new Service("Dualsword: S grade", 311262503, false), //
			new Service("Upgrade a D/C weapon", 311262511, true), //
			new Service("Swap D-B weapon type", 311262512, true), //
			new Service("Swap A weapon type", 311262519, true), //
			new Service("Craft ingredients", 1004, false)));
		SERVICES.put("Armor & Accessories", List.of( //
			new Service("Unseal B gloves/boots", 1002, true), //
			new Service("Reseal B gloves/boots", 1003, true), //
			new Service("Unseal A armor", 311262506, true), //
			new Service("Reseal A armor", 311262508, true), //
			new Service("Unseal A accessory", 311262507, true), //
			new Service("Unseal S armor", 311262504, true), //
			new Service("Unseal S accessory", 311262505, true), //
			new Service("Rare top to regular", 311262516, true), //
			new Service("Finish a Foundation item", 311262513, false)));
	}
	private static final Map<Integer, Service> SERVICE_BY_LIST = new HashMap<>();
	static
	{
		for (List<Service> services : SERVICES.values())
		{
			for (Service service : services)
			{
				SERVICE_BY_LIST.put(service.listId, service);
			}
		}
	}

	/** What each special ability does in general (the exact value of a weapon comes from sa_effects.xml). */
	private static final Map<String, String> SA_INFO = Map.ofEntries( //
		Map.entry("Acumen", "Casting Spd. +15%. The classic pick for mages."), //
		Map.entry("Anger", "More P. Atk., but Max HP -15%."), //
		Map.entry("Back Blow", "Higher Critical Rate when attacking from behind."), //
		Map.entry("Blessed Body", "20% chance to cast Blessed Body (Max HP buff) on the target of your magic skill."), //
		Map.entry("Cheap Shot", "Lower MP cost of skills (on bows: chance that a shot costs 1 MP)."), //
		Map.entry("Conversion", "Max MP +60%, but Max HP -40%."), //
		Map.entry("Critical Anger", "On a critical hit you lose a little HP and the hit gets a big P. Atk. bonus."), //
		Map.entry("Critical Bleed", "Chance to make the target bleed on a critical hit."), //
		Map.entry("Critical Damage", "Extra P. Atk. on critical hits."), //
		Map.entry("Critical Drain", "Absorbs some HP from the target on a critical hit."), //
		Map.entry("Critical Poison", "Chance to poison the target on a critical hit."), //
		Map.entry("Critical Slow", "Chance to slow the target on a critical hit."), //
		Map.entry("Critical Stun", "Chance to stun the target on a critical hit."), //
		Map.entry("Empower", "More M. Atk."), //
		Map.entry("Evasion", "More Evasion."), //
		Map.entry("Focus", "Higher Critical Rate. The common pick for fighters."), //
		Map.entry("Guidance", "More Accuracy."), //
		Map.entry("HP Drain", "Restores part of your melee damage as HP."), //
		Map.entry("HP Regeneration", "Faster HP regeneration."), //
		Map.entry("Haste", "Atk. Spd. +6-11%, always on."), //
		Map.entry("Health", "Max HP +25%."), //
		Map.entry("Light", "Weight limit +20%."), //
		Map.entry("M. Atk.", "More M. Atk., but magic skills cost 15% more MP."), //
		Map.entry("MP Regeneration", "Faster MP regeneration."), //
		Map.entry("Magic Chaos", "Chance to cast Curse Chaos on the target of your magic skill."), //
		Map.entry("Magic Damage", "30% chance to add extra magic damage to your magic skill."), //
		Map.entry("Magic Focus", "20% chance to cast Focus (Critical Rate buff) on the target of your magic skill."), //
		Map.entry("Magic Hold", "Chance to root (Dryad Root) the target of your magic skill."), //
		Map.entry("Magic Paralyze", "5% chance to paralyze the target of your magic skill."), //
		Map.entry("Magic Poison", "Chance to cast Curse: Poison on the target of your magic skill."), //
		Map.entry("Magic Regeneration", "30% chance to cast Regeneration (HP regen buff) on the target of your magic skill."), //
		Map.entry("Magic Shield", "50% chance to cast Mental Shield on the target of your magic skill."), //
		Map.entry("Magic Silence", "10% chance to silence the target of your magic skill."), //
		Map.entry("Magic Weakness", "Chance to cast Curse: Weakness on the target of your magic skill."), //
		Map.entry("Mana Up", "Max MP +30%."), //
		Map.entry("Mental Shield", "50% chance to cast Mental Shield on the target of your magic skill."), //
		Map.entry("Miser", "Chance to use fewer soulshots per shot (bows)."), //
		Map.entry("Quick Recovery", "Skill reuse delay -13% to -20%."), //
		Map.entry("Rsk. Evasion", "\"Risk\" Evasion: more Evasion while your HP is 60% or lower."), //
		Map.entry("Rsk. Focus", "\"Risk\" Focus: much higher Critical Rate while your HP is 60% or lower."), //
		Map.entry("Rsk. Haste", "\"Risk\" Haste: Atk. Spd. +9-13% while your HP is 60% or lower."), //
		Map.entry("Towering Blow", "Longer attack range (polearms)."), //
		Map.entry("Wide Blow", "Wider attack angle: hits more targets (polearms)."), //
		Map.entry("Wild Blow", "Wider attack angle: hits more targets (polearms)."));

	/** Where the monsters without a fixed world spawn are (epic bosses, instances). */
	private static final Map<Integer, String> PLACE_NOTES = new HashMap<>();
	static
	{
		PLACE_NOTES.put(29001, "Epic boss, Ant Nest");
		PLACE_NOTES.put(29006, "Epic boss, Cruma Tower");
		PLACE_NOTES.put(29014, "Epic boss, Sea of Spores");
		PLACE_NOTES.put(29020, "Epic boss, Tower of Insolence");
		for (int id : new int[]
		{
			29019,
			29066,
			29067,
			29068
		})
		{
			PLACE_NOTES.put(id, "Epic boss, Antharas' Lair");
		}
		PLACE_NOTES.put(29028, "Epic boss, Valakas' Lair");
		PLACE_NOTES.put(29118, "Epic boss, Steel Citadel");
		PLACE_NOTES.put(29047, "Epic boss, Last Imperial Tomb");
		PLACE_NOTES.put(29065, "Epic boss, Sailren's Lair (Primeval Isle)");
		for (int id : new int[]
		{
			29022,
			29176,
			29181,
			29026
		})
		{
			PLACE_NOTES.put(id, "Instance: Cavern of the Pirate Captain");
		}
		PLACE_NOTES.put(29150, "Instance: Heart of Infinity");
		PLACE_NOTES.put(25665, "Instance: Hall of Suffering");
		PLACE_NOTES.put(25666, "Instance: Hall of Suffering");
		PLACE_NOTES.put(29163, "Seed of Destruction");
		for (int id : new int[]
		{
			29179,
			29180,
			25699,
			25700
		})
		{
			PLACE_NOTES.put(id, "Instance: Ice Queen's Castle");
		}
		for (int id = 25690; id <= 25695; id++)
		{
			PLACE_NOTES.put(id, "Instance: Chamber of Delusion");
		}
		for (int id = 25609; id <= 25612; id++)
		{
			PLACE_NOTES.put(id, "Tower of Naia (Hellbound)");
		}
		PLACE_NOTES.put(25540, "Tower of Infinitum (Hellbound)");
		PLACE_NOTES.put(25542, "Tower of Infinitum (Hellbound)");
		PLACE_NOTES.put(25544, "Tully's Workshop (Hellbound)");
		PLACE_NOTES.put(25603, "Tully's Workshop (Hellbound)");
		PLACE_NOTES.put(25713, "Hellbound");
		PLACE_NOTES.put(25671, "Stakato Nest");
		for (int id = 25667; id <= 25670; id++)
		{
			PLACE_NOTES.put(id, "Stakato Nest");
		}
	}

	/** One way to add a special ability: a weapon, a soul crystal and a fee give the weapon with that ability. */
	private static final class Recipe
	{
		final int listId;
		final Entry entry;
		final ItemTemplate base;
		final ItemTemplate product;
		final String saName;
		final int crystalId;
		final int color;
		final int stage;
		final List<Ingredient> fees = new ArrayList<>();
		final String searchText;

		Recipe(int listId, Entry entry, ItemTemplate base, ItemTemplate product, String saName, int crystalId, int color, int stage)
		{
			this.listId = listId;
			this.entry = entry;
			this.base = base;
			this.product = product;
			this.saName = saName;
			this.crystalId = crystalId;
			this.color = color;
			this.stage = stage;
			searchText = " " + normalize(base.getName()) + " " + normalize(saName) + " " + initials(base.getName()) + " ";
		}

		CrystalType grade()
		{
			return base.getCrystalType();
		}
	}

	/** A monster that can raise a crystal stage. */
	private static final class Source
	{
		final NpcTemplate npc;
		final int chance;
		final AbsorbCrystalType type;
		final boolean skillNeeded;
		
		Source(NpcTemplate npc, int chance, AbsorbCrystalType type, boolean skillNeeded)
		{
			this.npc = npc;
			this.chance = chance;
			this.type = type;
			this.skillNeeded = skillNeeded;
		}
	}

	/** Where a monster is: a text and, when it has a fixed place in the world, a radar position. */
	private static final class Place
	{
		final String text;
		final Location location;
		
		Place(String text, Location location)
		{
			this.text = text;
			this.location = location;
		}
	}

	// Indexes, built once from the loaded data.
	private final List<Recipe> _recipes = new ArrayList<>();
	private final Map<Integer, Recipe> _byProduct = new HashMap<>();
	private final Map<Integer, List<Recipe>> _byBase = new LinkedHashMap<>();
	private final Map<String, List<Recipe>> _bySa = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
	private final List<String> _saNames = new ArrayList<>();
	/** Soul crystal item id -> {color, stage}. */
	private final Map<Integer, int[]> _crystals = new HashMap<>();
	/** Crystal stage -> monsters that raise it to the next stage. */
	private final Map<Integer, List<Source>> _sources = new HashMap<>();
	private int _maxStage;
	private final Map<Integer, String> _effects = new HashMap<>();
	private final Map<Integer, Place> _places = new ConcurrentHashMap<>();
	private volatile boolean _indexed;

	public MasterBlacksmith()
	{
		super(-1);
		addStartNpc(NPC_ID);
		addFirstTalkId(NPC_ID);
		addTalkId(NPC_ID);
	}

	@Override
	public String onFirstTalk(Npc npc, Player player)
	{
		showMain(player, npc, null);
		return null;
	}

	@Override
	public String onTalk(Npc npc, Player player)
	{
		showMain(player, npc, null);
		return null;
	}

	@Override
	public String onEvent(String event, Npc npc, Player player)
	{
		if ((npc == null) || (player == null) || (npc.getId() != NPC_ID))
		{
			return null;
		}

		ensureIndexed();
		final String[] args = event.trim().split("\\s+");
		try
		{
			switch (args[0])
			{
				case "main":
				{
					showMain(player, npc, null);
					break;
				}
				case "search":
				{
					showSearch(player, npc, event.substring("search".length()), 0);
					break;
				}
				case "sp": // sp <page> <query>
				{
					showSearch(player, npc, joinFrom(args, 2), Integer.parseInt(args[1]));
					break;
				}
				case "weapon":
				{
					showWeapon(player, npc, Integer.parseInt(args[1]));
					break;
				}
				case "recipe":
				{
					showRecipe(player, npc, Integer.parseInt(args[1]));
					break;
				}
				case "bestow":
				{
					bestow(player, npc, Integer.parseInt(args[1]));
					break;
				}
				case "saindex":
				{
					showSaIndex(player, npc);
					break;
				}
				case "sa": // sa <index> <page>
				{
					showSa(player, npc, Integer.parseInt(args[1]), Integer.parseInt(args[2]));
					break;
				}
				case "myweapons":
				{
					showMyWeapons(player, npc);
					break;
				}
				case "crystals":
				{
					showMyCrystals(player, npc);
					break;
				}
				case "withcrystal": // withcrystal <crystalId> <page>
				{
					showWithCrystal(player, npc, Integer.parseInt(args[1]), Integer.parseInt(args[2]));
					break;
				}
				case "plan": // plan <color> <target stage>
				{
					showPlan(player, npc, Integer.parseInt(args[1]), Integer.parseInt(args[2]));
					break;
				}
				case "step": // step <stage> <page> <color> <target>
				{
					showStep(player, npc, Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]));
					break;
				}
				case "rules":
				{
					showRules(player, npc);
					break;
				}
				case "getcrystal":
				{
					showGetCrystal(player, npc);
					break;
				}
				case "map":
				{
					markOnMap(player, Integer.parseInt(args[1]));
					break;
				}
				case "ms":
				{
					openService(player, npc, Integer.parseInt(args[1]));
					break;
				}
				case "augment":
				{
					player.sendPacket(ExShowVariationMakeWindow.STATIC_PACKET);
					break;
				}
				case "unaugment":
				{
					player.sendPacket(ExShowVariationCancelWindow.STATIC_PACKET);
					break;
				}
				case "attribute":
				{
					player.sendPacket(new ExShowBaseAttributeCancelWindow(player));
					break;
				}
			}
		}
		catch (NumberFormatException | ArrayIndexOutOfBoundsException e)
		{
			showMain(player, npc, null);
		}

		return null;
	}

	// ---------------------------------------------------------------------------------------------
	// Pages
	// ---------------------------------------------------------------------------------------------

	private void showMain(Player player, Npc npc, String notice)
	{
		final StringBuilder sb = new StringBuilder();
		if (notice != null)
		{
			sb.append(font(COLOR_NEGATIVE, notice)).append("<br>");
		}

		sb.append(searchBox("Find a Special Ability (SA) or a weapon:"));

		sb.append(sectionTitle("Soul Crystal &amp; SA Guide"));
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=2 cellspacing=0>");
		sb.append("<tr><td align=center>").append(button("My Weapons", "myweapons", 210)).append("</td><td align=center>").append(button("My Soul Crystals", "crystals", 210)).append("</td></tr>");
		sb.append("<tr><td align=center>").append(button("SA Effects A-Z", "saindex", 210)).append("</td><td align=center>").append(button("How Leveling Works", "rules", 210)).append("</td></tr>");
		sb.append("</table><br>");

		for (Map.Entry<String, List<Service>> section : SERVICES.entrySet())
		{
			sb.append(sectionTitle(section.getKey()));
			sb.append(buttonGrid(section.getValue().stream().map(s -> new String[]
			{
				s.label,
				"ms " + s.listId
			}).collect(Collectors.toList())));
		}

		sb.append(sectionTitle("Augments &amp; Attributes"));
		sb.append(buttonGrid(List.of(new String[]
		{
			"Augment an item",
			"augment"
		}, new String[]
		{
			"Remove an augment",
			"unaugment"
		}, new String[]
		{
			"Accessory Life Stones",
			"ms 1008"
		}, new String[]
		{
			"Remove an attribute",
			"attribute"
		})));
		sb.append(font(COLOR_HINT, "A-grade and S-grade work is paid in Ancient Adena, like at the Blacksmith of Mammon."));
		send(player, npc, "Every blacksmith service, plus a Soul Crystal and SA guide", sb.toString(), null);
	}

	private void showSearch(Player player, Npc npc, String rawQuery, int page)
	{
		String query = sanitizeQuery(rawQuery);
		if (query.length() < MIN_QUERY_LENGTH)
		{
			showMain(player, npc, "Type at least " + MIN_QUERY_LENGTH + " letters, e.g. \"rsk haste\" or \"focus\".");
			return;
		}

		// The name of an ability: show its page (what it does and every weapon that can get it).
		final String normalized = normalize(query);
		for (int i = 0; i < _saNames.size(); i++)
		{
			if (normalize(_saNames.get(i)).equals(normalized))
			{
				showSa(player, npc, i, page);
				return;
			}
		}

		final String[] words = normalized.split("\\s+");
		final List<Recipe> results = new ArrayList<>();
		for (Recipe recipe : _recipes)
		{
			if (matches(recipe, words))
			{
				results.add(recipe);
			}
		}

		if (results.isEmpty())
		{
			showMain(player, npc, "Nothing matches \"" + escape(query) + "\". Try a shorter word.");
			return;
		}

		// One weapon found: show its page right away.
		final Set<Integer> bases = new LinkedHashSet<>();
		results.forEach(r -> bases.add(r.base.getId()));
		if (bases.size() == 1)
		{
			showWeapon(player, npc, bases.iterator().next());
			return;
		}

		final StringBuilder sb = new StringBuilder();
		sb.append(searchBox("Search again:"));
		sb.append(sectionTitle(results.size() + " result(s) for \"" + escape(query) + "\""));
		appendRecipeRows(sb, results, page);
		sb.append(pager(page, results.size(), RESULTS_PER_PAGE, "sp %d " + query));
		send(player, npc, "Search", sb.toString(), "main");
	}

	private void showWeapon(Player player, Npc npc, int baseId)
	{
		final List<Recipe> recipes = _byBase.get(baseId);
		if (recipes == null)
		{
			showMain(player, npc, "That weapon can't get a special ability.");
			return;
		}

		final Recipe first = recipes.get(0);
		final StringBuilder sb = new StringBuilder();
		sb.append(itemHeader(first.base, gradeName(first.grade()) + "-grade " + weaponKind(first.base)));
		sb.append(ownedLine(player, baseId)).append("<br>");
		sb.append(sectionTitle("Choose one special ability"));
		for (Recipe recipe : recipes)
		{
			sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=3 cellspacing=0 bgcolor=").append(BG_CARD).append(">");
			sb.append("<tr><td width=290>").append(font(COLOR_SA, recipe.saName)).append("</td><td width=150 align=right>").append(crystalLabel(recipe.color, recipe.stage)).append("</td></tr>");
			sb.append("<tr><td colspan=2>").append(font(COLOR_MUTED, effectOf(recipe))).append("</td></tr>");
			sb.append("<tr><td>").append(font(COLOR_HINT, feeText(recipe))).append("</td><td align=right>").append(button("Details", "recipe " + recipe.product.getId(), 90)).append("</td></tr>");
			sb.append("</table><br>");
		}
		sb.append(font(COLOR_HINT, "A weapon holds one special ability. Its enchant level is kept."));
		send(player, npc, "Weapon", sb.toString(), "main");
	}

	private void showRecipe(Player player, Npc npc, int productId)
	{
		final Recipe recipe = _byProduct.get(productId);
		if (recipe == null)
		{
			showMain(player, npc, null);
			return;
		}

		final StringBuilder sb = new StringBuilder();
		sb.append(itemHeader(recipe.product, gradeName(recipe.grade()) + "-grade " + weaponKind(recipe.base)));
		sb.append(sectionTitle("Effect: " + recipe.saName));
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=4 cellspacing=0 bgcolor=").append(BG_CARD).append("><tr><td>");
		sb.append(font(COLOR_VALUE, effectOf(recipe)));
		final String info = SA_INFO.get(recipe.saName);
		if (info != null)
		{
			sb.append("<br1>").append(font(COLOR_HINT, info));
		}
		sb.append("</td></tr></table><br>");

		// What it takes, and what the player has.
		sb.append(sectionTitle("What you need"));
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=3 cellspacing=0>");
		boolean ready = true;

		final Item weapon = unequippedItem(player, recipe.base.getId());
		final boolean equipped = (weapon == null) && (player.getInventory().getItemByItemId(recipe.base.getId()) != null);
		ready &= weapon != null;
		sb.append(needRow(recipe.base.getName(), "1", weapon != null, weapon != null ? "+" + weapon.getEnchantLevel() + " in bag" : (equipped ? "unequip it first" : "you don't have it"), true));

		final long crystals = player.getInventory().getInventoryItemCount(recipe.crystalId, -1);
		ready &= crystals > 0;
		final int[] owned = ownedCrystal(player, recipe.color);
		final String crystalStatus;
		if (crystals > 0)
		{
			crystalStatus = "ready";
		}
		else if ((owned != null) && (owned[1] < recipe.stage))
		{
			crystalStatus = "yours is Stage " + owned[1] + ": " + (recipe.stage - owned[1]) + " to go";
		}
		else
		{
			crystalStatus = "you don't have it";
		}
		sb.append(needRow(COLOR_NAMES[recipe.color] + " Soul Crystal - Stage " + recipe.stage, "1", crystals > 0, crystalStatus, false));

		boolean shaded = true;
		for (Ingredient fee : recipe.fees)
		{
			final long have = player.getInventory().getInventoryItemCount(fee.getItemId(), -1);
			final boolean ok = have >= fee.getItemCount();
			ready &= ok;
			sb.append(needRow(fee.getTemplate().getName(), formatCount(fee.getItemCount()), ok, "you have " + formatCount(have), shaded));
			shaded = !shaded;
		}
		sb.append("</table><br>");

		if (ready)
		{
			sb.append(button("Bestow " + recipe.saName + " now", "bestow " + recipe.product.getId(), 220)).append("<br>");
		}
		else
		{
			sb.append(font(COLOR_HINT, "Bring everything above to bestow it here.")).append("<br>");
		}

		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=2 cellspacing=0><tr>");
		sb.append("<td align=center>").append(button("Level the crystal", "plan " + recipe.color + " " + recipe.stage, 210)).append("</td>");
		sb.append("<td align=center>").append(button("Other SAs of weapon", "weapon " + recipe.base.getId(), 210)).append("</td>");
		sb.append("</tr></table>");
		send(player, npc, "Special ability", sb.toString(), "weapon " + recipe.base.getId());
	}

	private void bestow(Player player, Npc npc, int productId)
	{
		final Recipe recipe = _byProduct.get(productId);
		if (recipe == null)
		{
			return;
		}

		if (unequippedItem(player, recipe.base.getId()) == null)
		{
			player.sendMessage("Put the " + recipe.base.getName() + " in your bag (unequipped) first.");
			showRecipe(player, npc, productId);
			return;
		}

		final ListContainer source = MultisellData.getInstance().getList(recipe.listId);
		final ListContainer list = new ListContainer(BESTOW_LIST_ID);
		list.allowNpc(NPC_ID);
		list.setApplyTaxes((source != null) && source.getApplyTaxes());
		list.setMaintainEnchantment((source == null) || source.getMaintainEnchantment());
		list.getEntries().add(copyEntry(recipe.entry, 1));
		MultisellData.getInstance().separateAndSend(list, player, npc, true);
	}

	private void showSaIndex(Player player, Npc npc)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append(searchBox("Find a Special Ability (SA) or a weapon:"));
		sb.append(sectionTitle("All special abilities"));
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=2 cellspacing=0>");
		for (int i = 0; i < _saNames.size(); i++)
		{
			if ((i % 3) == 0)
			{
				sb.append("<tr>");
			}
			final String name = _saNames.get(i);
			sb.append("<td width=147>").append(link(name, "sa " + i + " 0")).append(" ").append(font(COLOR_HINT, String.valueOf(_bySa.get(name).size()))).append("</td>");
			if ((i % 3) == 2)
			{
				sb.append("</tr>");
			}
		}
		if ((_saNames.size() % 3) != 0)
		{
			sb.append("</tr>");
		}
		sb.append("</table><br>");
		sb.append(font(COLOR_HINT, "The number is how many weapons can get that ability. The crystal color of an ability differs from weapon to weapon."));
		send(player, npc, "SA Effects A-Z", sb.toString(), "main");
	}

	private void showSa(Player player, Npc npc, int index, int page)
	{
		if ((index < 0) || (index >= _saNames.size()))
		{
			showSaIndex(player, npc);
			return;
		}

		final String name = _saNames.get(index);
		final List<Recipe> recipes = _bySa.get(name);
		final StringBuilder sb = new StringBuilder();
		sb.append(sectionTitle(name));
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=4 cellspacing=0 bgcolor=").append(BG_CARD).append("><tr><td>");
		sb.append(font(COLOR_VALUE, SA_INFO.getOrDefault(name, "")));
		final Set<String> kinds = new LinkedHashSet<>();
		recipes.forEach(r -> kinds.add(weaponKind(r.base)));
		sb.append("<br1>").append(font(COLOR_MUTED, "Weapons: " + String.join(", ", kinds)));
		sb.append("</td></tr></table><br>");
		sb.append(sectionTitle(recipes.size() + " weapon(s) with " + name));
		appendRecipeRows(sb, recipes, page);
		sb.append(pager(page, recipes.size(), RESULTS_PER_PAGE, "sa " + index + " %d"));
		send(player, npc, "Special ability", sb.toString(), "saindex");
	}

	private void showMyWeapons(Player player, Npc npc)
	{
		final StringBuilder sb = new StringBuilder();
		final Set<Integer> seen = new LinkedHashSet<>();
		final List<Item> canEnhance = new ArrayList<>();
		final List<Item> enhanced = new ArrayList<>();
		for (Item item : player.getInventory().getItems())
		{
			if (!item.isWeapon() || !seen.add(item.getId()))
			{
				continue;
			}
			if (_byBase.containsKey(item.getId()))
			{
				canEnhance.add(item);
			}
			else if (_byProduct.containsKey(item.getId()))
			{
				enhanced.add(item);
			}
		}

		sb.append(sectionTitle("Weapons you can enhance"));
		if (canEnhance.isEmpty())
		{
			sb.append(font(COLOR_HINT, "None of your weapons can get a special ability. C-grade and better weapons can.")).append("<br>");
		}
		for (Item item : canEnhance)
		{
			final List<Recipe> recipes = _byBase.get(item.getId());
			final StringBuilder options = new StringBuilder();
			for (Recipe recipe : recipes)
			{
				options.append(options.length() > 0 ? ", " : "").append("<font color=\"").append(COLOR_HTML[recipe.color]).append("\">").append(recipe.saName).append("</font>");
			}
			sb.append(itemRow(item.getTemplate(), (item.getEnchantLevel() > 0 ? "+" + item.getEnchantLevel() + " " : "") + item.getName() + (item.isEquipped() ? font(COLOR_HINT, " (equipped)") : ""), options.toString() + font(COLOR_HINT, " - Stage " + recipes.get(0).stage), "weapon " + item.getId()));
		}

		sb.append(sectionTitle("Weapons that already have an SA"));
		if (enhanced.isEmpty())
		{
			sb.append(font(COLOR_HINT, "None.")).append("<br>");
		}
		for (Item item : enhanced)
		{
			final Recipe recipe = _byProduct.get(item.getId());
			sb.append(itemRow(item.getTemplate(), item.getName(), font(COLOR_MUTED, effectOf(recipe)), "recipe " + item.getId()));
		}
		sb.append("<br>").append(button("Remove an SA", "ms 311262509", 160));
		send(player, npc, "My weapons", sb.toString(), "main");
	}

	private void showMyCrystals(Player player, Npc npc)
	{
		final StringBuilder sb = new StringBuilder();
		final List<Item> crystals = new ArrayList<>();
		for (Item item : player.getInventory().getItems())
		{
			if (_crystals.containsKey(item.getId()))
			{
				crystals.add(item);
			}
		}

		sb.append(questStatus(player));
		long total = 0;
		for (Item item : crystals)
		{
			total += item.getCount();
		}
		if (total > 1)
		{
			sb.append(font(COLOR_NEGATIVE, "You carry " + total + " soul crystals: none of them can absorb a soul. Keep only the one you are leveling and put the others in the warehouse.")).append("<br><br>");
		}

		sb.append(sectionTitle("Your soul crystals"));
		if (crystals.isEmpty())
		{
			sb.append(font(COLOR_HINT, "You have no soul crystal.")).append("<br>").append(button("Get a free crystal", "getcrystal", 200)).append("<br>");
		}
		for (Item item : crystals)
		{
			final int[] info = _crystals.get(item.getId());
			final int color = info[0];
			final int stage = info[1];
			sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=3 cellspacing=0 bgcolor=").append(BG_CARD).append(">");
			sb.append("<tr><td width=36><img src=\"").append(item.getTemplate().getIcon()).append("\" width=32 height=32></td><td width=404>").append(crystalLabel(color, stage)).append(item.getCount() > 1 ? font(COLOR_HINT, " x" + item.getCount()) : "").append("<br1>");
			final List<Recipe> usable = recipesWithCrystal(item.getId());
			sb.append(font(COLOR_MUTED, usable.isEmpty() ? "No weapon uses this stage. Keep leveling." : "Gives an SA to " + usable.size() + " weapon(s)."));
			sb.append("</td></tr><tr><td colspan=2><table width=430 cellpadding=0 cellspacing=0><tr>");
			if (_sources.containsKey(stage))
			{
				sb.append("<td align=center>").append(button("Next stage: where", "step " + stage + " 0 " + color + " " + (stage + 1), 140)).append("</td>");
			}
			sb.append("<td align=center>").append(button("Leveling plan", "plan " + color + " " + nextMilestone(stage), 140)).append("</td>");
			if (!usable.isEmpty())
			{
				sb.append("<td align=center>").append(button("What it makes", "withcrystal " + item.getId() + " 0", 140)).append("</td>");
			}
			sb.append("</tr></table></td></tr></table><br>");
		}

		sb.append(sectionTitle("Stages by grade"));
		sb.append(font(COLOR_MUTED, "C grade: Stage 5-9.  B grade: Stage 10.  A grade: Stage 11-12.  S grade: Stage 13.  S80/S84: Stage 14-16."));
		sb.append("<br>").append(font(COLOR_HINT, "Each weapon page names its exact stage."));
		send(player, npc, "My soul crystals", sb.toString(), "main");
	}

	private void showWithCrystal(Player player, Npc npc, int crystalId, int page)
	{
		final int[] info = _crystals.get(crystalId);
		if (info == null)
		{
			showMyCrystals(player, npc);
			return;
		}

		final List<Recipe> recipes = recipesWithCrystal(crystalId);
		// Weapons the player owns first.
		recipes.sort(Comparator.comparing((Recipe r) -> player.getInventory().getItemByItemId(r.base.getId()) == null));
		final StringBuilder sb = new StringBuilder();
		sb.append(sectionTitle("With a " + COLOR_NAMES[info[0]] + " crystal Stage " + info[1]));
		appendRecipeRows(sb, recipes, page);
		sb.append(pager(page, recipes.size(), RESULTS_PER_PAGE, "withcrystal " + crystalId + " %d"));
		send(player, npc, "What the crystal makes", sb.toString(), "crystals");
	}

	private void showPlan(Player player, Npc npc, int color, int target)
	{
		if ((color < RED) || (color > BLUE) || (target < 1) || (target > _maxStage))
		{
			showMain(player, npc, null);
			return;
		}

		final int[] owned = ownedCrystal(player, color);
		final int start = (owned != null) && (owned[1] < target) ? owned[1] : 0;
		final StringBuilder sb = new StringBuilder();
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=4 cellspacing=0 bgcolor=").append(BG_CARD).append("><tr><td align=center>");
		sb.append(font(COLOR_MUTED, "Goal: ")).append(crystalLabel(color, target)).append("<br1>");
		if ((owned != null) && (owned[1] >= target))
		{
			sb.append(font(COLOR_POSITIVE, "Your crystal is already Stage " + owned[1] + "."));
		}
		else if (owned != null)
		{
			sb.append(font(COLOR_VALUE, "You are at Stage " + owned[1] + ": " + (target - owned[1]) + " step(s) left."));
		}
		else
		{
			sb.append(font(COLOR_VALUE, "Start from a free Stage 0 crystal: " + target + " step(s)."));
		}
		sb.append("</td></tr></table><br>");
		sb.append(questStatus(player));

		sb.append(sectionTitle("Steps (click one for the monster list)"));
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=3 cellspacing=0>");
		sb.append("<tr><td width=120>").append(font(COLOR_HINT, "Step")).append("</td><td width=140>").append(font(COLOR_HINT, "Who")).append("</td><td width=80>").append(font(COLOR_HINT, "Best chance")).append("</td><td width=100></td></tr>");
		boolean shaded = false;
		for (int stage = start; stage < target; stage++)
		{
			final List<Source> sources = sourcesFor(stage, player.getLevel());
			final boolean current = (owned != null) && (owned[1] == stage);
			final String bg = current ? " bgcolor=\"2A2410\"" : (shaded ? " bgcolor=\"" + BG_CARD + "\"" : "");
			sb.append("<tr><td").append(bg).append(">").append(font(current ? COLOR_VALUE : "FFFFFF", "Stage " + stage + " &gt; " + (stage + 1))).append("</td>");
			if (sources.isEmpty())
			{
				sb.append("<td").append(bg).append(" colspan=3>").append(font(COLOR_NEGATIVE, "no monster raises this stage")).append("</td></tr>");
			}
			else
			{
				final boolean bosses = sources.stream().allMatch(s -> s.type != AbsorbCrystalType.LAST_HIT);
				final int best = sources.stream().mapToInt(s -> s.chance).max().orElse(0);
				sb.append("<td").append(bg).append(">").append(font(COLOR_MUTED, sources.size() + (bosses ? " bosses" : " monsters"))).append("</td>");
				sb.append("<td").append(bg).append(">").append(font(COLOR_VALUE, best + "%")).append("</td>");
				sb.append("<td").append(bg).append(" align=right>").append(button(current ? "Go here" : "List", "step " + stage + " 0 " + color + " " + target, 80)).append("</td></tr>");
			}
			shaded = !shaded;
		}
		sb.append("</table><br>");
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=2 cellspacing=0><tr>");
		sb.append("<td align=center>").append(button("How absorbing works", "rules", 210)).append("</td>");
		sb.append("<td align=center>").append(button("Get a free crystal", "getcrystal", 210)).append("</td>");
		sb.append("</tr></table>");
		send(player, npc, "Leveling plan", sb.toString(), "crystals");
	}

	private void showStep(Player player, Npc npc, int stage, int page, int color, int target)
	{
		final List<Source> sources = sourcesFor(stage, player.getLevel());
		final StringBuilder sb = new StringBuilder();
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=4 cellspacing=0 bgcolor=").append(BG_CARD).append("><tr><td align=center>");
		sb.append(crystalLabel(color, stage)).append(font(COLOR_MUTED, "  to  ")).append(crystalLabel(color, stage + 1)).append("<br1>");
		if (!sources.isEmpty() && sources.stream().allMatch(s -> s.type == AbsorbCrystalType.LAST_HIT))
		{
			sb.append(font(COLOR_HINT, "Bring it to half HP, use the crystal on it, then land the last hit."));
		}
		else
		{
			sb.append(font(COLOR_HINT, "Bosses: kill it with your party, no need to use the crystal."));
		}
		sb.append("</td></tr></table><br>");

		if (sources.isEmpty())
		{
			sb.append(font(COLOR_NEGATIVE, "No monster raises this stage.")).append("<br>");
		}
		else
		{
			sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=2 cellspacing=0>");
			sb.append("<tr><td width=170>").append(font(COLOR_HINT, "Monster (level)")).append("</td><td width=50>").append(font(COLOR_HINT, "Chance")).append("</td><td width=170>").append(font(COLOR_HINT, "Who gets it / where")).append("</td><td width=50></td></tr>");
			final int from = Math.min(page * SOURCES_PER_PAGE, Math.max(0, sources.size() - 1));
			final int to = Math.min(from + SOURCES_PER_PAGE, sources.size());
			boolean shaded = false;
			for (Source source : sources.subList(from, to))
			{
				final NpcTemplate template = source.npc;
				final Place place = placeOf(template.getId());
				final String bg = shaded ? " bgcolor=\"" + BG_CARD + "\"" : "";
				sb.append("<tr><td").append(bg).append(">").append(template.getName()).append(" ").append(font(levelColor(template.getLevel(), player.getLevel()), "(" + template.getLevel() + ")"));
				sb.append(bossTag(template)).append("</td>");
				sb.append("<td").append(bg).append(">").append(font(COLOR_VALUE, source.chance + "%")).append("<br1>").append(font(COLOR_HINT, "~" + averageKills(source.chance) + " kills")).append("</td>");
				sb.append("<td").append(bg).append(">").append(font(COLOR_MUTED, absorbText(source))).append("<br1>").append(font(COLOR_HINT, place.text)).append("</td>");
				sb.append("<td").append(bg).append(">");
				if (place.location != null)
				{
					sb.append(button("Map", "map " + template.getId(), 45));
				}
				sb.append("</td></tr>");
				shaded = !shaded;
			}
			sb.append("</table>");
			sb.append(pager(page, sources.size(), SOURCES_PER_PAGE, "step " + stage + " %d " + color + " " + target));
			sb.append(font(COLOR_HINT, "Levels: ")).append(font(COLOR_POSITIVE, "near yours")).append(font(COLOR_HINT, ", ")).append(font(COLOR_VALUE, "higher")).append(font(COLOR_HINT, ", ")).append(font(COLOR_MUTED, "lower")).append(font(COLOR_HINT, ". \"Map\" marks it on your radar."));
			if (PlayerConfig.SOUL_CRYSTAL_CHANCE_MULTIPLIER != 1)
			{
				sb.append("<br1>").append(font(COLOR_HINT, "Chances include this server's x" + formatRate(PlayerConfig.SOUL_CRYSTAL_CHANCE_MULTIPLIER) + " soul crystal rate."));
			}
		}
		send(player, npc, "Stage " + stage + " to " + (stage + 1), sb.toString(), "plan " + color + " " + target);
	}

	private void showRules(Player player, Npc npc)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append(questStatus(player));
		sb.append(sectionTitle("1. Get a crystal"));
		sb.append(card("Take the quest <font color=\"LEVEL\">Enhance Your Weapon</font> (level " + Q350_MIN_LEVEL + "+) from Jurek, Gideon or Winonin and pick a free Stage 0 crystal: Red, Green or Blue. The color decides which special abilities you can make: look the weapon up here first."));
		sb.append(sectionTitle("2. Carry only one"));
		sb.append(card("Keep exactly <font color=\"LEVEL\">one</font> soul crystal in your inventory. With two or more, none of them absorbs anything. The quest must stay active."));
		sb.append(sectionTitle("3. Stages 0-10: monsters"));
		sb.append(card("Bring the monster to <font color=\"LEVEL\">50% HP or less</font>, use the crystal on it (double-click it), then land the <font color=\"LEVEL\">last hit</font> yourself. Using it too early, or someone else killing it, wastes the try."));
		sb.append(sectionTitle("4. Stage 10 and up: bosses"));
		sb.append(card("Raid and epic bosses don't need the crystal used. When the boss dies, the soul goes to:<br1>" + font(COLOR_VALUE, "Whole party") + ": every member in range with a crystal rolls.<br1>" + font(COLOR_VALUE, "One random member") + ": one party member is picked at random (even one without a crystal).<br1>" + font(COLOR_VALUE, "Last hit") + ": only the killer."));
		sb.append(sectionTitle("5. What the messages mean"));
		sb.append(card(font(COLOR_POSITIVE, "Succeeded in absorbing") + ": the crystal is now one stage higher.<br1>" + font(COLOR_MUTED, "Not able to absorb") + ": no luck, try again. The crystal never breaks here.<br1>" + font(COLOR_NEGATIVE, "Refusing to absorb") + ": this monster can't raise your stage, go to the next step's list."));
		sb.append("<br>").append(button("Get a free crystal", "getcrystal", 200));
		send(player, npc, "How leveling works", sb.toString(), "main");
	}

	private void showGetCrystal(Player player, Npc npc)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append(questStatus(player));
		sb.append(sectionTitle("Quest: Enhance Your Weapon (level " + Q350_MIN_LEVEL + "+)"));
		sb.append(card("Any of these masters starts the quest and gives a free Stage 0 crystal of the color you choose. You can come back for another color."));
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=3 cellspacing=0>");
		for (int npcId : Q350_NPCS)
		{
			final NpcTemplate template = NpcData.getInstance().getTemplate(npcId);
			if (template == null)
			{
				continue;
			}
			final Place place = placeOf(npcId);
			sb.append("<tr><td width=150>").append(template.getName()).append("</td><td width=210>").append(font(COLOR_MUTED, place.text)).append("</td><td width=80>");
			if (place.location != null)
			{
				sb.append(button("Map", "map " + npcId, 60));
			}
			sb.append("</td></tr>");
		}
		sb.append("</table><br>");
		sb.append(sectionTitle("Which color?"));
		sb.append(card("The same ability can need a different color on another weapon (Haste is Red on one sword and Blue on another). Search your weapon on the main page to see its three choices."));
		sb.append(searchBox("Search a weapon or ability:"));
		send(player, npc, "Get a soul crystal", sb.toString(), "rules");
	}

	private void markOnMap(Player player, int npcId)
	{
		final Place place = placeOf(npcId);
		final NpcTemplate template = NpcData.getInstance().getTemplate(npcId);
		if ((place.location == null) || (template == null))
		{
			return;
		}

		final Location loc = place.location;
		player.getRadar().addMarker(loc.getX(), loc.getY(), loc.getZ());
		player.sendMessage(template.getName() + " is marked on your radar (" + place.text + ").");
	}

	private void openService(Player player, Npc npc, int listId)
	{
		if (listId == DUALS_B_LIST_ID)
		{
			final ListContainer list = new ListContainer(DUALS_B_LIST_ID);
			list.allowNpc(NPC_ID);
			list.setApplyTaxes(true);
			for (int sourceId : DUALS_B_LISTS)
			{
				final ListContainer source = MultisellData.getInstance().getList(sourceId);
				if (source != null)
				{
					for (Entry entry : source.getEntries())
					{
						list.getEntries().add(copyEntry(entry, list.getEntries().size() + 1));
					}
				}
			}
			MultisellData.getInstance().separateAndSend(list, player, npc, false);
			return;
		}

		// Only the lists of the menu (and the life stone list of the augment section).
		final Service service = SERVICE_BY_LIST.get(listId);
		if (service != null)
		{
			MultisellData.getInstance().separateAndSend(listId, player, npc, service.inventoryOnly);
		}
		else if (listId == 1008)
		{
			MultisellData.getInstance().separateAndSend(listId, player, npc, false);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Data
	// ---------------------------------------------------------------------------------------------

	private synchronized void ensureIndexed()
	{
		if (_indexed)
		{
			return;
		}

		new EffectReader(_effects).load();

		// Soul crystals: color and stage of every crystal id.
		for (SoulCrystal crystal : LevelUpCrystalData.getInstance().getSoulCrystals().values())
		{
			registerCrystal(crystal.getItemId(), crystal.getLevel());
			registerCrystal(crystal.getLeveledItemId(), crystal.getLevel() + 1);
		}

		// Who raises which stage.
		for (Map.Entry<Integer, Map<Integer, LevelingSoulCrystalInfo>> npcEntry : LevelUpCrystalData.getInstance().getNpcsSoulInfo().entrySet())
		{
			final NpcTemplate template = NpcData.getInstance().getTemplate(npcEntry.getKey());
			if (template == null)
			{
				continue;
			}
			for (Map.Entry<Integer, LevelingSoulCrystalInfo> level : npcEntry.getValue().entrySet())
			{
				final LevelingSoulCrystalInfo info = level.getValue();
				_sources.computeIfAbsent(level.getKey(), k -> new ArrayList<>()).add(new Source(template, info.getChance(), info.getAbsorbCrystalType(), info.isSkillNeeded()));
			}
		}

		// Special ability recipes (a few weapons are listed twice).
		final Set<Integer> seen = new HashSet<>();
		for (int listId : SA_LISTS)
		{
			final ListContainer list = MultisellData.getInstance().getList(listId);
			if (list == null)
			{
				LOGGER.warning(getClass().getSimpleName() + ": missing multisell " + listId + ".");
				continue;
			}
			for (Entry entry : list.getEntries())
			{
				final Recipe recipe = toRecipe(listId, entry);
				if ((recipe != null) && seen.add(recipe.product.getId()))
				{
					_recipes.add(recipe);
				}
			}
		}
		_recipes.sort(Comparator.comparingInt((Recipe r) -> r.grade().ordinal()).thenComparing(r -> r.base.getName()).thenComparingInt(r -> r.color));
		for (Recipe recipe : _recipes)
		{
			_byProduct.put(recipe.product.getId(), recipe);
			_byBase.computeIfAbsent(recipe.base.getId(), k -> new ArrayList<>()).add(recipe);
			_bySa.computeIfAbsent(recipe.saName, k -> new ArrayList<>()).add(recipe);
		}
		_saNames.addAll(_bySa.keySet());

		LOGGER.info(getClass().getSimpleName() + ": " + _recipes.size() + " special abilities on " + _byBase.size() + " weapons, " + _sources.values().stream().mapToInt(List::size).sum() + " crystal leveling entries.");
		_indexed = true;
	}

	private void registerCrystal(int itemId, int stage)
	{
		final ItemTemplate template = ItemData.getInstance().getTemplate(itemId);
		if (template == null)
		{
			return;
		}

		final String name = template.getName();
		final int color = name.startsWith("Red") ? RED : name.startsWith("Green") ? GREEN : name.startsWith("Blue") ? BLUE : -1;
		if (color < 0)
		{
			return;
		}

		_crystals.put(itemId, new int[]
		{
			color,
			stage
		});
		_maxStage = Math.max(_maxStage, stage);
	}

	private Recipe toRecipe(int listId, Entry entry)
	{
		if (entry.getProducts().isEmpty())
		{
			return null;
		}

		final ItemTemplate product = entry.getProducts().get(0).getTemplate();
		ItemTemplate base = null;
		int crystalId = 0;
		final List<Ingredient> fees = new ArrayList<>();
		for (Ingredient ingredient : entry.getIngredients())
		{
			final ItemTemplate template = ingredient.getTemplate();
			if (template == null)
			{
				continue;
			}
			if (_crystals.containsKey(ingredient.getItemId()))
			{
				crystalId = ingredient.getItemId();
			}
			else if ((base == null) && template.isWeapon())
			{
				base = template;
			}
			else
			{
				fees.add(ingredient);
			}
		}

		if ((product == null) || (base == null) || (crystalId == 0))
		{
			return null;
		}

		final String prefix = base.getName() + " - ";
		final String saName = product.getName().startsWith(prefix) ? product.getName().substring(prefix.length()) : product.getName().substring(product.getName().lastIndexOf(" - ") + 3);
		final int[] crystal = _crystals.get(crystalId);
		final Recipe recipe = new Recipe(listId, entry, base, product, saName, crystalId, crystal[0], crystal[1]);
		recipe.fees.addAll(fees);
		return recipe;
	}

	/** The monsters that raise a stage, the same monster listed once, the best and closest to the player's level first. */
	private List<Source> sourcesFor(int stage, int level)
	{
		final List<Source> all = _sources.getOrDefault(stage, Collections.emptyList());
		final Map<String, Source> unique = new LinkedHashMap<>();
		for (Source source : all)
		{
			final String key = source.npc.getName() + "|" + source.npc.getLevel() + "|" + source.chance + "|" + source.type;
			final Source known = unique.get(key);
			// Keep the copy that has a place in the world.
			if ((known == null) || ((placeOf(known.npc.getId()).location == null) && (placeOf(source.npc.getId()).location != null)))
			{
				unique.put(key, source);
			}
		}

		final List<Source> result = new ArrayList<>(unique.values());
		result.sort(Comparator.comparingInt((Source s) -> -s.chance) //
			.thenComparing(s -> isUnknownPlace(s.npc.getId())) //
			.thenComparingInt(s -> Math.abs(s.npc.getLevel() - level)) //
			.thenComparing(s -> s.npc.getName()));
		return result;
	}

	private boolean isUnknownPlace(int npcId)
	{
		return (placeOf(npcId).location == null) && !PLACE_NOTES.containsKey(npcId);
	}

	private Place placeOf(int npcId)
	{
		return _places.computeIfAbsent(npcId, id ->
		{
			Location location = null;
			for (Spawn spawn : SpawnTable.getInstance().getSpawns(id))
			{
				if ((spawn.getInstanceId() == 0) && ((spawn.getX() != 0) || (spawn.getY() != 0)))
				{
					location = new Location(spawn.getX(), spawn.getY(), spawn.getZ());
					break;
				}
			}
			if (location == null)
			{
				final StatSet boss = GrandBossManager.getInstance().getStatSet(id);
				if ((boss != null) && ((boss.getInt("loc_x", 0) != 0) || (boss.getInt("loc_y", 0) != 0)))
				{
					location = new Location(boss.getInt("loc_x", 0), boss.getInt("loc_y", 0), boss.getInt("loc_z", 0));
				}
			}

			final String note = PLACE_NOTES.get(id);
			if (note != null)
			{
				return new Place(note, location);
			}
			if (location != null)
			{
				return new Place("near " + townName(MapRegionData.getInstance().getClosestTownName(location.getX(), location.getY())), location);
			}
			return new Place("no fixed spawn", null);
		});
	}

	private List<Recipe> recipesWithCrystal(int crystalId)
	{
		final List<Recipe> result = new ArrayList<>();
		for (Recipe recipe : _recipes)
		{
			if (recipe.crystalId == crystalId)
			{
				result.add(recipe);
			}
		}
		return result;
	}

	/** @return {color, stage} of the highest crystal of that color the player carries, or null */
	private int[] ownedCrystal(Player player, int color)
	{
		int[] best = null;
		for (Item item : player.getInventory().getItems())
		{
			final int[] info = _crystals.get(item.getId());
			if ((info != null) && (info[0] == color) && ((best == null) || (info[1] > best[1])))
			{
				best = info;
			}
		}
		return best;
	}

	private static Item unequippedItem(Player player, int itemId)
	{
		for (Item item : player.getInventory().getAllItemsByItemId(itemId, false))
		{
			if (!item.isEquipped())
			{
				return item;
			}
		}
		return null;
	}

	/** The next stage a weapon asks for, to plan towards. */
	private int nextMilestone(int stage)
	{
		final Set<Integer> stages = new java.util.TreeSet<>();
		_recipes.forEach(r -> stages.add(r.stage));
		for (int milestone : stages)
		{
			if (milestone > stage)
			{
				return milestone;
			}
		}
		return Math.min(stage + 1, _maxStage);
	}

	private static Entry copyEntry(Entry entry, int entryId)
	{
		final Entry copy = new Entry(entryId);
		for (Ingredient product : entry.getProducts())
		{
			copy.addProduct(product.getCopy());
		}
		for (Ingredient ingredient : entry.getIngredients())
		{
			copy.addIngredient(ingredient.getCopy());
		}
		return copy;
	}

	private String effectOf(Recipe recipe)
	{
		final String effect = _effects.get(recipe.product.getId());
		return effect != null ? effect : SA_INFO.getOrDefault(recipe.saName, "");
	}

	/** Reads sa_effects.xml: the special ability text of each weapon. */
	private static class EffectReader implements IXmlReader
	{
		private final Map<Integer, String> _target;

		EffectReader(Map<Integer, String> target)
		{
			_target = target;
		}

		@Override
		public void load()
		{
			parseDatapackFile("data/scripts/custom/MasterBlacksmith/sa_effects.xml");
		}

		@Override
		public boolean isValidating()
		{
			return false;
		}

		@Override
		public void parseDocument(Document document, File file)
		{
			for (Node list = document.getFirstChild(); list != null; list = list.getNextSibling())
			{
				if (!"list".equals(list.getNodeName()))
				{
					continue;
				}
				for (Node node = list.getFirstChild(); node != null; node = node.getNextSibling())
				{
					if ("effect".equals(node.getNodeName()))
					{
						_target.put(parseInteger(node.getAttributes(), "itemId"), parseString(node.getAttributes(), "text"));
					}
				}
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Search
	// ---------------------------------------------------------------------------------------------

	private static boolean matches(Recipe recipe, String[] words)
	{
		for (String word : words)
		{
			if (!recipe.searchText.contains(" " + word))
			{
				return false;
			}
		}
		return true;
	}

	/** Lower case, letters and digits only, so "Rsk. Haste" matches "rsk haste" and "Dasparion's" matches "dasparions". */
	private static String normalize(String text)
	{
		return text.toLowerCase(Locale.ROOT).replace("'", "").replaceAll("[^a-z0-9]+", " ").trim();
	}

	/** "Sword of Damascus" gives "sod". */
	private static String initials(String name)
	{
		final StringBuilder sb = new StringBuilder();
		for (String word : normalize(name).split(" "))
		{
			if (!word.isEmpty())
			{
				sb.append(word.charAt(0));
			}
		}
		return sb.length() > 1 ? sb.toString() : "";
	}

	private static String sanitizeQuery(String raw)
	{
		String query = raw == null ? "" : raw.trim();
		// An empty edit box can arrive as the literal variable name on some clients.
		if (query.equals("$q"))
		{
			query = "";
		}
		query = query.replaceAll("[^A-Za-z0-9 .'-]", " ").replaceAll("\\s+", " ").trim();
		return query.length() > MAX_QUERY_LENGTH ? query.substring(0, MAX_QUERY_LENGTH) : query;
	}

	private static String joinFrom(String[] args, int from)
	{
		final StringBuilder sb = new StringBuilder();
		for (int i = from; i < args.length; i++)
		{
			sb.append(i > from ? " " : "").append(args[i]);
		}
		return sb.toString();
	}

	// ---------------------------------------------------------------------------------------------
	// Html
	// ---------------------------------------------------------------------------------------------

	private static void send(Player player, Npc npc, String subtitle, String body, String backEvent)
	{
		final StringBuilder sb = new StringBuilder(body.length() + 1200);
		sb.append("<html><body><center>");
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=0 cellspacing=0 bgcolor=\"").append(BG_BAR).append("\">");
		sb.append("<tr><td height=6></td></tr>");
		sb.append("<tr><td align=center><font color=\"LEVEL\" name=\"hs12\">Master Blacksmith</font></td></tr>");
		sb.append("<tr><td align=center>").append(font(COLOR_MUTED, subtitle)).append("</td></tr>");
		sb.append("<tr><td height=6></td></tr></table><br>");
		sb.append(body);
		if (backEvent != null)
		{
			sb.append("<br><table width=").append(CONTENT_WIDTH).append(" cellpadding=2 cellspacing=0><tr>");
			sb.append("<td align=center>").append(button("Back", backEvent, 120)).append("</td>");
			sb.append("<td align=center>").append(button("Main menu", "main", 120)).append("</td>");
			sb.append("</tr></table>");
		}
		sb.append("</center></body></html>");

		final NpcHtmlMessage html = new NpcHtmlMessage(npc.getObjectId(), sb.toString());
		html.setWindowSize(WINDOW_WIDTH, WINDOW_HEIGHT);
		player.sendPacket(html);
	}

	private static String searchBox(String label)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=3 cellspacing=0 bgcolor=\"").append(BG_CARD).append("\">");
		sb.append("<tr><td colspan=2>").append(font(COLOR_MUTED, label)).append("</td></tr>");
		sb.append("<tr><td width=320><edit var=\"q\" width=310 height=15></td>");
		sb.append("<td width=120>").append(button("Search", "search $q", 110)).append("</td></tr>");
		sb.append("<tr><td colspan=2>").append(font(COLOR_HINT, "e.g. rsk haste, focus, dragon slayer, sod (initials)")).append("</td></tr>");
		sb.append("</table><br>");
		return sb.toString();
	}

	private static String sectionTitle(String title)
	{
		return "<table width=" + CONTENT_WIDTH + " cellpadding=3 cellspacing=0 bgcolor=\"" + BG_BAR + "\"><tr><td><font color=\"LEVEL\">" + title + "</font></td></tr></table>";
	}

	private static String card(String text)
	{
		return "<table width=" + CONTENT_WIDTH + " cellpadding=4 cellspacing=0 bgcolor=\"" + BG_CARD + "\"><tr><td>" + font("CCCCCC", text) + "</td></tr></table><br>";
	}

	private static String button(String label, String event, int width)
	{
		return "<button value=\"" + label + "\" action=\"" + BYPASS + event + "\" width=" + width + " height=24 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">";
	}

	private static String buttonGrid(List<String[]> buttons)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=1 cellspacing=0>");
		for (int i = 0; i < buttons.size(); i += 2)
		{
			sb.append("<tr><td align=center width=220>").append(button(buttons.get(i)[0], buttons.get(i)[1], 210)).append("</td><td align=center width=220>");
			if ((i + 1) < buttons.size())
			{
				sb.append(button(buttons.get(i + 1)[0], buttons.get(i + 1)[1], 210));
			}
			sb.append("</td></tr>");
		}
		sb.append("</table><br>");
		return sb.toString();
	}

	private static String link(String label, String event)
	{
		return "<a action=\"" + BYPASS + event + "\">" + label + "</a>";
	}

	private static String font(String color, String text)
	{
		return "<font color=\"" + color + "\">" + text + "</font>";
	}

	private static String pager(int page, int total, int perPage, String eventPattern)
	{
		final int pages = (total + perPage - 1) / perPage;
		if (pages <= 1)
		{
			return "";
		}

		final StringBuilder sb = new StringBuilder();
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=2 cellspacing=0><tr>");
		sb.append("<td width=150 align=left>").append(page > 0 ? button("Previous", String.format(eventPattern, page - 1), 100) : "").append("</td>");
		sb.append("<td width=140 align=center>").append(font(COLOR_MUTED, "Page " + (page + 1) + " / " + pages)).append("</td>");
		sb.append("<td width=150 align=right>").append((page + 1) < pages ? button("Next", String.format(eventPattern, page + 1), 100) : "").append("</td>");
		sb.append("</tr></table>");
		return sb.toString();
	}

	private void appendRecipeRows(StringBuilder sb, List<Recipe> recipes, int page)
	{
		final int from = Math.min(Math.max(page, 0) * RESULTS_PER_PAGE, Math.max(0, recipes.size() - 1));
		final int to = Math.min(from + RESULTS_PER_PAGE, recipes.size());
		for (Recipe recipe : recipes.subList(from, to))
		{
			final String title = recipe.base.getName() + " - " + font(COLOR_SA, recipe.saName);
			final String detail = crystalLabel(recipe.color, recipe.stage) + font(COLOR_HINT, "  " + gradeName(recipe.grade()) + "  " + weaponKind(recipe.base));
			sb.append(itemRow(recipe.product, title, detail, "recipe " + recipe.product.getId()));
		}
	}

	private static String itemRow(ItemTemplate template, String title, String detail, String event)
	{
		return "<table width=" + CONTENT_WIDTH + " cellpadding=2 cellspacing=0 bgcolor=\"" + BG_CARD + "\"><tr><td width=36><img src=\"" + template.getIcon() + "\" width=32 height=32></td><td width=334>" + title + "<br1>" + detail + "</td><td width=70 align=right>" + button("View", event, 60) + "</td></tr></table><img src=\"L2UI.SquareBlank\" width=1 height=3>";
	}

	private static String itemHeader(ItemTemplate template, String detail)
	{
		return "<table width=" + CONTENT_WIDTH + " cellpadding=3 cellspacing=0 bgcolor=\"" + BG_CARD + "\"><tr><td width=36><img src=\"" + template.getIcon() + "\" width=32 height=32></td><td width=404><font color=\"" + COLOR_VALUE + "\">" + template.getName() + "</font><br1>" + font(COLOR_MUTED, detail) + "</td></tr></table><br>";
	}

	private static String needRow(String what, String count, boolean ok, String status, boolean shaded)
	{
		final String bg = shaded ? " bgcolor=\"" + BG_CARD + "\"" : "";
		return "<tr><td width=20" + bg + ">" + font(ok ? COLOR_POSITIVE : COLOR_NEGATIVE, ok ? "OK" : "X") + "</td><td width=230" + bg + ">" + what + "</td><td width=60" + bg + " align=right>" + font(COLOR_VALUE, count) + "</td><td width=130" + bg + " align=right>" + font(ok ? COLOR_MUTED : COLOR_NEGATIVE, status) + "</td></tr>";
	}

	private static String ownedLine(Player player, int itemId)
	{
		final Item item = player.getInventory().getItemByItemId(itemId);
		if (item == null)
		{
			return font(COLOR_HINT, "You don't have this weapon.");
		}
		return font(COLOR_POSITIVE, "You have it" + (item.getEnchantLevel() > 0 ? " (+" + item.getEnchantLevel() + ")" : "") + (item.isEquipped() ? ", equipped: unequip it before the blacksmith works on it." : "."));
	}

	private String questStatus(Player player)
	{
		final QuestState qs = player.getQuestState(Q350);
		if ((qs != null) && qs.isStarted())
		{
			return font(COLOR_POSITIVE, "Quest Enhance Your Weapon: active.") + "<br><br>";
		}
		return font(COLOR_NEGATIVE, "Quest Enhance Your Weapon: not taken. Crystals absorb nothing without it.") + " " + link("Where?", "getcrystal") + "<br><br>";
	}

	private static String crystalLabel(int color, int stage)
	{
		return "<font color=\"" + COLOR_HTML[color] + "\">" + COLOR_NAMES[color] + " Stage " + stage + "</font>";
	}

	private static String feeText(Recipe recipe)
	{
		final StringBuilder sb = new StringBuilder();
		for (Ingredient fee : recipe.fees)
		{
			sb.append(sb.length() > 0 ? " + " : "Fee: ").append(formatCount(fee.getItemCount())).append(" ").append(fee.getTemplate().getName());
		}
		return sb.toString();
	}

	private static String absorbText(Source source)
	{
		switch (source.type)
		{
			case FULL_PARTY:
			{
				return "Whole party";
			}
			case PARTY_ONE_RANDOM:
			{
				return "One random member";
			}
			case PARTY_RANDOM:
			{
				return "Random members";
			}
			default:
			{
				return source.skillNeeded ? "Use crystal, last hit" : "Last hit";
			}
		}
	}

	private static String bossTag(NpcTemplate template)
	{
		if (template.isType("GrandBoss"))
		{
			return font("CC88FF", " Epic");
		}
		if (template.isType("RaidBoss"))
		{
			final RaidBossStatus status = RaidBossSpawnManager.getInstance().getRaidBossStatusId(template.getId());
			if (status == RaidBossStatus.ALIVE)
			{
				return font(COLOR_POSITIVE, " RB alive");
			}
			if (status == RaidBossStatus.DEAD)
			{
				return font(COLOR_NEGATIVE, " RB dead");
			}
			return font(COLOR_VALUE, " RB");
		}
		return "";
	}

	private static String levelColor(int npcLevel, int playerLevel)
	{
		final int diff = npcLevel - playerLevel;
		if (diff > 5)
		{
			return COLOR_VALUE;
		}
		return diff < -5 ? COLOR_MUTED : COLOR_POSITIVE;
	}

	private static int averageKills(int chance)
	{
		return chance <= 0 ? 0 : (int) Math.ceil(100.0 / chance);
	}

	private static String gradeName(CrystalType grade)
	{
		return grade == null ? "" : grade.name();
	}

	private static String weaponKind(ItemTemplate template)
	{
		if (!(template instanceof Weapon))
		{
			return "weapon";
		}
		
		final Weapon weapon = (Weapon) template;
		final boolean twoHanded = template.getBodyPart() == BodyPart.LR_HAND;
		final String kind;
		switch (weapon.getItemType())
		{
			case SWORD:
			{
				kind = twoHanded ? "two-handed sword" : "sword";
				break;
			}
			case BLUNT:
			{
				kind = twoHanded ? "two-handed blunt" : "blunt";
				break;
			}
			case DAGGER:
			{
				kind = "dagger";
				break;
			}
			case BOW:
			{
				kind = "bow";
				break;
			}
			case POLE:
			{
				kind = "polearm";
				break;
			}
			case DUAL:
			{
				kind = "dualsword";
				break;
			}
			case FIST:
			case DUALFIST:
			{
				kind = "fist";
				break;
			}
			case RAPIER:
			{
				kind = "rapier";
				break;
			}
			case ANCIENTSWORD:
			{
				kind = "ancient sword";
				break;
			}
			case CROSSBOW:
			{
				kind = "crossbow";
				break;
			}
			case DUALDAGGER:
			{
				kind = "dual daggers";
				break;
			}
			default:
			{
				kind = "weapon";
				break;
			}
		}
		return template.isMagicWeapon() ? "magic " + kind : kind;
	}

	private static String townName(String region)
	{
		switch (region)
		{
			case "Darkelven Town":
			{
				return "Dark Elven Village";
			}
			case "Elven Town":
			{
				return "Elven Village";
			}
			case "Dwarven Town":
			{
				return "Dwarven Village";
			}
			case "Orc Town":
			{
				return "Orc Village";
			}
			case "Giran Habor":
			{
				return "Giran Harbor";
			}
			default:
			{
				return region.replace(" Castle Town", "").replace(" Town", "");
			}
		}
	}

	private static String formatRate(double rate)
	{
		return rate == Math.rint(rate) ? String.valueOf((long) rate) : String.valueOf(rate);
	}

	private static String formatCount(long count)
	{
		return String.format(Locale.US, "%,d", count);
	}

	private static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	public static void main(String[] args)
	{
		new MasterBlacksmith();
	}
}
