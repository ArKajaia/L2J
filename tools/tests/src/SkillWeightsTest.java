import java.util.List;
import java.util.Map;

import org.l2jmobius.gameserver.ai.fight.SkillWeights;

public class SkillWeightsTest
{
	public static void main(String[] args)
	{
		Check.run("SkillWeightsTest", //
			() -> // Set keys override, the rest keep the role's defaults, bad lines are skipped.
			{
				final Map<String, SkillWeights> weights = SkillWeights.parse(List.of("MAGE.risk = 1.5", "TANK.raceMargin=0.5", "bad line", "# a comment", "MAGE.nothing = 3"));
				Check.near(weights.get("MAGE").risk(), 1.5, 0, "MAGE risk");
				Check.near(weights.get("MAGE").raceMargin(), 0.75, 0, "MAGE race margin stays");
				Check.near(weights.get("TANK").raceMargin(), 0.5, 0, "TANK race margin");
				Check.eq(weights.get("FIGHTER"), SkillWeights.DEFAULT, "FIGHTER is the default");
			}, //
			() -> // The defaults of the spec, and the role defaults.
			{
				final Map<String, SkillWeights> weights = SkillWeights.parse(List.of());
				final SkillWeights d = SkillWeights.DEFAULT;
				Check.near(d.damage() + d.control() + d.cooldownCost() + d.risk(), 4.0, 0, "1.0 each");
				Check.near(d.setup(), 0.6, 0, "setup");
				Check.near(d.raceMargin(), 0.75, 0, "race margin");
				Check.near(d.burstReflexTtd(), 3.0, 0, "burst reflex");
				Check.near(d.noise(), 0.35, 0, "noise");
				Check.eq(d.planDepth(), 3, "plan depth");
				Check.near(weights.get("MAGE").risk(), 1.3, 0, "mage risk");
				Check.near(weights.get("ARCHER").risk(), 1.3, 0, "archer risk");
				Check.near(weights.get("TANK").raceMargin(), 0.6, 0, "tank race margin");
				Check.eq(weights.get("DAGGER"), SkillWeights.DEFAULT, "dagger");
			}, //
			() -> Check.eq(SkillWeights.of("NOT_A_ROLE"), SkillWeights.DEFAULT, "unknown role"));
	}
}
