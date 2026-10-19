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
package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.cache.HtmCache;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.config.custom.AdventurersGuideConfig;
import org.l2jmobius.gameserver.config.custom.ClassTransferChallengeConfig;
import org.l2jmobius.gameserver.config.custom.ClassTransferConfig;
import org.l2jmobius.gameserver.config.custom.CustomBuffConfig;
import org.l2jmobius.gameserver.config.custom.LuckyLootConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.sql.ClassTransferData;
import org.l2jmobius.gameserver.data.sql.ClassTransferHolder;
import org.l2jmobius.gameserver.data.xml.ClassListData;
import org.l2jmobius.gameserver.data.xml.DynamicQuestData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.holders.player.ClassInfoHolder;
import org.l2jmobius.gameserver.model.actor.instance.Chest;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.dynamicquest.NoticeBoardSite;
import org.l2jmobius.gameserver.model.events.Containers;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.OnNightPhaseChange;
import org.l2jmobius.gameserver.model.events.holders.actor.creature.OnCreatureDeath;
import org.l2jmobius.gameserver.model.events.holders.actor.npc.attackable.OnAttackableKill;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLevelChanged;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogin;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerPressTutorialMark;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerProfessionChange;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.model.guide.GuideCategory;
import org.l2jmobius.gameserver.model.guide.GuideLesson;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.holders.Elementals;
import org.l2jmobius.gameserver.model.nightcycle.NightPhase;
import org.l2jmobius.gameserver.model.script.QuestSound;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.model.variables.PlayerVariables;
import org.l2jmobius.gameserver.network.serverpackets.TutorialShowHtml;
import org.l2jmobius.gameserver.network.serverpackets.TutorialShowQuestionMark;

/**
 * The Adventurer's Guide: a tutorial for this server's own systems, in the style of the retail Tutorial quest.
 * <ul>
 * <li><b>Lessons</b> - one per system ({@link GuideLesson}): the class transfers (the classic quests and the Class Master trials), every monster variant, the Notice Board and the dynamic quests, hidden quests, the Survival Arena, learning buffs with Champion Medals, the
 * Community Board and more. A lesson comes once per character.</li>
 * <li><b>When</b> - at the level its feature becomes available to the character (the monster variants follow their own minimum level), or earlier by its event: the first kill of such a monster, the first Champion Medal or Sealed Cache carried, the first death to a monster, the
 * first nightfall, a class transfer, a quest taken. Talking to the npc of a system (the Arena Master, a Notice Board) or using a Champion Medal opens its lesson at once.</li>
 * <li><b>How</b> - like retail: the tutorial question mark blinks with the tutorial sound, and clicking it opens the lesson in the tutorial window. A lesson not read yet keeps its question mark over a relog.</li>
 * <li><b>Guidebook</b> - every lesson unlocked can be read again with {@code .guide} (tutorial window) or on the Community Board ({@code _bbsguide}), with the lessons still to come and their level.</li>
 * </ul>
 * Lessons read and waiting are kept in the player variables {@code GUIDE_SEEN} and {@code GUIDE_PENDING}. Pages are in {@code data/html/guide}; links in them use the bypass {@value #BYPASS} (rewritten for the Community Board).
 */
public class AdventurersGuideManager
{
	private static final Logger LOGGER = Logger.getLogger(AdventurersGuideManager.class.getName());

	/** The question mark of a lesson is this + the lesson's ordinal. */
	public static final int QUESTION_MARK_BASE = 9400;
	/** The bypass of the links in the guide's pages (handlers.bypass.npc.AdventurersGuideLink). */
	public static final String BYPASS = "guide";
	/** The Community Board page of the guide (handlers.bypass.communityboard.GuideBoard). */
	public static final String BOARD_BYPASS = "_bbsguide";

	private static final String VAR_SEEN = "GUIDE_SEEN";
	private static final String VAR_PENDING = "GUIDE_PENDING";
	/** The opening line of a lesson waiting to be read is kept in this + the lesson's name. */
	private static final String VAR_INTRO = "GUIDE_INTRO_";
	private static final String HTML_PATH = "data/html/guide/";
	private static final String RETAIL_TUTORIAL_PATH = "data/scripts/quests/Q00255_Tutorial/";

	/** After login, the welcome and the waiting question marks come once the client has loaded the world. */
	private static final long LOGIN_DELAY = 8000;
	/** Party members within this range of a kill learn about the monster too. */
	private static final int PARTY_RANGE = 1500;

	private static final int ARENA_SHOP_NPC_ID = 90000;
	private static final int HOTZONE_TELEPORTER_NPC_ID = 900004;
	private static final int MASTER_BLACKSMITH_NPC_ID = 900010;
	private static final int[] SEALED_CACHES_FALLBACK =
	{
		6492,
		6499,
		6509
	};

	private static final String COLOR_NEW = "LEVEL";
	private static final String COLOR_LOCKED = "808080";

	protected AdventurersGuideManager()
	{
		if (!AdventurersGuideConfig.ENABLED)
		{
			return;
		}

		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_LOGIN, (OnPlayerLogin event) -> onLogin(event.getPlayer()), this));
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_LEVEL_CHANGED, (OnPlayerLevelChanged event) -> onLevelChanged(event), this));
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_PROFESSION_CHANGE, (OnPlayerProfessionChange event) -> checkLevelLessons(event.getPlayer(), 0, event.getPlayer().getLevel()), this));
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_PRESS_TUTORIAL_MARK, (OnPlayerPressTutorialMark event) -> onQuestionMark(event.getPlayer(), event.getMarkId()), this));
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_CREATURE_DEATH, (OnCreatureDeath event) -> onPlayerDeath(event), this));
		Containers.Monsters().addListener(new ConsumerEventListener(Containers.Monsters(), EventType.ON_ATTACKABLE_KILL, (OnAttackableKill event) -> onMonsterKill(event), this));
		Containers.Global().addListener(new ConsumerEventListener(Containers.Global(), EventType.ON_NIGHT_PHASE_CHANGE, (OnNightPhaseChange event) -> onNightPhase(event.getPhase()), this));
		LOGGER.info(getClass().getSimpleName() + ": The Adventurer's Guide is enabled (" + GuideLesson.values().length + " lessons).");
	}

	// ---------------------------------------------------------------------------------------------
	// Triggers
	// ---------------------------------------------------------------------------------------------

	private void onLogin(Player player)
	{
		ThreadPool.schedule(() ->
		{
			if (!player.isOnline() || player.isInOfflineMode())
			{
				return;
			}

			if (AdventurersGuideConfig.WELCOME_WINDOW && !isSeen(player, GuideLesson.WELCOME))
			{
				showLesson(player, GuideLesson.WELCOME);
				player.sendPacket(QuestSound.ITEMSOUND_QUEST_TUTORIAL.getPacket());
			}

			// The question marks of the lessons not read yet.
			for (GuideLesson lesson : getPending(player))
			{
				player.sendPacket(new TutorialShowQuestionMark(QUESTION_MARK_BASE + lesson.ordinal()));
			}

			checkItems(player);
			if (NightCycleManager.getInstance().getPhase().isNight())
			{
				trigger(player, GuideLesson.NIGHT, "Night has fallen over the land.");
			}
		}, LOGIN_DELAY);
	}

	private void onLevelChanged(OnPlayerLevelChanged event)
	{
		if (event.getNewLevel() > event.getOldLevel())
		{
			checkLevelLessons(event.getPlayer(), event.getOldLevel(), event.getNewLevel());
		}
	}

	/**
	 * Raises the announced lessons whose level is above {@code fromLevel} and up to {@code toLevel}. The other lessons wait for their moment.
	 * @param player the player
	 * @param fromLevel the level already passed (0 to look at every level up to {@code toLevel})
	 * @param toLevel the level reached
	 */
	private void checkLevelLessons(Player player, int fromLevel, int toLevel)
	{
		for (GuideLesson lesson : GuideLesson.values())
		{
			final int level = lesson.getLevel();
			if (!lesson.isAnnounced() || (level <= fromLevel) || (level > toLevel))
			{
				continue;
			}

			// After a class transfer (fromLevel 0) only the class lessons are looked at again: the next one may be due already.
			if ((fromLevel == 0) && (lesson.getClassTier() == 0))
			{
				continue;
			}
			trigger(player, lesson, lesson.getClassTier() > 0 ? null : "You have reached level " + level + ".");
		}
	}

	private void onMonsterKill(OnAttackableKill event)
	{
		final Player killer = event.getAttacker();
		final Attackable target = event.getTarget();
		if ((killer == null) || (target == null) || !target.isMonster())
		{
			return;
		}

		// What the player may wonder about after this kill, and the line that opens its lesson ("%s" is who killed it).
		final Map<GuideLesson, String> lessons = new EnumMap<>(GuideLesson.class);
		final Monster monster = target.asMonster();
		final String name = monster.getName();
		if (monster instanceof Chest)
		{
			lessons.put(GuideLesson.TREASURE_CHESTS, "%s slain a mimic, " + name + ".");
		}
		if (monster.getChampionTier() > 0)
		{
			lessons.put(GuideLesson.CHAMPIONS, "%s slain a Champion monster, " + name + "!");
		}
		if (monster.isMageMonster())
		{
			lessons.put(GuideLesson.MAGE_MONSTERS, "%s slain a Mage monster, " + name + ".");
		}
		if (monster.isThief())
		{
			lessons.put(GuideLesson.THIEVES, "%s caught a Thief, " + name + ", with its bag " + monster.getThiefPercent() + "% full!");
		}
		if (monster.isInfused())
		{
			lessons.put(GuideLesson.INFUSED, "%s slain " + name + ", a monster infused with " + Elementals.getElementName(monster.getInfusedElement()) + ".");
		}
		if (monster.isResonant())
		{
			lessons.put(GuideLesson.RESONANT, "%s slain " + name + ", a Resonant monster.");
		}
		if (monster.isWaveChallenge())
		{
			lessons.put(GuideLesson.WAVE_CHALLENGES, "%s beaten every wave of " + name + "'s Wave Challenge!");
		}
		if (monster.getVariables().getString("PASSIVE_AVE", null) != null)
		{
			lessons.put(GuideLesson.MONSTER_TRAITS, "%s slain " + name + ", a monster glowing with hidden strengths.");
		}
		if (monster.getVariables().getBoolean("NEMESIS_FLAG", false))
		{
			lessons.put(GuideLesson.NEMESIS, "%s slain " + name + ", a Nemesis!");
		}
		if (monster.isHotzoneMiniboss())
		{
			lessons.put(GuideLesson.HOT_ZONES, "%s slain a Hotzone Menace, " + name + "!");
		}
		else if ((monster.getInstanceId() == 0) && (HotzoneModifierManager.getInstance().getZoneModifierFor(monster) != null))
		{
			lessons.put(GuideLesson.HOT_ZONES, "You are hunting in an active Hot Zone.");
		}

		final Party party = killer.getParty();
		if (party == null)
		{
			onKill(killer, lessons, true);
			return;
		}

		for (Player member : party.getMembers())
		{
			if ((member == killer) || member.isInsideRadius3D(monster, PARTY_RANGE))
			{
				onKill(member, lessons, member == killer);
			}
		}
	}

	private void onKill(Player player, Map<GuideLesson, String> lessons, boolean killer)
	{
		for (Entry<GuideLesson, String> entry : lessons.entrySet())
		{
			trigger(player, entry.getKey(), String.format(entry.getValue(), killer ? "You have" : "Your party has"));
		}
		checkItems(player);
	}

	/**
	 * Raises a lesson for everyone fighting a monster, when it does something they may wonder about: flies into a rage, rises for its next wave, its soul flees...
	 * @param monster the monster
	 * @param lesson the lesson
	 * @param intro the line that opens the lesson
	 * @param extra a player to include who may not be on its aggro list (who struck it), or {@code null}
	 */
	public void onMonsterEvent(Monster monster, GuideLesson lesson, String intro, Player extra)
	{
		if (!AdventurersGuideConfig.ENABLED || (monster == null) || !lesson.isEnabled())
		{
			return;
		}

		final Set<Player> players = new HashSet<>();
		for (Creature attacker : monster.getAggroList().keySet())
		{
			final Player player = attacker.asPlayer();
			if (player != null)
			{
				players.add(player);
			}
		}
		if (extra != null)
		{
			players.add(extra);
		}

		// Away from the monster's own locks.
		ThreadPool.execute(() ->
		{
			for (Player player : players)
			{
				trigger(player, lesson, intro);
			}
		});
	}

	/**
	 * The lessons of an item: the first Champion Medal or Sealed Cache carried. Checked after kills and at login, so nothing has to watch every item a player gets.
	 * @param player the player
	 */
	private void checkItems(Player player)
	{
		if (CustomBuffConfig.ENABLE && !isSeen(player, GuideLesson.BUFFS) && (player.getInventory().getItemByItemId(CustomBuffConfig.ITEM_ID) != null))
		{
			trigger(player, GuideLesson.BUFFS, "You have found a " + getItemName(CustomBuffConfig.ITEM_ID) + "!");
		}

		if (!isSeen(player, GuideLesson.LUCK))
		{
			for (int itemId : getSealedCacheIds())
			{
				if (player.getInventory().getItemByItemId(itemId) != null)
				{
					trigger(player, GuideLesson.LUCK, "You have found a " + getItemName(itemId) + "!");
					break;
				}
			}
		}
	}

	private void onPlayerDeath(OnCreatureDeath event)
	{
		final Creature killer = event.getAttacker();
		if (!RatesConfig.NEMESIS_SYSTEM_ENABLED || (killer == null) || !killer.isAttackable() || killer.isFakePlayer() || !event.getTarget().isPlayer())
		{
			return;
		}

		trigger(event.getTarget().asPlayer(), GuideLesson.NEMESIS, killer.getName() + " has defeated you, and grew stronger from it.");
	}

	private void onNightPhase(NightPhase phase)
	{
		if ((phase != NightPhase.DUSK) && !phase.isNight())
		{
			return;
		}

		for (Player player : World.getInstance().getPlayers())
		{
			if (player.isOnline() && !player.isInOfflineMode())
			{
				trigger(player, GuideLesson.NIGHT, phase == NightPhase.DUSK ? "Dusk is falling over the land." : "Night has fallen over the land.");
			}
		}
	}

	private void onQuestionMark(Player player, int markId)
	{
		final int index = markId - QUESTION_MARK_BASE;
		if ((index < 0) || (index >= GuideLesson.values().length))
		{
			return;
		}

		final GuideLesson lesson = GuideLesson.values()[index];
		if (lesson.isEnabled())
		{
			showLesson(player, lesson);
		}
	}

	/**
	 * Raises the question mark of a lesson the character hasn't had yet, if the character has its level.
	 * @param player the player
	 * @param lesson the lesson
	 * @param intro the line that opens the lesson the first time it is read: what just happened ("You have slain a Champion monster..."), or {@code null}
	 */
	public void trigger(Player player, GuideLesson lesson, String intro)
	{
		if (!AdventurersGuideConfig.ENABLED || (player == null) || !lesson.isEnabled() || player.isInOfflineMode() || (player.getLevel() < lesson.getLevel()) || !isForPlayer(player, lesson))
		{
			return;
		}

		synchronized (player.getVariables())
		{
			final Set<GuideLesson> seen = getSeen(player);
			final List<GuideLesson> pending = getPending(player);
			if (seen.contains(lesson) || pending.contains(lesson))
			{
				return;
			}

			// The oldest lesson gives way: it stays in the Guidebook.
			while (pending.size() >= AdventurersGuideConfig.MAX_QUESTION_MARKS)
			{
				player.getVariables().remove(VAR_INTRO + pending.remove(0).name());
			}
			pending.add(lesson);
			setPending(player, pending);
			if (intro != null)
			{
				player.getVariables().set(VAR_INTRO + lesson.name(), intro);
			}
		}

		player.sendPacket(new TutorialShowQuestionMark(QUESTION_MARK_BASE + lesson.ordinal()));
		player.sendPacket(QuestSound.ITEMSOUND_QUEST_TUTORIAL.getPacket());
		if (AdventurersGuideConfig.CHAT_NOTICE)
		{
			player.sendMessage("Adventurer's Guide: " + (intro != null ? intro + " " : "") + "Click the blinking question mark to learn about " + lesson.getTitle() + ".");
		}
	}

	/**
	 * Opens a lesson at once if the character hasn't read it yet and has its level: the player has just found its system (talked to its npc, used its item).
	 * @param player the player
	 * @param lesson the lesson
	 */
	public void discover(Player player, GuideLesson lesson)
	{
		if (!AdventurersGuideConfig.ENABLED || (player == null) || !lesson.isEnabled() || (player.getLevel() < lesson.getLevel()) || isSeen(player, lesson))
		{
			return;
		}

		showLesson(player, lesson);
		player.sendPacket(QuestSound.ITEMSOUND_QUEST_TUTORIAL.getPacket());
	}

	/**
	 * @param player the player
	 * @param lesson a lesson
	 * @return {@code true} if the lesson is meant for this character's class: a class transfer lesson only for the class it is about, and not the 1st and 2nd while the retail tutorial tells them
	 */
	private boolean isForPlayer(Player player, GuideLesson lesson)
	{
		final int tier = lesson.getClassTier();
		if (tier == 0)
		{
			return true;
		}

		if ((tier < 3) && !PlayerConfig.DISABLE_TUTORIAL)
		{
			return false;
		}

		return player.getPlayerClass().level() == (tier - 1);
	}

	// ---------------------------------------------------------------------------------------------
	// State
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param player the player
	 * @param lesson a lesson
	 * @return {@code true} if the character has read it
	 */
	public boolean isSeen(Player player, GuideLesson lesson)
	{
		return getSeen(player).contains(lesson);
	}

	/**
	 * @param player the player
	 * @param lesson a lesson
	 * @return {@code true} if the character can read it in the Guidebook: it came already, or it is announced and its level is reached
	 */
	public boolean isUnlocked(Player player, GuideLesson lesson)
	{
		if (!lesson.isEnabled())
		{
			return false;
		}

		return (lesson.isAnnounced() && (player.getLevel() >= lesson.getLevel())) || isSeen(player, lesson) || getPending(player).contains(lesson);
	}

	/**
	 * Marks a lesson read: it comes no more, and its question mark is no longer raised at login.
	 * @param player the player
	 * @param lesson the lesson
	 */
	public void markSeen(Player player, GuideLesson lesson)
	{
		synchronized (player.getVariables())
		{
			final Set<GuideLesson> seen = getSeen(player);
			if (seen.add(lesson))
			{
				player.getVariables().set(VAR_SEEN, join(seen));
			}

			final List<GuideLesson> pending = getPending(player);
			if (pending.remove(lesson))
			{
				setPending(player, pending);
			}
			player.getVariables().remove(VAR_INTRO + lesson.name());
		}
	}

	private Set<GuideLesson> getSeen(Player player)
	{
		final Set<GuideLesson> seen = EnumSet.noneOf(GuideLesson.class);
		seen.addAll(parse(player.getVariables().getString(VAR_SEEN, "")));
		return seen;
	}

	private List<GuideLesson> getPending(Player player)
	{
		return parse(player.getVariables().getString(VAR_PENDING, ""));
	}

	private void setPending(Player player, List<GuideLesson> pending)
	{
		final PlayerVariables variables = player.getVariables();
		if (pending.isEmpty())
		{
			variables.remove(VAR_PENDING);
		}
		else
		{
			variables.set(VAR_PENDING, join(pending));
		}
	}

	private static List<GuideLesson> parse(String value)
	{
		final List<GuideLesson> lessons = new ArrayList<>();
		for (String name : value.split(","))
		{
			final GuideLesson lesson = GuideLesson.find(name.trim());
			if ((lesson != null) && !lessons.contains(lesson))
			{
				lessons.add(lesson);
			}
		}
		return lessons;
	}

	private static String join(Iterable<GuideLesson> lessons)
	{
		final StringBuilder sb = new StringBuilder();
		for (GuideLesson lesson : lessons)
		{
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(lesson.name());
		}
		return sb.toString();
	}

	// ---------------------------------------------------------------------------------------------
	// Pages
	// ---------------------------------------------------------------------------------------------

	/**
	 * Shows a lesson in the tutorial window and marks it read.
	 * @param player the player
	 * @param lesson the lesson
	 */
	public void showLesson(Player player, GuideLesson lesson)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("<html><body>");
		sb.append(getLessonContent(player, lesson));
		sb.append("<br><a action=\"bypass -h ").append(BYPASS).append(" book\">Open the Guidebook</a><br>");
		sb.append("<a action=\"link tutorial_close\">Close Window</a>");
		sb.append("</body></html>");
		player.sendPacket(new TutorialShowHtml(sb.toString()));
		markSeen(player, lesson);
	}

	/**
	 * Shows the Guidebook in the tutorial window.
	 * @param player the player
	 */
	public void showBook(Player player)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("<html><body>");
		sb.append("<center><font color=\"LEVEL\">[Adventurer's Guidebook]</font></center><br>");
		sb.append(getBookContent(player, "bypass -h " + BYPASS + " show "));
		sb.append("<br><a action=\"link tutorial_close\">Close Window</a>");
		sb.append("</body></html>");
		player.sendPacket(new TutorialShowHtml(sb.toString()));
	}

	/**
	 * @param player the player
	 * @param showAction the action of a lesson's link, the lesson's name is put after it
	 * @return the list of lessons by chapter: the ones unlocked as links (not read yet in yellow), the ones still to come in grey with their level
	 */
	public String getBookContent(Player player, String showAction)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("Every lesson the guide has given you can be read again here. Lessons in <font color=\"LEVEL\">yellow</font> are new. The others come when the moment is right.<br>");
		for (GuideCategory category : GuideCategory.values())
		{
			final StringBuilder lines = new StringBuilder();
			for (GuideLesson lesson : GuideLesson.values())
			{
				if ((lesson.getCategory() != category) || !lesson.isEnabled() || (lesson == GuideLesson.WELCOME))
				{
					continue;
				}

				if (isUnlocked(player, lesson))
				{
					lines.append("<a action=\"").append(showAction).append(lesson.name()).append("\">");
					lines.append(isSeen(player, lesson) ? lesson.getTitle() : "<font color=\"" + COLOR_NEW + "\">" + lesson.getTitle() + "</font>");
					lines.append("</a><br1>");
				}
				else if (player.getLevel() < lesson.getLevel())
				{
					lines.append("<font color=\"").append(COLOR_LOCKED).append("\">").append(lesson.getTitle()).append(" (level ").append(lesson.getLevel()).append(")</font><br1>");
				}
				else
				{
					lines.append("<font color=\"").append(COLOR_LOCKED).append("\">").append(lesson.getTitle()).append(" (not met yet)</font><br1>");
				}
			}

			if (lines.length() > 0)
			{
				sb.append("<br><font color=\"LEVEL\">").append(category.getTitle()).append("</font><br1>");
				sb.append(lines);
			}
		}
		sb.append("<br><a action=\"").append(showAction).append(GuideLesson.WELCOME.name()).append("\">About the Adventurer's Guide</a>");
		return sb.toString();
	}

	/**
	 * @param player the player
	 * @param lesson the lesson
	 * @return the page of a lesson, without the window around it. Its links use the bypass {@value #BYPASS}.
	 */
	public String getLessonContent(Player player, GuideLesson lesson)
	{
		final String introLine = player.getVariables().getString(VAR_INTRO + lesson.name(), null);
		final String intro = introLine != null ? "<font color=\"LEVEL\">" + introLine + "</font><br><br>" : "";
		if (lesson.getClassTier() > 0)
		{
			return intro + getClassContent(player, lesson.getClassTier());
		}

		String content = HtmCache.getInstance().getHtm(player, HTML_PATH + lesson.getPage() + ".htm");
		if (content == null)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Missing page " + HTML_PATH + lesson.getPage() + ".htm");
			content = "The pages of this lesson are missing.";
		}

		content = content.replace("%level%", String.valueOf(lesson.getLevel()));
		content = content.replace("%medal%", getItemName(CustomBuffConfig.ITEM_ID));
		content = content.replace("%coin%", getItemName(RatesConfig.ARENA_CURRENCY_ITEM_ID));
		return "<center><font color=\"LEVEL\">[" + lesson.getTitle() + "]</font></center><br>" + intro + content;
	}

	/**
	 * The class transfer lessons: the 1st is the retail tutorial's page of the character's starting class (it names the masters, and the Class Master as well), the 2nd and 3rd are written for the class the character is in at that tier.
	 * @param player the player
	 * @param tier the tier, 1-3
	 * @return the page
	 */
	private String getClassContent(Player player, int tier)
	{
		final PlayerClass playerClass = getClassAtTier(player.getPlayerClass(), tier - 1);
		if ((tier == 1) && (playerClass != null))
		{
			final String retail = getRetailFirstClassPage(player, playerClass);
			if (retail != null)
			{
				return retail;
			}
		}

		final StringBuilder sb = new StringBuilder();
		final String stage = tier == 1 ? "1st" : tier == 2 ? "2nd" : "3rd";
		final int masterLevel = tier == 1 ? ClassTransferConfig.TIER1_MIN_LEVEL : tier == 2 ? ClassTransferConfig.TIER2_MIN_LEVEL : ClassTransferConfig.TIER3_MIN_LEVEL;
		sb.append("<center><font color=\"LEVEL\">[Preparations for the ").append(stage).append(" Class Transfer]</font></center><br>");

		if (playerClass != null)
		{
			final List<String> targets = getTargetClassNames(playerClass, tier);
			final String className = getClassName(playerClass.getId());
			sb.append("AEIOU".indexOf(className.charAt(0)) >= 0 ? "An " : "A ").append(className).append(" who reaches level ").append(masterLevel).append(" can proceed with the ").append(stage).append(" class transfer");
			if (!targets.isEmpty())
			{
				sb.append(" and become ");
				for (int i = 0; i < targets.size(); i++)
				{
					if (i > 0)
					{
						sb.append(i == (targets.size() - 1) ? " or " : ", ");
					}
					sb.append("<font color=\"LEVEL\">").append(targets.get(i)).append("</font>");
				}
			}
			sb.append(".<br>");
		}
		else
		{
			sb.append("At level ").append(masterLevel).append(" a character can proceed with the ").append(stage).append(" class transfer.<br>");
		}

		sb.append("There are two roads to your new class.<br><br>");
		sb.append("<font color=\"LEVEL\">The classic quests</font>: ");
		if (tier == 1)
		{
			sb.append("a quest that can be undertaken from level 18. The masters of your guild in the towns will tell you where to begin.");
		}
		else if (tier == 2)
		{
			if ((playerClass != null) && (playerClass.getRace() == Race.KAMAEL))
			{
				sb.append("a Kamael completes one quest, which can be undertaken from level 39.");
			}
			else
			{
				sb.append("three quests, the first of which can be undertaken from level 35. The masters of your guild in the towns will tell you where to begin.");
			}
		}
		else
		{
			sb.append("the Saga of your class, a long quest that can be undertaken from level 76.");
		}
		sb.append("<br><br>");
		sb.append(getClassMasterContent(masterLevel));
		return sb.toString();
	}

	/**
	 * @param masterLevel the Class Master's level for the transfer
	 * @return the paragraph about the Class Master's road, with a link that marks him on the map
	 */
	private String getClassMasterContent(int masterLevel)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("<font color=\"LEVEL\">The Class Master</font>: from level ").append(masterLevel).append(" the Class Master in <font color=\"LEVEL\">Giran</font>, next to the Arena, can change your class at once");
		if (ClassTransferChallengeConfig.ENABLED && ClassTransferChallengeConfig.REQUIRED_FOR_TRANSFER)
		{
			sb.append(" once you clear his trial: a short fight alone in a private hall, with an Omen that changes every attempt. Falling there costs you nothing; you start again at the entrance. Type <font color=\"LEVEL\">.trial</font> to see how your trial goes");
		}
		sb.append(".<br>");
		sb.append("<a action=\"bypass -h ").append(BYPASS).append(" radar class_master\">Show the Class Master on the map</a><br>");
		return sb.toString();
	}

	/**
	 * @param player the player, for the language of the page
	 * @param playerClass the starting class
	 * @return the retail tutorial's 1st class transfer page of that class (Q00255_Tutorial, question mark 13), without its window and close link; {@code null} if there is none
	 */
	private String getRetailFirstClassPage(Player player, PlayerClass playerClass)
	{
		final String file;
		switch (playerClass)
		{
			case FIGHTER:
			{
				file = "tutorial-21.html";
				break;
			}
			case MAGE:
			{
				file = "tutorial-21a.html";
				break;
			}
			case ELVEN_FIGHTER:
			{
				file = "tutorial-21b.html";
				break;
			}
			case ELVEN_MAGE:
			{
				file = "tutorial-21c.html";
				break;
			}
			case ORC_FIGHTER:
			{
				file = "tutorial-21d.html";
				break;
			}
			case ORC_MAGE:
			{
				file = "tutorial-21e.html";
				break;
			}
			case DWARVEN_FIGHTER:
			{
				file = "tutorial-21f.html";
				break;
			}
			case DARK_FIGHTER:
			{
				file = "tutorial-21g.html";
				break;
			}
			case DARK_MAGE:
			{
				file = "tutorial-21h.html";
				break;
			}
			case MALE_SOLDIER:
			{
				file = "tutorial-21i.html";
				break;
			}
			case FEMALE_SOLDIER:
			{
				file = "tutorial-21j.html";
				break;
			}
			default:
			{
				return null;
			}
		}

		final String html = HtmCache.getInstance().getHtm(player, RETAIL_TUTORIAL_PATH + file);
		if (html == null)
		{
			return null;
		}

		final int start = html.indexOf("<body>");
		final int end = html.lastIndexOf("</body>");
		final String body = (start >= 0) && (end > start) ? html.substring(start + 6, end) : html;
		final StringBuilder sb = new StringBuilder();
		for (String line : body.split("\n"))
		{
			if (!line.contains("tutorial_close"))
			{
				sb.append(line).append('\n');
			}
		}
		return sb.toString();
	}

	/**
	 * @param playerClass the player's class
	 * @param level the class level wanted (0 = starting class)
	 * @return the class the player was at that level, {@code null} if the player hasn't reached it
	 */
	private static PlayerClass getClassAtTier(PlayerClass playerClass, int level)
	{
		PlayerClass current = playerClass;
		while ((current != null) && (current.level() > level))
		{
			current = current.getParent();
		}
		return (current != null) && (current.level() == level) ? current : null;
	}

	/**
	 * @param playerClass the class before the transfer
	 * @param tier the tier
	 * @return the names of the classes it can become: the Class Master's list, else the class tree
	 */
	private static List<String> getTargetClassNames(PlayerClass playerClass, int tier)
	{
		final List<String> names = new ArrayList<>();
		for (ClassTransferHolder holder : ClassTransferData.getInstance().getEligibleTransfers(playerClass.getId(), tier))
		{
			names.add(getClassName(holder.getToClassId()));
		}

		if (names.isEmpty())
		{
			for (PlayerClass next : playerClass.getNextClasses())
			{
				names.add(getClassName(next.getId()));
			}
		}
		return names;
	}

	private static String getClassName(int classId)
	{
		final ClassInfoHolder info = ClassListData.getInstance().getClass(classId);
		return info != null ? info.getClassName() : ("Class " + classId);
	}

	private static String getItemName(int itemId)
	{
		final ItemTemplate item = ItemData.getInstance().getTemplate(itemId);
		return item != null ? item.getName() : ("Item " + itemId);
	}

	private static int[] getSealedCacheIds()
	{
		if (!LuckyLootConfig.SEALED_CACHE_ENABLED)
		{
			return SEALED_CACHES_FALLBACK;
		}
		return new int[]
		{
			LuckyLootConfig.SEALED_CACHE_COMMON_ITEM_ID,
			LuckyLootConfig.SEALED_CACHE_RARE_ITEM_ID,
			LuckyLootConfig.SEALED_CACHE_EPIC_ITEM_ID
		};
	}

	// ---------------------------------------------------------------------------------------------
	// Links
	// ---------------------------------------------------------------------------------------------

	/**
	 * Handles a link of the guide's pages: {@code book}, {@code show <lesson>} or {@code radar <place>}.
	 * @param player the player
	 * @param params what follows the bypass
	 * @return {@code true} if the tutorial window should show something (the bypass of the Community Board shows its own page)
	 */
	public boolean onBypass(Player player, String params)
	{
		final String[] parts = params.trim().split("\\s+");
		final String action = parts[0].toLowerCase();
		if (!AdventurersGuideConfig.ENABLED && !action.equals("radar"))
		{
			return false;
		}

		switch (action)
		{
			case "show":
			{
				final GuideLesson lesson = parts.length > 1 ? GuideLesson.find(parts[1]) : null;
				if ((lesson != null) && lesson.isEnabled() && isUnlocked(player, lesson))
				{
					showLesson(player, lesson);
					return true;
				}
				break;
			}
			case "radar":
			{
				if (parts.length > 1)
				{
					showOnMap(player, parts[1]);
				}
				return false;
			}
		}

		showBook(player);
		return true;
	}

	/**
	 * Marks a place of the guide on the player's map and radar.
	 * @param player the player
	 * @param place class_master, arena_master, arena_shop, hotzone_teleporter, master_blacksmith or notice_board (the nearest one)
	 */
	public void showOnMap(Player player, String place)
	{
		final Location location;
		final String name;
		switch (place.toLowerCase())
		{
			case "class_master":
			{
				location = getNpcLocation(ClassTransferConfig.CLASS_MASTER_NPC_ID);
				name = "The Class Master";
				break;
			}
			case "arena_master":
			{
				location = getNpcLocation(RatesConfig.ARENA_MASTER_NPC_ID);
				name = "The Arena Master";
				break;
			}
			case "arena_shop":
			{
				location = getNpcLocation(ARENA_SHOP_NPC_ID);
				name = "The Arena Merchant";
				break;
			}
			case "hotzone_teleporter":
			{
				location = getNpcLocation(HOTZONE_TELEPORTER_NPC_ID);
				name = "The Hotzone Teleporter";
				break;
			}
			case "master_blacksmith":
			{
				location = getNpcLocation(MASTER_BLACKSMITH_NPC_ID);
				name = "The Master Blacksmith";
				break;
			}
			case "notice_board":
			{
				final NoticeBoardSite site = DynamicQuestData.getInstance().getNearestBoard(player, 0);
				location = site != null ? site.getLocation() : null;
				name = site != null ? "The Notice Board of " + site.getTown() : "The Notice Board";
				break;
			}
			default:
			{
				return;
			}
		}

		if (location == null)
		{
			player.sendMessage(name + " can't be found right now.");
			return;
		}

		player.getRadar().addMarker(location.getX(), location.getY(), location.getZ());
		player.sendMessage(name + " is marked on your map.");
	}

	private static Location getNpcLocation(int npcId)
	{
		final Spawn spawn = SpawnTable.getInstance().getAnySpawn(npcId);
		if (spawn == null)
		{
			return null;
		}

		final Npc npc = spawn.getLastSpawn();
		return npc != null ? npc.getLocation() : new Location(spawn.getX(), spawn.getY(), spawn.getZ());
	}

	public static AdventurersGuideManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final AdventurersGuideManager INSTANCE = new AdventurersGuideManager();
	}
}
