package com.spdb.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "ana_field_mapping")
public class FieldMapping {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mapping_id")
    private Long mappingId;

    private String tranCode;
    private String serviceCode;
    private String stdFieldName;
    private String fieldCnName;
    private String sopFieldName;
    private String soapFieldName;
    private String bizjsonFieldName;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getMappingId() { return mappingId; }
    public void setMappingId(Long mappingId) { this.mappingId = mappingId; }
    public String getTranCode() { return tranCode; }
    public void setTranCode(String tranCode) { this.tranCode = tranCode; }
    public String getServiceCode() { return serviceCode; }
    public void setServiceCode(String serviceCode) { this.serviceCode = serviceCode; }
    public String getStdFieldName() { return stdFieldName; }
    public void setStdFieldName(String stdFieldName) { this.stdFieldName = stdFieldName; }
    public String getFieldCnName() { return fieldCnName; }
    public void setFieldCnName(String fieldCnName) { this.fieldCnName = fieldCnName; }
    public String getSopFieldName() { return sopFieldName; }
    public void setSopFieldName(String sopFieldName) { this.sopFieldName = sopFieldName; }
    public String getSoapFieldName() { return soapFieldName; }
    public void setSoapFieldName(String soapFieldName) { this.soapFieldName = soapFieldName; }
    public String getBizjsonFieldName() { return bizjsonFieldName; }
    public void setBizjsonFieldName(String bizjsonFieldName) { this.bizjsonFieldName = bizjsonFieldName; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
