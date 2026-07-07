package com.spdb.sample;

public interface TransactionSuccessStatConsumer {
    void accept(TransactionSuccessStatRow row);
}
