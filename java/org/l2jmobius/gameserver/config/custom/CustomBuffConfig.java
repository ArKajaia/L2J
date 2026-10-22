package org.l2jmobius.gameserver.config.custom;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

public class CustomBuffConfig
{
	private static final Logger LOGGER = Logger.getLogger(CustomBuffConfig.class.getName());
	
	public static boolean ENABLE;
	public static int ITEM_ID;
	public static double DROP_CHANCE;
	public static List<Integer> SKILLS = new ArrayList<>();
	/** Skill id to the text the medal window shows under that buff (config/Custom/CustomBuffDescriptions.ini). */
	public static Map<Integer, String> DESCRIPTIONS = new HashMap<>();
	
	public static void load()
	{
		SKILLS.clear();
		loadDescriptions();
		
		try
		{
			File file = new File("./config/Custom/CustomBuffSystem.ini");
			
			if (!file.exists())
			{
				LOGGER.warning("[CustomBuffConfig] config/Custom/CustomBuffSystem.ini missing! System disabled.");
				ENABLE = false;
				return;
			}
			
			try (InputStream is = new FileInputStream(file))
			{
				Properties settings = new Properties();
				settings.load(is);
				
				ENABLE = Boolean.parseBoolean(settings.getProperty("Enable", "False"));
				ITEM_ID = Integer.parseInt(settings.getProperty("ItemId", "90000"));
				DROP_CHANCE = Double.parseDouble(settings.getProperty("DropChance", "1.0"));
				
				String[] skillsArray = settings.getProperty("Skills", "1086,1204").split(",");
				for (String s : skillsArray)
				{
					final int skillId = Integer.parseInt(s.trim());
					if (!SKILLS.contains(skillId)) // The medal window lists each buff once.
					{
						SKILLS.add(skillId);
					}
				}
			}
			
			LOGGER.info("[CustomBuffConfig] Loaded perfectly. Drop Chance: " + DROP_CHANCE + "%, Skills: " + SKILLS.size());
		}
		catch (Exception e)
		{
			LOGGER.warning("[CustomBuffConfig] Error loading settings: " + e.getMessage());
		}
	}
	
	private static void loadDescriptions()
	{
		DESCRIPTIONS.clear();
		
		final File file = new File("./config/Custom/CustomBuffDescriptions.ini");
		if (!file.exists())
		{
			LOGGER.warning("[CustomBuffConfig] config/Custom/CustomBuffDescriptions.ini missing! Buffs are listed without descriptions.");
			return;
		}
		
		try (InputStream is = new FileInputStream(file))
		{
			final Properties descriptions = new Properties();
			descriptions.load(is);
			for (String key : descriptions.stringPropertyNames())
			{
				try
				{
					DESCRIPTIONS.put(Integer.parseInt(key.trim()), descriptions.getProperty(key).trim());
				}
				catch (NumberFormatException e)
				{
					LOGGER.warning("[CustomBuffConfig] Skipping description with invalid skill id: " + key);
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.warning("[CustomBuffConfig] Error loading descriptions: " + e.getMessage());
		}
	}
}
