Tutorial question mark - client fix
===================================

The tutorial (Q00255_Tutorial), the Adventurer's Guide, Dynamic Quests,
Hidden Quests and Crafting Goals raise the blinking question mark with
ShowTutorialMark (0xA7, int markId). Clicking it sends
RequestTutorialQuestionMark (0x87, int markId) back.

Why it never showed
-------------------
The client hands the packet to its interface as EV_ArriveNewTutorialQuestion
(1510), and interface/Classes/TutorialBtnWnd.uc is the window that shows the
question mark for it. In the client's interface sources (L2Compiler) that
script had its handler commented out: no event was registered, so the window
was never shown. The tutorial sound still played because the server sends it
in a separate packet (PlaySound). The server side was never the problem.

The fix
-------
TutorialBtnWnd.uc in this folder registers the event again and shows the
blinking button the same way QuestBtnWnd.uc and MailBtnWnd.uc do. It also
shows a mark again if it arrived while the player was talking to an NPC,
like QuestBtnWnd does. Clicking hides the button. The client sends the
click to the server from native code, so the script doesn't send it.

Install
-------
1. Copy TutorialBtnWnd.uc over interface/Classes/TutorialBtnWnd.uc in the
   L2Compiler repository.
2. Run System\Make_Interface.bat and check for "produced 17 of 17 packages".
3. Put the new System\Interface.u in the client's System folder.

To check it: an Adventurer's Guide lesson that hasn't been read raises a
mark at login, and so does a Crafting Goal resource you pick up. With
DisableTutorial = False in Player.ini, the retail tutorial raises its own
marks for new characters too.
