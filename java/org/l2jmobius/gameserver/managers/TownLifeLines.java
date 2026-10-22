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

import org.l2jmobius.commons.util.Rnd;

/**
 * What the town life npcs say (see {@link TownLifeManager}). In the lines, %town% is the name of the town and %name% the name of the player spoken to.
 */
final class TownLifeLines
{
	/** Two townsfolk meeting in the street: the first one speaks, the other answers, and so on. */
	static final String[][] CONVERSATIONS =
	{
		{
			"Good day, neighbour! Busy as ever?",
			"Always. The market won't run itself, you know.",
			"Ha! Don't work too hard."
		},
		{
			"Did you hear? Another band of adventurers came back from the hunt last night.",
			"Came back? Some of them, maybe. The rest are still waiting for a priest.",
			"Brave fools, the lot of them."
		},
		{
			"The bread at the grocer's was still warm this morning.",
			"Then I'd better hurry before the adventurers buy it all."
		},
		{
			"My cousin says the roads out of %town% aren't safe after dark.",
			"Your cousin says a lot of things.",
			"True. But he's still got all ten fingers."
		},
		{
			"Have you seen the prices at the weapon shop?",
			"I don't need a sword. I need a new roof.",
			"Same thing, these days."
		},
		{
			"Lovely weather today.",
			"Enjoy it while it lasts. The old folks say the night will be a strange one."
		},
		{
			"Is it true the warehouse keeper lost a crate again?",
			"Lost? I'd say it walked off on somebody's back.",
			"Shh! Not so loud."
		},
		{
			"I heard a hero passed through %town% yesterday.",
			"A real one? With the shining weapon and everything?",
			"That's what they say. I only saw the dust."
		},
		{
			"My boy wants to be an adventurer when he grows up.",
			"Mine too. I told him to try the warehouse first. Fewer orcs."
		},
		{
			"Where are you off to in such a hurry?",
			"The blacksmith. My kettle's got a hole in it again.",
			"Give him my regards. And my unpaid bill."
		},
		{
			"They say the castle lord raised the taxes again.",
			"Of course he did. Somebody has to pay for all those sieges.",
			"Well, it isn't going to be me. Not this month."
		},
		{
			"Did the children keep you up last night?",
			"Not them. The cat. Chasing something across the roof all night."
		},
		{
			"Ah, good to see you! How is your mother?",
			"Better, thank you. The priest's herbs worked wonders.",
			"Glad to hear it. Give her my best."
		},
		{
			"Somebody left a pile of rusty swords by the gate again.",
			"Adventurers. They pick up everything and keep nothing.",
			"The scrap dealer won't complain."
		},
		{
			"I'm thinking of opening a stall in the market.",
			"Selling what?",
			"Advice. Everybody here seems to need some."
		},
		{
			"Is the Night Market really open after dark in Giran?",
			"So I've heard. Strange wares, stranger merchants.",
			"I'll stick to the grocer, thank you."
		},
		{
			"You look tired.",
			"The tavern was loud until the Witching Hour.",
			"Serves you right for going."
		},
		{
			"Watch your purse, friend. I saw a strange fellow by the shops.",
			"Thanks for the warning. Probably just another adventurer looking for a party."
		},
	};
	
	/** Townsfolk greeting a player walking by. */
	static final String[] GREETINGS =
	{
		"Good day, %name%!",
		"Welcome to %town%, %name%.",
		"Mind the cobbles, %name%, they're slippery.",
		"Off to the hunt again, %name%?",
		"Stay safe out there, %name%.",
		"Nice armour, %name%. Is it new?",
		"Hello, %name%! Lovely day, isn't it?",
		"Ah, an adventurer! Good luck, %name%.",
		"Don't trample the flowers, %name%!",
		"Greetings, %name%. Need directions?",
	};
	
	/** A townsperson standing at a shop or looking around. */
	static final String[] MUSINGS =
	{
		"Now, what did I come here for?",
		"Hmm, maybe I'll buy it tomorrow.",
		"Such prices! In my day a potion cost half that.",
		"I could stand here all day.",
		"Just looking, just looking.",
		"Ah, %town%. Never a dull moment.",
	};
	
	/** Porters and errand runners. */
	static final String[] WORKER =
	{
		"Another crate, another copper.",
		"Make way, heavy load coming through!",
		"Who packs these things? Rocks?",
		"Hup! One more and I'm taking a break.",
		"The warehouse keeper wants this by noon. Which noon, he didn't say.",
		"Careful, careful... fragile, it says.",
	};
	
	/** The street sweeper. */
	static final String[] SWEEPER =
	{
		"Adventurers... always tracking mud everywhere.",
		"Somebody dropped a jellyfish here. A jellyfish!",
		"Sweep, sweep, sweep...",
		"A clean street is a happy street.",
		"Who leaves empty potion bottles lying around?",
	};
	
	/** Children at play. */
	static final String[] CHILD_TAG =
	{
		"Tag! You're it!",
		"Got you! You're it now!",
		"Ha! Tag!",
	};
	
	static final String[] CHILD_PLAY =
	{
		"Can't catch me!",
		"Hee hee hee!",
		"Over here! Over here!",
		"Too slow!",
		"No fair, you peeked!",
		"Wait for me!",
		"I'm the fastest in %town%!",
	};
	
	/** A child asking a player for a sweet. */
	static final String[] CHILD_ASK =
	{
		"Hey, %name%! Do you have a sweet for me? Please?",
		"Hey, adventurer! %name%! Got any candy?",
		"%name%, %name%! Talk to me, I want to show you something! ...and maybe you have a sweet?",
		"Psst, %name%! Mom says I can have one sweet a day. Just one!",
	};
	
	/** The children called home at dusk. */
	static final String[] CHILD_HOME =
	{
		"Aww, Mom's calling. Bye!",
		"I have to go home before it's dark!",
		"Same time tomorrow, okay?",
		"Race you home!",
	};
	
	/** Town patrols. */
	static final String[] PATROL =
	{
		"All quiet on this side.",
		"Keep moving. Nothing to see here.",
		"Keep your weapons sheathed in town, adventurers.",
		"Eyes open. Thieves love a crowded market.",
		"Another round, then the barracks.",
	};
	
	/** The lamplighter at dusk. */
	static final String[] LAMPLIGHTER =
	{
		"There we are, nice and bright.",
		"One more lamp... and another... and another.",
		"Night's coming. Can't have folk tripping in the dark.",
		"Lamps lit, %town% is safe for another night.",
	};
	
	/** Night watchmen, at the Witching Hour. */
	static final String[] NIGHT_WATCH_WITCHING =
	{
		"The Witching Hour! Stay indoors, good people!",
		"Lock your doors! The dead walk tonight!",
		"Keep away from the walls! Something stirs out there!",
	};
	
	/** The tavern crowd at night. */
	static final String[] TAVERN =
	{
		"Another round! On me! ...no wait, on him!",
		"And then I said to the orc, I said...",
		"Ha ha ha! Tell it again!",
		"To %town%! Cheers!",
		"This ale tastes like boiled boots. I'll have another.",
		"Sing, sing! Who knows the song of the Dragon Valley?",
		"Shh! The watch is coming.",
		"Best night of the week, this is.",
	};
	
	/** The tavern crowd leaving at the Witching Hour. */
	static final String[] TAVERN_LEAVE =
	{
		"The Witching Hour already? I'm off home.",
		"Time to go before the dead come knocking.",
		"Goodnight, all! Hic!",
	};
	
	/** Fishermen at the harbor. */
	static final String[] FISHERMAN =
	{
		"A bite! ...no, just weeds.",
		"The fish are shy today.",
		"Shh! You'll scare them off.",
		"Yesterday I caught one THIS big. Honest.",
		"Nothing beats the morning tide.",
	};
	
	/** Dock workers. */
	static final String[] DOCK_WORKER =
	{
		"Heave! And... ho!",
		"Mind the ropes!",
		"Whose barrels are these? They stink of fish.",
		"The ship's late again. Typical.",
		"One more crate and we're done. They always say that.",
	};
	
	/** The crier when there is no news. */
	static final String[] CRIER_FILLER =
	{
		"Hear ye, hear ye! The shops of %town% are open for business!",
		"Hear ye! Adventurers are reminded to keep their weapons sheathed in town!",
		"Hear ye! The warehouse keeps your goods safe, day and night!",
		"Hear ye, hear ye! Fresh supplies at the grocer's!",
		"Hear ye! Lost children are to be brought to the town square!",
	};
	
	/** Two townsfolk meeting in the street while monsters besiege %town%. */
	static final String[][] SIEGE_CONVERSATIONS =
	{
		{
			"Did you hear the horns? They're at the gates!",
			"I heard. My door's barred, I only came out for bread.",
			"Then get it and get back inside!"
		},
		{
			"How many of them are out there?",
			"Hundreds, the guards say. Maybe more.",
			"And how many defenders?",
			"Not enough. Never enough."
		},
		{
			"Will the gates hold?",
			"They held last time.",
			"Last time they didn't bring rams."
		},
		{
			"My brother went up to the walls with a pitchfork.",
			"A pitchfork? Against that?",
			"He says it's better than nothing. I say he's a fool. A brave fool."
		},
		{
			"Where are the adventurers when you need them?",
			"At the gates, the ones with any sense of honour.",
			"Then may the gods keep every one of them standing."
		},
		{
			"I can smell the smoke from here.",
			"That's the burning at the gate. Don't look, just walk.",
			"I can't help it. What if they break through?"
		},
		{
			"They say the Defence Force pays in medals for every monster slain.",
			"Medals won't patch my roof if the walls fall.",
			"No, but the walls won't fall if enough of them fight for medals."
		},
		{
			"The children are all inside?",
			"Every one. I counted them twice.",
			"Good. Now we wait."
		},
		{
			"I've hidden the silver under the floor.",
			"Monsters don't want your silver, they want your neck.",
			"Then I'll hide under the floor too."
		},
		{
			"Listen... the shouting stopped at the gate.",
			"Stopped because we won, or because...",
			"Don't say it. Don't you dare say it."
		},
		{
			"The healers are asking for clean bandages.",
			"Take my sheets. Take my curtains. Take whatever helps.",
			"Bless you, neighbour."
		},
		{
			"Why %town%? Why always us?",
			"Because we're here, and they're hungry.",
			"That's not an answer.",
			"It's the only one there is."
		},
	};
	
	/** Townsfolk greeting a player walking by during a siege. */
	static final String[] SIEGE_GREETINGS =
	{
		"%name%! Are you going to the gates? Please hurry!",
		"Thank the gods, a fighter! They need you at the walls, %name%!",
		"Don't stand around, %name%, the monsters are at the gates!",
		"%name%, the defenders need every blade they can get!",
		"Be careful out there, %name%. Come back alive.",
		"Is it true a gate is cracking, %name%? Tell me it isn't!",
		"Go on, %name%, show those beasts what %town% is made of!",
	};
	
	/** A townsperson still out in the streets during a siege. */
	static final String[] SIEGE_MUSINGS =
	{
		"Hurry, hurry... I shouldn't even be out here.",
		"Gods, listen to them at the gate...",
		"If the walls fall, I'm running for the temple.",
		"Just a few more things and I'm barring the door.",
		"Hold, gates. Please hold.",
		"Who would have thought monsters would march on %town%?",
		"Half the stalls are boarded up already.",
		"Did that come from the north gate? Or the south?",
	};
	
	/** Porters and errand runners during a siege. */
	static final String[] SIEGE_WORKER =
	{
		"Arrows for the walls! Make way!",
		"Potions for the defenders, coming through!",
		"They want more planks at the gate. Always more planks!",
		"No time to rest, the defenders need this now!",
		"Out of the way, this is for the healers!",
		"If I drop this crate, a gate falls. No pressure.",
	};
	
	/** Town patrols during a siege. */
	static final String[] SIEGE_PATROL =
	{
		"Clear the streets! Get indoors!",
		"Everyone off the streets! Only defenders outside!",
		"Keep away from the gates unless you can fight!",
		"Adventurers! To the walls! %town% needs you!",
		"Stay calm and stay inside. We'll hold.",
		"Bar your doors and shutters! Now!",
	};
	
	/** A townsperson who boasts it isn't afraid of the siege, starting a talk (until a gate is damaged and it runs home). */
	static final String[][] BOASTER_CONVERSATIONS =
	{
		{
			"Afraid of a few monsters? Not me. I'm staying right here.",
			"You're very brave. Or very foolish.",
			"Brave. Definitely brave."
		},
		{
			"I told the whole house: let them come, I'm staying out in the street.",
			"And what did they say?",
			"They barred the door behind me. Ha! ...They did bar it, didn't they?"
		},
		{
			"If one of them gets past the gate, I'll wrestle it myself.",
			"With those arms?",
			"These arms have carried many a sack of flour, friend."
		},
		{
			"Everybody's hiding. Cowards, the lot of them!",
			"And you're not scared at all?",
			"Scared? I laugh at sieges. Ha. Ha ha."
		},
	};
	
	/** A townsperson who boasts it isn't afraid of the siege. */
	static final String[] BOASTER =
	{
		"Monsters? Pah! I've seen scarier things in my soup.",
		"Afraid? Me? I'll stand right here and watch them break their teeth on our walls.",
		"Let them come! I could hold a gate myself, with a broom.",
		"Ha! A siege. In my day we called this a busy market.",
		"Hide? What for? Our walls have never fallen.",
		"I'm not afraid. I'm just... standing near my door. For no reason.",
	};
	
	/** The boaster greeting a player walking by. */
	static final String[] BOASTER_GREETINGS =
	{
		"%name%! Off to the gates? No need, I've got this street covered.",
		"Don't look so worried, %name%. I'm not.",
		"Monsters, %name%? Let them try me!",
	};
	
	/** The boaster running home once a gate is damaged. */
	static final String[] BOASTER_FLEE =
	{
		"Was that the gate?! It's cracking! I, er... just remembered I left the stove on!",
		"Right. Well. I'm not afraid, but I'm going inside. For... reasons!",
		"The gate is breaking?! Out of my way! Bravely! Out of my way!",
		"I'll, um, guard my house. From the inside. With the door barred.",
		"Nobody saw that. I was never here!",
	};
	
	/** A townsperson whose son defends a gate (%gate%), starting a talk: certain the village will hold. */
	static final String[][] PARENT_CONVERSATIONS =
	{
		{
			"My son is at the %gate%. Nothing gets past him.",
			"You must be worried sick.",
			"Worried? He's my son! The %gate% will hold."
		},
		{
			"Have you heard how the %gate% is doing?",
			"Holding, they say.",
			"Of course it's holding. My boy is there."
		},
		{
			"My son volunteered for the %gate% this morning.",
			"Brave lad.",
			"Brave? He gets it from me. They'll hold, you'll see."
		},
		{
			"Did you see the defenders march to the %gate%? My son was at the front!",
			"I saw a lot of them. Which one was he?",
			"The one in front! The tall one! The one who'll send those monsters running!"
		},
	};
	
	/** A townsperson whose son defends a gate (%gate%). */
	static final String[] PARENT =
	{
		"My son is at the %gate%. Not one of those beasts will get past him!",
		"Worried? Me? My boy holds the %gate%!",
		"Best spearman in %town%, my son. The %gate% will hold, mark my words.",
		"I packed him a lunch this morning. A fed son holds a gate, that's what I say.",
		"Those monsters don't know whose son is standing at the %gate%!",
		"Ha! Let them knock. My boy will answer the door.",
	};
	
	/** The proud parent greeting a player walking by. */
	static final String[] PARENT_GREETINGS =
	{
		"Going to the %gate%, %name%? Look out for my son, tall lad, can't miss him!",
		"%name%! If you see my boy at the %gate%, tell him we're proud of him!",
		"Don't you worry, %name%. My son is at the %gate%, we'll hold.",
	};
	
	/** The proud parent once the gate its son defends has fallen: still certain. */
	static final String[] PARENT_FALLEN =
	{
		"The %gate% fell? Nonsense. My boy is just letting them in so he can get at them!",
		"He'll have the %gate% back up by supper, you'll see.",
		"Fallen? Not with my son there. They must mean some other gate.",
		"A gate is only wood. My son is made of sterner stuff. We'll hold!",
	};
	
	/** Townsfolk running home as the siege begins. */
	static final String[] SIEGE_FLEE =
	{
		"Monsters! Monsters at the gates!",
		"Run! Get inside!",
		"The siege! It's started! Bar the doors!",
		"Gods help us, they're here!",
		"Inside, everyone, inside!",
		"I knew it, I knew they'd come!",
		"My house, I have to get home!",
		"Close the shutters! Hide the children!",
	};
	
	/** Children running home as the siege begins. */
	static final String[] SIEGE_CHILD_FLEE =
	{
		"Mommy! Mommy, the monsters are coming!",
		"I'm scared! I want to go home!",
		"Run, run, run!",
		"Wait for meee!",
	};
	
	/** Townsfolk coming back out after a siege. */
	static final String[] SIEGE_OVER =
	{
		"Is it over? It's over! We're still standing!",
		"Thank the gods... %town% held.",
		"I never want to hear those horns again.",
		"Look at the street. Look at us. We made it!",
		"Somebody buy those defenders a drink!",
		"Hah! Let them come back. We'll be ready.",
	};
	
	/** The hours of the night, for the night watch: index is the game hour 0-5. */
	static final String[] HOURS =
	{
		"Midnight",
		"One o'clock",
		"Two o'clock",
		"Three o'clock",
		"Four o'clock",
		"Five o'clock",
	};
	
	private TownLifeLines()
	{
	}
	
	/**
	 * @param lines some lines
	 * @return one of them at random
	 */
	static String random(String[] lines)
	{
		return lines[Rnd.get(lines.length)];
	}
	
	/**
	 * @param line a line
	 * @param town the name of the town
	 * @param name the name of the player spoken to, {@code null} for none
	 * @return the line with its %town% and %name% filled in
	 */
	static String fill(String line, String town, String name)
	{
		String text = line.replace("%town%", town);
		if (name != null)
		{
			text = text.replace("%name%", name);
		}
		return text;
	}
}
