package io.github.iufb.reach.ui

import io.github.iufb.reach.ui.DevicePerformance.Average
import io.github.iufb.reach.ui.DevicePerformance.High
import io.github.iufb.reach.ui.DevicePerformance.Low
import io.github.iufb.reach.ui.DevicePerformance.Companion.classify
import org.junit.Assert.assertEquals
import org.junit.Test

class DevicePerformanceTest {

    @Test fun helioG95IsAverage() {
        // Redmi Note 10S: 6×2,0 + 2×2,05 ГГц, memoryClass 256, 6 ГБ — размытие в чате удваивало кадр (#304)
        assertEquals(Average, classify(cpuCount = 8, averageMaxCpuMhz = 2012, memoryClassMb = 256, totalRamMb = 5592))
    }

    @Test fun flagshipIsHigh() {
        // Snapdragon 8 Gen 1: 3,0 + 3×2,5 + 4×1,8 ГГц
        assertEquals(High, classify(cpuCount = 8, averageMaxCpuMhz = 2212, memoryClassMb = 256, totalRamMb = 7680))
    }

    @Test fun fewCoresOrSmallHeapIsAverage() {
        assertEquals(Average, classify(cpuCount = 6, averageMaxCpuMhz = 2600, memoryClassMb = 256, totalRamMb = 6144))
        assertEquals(Average, classify(cpuCount = 8, averageMaxCpuMhz = 2600, memoryClassMb = 128, totalRamMb = 6144))
    }

    @Test fun unknownFrequencyIsNotHigh() {
        assertEquals(Average, classify(cpuCount = 8, averageMaxCpuMhz = null, memoryClassMb = 256, totalRamMb = 8192))
    }

    @Test fun weakDeviceIsLow() {
        assertEquals(Low, classify(cpuCount = 4, averageMaxCpuMhz = 1200, memoryClassMb = 192, totalRamMb = 3072))
        assertEquals(Low, classify(cpuCount = 8, averageMaxCpuMhz = 2000, memoryClassMb = 96, totalRamMb = 3072))
        assertEquals(Low, classify(cpuCount = 8, averageMaxCpuMhz = 2000, memoryClassMb = 192, totalRamMb = 1536))
    }
}
