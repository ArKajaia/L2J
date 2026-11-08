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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.ai.fight.SkillWeights;

/**
 * Loads config/Custom/FakeSkillWeights.ini into {@link SkillWeights}.
 */
public class FakeSkillWeightsConfig
{
	private static final Logger LOGGER = Logger.getLogger(FakeSkillWeightsConfig.class.getName());
	private static final String FAKE_SKILL_WEIGHTS_CONFIG_FILE = "./config/Custom/FakeSkillWeights.ini";
	
	public static void load()
	{
		List<String> lines = List.of();
		try
		{
			lines = Files.readAllLines(Path.of(FAKE_SKILL_WEIGHTS_CONFIG_FILE));
		}
		catch (IOException e)
		{
			LOGGER.warning("FakeSkillWeightsConfig: " + FAKE_SKILL_WEIGHTS_CONFIG_FILE + " not read, using the default weights: " + e.getMessage());
		}
		SkillWeights.load(lines);
	}
}
