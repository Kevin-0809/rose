package com.spdb.migration;

public record MigrationSourceRow(
        String sourceIp,
        String transId,
        String requestTxnCode,
        Long txnTime,
        String requestMessageType,
        byte[] requestMessage,
        String globalSeqNo,
        String tranTellerNo,
        String responseTxnCode,
        Long responseTime,
        String responseMessageType,
        byte[] responseMessage,
        String returnCode,
        String returnMsg
) {
}
