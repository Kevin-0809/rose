package com.spdb.message;

import org.springframework.util.StringUtils;

public record MessageFlowLogEntryForm(
        String sourceIp,
        String transId,
        String txnCode,
        String messageType,
        Long txnTime,
        String globalSeqNo,
        String tranTellerNo,
        String requestMessage,
        Long responseTime,
        String returnCode,
        String returnMsg,
        String responseMessage
) {
    public String cleanSourceIp() {
        return clean(sourceIp);
    }

    public String cleanTransId() {
        return clean(transId);
    }

    public String cleanTxnCode() {
        return clean(txnCode);
    }

    public String cleanMessageType() {
        return clean(messageType);
    }

    public String cleanGlobalSeqNo() {
        return clean(globalSeqNo);
    }

    public String cleanTranTellerNo() {
        return clean(tranTellerNo);
    }

    public String cleanRequestMessage() {
        return clean(requestMessage);
    }

    public String cleanReturnCode() {
        return clean(returnCode);
    }

    public String cleanReturnMsg() {
        return clean(returnMsg);
    }

    public String cleanResponseMessage() {
        return clean(responseMessage);
    }

    public boolean hasRequiredRequestFields() {
        return StringUtils.hasText(sourceIp)
                && StringUtils.hasText(transId)
                && StringUtils.hasText(txnCode)
                && txnTime != null
                && StringUtils.hasText(requestMessage);
    }

    public boolean hasResponseFields() {
        return responseTime != null
                || StringUtils.hasText(returnCode)
                || StringUtils.hasText(returnMsg)
                || StringUtils.hasText(responseMessage);
    }

    public static MessageFlowLogEntryForm empty() {
        return new MessageFlowLogEntryForm("", "", "", "", null, "", "", "", null, "", "", "");
    }

    private static String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
