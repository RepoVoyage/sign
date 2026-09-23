package com.repovoyage.sign.p1

import com.repovoyage.sign.SignApp
import com.repovoyage.sign.MainActivity
import com.repovoyage.sign.service.CameraBridgeForegroundService
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 工程骨架验收（plan.md §1 P1）。
 * Manifest 合并与双 flavor 构建由 assemble 任务验证（构建通过即合并通过），
 * 这里覆盖代码侧：入口类存在、FGS 为 Service 且是纯声明壳。
 */
class P1ScaffoldTest {

    @Test
    fun `应用入口与主界面存在`() {
        // 直接类引用：编译期即验证存在
        SignApp()
    }

    @Test
    fun `前台服务存在且继承 Service`() {
        assertTrue(
            android.app.Service::class.java.isAssignableFrom(
                CameraBridgeForegroundService::class.java
            )
        )
    }

    @Test
    fun `onBind 返回 null（纯声明壳，无绑定接口）`() {
        val service = CameraBridgeForegroundService()
        assertNull(service.onBind(null))
    }

    @Test
    fun `MainActivity 为 Activity 入口`() {
        // P7 UI 迁移：AppCompatActivity → ComponentActivity（Compose 宿主，§3.2）
        assertTrue(
            android.app.Activity::class.java.isAssignableFrom(MainActivity::class.java)
        )
    }
}
