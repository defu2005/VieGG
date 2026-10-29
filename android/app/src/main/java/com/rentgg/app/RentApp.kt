package com.rentgg.app

import android.app.Application
import com.rentgg.app.data.ApiClient
import com.rentgg.app.data.TokenStore

class RentApp : Application() {
    lateinit var api: ApiClient
        private set
    lateinit var tokens: TokenStore
        private set

    override fun onCreate() {
        super.onCreate()
        tokens = TokenStore(this)
        api = ApiClient(tokens)
    }
}
