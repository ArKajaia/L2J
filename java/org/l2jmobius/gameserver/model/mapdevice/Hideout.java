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
package org.l2jmobius.gameserver.model.mapdevice;

import org.l2jmobius.gameserver.model.actor.Npc;

/**
 * A player's hideout: a private instance with the Map Device in it. It lives while someone is inside or a map is open, and a while after (see {@link org.l2jmobius.gameserver.managers.MapDeviceManager}).
 * @author Mobius
 */
public class Hideout
{
	private final int _ownerId;
	private final String _ownerName;
	private final int _instanceId;
	private volatile Npc _device;
	private volatile MapRun _run;
	/** When the hideout was last seen empty with no map open, 0 while it is in use. */
	private volatile long _emptySince;

	public Hideout(int ownerId, String ownerName, int instanceId)
	{
		_ownerId = ownerId;
		_ownerName = ownerName;
		_instanceId = instanceId;
	}

	public int getOwnerId()
	{
		return _ownerId;
	}

	public String getOwnerName()
	{
		return _ownerName;
	}

	public int getInstanceId()
	{
		return _instanceId;
	}

	public Npc getDevice()
	{
		return _device;
	}

	public void setDevice(Npc device)
	{
		_device = device;
	}

	/**
	 * @return the map open at its device, {@code null} for none
	 */
	public MapRun getRun()
	{
		return _run;
	}

	public void setRun(MapRun run)
	{
		_run = run;
	}

	public long getEmptySince()
	{
		return _emptySince;
	}

	public void setEmptySince(long emptySince)
	{
		_emptySince = emptySince;
	}
}
