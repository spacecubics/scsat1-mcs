package org.yamcs.cfdp;

import org.yamcs.filetransfer.FileTransfer;

// A Resume.request is a primitive that requests a certain paused transaction to be resumed
public class Scsat1ResumeRequest extends CfdpRequest {
    private Scsat1OngoingTransfer transfer;

    public Scsat1ResumeRequest(FileTransfer transfer) {
        super(CfdpRequestType.RESUME);
        if (!(transfer instanceof Scsat1OngoingTransfer)) {
            throw new IllegalArgumentException();
        }
        this.transfer = (Scsat1OngoingTransfer) transfer;
    }

    public Scsat1OngoingTransfer getTransfer() {
        return this.transfer;
    }
}
