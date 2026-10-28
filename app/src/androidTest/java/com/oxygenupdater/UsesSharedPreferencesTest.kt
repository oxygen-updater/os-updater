package com.oxygenupdater

import android.content.Context
import android.content.SharedPreferences
import com.oxygenupdater.extensions.get
import org.junit.Before

open class UsesSharedPreferencesTest : ComposeBaseTest() {

    private lateinit var sharedPreferences: SharedPreferences

    @Before
    fun setup() {
        sharedPreferences = activity.getPreferences(Context.MODE_PRIVATE)
    }

    protected fun getPrefStr(key: String, default: String) = sharedPreferences[key, default]
    protected fun getPrefBool(key: String, default: Boolean) = sharedPreferences[key, default]
    protected fun getPrefInt(key: String, default: Int) = sharedPreferences[key, default]
    protected fun persistBool(key: String, value: Boolean) = trackCallback("persistBool: $key=$value")
    protected fun persistInt(key: String, value: Int) = trackCallback("persistInt: $key=$value")
}
