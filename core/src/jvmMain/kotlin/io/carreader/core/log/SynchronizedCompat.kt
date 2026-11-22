package io.carreader.core.log

actual inline fun <T> synchronizedCompat(lock: Any, block: () -> T): T = synchronized(lock, block)
