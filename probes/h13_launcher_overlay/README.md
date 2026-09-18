# H13 launcher overlay

This runtime resource overlay changes only the `com.bozhou.launcher` resource
`array/before_app_names`. It removes `app_1_1` (`com.cmccpoc`) while retaining
`app_1_2` (`com.bozhou.interphone`) and `app_1_3` (the duty camera entry).

The overlay does not replace or modify `SimpleHome.apk`.

Rollback on the device:

```powershell
adb shell cmd overlay disable --user 0 net.elfradio.h13.launcher.overlay
adb uninstall net.elfradio.h13.launcher.overlay
```
