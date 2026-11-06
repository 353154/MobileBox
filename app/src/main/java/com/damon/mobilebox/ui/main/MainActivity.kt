package com.damon.mobilebox.ui.main

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.damon.mobilebox.core.device.DeviceProfileProvider
import com.damon.mobilebox.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 主界面入口
 * 使用 @AndroidEntryPoint 标记，由 Hilt 自动注入所需依赖
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // 核心注入：由 Hilt 依赖注入容器自动提供单例实例，无需 new
    @Inject
    lateinit var deviceProfileProvider: DeviceProfileProvider

    private var clickCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 通过被注入的 DeviceProfileProvider 获取设备硬件摘要
        val summary = deviceProfileProvider.getDeviceSummary()
        binding.tvDeviceInfo.text = "Hilt 注入就绪: $summary"

        binding.btnVerify.setOnClickListener {
            clickCount++
            val isReady = deviceProfileProvider.isHiltReady()
            Toast.makeText(
                this,
                "🎉 Hilt 依赖注入验证通过！状态正常: $isReady (点击: $clickCount)",
                Toast.LENGTH_SHORT
            ).show()
            binding.btnVerify.text = "Hilt 容器运转正常 (点击: $clickCount 次)"
        }
    }
}
