package com.spdb.sampling.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public class IssueGrouper {
    private final int maxSamplesPerGroup;
    private final Map<String, MutableGroup> groups = new LinkedHashMap<>();

    public IssueGrouper(int maxSamplesPerGroup) {
        this.maxSamplesPerGroup = maxSamplesPerGroup;
    }

    public List<SampleGroupDraft> group(List<IssueCandidate> candidates) {
        candidates.stream()
                .sorted(Comparator.comparing(IssueCandidate::sourceKey))
                .forEach(this::add);
        return groups();
    }

    public void add(IssueCandidate candidate) {
        groups.computeIfAbsent(groupKey(candidate), key -> new MutableGroup(key, candidate))
                .add(candidate, maxSamplesPerGroup);
    }

    public List<SampleGroupDraft> groups() {
        return groups.values().stream()
                .map(MutableGroup::toDraft)
                .toList();
    }

    private String groupKey(IssueCandidate candidate) {
        return candidate.origCdate()
                + "|" + candidate.sampleType()
                + "|" + candidate.tranCode()
                + "|" + candidate.serviceCode()
                + "|" + candidate.compResult()
                + "|" + candidate.semanticSignatureHash();
    }

    private static class MutableGroup {
        private final String groupKey;
        private final IssueCandidate first;
        private final List<SampleDetailDraft> details = new ArrayList<>();
        private final TreeSet<String> messageTypes = new TreeSet<>();
        private final TreeSet<String> semanticFieldNames = new TreeSet<>();
        private long affectedTranCount;
        private long affectedFieldCount;

        private MutableGroup(String groupKey, IssueCandidate first) {
            this.groupKey = groupKey;
            this.first = first;
        }

        private void add(IssueCandidate candidate, int maxSamplesPerGroup) {
            affectedTranCount++;
            affectedFieldCount += candidate.fields().size();
            if (candidate.messageType() != null && !candidate.messageType().isBlank()) {
                messageTypes.add(candidate.messageType());
            }
            for (SampleDetailFieldDraft field : candidate.fields()) {
                semanticFieldNames.add(field.stdFieldName());
            }
            if (details.size() < maxSamplesPerGroup) {
                details.add(new SampleDetailDraft(
                        candidate.sourceKey(),
                        candidate.sourceKey().mesgSeq(),
                        candidate.messageType(),
                        candidate.destTrcd(),
                        candidate.compResult(),
                        candidate.configStatus(),
                        joined(fields -> fields.sopFieldName(), candidate.fields()),
                        joined(fields -> fields.soapFieldName(), candidate.fields()),
                        joined(fields -> fields.bizjsonFieldName(), candidate.fields()),
                        joined(fields -> fields.fieldCnName(), candidate.fields()),
                        candidate.origErrorCode(),
                        candidate.origErrorDesc(),
                        candidate.destErrorCode(),
                        candidate.destErrorDesc(),
                        candidate.fields()
                ));
            }
        }

        private String joined(java.util.function.Function<SampleDetailFieldDraft, String> extractor,
                              List<SampleDetailFieldDraft> fields) {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (SampleDetailFieldDraft field : fields) {
                String value = extractor.apply(field);
                if (value != null && !value.isBlank()) {
                    values.add(value);
                }
            }
            return String.join(",", values);
        }

        private SampleGroupDraft toDraft() {
            return new SampleGroupDraft(
                    first.origCdate(),
                    first.sampleType(),
                    groupKey,
                    first.semanticSignatureHash(),
                    first.tranCode(),
                    first.tranName(),
                    first.moduleName(),
                    first.owner(),
                    first.serviceCode(),
                    first.compResult(),
                    first.configStatus(),
                    first.mappingStatus(),
                    first.semanticSignature(),
                    first.semanticSignatureHash(),
                    String.join(",", semanticFieldNames),
                    String.join(",", messageTypes),
                    affectedTranCount,
                    affectedFieldCount,
                    List.copyOf(details)
            );
        }
    }
}
