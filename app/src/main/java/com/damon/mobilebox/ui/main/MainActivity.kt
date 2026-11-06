package com.damon.mobilebox.ui.main

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.damon.mobilebox.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var touchCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val model = Build.MODEL
        val release = Build.VERSION.RELEASE
        val sdkInt = Build.VERSION.SDK_INT
        binding.tvDeviceInfo.text = "实机型号: $model (Android $release, API $sdkInt)"

        binding.btnVerify.setOnClickListener {
            touchCount++
            Toast.makeText(this, "触控响应正常！点击次数: $touchCount", Toast.LENGTH_SHORT).show()
            binding.btnVerify.text = "触控手势已响应 ($touchCount 次)"
        }
    }
}
