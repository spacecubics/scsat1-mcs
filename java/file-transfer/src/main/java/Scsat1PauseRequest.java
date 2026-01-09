package org.yamcs.cfdp;

import org.yamcs.filetransfer.FileTransfer;

// A Pause.request is a primitive that requests a certain transaction to be paused
public class Scsat1PauseRequest extends CfdpRequest {
    private Scsat1OngoingTransfer transfer;

    public Scsat1PauseRequest(FileTransfer transfer) {
        super(CfdpRequestType.PAUSE);
        if (!(transfer instanceof Scsat1OngoingTransfer)) {
            throw new IllegalArgumentException();
        }
        this.transfer = (Scsat1OngoingTransfer) transfer;
    }

    public Scsat1OngoingTransfer getTransfer() {
        return this.transfer;
    }
}
