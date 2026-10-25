# The Cast framework instantiates its options provider by the class name the manifest's
# meta-data gives, which R8 cannot see.
-keep class io.putdotio.android.playback.MobileCastOptionsProvider { <init>(); }
