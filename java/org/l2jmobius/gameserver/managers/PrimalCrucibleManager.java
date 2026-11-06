/*
 * Copyright (c) 2013 L2jMobius
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.l2jmobius.gameserver.managers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.AttackableAI;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.data.xml.TransformData;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.npc.MonsterArchetype;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.actor.transform.Transform;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.mapdevice.MapArena;
import org.l2jmobius.gameserver.model.mapdevice.MapRun;
import org.l2jmobius.gameserver.model.mapdevice.MapRun.MonsterStat;
import org.l2jmobius.gameserver.model.mapdevice.UniqueMap;
import org.l2jmobius.gameserver.model.skill.AbnormalVisualEffect;
import org.l2jmobius.gameserver.model.stats.Stat;
import org.l2jmobius.gameserver.model.stats.functions.AbstractFunction;
import org.l2jmobius.gameserver.model.stats.functions.FuncMul;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * The Primal Crucible, a unique map of the Map Device ({@link UniqueMap.Type#CRUCIBLE}): "Evolve, or be eaten". {@link UniqueMapManager} calls in here at its hooks.
 * <ul>
 * <li><b>Evolution</b>: every kill gives the players near it {@link Hunter#_essence Essence}. At {@link #ESSENCE_STAGES} each player evolves (the first, Stage I, comes free on entering): one form of each path (fighter, caster, support) is offered, retail transformations, or <b>Defiance</b> (stay
 * yourself). Every stage adds HP and damage on top of the form; stage IV is the <b>Titan</b>: a giant (BIG_BODY), a burning aura, and <b>Rampage</b>.</li>
 * <li><b>The Food Chain</b>: every {@link #MEAL_INTERVAL} seconds the strongest monster with a weaker one near it eats it, and grows. Three meals make an <b>Alpha</b> (Essence and a <b>Mutation</b> for everyone), six an <b>Apex Predator</b> that hunts
 * the player with the most Essence. Every meal also feeds the final boss.</li>
 * <li><b>The Primordial</b>: lays eggs at 60% and 20% of its HP (they hatch into Alphas) and goes into a frenzy at 40%. Everyone inside when it dies gets <b>Primal Essence</b>, more the higher the run's best stage; 30 of it buy a <b>Titan Scroll</b>
 * that shows your Titan form in town, for looks only.</li>
 * </ul>
 * The forms and bonuses never leave the map: every instance change, the run closing and a sweep every second take them off.
 * @author Mobius
 */
public class PrimalCrucibleManager
{
	private static final Logger LOGGER = Logger.getLogger(PrimalCrucibleManager.class.getName());

	// Evolution.
	/** Essence needed for stages I to IV. */
	private static final int[] ESSENCE_STAGES =
	{
		40,
		100,
		200,
		330
	};
	/** HP and damage of each stage (0 = not evolved yet). */
	private static final double[] STAGE_BONUS =
	{
		1.0,
		1.2,
		1.5,
		2.0,
		3.0
	};
	private static final String[] STAGE_NAMES =
	{
		"Unevolved",
		"Stage I: Beast",
		"Stage II: Monster",
		"Stage III: Legend",
		"Stage IV: Titan"
	};
	private static final String[] PATHS =
	{
		"Fighter",
		"Caster",
		"Support"
	};
	/** The forms of stages I-III, by path (transformation ids). Stage IV is the Titan of a stage III form. */
	private static final int[][][] FORMS =
	{
		{ // Stage I: Beast
			{
				5, // Saber Tooth Tiger
				7, // Doll Blader
			},
			{
				4, // Veil Master
			},
			{
				3, // Heretic
			},
		},
		{ // Stage II: Monster
			{
				218, // Dragon Bomber
				209, // Lilim Knight
				212, // Golem Guardian
				6, // Oel Mahum
			},
			{
				215, // Inferno Drake
			},
			{
				206, // Unicorn
				203, // Grail Apostle
			},
		},
		{ // Stage III: Legend
			{
				305, // Zaken
				310, // Kechi
				308, // Gordon
				307, // Venom
				311, // Demon Prince
			},
			{
				306, // Anakim
				309, // Ranku
				256, // Divine Wizard
			},
			{
				255, // Divine Healer
				252, // Divine Knight
				257, // Divine Enchanter
			},
		},
	};
	private static final Map<Integer, String> FORM_NAMES = Map.ofEntries(Map.entry(3, "Heretic"), Map.entry(4, "Veil Master"), Map.entry(5, "Saber Tooth Tiger"), Map.entry(6, "Oel Mahum"), Map.entry(7, "Doll Blader"), Map.entry(203, "Grail Apostle"), Map.entry(206, "Unicorn"), Map.entry(209, "Lilim Knight"), Map.entry(212, "Golem Guardian"), Map.entry(215, "Inferno Drake"), Map.entry(218, "Dragon Bomber"), Map.entry(252, "Divine Knight"), Map.entry(255, "Divine Healer"), Map.entry(256, "Divine Wizard"), Map.entry(257, "Divine Enchanter"), Map.entry(305, "Zaken"), Map.entry(306, "Anakim"), Map.entry(307, "Venom"), Map.entry(308, "Gordon"), Map.entry(309, "Ranku"), Map.entry(310, "Kechi"), Map.entry(311, "Demon Prince"));
	/** Defiance: HP and damage per form refused. */
	private static final double DEFIANCE_BONUS = 0.15;
	private static final int ESSENCE_KILL = 10;
	private static final int ESSENCE_KILLER = 5;
	private static final int ESSENCE_ALPHA = 60;
	private static final int ESSENCE_APEX = 150;
	private static final int ESSENCE_RANGE = 2000;

	// Rampage and the Titan's aura.
	private static final int RAMPAGE_SECONDS = 10;
	private static final int RAMPAGE_COOLDOWN = 60;
	private static final double RAMPAGE_DAMAGE = 1.5;
	private static final double RAMPAGE_SPLASH = 0.3;
	private static final int SPLASH_RANGE = 200;
	private static final int AURA_INTERVAL = 2;
	private static final int AURA_RANGE = 250;
	private static final double AURA_DAMAGE = 0.01;
	private static final double AURA_BOSS_DAMAGE = 0.002;
	private static final double HUNGER_HEAL = 0.03;
	private static final int ENTER_DELAY = 1000;
	private static final int HEAL_DELAY = 600;

	// The Food Chain.
	private static final String MEALS_VAR = "CRUCIBLE_MEALS";
	private static final int MEAL_INTERVAL = 40;
	private static final int MEALS_MAX = 12;
	private static final int MEAL_RANGE = 800;
	/** A monster that has eaten hunts its next meal this far, walking to it. */
	private static final int HUNT_RANGE = 3000;
	/** Close enough to eat a hunted meal. */
	private static final int HUNT_REACH = 200;
	/** A hunt is given up after this long. */
	private static final int HUNT_SECONDS = 30;
	private static final int MIN_FIELD_LEFT = 6;
	private static final int ALPHA_MEALS = 3;
	private static final int APEX_MEALS = 6;
	private static final double MEAL_HP = 0.40;
	private static final double MEAL_ATTACK = 0.12;
	private static final double MEAL_DEFENCE = 0.05;
	private static final double MEAL_HEAL = 0.25;
	private static final int APEX_RETARGET = 10;
	private static final double ALPHA_SPECIAL_PER_MEAL = 0.25;

	// The Primordial.
	private static final double BOSS_MEAL_HP = 0.04;
	private static final String FRENZY_VAR = "CRUCIBLE_FRENZY";
	private static final double FRENZY = 1.25;
	private static final int EGG_NPC_ID = 18344; // Ancient Egg
	private static final int EGG_COUNT = 3;
	private static final int EGG_SECONDS = 20;
	/** The Ancient Egg has 10 HP (its passives make it less): this makes it about 40,000. */
	private static final double EGG_HP = 7500;
	private static final double EGG_SPREAD = 0.55;
	private static final int ROAR_SKILL_ID = 4526; // Quest - BOSS Summon (visual only)

	// Rewards.
	public static final int PRIMAL_ESSENCE = 50116;
	public static final int TITAN_SCROLL = 50117;
	private static final int SCROLL_COST = 30;
	/** Primal Essence for everyone inside when the Primordial dies, by the run's best stage. */
	private static final int[] ESSENCE_REWARD =
	{
		10,
		10,
		12,
		15,
		30
	};
	/** The Titan form a player last reached (0 = a Defiant Titan), for the Titan Scroll. */
	public static final String TITAN_VAR = "CRUCIBLE_TITAN_FORM";
	/** The looks-only copies of the forms (data/stats/transformations/09xxx.xml): this plus the form's id. */
	private static final int COSMETIC_OFFSET = 9000;
	private static final int COSMETIC_MINUTES = 30;
	private static final int COSMETIC_CHECK = 5000;

	/** The forms of each open Crucible's players, by instance id. */
	private static final Map<Integer, Crucible> CRUCIBLES = new ConcurrentHashMap<>();
	/** The Titan Scrolls in use in town, by player object id. */
	private static final Map<Integer, ScheduledFuture<?>> COSMETICS = new ConcurrentHashMap<>();
	private static final ThreadLocal<Boolean> SPLASHING = ThreadLocal.withInitial(() -> Boolean.FALSE);

	/** A trait taken from an Alpha's kill, for the rest of the run. */
	private enum Mutation
	{
		HORNS("Horns", "+20% P. Atk."),
		ARCANE_GLAND("Arcane Gland", "+20% M. Atk."),
		ADRENAL_SURGE("Adrenal Surge", "+15% attack and casting speed."),
		THICK_HIDE("Thick Hide", "+25% P. Def. and M. Def."),
		WINGS("Wings", "+25% speed."),
		PREDATORS_EYE("Predator's Eye", "+30% critical rate."),
		HUNGER("Hunger", "Heal 3% of your max HP with every kill."),
		REGENERATION("Regeneration", "Triple HP regeneration.");

		final String _name;
		final String _text;

		Mutation(String name, String text)
		{
			_name = name;
			_text = text;
		}
	}

	/** A choice waiting for a player: an evolution (3 forms or Defiance) or a Mutation (1 of 3). */
	private static final class Choice
	{
		final int _stage; // 0 for a Mutation
		final int[] _forms;
		final Mutation[] _mutations;

		Choice(int stage, int[] forms)
		{
			_stage = stage;
			_forms = forms;
			_mutations = null;
		}

		Choice(Mutation[] mutations)
		{
			_stage = 0;
			_forms = null;
			_mutations = mutations;
		}
	}

	/** A player's run in a Crucible. */
	private static final class Hunter
	{
		int _essence;
		int _stage;
		/** The form, 0 for none (yourself). */
		int _form;
		int _refusals;
		final Set<Mutation> _mutations = EnumSet.noneOf(Mutation.class);
		final Deque<Choice> _choices = new ArrayDeque<>();
		long _rampageUntil;
		long _rampageReady;
		boolean _rampaging;
	}

	/** An open Crucible. */
	private static final class Crucible
	{
		final Map<Integer, Hunter> _hunters = new ConcurrentHashMap<>();
		/** The players wearing their form and bonuses right now. */
		final Set<Integer> _wearing = ConcurrentHashMap.newKeySet();
		final Object _funcOwner = new Object();
		final Object _rampageOwner = new Object();
		/** Meals eaten in the field. */
		int _meals;
		final Set<Integer> _apexes = ConcurrentHashMap.newKeySet();
		/** The hunt under way: the eater, its prey and when it gives up (0 for none). */
		volatile int _hunter;
		volatile int _prey;
		volatile long _huntUntil;
		/** Eggs by object id, with the time they hatch. */
		final Map<Integer, Long> _eggs = new ConcurrentHashMap<>();
	}

	protected PrimalCrucibleManager()
	{
	}

	private static Crucible crucible(MapRun run)
	{
		return CRUCIBLES.computeIfAbsent(run.getInstanceId(), k -> new Crucible());
	}

	private static Hunter hunter(Crucible crucible, Player player)
	{
		return crucible._hunters.computeIfAbsent(player.getObjectId(), k -> new Hunter());
	}

	private static String formName(int form)
	{
		return form <= 0 ? "yourself" : FORM_NAMES.getOrDefault(form, "form " + form);
	}

	private static boolean isOurForm(int transformId)
	{
		return FORM_NAMES.containsKey(transformId);
	}

	// =========================================================================
	// Monster stats
	// =========================================================================

	/**
	 * @param monster a monster of a Crucible
	 * @param stat the stat
	 * @return what its meals (an Alpha's growth) and the Primordial's frenzy multiply the stat by
	 */
	static double getStatMultiplier(Monster monster, MonsterStat stat)
	{
		double multiplier = 1.0;
		final int meals = monster.getVariables().getInt(MEALS_VAR, 0);
		if (meals > 0)
		{
			multiplier *= switch (stat)
			{
				case HP -> 1 + (MEAL_HP * meals);
				case P_ATK, M_ATK -> 1 + (MEAL_ATTACK * meals);
				case P_DEF, M_DEF -> 1 + (MEAL_DEFENCE * meals);
				default -> 1.0;
			};
		}
		if (monster.getVariables().getBoolean(FRENZY_VAR, false))
		{
			multiplier *= switch (stat)
			{
				case P_ATK, M_ATK, ATK_SPD -> FRENZY;
				default -> 1.0;
			};
		}
		return multiplier;
	}

	/**
	 * @return what an egg's role multiplies a stat by
	 */
	static double getEggMultiplier(MonsterStat stat)
	{
		return stat == MonsterStat.HP ? EGG_HP : 1.0;
	}

	/**
	 * @param run a Crucible
	 * @return what the field's meals add to the Primordial's HP (x1 and up)
	 */
	static double getBossHpBonus(MapRun run)
	{
		final Crucible crucible = CRUCIBLES.get(run.getInstanceId());
		return 1 + ((crucible != null ? crucible._meals : 0) * BOSS_MEAL_HP);
	}

	/**
	 * @param run a Crucible
	 * @return how many meals the field ate
	 */
	static int getMeals(MapRun run)
	{
		final Crucible crucible = CRUCIBLES.get(run.getInstanceId());
		return crucible != null ? crucible._meals : 0;
	}

	// =========================================================================
	// Players: forms, bonuses, and keeping them inside the map
	// =========================================================================

	/**
	 * A player came into a Crucible: their form, stage and bonuses come back.
	 */
	static void onPlayerEntered(Player player, MapRun run)
	{
		final Crucible crucible = crucible(run);
		final Hunter hunter = hunter(crucible, player);
		endCosmetic(player);
		// Stage I comes free: you walk in already offered a Beast form, so even a solo player gets the evolution going.
		if ((hunter._stage == 0) && hunter._choices.isEmpty() && (hunter._essence < ESSENCE_STAGES[0]))
		{
			gain(player, hunter, ESSENCE_STAGES[0] - hunter._essence);
		}
		// Not in the middle of the instance change: a moment later, if still inside.
		ThreadPool.schedule(() ->
		{
			if (player.isOnline() && (player.getInstanceId() == run.getInstanceId()) && !run.isClosed())
			{
				apply(player, crucible, hunter);
			}
		}, ENTER_DELAY);
		player.sendMessage(run.getUnique().name() + ": evolve, or be eaten. You start as a Beast; kills give Essence, and at " + ESSENCE_STAGES[1] + ", " + ESSENCE_STAGES[2] + " and " + ESSENCE_STAGES[3] + " you evolve into a Monster, a Legend and a Titan. The monsters eat each other: three meals make an Alpha. Type .crucible for your evolution.");
		if (!hunter._choices.isEmpty())
		{
			remind(player, hunter);
		}
	}

	/**
	 * A player left a Crucible (died, went back, logged off): the form and the bonuses stay behind.
	 */
	static void onPlayerLeft(Player player, MapRun run)
	{
		final Crucible crucible = CRUCIBLES.get(run.getInstanceId());
		if (crucible != null)
		{
			strip(player, crucible);
		}
	}

	/**
	 * A Crucible closed: everyone still wearing a form loses it.
	 */
	static void onRunClosed(MapRun run)
	{
		final Crucible crucible = CRUCIBLES.remove(run.getInstanceId());
		if (crucible == null)
		{
			return;
		}
		for (int objectId : crucible._wearing)
		{
			final Player player = World.getInstance().getPlayer(objectId);
			if (player != null)
			{
				strip(player, crucible);
			}
		}
		crucible._wearing.clear();
	}

	/**
	 * Puts a player's form, Titan look and bonuses on, from their stage, refusals and Mutations.
	 */
	private static void apply(Player player, Crucible crucible, Hunter hunter)
	{
		player.removeStatsOwner(crucible._funcOwner);
		final double bonus = STAGE_BONUS[hunter._stage] * (1 + (hunter._refusals * DEFIANCE_BONUS));
		final List<AbstractFunction> functions = new ArrayList<>();
		mul(functions, Stat.MAX_HP, bonus, crucible._funcOwner);
		mul(functions, Stat.POWER_ATTACK, bonus, crucible._funcOwner);
		mul(functions, Stat.MAGIC_ATTACK, bonus, crucible._funcOwner);
		for (Mutation mutation : hunter._mutations)
		{
			switch (mutation)
			{
				case HORNS -> mul(functions, Stat.POWER_ATTACK, 1.2, crucible._funcOwner);
				case ARCANE_GLAND -> mul(functions, Stat.MAGIC_ATTACK, 1.2, crucible._funcOwner);
				case ADRENAL_SURGE ->
				{
					mul(functions, Stat.POWER_ATTACK_SPEED, 1.15, crucible._funcOwner);
					mul(functions, Stat.MAGIC_ATTACK_SPEED, 1.15, crucible._funcOwner);
				}
				case THICK_HIDE ->
				{
					mul(functions, Stat.POWER_DEFENCE, 1.25, crucible._funcOwner);
					mul(functions, Stat.MAGIC_DEFENCE, 1.25, crucible._funcOwner);
				}
				case WINGS -> mul(functions, Stat.MOVE_SPEED, 1.25, crucible._funcOwner);
				case PREDATORS_EYE ->
				{
					mul(functions, Stat.CRITICAL_RATE, 1.3, crucible._funcOwner);
					mul(functions, Stat.MCRITICAL_RATE, 1.3, crucible._funcOwner);
				}
				case REGENERATION -> mul(functions, Stat.REGENERATE_HP_RATE, 3.0, crucible._funcOwner);
				default ->
				{
				}
			}
		}
		player.addStatFuncs(functions);
		crucible._wearing.add(player.getObjectId());

		// The form.
		if (hunter._form > 0)
		{
			if (player.getTransformationId() != hunter._form)
			{
				final Transform transform = TransformData.getInstance().getTransform(hunter._form);
				if (transform != null)
				{
					if (player.getTransformation() != null)
					{
						player.untransform();
					}
					player.transform(transform);
				}
			}
		}
		else if (isOurForm(player.getTransformationId()))
		{
			player.untransform();
		}

		// The Titan's look.
		if (hunter._stage >= 4)
		{
			player.startAbnormalVisualEffect(true, AbnormalVisualEffect.BIG_BODY);
		}
		else
		{
			player.stopAbnormalVisualEffect(true, AbnormalVisualEffect.BIG_BODY);
		}
		refresh(player);
	}

	/**
	 * Takes a player's form, Titan look, Rampage and bonuses off.
	 */
	private static void strip(Player player, Crucible crucible)
	{
		crucible._wearing.remove(player.getObjectId());
		player.removeStatsOwner(crucible._funcOwner);
		player.removeStatsOwner(crucible._rampageOwner);
		final Hunter hunter = crucible._hunters.get(player.getObjectId());
		if (hunter != null)
		{
			hunter._rampaging = false;
		}
		if (isOurForm(player.getTransformationId()))
		{
			player.untransform();
		}
		player.stopAbnormalVisualEffect(true, AbnormalVisualEffect.BIG_BODY);
		player.stopAbnormalVisualEffect(true, AbnormalVisualEffect.ULTIMATE_DEFENCE);
		refresh(player);
	}

	private static void refresh(Player player)
	{
		ThreadPool.schedule(() ->
		{
			if (!player.isOnline())
			{
				return;
			}
			if (player.getCurrentHp() > player.getMaxHp())
			{
				player.setCurrentHp(player.getMaxHp());
			}
			player.broadcastUserInfo();
		}, 300);
	}

	private static void mul(List<AbstractFunction> functions, Stat stat, double value, Object owner)
	{
		if (value != 1.0)
		{
			functions.add(new FuncMul(stat, 0x30, owner, value, null));
		}
	}

	// =========================================================================
	// Essence and choices
	// =========================================================================

	/**
	 * Adds Essence; each stage reached queues its evolution.
	 */
	private static void gain(Player player, Hunter hunter, int amount)
	{
		synchronized (hunter)
		{
			hunter._essence += amount;
			int queued = hunter._stage;
			for (Choice choice : hunter._choices)
			{
				queued = Math.max(queued, choice._stage);
			}
			boolean added = false;
			while ((queued < ESSENCE_STAGES.length) && (hunter._essence >= ESSENCE_STAGES[queued]))
			{
				queued++;
				hunter._choices.add(new Choice(queued, rollForms(queued, hunter)));
				added = true;
			}
			if (added)
			{
				remind(player, hunter);
			}
		}
	}

	/**
	 * @return the forms offered at a stage: one of each path for stages I-III; for the Titan, your form (if any) and two other Legends
	 */
	private static int[] rollForms(int stage, Hunter hunter)
	{
		if (stage <= 3)
		{
			final int[] forms = new int[PATHS.length];
			for (int path = 0; path < PATHS.length; path++)
			{
				final int[] pool = FORMS[stage - 1][path];
				forms[path] = pool[Rnd.get(pool.length)];
			}
			return forms;
		}
		final List<Integer> legends = new ArrayList<>();
		for (int[] pool : FORMS[2])
		{
			for (int form : pool)
			{
				legends.add(form);
			}
		}
		Collections.shuffle(legends);
		final List<Integer> offer = new ArrayList<>();
		if (legends.contains(hunter._form))
		{
			offer.add(hunter._form);
		}
		for (int form : legends)
		{
			if ((offer.size() < 3) && !offer.contains(form))
			{
				offer.add(form);
			}
		}
		return offer.stream().mapToInt(Integer::intValue).toArray();
	}

	private static void offerMutation(Player player, Hunter hunter)
	{
		final List<Mutation> left = new ArrayList<>();
		for (Mutation mutation : Mutation.values())
		{
			if (!hunter._mutations.contains(mutation))
			{
				left.add(mutation);
			}
		}
		if (left.isEmpty())
		{
			return;
		}
		Collections.shuffle(left);
		synchronized (hunter)
		{
			hunter._choices.add(new Choice(left.subList(0, Math.min(3, left.size())).toArray(new Mutation[0])));
		}
		remind(player, hunter);
	}

	private static void remind(Player player, Hunter hunter)
	{
		final Choice choice = hunter._choices.peek();
		if (choice == null)
		{
			return;
		}
		final String what = choice._stage > 0 ? "Evolution ready: " + STAGE_NAMES[choice._stage] : "Mutation ready";
		player.sendPacket(new ExShowScreenMessage(what + "! Type .crucible", 4000));
		player.sendMessage(what + ": type .crucible to choose.");
		if (MapDeviceManager.getRun(player.getInstanceId()) != null)
		{
			showWindow(player);
		}
	}

	/**
	 * A player picks an option of their first waiting choice.
	 * @param index the option (for an evolution, the last one is Defiance)
	 * @return why it was refused, {@code null} if it was taken
	 */
	public String choose(Player player, int index)
	{
		final MapRun run = MapDeviceManager.getRun(player.getInstanceId());
		if ((run == null) || !run.isUnique() || (run.getUnique().type() != UniqueMap.Type.CRUCIBLE))
		{
			return "You can only evolve inside the Primal Crucible.";
		}
		final Crucible crucible = crucible(run);
		final Hunter hunter = hunter(crucible, player);
		final Choice choice;
		synchronized (hunter)
		{
			choice = hunter._choices.peek();
			if (choice == null)
			{
				return "Nothing to choose right now.";
			}
			final int options = choice._stage > 0 ? choice._forms.length + 1 : choice._mutations.length;
			if ((index < 0) || (index >= options))
			{
				return "No such option.";
			}
			hunter._choices.poll();
			if (choice._stage > 0)
			{
				hunter._stage = choice._stage;
				if (index == choice._forms.length)
				{
					hunter._refusals++;
					hunter._form = 0;
				}
				else
				{
					hunter._form = choice._forms[index];
				}
			}
			else
			{
				hunter._mutations.add(choice._mutations[index]);
			}
		}

		if (choice._stage > 0)
		{
			apply(player, crucible, hunter);
			// Healed to full once the new form and bonuses count (the max HP is read again a moment later).
			ThreadPool.schedule(() ->
			{
				if (player.isOnline() && !player.isDead())
				{
					player.setCurrentHpMp(player.getMaxHp(), player.getMaxMp());
					player.setCurrentCp(player.getMaxCp());
				}
			}, HEAL_DELAY);
			player.broadcastPacket(new MagicSkillUse(player, player, ROAR_SKILL_ID, 1, 0, 0));
			final String form = hunter._form > 0 ? "a " + formName(hunter._form) : "Defiant, as " + player.getName();
			if (hunter._stage >= 4)
			{
				player.getVariables().set(TITAN_VAR, hunter._form);
				MapDeviceManager.sendScreenToMap(run, player.getName() + " ascends: a TITAN rises!");
				MapDeviceManager.sendToMap(run, player.getName() + " becomes a Titan (" + form + "). Titans burn what stands near them, and can Rampage once a minute (.crucible rampage).");
			}
			else
			{
				MapDeviceManager.sendScreenToMap(run, player.getName() + " evolves: " + STAGE_NAMES[hunter._stage] + "!");
				MapDeviceManager.sendToMap(run, player.getName() + " evolves to " + STAGE_NAMES[hunter._stage] + ": " + form + ".");
			}
		}
		else
		{
			apply(player, crucible, hunter);
			player.sendMessage("Mutation: " + choice._mutations[index]._name + ". " + choice._mutations[index]._text);
		}
		if (!hunter._choices.isEmpty())
		{
			remind(player, hunter);
		}
		return null;
	}

	/**
	 * A player takes their form again (after Transform Dispel, or a form that didn't take).
	 * @return why it was refused, {@code null} if it was done
	 */
	public String takeForm(Player player)
	{
		final MapRun run = MapDeviceManager.getRun(player.getInstanceId());
		final Crucible crucible = run != null ? CRUCIBLES.get(run.getInstanceId()) : null;
		final Hunter hunter = crucible != null ? crucible._hunters.get(player.getObjectId()) : null;
		if ((hunter == null) || (hunter._form <= 0))
		{
			return "You have no form to take.";
		}
		if (player.isDead())
		{
			return "Not while dead.";
		}
		apply(player, crucible, hunter);
		return null;
	}

	/**
	 * A Titan's Rampage: for {@link #RAMPAGE_SECONDS} seconds, 50% more damage that splashes onto the monsters around, double P. Def. and M. Def., and double Essence.
	 * @return why it was refused, {@code null} if it began
	 */
	public String rampage(Player player)
	{
		final MapRun run = MapDeviceManager.getRun(player.getInstanceId());
		final Crucible crucible = run != null ? CRUCIBLES.get(run.getInstanceId()) : null;
		final Hunter hunter = crucible != null ? crucible._hunters.get(player.getObjectId()) : null;
		if ((hunter == null) || (hunter._stage < 4))
		{
			return "Only a Titan can Rampage.";
		}
		if (player.isDead())
		{
			return "Not while dead.";
		}
		final long now = System.currentTimeMillis();
		if (now < hunter._rampageReady)
		{
			return "Rampage is ready in " + (((hunter._rampageReady - now) / 1000) + 1) + " seconds.";
		}
		hunter._rampageUntil = now + (RAMPAGE_SECONDS * 1000L);
		hunter._rampageReady = now + (RAMPAGE_COOLDOWN * 1000L);
		hunter._rampaging = true;
		player.removeStatsOwner(crucible._rampageOwner);
		final List<AbstractFunction> functions = new ArrayList<>();
		mul(functions, Stat.POWER_DEFENCE, 2.0, crucible._rampageOwner);
		mul(functions, Stat.MAGIC_DEFENCE, 2.0, crucible._rampageOwner);
		mul(functions, Stat.POWER_ATTACK_SPEED, 1.2, crucible._rampageOwner);
		player.addStatFuncs(functions);
		player.startAbnormalVisualEffect(true, AbnormalVisualEffect.ULTIMATE_DEFENCE);
		player.broadcastPacket(new MagicSkillUse(player, player, ROAR_SKILL_ID, 1, 0, 0));
		MapDeviceManager.sendScreenToMap(run, player.getName() + " RAMPAGES!");
		refresh(player);
		return null;
	}

	private static void endRampage(Player player, Crucible crucible, Hunter hunter)
	{
		hunter._rampaging = false;
		player.removeStatsOwner(crucible._rampageOwner);
		player.stopAbnormalVisualEffect(true, AbnormalVisualEffect.ULTIMATE_DEFENCE);
		player.sendMessage("Your Rampage ends.");
		refresh(player);
	}

	private static boolean isRampaging(Crucible crucible, Player player)
	{
		final Hunter hunter = crucible._hunters.get(player.getObjectId());
		return (hunter != null) && hunter._rampaging && (System.currentTimeMillis() < hunter._rampageUntil);
	}

	// =========================================================================
	// Damage and deaths
	// =========================================================================

	/**
	 * A monster of a Crucible is about to take damage: a Rampage's damage and splash.
	 * @return the damage it takes
	 */
	static double onMonsterDamaged(MapRun run, Monster monster, double damage, Player attacker)
	{
		if ((attacker == null) || SPLASHING.get())
		{
			return damage;
		}
		final Crucible crucible = CRUCIBLES.get(run.getInstanceId());
		if ((crucible == null) || !isRampaging(crucible, attacker))
		{
			return damage;
		}
		final double hit = damage * RAMPAGE_DAMAGE;
		final double splash = hit * RAMPAGE_SPLASH;
		final List<Monster> around = World.getInstance().getVisibleObjectsInRange(monster, Monster.class, SPLASH_RANGE, other -> (other != monster) && !other.isDead() && (other.getInstanceId() == run.getInstanceId()));
		if (!around.isEmpty())
		{
			SPLASHING.set(Boolean.TRUE);
			try
			{
				for (Monster other : around)
				{
					other.reduceCurrentHp(splash, attacker, null);
				}
			}
			finally
			{
				SPLASHING.set(Boolean.FALSE);
			}
		}
		return hit;
	}

	/**
	 * A monster of a Crucible died: Essence for the players near it, an Alpha's Mutations, the eggs.
	 * @param counted {@code true} if it counted toward the clear
	 * @param isEgg {@code true} for one of the Primordial's eggs
	 * @param isHatchling {@code true} for an Alpha hatched from an egg
	 * @param isBoss {@code true} for the Primordial
	 */
	static void onMonsterDied(MapRun run, Monster victim, Player killer, boolean counted, boolean isEgg, boolean isHatchling, boolean isBoss)
	{
		final Crucible crucible = crucible(run);
		if (isEgg)
		{
			crucible._eggs.remove(victim.getObjectId());
			MapDeviceManager.sendToMap(run, "An egg is smashed" + (killer != null ? " by " + killer.getName() : "") + ".");
			return;
		}
		if (isBoss)
		{
			rewardEssence(run, crucible);
			return;
		}
		if (!counted && !isHatchling)
		{
			return;
		}

		final int meals = isHatchling ? Math.max(ALPHA_MEALS, victim.getVariables().getInt(MEALS_VAR, 0)) : victim.getVariables().getInt(MEALS_VAR, 0);
		crucible._apexes.remove(victim.getObjectId());
		final int bonus = meals >= APEX_MEALS ? ESSENCE_APEX : meals >= ALPHA_MEALS ? ESSENCE_ALPHA : 0;
		final boolean doubled = (killer != null) && isRampaging(crucible, killer);
		for (Player player : MapDeviceManager.getPlayersInside(run))
		{
			if (player.isDead() || (player.calculateDistance2D(victim) > ESSENCE_RANGE))
			{
				continue;
			}
			int amount = ESSENCE_KILL + bonus;
			if (player == killer)
			{
				amount += ESSENCE_KILLER;
				if (doubled)
				{
					amount *= 2;
				}
			}
			gain(player, hunter(crucible, player), amount);
		}
		if ((killer != null) && hunter(crucible, killer)._mutations.contains(Mutation.HUNGER) && !killer.isDead())
		{
			killer.setCurrentHp(Math.min(killer.getMaxHp(), killer.getCurrentHp() + (killer.getMaxHp() * HUNGER_HEAL)));
		}

		if (meals >= ALPHA_MEALS)
		{
			// An Alpha's special drops grow with what it ate.
			if (killer != null)
			{
				UniqueMapManager.dropSpecial(run, victim, killer, 1 + (ALPHA_SPECIAL_PER_MEAL * meals));
			}
			MapDeviceManager.sendScreenToMap(run, (meals >= APEX_MEALS ? "The Apex Predator" : "An Alpha") + " falls" + (killer != null ? " to " + killer.getName() : "") + "! Choose a Mutation.");
			for (Player player : MapDeviceManager.getPlayersInside(run))
			{
				offerMutation(player, hunter(crucible, player));
			}
		}
	}

	/**
	 * Everyone inside when the Primordial dies gets Primal Essence, more the higher the run's best stage.
	 */
	private static void rewardEssence(MapRun run, Crucible crucible)
	{
		int best = 0;
		for (Hunter hunter : crucible._hunters.values())
		{
			best = Math.max(best, hunter._stage);
		}
		final int count = ESSENCE_REWARD[best];
		if (ItemData.getInstance().getTemplate(PRIMAL_ESSENCE) == null)
		{
			return;
		}
		for (Player player : MapDeviceManager.getPlayersInside(run))
		{
			player.addItem(ItemProcessType.REWARD, PRIMAL_ESSENCE, count, player, true);
		}
		MapDeviceManager.sendToMap(run, "The Primordial's essence is yours: " + count + " Primal Essence each" + (best >= 4 ? " (a Titan stood in the Crucible: triple)." : best >= 3 ? " (a Legend stood in the Crucible)." : ".") + " " + SCROLL_COST + " of it buy a Titan Scroll (.crucible in town).");
	}

	// =========================================================================
	// The Primordial
	// =========================================================================

	/**
	 * The Primordial passed an HP threshold: eggs at the first and last, a frenzy in the middle.
	 */
	static void onBossThreshold(MapRun run, Monster boss, int index, int thresholds)
	{
		if ((index == 0) || (index == (thresholds - 1)))
		{
			layEggs(run, boss);
		}
		else
		{
			boss.getVariables().set(FRENZY_VAR, true);
			boss.broadcastPacket(new MagicSkillUse(boss, boss, ROAR_SKILL_ID, 1, 0, 0));
			MapDeviceManager.sendScreenToMap(run, "The Primordial goes into a frenzy!");
			boss.broadcastInfo();
		}
	}

	private static void layEggs(MapRun run, Monster boss)
	{
		final NpcTemplate template = NpcData.getInstance().getTemplate(EGG_NPC_ID);
		if (template == null)
		{
			return;
		}
		final Crucible crucible = crucible(run);
		final MapArena arena = run.getArena();
		final double start = Rnd.nextDouble() * 2 * Math.PI;
		final long hatch = System.currentTimeMillis() + (EGG_SECONDS * 1000L);
		for (int i = 0; i < EGG_COUNT; i++)
		{
			final double angle = start + ((i * 2 * Math.PI) / EGG_COUNT);
			final Location spot = MapDeviceManager.getGroundLocation(new Location(arena.x() + (int) (Math.cos(angle) * arena.radiusX() * EGG_SPREAD), arena.y() + (int) (Math.sin(angle) * arena.radiusY() * EGG_SPREAD), arena.z()));
			final Monster egg = UniqueMapManager.spawnEgg(run, template, spot);
			if (egg != null)
			{
				egg.setImmobilized(true);
				egg.setRandomWalking(false);
				crucible._eggs.put(egg.getObjectId(), hatch);
			}
		}
		boss.broadcastPacket(new MagicSkillUse(boss, boss, ROAR_SKILL_ID, 1, 0, 0));
		MapDeviceManager.sendScreenToMap(run, "The Primordial lays " + EGG_COUNT + " eggs: smash them within " + EGG_SECONDS + " seconds!");
	}

	private static void hatch(MapRun run, Monster egg)
	{
		final List<NpcTemplate> kinds = run.getMonsterKinds();
		final Location spot = new Location(egg.getX(), egg.getY(), egg.getZ());
		egg.deleteMe();
		if (kinds.isEmpty())
		{
			return;
		}
		final Monster hatchling = UniqueMapManager.spawnHatchling(run, kinds.get(Rnd.get(kinds.size())), spot);
		if (hatchling != null)
		{
			hatchling.getVariables().set(MEALS_VAR, ALPHA_MEALS);
			hatchling.startAbnormalVisualEffect(true, AbnormalVisualEffect.BIG_BODY);
			hatchling.setCurrentHpMp(hatchling.getMaxHp(), hatchling.getMaxMp());
			hatchling.broadcastInfo();
			UniqueMapManager.attack(hatchling, MapDeviceManager.getPlayersInside(run));
			MapDeviceManager.sendToMap(run, "An egg hatches: an Alpha breaks out!");
		}
	}

	// =========================================================================
	// The run's clock: the Food Chain, the Titans' aura, Rampage, eggs, the sweep
	// =========================================================================

	/**
	 * Once a second while the run is open.
	 * @param phase 0 while the field is cleared, 1 once the Primordial is up
	 */
	static void onTick(MapRun run, int tick, long now, int phase)
	{
		final Crucible crucible = crucible(run);

		// The sweep: nobody wears a form outside the map.
		for (int objectId : crucible._wearing)
		{
			final Player player = World.getInstance().getPlayer(objectId);
			if ((player == null) || !player.isOnline())
			{
				crucible._wearing.remove(objectId);
			}
			else if (player.getInstanceId() != run.getInstanceId())
			{
				strip(player, crucible);
				LOGGER.info(PrimalCrucibleManager.class.getSimpleName() + ": " + player.getName() + " wore a Crucible form outside it (instance " + player.getInstanceId() + "): taken off.");
			}
		}

		final List<Player> players = MapDeviceManager.getPlayersInside(run);
		for (Player player : players)
		{
			final Hunter hunter = crucible._hunters.get(player.getObjectId());
			if (hunter == null)
			{
				continue;
			}
			if (hunter._rampaging && (now >= hunter._rampageUntil))
			{
				endRampage(player, crucible, hunter);
			}
			// The Titan's aura.
			if ((hunter._stage >= 4) && !player.isDead() && ((tick % AURA_INTERVAL) == 0))
			{
				for (Monster monster : World.getInstance().getVisibleObjectsInRange(player, Monster.class, AURA_RANGE, m -> !m.isDead() && (m.getInstanceId() == run.getInstanceId())))
				{
					final boolean boss = UniqueMapManager.isBoss(monster);
					monster.reduceCurrentHp(monster.getMaxHp() * (boss ? AURA_BOSS_DAMAGE : AURA_DAMAGE), player, null);
				}
			}
		}

		if (phase == 0)
		{
			if ((tick % MEAL_INTERVAL) == 0)
			{
				devour(run, crucible);
			}
			hunt(run, crucible, now);
			if ((tick % APEX_RETARGET) == 0)
			{
				huntWithApexes(run, crucible, players);
			}
		}

		for (Map.Entry<Integer, Long> entry : crucible._eggs.entrySet())
		{
			if ((now >= entry.getValue()) && (crucible._eggs.remove(entry.getKey()) != null))
			{
				final WorldObject object = World.getInstance().findObject(entry.getKey());
				if ((object instanceof Monster egg) && !egg.isDead())
				{
					hatch(run, egg);
				}
			}
		}
	}

	/**
	 * The Food Chain: the strongest monster with a weaker one near it eats it.
	 */
	private static void devour(MapRun run, Crucible crucible)
	{
		if ((crucible._meals >= MEALS_MAX) || (run.getMonsters().size() <= MIN_FIELD_LEFT) || (crucible._hunter != 0))
		{
			return;
		}
		final List<Monster> field = new ArrayList<>();
		for (int objectId : run.getMonsters())
		{
			final WorldObject object = World.getInstance().findObject(objectId);
			if ((object instanceof Monster monster) && !monster.isDead() && !UniqueMapManager.isEncounterMonster(monster))
			{
				field.add(monster);
			}
		}
		Collections.shuffle(field);
		field.sort((a, b) -> Integer.compare(b.getVariables().getInt(MEALS_VAR, 0), a.getVariables().getInt(MEALS_VAR, 0)));
		for (Monster eater : field)
		{
			if (eater.isInCombat())
			{
				continue; // Busy with the players.
			}
			final int meals = eater.getVariables().getInt(MEALS_VAR, 0);
			// A monster that has eaten already hunts far for its next meal: the strong get stronger, and Alphas grow even in a spread-out field.
			final int range = meals > 0 ? HUNT_RANGE : MEAL_RANGE;
			Monster prey = null;
			double nearest = range;
			for (Monster other : field)
			{
				if ((other == eater) || (other.getVariables().getInt(MEALS_VAR, 0) > meals) || other.isInCombat())
				{
					continue;
				}
				final double distance = eater.calculateDistance2D(other);
				if (distance <= nearest)
				{
					nearest = distance;
					prey = other;
				}
			}
			if (prey == null)
			{
				continue;
			}
			if (nearest <= MEAL_RANGE)
			{
				eat(run, crucible, eater, prey);
			}
			else
			{
				// It stalks the prey across the field, and eats it when it gets there.
				crucible._hunter = eater.getObjectId();
				crucible._prey = prey.getObjectId();
				crucible._huntUntil = System.currentTimeMillis() + (HUNT_SECONDS * 1000L);
				prey.setRandomWalking(false);
				if (eater.hasAI())
				{
					eater.getAI().setIntention(Intention.MOVE_TO, new Location(prey.getX(), prey.getY(), prey.getZ()));
				}
				MapDeviceManager.sendToMap(run, "A " + eater.getName() + " (" + meals + (meals == 1 ? " meal" : " meals") + ") is hunting a " + prey.getName() + " " + describeWhere(run, eater) + "...");
			}
			return;
		}
	}

	/**
	 * The hunt under way, once a second: the eater eats its prey when it reaches it, and gives up when it is fighting, or after {@link #HUNT_SECONDS}.
	 */
	private static void hunt(MapRun run, Crucible crucible, long now)
	{
		if (crucible._hunter == 0)
		{
			return;
		}
		final WorldObject a = World.getInstance().findObject(crucible._hunter);
		final WorldObject b = World.getInstance().findObject(crucible._prey);
		final Monster eater = (a instanceof Monster m) && !m.isDead() ? m : null;
		final Monster prey = (b instanceof Monster m) && !m.isDead() && run.getMonsters().contains(m.getObjectId()) ? m : null;
		if ((eater == null) || (prey == null) || eater.isInCombat() || (now > crucible._huntUntil))
		{
			if (prey != null)
			{
				prey.setRandomWalking(true);
			}
			crucible._hunter = 0;
			crucible._prey = 0;
			return;
		}
		if (eater.calculateDistance2D(prey) <= HUNT_REACH)
		{
			crucible._hunter = 0;
			crucible._prey = 0;
			eat(run, crucible, eater, prey);
		}
		else if (eater.hasAI() && !eater.isMoving())
		{
			eater.getAI().setIntention(Intention.MOVE_TO, new Location(prey.getX(), prey.getY(), prey.getZ()));
		}
	}

	private static void eat(MapRun run, Crucible crucible, Monster eater, Monster prey)
	{
		if (!run.getMonsters().remove(prey.getObjectId()))
		{
			return;
		}
		run.setMonsterTotal(Math.max(run.getMonsters().size(), run.getMonsterTotal() - 1));
		eater.broadcastPacket(new MagicSkillUse(eater, prey, ROAR_SKILL_ID, 1, 0, 0));
		final String preyName = prey.getName();
		prey.deleteMe();
		crucible._meals++;

		final int meals = eater.getVariables().getInt(MEALS_VAR, 0) + 1;
		eater.getVariables().set(MEALS_VAR, meals);
		// Its max HP just grew: it heals a quarter of it.
		eater.setCurrentHp(Math.min(eater.getMaxHp(), eater.getCurrentHp() + (eater.getMaxHp() * MEAL_HEAL)));
		final String where = describeWhere(run, eater);
		if (meals == ALPHA_MEALS)
		{
			eater.startAbnormalVisualEffect(true, AbnormalVisualEffect.BIG_BODY);
			eater.setEventTitleTag("[Alpha]");
			MapDeviceManager.sendScreenToMap(run, "An Alpha has grown " + where + "!");
			MapDeviceManager.sendToMap(run, "A " + eater.getName() + " devours a " + preyName + " " + where + " and becomes an Alpha. Kill it for Essence and a Mutation, or let it grow...");
		}
		else if (meals == APEX_MEALS)
		{
			eater.setEventTitleTag("[Apex Predator]");
			if (eater.getAI() instanceof AttackableAI ai)
			{
				ai.setArchetype(MonsterArchetype.RAGER);
			}
			crucible._apexes.add(eater.getObjectId());
			final Player prey2 = topHunter(run, crucible, MapDeviceManager.getPlayersInside(run));
			MapDeviceManager.sendScreenToMap(run, "An APEX PREDATOR hunts " + (prey2 != null ? prey2.getName() : "you") + "!");
			if (prey2 != null)
			{
				UniqueMapManager.attack(eater, List.of(prey2));
			}
		}
		else
		{
			MapDeviceManager.sendToMap(run, "A " + eater.getName() + " devours a " + preyName + " " + where + " (" + meals + (meals == 1 ? " meal" : " meals") + ").");
		}
		eater.broadcastInfo();
	}

	private static void huntWithApexes(MapRun run, Crucible crucible, List<Player> players)
	{
		if (crucible._apexes.isEmpty())
		{
			return;
		}
		final Player target = topHunter(run, crucible, players);
		if (target == null)
		{
			return;
		}
		for (int objectId : crucible._apexes)
		{
			final WorldObject object = World.getInstance().findObject(objectId);
			if (!(object instanceof Monster apex) || apex.isDead())
			{
				crucible._apexes.remove(objectId);
				continue;
			}
			if (!apex.hasAI() || (apex.getAI().getIntention() != Intention.ATTACK))
			{
				UniqueMapManager.attack(apex, List.of(target));
			}
		}
	}

	/**
	 * @return the living player inside with the most Essence
	 */
	private static Player topHunter(MapRun run, Crucible crucible, List<Player> players)
	{
		Player best = null;
		int most = -1;
		for (Player player : players)
		{
			final Hunter hunter = crucible._hunters.get(player.getObjectId());
			final int essence = hunter != null ? hunter._essence : 0;
			if (!player.isDead() && (essence > most))
			{
				most = essence;
				best = player;
			}
		}
		return best;
	}

	private static String describeWhere(MapRun run, Monster monster)
	{
		final Location entry = run.getArena().entry();
		final double angle = Math.toDegrees(Math.atan2(monster.getY() - entry.getY(), monster.getX() - entry.getX()));
		final String[] ways =
		{
			"east",
			"south-east",
			"south",
			"south-west",
			"west",
			"north-west",
			"north",
			"north-east"
		};
		return "to the " + ways[(int) Math.floorMod(Math.round(angle / 45), 8)] + " of the entry";
	}

	// =========================================================================
	// The window (.crucible)
	// =========================================================================

	/**
	 * The Crucible window: stage, Essence, form, Mutations, Rampage and the waiting choice; in town, the Titan Scroll exchange.
	 */
	public static void showWindow(Player player)
	{
		final MapRun run = MapDeviceManager.getRun(player.getInstanceId());
		final boolean inside = (run != null) && run.isUnique() && (run.getUnique().type() == UniqueMap.Type.CRUCIBLE);
		final StringBuilder sb = new StringBuilder(2048);
		sb.append("<html><body><center><font name=\"hs12\" color=\"FF9955\">The Primal Crucible</font></center><br>");
		if (!inside)
		{
			sb.append("<font color=\"A0A0A0\">Evolve, or be eaten. Open a Unique Map: The Primal Crucible at your Map Device.</font><br><br>");
			final long essence = player.getInventory().getInventoryItemCount(PRIMAL_ESSENCE, -1);
			sb.append("Primal Essence: <font color=\"E6C35C\">").append(essence).append("</font><br>");
			sb.append("<font color=\"A0A0A0\">").append(SCROLL_COST).append(" Primal Essence buy a Titan Scroll: your Titan form in town for ").append(COSMETIC_MINUTES).append(" minutes, looks only.</font><br><br>");
			sb.append("<center><button value=\"Buy a Titan Scroll\" action=\"bypass voiced_crucible exchange\" width=180 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></center>");
			sb.append("</body></html>");
			send(player, sb);
			return;
		}
		final Crucible crucible = crucible(run);
		final Hunter hunter = hunter(crucible, player);
		final int next = hunter._stage < ESSENCE_STAGES.length ? ESSENCE_STAGES[hunter._stage] : 0;
		sb.append("<table width=270>");
		sb.append("<tr><td><font color=\"A0A0A0\">Stage</font></td><td align=right><font color=\"FF5555\">").append(STAGE_NAMES[hunter._stage]).append("</font></td></tr>");
		sb.append("<tr><td><font color=\"A0A0A0\">Form</font></td><td align=right>").append(hunter._form > 0 ? formName(hunter._form) : hunter._stage > 0 ? "Defiant" : "yourself").append("</td></tr>");
		sb.append("<tr><td><font color=\"A0A0A0\">Essence</font></td><td align=right><font color=\"E6C35C\">").append(hunter._essence).append(next > 0 ? " / " + next : "").append("</font></td></tr>");
		final double bonus = STAGE_BONUS[hunter._stage] * (1 + (hunter._refusals * DEFIANCE_BONUS));
		sb.append("<tr><td><font color=\"A0A0A0\">HP and damage</font></td><td align=right><font color=\"66CC66\">x").append(String.format("%.2f", bonus)).append("</font></td></tr>");
		if (hunter._refusals > 0)
		{
			sb.append("<tr><td><font color=\"A0A0A0\">Defiance</font></td><td align=right>").append(hunter._refusals).append(hunter._refusals == 1 ? " refusal" : " refusals").append("</td></tr>");
		}
		sb.append("<tr><td><font color=\"A0A0A0\">Meals of the field</font></td><td align=right>").append(crucible._meals).append(" (the Primordial +").append(Math.round(crucible._meals * BOSS_MEAL_HP * 100)).append("% HP)</td></tr>");
		sb.append("</table><br>");
		if (!hunter._mutations.isEmpty())
		{
			sb.append("<font color=\"LEVEL\">Mutations</font><br>");
			for (Mutation mutation : hunter._mutations)
			{
				sb.append("<font color=\"99CCFF\">").append(mutation._name).append("</font>: ").append(mutation._text).append("<br1>");
			}
			sb.append("<br>");
		}

		final Choice choice = hunter._choices.peek();
		if (choice != null)
		{
			if (choice._stage > 0)
			{
				sb.append("<font color=\"LEVEL\">Evolve: ").append(STAGE_NAMES[choice._stage]).append("</font><br>");
				for (int i = 0; i < choice._forms.length; i++)
				{
					final String label = (choice._stage <= 3 ? PATHS[i] + ": " : "") + formName(choice._forms[i]);
					button(sb, label, "evolve " + i);
				}
				button(sb, choice._stage >= 4 ? "Defiant Titan (yourself, giant)" : "Defy (stay yourself, +15%)", "evolve " + choice._forms.length);
			}
			else
			{
				sb.append("<font color=\"LEVEL\">Mutation: choose one</font><br>");
				for (int i = 0; i < choice._mutations.length; i++)
				{
					button(sb, choice._mutations[i]._name, "mutate " + i);
					sb.append("<font color=\"707070\">").append(choice._mutations[i]._text).append("</font><br1>");
				}
			}
			if (hunter._choices.size() > 1)
			{
				sb.append("<font color=\"707070\">").append(hunter._choices.size() - 1).append(" more after this one.</font><br>");
			}
			sb.append("<br>");
		}

		if (hunter._stage >= 4)
		{
			final long wait = Math.max(0, hunter._rampageReady - System.currentTimeMillis());
			if (wait == 0)
			{
				button(sb, "RAMPAGE", "rampage");
			}
			else
			{
				sb.append("<font color=\"707070\">Rampage ready in ").append((wait / 1000) + 1).append(" s. (Macro: .crucible rampage)</font><br>");
			}
		}
		if ((hunter._form > 0) && (player.getTransformationId() != hunter._form))
		{
			button(sb, "Take your form again", "form");
		}
		sb.append("</body></html>");
		send(player, sb);
	}

	private static void button(StringBuilder sb, String label, String params)
	{
		sb.append("<center><button value=\"").append(label).append("\" action=\"bypass voiced_crucible ").append(params).append("\" width=220 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></center>");
	}

	private static void send(Player player, StringBuilder sb)
	{
		final NpcHtmlMessage html = new NpcHtmlMessage(0);
		html.setHtml(sb.toString());
		player.sendPacket(html);
	}

	// =========================================================================
	// Primal Essence and the Titan Scroll
	// =========================================================================

	/**
	 * Trades {@link #SCROLL_COST} Primal Essence for a Titan Scroll.
	 * @return why it was refused, {@code null} if it was done
	 */
	public String exchange(Player player)
	{
		if (ItemData.getInstance().getTemplate(TITAN_SCROLL) == null)
		{
			return "The Titan Scroll is not available.";
		}
		if (player.getInventory().getInventoryItemCount(PRIMAL_ESSENCE, -1) < SCROLL_COST)
		{
			return "A Titan Scroll costs " + SCROLL_COST + " Primal Essence.";
		}
		if (!player.destroyItemByItemId(ItemProcessType.FEE, PRIMAL_ESSENCE, SCROLL_COST, player, true))
		{
			return "A Titan Scroll costs " + SCROLL_COST + " Primal Essence.";
		}
		player.addItem(ItemProcessType.REWARD, TITAN_SCROLL, 1, player, true);
		return null;
	}

	/**
	 * The Titan Scroll: the player's last Titan form, giant, for {@link #COSMETIC_MINUTES} minutes in a peaceful place. Looks only: a non-combat copy of the form (id + {@link #COSMETIC_OFFSET}, data/stats/transformations) that shows the form's model; a
	 * Defiant Titan is just giant. It ends on leaving the peaceful place, on Transform Dispel, or when the time is up.
	 * @return why it was refused, {@code null} if it began (the scroll is then used up)
	 */
	public String useTitanScroll(Player player)
	{
		if (!player.getVariables().hasVariable(TITAN_VAR))
		{
			return "You have never become a Titan in the Primal Crucible.";
		}
		if (!player.isInsideZone(ZoneId.PEACE) || (player.getInstanceId() != 0))
		{
			return "The Titan Scroll only works in a town or another peaceful place.";
		}
		if (player.isDead() || player.isMounted() || (player.getTransformation() != null) || COSMETICS.containsKey(player.getObjectId()))
		{
			return "You can't use it now.";
		}
		final int form = player.getVariables().getInt(TITAN_VAR, 0);
		if (form > 0)
		{
			final Transform transform = TransformData.getInstance().getTransform(COSMETIC_OFFSET + form);
			if (transform == null)
			{
				return "The Titan Scroll can't show that form.";
			}
			player.transform(transform);
		}
		player.startAbnormalVisualEffect(true, AbnormalVisualEffect.BIG_BODY);
		final long until = System.currentTimeMillis() + (COSMETIC_MINUTES * 60000L);
		final int cosmetic = form > 0 ? COSMETIC_OFFSET + form : 0;
		COSMETICS.put(player.getObjectId(), ThreadPool.scheduleAtFixedRate(() ->
		{
			if (!player.isOnline() || !player.isInsideZone(ZoneId.PEACE) || (System.currentTimeMillis() >= until) || ((cosmetic > 0) && (player.getTransformationId() != cosmetic)))
			{
				endCosmetic(player);
			}
		}, COSMETIC_CHECK, COSMETIC_CHECK));
		player.sendMessage("You stand as a Titan" + (form > 0 ? " (" + formName(form) + ")" : "") + " for " + COSMETIC_MINUTES + " minutes, as long as you stay in a peaceful place.");
		return null;
	}

	/**
	 * Ends a Titan Scroll's look.
	 */
	private static void endCosmetic(Player player)
	{
		final ScheduledFuture<?> task = COSMETICS.remove(player.getObjectId());
		if (task == null)
		{
			return;
		}
		task.cancel(false);
		if ((player.getTransformationId() > COSMETIC_OFFSET) && (player.getTransformationId() < (COSMETIC_OFFSET + 1000)))
		{
			player.untransform();
		}
		player.stopAbnormalVisualEffect(true, AbnormalVisualEffect.BIG_BODY);
	}

	public static PrimalCrucibleManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PrimalCrucibleManager INSTANCE = new PrimalCrucibleManager();
	}
}
