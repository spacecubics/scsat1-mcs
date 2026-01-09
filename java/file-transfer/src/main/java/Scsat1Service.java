package org.yamcs.cfdp;

import static org.yamcs.cfdp.CompletedTransfer.COL_CREATION_TIME;
import static org.yamcs.cfdp.CompletedTransfer.COL_DIRECTION;
import static org.yamcs.cfdp.CompletedTransfer.COL_TRANSFER_STATE;
import static org.yamcs.cfdp.CompletedTransfer.TDEF;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.yamcs.AbstractYamcsService;
import org.yamcs.ConfigurationException;
import org.yamcs.InitException;
import org.yamcs.Spec;
import org.yamcs.Spec.OptionType;
import org.yamcs.ValidationException;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.cfdp.Scsat1OngoingTransfer.FaultHandlingAction;
import org.yamcs.cfdp.pdu.ConditionCode;
import org.yamcs.cfdp.pdu.TLV;
import org.yamcs.events.EventProducer;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.filetransfer.BasicListingParser;
import org.yamcs.filetransfer.FileTransferFilter;
import org.yamcs.filetransfer.FileListingParser;
import org.yamcs.filetransfer.FileListingService;
import org.yamcs.filetransfer.FileTransfer;
import org.yamcs.filetransfer.FileTransferService;
import org.yamcs.filetransfer.InvalidRequestException;
import org.yamcs.filetransfer.RemoteFileListMonitor;
import org.yamcs.filetransfer.TransferMonitor;
import org.yamcs.filetransfer.TransferOptions;
import org.yamcs.protobuf.EntityInfo;
import org.yamcs.protobuf.FileTransferCapabilities;
import org.yamcs.protobuf.FileTransferOption;
import org.yamcs.protobuf.ListFilesResponse;
import org.yamcs.protobuf.TransferDirection;
import org.yamcs.protobuf.TransferState;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.utils.YObjectLoader;
import org.yamcs.utils.parser.ParseException;
import org.yamcs.buckets.Bucket;
import org.yamcs.yarch.Sequence;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.StreamSubscriber;
import org.yamcs.yarch.SqlBuilder;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;
import org.yamcs.yarch.streamsql.StreamSqlException;
import org.yamcs.yarch.streamsql.StreamSqlResult;

import com.google.common.collect.Streams;


public class Scsat1Service extends AbstractYamcsService
        implements FileTransferService, StreamSubscriber, TransferMonitor {

    static final String ETYPE_TRANSFER_STARTED = "TRANSFER_STARTED";
    static final String ETYPE_TRANSFER_FINISHED = "TRANSFER_FINISHED";
    static final String ETYPE_TRANSFER_SUSPENDED = "TRANSFER_SUSPENDED";
    static final String ETYPE_TRANSFER_RESUMED = "TRANSFER_RESUMED";

    static final String BUCKET_OPT = "bucket";
    static final String TABLE_NAME = "file_transfer";
    static final String SEQUENCE_NAME = "file_transfer";

    // FileTransferOption name literals
    private final String OVERWRITE_OPTION = "overwrite";
    private final String RELIABLE_OPTION = "reliable";
    private final String CLOSURE_OPTION = "closureRequested";
    private final String CREATE_PATH_OPTION = "createPath";
    private final String PDU_DELAY_OPTION = "pduDelay";
    private final String PDU_SIZE_OPTION = "pduSize";
    private final String START_OFFSET_OPTION = "startOffset";

    Map<CfdpTransactionId, Scsat1OngoingTransfer> pendingTransfers = new ConcurrentHashMap<>();
    Queue<Scsat1QueuedOutgoingTransfer> queuedTransfers = new ConcurrentLinkedQueue<>();

    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    Map<ConditionCode, FaultHandlingAction> senderFaultHandlers;
    EventProducer eventProducer;

    private Set<TransferMonitor> transferListeners = new CopyOnWriteArraySet<>();
    private Set<RemoteFileListMonitor> remoteFileListMonitors = new CopyOnWriteArraySet<>();
    private Map<String, EntityConf> localEntities = new LinkedHashMap<>();
    private Map<String, EntityConf> remoteEntities = new LinkedHashMap<>();

    int maxNumPendingUploads;
    int archiveRetrievalLimit;
    int pendingAfterCompletion;
    List<String> directoryTerminators;

    private boolean hasDownloadCapability = false;
    private boolean hasFileListingCapability = false;
    private FileListingService fileListingService;
    private FileListingParser fileListingParser;
    private boolean canChangeOffset;
    private boolean canChangePduDelay;
    private Stream dbStream;
    private List<Integer> pduDelayPredefinedValues;
    private List<Integer> startOffsetPredefinedValues;

    Sequence idSeq;
    static final Map<String, ConditionCode> VALID_CODES = new HashMap<>();
    static {
        VALID_CODES.put("AckLimitReached", ConditionCode.ACK_LIMIT_REACHED);
        VALID_CODES.put("KeepAliveLimitReached", ConditionCode.KEEP_ALIVE_LIMIT_REACHED);
        VALID_CODES.put("InvalidTransmissionMode", ConditionCode.INVALID_TRANSMISSION_MODE);
        VALID_CODES.put("FilestoreRejection", ConditionCode.FILESTORE_REJECTION);
        VALID_CODES.put("FileChecksumFailure", ConditionCode.FILE_CHECKSUM_FAILURE);
        VALID_CODES.put("FileSizeError", ConditionCode.FILE_SIZE_ERROR);
        VALID_CODES.put("NakLimitReached", ConditionCode.NAK_LIMIT_REACHED);
        VALID_CODES.put("InactivityDetected", ConditionCode.INACTIVITY_DETECTED);
        VALID_CODES.put("InvalidFileStructure", ConditionCode.INVALID_FILE_STRUCTURE);
        VALID_CODES.put("CheckLimitReached", ConditionCode.CHECK_LIMIT_REACHED);
        VALID_CODES.put("UnsupportedChecksum", ConditionCode.UNSUPPORTED_CHECKSUM_TYPE);
        VALID_CODES.put("CancelRequestReceived", ConditionCode.CANCEL_REQUEST_RECEIVED);
    }


    @Override
    public Spec getSpec() {
        Spec entitySpec = new Spec();
        entitySpec.addOption("name", OptionType.STRING);
        entitySpec.addOption("id", OptionType.INTEGER);
        entitySpec.addOption(BUCKET_OPT, OptionType.STRING).withDefault(null);

        Spec spec = new Spec();
        spec.addOption("sourceId", OptionType.INTEGER)
                .withDeprecationMessage("please use the localEntities");
        spec.addOption("destinationId", OptionType.INTEGER)
                .withDeprecationMessage("please use the remoteEntities");
        spec.addOption("canChangePduDelay", OptionType.BOOLEAN).withDefault(false);
        spec.addOption("pduDelayPredefinedValues", OptionType.LIST).withDefault(Collections.emptyList())
                .withElementType(OptionType.INTEGER);
        spec.addOption("sleepBetweenPdus", OptionType.INTEGER).withDefault(1000);
        spec.addOption("localEntities", OptionType.LIST).withElementType(OptionType.MAP).withSpec(entitySpec);
        spec.addOption("remoteEntities", OptionType.LIST).withElementType(OptionType.MAP).withSpec(entitySpec);
        spec.addOption("archiveRetrievalLimit", OptionType.INTEGER).withDefault(100);
        spec.addOption("senderFaultHandlers", OptionType.MAP).withSpec(Spec.ANY);
        spec.addOption("directoryTerminators", OptionType.LIST).withElementType(OptionType.STRING)
                .withDefault(Arrays.asList(":", "/", "\\"));
        spec.addOption("maxNumPendingUploads", OptionType.INTEGER).withDefault(10);
        spec.addOption("pendingAfterCompletion", OptionType.INTEGER).withDefault(600000);
        spec.addOption("fileListingServiceClassName", OptionType.STRING).withDefault("org.yamcs.cfdp.Scsat1Service");
        spec.addOption("fileListingServiceArgs", OptionType.MAP).withSpec(Spec.ANY)
                .withDefault(new HashMap<>());
        spec.addOption("fileListingParserClassName", OptionType.STRING)
                .withDefault("org.yamcs.filetransfer.BasicListingParser");
        spec.addOption("fileListingParserArgs", OptionType.MAP).withSpec(Spec.ANY)
                .withDefault(new HashMap<>());

        spec.addOption("canChangeOffset", OptionType.BOOLEAN).withDefault(false);
        spec.addOption("startOffset", OptionType.INTEGER).withDefault(0);
        spec.addOption("startOffsetPredefinedValues", OptionType.LIST).withDefault(Collections.emptyList())
                .withElementType(OptionType.INTEGER);
        return spec;
    }

    @Override
    public void init(String yamcsInstance, String serviceName, YConfiguration config) throws InitException {
        super.init(yamcsInstance, serviceName, config);
        YarchDatabaseInstance ydb = YarchDatabase.getInstance(yamcsInstance);
        maxNumPendingUploads = config.getInt("maxNumPendingUploads");
        archiveRetrievalLimit = config.getInt("archiveRetrievalLimit", 100);
        pendingAfterCompletion = config.getInt("pendingAfterCompletion", 600000);
        directoryTerminators = config.getList("directoryTerminators");
        canChangePduDelay = config.getBoolean("canChangePduDelay");
        pduDelayPredefinedValues = config.getList("pduDelayPredefinedValues");
        canChangeOffset = config.getBoolean("canChangeOffset");
        startOffsetPredefinedValues = config.getList("startOffsetPredefinedValues");

        String fileListingServiceClassName = config.getString("fileListingServiceClassName");
        YConfiguration fileListingServiceConfig = config.getConfig("fileListingServiceArgs");
        if (Objects.equals(fileListingServiceClassName, this.getClass().getName())) {
            // Use this instance as the file listing service
            fileListingService = this;

            String fileListingParserClassName;
            try {
                fileListingParserClassName = fileListingServiceConfig.getString("fileListingParserClassName");
            } catch (ConfigurationException e) {
                fileListingParserClassName = config.getString("fileListingParserClassName");
            }

            fileListingParser = YObjectLoader.loadObject(fileListingParserClassName);
            if (fileListingParser instanceof BasicListingParser) {
                // directoryTerminators will be overwritten by the specific fileListingParserArgs if existing
                ((BasicListingParser) fileListingParser).setDirectoryTerminators(directoryTerminators);
            }
            try {
                Spec spec = fileListingParser.getSpec();
                YConfiguration fileListingParserConfig;
                try {
                    fileListingParserConfig = fileListingServiceConfig.getConfig("fileListingParserArgs");
                } catch (ConfigurationException e) {
                    fileListingParserConfig = config.getConfig("fileListingParserArgs");
                }
                fileListingParser.init(yamcsInstance,
                        spec != null ? spec.validate(fileListingParserConfig) : fileListingParserConfig);
            } catch (ValidationException e) {
                throw new InitException("Failed to validate FileListingParser config", e);
            }
        } else {
            // Load and initialize an external file listing service
            fileListingService = YObjectLoader.loadObject(fileListingServiceClassName);
            fileListingService.init(yamcsInstance, serviceName + "_" + fileListingServiceClassName,
                    fileListingServiceConfig);
        }

        // Initialize source/destination configuration
        initSrcDst(config);
        // Set up event producer
        eventProducer = EventProducerFactory.getEventProducer(yamcsInstance, "Scsat1Service", 10000);
        // Get ID sequence
        idSeq = ydb.getSequence(SEQUENCE_NAME, true);
        // Configure sender fault handlers if available
        if (config.containsKey("senderFaultHandlers")) {
            senderFaultHandlers = readFaultHandlers(config.getMap("senderFaultHandlers"));
        } else {
            senderFaultHandlers = Collections.emptyMap();
        }
        // Set up recording
        setupRecording(ydb);
    }

    private Map<ConditionCode, FaultHandlingAction> readFaultHandlers(Map<String, String> map) {
        Map<ConditionCode, FaultHandlingAction> m = new EnumMap<>(ConditionCode.class);
        for (Map.Entry<String, String> me : map.entrySet()) {
            ConditionCode code = VALID_CODES.get(me.getKey());
            if (code == null) {
                throw new ConfigurationException(
                        "Unknown condition code " + me.getKey() + ". Valid codes: " + VALID_CODES.keySet());
            }
            FaultHandlingAction action = FaultHandlingAction.fromString(me.getValue());
            if (action == null) {
                throw new ConfigurationException(
                        "Unknown action " + me.getValue() + ". Valid actions: " + FaultHandlingAction.actions());
            }
            m.put(code, action);
        }
        return m;
    }

    private void initSrcDst(YConfiguration config) throws InitException {
        if (config.containsKey("sourceId")) {
            localEntities.put("default", new EntityConf(config.getLong("sourceId"), "default", null));
        }

        if (config.containsKey("destinationId")) {
            remoteEntities.put("default", new EntityConf(config.getLong("destinationId"), "default", null));
        }
        if (config.containsKey("localEntities")) {
            for (YConfiguration c : config.getConfigList("localEntities")) {
                long id = c.getLong("id");
                String name = c.getString("name");
                if (localEntities.containsKey(name)) {
                    throw new ConfigurationException("Duplicate local entity '" + name + "'.");
                }
                Bucket bucket = null;
                if (c.containsKey(BUCKET_OPT)) {
                    bucket = getBucket(c.getString(BUCKET_OPT));
                }
                EntityConf ent = new EntityConf(id, name, bucket);
                localEntities.put(name, ent);
            }
        }

        if (config.containsKey("remoteEntities")) {
            for (YConfiguration c : config.getConfigList("remoteEntities")) {
                long id = c.getLong("id");
                String name = c.getString("name");
                if (remoteEntities.containsKey(name)) {
                    throw new ConfigurationException("Duplicate remote entity '" + name + "'.");
                }
                Bucket bucket = null;
                if (c.containsKey(BUCKET_OPT)) {
                    bucket = getBucket(c.getString(BUCKET_OPT));
                }
                EntityConf ent = new EntityConf(id, name, bucket);
                remoteEntities.put(name, ent);
            }
        }

        if (localEntities.isEmpty()) {
            throw new ConfigurationException("No local entity specified");
        }
        if (remoteEntities.isEmpty()) {
            throw new ConfigurationException("No remote entity specified");
        }
    }

    private Bucket getBucket(String bucketName) throws InitException {
        var bucketManager = YamcsServer.getServer().getBucketManager();
        try {
            Bucket bucket = bucketManager.getBucket(bucketName);
            if (bucket == null) {
                bucket = bucketManager.createBucket(bucketName);
            }
            return bucket;
        } catch (IOException e) {
            throw new InitException(e);
        }
    }

    private void setupRecording(YarchDatabaseInstance ydb) throws InitException {
        try {
            if (ydb.getTable(TABLE_NAME) == null) {
                String query = "create table " + TABLE_NAME + "(" + TDEF.getStringDefinition1()
                        + ", primary key(id, serverId))";
                ydb.execute(query);
            }
            String streamName = TABLE_NAME + "table_in";
            if (ydb.getStream(streamName) == null) {
                ydb.execute("create stream " + streamName + TDEF.getStringDefinition());
            }
            ydb.execute("upsert_append into " + TABLE_NAME + " select * from " + streamName);
            dbStream = ydb.getStream(streamName);
        } catch (ParseException | StreamSqlException e) {
            throw new InitException(e);
        }
    }


    public Scsat1OngoingTransfer getCfdpTransfer(CfdpTransactionId transferId) {
        return pendingTransfers.get(transferId);
    }

    @Override
    public FileTransfer getFileTransfer(long id) {
        Optional<CfdpFileTransfer> r = Streams.concat(pendingTransfers.values().stream(), queuedTransfers.stream())
                .filter(c -> c.getId() == id).findAny();
        if (r.isPresent()) {
            return r.get();
        } else {
            return searchInArchive(id);
        }
    }

    private FileTransfer searchInArchive(long id) {
        YarchDatabaseInstance ydb = YarchDatabase.getInstance(yamcsInstance);
        try {
            StreamSqlResult res = ydb.execute("select * from " + TABLE_NAME + " where id=?", id);
            FileTransfer r = null;
            if (res.hasNext()) {
                r = new CompletedTransfer(res.next());
            }
            res.close();
            return r;

        } catch (Exception e) {
            log.error("Error executing query", e);
            return null;
        }
    }

@Override
    public List<FileTransfer> getTransfers(FileTransferFilter filter) {
        List<FileTransfer> toReturn = new ArrayList<>();
        YarchDatabaseInstance ydb = YarchDatabase.getInstance(yamcsInstance);

        pendingTransfers.values().stream()
                .filter(Scsat1Service::isRunning)
                .forEach(toReturn::add);
        toReturn.addAll(queuedTransfers);

        toReturn.removeIf(transfer -> {
            if (filter.start != TimeEncoding.INVALID_INSTANT) {
                if (transfer.getCreationTime() < filter.start) {
                    return true;
                }
            }
            if (filter.stop != TimeEncoding.INVALID_INSTANT) {
                if (transfer.getCreationTime() >= filter.stop) {
                    return true;
                }
            }
            if (!filter.states.isEmpty() && !filter.states.contains(transfer.getTransferState())) {
                return true;
            }
            if (filter.direction != null && !Objects.equals(filter.direction, transfer.getDirection())) {
                return true;
            }
            if (filter.localEntityId != null && !Objects.equals(filter.localEntityId, transfer.getLocalEntityId())) {
                return true;
            }
            if (filter.remoteEntityId != null && !Objects.equals(filter.remoteEntityId, transfer.getRemoteEntityId())) {
                return true;
            }

            return false;
        });

        if (toReturn.size() >= filter.limit) {
            return toReturn;
        }

        // Query only for COMPLETED or FAILED, while respecting the incoming requested states
        // (want to avoid duplicates with the in-memory data structure)
        if (filter.states.isEmpty() || filter.states.contains(TransferState.COMPLETED)
                || filter.states.contains(TransferState.FAILED)) {

            var sqlb = new SqlBuilder(TABLE_NAME);

            if (filter.start != TimeEncoding.INVALID_INSTANT) {
                sqlb.whereColAfterOrEqual(COL_CREATION_TIME, filter.start);
            }
            if (filter.stop != TimeEncoding.INVALID_INSTANT) {
                sqlb.whereColBefore(COL_CREATION_TIME, filter.stop);
            }

            if (filter.states.isEmpty()) {
                sqlb.whereColIn(COL_TRANSFER_STATE,
                        Arrays.asList(TransferState.COMPLETED.name(), TransferState.FAILED.name()));
            } else {
                var queryStates = new ArrayList<>(filter.states);
                queryStates.removeIf(state -> {
                    return state != TransferState.COMPLETED && state != TransferState.FAILED;
                });

                var stringStates = queryStates.stream().map(TransferState::name).toList();
                sqlb.whereColIn(COL_TRANSFER_STATE, stringStates);

            }
            if (filter.direction != null) {
                sqlb.where(COL_DIRECTION + " = ?", filter.direction.name());
            }
            if (filter.localEntityId != null) {
                // The 1=1 clause is a trick because Yarch is being difficult about multiple lparens
                sqlb.where("""
                        (1=1 and
                            (direction = 'UPLOAD' and sourceId = ?) or
                            (direction = 'DOWNLOAD' and destinationId = ?)
                        )
                        """, filter.localEntityId, filter.localEntityId);
            }
            if (filter.remoteEntityId != null) {
                // The 1=1 clause is a trick because Yarch is being difficult about multiple lparens
                sqlb.where("""
                        (1=1 and
                            (direction = 'UPLOAD' and destinationId = ?) or
                            (direction = 'DOWNLOAD' and sourceId = ?)
                        )
                        """, filter.remoteEntityId, filter.remoteEntityId);
            }

            sqlb.descend(filter.descending);
            sqlb.limit(filter.limit - toReturn.size());

            try {
                var res = ydb.execute(sqlb.toString(), sqlb.getQueryArgumentsArray());
                while (res.hasNext()) {
                    Tuple t = res.next();
                    toReturn.add(new CompletedTransfer(t));
                }
                res.close();
            } catch (ParseException | StreamSqlException e) {
                log.error("Error executing query", e);
            }
        }

        Collections.sort(toReturn, (a, b) -> {
            var rc = Long.compare(a.getCreationTime(), b.getCreationTime());
            return filter.descending ? -rc : rc;
        });
        return toReturn;
    }

    private CfdpFileTransfer processPutRequest(long initiatorEntityId, long seqNum, long creationTime,
            PutRequest request,
            Bucket bucket, Integer customPduSize, Integer customPduDelay, Integer startOffset) {
        Scsat1OutgoingTransfer transfer = new Scsat1OutgoingTransfer(yamcsInstance, initiatorEntityId, seqNum, creationTime,
                executor, request, config, bucket, customPduSize, customPduDelay, eventProducer, this,
                senderFaultHandlers, startOffset);

        dbStream.emitTuple(CompletedTransfer.toInitialTuple(transfer));

        stateChanged(transfer);
        pendingTransfers.put(transfer.getTransactionId(), transfer);

        if (request.getFileLength() > 0) {
            eventProducer.sendInfo(ETYPE_TRANSFER_STARTED,
                    "Starting new file transfer upload TXID[" + transfer.getTransactionId() + "] " + transfer.getObjectName()
                            + " -> " + transfer.getRemotePath());
        } else {
            eventProducer.sendInfo(ETYPE_TRANSFER_STARTED,
                    "Starting new file transfer upload TXID[" + transfer.getTransactionId()
                            + "] Fileless transfer (metadata options: \n"
                            + (request.getMetadata() != null ? request.getMetadata().getOptions().stream()
                                    .map(TLV::toString).collect(Collectors.joining(",\n")) : "")
                            + "\n)");
        }
        transfer.start();
        return transfer;
    }

    // called when queueConcurrentUploads = true, will start a queued transfer if no other transfer is running
    private void tryStartQueuedTransfer() {
        if (numPendingUploads() >= maxNumPendingUploads) {
            return;
        }
        Scsat1QueuedOutgoingTransfer trsf = queuedTransfers.poll();
        if (trsf != null) {
            processPutRequest(trsf.getInitiatorEntityId(), trsf.getId(), trsf.getCreationTime(), trsf.getPutRequest(),
                    trsf.getBucket(), trsf.getCustomPduSize(), trsf.getCustomPduDelay(), trsf.getStartOffset());
        }
    }

    private long numPendingUploads() {
        return pendingTransfers.values().stream()
                .filter(trsf -> isRunning(trsf) && trsf.getDirection() == TransferDirection.UPLOAD)
                .count();
    }

    static boolean isRunning(Scsat1OngoingTransfer trsf) {
        return trsf.state == TransferState.RUNNING || trsf.state == TransferState.PAUSED
                || trsf.state == TransferState.CANCELLING;
    }

    private Scsat1OngoingTransfer processPauseRequest(Scsat1PauseRequest request) {
        Scsat1OngoingTransfer transfer = request.getTransfer();
        transfer.pauseTransfer();
        return transfer;
    }

    private Scsat1OngoingTransfer processResumeRequest(Scsat1ResumeRequest request) {
        Scsat1OngoingTransfer transfer = request.getTransfer();
        transfer.resumeTransfer();
        return transfer;
    }

    private Scsat1OngoingTransfer processCancelRequest(Scsat1CancelRequest request) {
        Scsat1OngoingTransfer transfer = request.getTransfer();
        transfer.cancelTransfer();
        return transfer;
    }

    @Override
    public void onTuple(Stream stream, Tuple tuple) {
        // Ignore all incoming packets (this entity is sender-only)
        log.warn("Received file transfer packet but this instance is send-only; ignoring packet");
        return;
    }

    public EntityConf getRemoteEntity(long entityId) {
        return remoteEntities.entrySet()
                .stream()
                .filter(me -> me.getValue().id == entityId)
                .map(Map.Entry::getValue)
                .findAny()
                .orElse(null);
    }

    public EntityConf getLocalEntity(long entityId) {
        return localEntities.entrySet()
                .stream()
                .filter(me -> me.getValue().id == entityId)
                .map(Map.Entry::getValue)
                .findAny()
                .orElse(null);
    }

    @Override
    public void registerTransferMonitor(TransferMonitor listener) {
        transferListeners.add(listener);
    }

    @Override
    public void unregisterTransferMonitor(TransferMonitor listener) {
        transferListeners.remove(listener);
    }

    @Override
    public void registerRemoteFileListMonitor(RemoteFileListMonitor monitor) {
        if (fileListingService != this) {
            fileListingService.registerRemoteFileListMonitor(monitor);
            return;
        }
        log.debug("Registering file list monitor");
        remoteFileListMonitors.add(monitor);
    }

    @Override
    public void unregisterRemoteFileListMonitor(RemoteFileListMonitor monitor) {
        if (fileListingService != this) {
            fileListingService.unregisterRemoteFileListMonitor(monitor);
            return;
        }
        log.debug("Un-registering file list monitor");
        remoteFileListMonitors.remove(monitor);
    }

    @Override
    public void notifyRemoteFileListMonitors(ListFilesResponse listFilesResponse) {
        if (fileListingService != this) {
            fileListingService.notifyRemoteFileListMonitors(listFilesResponse);
            return;
        }
        remoteFileListMonitors.forEach(l -> l.receivedFileList(listFilesResponse));
    }

    @Override
    public Set<RemoteFileListMonitor> getRemoteFileListMonitors() {
        if (fileListingService != this) {
            return fileListingService.getRemoteFileListMonitors();
        }
        return remoteFileListMonitors;
    }

    @Override
    protected void doStart() {
        notifyStarted();
    }

    @Override
    protected void doStop() {
        for (Scsat1OngoingTransfer trsf : pendingTransfers.values()) {
            if (trsf.state == TransferState.RUNNING || trsf.state == TransferState.PAUSED) {
                trsf.failTransfer("service shutdown");
            }
        }
        executor.shutdown();
        notifyStopped();
    }

    @Override
    public void streamClosed(Stream stream) {
        if (isRunning()) {
            log.debug("Stream {} closed", stream.getName());
            notifyFailed(new Exception("Stream " + stream.getName() + " cloased"));
        }
    }

    @Override
    public void stateChanged(FileTransfer ft) {
        CfdpFileTransfer cfdpTransfer = (CfdpFileTransfer) ft;
        dbStream.emitTuple(CompletedTransfer.toUpdateTuple(cfdpTransfer));

        // Notify downstream listeners
        transferListeners.forEach(l -> l.stateChanged(cfdpTransfer));

        if (cfdpTransfer.getTransferState() == TransferState.COMPLETED
                || cfdpTransfer.getTransferState() == TransferState.FAILED) {

            if (cfdpTransfer instanceof Scsat1OngoingTransfer) {
                // keep it in pending for a while such that PDUs from remote entity can still be answered
                executor.schedule(() -> pendingTransfers.remove(cfdpTransfer.getTransactionId()),
                        pendingAfterCompletion, TimeUnit.MILLISECONDS);
            }
            executor.submit(this::tryStartQueuedTransfer);
        }
    }

    @Override
    public List<EntityInfo> getLocalEntities() {
        return localEntities.values().stream()
                .map(c -> EntityInfo.newBuilder().setName(c.name).setId(c.id).build())
                .collect(Collectors.toList());
    }

    @Override
    public List<EntityInfo> getRemoteEntities() {
        return remoteEntities.values().stream()
                .map(c -> EntityInfo.newBuilder().setName(c.name).setId(c.id).build())
                .collect(Collectors.toList());
    }

    public Scsat1OngoingTransfer getScsat1OngoingTransfer(long id) {
        return pendingTransfers.values().stream().filter(c -> c.getId() == id).findAny().orElse(null);
    }

    @Override
    public synchronized CfdpFileTransfer startUpload(String source, Bucket bucket, String objectName,
            String destination, final String destinationPath, TransferOptions options) throws IOException {
        byte[] objData;
        try {
            objData = bucket.getObjectAsync(objectName).get();
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (objData == null) {
            throw new InvalidRequestException("No object named '" + objectName + "' in bucket " + bucket.getName());
        }
        String absoluteDestinationPath = getAbsoluteDestinationPath(destinationPath, objectName);


        long sourceId = getEntityFromName(source, localEntities).id;
        long destinationId = getEntityFromName(destination, remoteEntities).id;

        // For backwards compatibility
        var booleanOptions = new HashMap<>(Map.of(
                OVERWRITE_OPTION, options.isOverwrite(),
                RELIABLE_OPTION, options.isReliable(),
                CLOSURE_OPTION, options.isClosureRequested(),
                CREATE_PATH_OPTION, options.isCreatePath()));

        OptionValues optionValues = getOptionValues(options.getExtraOptions());

        booleanOptions.putAll(optionValues.booleanOptions);

        FilePutRequest request = new FilePutRequest(sourceId, destinationId, objectName, absoluteDestinationPath,
                booleanOptions.get(OVERWRITE_OPTION), booleanOptions.get(RELIABLE_OPTION),
                booleanOptions.get(CLOSURE_OPTION), booleanOptions.get(CREATE_PATH_OPTION), bucket, objData);
        long creationTime = YamcsServer.getTimeService(yamcsInstance).getMissionTime();

        Double pduSize = optionValues.doubleOptions.get(PDU_SIZE_OPTION);
        Double pduDelay = optionValues.doubleOptions.get(PDU_DELAY_OPTION);

        Double startOffset = optionValues.doubleOptions.get(START_OFFSET_OPTION);

        if (numPendingUploads() < maxNumPendingUploads) {
            return processPutRequest(
                sourceId,
                idSeq.next(),
                creationTime,
                request,
                bucket,
                pduSize != null ? pduSize.intValue() : null,
                pduDelay != null ? pduDelay.intValue() : null,
                startOffset != null ? startOffset.intValue() : null
                );
        } else {
            Scsat1QueuedOutgoingTransfer transfer = new Scsat1QueuedOutgoingTransfer(
                    sourceId,
                    idSeq.next(),
                    creationTime,
                    request,
                    bucket,
                    pduSize != null ? pduSize.intValue() : null,
                    pduDelay != null ? pduDelay.intValue() : null,
                    startOffset != null ? startOffset.intValue() : null
                    );
            dbStream.emitTuple(CompletedTransfer.toInitialTuple(transfer));
            queuedTransfers.add(transfer);
            transferListeners.forEach(l -> l.stateChanged(transfer));

            executor.submit(this::tryStartQueuedTransfer);
            return transfer;
        }
    }

    @Override
    public FileTransfer startDownload(String sourceEntity, String sourcePath, String destinationEntity, Bucket bucket,
            String objectName, TransferOptions options) throws InvalidRequestException {
        throw new InvalidRequestException("Downloading is not enabled on this File Transfer service");
    }

    @Override
    public void fetchFileList(String source, String destination, String remotePath, Map<String, Object> options) {
        throw new InvalidRequestException("File listing is not enabled on this File Transfer service");
    }

    @Override
    public ListFilesResponse getFileList(String source, String destination, String remotePath,
            Map<String, Object> options) {
        // Getting remote file listing is not enabled on this File Transfer service
        return null;
    }

    @Override
    public void saveFileList(ListFilesResponse listFilesResponse) {
        // Saving file Listing is not enabled on this File Transfer service
    }

    private EntityConf getEntityFromName(String entityName, Map<String, EntityConf> entities) {
        if (entityName == null || entityName.isBlank()) {
            return entities.values().iterator().next();
        } else {
            if (!entities.containsKey(entityName)) {
                throw new InvalidRequestException(
                        "Invalid entity '" + entityName + "' (should be one of " + entities + "");
            }
            return entities.get(entityName);
        }
    }

    private String getAbsoluteDestinationPath(String destinationPath, String localObjectName) {
        if (localObjectName == null) {
            throw new NullPointerException("local object name cannot be null");
        }
        if (destinationPath == null) {
            return localObjectName;
        }
        if (directoryTerminators.stream().anyMatch(destinationPath::endsWith)) {
            return destinationPath + localObjectName;
        }
        return destinationPath;
    }

    private static class OptionValues {
        HashMap<String, Boolean> booleanOptions = new HashMap<>();
        HashMap<String, Double> doubleOptions = new HashMap<>();
    }

    private OptionValues getOptionValues(Map<String, Object> extraOptions) {
        var optionValues = new OptionValues();

        for (Map.Entry<String, Object> option : extraOptions.entrySet()) {
            try {
                switch (option.getKey()) {
                case OVERWRITE_OPTION:
                case RELIABLE_OPTION:
                case CLOSURE_OPTION:
                case CREATE_PATH_OPTION:
                    optionValues.booleanOptions.put(option.getKey(), (boolean) option.getValue());
                    break;
                case START_OFFSET_OPTION:
                case PDU_DELAY_OPTION:
                case PDU_SIZE_OPTION:
                    optionValues.doubleOptions.put(option.getKey(), (double) option.getValue());
                    break;
                default:
                    log.warn("Unknown file transfer option: {} (value: {})", option.getKey(), option.getValue());
                }
            } catch (ClassCastException e) {
                log.warn("Failed to cast option '{}' to its correct type (value: {})", option.getKey(),
                        option.getValue());
            }
        }

        return optionValues;
    }

    @Override
    public void pause(FileTransfer transfer) {
        processPauseRequest(new Scsat1PauseRequest(transfer));
    }

    @Override
    public void resume(FileTransfer transfer) {
        processResumeRequest(new Scsat1ResumeRequest(transfer));
    }

    @Override
    public void cancel(FileTransfer transfer) {
        if (transfer instanceof Scsat1OngoingTransfer) {
            processCancelRequest(new Scsat1CancelRequest(transfer));
        } else if (transfer instanceof Scsat1QueuedOutgoingTransfer) {
            Scsat1QueuedOutgoingTransfer trsf = (Scsat1QueuedOutgoingTransfer) transfer;
            if (queuedTransfers.remove(trsf)) {
                trsf.setTransferState(TransferState.FAILED);
                trsf.setFailureReason("Cancelled while queued");
                stateChanged(trsf);
            }
        } else {
            throw new InvalidRequestException("Unknown transfer type " + transfer);
        }
    }

    @Override
    public List<FileTransferOption> getFileTransferOptions() {
        var options = new ArrayList<FileTransferOption>();
        if (canChangePduDelay) {
            options.add(FileTransferOption.newBuilder()
                    .setName(PDU_DELAY_OPTION)
                    .setType(FileTransferOption.Type.DOUBLE)
                    .setTitle("PDU delay")
                    .setDefault(Integer.toString(config.getInt("sleepBetweenPdus")))
                    .addAllValues(pduDelayPredefinedValues.stream()
                            .map(value -> FileTransferOption.Value.newBuilder().setValue(value.toString()).build())
                            .collect(Collectors.toList()))
                    .setAllowCustomOption(true)
                    .build());
        }
        if (canChangeOffset) {
            options.add(FileTransferOption.newBuilder()
                    .setName(START_OFFSET_OPTION)
                    .setType(FileTransferOption.Type.DOUBLE)
                    .setTitle("Offset")
                    .setDefault(Integer.toString(config.getInt("startOffset")))
                    .addAllValues(startOffsetPredefinedValues.stream()
                            .map(value -> FileTransferOption.Value.newBuilder().setValue(value.toString()).build())
                            .collect(Collectors.toList()))
                    .setAllowCustomOption(true)
                    .build());
        }
        return options;
    }

    @Override
    public FileTransferCapabilities getCapabilities() {
        return FileTransferCapabilities
                .newBuilder()
                .setDownload(hasDownloadCapability)
                .setUpload(true)
                .setRemotePath(true)
                .setFileList(hasFileListingCapability)
                .setHasTransferType(true)
                .build();
    }

    ScheduledThreadPoolExecutor getExecutor() {
        return executor;
    }

    public FaultHandlingAction getSenderFaultHandler(ConditionCode code) {
        return senderFaultHandlers.get(code);
    }


    // Called from unit tests to abort all transactions
    void abortAll() {
        pendingTransfers.clear();
        queuedTransfers.clear();
    }
}
