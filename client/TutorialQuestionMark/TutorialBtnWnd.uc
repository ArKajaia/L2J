class TutorialBtnWnd extends UICommonAPI;

// The blinking tutorial question mark (server packet ShowTutorialMark).
// Clicking btnTutorial sends RequestTutorialQuestionMark from native code.
var bool	m_bArriveTutorialQuestion;
var int		m_iEffectNumber;

function OnRegisterEvent()
{
	RegisterEvent( EV_ArriveNewTutorialQuestion );
}

function OnLoad()
{
	if(CREATE_ON_DEMAND==0)
		OnRegisterEvent();
}

function OnEvent( int Event_ID, string param )
{
	ParseInt(param, "QuestionID", m_iEffectNumber);

	switch( Event_ID )
	{
	case EV_ArriveNewTutorialQuestion :
		ShowWindowWithFocus("TutorialBtnWnd");
		class'UIAPI_WINDOW'.static.ShowWindow("TutorialBtnWnd.btnTutorial");
		class'UIAPI_EFFECTBUTTON'.static.BeginEffect("TutorialBtnWnd.btnTutorial", m_iEffectNumber);

		m_bArriveTutorialQuestion = true;
		break;
	}
}

function OnEnterState( name a_PreStateName )
{
	// A mark that came while talking to an NPC is shown again afterwards, like QuestBtnWnd.
	if( m_bArriveTutorialQuestion && a_PreStateName == 'NpcZoomCameraState')
	{
		ShowWindowWithFocus("TutorialBtnWnd");
		class'UIAPI_WINDOW'.static.ShowWindow("TutorialBtnWnd.btnTutorial");
		class'UIAPI_EFFECTBUTTON'.static.BeginEffect("TutorialBtnWnd.btnTutorial", m_iEffectNumber);
	}

	// clear
	m_bArriveTutorialQuestion	= false;
	m_iEffectNumber				= 0;
}

function OnClickButton(string strID)
{
	switch(strID)
	{
	case "btnTutorial" :
		HideWindow("TutorialBtnWnd");
		m_bArriveTutorialQuestion = false;
		break;
	}
}
defaultproperties
{
}
