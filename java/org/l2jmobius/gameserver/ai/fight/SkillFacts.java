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
package org.l2jmobius.gameserver.ai.fight;

import java.util.Set;

/**
 * What a fake player knows of a skill when it sees it used or decides to use it: the facts of its data, read once (see {@link SkillFactsReader}). The fight logic in this package works on these instead of on the server's skills, so it can be tested without a server.
 * @param id the skill id
 * @param level the skill level
 * @param bad {@code true} for an offensive skill (damage, debuffs)
 * @param self {@code true} for a skill cast on oneself
 * @param castRange its cast range, 0 or less for none
 * @param hitTimeMs its cast time
 * @param coolTimeMs the pause after the cast
 * @param reuseMs its reuse time
 * @param abnormalTimeMs how long its effect lasts, 0 for none
 * @param abnormalType the name of its abnormal type ("NONE" for none)
 * @param effectTypes the names of the effect types of its effects (STUN, MAGICAL_ATTACK, HEAL...)
 * @param charge {@code true} for a skill that charges at the target (Rush)
 * @param power its power
 * @param activateRate its base land chance in percent, 0 or less for none
 * @param magicLevel its magic level
 * @param basicProperty the name of the stat that resists it (PHYSICAL, MAGIC, NONE)
 */
public record SkillFacts(int id, int level, boolean bad, boolean self, int castRange, int hitTimeMs, int coolTimeMs, int reuseMs, int abnormalTimeMs, String abnormalType, Set<String> effectTypes, boolean charge, double power, int activateRate, int magicLevel, String basicProperty)
{
}
