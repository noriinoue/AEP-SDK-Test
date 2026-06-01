using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;

[InitializeOnLoad]
public static class StartupSceneOpener
{
    private const string StartupScenePath = "Assets/First Scene.unity";
    private const string SessionKey = "AEP_SDK_TEST_STARTUP_SCENE_OPENED";

    static StartupSceneOpener()
    {
        EditorApplication.delayCall += OpenStartupSceneIfNeeded;
    }

    private static void OpenStartupSceneIfNeeded()
    {
        if (Application.isBatchMode || EditorApplication.isPlayingOrWillChangePlaymode)
        {
            return;
        }

        if (SessionState.GetBool(SessionKey, false))
        {
            return;
        }
        SessionState.SetBool(SessionKey, true);

        if (AssetDatabase.LoadAssetAtPath<SceneAsset>(StartupScenePath) == null)
        {
            Debug.LogWarning($"Startup scene not found: {StartupScenePath}");
            return;
        }

        Scene activeScene = SceneManager.GetActiveScene();
        if (activeScene.path == StartupScenePath)
        {
            return;
        }

        if (!EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
        {
            return;
        }

        EditorSceneManager.OpenScene(StartupScenePath, OpenSceneMode.Single);
    }
}
