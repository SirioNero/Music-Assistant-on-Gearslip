# Regole di R8 per Xonos. Le librerie (Media3, OkHttp, coroutine, Coil, Compose) portano le loro;
# qui c'è solo ciò che serve in più.

# Si riduce e si ottimizza, ma i nomi restano quelli veri: le eccezioni nei log si leggono come
# prima, senza bisogno del file di mapping (le release arrivano con Obtainium, non dal Play Store).
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Annotazioni usate solo durante la compilazione da Guava (arriva con Media3): a runtime non ci sono.
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
