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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.w3c.dom.Document;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.holders.FakePlayerChatHolder;
import org.l2jmobius.gameserver.data.xml.FakePlayerData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;

/**
 * @author Mobius
 */
public class FakePlayerChatManager implements IXmlReader
{
	private static final Logger LOGGER = Logger.getLogger(FakePlayerChatManager.class.getName());
	
	private static final List<FakePlayerChatHolder> MESSAGES = new ArrayList<>();
	private static final int MIN_DELAY = 5000;
	private static final int MAX_DELAY = 15000;
	/** A fake player answers general chat a moment later (typing), and only players within the general chat range hear it. */
	private static final int GENERAL_MIN_DELAY = 2000;
	private static final int GENERAL_MAX_DELAY = 6000;
	private static final int GENERAL_RANGE = 1250;
	/** A question to nobody in particular: it ends with a question mark, or starts with one of these words. */
	private static final String[] QUESTION_WORDS =
	{
		"who",
		"what",
		"where",
		"when",
		"why",
		"how",
		"which",
		"anyone",
		"any",
		"is",
		"are",
		"can",
		"does",
		"do",
		"did",
		"should",
		"wtb",
		"wts"
	};
	
	/** When a fake player may next answer a question a player asked nobody in particular, by player object id. */
	private final Map<Integer, Long> _nextNearbyAnswer = new ConcurrentHashMap<>();
	
	protected FakePlayerChatManager()
	{
		load();
	}
	
	@Override
	public void load()
	{
		if (FakePlayersConfig.FAKE_PLAYERS_ENABLED)
		{
			FakePlayerData.getInstance().report();
			if (FakePlayersConfig.FAKE_PLAYER_CHAT)
			{
				MESSAGES.clear();
				parseDatapackFile("data/FakePlayerChatData.xml");
				LOGGER.info(getClass().getSimpleName() + ": Loaded " + MESSAGES.size() + " chat templates.");
			}
		}
	}
	
	@Override
	public void parseDocument(Document document, File file)
	{
		forEach(document, "list", listNode -> forEach(listNode, "fakePlayerChat", fakePlayerChatNode ->
		{
			// Parse attributes of the "fakePlayerChat" element.
			final StatSet set = new StatSet(parseAttributes(fakePlayerChatNode));
			
			// Add a new FakePlayerChatHolder to the MESSAGES collection.
			MESSAGES.add(new FakePlayerChatHolder(set.getString("fpcName"), set.getString("searchMethod"), set.getString("searchText"), set.getString("answers")));
		}));
	}
	
	public void manageChat(Player player, String fpcName, String message)
	{
		ThreadPool.schedule(() -> manageResponce(player, fpcName, message), Rnd.get(MIN_DELAY, MAX_DELAY));
	}
	
	public void manageChat(Player player, String fpcName, String message, int minDelay, int maxDelay)
	{
		ThreadPool.schedule(() -> manageResponce(player, fpcName, message), Rnd.get(minDelay, maxDelay));
	}
	
	/**
	 * Called when a player says something in general chat: a talkable fake player it talks to answers in general chat, with the same answers as a whisper. A player talks to a fake player in hearing range by saying its name, or to the one it has targeted. Only
	 * players' general chat comes here, so fake players never answer each other.
	 * @param player the player
	 * @param message what the player said
	 */
	public void onGeneralChat(Player player, String message)
	{
		if (!FakePlayersConfig.FAKE_PLAYERS_ENABLED || !FakePlayersConfig.FAKE_PLAYER_CHAT || (player == null) || MESSAGES.isEmpty())
		{
			return;
		}
		
		final String text = message.toLowerCase().trim();
		Npc npc = findAddressee(player, text);
		final boolean addressed = npc != null;
		
		// A question to nobody in particular: now and then the closest fake player answers it, when it knows what to say.
		if (!addressed && isQuestion(text) && (FakePlayersConfig.FAKE_PLAYER_CHAT_NEARBY_CHANCE > 0))
		{
			final long now = System.currentTimeMillis();
			final Long next = _nextNearbyAnswer.get(player.getObjectId());
			if (((next == null) || (now >= next)) && (Rnd.get(100) < FakePlayersConfig.FAKE_PLAYER_CHAT_NEARBY_CHANCE))
			{
				npc = findClosest(player);
				if (npc != null)
				{
					_nextNearbyAnswer.put(player.getObjectId(), now + (FakePlayersConfig.FAKE_PLAYER_CHAT_NEARBY_COOLDOWN * 1000L));
				}
			}
		}
		
		if (npc != null)
		{
			final Npc speaker = npc;
			final String fpcName = speaker.getName();
			ThreadPool.schedule(() ->
			{
				if (!speaker.isDead() && speaker.isSpawned())
				{
					manageResponce(player, fpcName, message, speaker, !addressed);
				}
			}, Rnd.get(GENERAL_MIN_DELAY, GENERAL_MAX_DELAY));
		}
	}
	
	/**
	 * @param text what a player said, in lower case
	 * @return {@code true} if it is a question: it ends with a question mark or starts with a question word
	 */
	private static boolean isQuestion(String text)
	{
		if (text.endsWith("?"))
		{
			return true;
		}
		
		final String first = text.split("[^a-z0-9]+", 2)[0];
		for (String word : QUESTION_WORDS)
		{
			if (first.equals(word))
			{
				return true;
			}
		}
		return false;
	}
	
	/**
	 * @param player the player
	 * @return the closest talkable fake player within {@link FakePlayersConfig#FAKE_PLAYER_CHAT_NEARBY_RANGE} of {@code player}, {@code null} if none
	 */
	private static Npc findClosest(Player player)
	{
		Npc closest = null;
		double closestDistance = Double.MAX_VALUE;
		for (Npc npc : World.getInstance().getVisibleObjectsInRange(player, Npc.class, FakePlayersConfig.FAKE_PLAYER_CHAT_NEARBY_RANGE))
		{
			if (!isTalkable(npc, player))
			{
				continue;
			}
			
			final double distance = player.calculateDistance2D(npc);
			if (distance < closestDistance)
			{
				closest = npc;
				closestDistance = distance;
			}
		}
		return closest;
	}
	
	/**
	 * @param player the player
	 * @param text what it said, in lower case
	 * @return the talkable fake player in hearing range whose name it said (the closest one), else the one it has targeted, {@code null} if none
	 */
	private static Npc findAddressee(Player player, String text)
	{
		Npc named = null;
		double namedDistance = Double.MAX_VALUE;
		for (Npc npc : World.getInstance().getVisibleObjectsInRange(player, Npc.class, GENERAL_RANGE))
		{
			if (!isTalkable(npc, player) || !mentions(text, npc.getName().toLowerCase()))
			{
				continue;
			}
			
			final double distance = player.calculateDistance2D(npc);
			if (distance < namedDistance)
			{
				named = npc;
				namedDistance = distance;
			}
		}
		if (named != null)
		{
			return named;
		}
		
		final WorldObject target = player.getTarget();
		if ((target instanceof Npc) && isTalkable((Npc) target, player) && (player.calculateDistance2D(target) <= GENERAL_RANGE))
		{
			return (Npc) target;
		}
		return null;
	}
	
	/**
	 * @param npc an npc
	 * @param player the player talking
	 * @return {@code true} if {@code npc} is a living, talkable fake player in the player's world
	 */
	private static boolean isTalkable(Npc npc, Player player)
	{
		return npc.isFakePlayer() && !npc.isDead() && npc.isSpawned() && (npc.getInstanceId() == player.getInstanceId()) && FakePlayerData.getInstance().isTalkable(npc.getName());
	}
	
	/**
	 * @param text what a player said, in lower case
	 * @param name a name, in lower case
	 * @return {@code true} if {@code name} is a word of {@code text} (not part of a longer word)
	 */
	private static boolean mentions(String text, String name)
	{
		int index = text.indexOf(name);
		while (index >= 0)
		{
			final int end = index + name.length();
			if (((index == 0) || !Character.isLetterOrDigit(text.charAt(index - 1))) && ((end == text.length()) || !Character.isLetterOrDigit(text.charAt(end))))
			{
				return true;
			}
			index = text.indexOf(name, index + 1);
		}
		return false;
	}
	
	private void manageResponce(Player player, String fpcName, String message)
	{
		manageResponce(player, fpcName, message, null, false);
	}
	
	/**
	 * Answers what a player said to a fake player.
	 * @param player the player
	 * @param fpcName the fake player's name
	 * @param message what the player said
	 * @param speaker the fake player answering in general chat, {@code null} to answer with a whisper
	 * @param matchOnly {@code true} to answer only when a template matches (no DEFAULT answer), for a question it wasn't asked itself
	 */
	private void manageResponce(Player player, String fpcName, String message, Npc speaker, boolean matchOnly)
	{
		if (player == null)
		{
			return;
		}
		
		final String text = message.toLowerCase().trim();
		
		// tricky question
		if (text.contains("can you see me"))
		{
			final Spawn spawn = speaker != null ? null : SpawnTable.getInstance().getAnySpawn(FakePlayerData.getInstance().getNpcIdByName(fpcName));
			if ((speaker != null) || (spawn != null))
			{
				final Npc npc = speaker != null ? speaker : spawn.getLastSpawn();
				if (npc != null)
				{
					if (npc.calculateDistance2D(player) < 3000)
					{
						if (GeoEngine.getInstance().canSeeTarget(npc, player) && !player.isInvisible())
						{
							reply(player, fpcName, speaker, Rnd.nextBoolean() ? "i am not blind" : Rnd.nextBoolean() ? "of course i can" : "yes");
						}
						else
						{
							reply(player, fpcName, speaker, Rnd.nextBoolean() ? "i know you are around" : Rnd.nextBoolean() ? "not at the moment :P" : "no, where are you?");
						}
					}
					else
					{
						reply(player, fpcName, speaker, Rnd.nextBoolean() ? "nope, can't see you" : Rnd.nextBoolean() ? "nope" : "no");
					}
					return;
				}
			}
		}
		
		// One answer per message, from the first template that matches (DEFAULT ones only when nothing else does).
		FakePlayerChatHolder fallback = null;
		for (FakePlayerChatHolder chatHolder : MESSAGES)
		{
			if (!chatHolder.getFpcName().equals(fpcName) && !chatHolder.getFpcName().equals("ALL"))
			{
				continue;
			}

			boolean matches = false;
			switch (chatHolder.getSearchMethod())
			{
				case "EQUALS":
				{
					matches = text.equals(chatHolder.getSearchText().get(0));
					break;
				}
				case "STARTS_WITH":
				{
					matches = text.startsWith(chatHolder.getSearchText().get(0));
					break;
				}
				case "CONTAINS":
				{
					matches = true;
					for (String word : chatHolder.getSearchText())
					{
						if (!text.contains(word))
						{
							matches = false;
							break;
						}
					}
					break;
				}
				case "DEFAULT":
				{
					// A specific fake player's default wins over the ALL one.
					if ((fallback == null) || !chatHolder.getFpcName().equals("ALL"))
					{
						fallback = chatHolder;
					}
					break;
				}
			}

			if (matches)
			{
				reply(player, fpcName, speaker, chatHolder.getAnswers().get(Rnd.get(chatHolder.getAnswers().size())));
				return;
			}
		}

		if ((fallback != null) && !matchOnly)
		{
			reply(player, fpcName, speaker, fallback.getAnswers().get(Rnd.get(fallback.getAnswers().size())));
		}
	}
	
	/**
	 * @param player the player it answers
	 * @param fpcName the fake player's name
	 * @param speaker the fake player answering in general chat, {@code null} to whisper
	 * @param message the answer
	 */
	private void reply(Player player, String fpcName, Npc speaker, String message)
	{
		if (speaker == null)
		{
			sendChat(player, fpcName, message);
			return;
		}
		
		if (!speaker.isDead() && speaker.isSpawned())
		{
			final CreatureSay packet = new CreatureSay(speaker, ChatType.GENERAL, speaker.getName(), message);
			World.getInstance().forEachVisibleObjectInRange(speaker, Player.class, GENERAL_RANGE, listener -> listener.sendPacket(packet));
		}
	}
	
	public void sendChat(Player player, String fpcName, String message)
	{
		final Spawn spawn = SpawnTable.getInstance().getAnySpawn(FakePlayerData.getInstance().getNpcIdByName(fpcName));
		if (spawn != null)
		{
			final Npc npc = spawn.getLastSpawn();
			if (npc != null)
			{
				player.sendPacket(new CreatureSay(npc, ChatType.WHISPER, fpcName, message));
			}
		}
	}
	
	public static FakePlayerChatManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final FakePlayerChatManager INSTANCE = new FakePlayerChatManager();
	}
}
