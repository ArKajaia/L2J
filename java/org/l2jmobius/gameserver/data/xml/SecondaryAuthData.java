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
package org.l2jmobius.gameserver.data.xml;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

import org.w3c.dom.Document;
import org.w3c.dom.Node;

import org.l2jmobius.commons.util.IXmlReader;

/**
 * Secondary Auth data.
 * @author NosBit
 */
public class SecondaryAuthData implements IXmlReader
{
	private final Set<String> _forbiddenPasswords = new HashSet<>();
	private boolean _enabled = false;
	private int _maxAttempts = 5;
	private int _banTime = 480;
	private String _recoveryLink = "";
	
	protected SecondaryAuthData()
	{
		load();
	}
	
	@Override
	public synchronized void load()
	{
		_forbiddenPasswords.clear();
		parseFile(new File("config/SecondaryAuth.xml"));
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + _forbiddenPasswords.size() + " forbidden passwords.");
	}
	
	@Override
	public void parseDocument(Document document, File file)
	{
		try
		{
			for (Node node = document.getFirstChild(); node != null; node = node.getNextSibling())
			{
				if ("list".equalsIgnoreCase(node.getNodeName()))
				{
					for (Node list_node = node.getFirstChild(); list_node != null; list_node = list_node.getNextSibling())
					{
						if ("enabled".equalsIgnoreCase(list_node.getNodeName()))
						{
							_enabled = Boolean.parseBoolean(list_node.getTextContent());
						}
						else if ("maxAttempts".equalsIgnoreCase(list_node.getNodeName()))
						{
							_maxAttempts = Integer.parseInt(list_node.getTextContent());
						}
						else if ("banTime".equalsIgnoreCase(list_node.getNodeName()))
						{
							_banTime = Integer.parseInt(list_node.getTextContent());
						}
						else if ("recoveryLink".equalsIgnoreCase(list_node.getNodeName()))
						{
							_recoveryLink = list_node.getTextContent();
						}
						else if ("forbiddenPasswords".equalsIgnoreCase(list_node.getNodeName()))
						{
							for (Node forbiddenPasswords_node = list_node.getFirstChild(); forbiddenPasswords_node != null; forbiddenPasswords_node = forbiddenPasswords_node.getNextSibling())
							{
								if ("password".equalsIgnoreCase(forbiddenPasswords_node.getNodeName()))
								{
									_forbiddenPasswords.add(forbiddenPasswords_node.getTextContent());
								}
							}
						}
					}
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "Failed to load secondary auth data from xml.", e);
		}
	}
	
	/**
	 * Checks if the secondary authentication feature is enabled.
	 * @return {@code true} if secondary authentication is enabled; {@code false} otherwise
	 */
	public boolean isEnabled()
	{
		return _enabled;
	}
	
	/**
	 * Retrieves the maximum number of allowed authentication attempts.
	 * @return the maximum number of failed attempts allowed before a ban occurs
	 */
	public int getMaxAttempts()
	{
		return _maxAttempts;
	}
	
	/**
	 * Retrieves the duration of the ban time in minutes.
	 * @return the duration, in minutes, that a user is banned after exceeding the maximum failed attempts
	 */
	public int getBanTime()
	{
		return _banTime;
	}
	
	/**
	 * Retrieves the recovery link for password reset or account recovery.
	 * @return the URL string for the recovery link
	 */
	public String getRecoveryLink()
	{
		return _recoveryLink;
	}
	
	/**
	 * Retrieves the set of forbidden passwords that users are not allowed to use.
	 * @return a set of strings containing all forbidden passwords
	 */
	public Set<String> getForbiddenPasswords()
	{
		return _forbiddenPasswords;
	}
	
	/**
	 * Checks if a given password is in the list of forbidden passwords.
	 * @param password the password to check
	 * @return {@code true} if the password is forbidden; {@code false} otherwise
	 */
	public boolean isForbiddenPassword(String password)
	{
		return _forbiddenPasswords.contains(password);
	}
	
	public static SecondaryAuthData getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final SecondaryAuthData INSTANCE = new SecondaryAuthData();
	}
}
