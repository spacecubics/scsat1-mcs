package org.yamcs.cfdp;

import static org.yamcs.cfdp.CfdpService.ETYPE_TRANSFER_FINISHED;
import static org.yamcs.cfdp.CfdpService.ETYPE_TRANSFER_RESUMED;
import static org.yamcs.cfdp.CfdpService.ETYPE_TRANSFER_SUSPENDED;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.net.InetAddress;
import java.io.IOException;
import org.yamcs.YConfiguration;
import org.yamcs.Processor;
import org.yamcs.YamcsServer;
import org.yamcs.ErrorInCommand;
import org.yamcs.YamcsException;
import org.yamcs.security.User;
import org.yamcs.events.EventProducer;
import org.yamcs.mdb.Mdb;
import org.yamcs.mdb.MdbFactory;
import org.yamcs.xtce.MetaCommand;
import org.yamcs.xtce.Argument;
import org.yamcs.buckets.Bucket;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.commanding.CommandingManager;
import org.yamcs.protobuf.TransferDirection;
import org.yamcs.protobuf.TransferState;
import org.yamcs.cfdp.pdu.CfdpPacket;
import org.yamcs.cfdp.pdu.ConditionCode;
import org.yamcs.filetransfer.TransferMonitor;


public class Scsat1OutgoingTransfer extends Scsat1OngoingTransfer {
    private static final int MAX_DATA_BYTES = 200;
    private static final String OPEN_CMD_NAME = "/UPLOAD_OPEN_CMD";
    private static final String DATA_CMD_NAME = "/UPLOAD_DATA_CMD";
    private static final String CLOSE_CMD_NAME = "/UPLOAD_CLOSE_CMD";

    private enum OutTxState {
        START,
        SENDING_DATA,
        CANCELING,
        COMPLETED
    }

    private final Bucket bucket;
    private final String cmdPath;
    private final Mdb mdb ;
    private final CommandingManager cmdManager;
    private final User systemUser;
    private final int sleepBetweenPdus;
    private final int startOffset;
    private final int sessionId;

    private PutRequest request;
    private ScheduledFuture<?> pduSendingSchedule;
    private OutTxState outTxState;
    private long transferred;
    private String origin;
    private boolean suspended = false;
    private int offset;
    private int remainSize;


    public Scsat1OutgoingTransfer(String yamcsInstance, long initiatorEntityId, long id, long creationTime,
            ScheduledThreadPoolExecutor executor,
            PutRequest request, YConfiguration config, Bucket bucket,
            Integer customPduSize, Integer customPduDelay,
            EventProducer eventProducer,
            TransferMonitor monitor, Map<ConditionCode, FaultHandlingAction> faultHandlerActions, Integer startOffset) {
        super(yamcsInstance, id, creationTime,
                executor, config, makeTransactionId(initiatorEntityId, config, id),
                request.getDestinationCfdpEntityId(),
                eventProducer, monitor, faultHandlerActions);
        // Preparing to send commands
        String srcName = getNameById(config.getConfigList("remoteEntities"), (int)request.getDestinationCfdpEntityId()).toUpperCase();
        Processor processor = YamcsServer.getServer().getInstance(yamcsInstance).getFirstProcessor();
        cmdPath = String.format("/%s/%s", yamcsInstance.toUpperCase(), srcName);
        mdb = MdbFactory.getInstance(processor.getInstance());
        cmdManager = processor.getCommandingManager();
        systemUser = YamcsServer.getServer().getSecurityStore().getSystemUser();

        this.request = request;
        this.bucket = bucket;
        this.sessionId = (int) id;
        outTxState = OutTxState.START;
        this.sleepBetweenPdus = customPduDelay != null && customPduDelay > 0 ? customPduDelay
                : config.getInt("sleepBetweenPdus", 1000);
        this.startOffset = startOffset != null && startOffset > 0 ? startOffset
                : config.getInt("startOffset", 0);
    }

    public static String getNameById(List<YConfiguration> list, int targetId) {
        for (YConfiguration yc : list) {
            if (yc.containsKey("id") && yc.getInt("id") == targetId) {
                return yc.getString("name");
            }
        }
        return null;
    }

    private static CfdpTransactionId makeTransactionId(long sourceId, YConfiguration config, long id) {
        int seqNrSize = 2;
        long seqNum = id & ((1l << seqNrSize * 8) - 1);
        return new CfdpTransactionId(sourceId, seqNum);
    }

    // Start the transfer
    public void start() {
        if (startOffset > request.getFileLength()) {
            changeState(TransferState.FAILED);
            outTxState = OutTxState.COMPLETED;
            log.error(
                "Start offset is invalid: startOffset=" + startOffset +
                ", fileLength=" + request.getFileLength());
            throw new IllegalArgumentException(
                "Start offset must not be greater than file length: startOffset=" + startOffset +
                ", fileLength=" + request.getFileLength()
            );
        }
        pduSendingSchedule = executor.scheduleAtFixedRate(this::sendPDU, 0, sleepBetweenPdus, TimeUnit.MILLISECONDS);
    }

    // Handles PDU sending based on the current transfer state
    private void sendPDU() {
        if (suspended) {
            return;
        }
        try {
            switch (outTxState) {
                case START:
                    origin = InetAddress.getLocalHost().getHostName();
                    transferType = PredefinedTransferTypes.FILE_TRANSFER.toString();
                    uploadOpenCmd();
                    offset = startOffset;
                    remainSize = request.getFileLength() - offset;
                    transferred = 0;
                    this.outTxState = OutTxState.SENDING_DATA;
                    monitor.stateChanged(this);
                    break;

                case SENDING_DATA:
                    uploadDataCmd();
                    monitor.stateChanged(this);
                    break;

                case COMPLETED:
                    pduSendingSchedule.cancel(true);
                    break;

                default:
                    throw new IllegalStateException("unknown/illegal state");
            }
        }  catch (Exception e) {
                log.error("Error when sending command: ", e);
                throw new RuntimeException(e);
        }
    }

    // Sends the command to start uploading a file
    private void uploadOpenCmd()  throws IOException, ErrorInCommand, YamcsException, InterruptedException, ExecutionException {
        String fileName = request.getDestinationFileName();
        String commandName = cmdPath + OPEN_CMD_NAME;
        sendingCommand(commandName, List.of(sessionId, fileName));
    }

    // Sends a chunk of file data for uploading
    private void uploadDataCmd()  throws IOException, ErrorInCommand, YamcsException, InterruptedException, ExecutionException {
        int sendFileSize = 0; // uint32, little
        byte[] fileDataChunk; // binary, 1600bits
        String commandName = cmdPath + DATA_CMD_NAME;
        if(remainSize == 0){
            if (offset % MAX_DATA_BYTES == 0){
                //  All data has been sent cleanly (no leftover bytes)
                offset = 0;
                sendFileSize = 0;
                fileDataChunk = new byte[0];
                sendingCommand(commandName, List.of(sessionId, offset, sendFileSize, fileDataChunk));
            }
            // Finalize the upload session
            uploadCloseCmd(ConditionCode.NO_ERROR);
        } else {
            if (remainSize < MAX_DATA_BYTES){
                sendFileSize = remainSize;
            } else {
                sendFileSize = MAX_DATA_BYTES;
            }
            // Read a chunk of file data
            fileDataChunk = Arrays.copyOfRange(request.getFileData(), offset, offset + sendFileSize);
            sendingCommand(commandName, List.of(sessionId, offset, sendFileSize, fileDataChunk));
            // Update offset and remaining size
            offset = offset + sendFileSize;
            remainSize = remainSize - sendFileSize;
            transferred = transferred + sendFileSize;
        }
    }

    // Sends the command to finalize the upload session
    private void uploadCloseCmd(ConditionCode conditionCode) throws IOException, ErrorInCommand, YamcsException, InterruptedException, ExecutionException {
        String commandName = cmdPath + CLOSE_CMD_NAME;
        sendingCommand(commandName, List.of(sessionId));
        complete(conditionCode);
    }

    // Sends a command with specified arguments
    private void sendingCommand(String commandName, List<Object> argumentValues) throws IOException, ErrorInCommand, YamcsException, InterruptedException, ExecutionException{
        MetaCommand mc = mdb.getMetaCommand(commandName);
        Map<String, Object> argAssignmentList = assignValues(mc.getArgumentList(), argumentValues);
        PreparedCommand preparedCommand = cmdManager.buildCommand(mc, argAssignmentList, origin, 0, systemUser);
        cmdManager.sendCommand(systemUser, preparedCommand);
    }

    // Assigns values in order to arguments that do not have a default value
    private Map<String, Object> assignValues(List<Argument> argumentList, List<Object> values) {
        Map<String, Object> argValueMap = new LinkedHashMap<>();
        int valueIndex = 0;
        for (Argument arg : argumentList) {
            Object initialValue = arg.getArgumentType().getInitialValue();
            if (initialValue != null) {
                argValueMap.put(arg.getName(), initialValue);
            } else {
                if (valueIndex >= values.size()) {
                    throw new IllegalArgumentException("Insufficient values for non-default argument: " + arg.getName());
                }
                argValueMap.put(arg.getName(), values.get(valueIndex));
                valueIndex++;
            }
        }
        return argValueMap;
    }


    @Override
    public void processPacket(CfdpPacket packet) {
        executor.submit(() -> doProcessPacket(packet));
    }

    private void doProcessPacket(CfdpPacket packet) {
        if (state == TransferState.COMPLETED || state == TransferState.FAILED) {
            return;
        }
    }


    @Override
    protected void suspend() {
        if (outTxState == OutTxState.COMPLETED) {
            log.info("TXID{} transfer finished, suspend ignored", cfdpTransactionId);
            return;
        }
        sendInfoEvent(ETYPE_TRANSFER_SUSPENDED, "transfer suspended");
        log.info("TXID{} suspending transfer", cfdpTransactionId);
        pduSendingSchedule.cancel(true);
        suspended = true;
        changeState(TransferState.PAUSED);
    }


    @Override
    protected void resume() {
        if (!suspended) {
            log.info("TXID{} resume called while not suspended, ignoring", cfdpTransactionId);
            return;
        }
        if (outTxState == OutTxState.COMPLETED) {
            // it is possible the transfer has finished while being suspended
            log.info("TXID{} transfer finished, suspend ignored", cfdpTransactionId);
            return;
        }
        log.info("TXID{} resuming transfer", cfdpTransactionId);
        sendInfoEvent(ETYPE_TRANSFER_RESUMED, "transfer resumed");
        pduSendingSchedule = executor.scheduleAtFixedRate(this::sendPDU, 0, sleepBetweenPdus, TimeUnit.MILLISECONDS);
        changeState(TransferState.RUNNING);
        suspended = false;
    }

    public OutTxState getCfdpState() {
        return this.outTxState;
    }

    private void complete(ConditionCode conditionCode) {
        if (outTxState == OutTxState.COMPLETED) {
            return;
        }
        outTxState = OutTxState.COMPLETED;
        // Calculate elapsed time since the transfer started, in seconds
        long duration = (System.currentTimeMillis() - wallclockStartTime) / 1000;
        // State transition to COMPLETED
        String eventMessageSuffix = request.getSourceFileName() + " -> " + request.getDestinationFileName();
        if (conditionCode == ConditionCode.NO_ERROR) {
            changeState(TransferState.COMPLETED);
            sendInfoEvent(ETYPE_TRANSFER_FINISHED,
                    "transfer finished successfully in " + duration + " seconds: "
                            + eventMessageSuffix);
        } else {
            // Handle as error
            failTransfer(conditionCode.toString());
            sendWarnEvent(ETYPE_TRANSFER_FINISHED,
                    "transfer finished with error in " + duration + " seconds: "
                            + eventMessageSuffix );
        }
    }


    @Override
    protected void cancel(ConditionCode conditionCode) {
        log.debug("TXID{} Cancelling with code {}", cfdpTransactionId, conditionCode);
        try {
            switch (outTxState) {
                case START:
                case SENDING_DATA:
                    suspended = false; // wake up if sleeping
                    outTxState = OutTxState.CANCELING;
                    changeState(TransferState.CANCELLING);
                    uploadCloseCmd(conditionCode);
                    break;
                case CANCELING:
                case COMPLETED:
                    break;
            }
        } catch (Exception e) {
                log.error("Error when sending cancel command: ", e);
                throw new RuntimeException(e);
        }
    }

    private void handleFault(ConditionCode conditionCode) {
        log.debug("TXID{} Handling fault {}", cfdpTransactionId, conditionCode);

        if (outTxState == OutTxState.CANCELING) {
            complete(conditionCode);
        } else {
            FaultHandlingAction action = getFaultHandlingAction(conditionCode);
            switch (action) {
            case ABANDON:
                complete(conditionCode);
                break;
            case CANCEL:
                cancel(conditionCode);
                break;
            case SUSPEND:
                suspend();
            }
        }
    }

    @Override
    public TransferDirection getDirection() {
        return TransferDirection.UPLOAD;
    }

    @Override
    public long getTotalSize() {
        return this.request.getFileLength();
    }

    @Override
    public String getBucketName() {
        return bucket != null ? bucket.getName() : null;
    }

    @Override
    public String getObjectName() {
        return request.getSourceFileName();
    }

    @Override
    public String getRemotePath() {
        return request.getDestinationFileName();
    }

    @Override
    public long getTransferredSize() {
        return this.transferred;
    }
}
