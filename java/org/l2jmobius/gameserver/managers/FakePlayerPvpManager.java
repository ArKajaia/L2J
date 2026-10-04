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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.FakePlayerPvpAI;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.config.custom.FakePlayerPvpConfig;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.sql.CharInfoTable;
import org.l2jmobius.gameserver.data.xml.FakePlayerData;
import org.l2jmobius.gameserver.data.xml.FakePlayerPvpData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.data.xml.TransformData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.WorldRegion;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.enums.player.Sex;
import org.l2jmobius.gameserver.model.actor.holders.npc.AggroInfo;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerHolder;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerParty;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.SkillCategory;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpPersonality;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpWeapon;
import org.l2jmobius.gameserver.model.actor.instance.FakePlayerPvpServitor;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.actor.transform.Transform;
import org.l2jmobius.gameserver.model.actor.transform.TransformTemplate;
import org.l2jmobius.gameserver.model.item.holders.ItemEnchantHolder;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.skill.AbnormalType;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.enums.SkillFinishType;
import org.l2jmobius.gameserver.model.skill.holders.SkillHolder;
import org.l2jmobius.gameserver.model.skill.targets.TargetType;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.model.stats.Stat;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.model.zone.ZoneRegion;
import org.l2jmobius.gameserver.model.zone.ZoneType;
import org.l2jmobius.gameserver.model.zone.type.ArenaZone;
import org.l2jmobius.gameserver.model.zone.type.BossZone;
import org.l2jmobius.gameserver.model.zone.type.CastleZone;
import org.l2jmobius.gameserver.model.zone.type.ClanHallZone;
import org.l2jmobius.gameserver.model.zone.type.FortZone;
import org.l2jmobius.gameserver.model.zone.type.HotZone;
import org.l2jmobius.gameserver.model.zone.type.JailZone;
import org.l2jmobius.gameserver.model.zone.type.NoPvPZone;
import org.l2jmobius.gameserver.model.zone.type.OlympiadStadiumZone;
import org.l2jmobius.gameserver.model.zone.type.PeaceZone;
import org.l2jmobius.gameserver.model.zone.type.SiegeZone;
import org.l2jmobius.gameserver.model.zone.type.TownZone;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;
import org.l2jmobius.gameserver.network.serverpackets.SocialAction;

/**
 * Roaming fake players (see {@link FakePlayerPvpConfig}): like a champion, a regular monster has a small chance every time it spawns or respawns to be replaced by a fake player of the same level ({@link #tryReplace}, called from {@code Spawn} right before the monster
 * enters the world). The fake player is built like a real character of that level ({@link FakePlayerPvpFactory}), hunts the monsters around the spawn point ({@link org.l2jmobius.gameserver.ai.FakePlayerPvpAI}) and brings PvP to a quiet server:
 * <ul>
 * <li>monsters can't push it below {@link FakePlayerPvpConfig#MONSTER_DAMAGE_FLOOR}% HP, only players can kill it ({@link #limitDamage}, called from {@code Attackable#reduceCurrentHp});</li>
 * <li>a player that kills the monster it is fighting becomes its target ({@link #onAttackableKilled}, called from {@code Attackable#doDie});</li>
 * <li>a player that attacks it becomes its target ({@link #onFakePlayerAttacked}, called from its AI);</li>
 * <li>other fake players are met like players at a hunting ground: they say hello, walk over and talk ({@link #converse}), and some of them fight over the spot, steal each other's kills ({@link #onMonsterAttackedByFake}, {@link #onMonsterKilledByFake}) or join a fight
 * of others ({@link #attackFakePlayer}).</li>
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
	/** Transfer Pain only works with the servitor this close, like a player's. */
	private static final int SERVITOR_TRANSFER_RANGE = 1000;
	/** Milliseconds between two {@link #maintain} runs. */
	private static final long MAINTAIN_INTERVAL = 30000;
	/** A player this close sees a fake player even behind a wall (see {@link #isSeenByPlayer}). */
	private static final int CLOSE_SEEN_RANGE = 800;
	/** Seconds between two tries of a fake player coming back from town while its spot is watched, and how many tries it makes. */
	private static final int RETURN_RETRY_DELAY = 20;
	private static final int RETURN_MAX_RETRIES = 15;
	/** A fake player says at most one thing in this many milliseconds. */
	private static final long CHAT_INTERVAL = 20000;
	/** How long a fake player keeps not hitting back an attacker it chose not to fight, after their last hit. */
	private static final long REFUSE_MEMORY = 60000;
	/** A fake player doesn't pick a fight with a player this many levels above it (it only complains). */
	private static final int OUTLEVELED_DIFFERENCE = 6;
	
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
		"ok you asked for it",
		"dude i was hitting that",
		"get your own mob",
		"ks = pk",
		"no ks here pls",
		"find your own spot",
		"u blind? my mob",
		"i was hitting that mob for a while, now ur mine",
		"you stole my kill so now i steal your exp lol",
		"bad move, that mob had my name written on it",
		"ks me one more time and see what happens next",
		"alright you want to steal? lets see you fight",
		"that was my kill, time to pay for it buddy",
		"nobody steals from me in my own spot, nobody",
		"ok thats it, you just picked the wrong guy",
		"you took my mob, now i take your whole life",
		"do u even know how long i was hitting that",
		"i warned people about ks here, now u learn",
		"nice ks, now lets see if you can tank me too",
		"stealing kills is fun until someone fights back",
		"you really thought i would just let that go?",
		"my mob, my exp, and now my pvp kill as well",
		"next time find your own mob, if you survive",
		"that was the last mob you ever stole from me",
		"you better have pots because im coming for u",
		"cant believe you did that right in front of me",
		"i had it at 5 percent hp, you just finished it",
		"ok ks king, show me what else you can do",
		"u like stealing? lets see how u like dying",
		"that spawn is mine, i was here way before you",
		"this is what happens when u ks in my spot",
		"thanks for the ks, here is my thank you gift",
		"you just ks me? really? ok lets go then",
		"i let the first ks slide, not this one tho",
		"im tired of kill stealers, time to clean up",
		"u should have walked away while u could",
		"alright then, lets settle this like players",
		"stealing mobs from someone fully buffed, bold",
		"you want exp? come take it from me instead",
		"that mob was half dead because of me, not you",
		"hope that exp was worth what comes next",
		"learn some manners, dont touch other mobs",
		"so you think ks is ok here? wrong answer",
		"i didnt burn all my mp so you could ks it",
		"you just made this farm session interesting",
		"im done being nice to kill stealers today",
		"two can play that game, now you are my mob",
		"that kill was mine and you know it very well",
		"dont run now, you wanted this when you ks me",
		"your gear looks nice, lets see if you can use it",
		"pro tip, dont ks someone who is flag happy",
		"i dont care who you are, ks means pvp here",
		"see what happens when u take someone mob"
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
		"ok lets go",
		"big mistake",
		"u sure?",
		"hahaha ok",
		"here we go",
		"lets dance",
		"wrong target buddy",
		"you really want to fight me? ok lets do it",
		"wrong target buddy, you will regret this one",
		"did you just hit me? big mistake my friend",
		"i was just farming, now you have my attention",
		"ok if you want pvp, you got it right now",
		"cant a guy farm in peace around here lol",
		"you must be bored, let me fix that for you",
		"hitting me from behind? how brave of you",
		"come on then, show me what you got",
		"i hope you brought enough pots for this",
		"alright, farming can wait, you cant though",
		"u picked a fight with the wrong player",
		"you just flagged on me, now its my turn",
		"really? while im fighting a mob? classy",
		"lets see if your skills match your gear",
		"ok round one, try not to die too fast",
		"you should have kept walking past me",
		"is this a joke? you really attacking me?",
		"i was having a nice day until you showed up",
		"fine, lets see who goes back to town first",
		"careful, i bite back when someone hits me",
		"nice try but you will need way more than that",
		"you want my spot that bad? come take it",
		"i dont even know you and you attack me, ok",
		"alright then, no more mister nice guy",
		"did someone pay you to attack me or what?",
		"my mob can wait, you are more fun anyway",
		"oh you wanna play? lets play then",
		"you just woke up the wrong player buddy",
		"hitting me with that gear? brave choice",
		"fine, i needed some pvp practice anyway",
		"bad idea to attack someone who is buffed",
		"alright, i accept your little challenge",
		"you think im an easy kill? think again",
		"i hope your clan is ready to res you",
		"keep hitting me and see what happens next",
		"no warning, no talk, just a hit? ok then",
		"someone clearly doesnt like my farming spot",
		"your funeral buddy, lets go right now",
		"you should check my level before attacking",
		"thanks for the free pvp, i was getting bored",
		"cmon at least say hi before you attack me",
		"seriously, i didnt even do anything to you",
		"you just made a very expensive mistake",
		"never attack a player who is fully buffed",
		"alright lets dance, dont step on my feet"
	};
	private static final String[] TAUNTS_KILL =
	{
		"gg",
		"ez",
		"noob",
		"next time",
		"bb",
		"lol",
		"stay down",
		"gg wp",
		"too easy",
		"thx for the pvp",
		"sit",
		"learn to play",
		"go back to town",
		"rip",
		"nice try",
		"better luck next time",
		"cya in town",
		"bye",
		"stay dead",
		"thx for exp",
		"was that all?",
		"lol ok",
		"gg ez",
		"too slow",
		"outplayed",
		"come back with buffs",
		"need more pots?",
		"u ok?",
		":)",
		"haha",
		"that was fast",
		"one more?",
		"rematch anytime",
		"zzz",
		"sleep",
		"get gear first",
		"not today",
		"try again",
		"gj tho",
		"close one",
		"almost had me",
		"respect for trying",
		"wp",
		"bye bye",
		"done",
		"next",
		"whos next",
		"nice fight",
		"ty for fight",
		"go res",
		"thanks for the fight, come back when ready",
		"that was fun, lets do it again sometime",
		"you almost had me, maybe next time buddy",
		"go get some better gear and then come back",
		"dont feel bad, i have been doing this for years",
		"walk it off, the town is not that far away",
		"maybe try fighting mobs first, they hit softer",
		"good try, but you need way more practice",
		"tell your friends who sent you back to town",
		"see you in town, dont forget to buff up"
	};
	private static final String[] TAUNTS_KILL_STEAL_COMPLAIN =
	{
		"ks...",
		"thx for ks",
		"wow ks",
		"nice ks bro",
		"my mob...",
		"cmon man",
		"was about to die...",
		"ty for stealing",
		"seriously?",
		"ks again...",
		"whatever, spawn is big",
		"wow, i hit that mob for a minute and u ks it",
		"thanks for the ks, really appreciate it man",
		"cmon man there are like fifty mobs around here",
		"i was almost done with that one, nice timing",
		"really? you had to take the one i was hitting?",
		"not cool dude, please find your own mobs",
		"i would fight you but im too low hp right now",
		"next time i wont be so nice about the ks",
		"ks in a spot this big, some people are funny",
		"i saw that, dont think i didnt notice the ks",
		"you owe me one mob, just saying lol",
		"great, now i need to pull another one again",
		"my mob was literally at one percent hp, wow",
		"ok whatever, there is enough exp for both",
		"please dont do that again, its really annoying",
		"lucky for you im busy farming right now",
		"ks is so lame, go farm somewhere else pls",
		"i guess you needed that exp more than me",
		"just because u can doesnt mean u should ks",
		"thats the second time u take my mob today",
		"i am writing your name down, remember that",
		"some people have no respect for others mobs",
		"fine keep it, i hope it dropped nothing good",
		"not worth the fight, but you know what u did",
		"if u need exp that bad, just ask for a party",
		"cmon i spent half my mp on that thing",
		"the whole spot is empty and you take my mob",
		"you realise you just ks me right?",
		"ok ok, i will go hit another one, no worries",
		"ks again and we will have a problem buddy",
		"i let it slide this time, dont push it",
		"bro there is a mob right behind you, take that",
		"i was hitting it first and you know it",
		"nice ks, didnt know that was allowed here lol",
		"seriously, the mob was already dying from me",
		"my grandma could kill that mob faster than u",
		"do you have a problem with finding mobs?",
		"learn to share the spot, its not hard",
		"every time i pull a mob someone steals it",
		"people ks here like its a sport or something",
		"im too tired to pvp you, but that was rude",
		"i hope your next enchant fails for that one",
		"ugh, back to pulling mobs one by one again",
		"this is why i hate farming in busy spots",
		"dont do that again or i will flag on you",
		"thanks a lot, my exp bar stopped moving now",
		"whatever, i will get the next one before u",
		"im gonna remember your name, just so you know",
		"i was really close to finishing that one off"
	};
	private static final String[] TAUNTS_FLEE =
	{
		"brb",
		"lag",
		"omg",
		"wtf lag",
		"cya",
		"no pots",
		"2vs1 gj",
		"nope",
		"not today",
		"gtg",
		"no mp lol",
		"later",
		"u wont catch me",
		"nope, not today, im out of here right now",
		"i have no pots left, see you another time",
		"this lag is killing me, i am leaving",
		"two on one? thats not fair, im out",
		"brb, need to go restock pots in town",
		"you win this time, but i will be back",
		"my mp is gone, not dying for nothing",
		"gotta go, mom is calling me for dinner",
		"i dont fight people who cheat with buffs",
		"running is a strategy too you know",
		"not worth it, i have a lot of exp to lose",
		"catch me if you can, i have wind walk",
		"i will come back with my clan, you wait",
		"my connection is terrible, cant fight now",
		"you are too strong with that gear, bye",
		"my weapon is not enchanted enough for this",
		"i need a healer for this one, later",
		"lol no way im staying to die here",
		"cya later, i have better things to do",
		"you can have the spot, i am leaving",
		"this is not over, i will see you again",
		"im too low hp, gonna heal and come back",
		"i know when to leave, and that is now",
		"enjoy the spot while it lasts buddy",
		"my buffs just ran out, bad timing, bye",
		"im not dying today, too much to lose",
		"this fight is lagging too much for me",
		"you guys brought friends, not fair at all",
		"i will return with better gear, promise",
		"my potions are all gone, gotta go restock",
		"got a phone call, need to leave right now",
		"this is a tactical retreat, not running lol",
		"fine, the spot is yours for now, enjoy",
		"not today, my exp is too precious to lose",
		"you will not catch me with those boots",
		"im going to town, feel free to follow me",
		"you win the spot, but not the fight yet",
		"bye, i dont have time for this right now",
		"my soulshots ran out, cant fight like this",
		"dont chase me, it will not end well for u",
		"i need to rebuff, i will be right back",
		"ok ok you win, im going back to town now",
		"need to save my exp, maybe another time",
		"too many people here for a fair fight",
		"lol cya, i have somewhere else to be",
		"im outta here before i lose all my exp",
		"retreat, retreat, too much damage coming in"
	};
	private static final String[] TAUNTS_DEATH =
	{
		"gg",
		"lag...",
		"wtf",
		"nice one",
		"ok gj",
		"omg lag",
		"rematch?",
		"lucky",
		"ok ok gg",
		"crit spam...",
		"i was afk",
		"u were buffed",
		"ill be back",
		"damn",
		"gg wp",
		"nooo",
		"ugh",
		"rip",
		"rip me",
		"not fair",
		"no pots left",
		"my pc froze",
		"cheater",
		"k",
		"lol ok",
		"gj",
		"wow",
		"so lucky",
		"one shot?",
		"that hurt",
		"didnt see u coming",
		"sneaky",
		"u have better gear",
		"ok u win",
		"nice skills",
		"i was low already",
		"mobs took half my hp",
		"again...",
		"not again",
		"fml",
		"cant believe it",
		"my bad",
		"misclick",
		"ez for u huh",
		"well played",
		"respect",
		"wait for me",
		"see u soon",
		"this isnt over",
		"next time u wont be so lucky",
		"well played, you got me fair and square",
		"that last hit was crazy, how did u do that",
		"ok you win this one, see you again later",
		"my exp, noooo, i was almost level up",
		"i blame the lag, but ok good fight anyway",
		"back to town i go, thanks for nothing lol",
		"i had no pots left, otherwise you are dead",
		"nice fight, but i will get you next time",
		"res me pls, oh wait, you killed me lol",
		"i will remember your name, see you soon"
	};
	private static final String[] TAUNTS_FLAGGED =
	{
		"flag = free kill",
		"hi",
		"pvp?",
		"sorry, you were flagged",
		"lets go",
		"purple? ok",
		"u asked for it",
		"flag hunter here",
		"free pvp",
		"nice flag",
		"dont flag near me",
		"you are flagged, so you are fair game now",
		"purple name in my spot? not for long pal",
		"if you flag, you better be ready for pvp",
		"flagged players are free kills around here",
		"saw your purple name from across the map",
		"you wanted pvp when you flagged, here it is",
		"flagging near me is a really bad idea",
		"lets see what that purple name is worth",
		"nice flag, too bad i am not scared of it",
		"you should not walk around flagged here",
		"i was bored anyway, thanks for the flag",
		"purple means you are looking for trouble",
		"you flagged, so lets do this the fun way",
		"i always hunt purple names, nothing personal",
		"flag is on, so the gloves are off now",
		"who were you fighting? doesnt matter now",
		"you just hit someone, now its my turn",
		"flagged and alone? that was a mistake",
		"walking around purple, you must be brave",
		"hope your pvp was worth it, here i come",
		"lets see if your fight left you any hp",
		"your flag is still on, lucky me today",
		"i like my exp with a little pvp on the side",
		"purple players are my favourite mobs",
		"you look hurt, let me finish the job",
		"this is what happens when you flag near me",
		"you should wait for your flag to go away",
		"no karma for me if i kill you now, sweet",
		"flag hunting is the best part of this game",
		"you look like you need a trip to town",
		"i will take that flag as an invitation",
		"oh look, a flagged player, free exp for me",
		"did you think nobody would notice the flag?",
		"im always ready for a fight with a flagger",
		"you started the pvp, i am just joining in",
		"a purple name passing by, how convenient",
		"that flag will cost you some exp now",
		"lets have some fun while your flag is up",
		"i dont need a reason, you are flagged",
		"i see purple, i attack, thats the rule",
		"careful with that flag, someone might see it",
		"i dont know who you fought, but you lost now",
		"your fight is not over yet, round two",
		"lets see if you can handle two pvps in a row",
		"i hope you still have some pots left",
		"flag up means fight on, lets go",
		"not every purple name gets away, not you",
		"your flag says pvp and i say yes please",
		"purple and in my spot, you asked for it"
	};
	private static final String[] TAUNTS_KARMA =
	{
		"pk!",
		"die pk",
		"got a pk here",
		"red = dead",
		"pk scum",
		"kill the pk",
		"no pk in my spot",
		"ur karma is showing",
		"payback time",
		"go clean ur karma",
		"justice",
		"a red name in my spot? not on my watch",
		"pk spotted, time to clean up this area",
		"you killed someone innocent, now you pay",
		"red names do not live long around here",
		"hunting pks is my favourite hobby in game",
		"your karma is showing, let me fix that",
		"how many innocent players did you kill?",
		"i will drop your gear for all your victims",
		"this is for everyone you pked today",
		"pk scum like you should stay in town",
		"nice karma, time to lose some of it",
		"i hope you drop that nice weapon of yours",
		"red name means free kill, thats the rule",
		"no pk is safe while i am in this area",
		"you think you can pk here? think again",
		"guards cant help you now, but i can end you",
		"all those kills and now you meet me",
		"time for some justice, pk, lets go",
		"your red name is visible from a mile away",
		"careful pk, i am not an easy target",
		"im going to make you drop something good",
		"pking noobs is easy, try me instead",
		"you picked the wrong place to go red",
		"lets see how brave you are against me",
		"im the reason pks go back to town naked",
		"this server does not need more pks",
		"did you really think nobody would stop you?",
		"your karma will be gone soon, with your gear",
		"killing a pk always makes my day better",
		"you should have cleaned your karma first",
		"i can see your name glowing red, bad luck",
		"a pk in the wild, what a nice surprise",
		"you wont get another innocent kill today",
		"let me send you back to town where you belong",
		"everyone around here will thank me for this",
		"no mercy for pks, never, not even once",
		"so you like killing people? me too, pks",
		"im taking you down before you hurt anyone",
		"you are about to meet your first real fight",
		"pk hunting season just started, run",
		"i hope you are ready to lose some items",
		"your victims say hi, and so do i",
		"red name and alone, that was not smart",
		"lets see if you fight as good as you pk",
		"the only good pk is a dead pk, remember that",
		"cleaning the map one pk at a time",
		"i have been waiting for a pk all day",
		"you should not have gone red near me",
		"drop your weapon, it will happen anyway lol"
	};
	private static final String[] TAUNTS_RETURN =
	{
		"remember me?",
		"round 2",
		"im back",
		"again?",
		"now im buffed",
		"lets try that again",
		"found you",
		"rematch",
		"not so easy now",
		"u thought i was done?",
		"remember me? i died right here to you",
		"i told you i would come back, here i am",
		"round two, and this time i am fully buffed",
		"walked all the way from town just for you",
		"you got lucky last time, not this time",
		"i am back and i brought my good pots",
		"thought you got rid of me? think again",
		"lets see how you do when i am ready",
		"last fight was lag, this one is for real",
		"i just rebuffed in town, now we are even",
		"did you miss me? because i missed you",
		"i never forget the ones who kill me",
		"time for a rematch, no excuses this time",
		"you owe me some exp, i came to collect",
		"this time i wont go down so easily",
		"back from town and very angry now",
		"you killed me once, shame on you",
		"i ran all the way here, lets go",
		"i knew you would still be farming here",
		"you really thought that was the end of it?",
		"i came back just to settle our little score",
		"it took me a while, but i found you again",
		"lets finish what we started earlier",
		"i am not done with you yet, not even close",
		"this is the part where you run away",
		"all my buffs are fresh, you are not",
		"i remember your face, and your name too",
		"a second chance to beat me, good luck",
		"this spot is mine and so is this fight",
		"you should have logged out while you could",
		"revenge time, i hope you are ready for it",
		"same place, same guy, different ending",
		"you took my exp, now i take yours",
		"fully potted, fully buffed, fully angry",
		"i only lose to lag, and my ping is fine now",
		"walk of shame from town is over, lets go",
		"guess who is back to reclaim this spot",
		"i practiced my combo in town, wanna see?",
		"you wont get lucky twice in a row",
		"there you are, i was looking all over",
		"i told my clan about you, they say hi",
		"back for more and this time you go down",
		"you hit and run, now i hit and stay",
		"i have been waiting for this all the way here",
		"lets see who goes to town this time",
		"killing me once was easy, twice is not",
		"you should know by now, i always come back",
		"the first fight was a warm up, this is real",
		"here we go again, but this time i win",
		"i am like a bad dream, i keep coming back"
	};
	/** Said when it first sees a player (see {@link FakePlayerPvpConfig#GREET_CHANCE}). */
	private static final String[] TAUNTS_GREET =
	{
		"hi",
		"hey",
		"yo",
		"hello",
		"sup",
		"hi there",
		"heya",
		"o/",
		"hey hey",
		"hi :)",
		"yo whats up",
		"hello there",
		"sup dude",
		"evening",
		"hey man",
		"hi hi",
		"howdy",
		"oh hi",
		"oh hey",
		"hey :)",
		"hello :)",
		"yo yo",
		"hiya",
		"hi all",
		"hey guys",
		"greetings",
		"sup bro",
		"hey stranger",
		"long time no see",
		"oh another one",
		"finally someone",
		"didnt expect company",
		"company!",
		"share spot?",
		"spot is big enough for us",
		"hey good hunting",
		"hi gl",
		"gl hf",
		"gl with drops",
		"wow people here",
		"hows exp here?",
		"hello friend",
		"hey lets not ks ok?",
		"careful mobs hit hard here",
		"oh hi didnt see u",
		"hey nice gear",
		"rare to see someone here",
		"hello mate",
		"hey neighbor",
		"whats up",
		"hey there, how is the exp in this spot?",
		"hello, mind if i farm around here too?",
		"hi, looks like we both had the same idea",
		"oh hey, didnt expect anyone out here today",
		"hi, good luck with the drops today",
		"hey, lets not ks each other, deal?",
		"hello there, this spot is big enough for two",
		"yo, finally someone else is farming here",
		"hi, be careful the mobs here hit pretty hard",
		"hey, nice to see another player out here"
	};
	/** Said when a player hits the monster it is fighting. */
	private static final String[] TAUNTS_MOB_HUNT =
	{
		"hey thats my mob",
		"i was on that one",
		"dude",
		"my mob",
		"go find ur own",
		"?",
		"wtf",
		"hey",
		"lol really",
		"stop hitting my mob",
		"im already on it",
		"get off my mob",
		"ks?",
		"plenty mobs around",
		"not cool",
		"this spot is mine",
		"i pulled it first",
		"my target",
		"u see me hitting it right?",
		"bro wtf",
		"ok nice",
		"seriously",
		"hey hey hey",
		"back off",
		"thats mine",
		"i had aggro",
		"leave it",
		"take the next one",
		"there are others",
		"stop",
		"pls no ks",
		"cmon",
		"why",
		"hands off",
		"i was here first",
		"rude",
		"ks alert",
		"go away",
		"dont",
		"wait ur turn",
		"omg",
		"come on man",
		"u blind?",
		"last warning",
		"keep doing that and see",
		"want pvp?",
		"u looking for trouble?",
		"mine!",
		"ffs",
		"unbelievable",
		"hey, i was already hitting that mob, stop",
		"please find your own mob, this one is mine",
		"do you not see me fighting that mob right now?",
		"dude, i pulled that one first, back off",
		"there are plenty of mobs around, take another",
		"you touch my mob again and we will have a problem",
		"stop hitting my mob, last warning buddy",
		"i have been fighting that thing for a minute",
		"seriously, get your own mob, this is mine",
		"if you want my mob, you have to fight me first"
	};
	
	/** Said in general chat standing next to a lower level, before it hits them once to invite a PvP (3 to 60 characters). */
	private static final String[] TAUNTS_POKE =
	{
		"pvp?",
		"1v1",
		"fight me",
		"u scared?",
		"hit me back",
		"wanna duel? :)",
		"show me what u got",
		"u gonna hit back or what?",
		"this spot is mine now, fight for it",
		"lets see if you can handle a real pvp",
		"you farm here, you pay the toll. hit me back",
		"i am bored, lets see how long you can last vs me",
		"come on, hit back or go farm somewhere else noob",
		"one hit coming, show me you are not a coward",
		"flag up and fight me, or keep farming like a scared kid",
		"incoming hit, answer it or run, your choice",
		"whats wrong? afraid of a little pvp? lol",
		"hey you, yes you, 1v1 me right here",
		"ur gear looks weak, lets test it",
		"gg in advance"
	};
	
	/** Said when it doesn't hit back a higher level that attacks it while it isn't flagged. */
	private static final String[] TAUNTS_REFUSE =
	{
		"not flagging",
		"enjoy the karma",
		"go ahead, get red",
		"not gonna hit back",
		"im not flagging for you",
		"pk me then",
		"no pvp, im farming",
		"lol no",
		"ur higher lvl, go away",
		"pick on someone your level",
		"im not fighting u",
		"leave me alone",
		"hope u like karma",
		"kill me and u go red",
		"not worth it",
		"go bother someone else",
		"what do u want",
		"bored?"
	};
	
	/** Said back to another fake player that said hello. */
	private static final String[] TAUNTS_GREET_REPLY =
	{
		"hi",
		"hey",
		"yo",
		"o/",
		"sup",
		"hey hey",
		"hi :)",
		"hello",
		"gl",
		"gl hf",
		"hey, gl",
		"yo, good hunting",
		"hi, u too",
		"heya",
		"hey man",
		"sup, exp is ok here",
		"hi, mobs are fine here",
		"hey, dont ks me lol",
		"hello, plenty mobs for both",
		"hi, ty gl to u too"
	};
	
	/** Said when it joins a fight of two other fake players. */
	private static final String[] TAUNTS_JOIN_FIGHT =
	{
		"free kill",
		"mind if i join?",
		"third party!",
		"im in",
		"lol thx for the hp",
		"dont mind me",
		"2v1 now",
		"ffa",
		"thats what u get for flagging",
		"flagged = fair game",
		"oh a fight, nice",
		"saw purple, came running",
		"u two done? my turn",
		"cleaning up",
		"one flagger less"
	};
	
	/** A short talk of two fake players that met at a hunting ground, lines said by turns (the one that walked over first). */
	private static final String[][] DIALOGUES_HUNTING =
	{
		{
			"hey, hows the exp here?",
			"not bad, bit slow",
			"better than the last spot i was at"
		},
		{
			"yo, any good drops?",
			"nah, only mats so far",
			"same lol",
			"gl"
		},
		{
			"hi, solo?",
			"yeah, party left",
			"same, everyone afk tonight"
		},
		{
			"hey, u farming here long?",
			"like an hour",
			"ok ill take the other side",
			"np gl"
		},
		{
			"sup, want to party?",
			"nah im fine solo, ty",
			"ok gl"
		},
		{
			"hi, is it always this empty here?",
			"at night yeah",
			"nice, more mobs for us"
		},
		{
			"yo, u have ss to sell?",
			"no, only for me sry",
			"np"
		},
		{
			"hey, this spot ok for my lvl?",
			"yeah its fine",
			"thx"
		},
		{
			"hi, did u see any pk around?",
			"one red guy before, he left",
			"ok ty, ill watch out"
		},
		{
			"yo, whats ur clan?",
			"no clan atm",
			"we recruit if u want",
			"maybe later ty"
		},
		{
			"hey, spoiler around?",
			"didnt see one",
			"ok"
		},
		{
			"hi, server lagging for u too?",
			"a bit yeah",
			"thought it was my pc lol"
		},
		{
			"how much exp per mob here?",
			"dunno, decent",
			"ok ill stay a bit"
		},
		{
			"hey, lets split the spot?",
			"ok u left me right",
			"deal"
		},
		{
			"yo, got any potions?",
			"few, why",
			"nvm i have some left",
			"lol ok"
		},
		{
			"hi, nice weapon",
			"ty, took me ages",
			"enchanted?",
			"a little"
		},
		{
			"hey, raid up today?",
			"no idea",
			"ok gl"
		},
		{
			"hi, how long to next lvl?",
			"like 2 hours",
			"rip",
			"lol yeah"
		},
		{
			"yo",
			"yo",
			"mobs respawn fast here",
			"yeah its good"
		},
		{
			"hey, u see the mob with the chest?",
			"no",
			"must have been killed already",
			"rip"
		},
		{
			"hi, back from town?",
			"yeah had to repot",
			"same, prices are crazy",
			"true"
		},
		{
			"hey, this zone is hot today",
			"yeah exp is good",
			"lets make the most of it"
		},
		{
			"hi, any buffer around?",
			"no, i only have my own",
			"ok ty"
		},
		{
			"sup, going to farm long?",
			"till i drop lol",
			"same",
			"gl then"
		}
	};
	
	/** A talk of two fake players that ends in a fight over the spot (the one that walked over first hits first). */
	private static final String[][] DIALOGUES_RIVALRY =
	{
		{
			"this is my spot",
			"says who?",
			"says me"
		},
		{
			"hey, u ks me all the time",
			"lol no",
			"ok then lets settle it"
		},
		{
			"move, i farm here",
			"spot is free",
			"not anymore"
		},
		{
			"u again?",
			"what",
			"i told u this is my spot"
		},
		{
			"go farm somewhere else",
			"no",
			"ok ur choice"
		},
		{
			"how about a duel for the spot?",
			"lol go away",
			"wrong answer"
		},
		{
			"ur stealing my mobs",
			"they are not urs",
			"we will see"
		},
		{
			"hey, wanna pvp?",
			"im farming",
			"not anymore u are not"
		},
		{
			"ur gear looks weak",
			"come test it",
			"ok"
		},
		{
			"last time u ran away",
			"didnt",
			"lets see this time"
		},
		{
			"too many people here",
			"so leave",
			"i was thinking u leave"
		},
		{
			"ill give u 10 sec to leave",
			"lol",
			"time is up"
		}
	};
	
	/** Social actions: wave, bow. */
	private static final int SOCIAL_GREETING = 2;
	private static final int SOCIAL_BOW = 7;
	/** Milliseconds a fake player takes to type one character, like a player chatting (min, max). */
	private static final int TYPING_MIN = 60;
	private static final int TYPING_MAX = 120;
	/** A talk goes on this far at most: the partners walk off (or start fighting) if it took longer, like players busy with their hunt. */
	private static final long TALK_MAX_TIME = 30000;
	
	private final AtomicInteger _nextNpcId = new AtomicInteger(FIRST_NPC_ID);
	private final Set<Npc> _fakePlayers = ConcurrentHashMap.newKeySet();
	private final Set<String> _names = ConcurrentHashMap.newKeySet();
	/** Fake players killed by a player that come back to where they died (see {@link #returnFakePlayer}), by name. */
	private final Map<String, ScheduledFuture<?>> _pendingReturns = new ConcurrentHashMap<>();
	
	protected FakePlayerPvpManager()
	{
		// Also runs while spawning is off: fake players spawned with //fakepvp still need rebuffs and their lifetime.
		ThreadPool.scheduleAtFixedRate(this::maintain, MAINTAIN_INTERVAL, MAINTAIN_INTERVAL);
		
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
		
		// The passive trees are grown here, once, so a spawn only adds up a prepared one.
		if (FakePlayerPvpPassiveTree.isEnabled())
		{
			FakePlayerPvpPassiveTree.getInstance();
		}
	}
	
	/**
	 * @return {@code true} if roaming fake players can spawn
	 */
	public boolean isEnabled()
	{
		return FakePlayerPvpConfig.ENABLED && FakePlayersConfig.FAKE_PLAYERS_ENABLED && !FakePlayerPvpData.getInstance().getBuilds().isEmpty();
	}
	
	/**
	 * Checks the spawn point itself: the monster isn't in the world yet, so its zone flags aren't set.
	 * @param x the x
	 * @param y the y
	 * @param z the z
	 * @return {@code true} if the point is inside a hotzone the rotation currently has active
	 */
	private static boolean isInHotzone(int x, int y, int z)
	{
		return getActiveHotzoneId(x, y, z) != 0;
	}
	
	/**
	 * Every hotzone stays enabled, but only the rotation's current picks are hot (see {@link HotzoneModifierManager#isActive}).
	 * @param x the x
	 * @param y the y
	 * @param z the z
	 * @return the id of the active hotzone at that point, 0 if none
	 */
	private static int getActiveHotzoneId(int x, int y, int z)
	{
		for (ZoneType zone : ZoneManager.getInstance().getZones(x, y, z))
		{
			if ((zone instanceof HotZone) && HotzoneModifierManager.getInstance().isActive(zone.getId()))
			{
				return zone.getId();
			}
		}
		
		return 0;
	}
	
	/**
	 * Rolls {@link FakePlayerPvpConfig#SPAWN_CHANCE} (times {@link FakePlayerPvpConfig#HOTZONE_SPAWN_MULTIPLIER} inside a hotzone) for a monster that is about to enter the world and, if it hits, spawns a roaming fake player in its place. The monster is then kept out of the world, still counted by {@code spawn}, until the fake player is gone.
	 * @param npc the monster that is spawning
	 * @param spawn its spawn point
	 * @param x the spawn x
	 * @param y the spawn y
	 * @param z the spawn z
	 * @return {@code true} if a fake player took the monster's place and the monster must not be spawned
	 */
	public boolean tryReplace(Npc npc, Spawn spawn, int x, int y, int z)
	{
		if (!isEnabled() || !isReplaceable(npc, spawn))
		{
			return false;
		}
		
		final Monster monster = npc.asMonster();
		
		// This spawn point had a fake player recently - sit this spawn out.
		final int cooldown = spawn.getFakePlayerCooldown();
		if (cooldown > 0)
		{
			spawn.setFakePlayerCooldown(cooldown - 1);
			return false;
		}
		
		// Hotzones draw more of them.
		final double chance = isInHotzone(x, y, z) ? FakePlayerPvpConfig.SPAWN_CHANCE * FakePlayerPvpConfig.HOTZONE_SPAWN_MULTIPLIER : FakePlayerPvpConfig.SPAWN_CHANCE;
		if ((Rnd.nextDouble() * 100) >= chance)
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
		
		// Nobody sees a fake player pop out of nowhere: the monster spawns as usual.
		if (isSeenByPlayer(x, y, z, spawn.getInstanceId()))
		{
			return false;
		}
		
		final Npc fake = spawnFakePlayer(FakePlayerPvpData.getInstance().getRandomBuild(), getFakeLevel(monster), x, y, z, spawn.getInstanceId(), monster, spawn);
		if (fake == null)
		{
			return false;
		}
		
		spawn.setFakePlayerCooldown(FakePlayerPvpConfig.RESPAWN_COOLDOWN);
		
		// Out of the world until the fake player is gone, see onFakePlayerDecay().
		monster.setDead(true);
		monster.setDecayed(true);
		
		// Sometimes it comes with friends.
		FakePartyManager.getInstance().onRoamingSpawn(fake);
		return true;
	}
	
	/**
	 * @param npc the npc
	 * @param spawn its spawn point
	 * @return {@code true} if {@code npc} is a regular, respawning world monster a fake player may take the place of
	 */
	private static boolean isReplaceable(Npc npc, Spawn spawn)
	{
		if ((npc == null) || (spawn == null) || (npc.getClass() != Monster.class) || npc.isFakePlayer())
		{
			return false;
		}
		
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
		return (level >= FakePlayerPvpConfig.MIN_LEVEL) && (level <= FakePlayerPvpConfig.MAX_LEVEL) && !FakePlayerPvpConfig.EXCLUDED_NPC_IDS.contains(monster.getId());
	}
	
	/**
	 * @param monster the monster being replaced
	 * @return the level of the fake player that takes its place
	 */
	private static int getFakeLevel(Monster monster)
	{
		return Math.max(1, Math.min(85, monster.getLevel() + (FakePlayerPvpConfig.LEVEL_VARIANCE > 0 ? Rnd.get(-FakePlayerPvpConfig.LEVEL_VARIANCE, FakePlayerPvpConfig.LEVEL_VARIANCE) : 0)));
	}
	
	private boolean isAllowedLocation(int x, int y, int z, int instanceId)
	{
		if (!isAllowedZone(x, y, z))
		{
			return false;
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
	 * @param x the x
	 * @param y the y
	 * @param z the z
	 * @return {@code true} if the point is not in town, a no PvP zone, a siege, an arena, a boss zone...
	 */
	private static boolean isAllowedZone(int x, int y, int z)
	{
		for (ZoneType zone : ZoneManager.getInstance().getZones(x, y, z))
		{
			if ((zone instanceof PeaceZone) || (zone instanceof TownZone) || (zone instanceof NoPvPZone) || (zone instanceof SiegeZone) || (zone instanceof ArenaZone) || (zone instanceof OlympiadStadiumZone) || (zone instanceof JailZone) || (zone instanceof BossZone) || (zone instanceof CastleZone) || (zone instanceof FortZone) || (zone instanceof ClanHallZone))
			{
				return false;
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
			profile.setHotzoneId(getActiveHotzoneId(x, y, z)); // It leaves once that hotzone rotates out.
			
			final Npc fake = spawnFromTemplate(template, x, y, z, instanceId);
			if (fake == null)
			{
				FakePlayerData.getInstance().removeFakePlayer(name);
				_names.remove(name.toLowerCase());
			}
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
	 * Spawns a class transfer challenge opponent: a fake player of {@code build} forced into {@code playerClass}, that only fights {@code target}. It never flees, escapes, logs off or comes back after dying, stays flagged (so it never refuses the fight and killing it is never a PK), and its
	 * death pays no PvP count, karma or reward (see {@link FakePlayerPvpProfile#isTrialDuelist()}). The challenge removes it with {@code deleteMe()} or by destroying its instance.
	 * @param build the build
	 * @param level the level
	 * @param playerClass a class of the build's class line, {@code null} for the one its level gives
	 * @param title the title it shows
	 * @param x the x
	 * @param y the y
	 * @param z the z
	 * @param instanceId the instance
	 * @param target the challenger it duels
	 * @return the opponent, or {@code null} if it could not be created
	 */
	public Npc spawnTrialDuelist(FakePlayerPvpBuild build, int level, PlayerClass playerClass, String title, int x, int y, int z, int instanceId, Player target)
	{
		if ((build == null) || (target == null))
		{
			return null;
		}
		
		final String name = generateName();
		try
		{
			final NpcTemplate template = FakePlayerPvpFactory.createTemplate(build, level, _nextNpcId.getAndIncrement(), name, playerClass, title);
			if (template == null)
			{
				_names.remove(name.toLowerCase());
				return null;
			}
			
			final FakePlayerPvpProfile profile = template.getFakePlayerPvpProfile();
			profile.setSpawnTime(System.currentTimeMillis());
			profile.setHotzoneId(0);
			profile.setBlessedEscape(false);
			profile.setTrialDuelTarget(target.getObjectId());
			profile.onReturn(0); // Marks it as already returned: it never walks back after dying.
			
			final Npc fake = spawnFromTemplate(template, x, y, z, instanceId);
			if (fake == null)
			{
				FakePlayerData.getInstance().removeFakePlayer(name);
				_names.remove(name.toLowerCase());
				return null;
			}
			
			// Flagged for good (no PvP flag timer is started for a script value set by hand).
			fake.setScriptValue(1);
			fake.broadcastInfo();
			engageTrialTarget(fake.asAttackable());
			return fake;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not spawn trial duelist " + build.getName() + " level " + level + ".", e);
			FakePlayerData.getInstance().removeFakePlayer(name);
			_names.remove(name.toLowerCase());
			return null;
		}
	}
	
	/**
	 * Points a class transfer challenge opponent at its challenger when the challenger can be fought (online, alive, attackable and in the same instance).
	 * @param fake the opponent
	 * @return {@code true} if it is (now) fighting its challenger
	 */
	public boolean engageTrialTarget(Attackable fake)
	{
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		if ((profile == null) || !profile.isTrialDuelist() || fake.isDead())
		{
			return false;
		}
		
		final Player target = World.getInstance().getPlayer(profile.getTrialDuelTarget());
		if ((target == null) || !target.isOnline() || target.isDead() || target.isInvul() || (target.getInstanceId() != fake.getInstanceId()))
		{
			return false;
		}
		
		if (isFighting(fake, target))
		{
			return true;
		}
		
		startFight(fake, target, TAUNTS_FLAGGED);
		return true;
	}
	
	/**
	 * Spawns a roaming fake player from its template: toggles on, buffed, full HP/MP - like a player that just arrived.
	 * @param template the fake player template
	 * @param x the x
	 * @param y the y
	 * @param z the z
	 * @param instanceId the instance
	 * @return the fake player, or {@code null} if it could not be spawned
	 * @throws Exception if the spawn could not be created
	 */
	private Npc spawnFromTemplate(NpcTemplate template, int x, int y, int z, int instanceId) throws Exception
	{
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
			return null;
		}
		
		_fakePlayers.add(fake);
		
		final FakePlayerPvpProfile profile = template.getFakePlayerPvpProfile();
		PassiveTreeManager.getInstance().applyToFakePlayer(fake, profile.getPassives()); // Before the HP is filled up: the tree raises max HP.
		profile.setTransform(0, null);
		profile.setDisarmedWeapon(null);
		template.getFakePlayerInfo().setTransformDisplayId(0);
		refreshToggles(fake, profile);
		refreshBuffs(fake, profile);
		fake.setCurrentHpMp(fake.getMaxHp(), fake.getMaxMp());
		profile.setSouls(getMaxSouls(fake)); // A Kamael comes from its hunt with its souls.
		fake.broadcastInfo();
		
		// A necromancer arrives with its servitor out, like a player.
		if (profile.needsServitor())
		{
			profile.getSkills(SkillCategory.SUMMON).get(0).applyEffects(fake, fake);
		}
		return fake;
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
		
		unsummonServitor(fake);
		
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
		
		// Dead in a party with players: it comes back to its party from town, its name stays taken until then.
		if (FakePartyManager.getInstance().onFakeDecay(fake))
		{
			return;
		}
		
		// Killed by a player and walking back from town: its name stays taken until it comes back.
		if (profile.isReturnPending() && isEnabled())
		{
			final NpcTemplate template = fake.getTemplate();
			final String name = fake.getName();
			_pendingReturns.put(name, ThreadPool.schedule(() -> returnFakePlayer(template, 0), Rnd.get(FakePlayerPvpConfig.RETURN_DELAY_MIN, FakePlayerPvpConfig.RETURN_DELAY_MAX) * 1000L));
			return;
		}
		
		_names.remove(fake.getName().toLowerCase());
	}
	
	/**
	 * Like a player that walks back from town for round two, a fake player killed by a player comes back to where it died (see {@link FakePlayerPvpConfig#RETURN_CHANCE}): same template, so same name, looks and gear. It looks for its killer for a while
	 * ({@link org.l2jmobius.gameserver.ai.FakePlayerPvpAI}, then {@link #revenge}) and otherwise hunts like any other fake player. It doesn't replace a monster anymore, so killing it again gives no monster loot or exp. It doesn't appear in front of a player: when a
	 * player sees the spot, it arrives out of sight and walks back to it (see {@link #findArrival}), and when there is no such place it tries again a bit later.
	 * @param template the template of the fake player that died
	 * @param retries how many times it already tried
	 */
	private void returnFakePlayer(NpcTemplate template, int retries)
	{
		// Cancelled meanwhile (//fakepvp_clear).
		final String name = template.getName();
		if (_pendingReturns.remove(name) == null)
		{
			return;
		}
		
		final FakePlayerPvpProfile profile = template.getFakePlayerPvpProfile();
		final int x = profile.getDeathX();
		final int y = profile.getDeathY();
		final int z = profile.getDeathZ();
		final int instanceId = profile.getDeathInstanceId();
		if (!isEnabled() || ((FakePlayerPvpConfig.MAX_ALIVE > 0) && (_fakePlayers.size() >= FakePlayerPvpConfig.MAX_ALIVE)) || !isAllowedZone(x, y, z) || ((instanceId != 0) && (InstanceManager.getInstance().getInstance(instanceId) == null)))
		{
			_names.remove(name.toLowerCase());
			return;
		}
		
		final Location arrival = findArrival(x, y, z, instanceId);
		if (arrival == null)
		{
			if (retries < RETURN_MAX_RETRIES)
			{
				_pendingReturns.put(name, ThreadPool.schedule(() -> returnFakePlayer(template, retries + 1), RETURN_RETRY_DELAY * 1000L));
			}
			else
			{
				_names.remove(name.toLowerCase());
			}
			return;
		}
		
		try
		{
			profile.setReplacedMonster(null, null);
			profile.setSpawnTime(System.currentTimeMillis());
			profile.onReturn(System.currentTimeMillis() + (profile.getPersonality().getReturnRevengeTime() * 1000L));
			
			// It comes back with its weapon out, not the bow or polearm it may have died with (the new body only has the skills of the template).
			if (profile.getHeldWeapon() != profile.getMainWeapon())
			{
				setTemplateWeapon(template, profile, profile.getMainWeapon());
			}
			
			// The template was made once, so it is known again by name for whispers.
			final String lowercaseName = name.toLowerCase();
			FakePlayerData.getInstance().addFakePlayerId(name, template.getId());
			FakePlayerData.getInstance().addFakePlayerName(lowercaseName, name);
			FakePlayerData.getInstance().addTalkableFakePlayerName(lowercaseName);
			
			final Npc fake = spawnFromTemplate(template, arrival.getX(), arrival.getY(), arrival.getZ(), instanceId);
			if (fake == null)
			{
				FakePlayerData.getInstance().removeFakePlayer(name);
				_names.remove(lowercaseName);
			}
			else if (fake.getSpawn() != null)
			{
				// Its hunting ground is still where it died: its AI walks it back there.
				fake.getSpawn().setXYZ(x, y, z);
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not bring back fake player " + name + ".", e);
			FakePlayerData.getInstance().removeFakePlayer(name);
			_names.remove(name.toLowerCase());
		}
	}
	
	/**
	 * @param x where it died
	 * @param y where it died
	 * @param z where it died
	 * @param instanceId the instance
	 * @return where a fake player coming back from town appears: where it died if no player sees it, otherwise a point out of sight that it can walk back from, {@code null} if there is none
	 */
	private static Location findArrival(int x, int y, int z, int instanceId)
	{
		if (!isSeenByPlayer(x, y, z, instanceId))
		{
			return new Location(x, y, z);
		}
		
		final int distance = FakePlayerPvpConfig.UNSEEN_RANGE + 300;
		final double start = Rnd.nextDouble() * 2 * Math.PI;
		for (int i = 0; i < 8; i++)
		{
			final double angle = start + ((i * Math.PI) / 4);
			final Location point = GeoEngine.getInstance().getValidLocation(x, y, z, x + (int) (Math.cos(angle) * distance), y + (int) (Math.sin(angle) * distance), z, instanceId);
			
			// A wall close by: not a way back.
			final long dx = point.getX() - x;
			final long dy = point.getY() - y;
			if (Math.sqrt((dx * dx) + (dy * dy)) < (distance / 2))
			{
				continue;
			}
			
			if (isAllowedZone(point.getX(), point.getY(), point.getZ()) && !isSeenByPlayer(point.getX(), point.getY(), point.getZ(), instanceId))
			{
				return point;
			}
		}
		
		return null;
	}
	
	/**
	 * Called by the fake player AI when a returned fake player finds the player that killed it.
	 * @param fake the fake player
	 * @param player its killer
	 */
	public void revenge(Attackable fake, Player player)
	{
		fake.getTemplate().getFakePlayerPvpProfile().clearRevengeTarget();
		if (!fake.isDead() && !isFighting(fake, player))
		{
			startFight(fake, player, TAUNTS_RETURN);
		}
	}
	
	/**
	 * Cancels the fake players on their way back to where they died.
	 * @return how many were cancelled
	 */
	public int clearPendingReturns()
	{
		int count = 0;
		for (Map.Entry<String, ScheduledFuture<?>> entry : _pendingReturns.entrySet())
		{
			if (_pendingReturns.remove(entry.getKey(), entry.getValue()))
			{
				entry.getValue().cancel(false);
				_names.remove(entry.getKey().toLowerCase());
				count++;
			}
		}
		
		// And the party fake players on their way back to their party.
		count += FakePartyManager.getInstance().clearReturns();
		return count;
	}
	
	/**
	 * Monsters (and anything else that isn't a player, a player's summon or another roaming fake player) cannot bring a roaming fake player below {@link FakePlayerPvpConfig#MONSTER_DAMAGE_FLOOR}% HP.
	 * @param fake the fake player being hit
	 * @param damage the damage
	 * @param attacker who deals it, can be {@code null}
	 * @return the damage it actually takes
	 */
	public double limitDamage(Attackable fake, double damage, Creature attacker)
	{
		if ((attacker != null) && isPvpEnemy(attacker))
		{
			return damage;
		}
		
		// In a party with players, monsters can kill it like a player (see FakePartyConfig#CAN_DIE).
		if (FakePartyManager.getInstance().canBeKilledByMonsters(fake))
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
			if (!fake.isPvpFakePlayer() || fake.isDead() || !fake.hasAI() || isFighting(fake, killer) || FakePartyManager.getInstance().isSameGroup(fake, killer))
			{
				return;
			}
			
			// Only a stolen kill counts: a monster it had already hit, not one it was just running to.
			final AggroInfo damageDone = victim.getAggroList().get(fake);
			if ((damageDone == null) || (damageDone.getDamage() <= 0))
			{
				return;
			}
			
			// Like a player, it doesn't always go for it: not hurt, not against a much higher level, and not every time.
			if ((fake.getCurrentHp() < (fake.getMaxHp() * 0.5)) || (killer.getLevel() >= (fake.getLevel() + OUTLEVELED_DIFFERENCE)) || (Rnd.get(100) >= FakePlayerPvpPersonality.of(fake).getRevengeChance()))
			{
				taunt(fake, TAUNTS_KILL_STEAL_COMPLAIN, false);
				return;
			}
			
			startFight(fake, killer, TAUNTS_KILL_STEAL);
		});
	}
	
	/**
	 * Called by the fake player AI when it gets hit: a player (or another roaming fake player) that attacks it becomes its target.<br>
	 * Like a player that doesn't want to fight, a fake player that isn't flagged may not hit back someone of a higher level (see {@link FakePlayerPvpPersonality#getRefuseChance}): it goes on hunting and lets them get the karma. It decides once, and keeps to it
	 * while they keep hitting it.
	 * @param fake the fake player
	 * @param attacker the attacker
	 */
	public void onFakePlayerAttacked(Attackable fake, Creature attacker)
	{
		final Creature enemy = getPvpEnemy(attacker);
		if ((enemy == null) || (enemy == fake) || fake.isDead() || isFighting(fake, enemy) || FakePartyManager.getInstance().isSameGroup(fake, enemy))
		{
			return;
		}
		
		// Its party fights them too.
		FakePartyManager.getInstance().onFakeAttacked(fake, enemy);
		
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		if (profile == null)
		{
			return;
		}
		
		final long now = System.currentTimeMillis();
		final boolean unflagged = fake.isScriptValue(0) && (fake.getKarma() <= 0);
		if (profile.isRefusing(enemy, now))
		{
			// Still not hitting back, for a while longer since they go on.
			if (unflagged)
			{
				profile.refuse(enemy, now + REFUSE_MEMORY);
				return;
			}
			
			// Flagged meanwhile (it attacked someone): no reason to hold back anymore.
			profile.stopRefusing(enemy);
		}
		else if (unflagged && (enemy.getLevel() > fake.getLevel()) && (Rnd.get(100) < getRefuseChance(profile, enemy, enemy.getLevel() - fake.getLevel())))
		{
			profile.refuse(enemy, now + REFUSE_MEMORY);
			taunt(fake, TAUNTS_REFUSE, false);
			return;
		}
		
		startFight(fake, enemy, TAUNTS_ATTACKED);
	}
	
	/**
	 * @param profile the profile of the fake player that is attacked
	 * @param enemy who attacks it (a player, or another roaming fake player)
	 * @param levelDiff how many levels {@code enemy} is above it
	 * @return its chance (in %) not to hit {@code enemy} back, scaled by {@link FakePlayerPvpConfig#FAKE_REFUSE_SCALE} against another fake player
	 */
	private static int getRefuseChance(FakePlayerPvpProfile profile, Creature enemy, int levelDiff)
	{
		final int chance = profile.getPersonality().getRefuseChance(levelDiff);
		return enemy.isPvpFakePlayer() ? (chance * FakePlayerPvpConfig.FAKE_REFUSE_SCALE) / 100 : chance;
	}
	
	/**
	 * @param fake a roaming fake player
	 * @param attacker a creature that attacked it
	 * @return {@code true} if it chose not to hit {@code attacker} (or its owner) back, see {@link #onFakePlayerAttacked}
	 */
	public static boolean isRefusing(Attackable fake, Creature attacker)
	{
		final Creature enemy = getPvpEnemy(attacker);
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		return (enemy != null) && (profile != null) && profile.isRefusing(enemy, System.currentTimeMillis());
	}
	
	/**
	 * @param creature a creature
	 * @return {@code true} if a roaming fake player fighting {@code creature} is in a PvP: a player, a player's summon, or another roaming fake player
	 */
	public static boolean isPvpEnemy(Creature creature)
	{
		return (creature != null) && (creature.isPlayable() || creature.isPvpFakePlayer());
	}
	
	/**
	 * @param attacker a creature that attacks a roaming fake player
	 * @return who it would fight for that: the player (the owner of a summon), or the other roaming fake player, {@code null} for anything else
	 */
	private static Creature getPvpEnemy(Creature attacker)
	{
		if (attacker == null)
		{
			return null;
		}
		
		if (attacker.isPvpFakePlayer())
		{
			return attacker;
		}
		
		// Another fake player's servitor: that fake player.
		if (attacker instanceof FakePlayerPvpServitor)
		{
			final Npc owner = ((FakePlayerPvpServitor) attacker).getOwner();
			return (owner != null) && owner.isPvpFakePlayer() ? owner : null;
		}
		
		return attacker.asPlayer();
	}
	
	/**
	 * Called by the fake player AI when it walks up to a lower level and hits it once to invite a PvP.
	 * @param fake the fake player
	 */
	public void onPoke(Attackable fake)
	{
		taunt(fake, TAUNTS_POKE, false);
	}
	
	/**
	 * Called when a roaming fake player is killed by another roaming fake player: a few words from the ground. There is nothing to loot, no exp and no coming back for revenge, which are for players.
	 * @param victim the fake player that died
	 * @param killer the fake player that killed it
	 */
	public void onFakePlayerKilledByFake(Attackable victim, Creature killer)
	{
		taunt(victim, TAUNTS_DEATH, true);
	}
	
	/**
	 * Called by the fake player AI when it goes after a flagged (purple) or karma (red) player passing by, like PvPers do.
	 * @param fake the fake player
	 * @param player the player
	 * @param karma {@code true} if the player is a PK
	 */
	public void attackPlayer(Attackable fake, Player player, boolean karma)
	{
		if (!fake.isDead() && !isFighting(fake, player))
		{
			startFight(fake, player, karma ? TAUNTS_KARMA : TAUNTS_FLAGGED);
		}
	}
	
	/**
	 * Called by the fake player AI when it goes after another roaming fake player that is flagged (in a fight) or has karma: it joins the fight, like players that can't resist a purple name.
	 * @param fake the fake player
	 * @param other the other fake player
	 * @param karma {@code true} if the other one is a PK
	 */
	public void attackFakePlayer(Attackable fake, Npc other, boolean karma)
	{
		if (!fake.isDead() && !isFighting(fake, other))
		{
			startFight(fake, other, karma ? TAUNTS_KARMA : TAUNTS_JOIN_FIGHT);
		}
	}
	
	/**
	 * Called by the fake player AI the first time it sees another roaming fake player: maybe a hello in general chat, and the other one may say hello back (and wave).
	 * @param fake the fake player
	 * @param other the other fake player
	 */
	public void greetFake(Attackable fake, Npc other)
	{
		if (!taunt(fake, TAUNTS_GREET, false, FakePlayerPvpPersonality.of(fake).getGreetChance()))
		{
			return;
		}
		
		// The chattier, the more likely it answers.
		final FakePlayerPvpPersonality personality = FakePlayerPvpPersonality.of(other);
		if (Rnd.get(100) >= (int) Math.round(50 + (40 * personality.getChattiness())))
		{
			return;
		}
		
		final FakePlayerPvpProfile profile = other.getTemplate().getFakePlayerPvpProfile();
		final long now = System.currentTimeMillis();
		if ((profile == null) || (now < profile.getNextChatTime()) || isInPvp(other))
		{
			return;
		}
		profile.setNextChatTime(now + CHAT_INTERVAL);
		
		final String text = TAUNTS_GREET_REPLY[Rnd.get(TAUNTS_GREET_REPLY.length)];
		ThreadPool.schedule(() ->
		{
			if (!other.isDead() && other.isSpawned() && !isInPvp(other))
			{
				social(other, Rnd.nextBoolean() ? SOCIAL_GREETING : SOCIAL_BOW);
				other.broadcastPacket(new CreatureSay(other, ChatType.GENERAL, other.getName(), text));
			}
		}, Rnd.get(2500, 5000) + typingTime(text));
	}
	
	/**
	 * Two roaming fake players that met at their hunting ground talk a bit in general chat, standing there facing each other (see {@link FakePlayerPvpAI#holdForTalk}): the one that walked over waves and starts, the other one answers, and so on. With
	 * {@code rivalry} the talk is about the spot and ends with the first one hitting the other ({@link FakePlayerPvpAI#startRivalry}), who may or may not hit back ({@link #onFakePlayerAttacked}). A monster, a player or anything else that starts a fight ends
	 * the talk.
	 * @param fake the fake player that walked over
	 * @param other the one it talks to
	 * @param rivalry {@code true} if it wants the spot
	 */
	public void converse(Attackable fake, Npc other, boolean rivalry)
	{
		if (!(fake.getAI() instanceof FakePlayerPvpAI) || !other.hasAI() || !(other.getAI() instanceof FakePlayerPvpAI))
		{
			return;
		}
		
		final FakePlayerPvpAI fakeAI = (FakePlayerPvpAI) fake.getAI();
		final FakePlayerPvpAI otherAI = (FakePlayerPvpAI) other.getAI();
		final String[][] dialogues = rivalry ? DIALOGUES_RIVALRY : DIALOGUES_HUNTING;
		final String[] lines = dialogues[Rnd.get(dialogues.length)];
		
		// When each line is said: it is typed, read by the other one, who types the answer...
		final long[] times = new long[lines.length];
		long time = Rnd.get(800, 1800);
		for (int i = 0; i < lines.length; i++)
		{
			time += typingTime(lines[i]);
			times[i] = Math.min(time, TALK_MAX_TIME);
			time += Rnd.get(800, 2000);
		}
		final long end = times[lines.length - 1] + Rnd.get(1500, 3000);
		
		final long now = System.currentTimeMillis();
		fakeAI.holdForTalk(other, now + end + 1000);
		otherAI.holdForTalk(fake, now + end + 1000);
		for (Npc speaker : new Npc[]
		{
			fake,
			other
		})
		{
			final FakePlayerPvpProfile profile = speaker.getTemplate().getFakePlayerPvpProfile();
			if (profile != null)
			{
				profile.setNextChatTime(now + end + CHAT_INTERVAL);
			}
		}
		
		// A wave to start with, and one back.
		ThreadPool.schedule(() -> social(fake, SOCIAL_GREETING), 300);
		ThreadPool.schedule(() ->
		{
			if (otherAI.isTalkingWith(fake))
			{
				social(other, Rnd.nextBoolean() ? SOCIAL_GREETING : SOCIAL_BOW);
			}
		}, Math.min(times[0] + 800, end));
		
		for (int i = 0; i < lines.length; i++)
		{
			final boolean first = (i % 2) == 0;
			final Npc speaker = first ? fake : other;
			final Npc listener = first ? other : fake;
			final FakePlayerPvpAI speakerAI = first ? fakeAI : otherAI;
			final String text = lines[i];
			ThreadPool.schedule(() ->
			{
				if (!speaker.isDead() && speaker.isSpawned() && speakerAI.isTalkingWith(listener))
				{
					speaker.broadcastPacket(new CreatureSay(speaker, ChatType.GENERAL, speaker.getName(), text));
				}
			}, times[i]);
		}
		
		// The end: they go back to hunting (with a nod now and then), or the first one picks the fight.
		ThreadPool.schedule(() ->
		{
			if (!fakeAI.isTalkingWith(other) || !otherAI.isTalkingWith(fake))
			{
				return;
			}
			
			fakeAI.endTalk();
			if (rivalry)
			{
				fakeAI.startRivalry(other);
				return;
			}
			
			if (Rnd.nextBoolean())
			{
				social(fake, SOCIAL_BOW);
			}
			if (Rnd.nextBoolean())
			{
				social(other, Rnd.nextBoolean() ? SOCIAL_BOW : SOCIAL_GREETING);
			}
		}, end);
	}
	
	/**
	 * @param text a chat line
	 * @return how long a player takes to type it
	 */
	private static int typingTime(String text)
	{
		return text.length() * Rnd.get(TYPING_MIN, TYPING_MAX);
	}
	
	/**
	 * A social action (wave, bow...), when it stands.
	 * @param fake the fake player
	 * @param actionId the social action
	 */
	private static void social(Npc fake, int actionId)
	{
		if (fake.isDead() || !fake.isSpawned() || fake.isMoving() || fake.isCastingNow() || fake.isAttackingNow())
		{
			return;
		}
		
		final FakePlayerHolder holder = fake.getTemplate().getFakePlayerInfo();
		if ((holder != null) && holder.isSitting())
		{
			return;
		}
		
		fake.broadcastPacket(new SocialAction(fake.getObjectId(), actionId));
	}
	
	/**
	 * Called when a roaming fake player hits a monster: another roaming fake player that is fighting that monster complains in general chat, like about a player (see {@link #onMonsterAttacked}).
	 * @param monster the monster being hit
	 * @param stealer the fake player hitting it
	 */
	public void onMonsterAttackedByFake(Attackable monster, Attackable stealer)
	{
		if ((_fakePlayers.size() < 2) || (FakePlayerPvpConfig.TAUNT_CHANCE <= 0))
		{
			return;
		}
		
		for (Map.Entry<Creature, AggroInfo> entry : monster.getAggroList().entrySet())
		{
			final Creature creature = entry.getKey();
			if ((creature == null) || (creature == stealer) || !creature.isPvpFakePlayer() || creature.isDead() || (entry.getValue().getDamage() <= 0) || (creature.getTarget() != monster) || FakePartyManager.getInstance().isSameGroup(creature, stealer))
			{
				continue;
			}
			
			final Attackable fake = creature.asAttackable();
			if (!isFighting(fake, stealer))
			{
				taunt(fake, TAUNTS_MOB_HUNT, false);
			}
		}
	}
	
	/**
	 * Called when a roaming fake player kills a monster: another roaming fake player that did more damage to it than the killer takes it as a stolen kill, like from a player (see {@link #onAttackableKilled}): it complains, or goes after the killer.
	 * @param victim the monster
	 * @param killer the fake player that killed it
	 */
	public void onMonsterKilledByFake(Attackable victim, Attackable killer)
	{
		if (!FakePlayerPvpConfig.REVENGE_ON_KILL_STEAL || !victim.isMonster() || (_fakePlayers.size() < 2) || killer.isInsideZone(ZoneId.PEACE))
		{
			return;
		}
		
		final AggroInfo killerDamage = victim.getAggroList().get(killer);
		final long stolenDamage = killerDamage != null ? killerDamage.getDamage() : 0;
		World.getInstance().forEachVisibleObjectInRange(victim, Attackable.class, FakePlayerPvpConfig.REVENGE_RANGE, fake ->
		{
			if ((fake == killer) || !fake.isPvpFakePlayer() || fake.isDead() || !fake.hasAI() || fake.isTrialDuelist() || isFighting(fake, killer) || FakePartyManager.getInstance().isSameGroup(fake, killer))
			{
				return;
			}
			
			// Only its own monster counts: one it did most of the work on.
			final AggroInfo damageDone = victim.getAggroList().get(fake);
			if ((damageDone == null) || (damageDone.getDamage() <= 0) || (damageDone.getDamage() < stolenDamage))
			{
				return;
			}
			
			if ((fake.getCurrentHp() < (fake.getMaxHp() * 0.5)) || (killer.getLevel() >= (fake.getLevel() + OUTLEVELED_DIFFERENCE)) || (Rnd.get(100) >= FakePlayerPvpPersonality.of(fake).getRevengeChance()))
			{
				taunt(fake, TAUNTS_KILL_STEAL_COMPLAIN, false);
				return;
			}
			
			startFight(fake, killer, TAUNTS_KILL_STEAL);
		});
	}
	
	/**
	 * Called by the fake player AI the first time it sees a player: maybe a hello in general chat.
	 * @param fake the fake player
	 */
	public void greet(Attackable fake)
	{
		taunt(fake, TAUNTS_GREET, false, FakePlayerPvpPersonality.of(fake).getGreetChance());
	}
	
	/**
	 * Called when a player (or a summon) hits a monster: a roaming fake player that is fighting that monster complains in general chat.
	 * @param monster the monster being hit
	 * @param attacker the attacker
	 */
	public void onMonsterAttacked(Attackable monster, Creature attacker)
	{
		if (_fakePlayers.isEmpty() || (FakePlayerPvpConfig.TAUNT_CHANCE <= 0))
		{
			return;
		}
		
		final Player player = attacker.asPlayer();
		if (player == null)
		{
			return;
		}
		
		for (Map.Entry<Creature, AggroInfo> entry : monster.getAggroList().entrySet())
		{
			final Creature creature = entry.getKey();
			if ((creature == null) || !creature.isAttackable() || !creature.asAttackable().isPvpFakePlayer() || creature.isDead() || (entry.getValue().getDamage() <= 0) || (creature.getTarget() != monster) || FakePartyManager.getInstance().isSameGroup(creature, player))
			{
				continue;
			}
			
			// Already fighting that player: no time to talk about the monster.
			final Attackable fake = creature.asAttackable();
			if (!isFighting(fake, player))
			{
				taunt(fake, TAUNTS_MOB_HUNT, false);
			}
		}
	}
	
	/**
	 * Called by the fake player AI when it runs away from a PvP it is losing.
	 * @param fake the fake player
	 */
	public void onFakePlayerFlee(Attackable fake)
	{
		taunt(fake, TAUNTS_FLEE, false);
	}
	
	/**
	 * Called when a roaming fake player finishes reading a Scroll of Escape: it teleports away, which for everyone around is the same as leaving. Its monster respawns as usual. Its AI only reads the scroll out of sight and cancels it when a player shows up, but
	 * should a player see it right when it finishes, it stays (and reads it again later).
	 * @param fake the fake player
	 */
	public void onFakePlayerEscaped(Npc fake)
	{
		// Out of the cast that got it here first.
		ThreadPool.schedule(() ->
		{
			final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
			final boolean inView = (profile != null) && profile.isEscapeInView();
			if (profile != null)
			{
				profile.setEscapeInView(false);
			}
			
			// Leaving a hotzone that rotated out, or a blessed scroll: gone in front of whoever watches, like a player going to town.
			if (!fake.isDead() && fake.isSpawned() && (inView || !isSeenByPlayer(fake)))
			{
				fake.deleteMe();
			}
		}, 300);
	}
	
	/**
	 * A fake player doesn't teleport (Scroll of Escape, logging off, its dead body going to town) nor appear while a player sees it: a player within {@link FakePlayerPvpConfig#UNSEEN_RANGE} that has a line of sight on it, or that is closer than
	 * {@link #CLOSE_SEEN_RANGE} (walls or not).
	 * @param fake a fake player
	 * @return {@code true} if a player sees {@code fake}
	 */
	public static boolean isSeenByPlayer(WorldObject fake)
	{
		return isSeenByPlayer(fake.getX(), fake.getY(), fake.getZ(), fake.getInstanceId());
	}
	
	/**
	 * @param x the x
	 * @param y the y
	 * @param z the z
	 * @param instanceId the instance
	 * @return {@code true} if a player sees that point (see {@link #isSeenByPlayer(WorldObject)})
	 */
	public static boolean isSeenByPlayer(int x, int y, int z, int instanceId)
	{
		final int range = FakePlayerPvpConfig.UNSEEN_RANGE;
		if (range <= 0)
		{
			return false;
		}
		
		// The players that know about that point are the ones in the surrounding regions.
		final WorldRegion region = World.getInstance().getRegion(x, y, z);
		if (region == null)
		{
			return false;
		}
		
		final long rangeSq = (long) range * range;
		final long closeSq = (long) CLOSE_SEEN_RANGE * CLOSE_SEEN_RANGE;
		for (WorldRegion surrounding : region.getSurroundingRegions())
		{
			for (WorldObject object : surrounding.getVisibleObjects())
			{
				if (!object.isPlayer() || (object.getInstanceId() != instanceId))
				{
					continue;
				}
				
				final long dx = object.getX() - x;
				final long dy = object.getY() - y;
				final long dz = object.getZ() - z;
				final long distanceSq = (dx * dx) + (dy * dy) + (dz * dz);
				if ((distanceSq <= closeSq) || ((distanceSq <= rangeSq) && GeoEngine.getInstance().canSeeTarget(object.getX(), object.getY(), object.getZ(), x, y, z, instanceId)))
				{
					return true;
				}
			}
		}
		
		return false;
	}
	
	/**
	 * Whether an area skill of a roaming fake player may hit {@code player}, like the area skill of a player that doesn't hold Ctrl: a player it is fighting, or a flagged or karma one, never a bystander, nor anyone in town.
	 * @param fake the fake player casting
	 * @param player a player (or a summon's owner) in the area
	 * @return {@code true} if {@code player} may be hit
	 */
	public static boolean isFairAreaTarget(Attackable fake, Player player)
	{
		if (player.isInsideZone(ZoneId.PEACE))
		{
			return false;
		}
		
		return isFighting(fake, player) || (player.getPvpFlag() > 0) || (player.getKarma() > 0);
	}
	
	/**
	 * Like {@link #isFairAreaTarget(Attackable, Player)} for another roaming fake player: one it is fighting, or a flagged or karma one.
	 * @param fake the fake player casting
	 * @param other another roaming fake player in the area
	 * @return {@code true} if {@code other} may be hit
	 */
	public static boolean isFairAreaTarget(Attackable fake, Npc other)
	{
		if (other.isInsideZone(ZoneId.PEACE))
		{
			return false;
		}
		
		return isFighting(fake, other) || (other.getScriptValue() > 0) || (other.getKarma() > 0);
	}
	
	/**
	 * @param fake a fake player
	 * @param player a player, or another roaming fake player
	 * @return {@code true} if {@code fake} is already fighting {@code player}
	 */
	private static boolean isFighting(Attackable fake, Creature player)
	{
		// Read from the aggro list itself: getHating() also drops invulnerable players from it.
		final AggroInfo info = fake.getAggroList().get(player);
		return (info != null) && (info.getHate() >= PVP_HATE);
	}
	
	/**
	 * Called by the fake player AI when the player it was fighting dies.
	 * @param fake the fake player
	 */
	public void onPlayerDefeated(Attackable fake)
	{
		taunt(fake, TAUNTS_KILL, false);
	}
	
	/**
	 * Makes a fake player drop what it is doing and fight {@code player}.
	 * @param fake the fake player
	 * @param player the player, or another roaming fake player
	 * @param taunts what it may say about it
	 */
	private void startFight(Attackable fake, Creature player, String[] taunts)
	{
		if (player.isDead() || player.isInvisible() || (player.isPlayer() && player.isGM() && !player.asPlayer().getAccessLevel().canTakeAggro()))
		{
			return;
		}
		
		fake.addDamageHate(player, 0, PVP_HATE);
		fake.getAI().setIntention(Intention.ATTACK, player);
		taunt(fake, taunts, false);
	}
	
	/**
	 * Maybe says something in general chat, a moment later (typing takes a while), and not more than once every {@link #CHAT_INTERVAL} ms (except a last word when it dies).
	 * @param fake the fake player
	 * @param taunts what it may say
	 * @param dead {@code true} if it is said by a dead fake player (players still talk once dead)
	 */
	private void taunt(Npc fake, String[] taunts, boolean dead)
	{
		taunt(fake, taunts, dead, FakePlayerPvpPersonality.of(fake).getTauntChance());
	}
	
	/**
	 * Maybe says something in general chat, like {@link #taunt(Npc, String[], boolean)}, with its own chance.
	 * @param fake the fake player
	 * @param taunts what it may say
	 * @param dead {@code true} if it is said by a dead fake player
	 * @param chance the chance (in %) to say something
	 * @return {@code true} if it says something
	 */
	private boolean taunt(Npc fake, String[] taunts, boolean dead, int chance)
	{
		if ((chance <= 0) || (Rnd.get(100) >= chance))
		{
			return false;
		}
		
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		final long now = System.currentTimeMillis();
		if ((profile == null) || (!dead && (now < profile.getNextChatTime())))
		{
			return false;
		}
		profile.setNextChatTime(now + CHAT_INTERVAL);
		
		final String text = taunts[Rnd.get(taunts.length)];
		ThreadPool.schedule(() ->
		{
			if ((dead || !fake.isDead()) && fake.isSpawned())
			{
				fake.broadcastPacket(new CreatureSay(fake, ChatType.GENERAL, fake.getName(), text));
			}
		}, Rnd.get(800, 2500));
		return true;
	}
	
	private void rewardKill(Attackable fake, Player killer)
	{
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		
		// A few words from the ground.
		taunt(fake, TAUNTS_DEATH, true);
		
		// A class transfer challenge opponent is part of the trial: no return trip, no gear, no loot.
		if (profile.isTrialDuelist())
		{
			return;
		}
		
		// Maybe it walks back from town for round two, unless it already did or its killer is far above it.
		if (!profile.hasReturned() && ((FakePlayerPvpConfig.OUTLEVELED_DIFFERENCE <= 0) || (killer.getLevel() < (fake.getLevel() + FakePlayerPvpConfig.OUTLEVELED_DIFFERENCE))) && (Rnd.get(100) < profile.getPersonality().getReturnChance()))
		{
			profile.setReturn(fake.getX(), fake.getY(), fake.getZ(), fake.getInstanceId(), killer.getObjectId());
		}
		
		// Nothing to loot for a player far above its level.
		final boolean outleveled = (FakePlayerPvpConfig.OUTLEVELED_DIFFERENCE > 0) && (killer.getLevel() >= (fake.getLevel() + FakePlayerPvpConfig.OUTLEVELED_DIFFERENCE));
		
		// Rarely, one piece of its gear, enchant included.
		final List<ItemEnchantHolder> equipment = profile.getEquipment();
		if (!outleveled && !equipment.isEmpty() && ((Rnd.nextDouble() * 100) < FakePlayerPvpConfig.EQUIPMENT_DROP_CHANCE))
		{
			final ItemEnchantHolder piece = equipment.get(Rnd.get(equipment.size()));
			final Item item = fake.dropItem(killer, piece.getId(), 1);
			if (item != null)
			{
				if (item.isEnchantable() && (piece.getEnchantLevel() > 0))
				{
					item.setEnchantLevel(piece.getEnchantLevel());
				}
				
				killer.sendMessage(fake.getName() + " dropped " + (item.getEnchantLevel() > 0 ? "+" + item.getEnchantLevel() + " " : "") + item.getName() + "!");
			}
		}
		
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
		
		if (FakePlayerPvpConfig.REWARD_DROPS && !outleveled)
		{
			fake.doItemDrop(monster.getTemplate(), killer);
		}
	}
	
	/**
	 * A potion: below its {@link FakePlayerPvpConfig#POTION_HP_PERCENT}% HP (see {@link FakePlayerPvpPersonality}) a fake player heals {@link FakePlayerPvpConfig#POTION_HEAL_PERCENT}% of its max HP, once every {@link FakePlayerPvpConfig#POTION_REUSE} ms.
	 * @param fake the fake player
	 * @return {@code true} if it drank one
	 */
	public boolean tryPotion(Npc fake)
	{
		if (FakePlayerPvpConfig.POTION_HEAL_PERCENT <= 0)
		{
			return false;
		}
		
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		final double maxHp = fake.getMaxHp();
		if (fake.isDead() || (fake.getCurrentHp() >= ((maxHp * profile.getPersonality().getPotionHpPercent()) / 100.0)))
		{
			return false;
		}
		
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
	 * Switches the weapon (and shield) a fake player holds, like a player equipping another weapon: its stats, its item skills (a polearm's multi-attack), the skills that need a weapon type and what players see follow the new weapon.
	 * @param fake the fake player
	 * @param weapon its main weapon, its bow or its polearm
	 * @return {@code true} if it switched
	 */
	public boolean equipWeapon(Npc fake, FakePlayerPvpWeapon weapon)
	{
		final NpcTemplate template = fake.getTemplate();
		final FakePlayerPvpProfile profile = template.getFakePlayerPvpProfile();
		// Like a player, no weapon goes in its hands while a Disarm holds.
		if ((profile == null) || (weapon == null) || (profile.getHeldWeapon() == weapon) || fake.isAttackingNow() || fake.isCastingNow() || fake.isDisarmed())
		{
			return false;
		}
		
		switchWeapon(fake, profile, weapon);
		return true;
	}
	
	/**
	 * Puts {@code weapon} in the fake player's hands: its stats, its item skills and what players see.
	 */
	private static void switchWeapon(Npc fake, FakePlayerPvpProfile profile, FakePlayerPvpWeapon weapon)
	{
		for (Skill skill : profile.getHeldWeapon().getSkills())
		{
			fake.removeSkill(skill, true);
		}
		
		setTemplateWeapon(fake.getTemplate(), profile, weapon);
		
		for (Skill skill : weapon.getSkills())
		{
			fake.addSkill(skill);
		}
		
		// Shows the new weapon to the players around.
		fake.setLRHandId(weapon.getShieldId(), weapon.getWeaponId());
	}
	
	/**
	 * Called by the Disarm effect: like a player's, the fake player's weapon leaves its hands (its shield stays) until the effect ends. It fights with its bare hands meanwhile, without the skills that need a weapon.
	 * @param fake the fake player
	 */
	public void disarm(Npc fake)
	{
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		final FakePlayerPvpWeapon unarmed = profile.getUnarmed();
		if ((unarmed == null) || (profile.getHeldWeapon() == unarmed))
		{
			return;
		}
		
		profile.setDisarmedWeapon(profile.getHeldWeapon());
		fake.abortAttack();
		switchWeapon(fake, profile, unarmed);
	}
	
	/**
	 * Called when the Disarm effect ends: the fake player takes back the weapon it had, like a player's weapon going back on.
	 * @param fake the fake player
	 */
	public void rearm(Npc fake)
	{
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		final FakePlayerPvpWeapon weapon = profile.getDisarmedWeapon();
		profile.setDisarmedWeapon(null);
		if ((weapon != null) && (profile.getHeldWeapon() == profile.getUnarmed()))
		{
			switchWeapon(fake, profile, weapon);
		}
	}
	
	/**
	 * Called by the Transformation effect: a Kamael fake player in Final Form looks transformed to the players around and fights with the transformation's skills instead of its class skills, like a player. Its stats come from the effect itself.
	 * @param fake the fake player
	 * @param transformId the transformation
	 */
	public void transform(Npc fake, int transformId)
	{
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		final Transform transform = TransformData.getInstance().getTransform(transformId);
		if ((transform == null) || profile.isTransformed())
		{
			return;
		}
		
		final TransformTemplate template = transform.getTemplate(fake.getTemplate().getSex() == Sex.FEMALE);
		final List<Skill> skills = new ArrayList<>();
		if (template != null)
		{
			for (SkillHolder holder : template.getSkills())
			{
				// Its attacks: not the passives, the self skills (Transform Dispel) nor the channeled ones.
				final Skill skill = holder.getSkill();
				if ((skill != null) && !skill.isPassive() && (skill.getTargetType() != TargetType.SELF) && !skill.isChanneling())
				{
					skills.add(skill);
					fake.addSkill(skill);
				}
			}
		}
		
		profile.setTransform(transformId, skills);
		fake.getTemplate().getFakePlayerInfo().setTransformDisplayId(transform.getDisplayId());
		fake.broadcastInfo();
	}
	
	/**
	 * Called when the transformation ends (its time is up, or the fake player died): back to its own looks and class skills.
	 * @param fake the fake player
	 */
	public void untransform(Npc fake)
	{
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		if (!profile.isTransformed())
		{
			return;
		}
		
		for (Skill skill : profile.getTransformSkills())
		{
			fake.removeSkill(skill, true);
		}
		
		profile.setTransform(0, null);
		fake.getTemplate().getFakePlayerInfo().setTransformDisplayId(0);
		fake.broadcastInfo();
	}
	
	/**
	 * @param template the fake player template
	 * @param profile its profile
	 * @param weapon the weapon (and shield) it now holds
	 */
	private static void setTemplateWeapon(NpcTemplate template, FakePlayerPvpProfile profile, FakePlayerPvpWeapon weapon)
	{
		profile.setHeldWeapon(weapon);
		template.setWeaponStats(weapon.getPAtk(), weapon.getMAtk(), weapon.getPAtkSpd(), weapon.getCritRate(), weapon.getAttackRange(), weapon.getRandomDamage(), weapon.getAttackType(), weapon.getShieldDefence(), weapon.getShieldRate());
		template.setHandIds(weapon.getWeaponId(), weapon.getShieldId());
		template.getFakePlayerInfo().setWeapon(weapon.getWeaponId(), weapon.getShieldId(), weapon.getEnchant());
	}
	
	/**
	 * A Kamael fake player absorbs a soul from a monster it kills, like a player with Soul Mastery gaining exp.
	 * @param fake the fake player
	 */
	public void absorbSoul(Npc fake)
	{
		final int maxSouls = getMaxSouls(fake);
		if (maxSouls > 0)
		{
			fake.getTemplate().getFakePlayerPvpProfile().increaseSouls(1, maxSouls);
		}
	}
	
	/**
	 * @param fake a fake player
	 * @return how many souls it can hold (Soul Mastery), 0 for a class without souls
	 */
	private static int getMaxSouls(Npc fake)
	{
		return (int) fake.getStat().calcStat(Stat.MAX_SOULS, 0, null, null);
	}
	
	/**
	 * Called by the Summon effect when a fake player finishes summoning its servitor (a player's {@link org.l2jmobius.gameserver.model.actor.instance.Servitor} needs a player owner): the servitor, made from the same template, comes out next to it, and its link toggles
	 * (Transfer Pain) go on.
	 * @param fake the fake player
	 * @param npcId the servitor npc id of the summon skill
	 */
	public void summonServitor(Npc fake, int npcId)
	{
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		if ((profile == null) || fake.isDead() || !fake.isSpawned() || !profile.needsServitor())
		{
			return;
		}
		
		final NpcTemplate template = NpcData.getInstance().getTemplate(npcId);
		if (template == null)
		{
			LOGGER.warning(getClass().getSimpleName() + ": " + fake.getName() + " could not summon unknown servitor " + npcId + ".");
			return;
		}
		
		final FakePlayerPvpServitor servitor = new FakePlayerPvpServitor(template, fake);
		servitor.setTitle(fake.getName());
		servitor.setInstanceId(fake.getInstanceId());
		servitor.setHeading(fake.getHeading());
		servitor.setCurrentHpMp(servitor.getMaxHp(), servitor.getMaxMp());
		profile.setServitor(servitor);
		
		final double angle = Rnd.nextDouble() * 2 * Math.PI;
		final int offset = fake.getTemplate().getCollisionRadius() + template.getCollisionRadius() + 30;
		final Location location = GeoEngine.getInstance().getValidLocation(fake.getX(), fake.getY(), fake.getZ(), fake.getX() + (int) (Math.cos(angle) * offset), fake.getY() + (int) (Math.sin(angle) * offset), fake.getZ(), fake.getInstanceId());
		servitor.spawnMe(location.getX(), location.getY(), location.getZ());
		servitor.setRunning();
		if (servitor.getInstanceId() > 0)
		{
			servitor.broadcastInfo();
		}
		
		switchLink(fake, profile, true);
	}
	
	/**
	 * Sends a fake player's servitor away (it died, logged off, or left it behind) and switches its link toggles off.
	 * @param fake the fake player
	 */
	public void unsummonServitor(Npc fake)
	{
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		if (profile == null)
		{
			return;
		}
		
		final Npc servitor = profile.getServitor();
		profile.setServitor(null);
		switchLink(fake, profile, false);
		if ((servitor != null) && servitor.isSpawned())
		{
			servitor.deleteMe();
		}
	}
	
	/**
	 * Called when a fake player's servitor dies: Transfer Pain has nothing left to link to, so it goes off. The fake player summons a new one (see {@link org.l2jmobius.gameserver.ai.FakePlayerPvpAI}).
	 * @param servitor the servitor
	 */
	public void onServitorDeath(FakePlayerPvpServitor servitor)
	{
		final Npc owner = servitor.getOwner();
		final FakePlayerPvpProfile profile = owner.getTemplate().getFakePlayerPvpProfile();
		if ((profile != null) && (profile.getServitor() == servitor))
		{
			switchLink(owner, profile, false);
		}
	}
	
	/**
	 * Switches the link toggles (Transfer Pain) of a fake player on or off.
	 * @param fake the fake player
	 * @param profile its profile
	 * @param on {@code true} to switch them on (when missing), {@code false} to switch them off
	 */
	public void switchLink(Npc fake, FakePlayerPvpProfile profile, boolean on)
	{
		for (Skill skill : profile.getSkills(SkillCategory.LINK))
		{
			if (!on)
			{
				fake.stopSkillEffects(SkillFinishType.REMOVED, skill.getId());
			}
			else if (!fake.isAffectedBySkill(skill.getId()))
			{
				skill.applyEffects(fake, fake);
			}
		}
	}
	
	/**
	 * Transfer Pain: like a player's, part of the damage a fake player takes goes to its servitor when it is close by, never enough to kill the servitor.
	 * @param fake the fake player being hit
	 * @param damage the damage
	 * @param attacker who deals it, can be {@code null}
	 * @return the damage the fake player still takes
	 */
	public double transferDamage(Attackable fake, double damage, Creature attacker)
	{
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		final Npc servitor = profile.getServitor();
		if ((damage <= 0) || (servitor == null) || (servitor == attacker) || servitor.isDead() || !servitor.isSpawned() || (fake.calculateDistance3D(servitor) > SERVITOR_TRANSFER_RANGE))
		{
			return damage;
		}
		
		final double percent = fake.getStat().calcStat(Stat.TRANSFER_DAMAGE_PERCENT, 0, null, null);
		final double transferred = Math.min(servitor.getCurrentHp() - 1, (damage * percent) / 100);
		if (transferred <= 0)
		{
			return damage;
		}
		
		servitor.reduceCurrentHp(transferred, attacker, null);
		return damage - transferred;
	}
	
	/**
	 * A buff counts as active when the skill itself, or any buff of the same abnormal type, is on: Focus Chance, Focus Power and Focus Death (or Might and Attack Aura...) replace each other, and a weaker one can't replace a stronger one, so casting it again would
	 * just loop. The same goes for a slot an improved buff blocks (Improved Combat blocks Might and Shield).
	 * @param creature the one to check
	 * @param skill a buff
	 * @return {@code true} if {@code skill}, or another buff taking its place, is already on {@code creature}
	 */
	public static boolean isBuffActive(Creature creature, Skill skill)
	{
		if (creature.isAffectedBySkill(skill.getId()))
		{
			return true;
		}
		
		final AbnormalType type = skill.getAbnormalType();
		if ((type == null) || (type == AbnormalType.NONE))
		{
			return false;
		}
		
		if (creature.isAffectedByAbnormalType(type))
		{
			return true;
		}
		
		final Set<AbnormalType> blocked = creature.getEffectList().getBlockedAbnormalTypes();
		return (blocked != null) && blocked.contains(type);
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
			if ((skill != null) && !isBuffActive(fake, skill))
			{
				skill.applyEffects(fake, fake);
			}
		}
	}
	
	/**
	 * Puts back the toggles that went off (canceled, or switched off for lack of HP).
	 * @param fake the fake player
	 * @param profile its profile
	 */
	private void refreshToggles(Npc fake, FakePlayerPvpProfile profile)
	{
		for (Skill toggle : profile.getSkills(SkillCategory.TOGGLE))
		{
			if (!fake.isAffectedBySkill(toggle.getId()))
			{
				toggle.applyEffects(fake, fake);
			}
		}
	}
	
	/**
	 * @param fake a roaming fake player
	 * @return {@code true} if it is fighting a player: flagged, or a player (or summon) is the one it hates most
	 */
	public static boolean isInPvp(Npc fake)
	{
		if (fake.getScriptValue() > 0)
		{
			return true;
		}
		
		final Creature hated = fake.isAttackable() ? fake.asAttackable().getMostHated() : null;
		return isPvpEnemy(hated);
	}
	
	/**
	 * Every 30 seconds: rebuff fake players and log off the ones that lived long enough. A fake player hunts non stop (it picks its next monster right after a kill), so only a fight with a player keeps it from rebuffing or logging off, like a player that
	 * leaves a hunt at any time but not in the middle of a PvP. It doesn't log off in front of a player either (see {@link #isSeenByPlayer}), it waits until nobody is watching.
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
				
				if (fake.isCastingNow() || isInPvp(fake))
				{
					continue;
				}
				
				final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
				
				// A class transfer challenge opponent stays until its challenge removes it, and so does a fake player in a party with players, or on its way to one.
				// In a party of fake players, the leader logs off for all of them (the others then go their own way).
				final FakePlayerParty party = profile.getParty();
				final boolean staysWithParty = (party != null) && (!party.isFakeOnly() || (party.getLeader() != fake));
				if (!profile.isTrialDuelist() && !staysWithParty && (profile.getLfTarget(now) == 0))
				{
					checkHotzoneLeave(fake, profile, now);
					
					// Logs off once nobody is watching: it doesn't vanish in front of a player.
					if ((FakePlayerPvpConfig.LIFETIME > 0) && ((now - profile.getSpawnTime()) > (FakePlayerPvpConfig.LIFETIME * 1000L * profile.getLifetimeScale())) && !isSeenByPlayer(fake))
					{
						fake.deleteMe();
						continue;
					}
				}
				
				refreshToggles(fake, profile);
				refreshBuffs(fake, profile);
				if (profile.getServitor() != null)
				{
					switchLink(fake, profile, !profile.needsServitor());
				}
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Problem while maintaining " + fake.getName() + ".", e);
			}
		}
		
		if (FakePlayerPvpConfig.KEEP_POPULATION && (FakePlayerPvpConfig.LIFETIME > 0) && isEnabled())
		{
			try
			{
				keepPopulation();
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Problem while keeping the population.", e);
			}
		}
	}
	
	/**
	 * A fake player that came to an active hotzone leaves some time after the rotation moves on (see {@link FakePlayerPvpConfig#HOTZONE_LEAVE}): its AI then reads a Scroll of Escape and it logs off, which leaves room for the new hotzones. It stays if the zone
	 * becomes hot again before that. One that was already there when the zone became hot (at server start, before the first rotation...) counts as having come for it.
	 * @param fake the fake player
	 * @param profile its profile
	 * @param now the current time
	 */
	private static void checkHotzoneLeave(Npc fake, FakePlayerPvpProfile profile, long now)
	{
		final Spawn spawn = fake.getSpawn();
		if ((profile.getHotzoneId() == 0) && (spawn != null))
		{
			profile.setHotzoneId(getActiveHotzoneId(spawn.getX(), spawn.getY(), spawn.getZ()));
		}
		
		final int hotzoneId = profile.getHotzoneId();
		if (!FakePlayerPvpConfig.HOTZONE_LEAVE || (hotzoneId == 0) || HotzoneModifierManager.getInstance().isActive(hotzoneId))
		{
			profile.setLeaveTime(0);
		}
		else if (profile.getLeaveTime() == 0)
		{
			profile.setLeaveTime(now + (Rnd.get(FakePlayerPvpConfig.HOTZONE_LEAVE_DELAY_MIN, FakePlayerPvpConfig.HOTZONE_LEAVE_DELAY_MAX) * 1000L));
		}
	}
	
	/**
	 * Server start rolls every monster of the world at once, but afterwards only respawning monsters roll: once that first generation logs off, fake players would only come back where players kill monsters and vanish from everywhere else. So every
	 * {@link #maintain}, each idle monster no player is near rolls {@link FakePlayerPvpConfig#SPAWN_CHANCE} spread over {@link FakePlayerPvpConfig#LIFETIME}, which brings in about as many fake players as log off - like players arriving at a hunting ground.
	 */
	private void keepPopulation()
	{
		final double chance = (FakePlayerPvpConfig.SPAWN_CHANCE * MAINTAIN_INTERVAL) / (FakePlayerPvpConfig.LIFETIME * 1000.0);
		final double hotzoneChance = chance * FakePlayerPvpConfig.HOTZONE_SPAWN_MULTIPLIER;
		final double maxChance = Math.max(chance, hotzoneChance);
		
		// Picked first and replaced after, the fake players' own spawns join the spawn table.
		final List<Npc> picked = new ArrayList<>();
		for (Set<Spawn> spawns : SpawnTable.getInstance().getSpawnTable().values())
		{
			for (Spawn spawn : spawns)
			{
				for (Npc npc : spawn.getSpawnedNpcs())
				{
					// The zone lookup only for the few that pass the higher of both chances.
					final double roll = Rnd.nextDouble() * 100;
					if ((roll < maxChance) && npc.isSpawned() && !npc.isDead() && (roll < (isInHotzone(npc.getX(), npc.getY(), npc.getZ()) ? hotzoneChance : chance)))
					{
						picked.add(npc);
					}
				}
			}
		}
		
		for (Npc npc : picked)
		{
			if ((FakePlayerPvpConfig.MAX_ALIVE > 0) && (_fakePlayers.size() >= FakePlayerPvpConfig.MAX_ALIVE))
			{
				return;
			}
			
			final Spawn spawn = npc.getSpawn();
			if (!isReplaceable(npc, spawn) || !isIdle(npc.asMonster()) || !isAllowedLocation(npc.getX(), npc.getY(), npc.getZ(), npc.getInstanceId()))
			{
				continue;
			}
			
			final Monster monster = npc.asMonster();
			final Npc fake = spawnFakePlayer(FakePlayerPvpData.getInstance().getRandomBuild(), getFakeLevel(monster), monster.getX(), monster.getY(), monster.getZ(), monster.getInstanceId(), monster, spawn);
			if (fake == null)
			{
				continue;
			}
			
			spawn.setFakePlayerCooldown(FakePlayerPvpConfig.RESPAWN_COOLDOWN);
			
			// Out of the world like a monster that died and decayed, but still counted by its spawn until the fake player is gone, see onFakePlayerDecay().
			monster.decayMe();
			final ZoneRegion region = ZoneManager.getInstance().getRegion(monster);
			if (region != null)
			{
				region.removeFromZones(monster);
			}
			
			monster.setDead(true);
			monster.setDecayed(true);
			
			// Sometimes it comes with friends.
			FakePartyManager.getInstance().onRoamingSpawn(fake);
		}
	}
	
	/**
	 * @param monster the monster
	 * @return {@code true} if {@code monster} is a plain monster (no champion, thief, mage or other special one) that isn't fighting and that no player sees, so nobody sees it turn into a fake player
	 */
	private static boolean isIdle(Monster monster)
	{
		if (!monster.isSpawned() || monster.isDead() || monster.isInCombat() || !monster.getAggroList().isEmpty() || (monster.getTarget() != null))
		{
			return false;
		}
		
		if ((monster.getChampionTier() > 0) || monster.isMageMonster() || !monster.canHotzoneRise())
		{
			return false;
		}
		
		return !isSeenByPlayer(monster);
	}
	
	/**
	 * Frees a name taken with {@link #generateName()}.
	 * @param name the name
	 */
	void releaseName(String name)
	{
		_names.remove(name.toLowerCase());
	}
	
	/**
	 * @return a name no character or fake player uses (also used by {@link FakePlayerTownManager})
	 */
	String generateName()
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
	 * Called when a fake player joins a party with players (see {@link FakePartyManager}): it goes away with its party, so the monster it replaced spawns again, and it forgets its hotzone.
	 * @param fake the fake player
	 */
	public void onJoinParty(Npc fake)
	{
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		final Npc monster = profile.takeReplacedMonster();
		final Spawn replacedSpawn = profile.getReplacedSpawn();
		if ((monster != null) && (replacedSpawn != null))
		{
			replacedSpawn.decreaseCount(monster);
		}
		profile.setReplacedMonster(null, null);
		profile.setHotzoneId(0);
		profile.setLeaveTime(0);
	}
	
	/**
	 * Called when a fake player leaves its party (or gives up waiting for an invite): it hunts where it is for a while, then logs off like the others.
	 * @param fake the fake player
	 */
	public void onLeaveParty(Npc fake)
	{
		final Spawn spawn = fake.getSpawn();
		if (spawn != null)
		{
			spawn.setXYZ(fake.getX(), fake.getY(), fake.getZ());
		}
		
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		if (FakePlayerPvpConfig.LIFETIME > 0)
		{
			// A quarter of its time left.
			final long lifetime = (long) (FakePlayerPvpConfig.LIFETIME * 1000L * profile.getLifetimeScale());
			profile.setSpawnTime(Math.min(profile.getSpawnTime(), System.currentTimeMillis() - ((lifetime * 3) / 4)));
		}
	}
	
	/**
	 * Brings back a party fake player that died, from town (see {@link FakePartyManager}): same template, so same name, looks and gear.
	 * @param template its template
	 * @param x the x
	 * @param y the y
	 * @param z the z
	 * @param instanceId the instance
	 * @return the fake player, {@code null} if it could not be spawned (its name is then free)
	 */
	public Npc respawnFromTemplate(NpcTemplate template, int x, int y, int z, int instanceId)
	{
		final String name = template.getName();
		final FakePlayerPvpProfile profile = template.getFakePlayerPvpProfile();
		try
		{
			profile.setReplacedMonster(null, null);
			profile.setSpawnTime(System.currentTimeMillis());
			profile.setHotzoneId(0);
			profile.setLeaveTime(0);
			profile.onReturn(0); // Back for its party, not for revenge.
			if (profile.getHeldWeapon() != profile.getMainWeapon())
			{
				setTemplateWeapon(template, profile, profile.getMainWeapon());
			}
			
			final String lowercaseName = name.toLowerCase();
			FakePlayerData.getInstance().addFakePlayerId(name, template.getId());
			FakePlayerData.getInstance().addFakePlayerName(lowercaseName, name);
			FakePlayerData.getInstance().addTalkableFakePlayerName(lowercaseName);
			
			final Npc fake = spawnFromTemplate(template, x, y, z, instanceId);
			if (fake == null)
			{
				FakePlayerData.getInstance().removeFakePlayer(name);
				_names.remove(lowercaseName);
			}
			return fake;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not bring back party fake player " + name + ".", e);
			FakePlayerData.getInstance().removeFakePlayer(name);
			_names.remove(name.toLowerCase());
			return null;
		}
	}
	
	/**
	 * Called when a dead fake player is resurrected (a party fake player, see {@link FakePartyManager}): death took its buffs and toggles, it puts them back like the ones it comes back to life with (see {@link #maintain}).
	 * @param fake the fake player
	 */
	public void onRevived(Npc fake)
	{
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		if (profile == null)
		{
			return;
		}
		
		refreshToggles(fake, profile);
		refreshBuffs(fake, profile);
	}
	
	/**
	 * A fake player joins the fight of a party member against {@code enemy} (see {@link FakePartyManager}).
	 * @param fake the fake player
	 * @param enemy the player (or fake player) its party fights
	 */
	public void assistFight(Attackable fake, Creature enemy)
	{
		if (!fake.isDead() && !isFighting(fake, enemy))
		{
			startFight(fake, enemy, TAUNTS_ATTACKED);
		}
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
