# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile


# Mantiene intacta la clase NativeBridge y sus métodos nativos
# para que el puente JNI (C++/Rust) pueda enlazarlos en tiempo de ejecución.
-keepclasseswithmembernames class jhonatan.s.rag_engine.NativeBridge {
    native <methods>;
}

# Mantiene el nombre de la clase para el System.loadLibrary
-keep class jhonatan.s.rag_engine.NativeBridge { *; }