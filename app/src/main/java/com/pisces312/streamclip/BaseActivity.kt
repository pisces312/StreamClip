package com.pisces312.streamclip

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.pisces312.streamclip.util.LocaleHelper

abstract class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase))
    }
}
