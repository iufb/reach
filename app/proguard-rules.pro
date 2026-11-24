# R8 для release и releaseTestdc (ADR-0014).
# Библиотеки (Hilt, kotlinx-serialization, Firebase, media3, Coil, Compose) приносят свои consumer-правила.

# TDLib: нативный код создаёт объекты TdApi по имени класса и пишет/читает поля по именам,
# а Client вызывается из JNI. Ничего из пакета нельзя ни переименовать, ни выбросить.
-keep class org.drinkless.tdlib.** { *; }

# rlottie: JNI-мост с native-методами и обратными вызовами из C++.
-keep class io.github.iufb.reach.core.rlottie.** { *; }

# Любые native-методы: имя в JNI-таблице должно совпасть с именем в классе.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Читаемые стектрейсы: номера строк сохраняем, mapping.txt кладём рядом с релизом.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
