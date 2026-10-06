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
package org.l2jmobius.gameserver.config.custom;

import org.l2jmobius.commons.util.ConfigReader;

/**
 * Loads the Alternative Class Transfer Challenge configuration: a solo, instanced trial offered by the Class Master NPC (900001) whose completion unlocks the Class Master transfer for that tier. The challenges themselves live in {@code data/ClassTransferChallenges/*.xml}.
 * @author Mobius
 */
public class ClassTransferChallengeConfig
{
	// File
	private static final String CLASS_TRANSFER_CHALLENGE_CONFIG_FILE = "./config/Custom/ClassTransferChallenge.ini";

	/** What happens when a player's HP reaches zero inside a trial. */
	public enum DeathBehavior
	{
		/** The death is cancelled: the player is knocked out, then sent back to the trial entrance with progress kept. */
		REVIVE,
		/** The death happens normally and the trial fails. */
		FAIL
	}

	// Constants
	public static boolean ENABLED;
	public static boolean ENTRY_ENABLED;
	public static boolean REQUIRED_FOR_TRANSFER;
	public static int DEFAULT_DURATION;
	public static int DISCONNECT_GRACE_PERIOD;
	public static DeathBehavior DEATH_BEHAVIOR;
	public static int KNOCKOUT_DELAY;
	public static int REVIVE_INVULNERABILITY;
	public static boolean ALLOW_RESTART;
	public static int MAXIMUM_ATTEMPTS;
	public static int RETRY_COOLDOWN;
	public static boolean ALLOW_SUMMONS;
	public static boolean OMENS_ENABLED;
	public static boolean TWISTS_ENABLED;
	public static boolean REWARDS_ENABLED;
	public static boolean LOGGING;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(CLASS_TRANSFER_CHALLENGE_CONFIG_FILE);
		ENABLED = config.getBoolean("EnableAlternativeClassTransferChallenges", true);
		ENTRY_ENABLED = config.getBoolean("ChallengeEntryEnabled", true);
		REQUIRED_FOR_TRANSFER = config.getBoolean("ChallengeRequiredForTransfer", true);
		DEFAULT_DURATION = Math.max(60, config.getInt("ChallengeDefaultDuration", 1200));
		DISCONNECT_GRACE_PERIOD = Math.max(0, config.getInt("ChallengeDisconnectGracePeriod", 180));
		DEATH_BEHAVIOR = config.getEnum("ChallengeDeathBehavior", DeathBehavior.class, DeathBehavior.REVIVE);
		KNOCKOUT_DELAY = Math.max(1, config.getInt("ChallengeKnockoutDelay", 3));
		REVIVE_INVULNERABILITY = Math.max(0, config.getInt("ChallengeReviveInvulnerability", 5));
		ALLOW_RESTART = config.getBoolean("ChallengeAllowRestart", true);
		MAXIMUM_ATTEMPTS = Math.max(0, config.getInt("ChallengeMaximumAttempts", 0));
		RETRY_COOLDOWN = Math.max(0, config.getInt("ChallengeRetryCooldown", 0));
		ALLOW_SUMMONS = config.getBoolean("ChallengeAllowSummons", true);
		OMENS_ENABLED = config.getBoolean("ChallengeOmensEnabled", true);
		TWISTS_ENABLED = config.getBoolean("ChallengeTwistsEnabled", true);
		REWARDS_ENABLED = config.getBoolean("ChallengeRewardsEnabled", true);
		LOGGING = config.getBoolean("ChallengeLogging", false);
	}
}
