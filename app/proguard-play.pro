# R8 rules for the Google Play build only, on top of proguard-rules.pro (see the play build type).

# review-ktx's listener wrappers carry an annotation from play-services-basement that is not in
# the artifact; it only marks code for Google's own tooling, so its absence changes nothing.
-dontwarn com.google.android.gms.common.annotation.NoNullnessRewrite
