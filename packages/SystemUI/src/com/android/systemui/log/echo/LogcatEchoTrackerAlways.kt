/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.log.echo

import com.android.systemui.log.LogcatEchoTracker
import com.android.systemui.log.core.LogLevel

/**
 * The buffer and all of its tags will be logged to logcat at all times.
 *
 * This can be used for buffers that are important and should appear in bugreports in logcat
 * directly.
 */
object LogcatEchoTrackerAlways : LogcatEchoTracker {
    override fun isBufferLoggable(bufferName: String, level: LogLevel): Boolean = true

    override fun isTagLoggable(tagName: String, level: LogLevel): Boolean = true
}
