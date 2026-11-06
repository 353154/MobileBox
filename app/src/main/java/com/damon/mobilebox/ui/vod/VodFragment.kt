package com.damon.mobilebox.ui.vod

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.damon.mobilebox.databinding.FragmentVodBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class VodFragment : Fragment() {

    private var _binding: FragmentVodBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVodBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.chipSourceSelect.setOnClickListener {
            Toast.makeText(requireContext(), "触控切换数据源 (Step 2.2 将接入真实片库列表)", Toast.LENGTH_SHORT).show()
        }
        binding.ivQuickSearch.setOnClickListener {
            Toast.makeText(requireContext(), "快捷跳转至全网搜索", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
