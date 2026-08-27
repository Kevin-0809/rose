package com.spdb.replay;

import java.util.List;

public record ReplayVolumeCheckResult(
        ReplayVolumeCheckBatch batch,
        List<ReplayVolumeCheckDetail> details,
        List<ReplayVolumeCleanupDetail> cleanupDetails) {
}
