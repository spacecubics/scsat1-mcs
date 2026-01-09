package org.yamcs.cfdp;

import org.yamcs.filetransfer.FileTransfer;

// A Cancel.request is a primitive that requests a certain transaction canceled
public class Scsat1CancelRequest extends CfdpRequest {
    private Scsat1OngoingTransfer transfer;

    public Scsat1CancelRequest(FileTransfer transfer) {
        super(CfdpRequestType.CANCEL);
        if (!(transfer instanceof Scsat1OngoingTransfer)) {
            throw new IllegalArgumentException();
        }
        this.transfer = (Scsat1OngoingTransfer) transfer;
    }

    public Scsat1OngoingTransfer getTransfer() {
        return this.transfer;
    }
}
