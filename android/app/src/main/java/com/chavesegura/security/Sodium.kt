package com.chavesegura.security

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid

/** Instância única da libsodium (carrega a biblioteca nativa na primeira chamada). */
object Sodium {
    val lazy: LazySodiumAndroid by lazy { LazySodiumAndroid(SodiumAndroid()) }
}
