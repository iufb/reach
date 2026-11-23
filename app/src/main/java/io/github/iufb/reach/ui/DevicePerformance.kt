package io.github.iufb.reach.ui

import android.app.ActivityManager
import android.content.Context
import java.io.File

/**
 * Грубый класс производительности устройства — по образцу Telegram Android
 * (`SharedConfig.measureDevicePerformanceClass`): там же по нему выключают размытие в чате
 * на средних и слабых устройствах. Считается один раз за процесс.
 */
enum class DevicePerformance {
    Low, Average, High;

    companion object {
        @Volatile private var cached: DevicePerformance? = null

        fun of(context: Context): DevicePerformance = cached ?: measure(context).also { cached = it }

        private fun measure(context: Context): DevicePerformance {
            val activityManager = context.getSystemService(ActivityManager::class.java)
            val memory = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
            return classify(
                cpuCount = Runtime.getRuntime().availableProcessors(),
                averageMaxCpuMhz = averageMaxCpuMhz(),
                memoryClassMb = activityManager?.memoryClass ?: 0,
                totalRamMb = memory.totalMem / (1024 * 1024),
            )
        }

        /** Средняя максимальная частота ядер, МГц; `null` — не удалось прочитать. */
        private fun averageMaxCpuMhz(): Int? {
            val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { cpu ->
                runCatching {
                    File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toInt() / 1000
                }.getOrNull()
            }
            return if (freqs.isEmpty()) null else freqs.sum() / freqs.size
        }

        /**
         * Пороги Telegram Android. Средняя, а не максимальная частота: у «средних» SoC большие ядра
         * тоже 2+ ГГц, отличает их число и частота остальных (Helio G95: 2×2,05 + 6×2,0 → Average;
         * Snapdragon 8 Gen 1: 3,0 + 3×2,5 + 4×1,8 → High).
         */
        fun classify(cpuCount: Int, averageMaxCpuMhz: Int?, memoryClassMb: Int, totalRamMb: Long): DevicePerformance = when {
            cpuCount <= 2 || memoryClassMb <= 100 || totalRamMb in 1 until 2048 ||
                (cpuCount <= 4 && averageMaxCpuMhz != null && averageMaxCpuMhz <= 1250) -> Low
            cpuCount < 8 || memoryClassMb <= 160 || averageMaxCpuMhz == null || averageMaxCpuMhz <= 2055 -> Average
            else -> High
        }
    }
}
