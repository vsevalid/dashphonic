# FYT calls these interfaces across Binder, outside the app's call graph.
-keep class com.syu.ipc.** { *; }
-keep class app.sidewave.FytIpcBridge$* extends com.syu.ipc.IModuleCallback$Stub { *; }
