# Shizuku loads the UserService by its class name and Context constructor.
-keep class com.example.soul_knight_save_editor.unlock.ShizukuSaveService { public <init>(android.content.Context); *; }
-keep class com.example.soul_knight_save_editor.unlock.ShizukuOnlyProvider { *; }
-keep class com.example.soul_knight_save_editor.unlock.IShizukuSaveService$Stub { *; }

# app_process invokes this Java-only compatibility helper by its class and main method names.
-keep class com.example.soul_knight_save_editor.unlock.LegacySha256 { public static void main(java.lang.String[]); }
