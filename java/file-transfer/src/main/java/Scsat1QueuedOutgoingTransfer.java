package org.yamcs.cfdp;

import org.yamcs.buckets.Bucket;

// Represents a queued outgoing transfer with a custom start offset
public class Scsat1QueuedOutgoingTransfer extends QueuedCfdpOutgoingTransfer {
    private Integer startOffset;

    public Scsat1QueuedOutgoingTransfer(
        long initiatorEntityId, long id, long creationTime, PutRequest putRequest,
        Bucket bucket, Integer customPduSize, Integer customPduDelay, Integer startOffset
    ) {
        super(initiatorEntityId, id, creationTime, putRequest, bucket, customPduSize, customPduDelay);
        this.startOffset = startOffset;
    }

    // Returns the start offset for the transfer
    public Integer getStartOffset() {
        return startOffset;
    }
}
