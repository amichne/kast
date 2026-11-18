/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 * Extracted from the pre-fix Delay.kt in kotlinx.coroutines. Only the conversion is included.
 */
package kotlinx.coroutines

import kotlin.time.Duration

/**
 * Convert this duration to its millisecond value.
 * Positive durations are coerced at least `1`.
 */
internal fun Duration.toDelayMillis(): Long =
    if (this > Duration.ZERO) inWholeMilliseconds.coerceAtLeast(1) else 0
