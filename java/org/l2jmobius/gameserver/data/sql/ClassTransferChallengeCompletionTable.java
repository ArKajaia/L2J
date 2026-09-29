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
package org.l2jmobius.gameserver.data.sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.classtransfer.TransferStage;

/**
 * Persists cleared Alternative Class Transfer Challenges ({@code class_transfer_challenge_completion}). A completion grants eligibility for one Class Master transfer: it only counts while the character is still the class it cleared the trial as, on the same class slot, and it is consumed by the
 * transfer itself.
 * @author Mobius
 */
public class ClassTransferChallengeCompletionTable
{
	private static final Logger LOGGER = Logger.getLogger(ClassTransferChallengeCompletionTable.class.getName());

	private static final String SELECT = "SELECT from_class_id FROM class_transfer_challenge_completion WHERE char_id=? AND class_index=? AND stage=?";
	private static final String REPLACE = "REPLACE INTO class_transfer_challenge_completion (char_id, class_index, stage, from_class_id, challenge_id, completed_at) VALUES (?, ?, ?, ?, ?, ?)";
	private static final String DELETE_STAGE = "DELETE FROM class_transfer_challenge_completion WHERE char_id=? AND class_index=? AND stage=?";
	private static final String DELETE_CLASS_INDEX = "DELETE FROM class_transfer_challenge_completion WHERE char_id=? AND class_index=?";
	private static final String DELETE_CHARACTER = "DELETE FROM class_transfer_challenge_completion WHERE char_id=?";

	protected ClassTransferChallengeCompletionTable()
	{
	}

	/**
	 * @param player the character
	 * @param stage the transfer stage
	 * @return {@code true} if the character cleared this stage's trial as its current class, on its current class slot, and has not transferred since
	 */
	public boolean hasValidCompletion(Player player, TransferStage stage)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(SELECT))
		{
			ps.setInt(1, player.getObjectId());
			ps.setInt(2, player.getClassIndex());
			ps.setInt(3, stage.getTier());
			try (ResultSet rs = ps.executeQuery())
			{
				return rs.next() && (rs.getInt("from_class_id") == player.getPlayerClass().getId());
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not read completion of " + player.getName() + " for " + stage + ": " + e.getMessage(), e);
			return false;
		}
	}

	/**
	 * Records a cleared trial for the character's current class and class slot, replacing any earlier record of the same stage.
	 * @param player the character
	 * @param stage the transfer stage
	 * @param challengeId the cleared challenge
	 * @return {@code true} if the record was written
	 */
	public boolean storeCompletion(Player player, TransferStage stage, String challengeId)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(REPLACE))
		{
			ps.setInt(1, player.getObjectId());
			ps.setInt(2, player.getClassIndex());
			ps.setInt(3, stage.getTier());
			ps.setInt(4, player.getPlayerClass().getId());
			ps.setString(5, challengeId);
			ps.setLong(6, System.currentTimeMillis());
			ps.execute();
			return true;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not store completion of " + player.getName() + " for " + stage + ": " + e.getMessage(), e);
			return false;
		}
	}

	/**
	 * Deletes the stage's record for the character's current class slot. Called once the transfer it unlocked has been performed, and by the admin reset.
	 * @param player the character
	 * @param stage the transfer stage
	 * @return {@code true} if a record was deleted
	 */
	public boolean consume(Player player, TransferStage stage)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(DELETE_STAGE))
		{
			ps.setInt(1, player.getObjectId());
			ps.setInt(2, player.getClassIndex());
			ps.setInt(3, stage.getTier());
			return ps.executeUpdate() > 0;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not consume completion of " + player.getName() + " for " + stage + ": " + e.getMessage(), e);
			return false;
		}
	}

	/**
	 * Deletes every record of a class slot, used when a subclass is replaced or removed.
	 * @param charId the character object id
	 * @param classIndex the class slot
	 */
	public void deleteClassIndex(int charId, int classIndex)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(DELETE_CLASS_INDEX))
		{
			ps.setInt(1, charId);
			ps.setInt(2, classIndex);
			ps.execute();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not delete completions of " + charId + " class index " + classIndex + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Deletes every record of a character, used on character deletion.
	 * @param con an open connection
	 * @param charId the character object id
	 */
	public void deleteCharacter(Connection con, int charId)
	{
		try (PreparedStatement ps = con.prepareStatement(DELETE_CHARACTER))
		{
			ps.setInt(1, charId);
			ps.execute();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not delete completions of " + charId + ": " + e.getMessage(), e);
		}
	}

	public static ClassTransferChallengeCompletionTable getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final ClassTransferChallengeCompletionTable INSTANCE = new ClassTransferChallengeCompletionTable();
	}
}
