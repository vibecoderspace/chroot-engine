# Keep the public entry points stable for consumers that enable R8.
-keep class com.vibecoderspace.chrootengine.ChrootEngine { *; }
-keep interface com.vibecoderspace.chrootengine.ChrootRuntime { *; }
-keep interface com.vibecoderspace.chrootengine.RootfsProfile { *; }
-keep class com.vibecoderspace.chrootengine.process.ProcessSpec { *; }
