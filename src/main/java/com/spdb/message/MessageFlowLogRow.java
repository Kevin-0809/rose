package com.spdb.message;

public record MessageFlowLogRow(
        String sourceIp,
        String transId,
        String txnCode,
        Long txnTime,
        String requestMessageType,
        String globalSeqNo,
        String tranTellerNo,
        Long responseTime,
        String responseMessageType,
        String returnCode,
        String returnMsg,
        String requestMessage,
        String responseMessage
) {
}
