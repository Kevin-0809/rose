package com.spdb.migration;
import java.time.LocalDate;
import java.util.Objects;
public class MigrationTranCodeCommandForm {
 public static final int DEFAULT_LOOKBACK_DAYS=30, DEFAULT_SAMPLE_SIZE=100, DEFAULT_PARALLELISM=16, ALL_LOOKBACK_DAYS=10_000;
 public static final String COLLECTION_TYPE_QUERY="QUERY", COLLECTION_TYPE_ACCOUNT="ACCOUNT";
 private String tranCodes, remark; private int sampleSize, lookbackDays, parallelism; private String collectionType = ""; private LocalDate baseDate;
 public MigrationTranCodeCommandForm(String tranCodes,int sampleSize,int lookbackDays,int parallelism,String remark){this.tranCodes=tranCodes;this.sampleSize=sampleSize;this.lookbackDays=lookbackDays;this.parallelism=parallelism;this.remark=remark;}
 public static MigrationTranCodeCommandForm empty(){return new MigrationTranCodeCommandForm("",DEFAULT_SAMPLE_SIZE,DEFAULT_LOOKBACK_DAYS,DEFAULT_PARALLELISM,"");}
 public String tranCodes(){return tranCodes;} public int sampleSize(){return sampleSize;} public int lookbackDays(){return lookbackDays;} public int parallelism(){return parallelism;} public String remark(){return remark;} public String collectionType(){return collectionType;} public LocalDate baseDate(){return baseDate;}
 public void setTranCodes(String v){tranCodes=v;} public void setSampleSize(int v){sampleSize=v;} public void setLookbackDays(int v){lookbackDays=v;} public void setParallelism(int v){parallelism=v;} public void setRemark(String v){remark=v;}
 public void setCollectionType(String v){collectionType=v;} public void setBaseDate(LocalDate v){baseDate=v;}
 @Override public boolean equals(Object o){if(!(o instanceof MigrationTranCodeCommandForm f))return false;return sampleSize==f.sampleSize&&lookbackDays==f.lookbackDays&&parallelism==f.parallelism&&Objects.equals(collectionType,f.collectionType)&&Objects.equals(tranCodes,f.tranCodes)&&Objects.equals(remark,f.remark)&&Objects.equals(baseDate,f.baseDate);}
 @Override public int hashCode(){return Objects.hash(tranCodes,sampleSize,lookbackDays,parallelism,remark,collectionType,baseDate);}
}
