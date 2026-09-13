package org.l2jmobius.gameserver.model.actor.stat;

import java.util.LinkedList;
import java.util.Queue;

public class PlayerStatsTracker
{
	private static class DamageRecord
	{
		final long timestamp;
		final double damage;
		
		DamageRecord(long timestamp, double damage)
		{
			this.timestamp = timestamp;
			this.damage = damage;
		}
	}
	
	private static class ExpRecord
	{
		final long timestamp;
		final long exp;
		
		ExpRecord(long timestamp, long exp)
		{
			this.timestamp = timestamp;
			this.exp = exp;
		}
	}
	
	private final Queue<DamageRecord> _damageHistory = new LinkedList<>();
	private final Queue<ExpRecord> _expHistory = new LinkedList<>();
	
	// 1. Record Damage Dealt
	public synchronized void addDamage(double damage)
	{
		long now = System.currentTimeMillis();
		_damageHistory.add(new DamageRecord(now, damage));
		cleanOldData(now);
	}
	
	// 2. Record EXP Gained
	public synchronized void addExp(long exp)
	{
		long now = System.currentTimeMillis();
		_expHistory.add(new ExpRecord(now, exp));
		cleanOldData(now);
	}
	
	// Remove data older than the calculation windows
	private void cleanOldData(long now)
	{
		// Clean damage older than 10 seconds (10,000 ms)
		while (!_damageHistory.isEmpty() && ((now - _damageHistory.peek().timestamp) > 10000))
		{
			_damageHistory.poll();
		}
		
		// Clean EXP older than 60 minutes (3,600,000 ms)
		while (!_expHistory.isEmpty() && ((now - _expHistory.peek().timestamp) > 3600000))
		{
			_expHistory.poll();
		}
	}
	
	// Calculate Average DPS over the last 10 seconds
	public synchronized double getDps()
	{
		long now = System.currentTimeMillis();
		cleanOldData(now);
		
		if (_damageHistory.isEmpty())
		{
			return 0.0;
		}
		
		double totalDamage = 0;
		for (DamageRecord record : _damageHistory)
		{
			totalDamage += record.damage;
		}
		
		// Always divide by 10.0 seconds window for accurate average rate
		return totalDamage / 10.0;
	}
	
	// Calculate Projected EXP per Hour based on the last 60 minutes
	public synchronized long getExpPerHour()
	{
		long now = System.currentTimeMillis();
		cleanOldData(now);
		
		if (_expHistory.isEmpty())
		{
			return 0;
		}
		
		long totalExp = 0;
		long oldestTimestamp = now;
		
		for (ExpRecord record : _expHistory)
		{
			totalExp += record.exp;
			if (record.timestamp < oldestTimestamp)
			{
				oldestTimestamp = record.timestamp;
			}
		}
		
		// Calculate how long player has been gaining exp (in hours)
		double durationInHours = (now - oldestTimestamp) / 3600000.0;
		
		// If player started combat less than 1 minute ago, scale against elapsed time to prevent extreme spikes
		if (durationInHours < (1.0 / 60.0))
		{
			durationInHours = 1.0 / 60.0;
		}
		
		return (long) (totalExp / durationInHours);
	}
}