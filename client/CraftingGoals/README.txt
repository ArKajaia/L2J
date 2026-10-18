Crafting Goals - client tracker protocol
=======================================

A crafting goal pinned with "Pin to Screen" (the .craft window or the
Community Board's Crafting Goals page) is drawn by the client's own
tracker screen. The server sends it over three EX2 packets and the client
unpins it with a bypass. Nothing else is needed from the client: item
names and icons come from its own ItemName-e.dat / Itemgrp.

Framing
-------
EX2 server packets start with
  byte  0xFE
  short 0xFE
  int   sub id
(ServerPackets.writeId; EX2_LINK_OPEN is sub id 0x01.)

Types: byte = 1 byte, int = 4, long = 8, little endian like every L2 packet.
Booleans are bytes (0 / 1).


0x02  Ex2CraftingGoalInfo  - the whole goal
-------------------------------------------
Sent when the goal is pinned, on login for every pinned goal, and when the
client asks again (_craftpin sync). Replace whatever the client had for this
recipeListId.

  int   recipeListId      the goal's id in every packet and bypass
  int   productId         item the goal makes
  int   countPerCraft     items one craft makes (shots make stacks)
  int   craftsLeft        crafts still to make (items = craftsLeft * countPerCraft)
  int   successRate       % of the recipe
  byte  recipeKnown       the player knows the recipe
  byte  dwarven           dwarven (1) or common (0) craft

  int   nodeCount         the recipe tree, depth first: a node's children follow it
  nodeCount x {
    int  parentIndex      index of the parent node, -1 = ingredient of the goal's recipe
    int  itemId
    long quantity         how many one craft of the parent's recipe uses
    int  outputPerCraft   items one craft of this node's own recipe makes,
                          0 = not broken down (not craftable, or below the breakdown depth)
  }

  byte  ready             every component is in the bag
  int   componentsDone    components the player has enough of
  int   componentCount    components of the recipe
  int   lineCount
  lineCount x LINE

LINE (also used by 0x03):
  int   itemId
  byte  material          0 = component (ingredient of the goal's recipe),
                          1 = material (needed to craft a component still missing)
  byte  craftable         a recipe makes this item
  long  needed            in total, for every craft still to make
  long  have              what counts for this line

Lines are keyed by (itemId, material): an item can be both a component and
a material, as two lines. These are the numbers the server's HTML tracker
shows, so the client never has to count the inventory itself:
- worn items don't count;
- a material only counts what the player carries beyond the components
  (Leather kept for the recipe is not counted again for Crafted Leather);
- material lines only exist for the parts still missing: once a craftable
  component is complete, its materials are gone.

Matching the tree with the lines: a node with parentIndex -1 goes with the
component line of its item; any deeper node goes with the material line of
its item. A material line adds up every node of that item (two parts that
both need Leather share one Leather line). A node without a line is not
needed any more (its component is already in the bag).


0x03  Ex2CraftingGoalUpdate  - what changed
-------------------------------------------
Sent about 300 ms after the inventory changes (one packet per batch:
a pickup, a sweep, a trade, a craft), and when the crafts left change.
Only when something changed. It is a delta against the lines the client
holds from the last 0x02 / 0x03 of this goal.

  int   recipeListId
  int   craftsLeft
  byte  ready
  int   componentsDone
  int   componentCount

  int   changedCount      lines that are new, or whose needed/have/craftable changed
  changedCount x LINE     -> insert or replace by (itemId, material)

  int   removedCount      lines that are gone
  removedCount x {
    int  itemId
    byte material
  }                       -> delete by (itemId, material)

Packets reach the client in order, and the server never sends an update
for a goal before its 0x02. An update for a recipeListId the client does
not hold (its screen was not loaded yet): send "_craftpin sync <id>".


0x04  Ex2CraftingGoalRemove  - off the screen
---------------------------------------------
  int   recipeListId
  byte  reason            0 = unpinned, 1 = abandoned (or the player has no
                          such goal), 2 = completed (the last craft is made)

A completed goal already gets the quest finish sound, an on-screen message
and a Large Firework from the server.


Client -> server
----------------
RequestBypassToServer (0x23) with one of:
  _craftpin unpin <recipeListId>   the tracker's close button   -> 0x04 reason 0
  _craftpin pin <recipeListId>     pin a goal again             -> 0x02
  _craftpin sync <recipeListId>    send me this goal again      -> 0x02, or 0x04 if not pinned / gone
  _craftpin craft <itemId>         open the manufacture window  -> RecipeItemMakeInfo (0xDD) for the
                                   surest recipe the player knows for that item, or a chat message
                                   when the player knows none
They open no window. The HTML pages use ".craft pin/unpin" bypasses of
their own; the client screen should use _craftpin.

Server side: CraftingGoalManager (pin, onPinBypass, check -> diff),
network/serverpackets/Ex2CraftingGoal*.java.
