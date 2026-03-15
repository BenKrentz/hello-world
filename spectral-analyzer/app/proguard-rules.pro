# Add project specific ProGuard rules here.

# MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Keep data classes used for serialization
-keep class com.spectravision.analyzer.data.** { *; }
