package com.spdb.message;

public interface AnaMessageSendTaskLauncher {
    void launch(String target, int concurrency, int rangeMinutes, int retries, int timeout);
}
