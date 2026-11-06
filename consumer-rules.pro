# Keep the public entry points stable for consumers that enable R8.
-keep class com.vibecoderspace.chrootengine.ChrootRuntime { *; }
-keep class com.vibecoderspace.chrootengine.ChrootTerminal { *; }
-keep interface com.vibecoderspace.chrootengine.RootfsProfile { *; }
-keep class com.vibecoderspace.chrootengine.DefaultProfiles { *; }
