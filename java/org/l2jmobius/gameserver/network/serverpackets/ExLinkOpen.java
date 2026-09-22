package org.l2jmobius.gameserver.network.serverpackets;

import org.l2jmobius.commons.network.WritableBuffer;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

public class ExLinkOpen extends ServerPacket
{
    String _link;
    public ExLinkOpen(String link)
    {
        this._link = link;
    }

    @Override
    public void writeImpl(GameClient client, WritableBuffer buffer)
    {
        ServerPackets.EX_LINK_OPEN.writeId(this, buffer);
        buffer.writeString(_link);
    }
}