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
package org.l2jmobius.gameserver.ai;

import java.util.Locale;

import org.l2jmobius.gameserver.config.custom.TeamVsTeamConfig;
import org.l2jmobius.gameserver.managers.TeamVsTeamManager;
import org.l2jmobius.gameserver.model.actor.Creature;

/**
 * How the fake players of a Team vs Team team pick their targets and where its archers and mages stand (brain A, {@link FakePlayerPvpAI}). Every team of a match plays the tactics of
 * {@link TeamVsTeamConfig#FAKE_TACTICS}; a simulated batch ({@code //tvt batch}) can give each team its own, to measure one against the other (see docs/tvt-tactics.md).
 * <p>
 * Outside Team vs Team (roaming fake players, party simulations, the A/B arena) the fake players always play {@link #LEGACY}, the tactics brain A had before these knobs.
 * @param name a short name, for the logs and the batch results
 * @param healer how much better an enemy healer is as a target for a member that is not a tank, a dagger nor a nuker (on a score where 1 is full HP; legacy 0.6)
 * @param assassinHealer the same for a dagger (legacy 1.2)
 * @param nukerHealer the same for a nuker, an archer or mage that isn't a support (legacy 0.6). A nuker that weighs healers less than the others doesn't follow its party's call on a healer.
 * @param nukerDps how much better an enemy damage dealer (not a tank, healer nor buffer) is as a target for a nuker or an archer (legacy 0)
 * @param healerCalls whether a member calls its party onto an enemy healer (legacy {@code true})
 * @param standoff archers and mages stay back: they don't walk past their party's front to reach a target, prefer one within their reach, and a mage between casts holds at the edge of
 *            its reach instead of 600 from its target (legacy {@code false})
 * @param standoffWait with {@code standoff}: how long (milliseconds) an archer or mage waits behind its party's front for the front to go in before it walks up to its target anyway, 0 not to wait
 * @param healerInReach an enemy healer is worth its priority (and called for the party) only while one of the team's archers or nukers reaches it: a nuker's own reach, for anyone else one of the
 *            archers or nukers of its party; out of that reach it is a target like any other (legacy {@code false})
 * @param healerLock an archer or nuker with an enemy healer within its own reach stuns, silences or paralyzes it (a skill it has ready), then goes back to its target (legacy {@code false})
 * @param nukerNuker how much better an enemy nuker (a mage that isn't a support) is as a target for a nuker or an archer, on top of {@code nukerDps} (legacy 0)
 * @param assassinRush a dagger weighs an enemy healer by {@code assassinHealer} only while the healer is within reach of its gap closer (Shadow Step); farther it is a target like any other (legacy {@code false})
 * @param tankHealer how much better an enemy healer is as a target for a tank (legacy 0)
 * @param ease how much better an enemy close by is as a target the easier its class is to kill in a team fight ({@link FakeTvtKillEase}: 0 for a tank, 1 for a dagger), times this; close by is within
 *            its reach for an archer or mage, within 600 for anyone else (legacy 0)
 * @param hunters the share of the tanks and daggers that hunt healers (each one decided once, by its object id): they weigh an enemy healer by {@code hunterHealer}, the others play the rest of
 *            the tactics (legacy 0)
 * @param hunterHealer with {@code hunters}: how much better an enemy healer is as a target for a hunter
 */
public record FakeTvtTactics(String name, double healer, double assassinHealer, double nukerHealer, double nukerDps, boolean healerCalls, boolean standoff, int standoffWait, boolean healerInReach, boolean healerLock, double nukerNuker, boolean assassinRush, double tankHealer, double ease, double hunters, double hunterHealer)
{
	/** How much a healer hunter weighs an enemy healer by default (hunters). */
	public static final double DEFAULT_HUNTER_HEALER = 1.2;
	/** Brain A before the tactics: what every fake player plays outside Team vs Team. */
	public static final FakeTvtTactics LEGACY = new FakeTvtTactics("legacy", 0.6, 1.2, 0.6, 0, true, false, 0, false, false, 0, false, 0, 0, 0, DEFAULT_HUNTER_HEALER);
	/** How long an archer or mage waits behind its front by default (standoff). */
	public static final int DEFAULT_STANDOFF_WAIT = 4000;

	/**
	 * @param creature a fake player
	 * @return the tactics of its Team vs Team team, {@link #LEGACY} outside Team vs Team (and in a Deathmatch)
	 */
	public static FakeTvtTactics of(Creature creature)
	{
		if (!TeamVsTeamManager.ACTIVE)
		{
			return LEGACY;
		}
		final TeamVsTeamManager.Fighter fighter = TeamVsTeamManager.getFighter(creature);
		return (fighter == null) || fighter.getMatch().isDeathmatch() ? LEGACY : fighter.getSide().getTactics();
	}

	/**
	 * Reads tactics from a spec: a preset name ({@code legacy}, {@code focus}, {@code split}, {@code dps}, {@code standoff}, {@code best}), optionally followed by {@code :} and
	 * {@code key=value} pairs separated by commas that change it ({@code healer}, {@code assassin}, {@code nukerHealer}, {@code nukerDps}, {@code calls}, {@code standoff}, {@code wait}, {@code reach}, {@code lock}, {@code nukerNuker}, {@code rush}, {@code tankHealer}, {@code ease}, {@code hunters}, {@code hunterHealer}, {@code name}), or
	 * only pairs (on {@link #LEGACY}). For example {@code legacy:healer=1.2,name=h12} or {@code healer=0,calls=0}.
	 * @param spec the spec
	 * @return the tactics
	 * @throws IllegalArgumentException for an unknown preset or key, or a bad number
	 */
	public static FakeTvtTactics parse(String spec)
	{
		final String text = spec == null ? "" : spec.trim();
		if (text.isEmpty())
		{
			return LEGACY;
		}

		final int colon = text.indexOf(':');
		final String head = colon >= 0 ? text.substring(0, colon).trim() : (text.contains("=") ? "" : text);
		final String pairs = colon >= 0 ? text.substring(colon + 1) : (text.contains("=") ? text : "");
		FakeTvtTactics tactics = head.isEmpty() ? LEGACY : preset(head);
		// A preset changed by pairs is named after both ("ignore:standoff=1+wait=0"), commas left out for the batch results.
		String name = head.isEmpty() ? null : (pairs.isBlank() ? tactics.name() : tactics.name() + ":" + pairs.trim().replace(',', '+').replace(" ", ""));
		double healer = tactics.healer;
		double assassin = tactics.assassinHealer;
		double nukerHealer = tactics.nukerHealer;
		double nukerDps = tactics.nukerDps;
		boolean calls = tactics.healerCalls;
		boolean standoff = tactics.standoff;
		int wait = tactics.standoffWait;
		boolean reach = tactics.healerInReach;
		boolean lock = tactics.healerLock;
		double nukerNuker = tactics.nukerNuker;
		boolean rush = tactics.assassinRush;
		double tankHealer = tactics.tankHealer;
		double ease = tactics.ease;
		double hunters = tactics.hunters;
		double hunterHealer = tactics.hunterHealer;
		for (String pair : pairs.split(","))
		{
			if (pair.isBlank())
			{
				continue;
			}
			final String[] kv = pair.split("=", 2);
			if (kv.length != 2)
			{
				throw new IllegalArgumentException("Expected key=value, got '" + pair.trim() + "'.");
			}
			final String key = kv[0].trim().toLowerCase(Locale.ROOT);
			final String value = kv[1].trim();
			switch (key)
			{
				case "name" -> name = value.replace(',', '+');
				case "healer" -> healer = Double.parseDouble(value);
				case "assassin", "assassinhealer" -> assassin = Double.parseDouble(value);
				case "nukerhealer" -> nukerHealer = Double.parseDouble(value);
				case "nukerdps", "dps" -> nukerDps = Double.parseDouble(value);
				case "calls", "healercalls" -> calls = parseBoolean(value);
				case "standoff" ->
				{
					standoff = parseBoolean(value);
					if (standoff && (wait == 0) && !pairs.toLowerCase(Locale.ROOT).contains("wait="))
					{
						wait = DEFAULT_STANDOFF_WAIT;
					}
				}
				case "wait", "standoffwait" -> wait = Math.max(0, Integer.parseInt(value));
				case "reach", "healerinreach" -> reach = parseBoolean(value);
				case "lock", "healerlock" -> lock = parseBoolean(value);
				case "nukernuker" -> nukerNuker = Double.parseDouble(value);
				case "rush", "assassinrush" -> rush = parseBoolean(value);
				case "tankhealer" -> tankHealer = Double.parseDouble(value);
				case "ease" -> ease = Double.parseDouble(value);
				case "hunters" -> hunters = Math.max(0, Math.min(1, Double.parseDouble(value)));
				case "hunterhealer" -> hunterHealer = Double.parseDouble(value);
				default -> throw new IllegalArgumentException("Unknown tactics key '" + key + "' (healer, assassin, nukerHealer, nukerDps, calls, standoff, wait, reach, lock, nukerNuker, rush, tankHealer, ease, hunters, hunterHealer, name).");
			}
		}
		if (name == null)
		{
			name = describe(healer, assassin, nukerHealer, nukerDps, calls, standoff, wait, reach, lock, nukerNuker, rush, tankHealer) + (ease > 0 ? String.format(Locale.ROOT, "-ease%.2f", ease) : "") + (hunters > 0 ? String.format(Locale.ROOT, "-hunt%.2f@%.2f", hunters, hunterHealer) : "");
		}
		return new FakeTvtTactics(name, healer, assassin, nukerHealer, nukerDps, calls, standoff, wait, reach, lock, nukerNuker, rush, tankHealer, ease, hunters, hunterHealer);
	}

	/**
	 * @param name a preset name
	 * @return the preset
	 */
	private static FakeTvtTactics preset(String name)
	{
		return switch (name.toLowerCase(Locale.ROOT))
		{
			case "legacy" -> LEGACY;
			// Everyone on the healer: twice the legacy priority, the nukers too.
			case "focus" -> new FakeTvtTactics("focus", 1.2, 2.4, 1.2, 0, true, false, 0, false, false, 0, false, 0, 0, 0, DEFAULT_HUNTER_HEALER);
			// Nobody hunts the healer: the damage dealers first.
			case "ignore" -> new FakeTvtTactics("ignore", 0, 0, 0, 0.3, false, false, 0, false, false, 0, false, 0, 0, 0, DEFAULT_HUNTER_HEALER);
			// Divided labor: the daggers and melee on the healer, the nukers and archers on the enemy damage dealers.
			case "split" -> new FakeTvtTactics("split", 0.6, 1.6, 0, 0.4, true, false, 0, false, false, 0, false, 0, 0, 0, DEFAULT_HUNTER_HEALER);
			// Only the nukers change: on the damage dealers.
			case "dps" -> new FakeTvtTactics("dps", 0.6, 1.2, 0, 0.4, true, false, 0, false, false, 0, false, 0, 0, 0, DEFAULT_HUNTER_HEALER);
			// Only the positions change: archers and mages stay back.
			case "standoff" -> new FakeTvtTactics("standoff", 0.6, 1.2, 0.6, 0, true, true, DEFAULT_STANDOFF_WAIT, false, false, 0, false, 0, 0, 0, DEFAULT_HUNTER_HEALER);
			// What the batches of 2026-11-07/08 found best (docs/tvt-tactics.md): ignore, but daggers still look at a healer a little, archers and mages stay back without waiting for the front, and
			// everyone prefers the enemies close by that are easiest to kill (ease 2.0, round 7).
			case "best" -> new FakeTvtTactics("best", 0, 0.6, 0, 0.3, false, true, 0, false, false, 0, false, 0, 2.0, 0, DEFAULT_HUNTER_HEALER);
			default -> throw new IllegalArgumentException("Unknown tactics preset '" + name + "' (legacy, focus, ignore, split, dps, standoff, best).");
		};
	}

	private static boolean parseBoolean(String value)
	{
		return switch (value.toLowerCase(Locale.ROOT))
		{
			case "1", "true", "yes", "on" -> true;
			case "0", "false", "no", "off" -> false;
			default -> throw new IllegalArgumentException("Expected 0 or 1, got '" + value + "'.");
		};
	}

	private static String describe(double healer, double assassin, double nukerHealer, double nukerDps, boolean calls, boolean standoff, int wait, boolean reach, boolean lock, double nukerNuker, boolean rush, double tankHealer)
	{
		return String.format(Locale.ROOT, "h%.2f-a%.2f-nh%.2f-nd%.2f%s%s%s%s%s%s%s", healer, assassin, nukerHealer, nukerDps, calls ? "" : "-nocall", standoff ? "-standoff" + wait : "", reach ? "-reach" : "", lock ? "-lock" : "", nukerNuker > 0 ? String.format(Locale.ROOT, "-nn%.2f", nukerNuker) : "", rush ? "-rush" : "", tankHealer > 0 ? String.format(Locale.ROOT, "-th%.2f", tankHealer) : "");
	}

	/**
	 * @return the tactics as a spec {@link #parse} reads back
	 */
	public String toSpec()
	{
		return String.format(Locale.ROOT, "healer=%s,assassin=%s,nukerHealer=%s,nukerDps=%s,calls=%d,standoff=%d,wait=%d,reach=%d,lock=%d,nukerNuker=%s,rush=%d,tankHealer=%s,ease=%s,hunters=%s,hunterHealer=%s,name=%s", healer, assassinHealer, nukerHealer, nukerDps, healerCalls ? 1 : 0, standoff ? 1 : 0, standoffWait, healerInReach ? 1 : 0, healerLock ? 1 : 0, nukerNuker, assassinRush ? 1 : 0, tankHealer, ease, hunters, hunterHealer, name);
	}
}
