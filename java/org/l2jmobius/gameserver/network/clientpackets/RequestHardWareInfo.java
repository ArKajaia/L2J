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
package org.l2jmobius.gameserver.network.clientpackets;

import org.l2jmobius.gameserver.network.holders.ClientHardwareInfoHolder;

/**
 * @author Mobius
 */
public class RequestHardWareInfo extends ClientPacket
{
	private String _macAddress;
	private int _windowsPlatformId;
	private int _windowsMajorVersion;
	private int _windowsMinorVersion;
	private int _windowsBuildNumber;
	private int _directxVersion;
	private int _directxRevision;
	private String _cpuName;
	private int _cpuSpeed;
	private int _cpuCoreCount;
	private int _vgaCount;
	private int _vgaPcxSpeed;
	private int _physMemorySlot1;
	private int _physMemorySlot2;
	private int _physMemorySlot3;
	private int _videoMemory;
	private int _vgaVersion;
	private String _vgaName;
	private String _vgaDriverVersion;
	
	@Override
	protected void readImpl()
	{
		_macAddress = readString();
		_windowsPlatformId = readInt();
		_windowsMajorVersion = readInt();
		_windowsMinorVersion = readInt();
		_windowsBuildNumber = readInt();
		_directxVersion = readInt();
		_directxRevision = readInt();
		readBytes(16);
		_cpuName = readString();
		_cpuSpeed = readInt();
		_cpuCoreCount = readByte();
		readInt();
		_vgaCount = readInt();
		_vgaPcxSpeed = readInt();
		_physMemorySlot1 = readInt();
		_physMemorySlot2 = readInt();
		_physMemorySlot3 = readInt();
		readByte();
		_videoMemory = readInt();
		readInt();
		_vgaVersion = readShort();
		_vgaName = readString();
		_vgaDriverVersion = readString();
	}
	
	@Override
	protected void runImpl()
	{
		getClient().setHardwareInfo(new ClientHardwareInfoHolder(_macAddress, _windowsPlatformId, _windowsMajorVersion, _windowsMinorVersion, _windowsBuildNumber, _directxVersion, _directxRevision, _cpuName, _cpuSpeed, _cpuCoreCount, _vgaCount, _vgaPcxSpeed, _physMemorySlot1, _physMemorySlot2, _physMemorySlot3, _videoMemory, _vgaVersion, _vgaName, _vgaDriverVersion));
	}
}
