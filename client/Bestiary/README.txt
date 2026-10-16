Bestiary models - monster visuals from your own client (High Five)
===================================================================

The bestiary's profile pages show each monster as a 3D model you can turn
around, and the collection shows its portrait. Both come from your client:
extract_models.py takes the mesh and the textures of every monster of the
server out of it, and the bake page (//bestiary in game) makes the portraits.

What you need
-------------
- Your High Five client folder (the one with Animations, StaticMeshes,
  Textures and System).
- UE Viewer (umodel) by Gildor, free: https://www.gildor.org/en/projects/umodel
  It reads Lineage 2's encrypted .ukx/.usx/.utx packages. Unzip it anywhere.
- Python 3. Pillow is optional (pip install pillow): with it the textures are
  made smaller (--max-texture, 1024 by default), which saves a lot of space.
- Npcgrp.dat as text: open system/Npcgrp.dat with L2ClientDat (High Five) and
  save it as text (Npcgrp.txt), the same way as the other .dat files of this
  folder. It names the mesh and the textures of every npc id.

1. Extract
----------
From this folder:

  python extract_models.py --client "C:/Lineage II" --umodel "C:/tools/umodel/umodel.exe" --npcgrp Npcgrp.txt

Try a few monsters first with --only 20487,25013,29001. On Linux, umodel has
its own build; --umodel "wine umodel.exe" works too.

It writes into dist/game/data/html/custom/bestiary, where the game server
serves it from:
  models/<mesh>.glb        one file per mesh; monsters with the same mesh share it
  textures/<texture>.png   the skins (many monsters are a mesh with another skin)
  models/manifest.json     npc id -> mesh and skins
  extract_report.txt       what was taken, and what was not found

umodel's raw export goes to client/Bestiary/work; delete it when you are done.
A package already exported is skipped next time (--force exports it again).
Only the server's monster types are taken (--types); --all takes every npc.
A server npc with displayId gets that client npc's look.

2. Portraits
------------
Log in with a GM and type //bestiary. The bake page opens: it shows a few
monsters, lets you turn them so they face you (all models of the client face
the same way), then renders the portrait of every monster in your browser and
saves it on the server (data/html/custom/bestiary/<npc id>.webp). The
collection shows them at once; monsters not found yet show as the dark
silhouette of their portrait.

Without portraits, the collection keeps the drawn race portraits, and profile
pages still show the 3D model.

Size
----
The models and skins of all monsters take a few hundred MB (less with Pillow
and a smaller --max-texture), the portraits about 50 KB each. They are made
from your client: keep them out of git (.gitignore already does).

When something is missing
-------------------------
extract_report.txt lists the npcs without an Npcgrp row, the packages and
meshes umodel could not export, and the skins it could not find (those
monsters show their mesh with its own skin). umodel's output is in
client/Bestiary/work/umodel.log. If umodel does not recognize the packages,
run "umodel -taglist" for Lineage 2's game tag and pass it with
--umodel-args "-game=<tag>".

Turning the models
------------------
The models face the way the client has them. The bake page's preview shows a
few of them: turn them (15 degree steps) until they face you and save the
view. It is kept in models/view.json and used by the profile pages too.
