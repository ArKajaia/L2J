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
import java.util.List;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.model.item.ItemTemplate;

/**
 * What the town fake players say (see {@link FakePlayerTownManager}): short conversations between a few of them, asking a buffer for buffs, and now and then a line in general chat.<br>
 * A conversation is put together from topics. Each topic line is {@code S:alternative|alternative|...}, where {@code S} is who says it ({@code A}, {@code B} or {@code C}, lowercase when the line may be left out) and the alternatives can hold placeholders: {@code {spotA}}
 * a hunting ground that fits the speaker's level, {@code {lvlA}}, {@code {clsA}}, {@code {weaponA}}, {@code {nameB}}, {@code {mat}}, {@code {price}}, {@code {town}}, {@code {bigTown}}, {@code {castle}}, {@code {ss}}, {@code {next}}. Each speaker types in a style of its own (case, typos,
 * smileys), so the same line never looks quite the same twice.
 */
final class FakePlayerTownChat
{
	/** A hunting ground and the levels it fits (taken from the monster levels of data/spawns). */
	private record Spot(int min, int max, String... names)
	{
	}
	
	/** One line of a conversation: the speaker (0 = A, 1 = B, 2 = C) and the text. */
	record Line(int speaker, String text)
	{
	}
	
	/** How a speaker types. */
	static final class Style
	{
		final boolean lowercase = Rnd.get(100) < 82;
		final boolean dropsPunctuation = Rnd.get(100) < 45;
		final int smileyChance = Rnd.get(0, 12);
		final int typoChance = Rnd.get(0, 6);
		final String smiley = SMILEYS[Rnd.get(SMILEYS.length)];
	}
	
	/** Who takes part in a conversation, for the placeholders. */
	static final class Speaker
	{
		final String name;
		final int level;
		final String className;
		final int weaponId;
		final int weaponEnchant;
		final Style style;
		
		Speaker(String name, int level, String className, int weaponId, int weaponEnchant, Style style)
		{
			this.name = name;
			this.level = level;
			this.className = className;
			this.weaponId = weaponId;
			this.weaponEnchant = weaponEnchant;
			this.style = style;
		}
	}
	
	/** The kinds of conversation, picked by the manager. */
	enum Topic
	{
		/** Two or three that just met. */
		CHAT,
		/** A party that just came back to town together. */
		PARTY_END,
		/** One asks the other to hunt together: both leave for the gatekeeper right after. */
		TEAM_UP
	}
	
	private static final String[] SMILEYS =
	{
		":)",
		":D",
		"xD",
		"^^",
		":P",
		";)",
		":>",
		"=)"
	};
	
	private static final Spot[] SPOTS =
	{
		new Spot(1, 17, "elven ruins", "the ruins", "isle of souls", "school of dark arts", "coal mines", "elven forest"),
		new Spot(12, 24, "ruins of agony", "ruins of despair", "orc barracks", "abandoned camp", "windawood manor", "neutral zone"),
		new Spot(21, 30, "plains of dion", "turek orcs", "necro of sacrifice", "the dion plains"),
		new Spot(25, 36, "execution grounds", "cruma marsh", "wasteland", "breka", "gorgon garden", "bee hive"),
		new Spot(30, 40, "catacomb of the heretic", "necro of pilgrims", "death pass", "alligator beach", "hardin's academy"),
		new Spot(38, 51, "cruma tower", "cruma", "alligator island", "timak outpost", "sea of spores", "sos", "garden of eva", "ivory tower crater", "catacomb of the branded"),
		new Spot(44, 55, "tanor canyon", "devil's isle", "war-torn plains", "plains of glory", "enchanted valley", "ev", "outlaw forest", "cemetery"),
		new Spot(51, 62, "catacomb of the apostate", "necro of patriots", "cemetery", "outlaw forest"),
		new Spot(56, 68, "fields of massacre", "fom", "forbidden gateway", "valley of saints", "vos", "necro of devotion", "toi", "tower of insolence"),
		new Spot(60, 74, "toi", "tower of insolence", "catacomb of the witch", "swamp of screams", "garden of beasts", "blazing swamp"),
		new Spot(66, 77, "wall of argos", "hot springs", "blazing swamp", "swamp of screams", "necro of martyrdom", "toi 11", "silent valley"),
		new Spot(72, 80, "silent valley", "necro of saints", "necro of disciples", "dark omens", "forbidden path", "ketra", "varka", "imperial tomb", "it", "forge of the gods", "fog"),
		new Spot(76, 82, "imperial tomb", "it", "primeval isle", "pi", "ketra", "varka", "chapel", "forge of the gods", "fog", "giant's cave"),
		new Spot(80, 85, "giant's cave", "hellbound", "hb", "fields of silence", "fields of whispers", "sel mahum", "seed of destruction", "sod", "seed of infinity", "soi", "kamaloka")
	};
	
	private static final String[] BIG_TOWNS =
	{
		"gludio",
		"dion",
		"giran",
		"oren",
		"aden",
		"heine",
		"goddard",
		"rune",
		"schuttgart"
	};
	
	private static final String[] CASTLES =
	{
		"gludio",
		"dion",
		"giran",
		"oren",
		"aden",
		"innadril",
		"goddard",
		"rune",
		"schuttgart"
	};
	
	private static final String[] LOW_MATERIALS =
	{
		"coarse bone powder",
		"stem",
		"animal skin",
		"iron ore",
		"varnish",
		"suede",
		"thread",
		"charcoal",
		"cord",
		"steel"
	};
	
	private static final String[] MID_MATERIALS =
	{
		"mithril ore",
		"oriharukon ore",
		"varnish of purity",
		"synthetic cokes",
		"compound braid",
		"crafted leather",
		"silver mold",
		"metallic fiber",
		"mold glue",
		"high grade suede",
		"enria",
		"asofe",
		"thons",
		"stone of purity",
		"life stones"
	};
	
	private static final String[] HIGH_MATERIALS =
	{
		"enria",
		"asofe",
		"thons",
		"mold hardener",
		"mold lubricant",
		"oriharukon",
		"maestro holder",
		"craftsman mold",
		"durable metal plate",
		"leolin's mold",
		"top life stones",
		"giant's codex",
		"attribute stones",
		"fire stones",
		"enchant scrolls",
		"blessed scrolls",
		"ancient adena",
		"seal stones"
	};
	
	// Conversations. "S:alternative|alternative": S says one of them (uppercase: always, lowercase: maybe). "QA:question=>answer|...": Q asks and A answers, "QAQ:question=>answer=>reply|..." Q replies too (all left out together when the first letter is lowercase).
	private static final String[] GREET =
	{
		"A:hi|hey|yo|sup|hello|o/|hey {nameB}|heya|hi :)|yo {nameB}|hey there|hiya",
		"B:hi|hey|yo|hello|sup|o/|hey hey|hi {nameA}|heya|yo yo",
		"aba:how r u?=>fine u?=>same|how r u?=>fine u?=>cant complain|how r u?=>good=>nice|hows it going?=>ok=>cool|hows it going?=>tired lol=>same lol|whats up?=>farming as always=>same old|whats up?=>nm u?=>nm|u ok?=>alive=>lol|long time=>ya been busy=>same"
	};
	private static final String[] GREET_JOIN =
	{
		"C:hi guys|hey all|yo|sup guys|hi|o/|hey hey",
		"a:hey|yo|hi|sup",
		"b:o/|hi|hey"
	};
	private static final String[] HUNT =
	{
		"AB:where u farming?=>{spotB}|where u hunting?=>{spotB} atm|where do u farm now?=>was at {spotB}|wru farming=>{spotB}, u?|what spot u doing?=>{spotB} mostly|where u lvling?=>solo {spotB}",
		"ab:exp good there?=>decent|exp good there?=>meh|how is it?=>its ok|how is it?=>kinda slow|crowded?=>full of people lol|crowded?=>empty at night|good drops?=>drops suck|good drops?=>not bad",
		"A:{spotA} for me|im going {spotA}|i do {spotA}|{spotA} is better for my lvl|gonna try {spotA}|{spotA} is dead lately",
		"b:nice|gl|ok|have fun|cool|careful there|gl with drops"
	};
	private static final String[] TEAM_UP =
	{
		"A:wanna party {spotA}?|lf pt for {spotA}, u in?|need a partner for {spotA}|duo {spotA}?|come {spotA} with me?",
		"B:sure|ok|why not|yea|k|ok lets go|sure",
		"ba:what class r u?=>{clsA}|lvl?=>{lvlA}|u {clsA}?=>yea|buffed?=>yea|got pots?=>ya",
		"A:k port to {spotA}|ok lets go|meet there|k im porting|lets go|go go",
		"B:k|omw|ok|right behind u|coming|go"
	};
	private static final String[] PARTY_NO =
	{
		"A:lf pt?|anyone for party?|need pt {spotA}?|wanna duo?|party?",
		"B:sry|nah solo|no sry|maybe later|im just afk|not now|already have pt|sry going offline soon",
		"a:np|ok|k|ok np|np gl"
	};
	private static final String[] GEAR =
	{
		"B:nice {weaponA}|is that {weaponA}?|{weaponA}? nice|wow nice weapon|where did u get that {weaponA}?",
		"A:ty|thx|ty finally got it|yea took forever|thx :)|lol ty|crafted it|drop lol",
		"ba:+?=>+{encA}|+?=>only +{encA}|enchanted?=>+{encA}|enchanted?=>safe lol|how much was it?=>{price}kk|how much was it?=>dont ask lol|from craft?=>crafted|from craft?=>drop lol|whats the enchant?=>+{encA}, scared to go more",
		"b:not bad|nice|gz|gratz|cool|go +{encNext}|dont enchant more lol"
	};
	private static final String[] ENCHANT =
	{
		"A:broke my weapon|omg broke my +{encNext}|fml broke it|lost my +{encNext} weapon|enchant failed again|scroll ate my weapon",
		"B:rip|f|lol|ouch|thats l2|unlucky|gratz.. not|rofl|oh no",
		"a:gonna farm a new one|whatever|ugh|never enchanting again lol|3rd one this week|my wh is empty now",
		"b:lol|we all say that|gl|rofl|thats life|next one will be +10"
	};
	private static final String[] LEVEL =
	{
		"A:finally {lvlA}|{lvlA} :)|dinged {lvlA}|got {lvlA}!|{lvlA} finally|lvl up :)",
		"B:gz|grats|gratz|gj|nice|gz!|grats man|gz gz",
		"a:ty|thx|ty :)|thx man",
		"ba:how long to {next}?=>like a week lol|how long to {next}?=>forever|how long to {next}?=>dont ask|whats next?=>{spotA} till {next}|whats next?=>farming gear|going for {next}?=>slowly lol|going for {next}?=>trying"
	};
	private static final String[] MARKET =
	{
		"A:how much is {mat}?|price of {mat} now?|anyone knows {mat} price?|{mat} price?",
		"BA:{price}k=>expensive|{price}k=>ok ty|{price}k each=>damn|{price}k each=>cheap lol|no idea=>ok|dunno=>k|check stores=>k ty|cheaper in giran=>ty|went up lol=>ugh|{price}k i think=>hmm"
	};
	private static final String[] TRADE =
	{
		"A:anyone selling {mat}?|need {mat}|u have {mat}?|wtb {mat}",
		"BA:how many?=>20|how many?=>like 10|how many?=>all u have lol|i have some=>how much?|i have some=>nice|check stores=>ok|no sry=>np|maybe in wh=>k lmk|no idea=>ok"
	};
	private static final String[] BUFFS =
	{
		"A:any buffer around?|who buffs here?|pp online?|anyone buffing?",
		"B:no idea|guide buffs till 75|i buff myself lol|dunno|nope|there was one near gk|ask in shout",
		"a:k|ty|ugh|ok"
	};
	private static final String[] SERVER =
	{
		"AB:which hotzone today?=>no idea|which hotzone today?=>{spotA} i think|where is the hotzone now?=>check the hotzone teleporter|where is the hotzone now?=>dunno|hotzone changed?=>changed again lol|hotzone changed?=>was {spotB} earlier",
		"a:k|ty|ok|lol"
	};
	private static final String[] SIEGE =
	{
		"AB:siege this weekend?=>yea|siege this weekend?=>no idea|who has {castle} now?=>some clan lol|who has {castle} now?=>probably same clan as always|going siege?=>nah|going siege?=>if my clan goes|any siege today?=>dunno|any siege today?=>dont think so",
		"a:k|lol|ok|figures"
	};
	private static final String[] OLYMPIAD =
	{
		"AB:doing oly?=>nah|doing oly?=>later|oly today?=>maybe later|going oly?=>yea|going oly?=>lost 2 already lol|how many oly matches u have?=>need 1 more|how many oly matches u have?=>like 10|doing oly?=>i suck at oly",
		"a:lol|same|gl|ok"
	};
	private static final String[] CLAN =
	{
		"A:ur clan recruiting?|need clan|any clan recruiting?|looking for clan",
		"BA:dunno ask leader=>ok ty|we're full sry=>np|maybe, what lvl?=>{lvlA} {clsA}|no clan atm=>same lol|join mine lol=>which one?|ask in shout=>k"
	};
	private static final String[] AFK =
	{
		"A:brb|brb food|brb 5 min|afk|brb door",
		"B:k|ok|kk|np|ok"
	};
	private static final String[] NEWBIE =
	{
		"AB:how do i change class?=>talk to the master at 20|how do i change class?=>at lvl 20, the guild master|where do i get shots?=>grocer|where do i get shots?=>the grocer sells them|how to get to {bigTown}?=>gatekeeper|how to get to {bigTown}?=>talk to the gatekeeper|where is the grocer?=>over there|where is the grocer?=>near the warehouse i think|where to lvl now?=>{spotA}|where to lvl now?=>go to {spotA}|where do i learn skills?=>the masters in the guild|what do i do now?=>quests lol|what do i do now?=>ask the newbie guide|what do i do now?=>idk im new too lol",
		"a:ty|thx|ok thanks|oh ok|ty!",
		"b:np|gl|yw|np gl"
	};
	private static final String[] TIRED =
	{
		"AB:so tired=>same|so tired=>go sleep|work tomorrow ugh=>ugh same|gonna sleep soon=>gn|gonna sleep soon=>same|so laggy today=>lag here too|so laggy today=>not for me|my pc is dying lol=>lol",
		"a:lol|ya|maybe"
	};
	private static final String[] PARTY_END =
	{
		"A:gg|gg all|ty for pt|thx for party|gg wp|ty all",
		"B:gg|ty|gg wp|thx|ty u too",
		"c:gg|ty all|gg|thx guys",
		"ab:same time tomorrow?=>sure|same time tomorrow?=>if im on|again later?=>maybe|was fun=>yea|good exp=>yea not bad|nice drops today=>finally lol",
		"A:cya|bb|later|cya guys|gn",
		"B:cya|bb|o/|later",
		"c:bb|cya|o/"
	};
	private static final String[] FAREWELL =
	{
		"A:ok gtg|cya|bb|gl|later|ok cya|gl hf|gonna go|k im off|cya around",
		"B:cya|gl|bb|later|o/|gl hf|cya|take care"
	};
	
	private static final String[] ASK_BUFF =
	{
		"buff pls",
		"can i get buffs?",
		"buffs pls",
		"bufff",
		"buff?",
		"could u buff me?",
		"buffs? :)",
		"pls buff",
		"can u buff?",
		"buff me pls",
		"hey can i get buffs",
		"buffs?"
	};
	private static final String[] ASK_SONG_DANCE =
	{
		"song pls",
		"dances pls",
		"dance?",
		"songs?",
		"can i get dances?",
		"pt for songs?",
		"dance pls",
		"songs pls"
	};
	private static final String[] BUFFER_ACK =
	{
		"sec",
		"k",
		"1 sec",
		"ok",
		"sure",
		"np",
		"wait",
		"yep",
		"kk",
		"mom"
	};
	private static final String[] BUFFER_DECLINE =
	{
		"no mp sry",
		"sry afk",
		"later",
		"busy sry",
		"no mp"
	};
	private static final String[] THANKS =
	{
		"ty",
		"thx",
		"ty!",
		"thanks",
		"tyvm",
		"ty :)",
		"thx a lot",
		"ty <3",
		"thank u",
		"tyty"
	};
	private static final String[] BUFFER_DONE =
	{
		"np",
		"gl",
		"yw",
		":)",
		"np gl",
		"gl hf"
	};
	private static final String[] OK =
	{
		"k",
		"ok",
		"np",
		"ok np"
	};
	
	// Said alone in general chat.
	private static final String[] LONE_ANY =
	{
		"hi all",
		"anyone?",
		"lol",
		"so laggy today",
		"back",
		"brb",
		"anyone here?",
		"hello",
		"gm?",
		"any buffer online?",
		"which hotzone today?",
		"zzz"
	};
	private static final String[] LONE_HUNT =
	{
		"lf party {spotA}",
		"lfp {clsA} {lvlA}",
		"{clsA} lf pt",
		"lfm {spotA}",
		"anyone going {spotA}?",
		"lf pt {spotA} {clsA}",
		"anyone for {spotA}?",
		"lf clan, {lvlA} {clsA}",
		"where to farm at {lvlA}?",
		"wtb {ss}",
		"wtb {mat}",
		"selling {mat}",
		"wts {mat} cheap",
		"anyone selling {mat}?"
	};
	private static final String[] LONE_NEWBIE =
	{
		"how do i get soulshots?",
		"where do i learn skills?",
		"lol im lost",
		"how to get to {bigTown}?",
		"where can i lvl at {lvlA}?",
		"what do i do at lvl 20?",
		"anyone lf party?",
		"how do i use the gatekeeper?"
	};
	
	private FakePlayerTownChat()
	{
	}
	
	/**
	 * @param topic the kind of conversation
	 * @param town the town short name (for the placeholders)
	 * @param speakers A, B and maybe C
	 * @return the lines of the conversation, in order
	 */
	static List<Line> conversation(Topic topic, String town, List<Speaker> speakers)
	{
		final List<Line> lines = new ArrayList<>();
		final Context context = new Context(town, speakers);
		final Speaker a = speakers.get(0);
		final Speaker b = speakers.get(1);
		switch (topic)
		{
			case PARTY_END:
			{
				add(lines, PARTY_END, context);
				return lines;
			}
			case TEAM_UP:
			{
				if (Rnd.get(100) < 40)
				{
					add(lines, GREET, context);
				}
				add(lines, TEAM_UP, context);
				return lines;
			}
			default:
			{
				break;
			}
		}
		
		if (Rnd.get(100) < 70)
		{
			add(lines, GREET, context);
		}
		
		// One or two topics, then maybe goodbye (someone going afk ends it).
		final int topics = Rnd.get(100) < 35 ? 2 : 1;
		String[] last = null;
		for (int i = 0; i < topics; i++)
		{
			String[] picked = pickTopic(a, b);
			for (int attempt = 0; (picked == last) && (attempt < 4); attempt++)
			{
				picked = pickTopic(a, b);
			}
			add(lines, picked, context);
			if (picked == AFK)
			{
				return lines;
			}
			last = picked;
		}
		
		if (Rnd.get(100) < 60)
		{
			add(lines, FAREWELL, context);
		}
		return lines;
	}
	
	/**
	 * @param town the town short name
	 * @param speakers the first two members and the one joining, third (as C)
	 * @return what is said when someone joins a conversation
	 */
	static List<Line> join(String town, List<Speaker> speakers)
	{
		final List<Line> lines = new ArrayList<>();
		add(lines, GREET_JOIN, new Context(town, speakers));
		return lines;
	}
	
	private static String[] pickTopic(Speaker a, Speaker b)
	{
		final int level = Math.min(a.level, b.level);
		if (level < 20)
		{
			switch (Rnd.get(10))
			{
				case 0:
				case 1:
				case 2:
				case 3:
				{
					return NEWBIE;
				}
				case 4:
				case 5:
				{
					return HUNT;
				}
				case 6:
				{
					return LEVEL;
				}
				case 7:
				{
					return PARTY_NO;
				}
				case 8:
				{
					return TIRED;
				}
				default:
				{
					return AFK;
				}
			}
		}
		
		final int roll = Rnd.get(100);
		if (roll < 20)
		{
			return HUNT;
		}
		if (roll < 30)
		{
			return a.weaponId > 0 ? GEAR : MARKET;
		}
		if (roll < 38)
		{
			return MARKET;
		}
		if (roll < 44)
		{
			return TRADE;
		}
		if (roll < 51)
		{
			return LEVEL;
		}
		if (roll < 57)
		{
			return PARTY_NO;
		}
		if (roll < 62)
		{
			return ENCHANT;
		}
		if (roll < 67)
		{
			return BUFFS;
		}
		if (roll < 72)
		{
			return SERVER;
		}
		if (roll < 77)
		{
			return CLAN;
		}
		if (roll < 82)
		{
			return level >= 76 ? OLYMPIAD : SIEGE;
		}
		if (roll < 86)
		{
			return SIEGE;
		}
		if (roll < 92)
		{
			return TIRED;
		}
		return AFK;
	}
	
	private static void add(List<Line> lines, String[] topic, Context context)
	{
		for (String entry : topic)
		{
			final int colon = entry.indexOf(':');
			final String who = entry.substring(0, colon);
			if (((Character.toUpperCase(who.charAt(0)) - 'A') >= context.speakers.size()) || (Character.isLowerCase(who.charAt(0)) && (Rnd.get(100) < 45)))
			{
				continue;
			}
			
			// One line, or a question, its answer and maybe a reply, each by its own speaker.
			final String[] alternatives = entry.substring(colon + 1).split("\\|");
			final String[] parts = alternatives[Rnd.get(alternatives.length)].split("=>");
			for (int i = 0; (i < parts.length) && (i < who.length()); i++)
			{
				final int speaker = Character.toUpperCase(who.charAt(i)) - 'A';
				if (speaker >= context.speakers.size())
				{
					break;
				}
				lines.add(new Line(speaker, style(context.fill(parts[i]), context.speakers.get(speaker).style)));
			}
		}
	}
	
	/**
	 * @param style how the requester types
	 * @param songsOrDances {@code true} if the buffer is a swordsinger or a bladedancer
	 * @return a buff request
	 */
	static String askBuff(Style style, boolean songsOrDances)
	{
		return style(pick(songsOrDances && (Rnd.get(100) < 70) ? ASK_SONG_DANCE : ASK_BUFF), style);
	}
	
	static String bufferAck(Style style)
	{
		return style(pick(BUFFER_ACK), style);
	}
	
	static String bufferDecline(Style style)
	{
		return style(pick(BUFFER_DECLINE), style);
	}
	
	static String thanks(Style style)
	{
		return style(pick(THANKS), style);
	}
	
	static String bufferDone(Style style)
	{
		return style(pick(BUFFER_DONE), style);
	}
	
	static String ok(Style style)
	{
		return style(pick(OK), style);
	}
	
	/**
	 * @param speaker the one talking
	 * @param town the town short name
	 * @return something said alone in general chat
	 */
	static String lone(Speaker speaker, String town)
	{
		final String[] lines = speaker.level < 20 ? (Rnd.get(100) < 60 ? LONE_NEWBIE : LONE_ANY) : (Rnd.get(100) < 65 ? LONE_HUNT : LONE_ANY);
		return style(new Context(town, List.of(speaker, speaker)).fill(pick(lines)), speaker.style);
	}
	
	private static String pick(String[] lines)
	{
		return lines[Rnd.get(lines.length)];
	}
	
	/**
	 * The placeholder values of one conversation, rolled once so that everyone talks about the same hunting ground, price or castle.
	 */
	private static final class Context
	{
		final List<Speaker> speakers;
		private final String[][] _values;
		
		Context(String town, List<Speaker> speakers)
		{
			this.speakers = speakers;
			final Speaker a = speakers.get(0);
			final Speaker b = speakers.get(1);
			_values = new String[][]
			{
				// @formatter:off
				{"{nameA}", a.name.toLowerCase()},
				{"{nameB}", b.name.toLowerCase()},
				{"{lvlA}", String.valueOf(a.level)},
				{"{clsA}", a.className},
				{"{spotA}", spot(a.level)},
				{"{spotB}", spot(b.level)},
				{"{weaponA}", weaponName(a.weaponId)},
				{"{encA}", String.valueOf(Math.max(1, a.weaponEnchant))},
				{"{encNext}", String.valueOf(Math.max(4, a.weaponEnchant + Rnd.get(1, 3)))},
				{"{next}", String.valueOf(nextGoal(a.level))},
				{"{mat}", material(a.level)},
				{"{ss}", soulshots(a.level)},
				{"{price}", String.valueOf(price(a.level))},
				{"{town}", town},
				{"{bigTown}", BIG_TOWNS[Rnd.get(BIG_TOWNS.length)]},
				{"{castle}", CASTLES[Rnd.get(CASTLES.length)]}
				// @formatter:on
			};
		}
		
		String fill(String text)
		{
			if (text.indexOf('{') < 0)
			{
				return text;
			}
			
			String result = text;
			for (String[] value : _values)
			{
				result = result.replace(value[0], value[1]);
			}
			return result;
		}
	}
	
	/**
	 * @param level a level
	 * @return a hunting ground that fits it, the way players call it
	 */
	static String spot(int level)
	{
		final List<Spot> fitting = new ArrayList<>();
		for (Spot spot : SPOTS)
		{
			if ((level >= spot.min()) && (level <= spot.max()))
			{
				fitting.add(spot);
			}
		}
		
		final Spot spot = fitting.isEmpty() ? SPOTS[SPOTS.length - 1] : fitting.get(Rnd.get(fitting.size()));
		return spot.names()[Rnd.get(spot.names().length)];
	}
	
	private static int nextGoal(int level)
	{
		if (level < 20)
		{
			return 20;
		}
		if (level < 40)
		{
			return 40;
		}
		if (level < 52)
		{
			return 52;
		}
		if (level < 61)
		{
			return 61;
		}
		if (level < 76)
		{
			return 76;
		}
		return Math.min(85, level < 80 ? 80 : level + 1);
	}
	
	private static String material(int level)
	{
		final String[] materials = level < 40 ? LOW_MATERIALS : level < 70 ? MID_MATERIALS : HIGH_MATERIALS;
		return materials[Rnd.get(materials.length)];
	}
	
	private static String soulshots(int level)
	{
		final String grade = level < 20 ? "ng" : level < 40 ? "d" : level < 52 ? "c" : level < 61 ? "b" : level < 76 ? "a" : "s";
		switch (Rnd.get(4))
		{
			case 0:
			{
				return "ss" + grade;
			}
			case 1:
			{
				return "bss" + grade;
			}
			case 2:
			{
				return grade + " shots";
			}
			default:
			{
				return grade + " grade soulshots";
			}
		}
	}
	
	private static int price(int level)
	{
		if (level < 40)
		{
			return Rnd.get(1, 30);
		}
		if (level < 70)
		{
			return Rnd.get(5, 150);
		}
		return Rnd.get(20, 900);
	}
	
	private static String weaponName(int weaponId)
	{
		final ItemTemplate item = weaponId > 0 ? ItemData.getInstance().getTemplate(weaponId) : null;
		if (item == null)
		{
			return "weapon";
		}
		
		// Players call a weapon by its name without the special ability ("Dark Screamer - Wide Blow" is a dark screamer).
		String name = item.getName();
		final int dash = name.indexOf(" - ");
		if (dash > 0)
		{
			name = name.substring(0, dash);
		}
		return name.toLowerCase();
	}
	
	/**
	 * Types the text the way the speaker does: case, punctuation, a typo or a smiley now and then.
	 * @param text the text
	 * @param style the speaker's style
	 * @return the text as typed
	 */
	static String style(String text, Style style)
	{
		String result = style.lowercase ? text.toLowerCase() : Character.toUpperCase(text.charAt(0)) + text.substring(1);
		if (style.dropsPunctuation && (result.length() > 3) && (result.endsWith("?") || result.endsWith("!")) && (Rnd.get(100) < 60))
		{
			result = result.substring(0, result.length() - 1);
		}
		
		if ((style.typoChance > 0) && (result.length() > 5) && (Rnd.get(100) < style.typoChance))
		{
			result = typo(result);
		}
		
		if ((style.smileyChance > 0) && (Rnd.get(100) < style.smileyChance) && !result.endsWith(")") && !result.endsWith("D") && !result.endsWith("?") && !isSad(result))
		{
			result = result + " " + (Rnd.get(100) < 70 ? style.smiley : SMILEYS[Rnd.get(SMILEYS.length)]);
		}
		return result;
	}
	
	/**
	 * @param text a line
	 * @return {@code true} if it is bad news (no smiley after that)
	 */
	private static boolean isSad(String text)
	{
		final String lower = text.toLowerCase();
		return lower.contains("broke") || lower.contains("rip") || lower.contains("ugh") || lower.contains("fml") || lower.contains("lost") || lower.contains("failed") || lower.contains("ouch") || lower.contains("unlucky") || lower.contains("sry");
	}
	
	/**
	 * @param text the text
	 * @return the text with two letters of a word swapped, like a quick typist
	 */
	private static String typo(String text)
	{
		for (int attempt = 0; attempt < 5; attempt++)
		{
			final int i = Rnd.get(1, text.length() - 2);
			if (Character.isLetter(text.charAt(i)) && Character.isLetter(text.charAt(i + 1)) && (text.charAt(i) != text.charAt(i + 1)))
			{
				return text.substring(0, i) + text.charAt(i + 1) + text.charAt(i) + text.substring(i + 2);
			}
		}
		return text;
	}
}
