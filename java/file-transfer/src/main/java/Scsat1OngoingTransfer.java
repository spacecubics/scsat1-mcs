package org.yamcs.cfdp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.stream.Collectors;

import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.cfdp.pdu.CfdpPacket;
import org.yamcs.cfdp.pdu.ConditionCode;
import org.yamcs.cfdp.pdu.DirectoryListingRequest;
import org.yamcs.cfdp.pdu.DirectoryListingResponse;
import org.yamcs.cfdp.pdu.MetadataPacket;
import org.yamcs.cfdp.pdu.OriginatingTransactionId;
import org.yamcs.cfdp.pdu.ProxyClosureRequest;
import org.yamcs.cfdp.pdu.ProxyPutRequest;
import org.yamcs.cfdp.pdu.ProxyPutResponse;
import org.yamcs.cfdp.pdu.ProxyTransmissionMode;
import org.yamcs.cfdp.pdu.ReservedMessageToUser;
import org.yamcs.cfdp.pdu.TLV;
import org.yamcs.events.EventProducer;
import org.yamcs.filetransfer.TransferMonitor;
import org.yamcs.logging.Log;
import org.yamcs.protobuf.TransferState;


public abstract class Scsat1OngoingTransfer implements CfdpFileTransfer {
    protected final CfdpTransactionId cfdpTransactionId;
    protected TransferState state;
    protected final ScheduledThreadPoolExecutor executor;
    protected final EventProducer eventProducer;
    protected boolean acknowledged = false;
    protected final Log log;
    protected final long startTime;
    protected final long wallclockStartTime;
    protected final long creationTime;
    protected String transferType = PredefinedTransferTypes.UNKNOWN.toString();

    final TransferMonitor monitor;
    final long destinationId;

    // transaction unique identifier (coming from a database)
    final long id;

    // accumulate the errors
    List<String> errors = new ArrayList<>();

    enum FaultHandlingAction {
        SUSPEND, CANCEL, ABANDON;

        public static FaultHandlingAction fromString(String str) {
            for (FaultHandlingAction a : values()) {
                if (a.name().equalsIgnoreCase(str)) {
                    return a;
                }
            }
            return null;
        }

        public static List<String> actions() {
            return Arrays.stream(FaultHandlingAction.values()).map(a -> a.name().toLowerCase())
                    .collect(Collectors.toList());
        }
    }

    final Map<ConditionCode, FaultHandlingAction> faultHandlerActions;
    public Scsat1OngoingTransfer(String yamcsInstance, long id, long creationTime, ScheduledThreadPoolExecutor executor,
            YConfiguration config, CfdpTransactionId cfdpTransactionId, long destinationId,
            EventProducer eventProducer, TransferMonitor monitor,
            Map<ConditionCode, FaultHandlingAction> faultHandlerActions) {
        this.cfdpTransactionId = cfdpTransactionId;
        this.state = TransferState.RUNNING;
        this.executor = executor;
        this.eventProducer = eventProducer;
        this.startTime = YamcsServer.getTimeService(yamcsInstance).getMissionTime();
        this.wallclockStartTime = System.currentTimeMillis();
        this.log = new Log(this.getClass(), yamcsInstance);
        this.id = id;
        this.destinationId = destinationId;
        this.creationTime = creationTime;
        if (monitor == null) {
            throw new NullPointerException("the monitor cannot be null");
        }
        this.monitor = monitor;
        this.faultHandlerActions = faultHandlerActions;
    }

    protected static String getTransferType(MetadataPacket metadata) {
        if (metadata == null) {
            return PredefinedTransferTypes.UNKNOWN.toString();
        }

        ArrayList<String> transferTypeInfo = new ArrayList<>();
        if (metadata.getFileLength() > 0) {
            transferTypeInfo.add(PredefinedTransferTypes.FILE_TRANSFER.toString());
        } else if (metadata.getHeader().isLargeFile()) {
            transferTypeInfo.add(PredefinedTransferTypes.LARGE_FILE_TRANSFER.toString());
        }

        List<TLV> options = metadata.getOptions();
        if (options == null || options.isEmpty()) {
            if (transferTypeInfo.isEmpty()) {
                return PredefinedTransferTypes.METADATA_ONLY_TRANSFER.toString();
            } else {
                return transferTypeInfo.get(0);
            }
        }

        boolean hasOriginatingTransactionId = false;
        int unknownOptions = 0;
        for (TLV option : options) {
            if (option instanceof ReservedMessageToUser) {
                if (option instanceof OriginatingTransactionId) {
                    hasOriginatingTransactionId = true;
                } else if (option instanceof ProxyPutRequest) {
                    ProxyPutRequest proxyPutRequest = (ProxyPutRequest) option;
                    transferTypeInfo.add(
                            PredefinedTransferTypes.DOWNLOAD_REQUEST + " (" + proxyPutRequest.getDestinationFileName()
                                    + " ⟵ " + proxyPutRequest.getSourceFileName() + ")");
                } else if (option instanceof ProxyPutResponse) {
                    ProxyPutResponse proxyPutResponse = (ProxyPutResponse) option;
                    transferTypeInfo.add(PredefinedTransferTypes.DOWNLOAD_REQUEST_RESPONSE + " ("
                            + proxyPutResponse.getConditionCode() + ", "
                            + (proxyPutResponse.isDataComplete() ? "Complete" : "Incomplete") + ", "
                            + proxyPutResponse.getFileStatus() + ")");
                } else if (option instanceof ProxyTransmissionMode || option instanceof ProxyClosureRequest) {
                    transferTypeInfo.add(option.toString());
                } else if (option instanceof DirectoryListingRequest) {
                    transferTypeInfo.add(PredefinedTransferTypes.DIRECTORY_LISTING_REQUEST + " ("
                            + ((DirectoryListingRequest) option).getDirectoryName() + ")");
                } else if (option instanceof DirectoryListingResponse) {
                    transferTypeInfo.add(PredefinedTransferTypes.DIRECTORY_LISTING_RESPONSE + " ("
                            + ((DirectoryListingResponse) option).getListingResponseCode() + ")");
                } else {
                    transferTypeInfo.add(((ReservedMessageToUser) option).getMessageType().toString());
                }
            } else {
                unknownOptions += 1;
            }
        }

        if (hasOriginatingTransactionId && transferTypeInfo.isEmpty()) {
            transferTypeInfo.add(PredefinedTransferTypes.ORIGINATING_TRANSACTION_ID_ONLY.toString());
        }

        if (unknownOptions > 0) {
            transferTypeInfo.add(unknownOptions + " " + PredefinedTransferTypes.UNKNOWN_METADATA_OPTION
                    + (unknownOptions > 1 ? "s" : ""));
        }

        return String.join(", ", transferTypeInfo);
    }

    public abstract void processPacket(CfdpPacket packet);

    protected void pushError(String err) {
        errors.add(err);
    }

    public final boolean isOngoing() {
        return state == TransferState.RUNNING || state == TransferState.PAUSED;
    }

    @Override
    public final TransferState getTransferState() {
        return state;
    }

    @Override
    public boolean cancellable() {
        return true;
    }

    @Override
    public boolean pausable() {
        return true;
    }

    public Scsat1OngoingTransfer pauseTransfer() {
        executor.submit(this::suspend);
        return this;
    }

    protected abstract void suspend();

    public Scsat1OngoingTransfer resumeTransfer() {
        executor.submit(this::resume);
        return this;
    }

    protected abstract void resume();

    public Scsat1OngoingTransfer cancelTransfer() {
        executor.submit(() -> {
            pushError("Cancel request received");
            cancel(ConditionCode.CANCEL_REQUEST_RECEIVED);
        });
        return this;
    }

    protected abstract void cancel(ConditionCode code);

    public Scsat1OngoingTransfer abandonTransfer(String reason) {
        executor.submit(() -> failTransfer(reason));
        return this;
    }

    @Override
    public CfdpTransactionId getTransactionId() {
        return cfdpTransactionId;
    }

    @Override
    public boolean isReliable() {
        return acknowledged;
    }

    @Override
    public long getStartTime() {
        return startTime;
    }

    protected void failTransfer(String failureReason) {
        pushError(failureReason);
        changeState(TransferState.FAILED);
    }

    protected void changeState(TransferState newState) {
        this.state = newState;
        monitor.stateChanged(this);
    }

    @Override
    public String getFailuredReason() {
        return errors.stream().collect(Collectors.joining("; "));
    }

    @Override
    public long getId() {
        return id;
    }

    protected FaultHandlingAction getFaultHandlingAction(ConditionCode code) {
        FaultHandlingAction action = faultHandlerActions.get(code);
        if (action == null) {
            return FaultHandlingAction.CANCEL;
        } else {
            return action;
        }
    }

    // Return the entity id of the Sender
    @Override
    public long getInitiatorEntityId() {
        return cfdpTransactionId.getInitiatorEntity();
    }

    // Return the entity id of the Receiver
    @Override
    public long getDestinationId() {
        return destinationId;
    }

    @Override
    public String getTransferType() {
        return transferType;
    }

    protected void sendInfoEvent(String type, String msg) {
        eventProducer.sendInfo(type, "TXID[" + cfdpTransactionId + "] " + msg);
    }

    protected void sendWarnEvent(String type, String msg) {
        eventProducer.sendWarning(type, "TXID[" + cfdpTransactionId + "] " + msg);
    }

    protected String toEventMsg(MetadataPacket packet) {
        return packet.toJson();
    }

    @Override
    public long getCreationTime() {
        return creationTime;
    }
}
