package org.l2jmobius.gameserver.config.custom;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

public class CustomBuffConfig
{
	private static final Logger LOGGER = Logger.getLogger(CustomBuffConfig.class.getName());
	
	public static boolean ENABLE;
	public static int ITEM_ID;
	public static double DROP_CHANCE;
	public static List<Integer> SKILLS = new ArrayList<>();
	
	public static void load()
	{
		SKILLS.clear();
		
		try
		{
			File file = new File("config/custom/CustomBuffSystem.ini");
			
			if (!file.exists())
			{
				LOGGER.warning("[CustomBuffConfig] config/custom/CustomBuffSystem.ini missing! System disabled.");
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
					SKILLS.add(Integer.parseInt(s.trim()));
				}
			}
			
			LOGGER.info("[CustomBuffConfig] Loaded perfectly. Drop Chance: " + DROP_CHANCE + "%, Skills: " + SKILLS.size());
		}
		catch (Exception e)
		{
			LOGGER.warning("[CustomBuffConfig] Error loading settings: " + e.getMessage());
		}
	}
}