Radar Zones - client radar protocol
===================================

Any server zone can be drawn on the client's radar (the minimap in the
corner): a translucent area with a border, under the party and target marks.
RadarZoneManager.publish(zone, style) shows a zone to everyone online and
unpublish(zoneId) takes it off. The first user is custom.RotatingHotZones,
which shows its active hotzones (RadarZones.ini, RadarZonesHotzones).

The client (L2Source, l2DecompileTest) keeps at most 128 zones. It draws
Cuboid and Cylinder zones; NPoly zones are decoded and checked, then
dropped until the client can draw polygons.

Framing
-------
EX2 server packets start with
  byte  0xFE
  short 0xFE
  int   sub id
(ServerPackets.writeId; EX2_LINK_OPEN is sub id 0x01.)

Types: byte = 1 byte, int = 4, little endian like every L2 packet.
Strings are UTF-16LE with a terminating 0.


0x05  Ex2RadarZoneShow  - show a zone, or replace the one with its id
----------------------------------------------------------------------
  int   zoneId            > 0, the key of Ex2RadarZoneHide
  byte  form              0 Cuboid, 1 Cylinder, 2 NPoly
  int   minZ              the zone is drawn while the player's Z is in
  int   maxZ              minZ..maxZ (minZ <= maxZ)

  Cuboid:
    int x1, int y1        x1 < x2
    int x2, int y2        y1 < y2
  Cylinder:
    int x, int y          center
    int radiusX           > 0 (the wire carries an ellipse;
    int radiusY           > 0  ZoneCylinder sends its radius twice)
  NPoly:
    int count             3 to 256
    count x { int x, int y }

  int   fill              ARGB, alpha in the top byte
  int   border            ARGB
  byte  flags             1 = pulsing border, 2 = hatched fill; no other bits
  str   name              at most 64 characters

Nothing may follow the name. A packet that breaks any rule above is
rejected whole and changes nothing.


0x06  Ex2RadarZoneHide  - take a zone off the radar
---------------------------------------------------
  int   zoneId            > 0; an id the client does not hold is ignored


0x07  Ex2RadarZoneClear  - take every zone off the radar
--------------------------------------------------------
  (no body)

On login the server sends Clear and then every published zone, so a relog
or a character switch never keeps stale zones.


0x19  Ex2RadarZoneProgress  - a sweep over a zone (a capture filling up)
-------------------------------------------------------------------------
  int   zoneId            a zone already shown with Ex2RadarZoneShow (0x05);
                          an id the client does not hold is ignored
  int   sweepArgb         the sweep's color, ARGB
  short fraction          0 to 1000 of the zone swept; 0 takes the sweep off
  short rate              per-mille per second, signed: until the next
                          packet the client draws fraction + rate * dt,
                          clamped to 0..1000
  byte  flags             1 = contested: the border pulses and the zone is
                          hatched; no other bits

The sweep belongs to its zone: Ex2RadarZoneHide takes it off with the zone.
A zone shown again (0x05, same id) may drop its sweep; the server sends the
sweep again right after re-showing a zone.
The first user is Conquest (client/TeamEvents/PROTOCOL.txt), which sends a
capture's progress toward a team in that team's color.
