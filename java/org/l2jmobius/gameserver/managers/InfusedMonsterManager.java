package org.l2jmobius.gameserver.managers;

import java.util.Map;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.InfusedMonsterConfig;
import org.l2jmobius.gameserver.data.holders.ElementalItemHolder;
import org.l2jmobius.gameserver.data.xml.ElementalAttributeData;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Chest;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.hotzone.HotzoneModifier;
import org.l2jmobius.gameserver.model.item.enums.ElementalItemType;
import org.l2jmobius.gameserver.model.item.holders.Elementals;
import org.l2jmobius.gameserver.model.item.holders.ItemHolder;
import org.l2jmobius.gameserver.model.spawns.Spawn;

/**
 * Infused monsters (see {@link InfusedMonsterConfig}): like a champion, a regular monster has a small chance every time it spawns or respawns to be infused with an element ({@link #tryConvert(Npc, Spawn)}, called from {@code Spawn} right after the Mage roll). A spawn point that just
 * produced one skips the roll for its next {@link InfusedMonsterConfig#RESPAWN_COOLDOWN} spawns.
 * <ul>
 * <li>Combat: it attacks with its element, resists it and is weak to the opposite one (stat Funcs added in {@code Monster#startInfused(byte)}), has more HP and attack, and pulses its element's nova.</li>
 * <li>Loot: {@link #onAttackableKilled(Attackable, Player)} (called from {@code Attackable#doDie()} alongside the other post-kill hooks) drops attribute stones of its element, and at high levels a chance of an attribute crystal.</li>
 * </ul>
 */
public class InfusedMonsterManager
{
	protected InfusedMonsterManager()
	{
	}

	/**
	 * Rolls {@link InfusedMonsterConfig#SPAWN_CHANCE} for a freshly spawned npc and, if it hits, infuses it with an element.
	 * @param npc the npc that just spawned or respawned
	 * @param spawn the spawn point it came from
	 */
	public void tryConvert(Npc npc, Spawn spawn)
	{
		if (!InfusedMonsterConfig.ENABLED || (npc == null) || !npc.isMonster() || (spawn == null))
		{
			return;
		}

		// This spawn point was Infused recently - sit this spawn out.
		final int cooldown = spawn.getInfusedCooldown();
		if (cooldown > 0)
		{
			spawn.setInfusedCooldown(cooldown - 1);
			return;
		}

		final Monster monster = npc.asMonster();
		if (!isEligible(monster, spawn.getInstanceId()))
		{
			return;
		}

		// An ELEMENTAL_STORM-style hotzone modifier draws more of them.
		final HotzoneModifier modifier = HotzoneModifierManager.getInstance().getModifierFor(monster);
		final double chance = InfusedMonsterConfig.SPAWN_CHANCE * (modifier != null ? modifier.getInfusedSpawnMult() : 1.0);
		if ((Rnd.nextDouble() * 100) >= chance)
		{
			return;
		}

		final byte element = pickElement(monster);
		if (element == Elementals.NONE)
		{
			return;
		}

		monster.startInfused(element);
		spawn.setInfusedCooldown(InfusedMonsterConfig.RESPAWN_COOLDOWN);
	}

	/**
	 * @param monster the monster
	 * @param instanceId the instance it spawned in
	 * @return {@code true} if {@code monster} may become Infused: a plain monster of the right level, none of the other special kinds
	 */
	private static boolean isEligible(Monster monster, int instanceId)
	{
		if (monster.isQuestMonster() || monster.getTemplate().isUndying() || monster.isRaid() || monster.isRaidMinion() || (monster.getLeader() != null) || monster.isFakePlayer() || (monster instanceof Chest))
		{
			return false;
		}

		if (!InfusedMonsterConfig.ALLOW_IN_INSTANCES && (instanceId != 0))
		{
			return false;
		}

		if (!InfusedMonsterConfig.ALLOW_CHAMPIONS && (monster.getChampionTier() > 0))
		{
			return false;
		}

		// Keep the special monster types apart.
		if (InfusedMonsterConfig.EXCLUDED_NPC_IDS.contains(monster.getId()) || monster.isThief() || monster.isMageMonster() || monster.isWaveChallenge() || monster.getVariables().getBoolean("IS_ARENA_CHALLENGER", false) || monster.isHotzoneMiniboss())
		{
			return false;
		}

		final int level = monster.getLevel();
		return (level >= InfusedMonsterConfig.MIN_LEVEL) && (level <= InfusedMonsterConfig.MAX_LEVEL);
	}

	/**
	 * A monster whose template already attacks with an element keeps it {@link InfusedMonsterConfig#KEEP_TEMPLATE_ELEMENT_CHANCE}% of the time; otherwise the element is picked by {@link InfusedMonsterConfig#ELEMENT_WEIGHTS}.
	 * @param monster the monster
	 * @return the element, or {@link Elementals#NONE} if every weight is 0
	 */
	public static byte pickElement(Monster monster)
	{
		final byte templateElement = monster.getStat().getAttackElement();
		if ((templateElement >= 0) && (templateElement < InfusedMonsterConfig.ELEMENT_COUNT) && (Rnd.get(100) < InfusedMonsterConfig.KEEP_TEMPLATE_ELEMENT_CHANCE))
		{
			return templateElement;
		}

		double total = 0;
		for (double weight : InfusedMonsterConfig.ELEMENT_WEIGHTS)
		{
			total += weight;
		}
		if (total <= 0)
		{
			return Elementals.NONE;
		}

		double roll = Rnd.nextDouble() * total;
		for (byte element = 0; element < InfusedMonsterConfig.ELEMENT_COUNT; element++)
		{
			roll -= InfusedMonsterConfig.ELEMENT_WEIGHTS[element];
			if ((roll < 0) && (InfusedMonsterConfig.ELEMENT_WEIGHTS[element] > 0))
			{
				return element;
			}
		}

		// Rounding left the roll at the very end: the last element with a weight.
		for (byte element = InfusedMonsterConfig.ELEMENT_COUNT - 1; element >= 0; element--)
		{
			if (InfusedMonsterConfig.ELEMENT_WEIGHTS[element] > 0)
			{
				return element;
			}
		}
		return Elementals.NONE;
	}

	/**
	 * Drops the attribute stones (and maybe the attribute crystal) of the Infused monster's element.
	 * @param victim the attackable a player just killed
	 * @param killer the player credited with the kill
	 */
	public void onAttackableKilled(Attackable victim, Player killer)
	{
		if (!InfusedMonsterConfig.ENABLED || (victim == null) || (killer == null) || !victim.isMonster() || !victim.asMonster().isInfused())
		{
			return;
		}

		final Monster monster = victim.asMonster();
		final byte element = monster.getInfusedElement();
		if ((InfusedMonsterConfig.MAX_LEVEL_DIFFERENCE >= 0) && ((killer.getLevel() - monster.getLevel()) > InfusedMonsterConfig.MAX_LEVEL_DIFFERENCE))
		{
			if (InfusedMonsterConfig.MESSAGE)
			{
				killer.sendMessage(monster.getName() + " was too weak for you to leave its " + Elementals.getElementName(element) + " essence (more than " + InfusedMonsterConfig.MAX_LEVEL_DIFFERENCE + " levels below you).");
			}
			return;
		}

		final HotzoneModifier modifier = HotzoneModifierManager.getInstance().getModifierFor(monster);
		final int level = monster.getLevel();
		final StringBuilder dropped = new StringBuilder();

		final Map.Entry<Integer, int[]> stoneEntry = InfusedMonsterConfig.STONE_AMOUNTS.floorEntry(level);
		int stones = stoneEntry != null ? Rnd.get(stoneEntry.getValue()[0], stoneEntry.getValue()[1]) : 0;
		if (modifier != null)
		{
			stones += modifier.getInfusedExtraStones();
		}
		final int stoneId = getElementalItemId(element, ElementalItemType.STONE);
		if ((stones > 0) && (stoneId > 0))
		{
			monster.dropOrAutoLoot(killer, new ItemHolder(stoneId, stones));
			dropped.append(stones).append(' ').append(Elementals.getElementName(element)).append(stones > 1 ? " Stones" : " Stone");
		}

		final Map.Entry<Integer, Double> crystalEntry = InfusedMonsterConfig.CRYSTAL_CHANCES.floorEntry(level);
		final int crystalId = getElementalItemId(element, ElementalItemType.CRYSTAL);
		if ((crystalEntry != null) && (crystalId > 0) && ((Rnd.nextDouble() * 100) < crystalEntry.getValue()))
		{
			monster.dropOrAutoLoot(killer, new ItemHolder(crystalId, 1));
			dropped.append(dropped.length() > 0 ? " and " : "").append("a ").append(Elementals.getElementName(element)).append(" Crystal");
		}

		if (InfusedMonsterConfig.MESSAGE && (dropped.length() > 0))
		{
			killer.sendMessage(monster.getName() + " left behind " + dropped + "!");
		}
	}

	/**
	 * @param element the element
	 * @param type the kind of attribute item
	 * @return the item id of the {@code type} attribute item of {@code element} (from {@code data/ElementalAttributeData.xml}), or 0 if there is none
	 */
	private static int getElementalItemId(byte element, ElementalItemType type)
	{
		final ElementalItemHolder holder = ElementalAttributeData.getInstance().getElementalItem(element, type);
		return holder != null ? holder.getItemId() : 0;
	}

	public static InfusedMonsterManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final InfusedMonsterManager INSTANCE = new InfusedMonsterManager();
	}
}
