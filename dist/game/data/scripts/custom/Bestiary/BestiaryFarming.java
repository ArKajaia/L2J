package custom.Bestiary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.config.custom.BestiaryConfig;
import org.l2jmobius.gameserver.config.custom.CraftingGoalsConfig;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.managers.BestiaryManager;
import org.l2jmobius.gameserver.managers.CraftingGoalManager;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.DropGroupHolder;
import org.l2jmobius.gameserver.model.actor.holders.npc.DropHolder;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.craftinggoal.CraftingGoal;
import org.l2jmobius.gameserver.model.craftinggoal.CraftingGoalLine;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.itemcontainer.Inventory;
import org.l2jmobius.gameserver.util.MathUtil;

/**
 * Where to farm what a crafting goal still misses (see {@link CraftingGoalManager}), from what the bestiary knows: shown on the Crafting Goals tab of the web page ({@link BestiaryWeb}) and on the "Where to Farm" page of the Crafting Goals tracker.
 * <ul>
 * <li><b>Sources</b> - every item the monsters of the collection drop or spoil, with its chance and amounts at the server's rates, as their profile pages show them. Raid and grand bosses, and monsters that don't respawn, are no farming ground and are left out.</li>
 * <li><b>Spots</b> - for each resource of the goal still missing, the monsters the account found (Discovered or above) up to BestiaryFarmingLevelsAbove levels above the character, best first by how many of it one kill gives: the chance with the account's drop bonus on that monster (not on spoils), times the lower chance of monsters far below the character (the drop formula's level gap), times the average amount. Spoils count for a character who knows Spoil; for the others a resource that only comes from spoil still lists its spoil spots, marked as needing a spoiler. Monsters not found yet are only counted.</li>
 * <li><b>Where to look</b> - a resource no monster the account found gives names the regions (never the monsters) where the ones not found yet live, when the page shows where they live (BestiaryShowUndiscoveredAreas): the best ones in the level range first, or else the nearest above it.</li>
 * <li><b>Hunting grounds</b> - each ground counts, for every resource, its best spot against the best one anywhere (1 when it is the best place for it), and the grounds that add up to the most come first.</li>
 * </ul>
 * @author Claude
 */
public class BestiaryFarming
{
	/** The spots a resource keeps, best first. */
	public static final int SPOTS = 3;
	/** The hunting grounds a goal keeps, best first. */
	public static final int GROUNDS = 5;
	/** The regions a resource no found monster gives names. */
	public static final int HINTS = 2;
	private static final int SPOIL_SKILL_ID = 254;

	/**
	 * One way an item comes out of a monster, at the server's rates.
	 * @param npcId the monster
	 * @param spoil {@code true} for its spoil, {@code false} for its drops
	 * @param chance the chance (percent, may be above 100 with the rates), a group's chance included
	 * @param min the least amount
	 * @param max the most
	 * @param bonus {@code true} if the bestiary's drop bonus raises the chance (items of the drops, not spoils)
	 */
	private static class Source
	{
		private final int npcId;
		private final boolean spoil;
		private final double chance;
		private final long min;
		private final long max;
		private final boolean bonus;

		public Source(int npcId, boolean spoil, double chance, long min, long max, boolean bonus)
		{
			this.npcId = npcId;
			this.spoil = spoil;
			this.chance = chance;
			this.min = min;
			this.max = max;
			this.bonus = bonus;
		}

		public int npcId()
		{
			return npcId;
		}

		public boolean spoil()
		{
			return spoil;
		}

		public double chance()
		{
			return chance;
		}

		public long min()
		{
			return min;
		}

		public long max()
		{
			return max;
		}

		public boolean bonus()
		{
			return bonus;
		}
	}

	/**
	 * How an item comes out of a monster for the account, as shown.
	 * @param chance the chance (percent, at most 100), with the account's drop bonus
	 * @param min the least amount
	 * @param max the most
	 * @param boosted {@code true} if the account's drop bonus raised it
	 */
	public static class Yield
	{
		private final double chance;
		private final long min;
		private final long max;
		private final boolean boosted;

		public Yield(double chance, long min, long max, boolean boosted)
		{
			this.chance = chance;
			this.min = min;
			this.max = max;
			this.boosted = boosted;
		}

		public double chance()
		{
			return chance;
		}

		public long min()
		{
			return min;
		}

		public long max()
		{
			return max;
		}

		public boolean boosted()
		{
			return boosted;
		}
	}

	/**
	 * A monster to farm a resource on.
	 * @param template the monster
	 * @param kind where it lives
	 * @param drop its best drop of the item, or {@code null}
	 * @param spoil its best spoil of the item, or {@code null}
	 * @param perKill how many of the item one kill gives on average
	 * @param kills the kills to get what is missing
	 * @param counted {@code false} for a spoil spot of a character who can't spoil
	 */
	public static class Spot
	{
		private final NpcTemplate template;
		private final BestiaryCatalog.Kind kind;
		private final Yield drop;
		private final Yield spoil;
		private final double perKill;
		private final long kills;
		private final boolean counted;

		public Spot(NpcTemplate template, BestiaryCatalog.Kind kind, Yield drop, Yield spoil, double perKill, long kills, boolean counted)
		{
			this.template = template;
			this.kind = kind;
			this.drop = drop;
			this.spoil = spoil;
			this.perKill = perKill;
			this.kills = kills;
			this.counted = counted;
		}

		public NpcTemplate template()
		{
			return template;
		}

		public BestiaryCatalog.Kind kind()
		{
			return kind;
		}

		public Yield drop()
		{
			return drop;
		}

		public Yield spoil()
		{
			return spoil;
		}

		public double perKill()
		{
			return perKill;
		}

		public long kills()
		{
			return kills;
		}

		public boolean counted()
		{
			return counted;
		}
	}

	/**
	 * A region to look for a resource in, when no monster the account found gives it.
	 * @param region the region (or the hunting ground, for the lands outside the regions)
	 * @param monsters how many monsters there give it
	 * @param level the lowest level of them
	 * @param higher {@code true} if they are all above the level range
	 */
	public static class Hint
	{
		private final String region;
		private final int monsters;
		private final int level;
		private final boolean higher;

		public Hint(String region, int monsters, int level, boolean higher)
		{
			this.region = region;
			this.monsters = monsters;
			this.level = level;
			this.higher = higher;
		}

		public String region()
		{
			return region;
		}

		public int monsters()
		{
			return monsters;
		}

		public int level()
		{
			return level;
		}

		public boolean higher()
		{
			return higher;
		}
	}

	/**
	 * A resource of the goal still missing.
	 * @param line the resource
	 * @param missing how many are still missing
	 * @param spots the best monsters for it, at most {@link #SPOTS}
	 * @param unfound how many monsters the account has not found yet give it (in the level range)
	 * @param hints where to look for it when no spot gives it, at most {@link #HINTS}
	 */
	public static class Resource
	{
		private final CraftingGoalLine line;
		private final long missing;
		private final List<Spot> spots;
		private final int unfound;
		private final List<Hint> hints;

		public Resource(CraftingGoalLine line, long missing, List<Spot> spots, int unfound, List<Hint> hints)
		{
			this.line = line;
			this.missing = missing;
			this.spots = spots;
			this.unfound = unfound;
			this.hints = hints;
		}

		public CraftingGoalLine line()
		{
			return line;
		}

		public long missing()
		{
			return missing;
		}

		public List<Spot> spots()
		{
			return spots;
		}

		public int unfound()
		{
			return unfound;
		}

		public List<Hint> hints()
		{
			return hints;
		}
		/** @return {@code true} if a monster can be farmed for it by the character */
		public boolean isFarmable()
		{
			return !spots.isEmpty() && spots.get(0).counted();
		}
	}

	/**
	 * A hunting ground for the goal.
	 * @param area the hunting ground
	 * @param score each resource it gives, counted for its best spot here against the best one anywhere
	 * @param best its best spot of each resource it gives, by the resource's index in the plan
	 */
	public static class Ground
	{
		private final BestiaryCatalog.Area area;
		private final double score;
		private final Map<Integer, Spot> best;

		public Ground(BestiaryCatalog.Area area, double score, Map<Integer, Spot> best)
		{
			this.area = area;
			this.score = score;
			this.best = best;
		}

		public BestiaryCatalog.Area area()
		{
			return area;
		}

		public double score()
		{
			return score;
		}

		public Map<Integer, Spot> best()
		{
			return best;
		}
	}

	/**
	 * Where to farm a goal.
	 * @param goal the goal
	 * @param lines all its resources, as the tracker counts them
	 * @param resources the ones still missing
	 * @param grounds the best hunting grounds, at most {@link #GROUNDS}
	 * @param level the character's level
	 * @param spoils {@code true} if spoils were counted
	 * @param farmable how many of the resources a found monster gives
	 */
	public static class GoalPlan
	{
		private final CraftingGoal goal;
		private final List<CraftingGoalLine> lines;
		private final List<Resource> resources;
		private final List<Ground> grounds;
		private final int level;
		private final boolean spoils;
		private final int farmable;

		public GoalPlan(CraftingGoal goal, List<CraftingGoalLine> lines, List<Resource> resources, List<Ground> grounds, int level, boolean spoils, int farmable)
		{
			this.goal = goal;
			this.lines = lines;
			this.resources = resources;
			this.grounds = grounds;
			this.level = level;
			this.spoils = spoils;
			this.farmable = farmable;
		}

		public CraftingGoal goal()
		{
			return goal;
		}

		public List<CraftingGoalLine> lines()
		{
			return lines;
		}

		public List<Resource> resources()
		{
			return resources;
		}

		public List<Ground> grounds()
		{
			return grounds;
		}

		public int level()
		{
			return level;
		}

		public boolean spoils()
		{
			return spoils;
		}

		public int farmable()
		{
			return farmable;
		}
	}

	/** A monster that gives a resource, before the spots are picked. */
	private static class Candidate
	{
		private final NpcTemplate template;
		private final BestiaryCatalog.Kind kind;
		private final Yield drop;
		private final Yield spoil;
		private final double dropPerKill;
		private final double spoilPerKill;

		public Candidate(NpcTemplate template, BestiaryCatalog.Kind kind, Yield drop, Yield spoil, double dropPerKill, double spoilPerKill)
		{
			this.template = template;
			this.kind = kind;
			this.drop = drop;
			this.spoil = spoil;
			this.dropPerKill = dropPerKill;
			this.spoilPerKill = spoilPerKill;
		}

		public NpcTemplate template()
		{
			return template;
		}

		public BestiaryCatalog.Kind kind()
		{
			return kind;
		}

		public Yield drop()
		{
			return drop;
		}

		public Yield spoil()
		{
			return spoil;
		}

		public double dropPerKill()
		{
			return dropPerKill;
		}

		public double spoilPerKill()
		{
			return spoilPerKill;
		}
		/** @return how many of it one kill gives the character, with the spoils when they count or when nothing else gives it */
		double perKill(boolean spoils)
		{
			final double perKill = dropPerKill + (spoils ? spoilPerKill : 0);
			return perKill > 0 ? perKill : spoilPerKill;
		}
	}

	/** The spots of a resource, and where to look for it without them. */
	private static class Search
	{
		private final List<Spot> spots;
		private final int unfound;
		private final List<Hint> hints;

		public Search(List<Spot> spots, int unfound, List<Hint> hints)
		{
			this.spots = spots;
			this.unfound = unfound;
			this.hints = hints;
		}

		public List<Spot> spots()
		{
			return spots;
		}

		public int unfound()
		{
			return unfound;
		}

		public List<Hint> hints()
		{
			return hints;
		}
	}

	/** The sources of each item, by item id. */
	private final Map<Integer, List<Source>> _sources = new HashMap<>();

	private BestiaryFarming()
	{
		for (BestiaryCatalog.Kind kind : BestiaryCatalog.getInstance().getMonsters().values())
		{
			final NpcTemplate template = kind.getTemplate();
			if (BestiaryManager.isBoss(template) || (kind.getRespawnMin() <= 0))
			{
				continue;
			}

			if (template.getDropGroups() != null)
			{
				for (DropGroupHolder group : template.getDropGroups())
				{
					for (DropHolder drop : group.getDropList())
					{
						addSource(template, drop, drop.getChance() * (group.getChance() / 100), false);
					}
				}
			}
			if (template.getDropList() != null)
			{
				for (DropHolder drop : template.getDropList())
				{
					addSource(template, drop, drop.getChance(), false);
				}
			}
			if (template.getSpoilList() != null)
			{
				for (DropHolder drop : template.getSpoilList())
				{
					addSource(template, drop, drop.getChance(), true);
				}
			}
		}
	}

	private void addSource(NpcTemplate template, DropHolder drop, double baseChance, boolean spoil)
	{
		final ItemTemplate item = ItemData.getInstance().getTemplate(drop.getItemId());
		if ((item == null) || (item.getId() == Inventory.ADENA_ID))
		{
			return;
		}

		final double[] rates = BestiaryWeb.rates(drop, item, false);
		final long min = Math.max(1, Math.round(drop.getMin() * rates[1]));
		final long max = Math.max(min, Math.round(drop.getMax() * rates[1]));
		_sources.computeIfAbsent(item.getId(), id -> new ArrayList<>()).add(new Source(template.getId(), spoil, baseChance * rates[0], min, max, !spoil && !item.hasExImmediateEffect()));
	}

	/**
	 * @return {@code true} if the bestiary shows where to farm the crafting goals
	 */
	public static boolean isAvailable()
	{
		return BestiaryConfig.ENABLED && BestiaryConfig.FARMING_ENABLED && CraftingGoalsConfig.ENABLED;
	}

	/**
	 * @param player a player
	 * @return {@code true} if the character knows Spoil, so spoils count for it
	 */
	public static boolean canSpoil(Player player)
	{
		return player.getKnownSkill(SPOIL_SKILL_ID) != null;
	}

	/**
	 * @param monsterLevel the monster's level
	 * @param level the character's level
	 * @return how much of an item's chance is left for a character that much above the monster (1 up to DropItemMinLevelDifference), as the drop formula has it
	 */
	public static double levelFactor(int monsterLevel, int level)
	{
		return MathUtil.scaleToRange(monsterLevel - level, -RatesConfig.DROP_ITEM_MAX_LEVEL_DIFFERENCE, -RatesConfig.DROP_ITEM_MIN_LEVEL_DIFFERENCE, RatesConfig.DROP_ITEM_MIN_LEVEL_GAP_CHANCE, 100d) / 100;
	}

	/**
	 * Works out where a player should farm what a goal still misses.
	 * @param player the player (online: the goal's counts are their inventory's)
	 * @param goal the goal
	 * @param spoils {@code true} to count spoils
	 * @return the plan
	 */
	public GoalPlan plan(Player player, CraftingGoal goal, boolean spoils)
	{
		final List<CraftingGoalLine> lines = CraftingGoalManager.getInstance().computeLines(player, goal);
		final Map<Integer, BestiaryManager.Entry> entries = BestiaryManager.getInstance().getEntries(player.getObjectId());
		final int level = player.getLevel();

		final List<Resource> resources = new ArrayList<>();
		final List<List<Spot>> allSpots = new ArrayList<>();
		int farmable = 0;
		for (CraftingGoalLine line : lines)
		{
			if (line.isComplete())
			{
				continue;
			}

			final long missing = line.getNeeded() - line.getHave();
			final Search search = search(line.getItemId(), missing, entries, level, spoils);
			final List<Spot> spots = search.spots();
			final Resource resource = new Resource(line, missing, Collections.unmodifiableList(spots.subList(0, Math.min(SPOTS, spots.size()))), search.unfound(), search.hints());
			resources.add(resource);
			allSpots.add(spots);
			farmable += resource.isFarmable() ? 1 : 0;
		}
		return new GoalPlan(goal, lines, Collections.unmodifiableList(resources), grounds(allSpots, level), level, spoils, farmable);
	}

	/**
	 * @return every spot of the item for the character, best first (the ones that count, or else the spoil ones of a character who can't spoil), how many monsters not found yet give it, and without spots, where to look for it
	 */
	private Search search(int itemId, long missing, Map<Integer, BestiaryManager.Entry> entries, int level, boolean spoils)
	{
		final List<Source> sources = _sources.get(itemId);
		if (sources == null)
		{
			return new Search(new ArrayList<>(), 0, Collections.emptyList());
		}

		// The sources of each monster: an item can be in two groups, and in the drops and the spoil.
		final Map<Integer, List<Source>> byMonster = new LinkedHashMap<>();
		for (Source source : sources)
		{
			byMonster.computeIfAbsent(source.npcId(), id -> new ArrayList<>(2)).add(source);
		}

		final BestiaryCatalog catalog = BestiaryCatalog.getInstance();
		final List<Candidate> candidates = new ArrayList<>();
		final List<Candidate> unfound = new ArrayList<>();
		final List<Candidate> higher = new ArrayList<>();
		for (Map.Entry<Integer, List<Source>> monster : byMonster.entrySet())
		{
			final BestiaryCatalog.Kind kind = catalog.getMonsters().get(monster.getKey());
			if (kind == null)
			{
				continue;
			}

			final NpcTemplate template = kind.getTemplate();
			final BestiaryManager.Entry entry = entries.get(template.getId());
			final int tier = entry != null ? BestiaryManager.getTier(template, entry.kills()) : 0;

			final double factor = levelFactor(template.getLevel(), level);
			final int dropBonus = BestiaryManager.getDropBonus(template, tier);
			Yield drop = null;
			Yield spoil = null;
			double dropPerKill = 0;
			double spoilPerKill = 0;
			for (Source source : monster.getValue())
			{
				final boolean boosted = source.bonus() && (dropBonus > 0);
				final double chance = Math.min(100, source.chance() * (boosted ? 1 + (dropBonus / 100.0) : 1));
				final double perKill = (chance / 100) * factor * ((source.min() + source.max()) / 2.0);
				final Yield yield = new Yield(chance, source.min(), source.max(), boosted);
				if (source.spoil())
				{
					spoilPerKill += perKill;
					spoil = (spoil == null) || (chance > spoil.chance()) ? yield : spoil;
				}
				else
				{
					dropPerKill += perKill;
					drop = (drop == null) || (chance > drop.chance()) ? yield : drop;
				}
			}
			final Candidate candidate = new Candidate(template, kind, drop, spoil, dropPerKill, spoilPerKill);
			if (template.getLevel() > (level + BestiaryConfig.FARMING_LEVELS_ABOVE))
			{
				higher.add(candidate);
			}
			else if (tier == 0)
			{
				unfound.add(candidate);
			}
			else
			{
				candidates.add(candidate);
			}
		}

		// What counts for the character; with nothing, the spoil spots for a spoiler to come along.
		final List<Spot> spots = new ArrayList<>();
		for (Candidate candidate : candidates)
		{
			final double perKill = candidate.dropPerKill() + (spoils ? candidate.spoilPerKill() : 0);
			if (perKill > 0)
			{
				spots.add(spot(candidate, perKill, missing, true));
			}
		}
		if (spots.isEmpty() && !spoils)
		{
			for (Candidate candidate : candidates)
			{
				if (candidate.spoilPerKill() > 0)
				{
					spots.add(spot(candidate, candidate.spoilPerKill(), missing, false));
				}
			}
		}

		spots.sort((a, b) ->
		{
			if (a.perKill() != b.perKill())
			{
				return Double.compare(b.perKill(), a.perKill());
			}
			if (a.kind().getSpawnCount() != b.kind().getSpawnCount())
			{
				return Integer.compare(b.kind().getSpawnCount(), a.kind().getSpawnCount());
			}
			return Integer.compare(Math.abs(a.template().getLevel() - level), Math.abs(b.template().getLevel() - level));
		});
		return new Search(spots, unfound.size(), spots.isEmpty() ? hints(unfound, higher, spoils) : Collections.emptyList());
	}

	/**
	 * Where to look for an item no monster the account found gives: the regions of the monsters not found yet in the level range, the best ones first, or else of the monsters above it, the lowest first. None when the page hides where monsters not found yet live.
	 */
	private static List<Hint> hints(List<Candidate> unfound, List<Candidate> higher, boolean spoils)
	{
		if (!BestiaryConfig.SHOW_UNDISCOVERED_AREAS || (unfound.isEmpty() && higher.isEmpty()))
		{
			return Collections.emptyList();
		}

		final boolean above = unfound.isEmpty();
		final List<BestiaryCatalog.Area> areas = BestiaryCatalog.getInstance().getAreas();
		final Map<String, double[]> regions = new LinkedHashMap<>(); // best per kill, monsters, lowest level
		for (Candidate candidate : above ? higher : unfound)
		{
			final double perKill = candidate.perKill(spoils);
			final int monsterLevel = candidate.template().getLevel();
			final List<String> counted = new ArrayList<>(2);
			for (int areaId : candidate.kind().getAreas())
			{
				final BestiaryCatalog.Area area = areas.get(areaId);
				final String region = BestiaryCatalog.OTHER_REGION.equals(area.getRegion()) ? area.getName() : area.getRegion();
				if (counted.contains(region))
				{
					continue;
				}
				counted.add(region);
				final double[] values = regions.computeIfAbsent(region, key -> new double[]
				{
					0,
					0,
					Integer.MAX_VALUE
				});
				values[0] = Math.max(values[0], perKill);
				values[1]++;
				values[2] = Math.min(values[2], monsterLevel);
			}
		}

		final List<Map.Entry<String, double[]>> sorted = new ArrayList<>(regions.entrySet());
		sorted.sort((a, b) -> above ? Double.compare(a.getValue()[2], b.getValue()[2]) : Double.compare(b.getValue()[0], a.getValue()[0]));
		final List<Hint> hints = new ArrayList<>(HINTS);
		for (int i = 0; i < Math.min(HINTS, sorted.size()); i++)
		{
			final double[] values = sorted.get(i).getValue();
			hints.add(new Hint(sorted.get(i).getKey(), (int) values[1], (int) values[2], above));
		}
		return Collections.unmodifiableList(hints);
	}

	private static Spot spot(Candidate candidate, double perKill, long missing, boolean counted)
	{
		return new Spot(candidate.template(), candidate.kind(), candidate.drop(), candidate.spoil(), perKill, (long) Math.ceil(missing / perKill), counted);
	}

	/**
	 * @param allSpots every spot of each resource, best first
	 * @return the hunting grounds that give the most of the resources, against the best place of each
	 */
	private static List<Ground> grounds(List<List<Spot>> allSpots, int level)
	{
		final Map<Integer, Map<Integer, Spot>> byArea = new HashMap<>();
		final double[] best = new double[allSpots.size()];
		for (int r = 0; r < allSpots.size(); r++)
		{
			for (Spot spot : allSpots.get(r))
			{
				if (!spot.counted())
				{
					continue;
				}

				best[r] = Math.max(best[r], spot.perKill());
				for (int areaId : spot.kind().getAreas())
				{
					// The spots come best first: the first one of an area is its best.
					byArea.computeIfAbsent(areaId, id -> new TreeMap<>()).putIfAbsent(r, spot);
				}
			}
		}

		final List<BestiaryCatalog.Area> areas = BestiaryCatalog.getInstance().getAreas();
		final List<Ground> grounds = new ArrayList<>();
		for (Map.Entry<Integer, Map<Integer, Spot>> area : byArea.entrySet())
		{
			double score = 0;
			for (Map.Entry<Integer, Spot> spot : area.getValue().entrySet())
			{
				score += spot.getValue().perKill() / best[spot.getKey()];
			}
			grounds.add(new Ground(areas.get(area.getKey()), score, Collections.unmodifiableMap(area.getValue())));
		}

		grounds.sort((a, b) -> a.score() != b.score() ? Double.compare(b.score(), a.score()) : Double.compare(Math.abs(a.area().getAverageLevel() - level), Math.abs(b.area().getAverageLevel() - level)));
		return Collections.unmodifiableList(new ArrayList<>(grounds.subList(0, Math.min(GROUNDS, grounds.size()))));
	}

	public static BestiaryFarming getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final BestiaryFarming INSTANCE = new BestiaryFarming();
	}
}
