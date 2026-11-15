# R8 rules for the release build (see app/build.gradle.kts).
#
# Continuum's own classes are kept whole and under their own names: nothing of the keyboard, its
# decoder or the settings pages is removed or renamed, and stack traces read as the source does.
# The size worth saving is in the libraries (AppCompat, Material, Preference), which bring their
# own rules. The settings menu also names a fragment class in XML (res/xml/prefs_root.xml,
# app:fragment), which must keep that name.
-keep class com.brownllama.continuum.** { *; }
