package com.maxrave.data.parser

internal fun formatTrackDuration(seconds: Int): String =
    (seconds / 60).toString().padStart(2, '0') + ":" + (seconds % 60).toString().padStart(2, '0')
