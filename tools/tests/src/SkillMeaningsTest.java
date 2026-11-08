import java.util.EnumSet;
import java.util.Set;

import org.l2jmobius.gameserver.ai.fight.SkillFacts;
import org.l2jmobius.gameserver.ai.fight.SkillMeaning;
import org.l2jmobius.gameserver.ai.fight.SkillMeanings;

public class SkillMeaningsTest
{
	static SkillFacts facts(int id, boolean bad, boolean self, int reuse, int abnormalTime, String abnormal, double power, String... effects)
	{
		return new SkillFacts(id, 1, bad, self, 600, 1000, 300, reuse, abnormalTime, abnormal, Set.of(effects), false, power, 80, 76, "PHYSICAL");
	}
	
	public static void main(String[] args)
	{
		Check.run("SkillMeaningsTest", //
			() -> // A stun shot: control and damage.
			{
				final EnumSet<SkillMeaning> m = SkillMeanings.classify(facts(101, true, false, 15000, 9000, "STUN", 1500, "PHYSICAL_ATTACK", "STUN"));
				Check.isTrue(m.contains(SkillMeaning.CC) && m.contains(SkillMeaning.NUKE), "stun shot is CC and NUKE: " + m);
			}, //
			() -> Check.isTrue(SkillMeanings.classify(facts(821, true, false, 30000, 0, "NONE", 0, "TELEPORT_TO_TARGET")).contains(SkillMeaning.GAP_CLOSER), "shadow step is a gap closer"), //
			() -> Check.eq(SkillMeanings.classify(facts(1235, true, false, 3000, 0, "NONE", 120, "MAGICAL_ATTACK")), EnumSet.of(SkillMeaning.NUKE), "hydro blast"), //
			() -> Check.eq(SkillMeanings.classify(facts(110, false, true, 1800000, 30000, "PD_UP_SPECIAL", 0, "BUFF")), EnumSet.of(SkillMeaning.DEFENSIVE), "ultimate defense"), //
			() -> Check.eq(SkillMeanings.classify(facts(176, false, true, 300000, 120000, "BERSERKER", 0, "BUFF")), EnumSet.of(SkillMeaning.BURST_BUFF), "frenzy"), //
			() -> Check.eq(SkillMeanings.classify(facts(0, true, false, 0, 9000, "STUN", 0, "STUN")), EnumSet.of(SkillMeaning.OTHER), "id 0"), //
			() -> Check.eq(SkillMeanings.classify(facts(5000, false, false, 0, 0, "NONE", 0)), EnumSet.of(SkillMeaning.OTHER), "nothing known"), //
			() -> Check.eq(SkillMeanings.classify(facts(1011, false, false, 0, 0, "NONE", 0, "HEAL")), EnumSet.of(SkillMeaning.HEAL), "heal"), //
			() -> Check.eq(SkillMeanings.classify(facts(1056, true, false, 0, 0, "NONE", 0, "DISPEL")), EnumSet.of(SkillMeaning.CANCEL), "cancel"), //
			() -> Check.eq(SkillMeanings.classify(facts(1206, true, false, 0, 30000, "PD_DOWN", 0, "DEBUFF")), EnumSet.of(SkillMeaning.DEBUFF), "a P.Def debuff"));
	}
}
