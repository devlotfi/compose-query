package com.devlotfi.composequery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

@Composable
fun rememberCounter(initialValue: Int = 0) {
    val (count, setCount) = remember { mutableStateOf(initialValue) }
}