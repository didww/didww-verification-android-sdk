# kotlinx.serialization keep rules.
#
# STATUS: PRECAUTIONARY, NOT CURRENTLY LOAD-BEARING. Read this before trusting it.
#
# These rules protect the generated `$$serializer` classes and the synthetic
# `Companion.serializer()` accessors that the kotlinx.serialization compiler plugin emits
# for `@Serializable` types. R8 strips them because nothing references them by name — they
# are reached reflectively at the KSerializer boundary — and the failure is invisible in
# every debug build because R8 does not run there.
#
# THE SDK CURRENTLY DECLARES NO `@Serializable` CLASS AT ALL. The wire model is read
# through the JsonElement tree API (`Json.parseToJsonElement`, `buildJsonObject`) rather
# than through generated serializers, because the per-channel response block is keyed by
# the delivery method's own name — a dynamic key that a generated serializer cannot
# express — and because tree parsing tolerates unknown fields by construction.
#
# So today these rules match nothing. That is stated plainly rather than left implied: a
# keep rule that silently protects nothing looks exactly like a keep rule that is working,
# and this file would otherwise read as an active mitigation for a crash that cannot
# presently occur.
#
# They are kept because the moment anyone adds `@Serializable` to a wire type, the crash
# becomes real, release-only, and extremely hard to attribute. Adding the rules then is
# something someone has to remember; having them already is free.

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# The generated serializer for every @Serializable class in this SDK.
-keep,includedescriptorclasses class com.didww.android.sdk.verification.**$$serializer { *; }

# The Companion field the plugin looks up to reach serializer().
-keepclassmembers class com.didww.android.sdk.verification.** {
    *** Companion;
}

# Companion.serializer() itself.
-keepclasseswithmembers class com.didww.android.sdk.verification.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# @Serializable objects are reached through INSTANCE.serializer().
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
