package com.chuckiehelper.mobile.nativeui

import kotlinx.coroutines.Job

/** A cancelled screen/lifecycle wait must also cancel its view-model read request. */
internal suspend fun awaitUiRead(job: Job) {
    try { job.join() } finally { if (!job.isCompleted) job.cancel() }
}
