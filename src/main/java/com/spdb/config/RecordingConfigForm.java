package com.spdb.config;

public class RecordingConfigForm {
    private Long id;
    private String txnCode;
    private Integer txnSwitch = 1;
    private Integer recordRatio = 100;
    private String description;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTxnCode() { return txnCode; }
    public void setTxnCode(String txnCode) { this.txnCode = txnCode; }
    public Integer getTxnSwitch() { return txnSwitch; }
    public void setTxnSwitch(Integer txnSwitch) { this.txnSwitch = txnSwitch; }
    public Integer getRecordRatio() { return recordRatio; }
    public void setRecordRatio(Integer recordRatio) { this.recordRatio = recordRatio; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
