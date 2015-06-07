Android Melodeon
================
Android melodeon emulator for tablets. The app is available on [F-Droid](https://f-droid.org/repository/browse/?fdid=org.billthefarmer.melodeon)
and [here](https://github.com/billthefarmer/melodeon/releases).

![](https://github.com/billthefarmer/billthefarmer.github.io/raw/master/images/Melodeon.png)
&nbsp;
![](https://github.com/billthefarmer/billthefarmer.github.io/raw/master/images/Melodeon-organetto.png)

This project uses the Sonivox EAS library, libsonivox.so, which is included in Android, but not documented or accessible using the standard Android NDK toolkit. In order to use this library you need to:

  * Get and install the Android NDK toolkit.
  * Get the documentation and include files from https://github.com/android/platform_external_sonivox.
  * Get a copy of the libsonivox.so library from an Android device or an emulator (in system/lib) and put it in the ~/jni/libs/<arch> folder.
  * Adjust the ~jni/Application.mk to build the architecture(s) you want.

The windows NDK toolkit doesn't appear to use anything other than standard windows shell commands and it's own tools, so you don't need Cygwin, despite what the docs say.
