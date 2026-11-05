[app]
# (str) Title of your application
title = dc- Discord Manager

# (str) Package name
package.name = dc

# (str) Package domain (needed for android packaging)
package.domain = org.hubg50we.dc

# (str) Source code where the main.py lives
source.dir = .

# (list) Source files to include (let empty to include all the files)
source.include_exts = py,png,jpg,kv,atlas,html,css,js,json,txt,example

# (list) List of inclusions using pattern matching
source.include_patterns = assets/*,web/*

# (list) Source files to exclude (let empty to not exclude anything)
source.exclude_dirs = tests, bin, .git, .github

# (str) Application versioning
version = 1.1.6

# (list) Application requirements
# Comma separated e.g. requirements = sqlite3,kivy
requirements = python3==3.11.5,hostpython3==3.11.5,websockets,python-dotenv

# (str) Presplash of the application
#presplash.filename = %(source.dir)s/assets/presplash.png

# (str) Icon of the application
#icon.filename = %(source.dir)s/assets/icon.png

# (str) Supported orientation (one of landscape, sensorLandscape, portrait or all)
orientation = portrait

# (bool) Indicate if the application should be fullscreen to not
fullscreen = 0

# (bool) Automatically accept Android SDK licenses
android.accept_sdk_license = True

# (list) Permissions
android.permissions = INTERNET,ACCESS_NETWORK_STATE,WAKE_LOCK,FOREGROUND_SERVICE

# (int) Target Android API, should be as high as possible.
android.api = 33

# (int) Minimum API your APK / AAB will support.
android.minapi = 24

# (int) Android NDK API to use. This is the minimum API your app will support, it should usually match android.minapi.
android.ndk_api = 24

# (bool) Use --private data storage (True) or --dir public storage (False)
android.private_storage = True

# (list) The Android architectures to build for, choices: armeabi-v7a, arm64-v8a, x86, x86_64
android.archs = arm64-v8a, armeabi-v7a

# (bool) enables Android auto backup feature (Android API >=23)
android.allow_backup = True

# (str) The format used to package the app for release mode (aab or apk)
android.release_artifact = apk

# (str) The format used to package the app for debug mode (apk)
android.debug_artifact = apk

# (str) Bootstrap to use for android
# Options: sdl2, webview, service_library
p4a.bootstrap = webview

# (int) Port to open in webview bootstrap
p4a.port = 5000

# (str) Android entry point
p4a.hook = 

[buildozer]
# (int) Log level (0 = error only, 1 = info, 2 = debug with command output)
log_level = 2

# (int) Display warning if buildozer is run as root (0 = False, 1 = True)
warn_on_root = 1
