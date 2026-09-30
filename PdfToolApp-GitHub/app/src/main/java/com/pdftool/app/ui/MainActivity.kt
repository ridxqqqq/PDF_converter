package com.pdftool.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.GridView
import com.pdftool.app.R
import com.pdftool.app.model.ConversionType

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val grid = findViewById<GridView>(R.id.grid)
        val adapter = FeatureGridAdapter(ConversionType.values().toList()) { type ->
            startActivity(
                Intent(this, ConversionActivity::class.java)
                    .putExtra("type", type.name)
            )
        }
        grid.adapter = adapter
    }
}
