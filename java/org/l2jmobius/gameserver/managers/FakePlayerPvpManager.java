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

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.config.custom.FakePlayerPvpConfig;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.sql.CharInfoTable;
import org.l2jmobius.gameserver.data.xml.FakePlayerData;
import org.l2jmobius.gameserver.data.xml.FakePlayerPvpData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.SkillCategory;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.holders.SkillHolder;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.model.zone.ZoneType;
import org.l2jmobius.gameserver.model.zone.type.ArenaZone;
import org.l2jmobius.gameserver.model.zone.type.BossZone;
import org.l2jmobius.gameserver.model.zone.type.CastleZone;
import org.l2jmobius.gameserver.model.zone.type.ClanHallZone;
import org.l2jmobius.gameserver.model.zone.type.FortZone;
import org.l2jmobius.gameserver.model.zone.type.JailZone;
import org.l2jmobius.gameserver.model.zone.type.NoPvPZone;
import org.l2jmobius.gameserver.model.zone.type.OlympiadStadiumZone;
import org.l2jmobius.gameserver.model.zone.type.PeaceZone;
import org.l2jmobius.gameserver.model.zone.type.SiegeZone;
import org.l2jmobius.gameserver.model.zone.type.TownZone;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;

/**
 * Roaming fake players (see {@link FakePlayerPvpConfig}): like a champion, a regular monster has a small chance every time it spawns or respawns to be replaced by a fake player of the same level ({@link #tryReplace}, called from {@code Spawn} right before the monster
 * enters the world). The fake player is built like a real character of that level ({@link FakePlayerPvpFactory}), hunts the monsters around the spawn point ({@link org.l2jmobius.gameserver.ai.FakePlayerPvpAI}) and brings PvP to a quiet server:
 * <ul>
 * <li>monsters can't push it below {@link FakePlayerPvpConfig#MONSTER_DAMAGE_FLOOR}% HP, only players can kill it ({@link #limitDamage}, called from {@code Attackable#reduceCurrentHp});</li>
 * <li>a player that kills the monster it is fighting becomes its target ({@link #onAttackableKilled}, called from {@code Attackable#doDie});</li>
 * <li>a player that attacks it becomes its target ({@link #onFakePlayerAttacked}, called from its AI).</li>
 * </ul>
 * The replaced monster stays counted by its spawn, outside the world, until the fake player dies or logs off ({@link #onFakePlayerDecay}, called from {@code Npc#onDecay}); then the monster respawns after its normal delay.
 */
public class FakePlayerPvpManager
{
	private static final Logger LOGGER = Logger.getLogger(FakePlayerPvpManager.class.getName());
	
	/** Npc ids of the generated templates, far above any datapack id. */
	private static final int FIRST_NPC_ID = 9_500_000;
	/** Hate put on a player the fake player decided to fight, so that player stays its target over any monster. */
	public static final long PVP_HATE = 1_000_000;
	/** Greater Healing Potion effect. */
	private static final int POTION_SKILL_ID = 2037;
	
	private static final String[] NAME_PREFIXES =
	{
		"Dark", "Shadow", "Blood", "Night", "Storm", "Frost", "Fire", "Iron", "Silver", "Death", "Wolf", "Dragon", "Soul", "Ghost", "Rage", "Grim", "Holy", "Wild", "Mad", "Evil", "Sky", "Moon", "Sun", "Stone", "Doom", "Hell", "Ice", "Venom", "Chaos", "Neo", "Lil", "Big", "Sweet", "Crazy", "Lucky", "Swift", "Silent", "Red", "Black", "Wicked"
	};
	private static final String[] NAME_SUFFIXES =
	{
		"Blade", "Slayer", "Hunter", "Knight", "Mage", "Lord", "Walker", "Rider", "Heart", "Fang", "Storm", "Wolf", "Killer", "Master", "Reaper", "Soul", "Bane", "Fury", "Shot", "Arrow", "Fist", "Eye", "Wind", "Star", "Born", "King", "Queen", "Boy", "Girl", "Dude", "Pvp", "Hawk", "Viper", "Rose"
	};
	private static final String[] NAMES =
	{
		"Aeris", "Kalista", "Morgana", "Varka", "Tiran", "Drakon", "Elyssa", "Nyx", "Raiden", "Kaelthas", "Lyra", "Zephyr", "Seraph", "Orin", "Thorne", "Valen", "Ragnar", "Freya", "Loki", "Sylas", "Kiera", "Ashe", "Draven", "Riven", "Vex", "Zed", "Kain", "Lucian", "Selene", "Artemis", "Baal", "Cyra", "Dante", "Eira", "Fenrir", "Gorn", "Hilda", "Ivar", "Jinx", "Kira", "Lestat", "Mira", "Nero", "Odessa", "Pyra", "Quinn", "Rhea", "Sven", "Talon", "Ursa", "Vesna", "Wulf", "Xena", "Yuna", "Zara", "Bjorn", "Cassia", "Darius", "Elena", "Garrick", "Helga", "Isolde", "Kharn", "Leona", "Magnus", "Nadia", "Osric", "Petra", "Roran", "Saria", "Tyra", "Viktor"
	};
	
	private static final String[] TAUNTS_KILL_STEAL =
	{
		"ks?",
		"wtf ks",
		"that was my mob",
		"nice ks...",
		"ks noob",
		"really? ks?",
		"my mob, go away",
		"ok you asked for it"
	};
	private static final String[] TAUNTS_ATTACKED =
	{
		"?",
		"really?",
		"u want die?",
		"lol",
		"come on then",
		"bad idea",
		"wtf",
		"ok lets go"
	};
	private static final String[] TAUNTS_KILL =
	{
		"gg",
		"ez",
		"noob",
		"next time",
		"bb",
		"lol",
		"stay down"
	};
	
	private final AtomicInteger _nextNpcId = new AtomicInteger(FIRST_NPC_ID);
	private final Set<Npc> _fakePlayers = ConcurrentHashMap.newKeySet();
	private final Set<String> _names = ConcurrentHashMap.newKeySet();
	
	protected FakePlayerPvpManager()
	{
		if (!FakePlayerPvpConfig.ENABLED)
		{
			LOGGER.info(getClass().getSimpleName() + ": Disabled.");
			return;
		}
		
		if (!FakePlayersConfig.FAKE_PLAYERS_ENABLED)
		{
			LOGGER.info(getClass().getSimpleName() + ": Disabled, fake players (EnableFakePlayers) are off.");
			return;
		}
		
		FakePlayerPvpData.getInstance();
		ThreadPool.scheduleAtFixedRate(this::maintain, 30000, 30000);
	}
	
	/**
	 * @return {@code true} if roaming fake players can spawn
	 */
	public boolean isEnabled()
	{
		return FakePlayerPvpConfig.ENABLED && FakePlayersConfig.FAKE_PLAYERS_ENABLED && !FakePlayerPvpData.getInstance().getBuilds().isEmpty();
	}
	
	/**
	 * Rolls {@link FakePlayerPvpConfig#SPAWN_CHANCE} for a monster that is about to enter the world and, if it hits, spawns a roaming fake player in its place. The monster is then kept out of the world, still counted by {@code spawn}, until the fake player is gone.
	 * @param npc the monster that is spawning
	 * @param spawn its spawn point
	 * @param x the spawn x
	 * @param y the spawn y
	 * @param z the spawn z
	 * @return {@code true} if a fake player took the monster's place and the monster must not be spawned
	 */
	public boolean tryReplace(Npc npc, Spawn spawn, int x, int y, int z)
	{
		if ((npc == null) || (spawn == null) || (npc.getClass() != Monster.class) || npc.isFakePlayer() || !isEnabled())
		{
			return false;
		}
		
		// Only regular, respawning world monsters.
		final Monster monster = npc.asMonster();
		if (monster.isQuestMonster() || monster.getTemplate().isUndying() || monster.isRaid() || monster.isRaidMinion() || (monster.getLeader() != null) || NpcData.getMasterMonsterIDs().contains(monster.getId()))
		{
			return false;
		}
		
		if (!spawn.isRespawnEnabled() || (spawn.getRespawnDelay() <= 0) || WalkingManager.getInstance().isTargeted(monster))
		{
			return false;
		}
		
		if (!FakePlayerPvpConfig.ALLOW_IN_INSTANCES && (spawn.getInstanceId() != 0))
		{
			return false;
		}
		
		final int level = monster.getLevel();
		if ((level < FakePlayerPvpConfig.MIN_LEVEL) || (level > FakePlayerPvpConfig.MAX_LEVEL) || FakePlayerPvpConfig.EXCLUDED_NPC_IDS.contains(monster.getId()))
		{
			return false;
		}
		
		// This spawn point had a fake player recently - sit this spawn out.
		final int cooldown = spawn.getFakePlayerCooldown();
		if (cooldown > 0)
		{
			spawn.setFakePlayerCooldown(cooldown - 1);
			return false;
		}
		
		if ((Rnd.nextDouble() * 100) >= FakePlayerPvpConfig.SPAWN_CHANCE)
		{
			return false;
		}
		
		if ((FakePlayerPvpConfig.MAX_ALIVE > 0) && (_fakePlayers.size() >= FakePlayerPvpConfig.MAX_ALIVE))
		{
			return false;
		}
		
		if (!isAllowedLocation(x, y, z, spawn.getInstanceId()))
		{
			return false;
		}
		
		final int fakeLevel = Math.max(1, Math.min(85, level + (FakePlayerPvpConfig.LEVEL_VARIANCE > 0 ? Rnd.get(-FakePlayerPvpConfig.LEVEL_VARIANCE, FakePlayerPvpConfig.LEVEL_VARIANCE) : 0)));
		final Npc fake = spawnFakePlayer(FakePlayerPvpData.getInstance().getRandomBuild(), fakeLevel, x, y, z, spawn.getInstanceId(), monster, spawn);
		if (fake == null)
		{
			return false;
		}
		
		spawn.setFakePlayerCooldown(FakePlayerPvpConfig.RESPAWN_COOLDOWN);
		
		// Out of the world until the fake player is gone, see onFakePlayerDecay().
		monster.setDead(true);
		monster.setDecayed(true);
		return true;
	}
	
	private boolean isAllowedLocation(int x, int y, int z, int instanceId)
	{
		for (ZoneType zone : ZoneManager.getInstance().getZones(x, y, z))
		{
			if ((zone instanceof PeaceZone) || (zone instanceof TownZone) || (zone instanceof NoPvPZone) || (zone instanceof SiegeZone) || (zone instanceof ArenaZone) || (zone instanceof OlympiadStadiumZone) || (zone instanceof JailZone) || (zone instanceof BossZone) || (zone instanceof CastleZone) || (zone instanceof FortZone) || (zone instanceof ClanHallZone))
			{
				return false;
			}
		}
		
		if (FakePlayerPvpConfig.MIN_DISTANCE > 0)
		{
			final long minDistanceSq = (long) FakePlayerPvpConfig.MIN_DISTANCE * FakePlayerPvpConfig.MIN_DISTANCE;
			for (Npc fake : _fakePlayers)
			{
				final long dx = fake.getX() - x;
				final long dy = fake.getY() - y;
				if ((fake.getInstanceId() == instanceId) && (((dx * dx) + (dy * dy)) < minDistanceSq))
				{
					return false;
				}
			}
		}
		
		return true;
	}
	
	/**
	 * Creates and spawns a roaming fake player.
	 * @param build the build, {@code null} for a random one
	 * @param level its level
	 * @param x the x
	 * @param y the y
	 * @param z the z
	 * @param instanceId the instance
	 * @param replacedMonster the monster it replaces, {@code null} for none
	 * @param replacedSpawn the spawn of {@code replacedMonster}
	 * @return the fake player, or {@code null} if it could not be created
	 */
	public Npc spawnFakePlayer(FakePlayerPvpBuild build, int level, int x, int y, int z, int instanceId, Npc replacedMonster, Spawn replacedSpawn)
	{
		final FakePlayerPvpBuild usedBuild = build != null ? build : FakePlayerPvpData.getInstance().getRandomBuild();
		if (usedBuild == null)
		{
			return null;
		}
		
		final String name = generateName();
		try
		{
			final NpcTemplate template = FakePlayerPvpFactory.createTemplate(usedBuild, level, _nextNpcId.getAndIncrement(), name);
			if (template == null)
			{
				_names.remove(name.toLowerCase());
				return null;
			}
			
			final FakePlayerPvpProfile profile = template.getFakePlayerPvpProfile();
			profile.setReplacedMonster(replacedMonster, replacedSpawn);
			profile.setSpawnTime(System.currentTimeMillis());
			
			final Spawn spawn = new Spawn(template);
			spawn.setXYZ(x, y, z);
			spawn.setHeading(-1);
			spawn.setAmount(1);
			spawn.setInstanceId(instanceId);
			spawn.setRespawnDelay(0);
			spawn.stopRespawn();
			SpawnTable.getInstance().addSpawn(spawn);
			
			final Npc fake = spawn.doSpawn(false);
			if (fake == null)
			{
				SpawnTable.getInstance().removeSpawn(spawn);
				FakePlayerData.getInstance().removeFakePlayer(name);
				_names.remove(name.toLowerCase());
				return null;
			}
			
			_fakePlayers.add(fake);
			
			// Toggles on, buffed, full HP/MP - like a player that just arrived.
			for (Skill toggle : profile.getSkills(SkillCategory.TOGGLE))
			{
				toggle.applyEffects(fake, fake);
			}
			refreshBuffs(fake, profile);
			fake.setCurrentHpMp(fake.getMaxHp(), fake.getMaxMp());
			fake.broadcastInfo();
			return fake;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not spawn fake player " + usedBuild.getName() + " level " + level + ".", e);
			FakePlayerData.getInstance().removeFakePlayer(name);
			_names.remove(name.toLowerCase());
			return null;
		}
	}
	
	/**
	 * Called when a roaming fake player leaves the world (dead body decayed, lifetime over, deleted). Gives its spot back to the monster it replaced, which respawns after its normal delay.
	 * @param fake the fake player
	 */
	public void onFakePlayerDecay(Npc fake)
	{
		if (!_fakePlayers.remove(fake))
		{
			return;
		}
		
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		final Npc monster = profile.takeReplacedMonster();
		final Spawn replacedSpawn = profile.getReplacedSpawn();
		if ((monster != null) && (replacedSpawn != null))
		{
			replacedSpawn.decreaseCount(monster);
		}
		
		final Spawn spawn = fake.getSpawn();
		if (spawn != null)
		{
			SpawnTable.getInstance().removeSpawn(spawn);
		}
		
		FakePlayerData.getInstance().removeFakePlayer(fake.getName());
		_names.remove(fake.getName().toLowerCase());
	}
	
	/**
	 * Monsters (and anything else that isn't a player or a player's summon) cannot bring a roaming fake player below {@link FakePlayerPvpConfig#MONSTER_DAMAGE_FLOOR}% HP.
	 * @param fake the fake player being hit
	 * @param damage the damage
	 * @param attacker who deals it, can be {@code null}
	 * @return the damage it actually takes
	 */
	public double limitDamage(Attackable fake, double damage, Creature attacker)
	{
		if ((attacker != null) && attacker.isPlayable())
		{
			return damage;
		}
		
		final double floor = (fake.getMaxHp() * FakePlayerPvpConfig.MONSTER_DAMAGE_FLOOR) / 100.0;
		final double allowed = fake.getCurrentHp() - floor;
		return allowed <= 0 ? 0 : Math.min(damage, allowed);
	}
	
	/**
	 * Called when a player kills an attackable: killing a roaming fake player rewards the loot of the monster it replaced, and killing a monster a roaming fake player was fighting makes it attack the player.
	 * @param victim the attackable that died
	 * @param killer the player credited with the kill
	 */
	public void onAttackableKilled(Attackable victim, Player killer)
	{
		if ((victim == null) || (killer == null))
		{
			return;
		}
		
		if (victim.isPvpFakePlayer())
		{
			rewardKill(victim, killer);
			return;
		}
		
		if (!FakePlayerPvpConfig.REVENGE_ON_KILL_STEAL || !victim.isMonster() || _fakePlayers.isEmpty())
		{
			return;
		}
		
		World.getInstance().forEachVisibleObjectInRange(victim, Attackable.class, FakePlayerPvpConfig.REVENGE_RANGE, fake ->
		{
			if (!fake.isPvpFakePlayer() || fake.isDead() || !fake.hasAI() || (fake.getHating(killer) >= PVP_HATE))
			{
				return;
			}
			
			// Only the fake players that were fighting this monster care.
			if ((fake.getAI().getAttackTarget() == victim) || (fake.getTarget() == victim))
			{
				startFight(fake, killer, TAUNTS_KILL_STEAL);
			}
		});
	}
	
	/**
	 * Called by the fake player AI when it gets hit: a player that attacks it becomes its target.
	 * @param fake the fake player
	 * @param attacker the attacker
	 */
	public void onFakePlayerAttacked(Attackable fake, Creature attacker)
	{
		final Player player = attacker != null ? attacker.asPlayer() : null;
		if ((player == null) || fake.isDead() || (fake.getHating(player) >= PVP_HATE))
		{
			return;
		}
		
		startFight(fake, player, TAUNTS_ATTACKED);
	}
	
	/**
	 * Called by the fake player AI when the player it was fighting dies.
	 * @param fake the fake player
	 */
	public void onPlayerDefeated(Attackable fake)
	{
		taunt(fake, TAUNTS_KILL);
	}
	
	/**
	 * Makes a fake player drop what it is doing and fight {@code player}.
	 * @param fake the fake player
	 * @param player the player
	 * @param taunts what it may say about it
	 */
	private void startFight(Attackable fake, Player player, String[] taunts)
	{
		if (player.isDead() || player.isInvisible() || (player.isGM() && !player.getAccessLevel().canTakeAggro()))
		{
			return;
		}
		
		fake.addDamageHate(player, 0, PVP_HATE);
		fake.getAI().setIntention(Intention.ATTACK, player);
		taunt(fake, taunts);
	}
	
	private void taunt(Npc fake, String[] taunts)
	{
		if ((FakePlayerPvpConfig.TAUNT_CHANCE <= 0) || (Rnd.get(100) >= FakePlayerPvpConfig.TAUNT_CHANCE))
		{
			return;
		}
		
		final String text = taunts[Rnd.get(taunts.length)];
		ThreadPool.schedule(() ->
		{
			if (!fake.isDead() && fake.isSpawned())
			{
				fake.broadcastPacket(new CreatureSay(fake, ChatType.GENERAL, fake.getName(), text));
			}
		}, Rnd.get(800, 2500));
	}
	
	private void rewardKill(Attackable fake, Player killer)
	{
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		final Npc monster = profile.getReplacedMonster();
		if (monster == null)
		{
			return;
		}
		
		if ((FakePlayerPvpConfig.REWARD_EXP_SP_MULTIPLIER > 0) && (Math.abs(killer.getLevel() - fake.getLevel()) < RatesConfig.MONSTER_EXP_MAX_LEVEL_DIFFERENCE))
		{
			final double exp = monster.getExpReward(killer.getLevel()) * FakePlayerPvpConfig.REWARD_EXP_SP_MULTIPLIER;
			final double sp = monster.getSpReward(killer.getLevel()) * FakePlayerPvpConfig.REWARD_EXP_SP_MULTIPLIER;
			if ((exp > 0) || (sp > 0))
			{
				killer.addExpAndSp(exp, sp);
			}
		}
		
		if (FakePlayerPvpConfig.REWARD_DROPS)
		{
			fake.doItemDrop(monster.getTemplate(), killer);
		}
	}
	
	/**
	 * A potion: below {@link FakePlayerPvpConfig#POTION_HP_PERCENT}% HP a fake player heals {@link FakePlayerPvpConfig#POTION_HEAL_PERCENT}% of its max HP, once every {@link FakePlayerPvpConfig#POTION_REUSE} ms.
	 * @param fake the fake player
	 * @return {@code true} if it drank one
	 */
	public boolean tryPotion(Npc fake)
	{
		if (FakePlayerPvpConfig.POTION_HEAL_PERCENT <= 0)
		{
			return false;
		}
		
		final double maxHp = fake.getMaxHp();
		if (fake.isDead() || (fake.getCurrentHp() >= ((maxHp * FakePlayerPvpConfig.POTION_HP_PERCENT) / 100.0)))
		{
			return false;
		}
		
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		final long now = System.currentTimeMillis();
		if (now < profile.getNextPotionTime())
		{
			return false;
		}
		
		profile.setNextPotionTime(now + FakePlayerPvpConfig.POTION_REUSE);
		fake.setCurrentHp(Math.min(maxHp, fake.getCurrentHp() + ((maxHp * FakePlayerPvpConfig.POTION_HEAL_PERCENT) / 100.0)));
		fake.broadcastPacket(new MagicSkillUse(fake, fake, POTION_SKILL_ID, 1, 0, 0));
		return true;
	}
	
	/**
	 * Puts back the buffs (from other players) that ran out.
	 * @param fake the fake player
	 * @param profile its profile
	 */
	private void refreshBuffs(Npc fake, FakePlayerPvpProfile profile)
	{
		if (!FakePlayerPvpConfig.BUFFS_ENABLED)
		{
			return;
		}
		
		for (SkillHolder holder : profile.getBuffs())
		{
			final Skill skill = holder.getSkill();
			if ((skill != null) && !fake.isAffectedBySkill(skill.getId()))
			{
				skill.applyEffects(fake, fake);
			}
		}
	}
	
	/**
	 * Every 30 seconds: rebuff idle fake players and log off the ones that lived long enough.
	 */
	private void maintain()
	{
		final long now = System.currentTimeMillis();
		for (Npc fake : _fakePlayers)
		{
			try
			{
				if (fake.isDead())
				{
					continue;
				}
				
				// Deleted without going through onDecay (should not happen) - forget it.
				if (!fake.isSpawned() && fake.isDecayed())
				{
					onFakePlayerDecay(fake);
					continue;
				}
				
				if (fake.isInCombat() || fake.isCastingNow() || (fake.isAttackable() && !fake.asAttackable().getAggroList().isEmpty() && (fake.asAttackable().getMostHated() != null)))
				{
					continue;
				}
				
				final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
				if ((FakePlayerPvpConfig.LIFETIME > 0) && ((now - profile.getSpawnTime()) > (FakePlayerPvpConfig.LIFETIME * 1000L)))
				{
					fake.deleteMe();
					continue;
				}
				
				refreshBuffs(fake, profile);
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Problem while maintaining " + fake.getName() + ".", e);
			}
		}
	}
	
	/**
	 * @return a name no character or fake player uses
	 */
	private String generateName()
	{
		for (int attempt = 0; attempt < 100; attempt++)
		{
			String name;
			if (Rnd.get(100) < 55)
			{
				name = NAME_PREFIXES[Rnd.get(NAME_PREFIXES.length)] + NAME_SUFFIXES[Rnd.get(NAME_SUFFIXES.length)];
			}
			else
			{
				name = NAMES[Rnd.get(NAMES.length)];
			}
			
			final int style = Rnd.get(100);
			if (style < 30)
			{
				name += Rnd.get(1, 99);
			}
			else if (style < 35)
			{
				name = "xX" + name + "Xx";
			}
			else if (style < 45)
			{
				name = name.toLowerCase();
			}
			
			if (name.length() > 16)
			{
				name = name.substring(0, 16);
			}
			
			if ((CharInfoTable.getInstance().getIdByName(name) > 0) || (FakePlayerData.getInstance().getProperName(name) != null) || !_names.add(name.toLowerCase()))
			{
				continue;
			}
			
			return name;
		}
		
		// Very unlikely: fall back to a numbered name.
		final String name = "Player" + _nextNpcId.get();
		_names.add(name.toLowerCase());
		return name;
	}
	
	/**
	 * @return the roaming fake players currently in the world
	 */
	public Set<Npc> getFakePlayers()
	{
		return Collections.unmodifiableSet(_fakePlayers);
	}
	
	public static FakePlayerPvpManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final FakePlayerPvpManager INSTANCE = new FakePlayerPvpManager();
	}
}
