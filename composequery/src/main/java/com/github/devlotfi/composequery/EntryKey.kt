package com.github.devlotfi.composequery

import kotlin.reflect.KProperty

interface EntryKey {
    fun getKey(key: String, property: KProperty<*>) = "$key/${property.name}"
}