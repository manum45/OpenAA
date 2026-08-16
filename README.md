This app is an experiment for communicating with an Android Auto headunit.

Much of it is based on AACS (an alternative android auto server running on a Raspberry Pi):
https://github.com/tomasz-grobelny/AACS

Testing was greatly helped by OpenAuto (a headunit replacement running on a Raspberry Pi):
https://github.com/f1xpl/openauto

## Goal
Determine whether it is feasible to implement a (FOSS) replacement for the Android Auto app.

# About Android Auto (AA)
- the Android Auto app (= server) runs on a phone. It creates a virtual display in Android, opens the apps on it, and streams video and audio to a headunit. 
- the headunit (= accessory) is the computer and display in the car. It doesn't have to run android itself
- the server and headunit communicate via USB or Wifi, using the Android Open Accessory protocol and google's protobufs.
- Note: "Android Auto" is different from "Android Automotive", the latter one referring to a headunit running full Android.

# Results
- Communication with the headunit USB is feasible, but a system API has to be used (ACTION_USB_ACCESSORY_HANDSHAKE).

  A non-system app is not allowed to use the official definition directly, but the string can be defined and the Intent can be received and handled by this experimental app, which is not a system app (tested on GrapheneOs with Android 13).
  
  This behavior can probably be changed by Android without any notice.
  
  References:
  - official definition: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/android/hardware/usb/UsbManager.java#200
  - experimental implementation: `app/src/main/java/io/github/manum45/openaa/UsbHandler.kt:BroadcastReceiver`
- Streaming arbitrary video and audio to the headunit is feasible.
- Phone screen can be mirrored to the headunit, system audio as a whole can be output on the headunit.
- **Blocker**: can't open apps on a virtual display.

  My understanding of this issue, but it's difficult for me to find comprehensive docu on this:
  - non-system apps can only create "untrusted" virtual displays
  - non-system apps are only allwed to open other apps on a virtual display, if the virtual display is trusted

  Related permissions:
  - ADD_TRUSTED_DISPLAY, part of COMPANION_DEVICE_APP_STREAMING role?

    https://android.googlesource.com/platform/packages/modules/Permission/+/7816a6a2bfed3e4727f6b6f767a3e0f825dce880/PermissionController/res/xml/roles.xml#1070

    https://source.android.com/docs/core/permissions/app-streaming

    https://source.android.com/docs/core/permissions/android-roles


    "Requirements: The app is a system app. Only OEMs can grant this role to the app."
  
    https://rtx.meta.security/reference/2024/07/03/Android-system-apps.html
    
    https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/permission/Permissions.md#permission-protection-level

    In my understanding this means: the user would have to compile and sign the OS and the app, or the app would have to be provided by the OS distributor.


# Software Design

Note: this project is an experiment, much of it was vibe coded.

## Communication with Headunit
- UsbHandler receives the intents on USB device connection, instantiates an AccessoryCommunicator
- AccessoryCommunicator instantiates a UsbStreamer for reading/writing to/from USB, and a MessageHandler that decodes the messages
- MessageHandler instantiates an SslHandler and uses it for encryption/decryption


# License

GPLv3


# References

## Existing AA related implementations
CAUTION: I have not validated that these are trustworthy.
- https://github.com/opencardev/crankshaft -> headunit on RPi (image using openauto)
- https://github.com/f1xpl/openauto -> headunit
    - has newer forks:
        - https://github.com/openDsh/openauto
        - https://github.com/opencardev/openauto
- https://github.com/borconi/headunit, https://github.com/rishie/headunit-borconi, https://play.google.com/store/apps/details?id=gb.xxy.hr&hl=en, https://github.com/andreknieriem/headunit-revived -> headunit on tablet
- https://github.com/tomasz-grobelny/AACS -> proxy server between headunit and phone
    - https://github.com/viktorgino/libheadunit

- https://inceptive.ru/projects/s2a, https://aamirror.com/ (https://github.com/slashmax/AAMirror), Wheelpal -> screen mirroring to AA headunit
- AAStore, AAAD: alternative App stores for apps that support Android Auto
- Samsung Auto: proprietary Android Auto alternative, currently exclusively in China?

- https://www.okcaros.com/en -> server for Apple Carplay on android, requires to be flashed via recovery
	
## Related documentation by Google/Android
- https://source.android.com/docs/automotive -> automotive OS (standalone headunit) and android auto (server on phone)
- https://developer.android.com/training/cars/apps#key-terms-concepts
- https://www.youtube.com/watch?v=KNKGM4ss5Sc

## Third party docu
- https://rajasoftwarelabs.com/blog/developing-apps-for-android-auto
- https://stackoverflow.com/questions/77541511/how-to-start-an-android-application-on-a-virtual-screen
- https://android-bytes-by-esper.captivate.fm/episode/ep12-android-auto
- https://milek7.pl/.stuff/galdocs/readme.md
- https://technolinchpin.wordpress.com/2015/10/23/cracking-android-open-accessory-trasactions/
