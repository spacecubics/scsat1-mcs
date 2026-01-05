package org.yamcs.tctm;

import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Arrays;

import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.time.FixedSizeTimeDecoder;
import org.yamcs.time.Float64TimeDecoder;
import org.yamcs.time.TimeDecoder;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.utils.ByteArrayUtils;
import org.yamcs.tctm.csp.CspPacket;

public class Scsat1PacketPreprocessor extends AbstractPacketPreprocessor {
    static class TmData {
        private final int dport;
        private final int sport;
        private final List<Integer> commandIdList;
        private final int commandIdOffset = 4;
        private final int commandIdLength = 1;

        public TmData(YConfiguration config) {
            dport = config.getInt("dport", -1);
            sport = config.getInt("sport", -1);
            // If commandId is not specified, initialize it to null
            if (config.containsKey("commandId")) {
                commandIdList = config.getList("commandId");
            } else {
                commandIdList = null;
            }
        }

        // Validate if port is matches the packet's port
        private boolean isMatchedPacketPortNum(byte[] packet) {
            CspPacket cspHeader = new CspPacket(packet);
            int packetDport = cspHeader.getDestinationPort();
            int packetSport = cspHeader.getSourcePort();
            // The port doesn't match the configured value
            if (packetDport != dport && dport != -1) {
                return false;
            }
            if (packetSport != sport && sport != -1) {
                return false;
            }
            // Both ports match the configured value or are unconfigured
            return true;
        }

        // Validate if command ID is unspecified or matches the packet's command ID
        private boolean isValidPacketCommandId(byte[] packet) {
            if (packet.length < commandIdOffset + commandIdLength) {
                return false;
            }
            int packetCommandId = ByteArrayUtils.decodeUnsignedByte(packet, commandIdOffset);
            if (commandIdList == null || commandIdList.isEmpty() || commandIdList.contains(packetCommandId)) {
                return true;
            } else {
                return false;
            }
        }

        public boolean isValidPortAndCommandId(byte[] packet) {
            boolean isMatchedPortNum = isMatchedPacketPortNum(packet);
            boolean isValidCommandId = isValidPacketCommandId(packet);
            if (isMatchedPortNum && isValidCommandId) {
                return true;
            } else {
                return false;
            }
        }
    }

    class TimeSettings {
        private final int timestampOffset;
        private final ByteOrder byteOrder;
        private final TimeDecoder timeDecoder;
        private final TimeEpochs timeEpoch;
        private final List<TmData> tmDataList;

        TimeSettings(YConfiguration config) {
            timestampOffset = config.getInt("timestampOffset", -1);
            // Byte order setting (default: BIG_ENDIAN)
            String byteOrderStr = config.getString("byteOrder", "BIG_ENDIAN");
            if ("LITTLE_ENDIAN".equalsIgnoreCase(byteOrderStr)) {
                byteOrder = ByteOrder.LITTLE_ENDIAN;
            } else {
                byteOrder = ByteOrder.BIG_ENDIAN;
            }
            // Read the setting of target telemetry
            if (config.containsKey("tmDataList")) {
                tmDataList = config.getConfigList("tmDataList").stream()
                                .map(tmConfig -> new TmData(tmConfig))
                                .collect(Collectors.toList());
            } else {
                tmDataList = null;
            }
            // Time Encoding setting
            if (config.containsKey("timeEncoding")) {
                YConfiguration timeEncodingConfig = config.getConfig("timeEncoding");
                int size = timeEncodingConfig.getInt("size", 8);
                long multiplier = timeEncodingConfig.getLong("multiplier", 1);
                String type = timeEncodingConfig.getString("type", "FIXED");
                String epoc = timeEncodingConfig.getString("epoch", "UNIX");
                if ("FLOAT64".equals(type)) {
                    timeDecoder = new Float64TimeDecoder(byteOrder);
                } else {
                    timeDecoder = new FixedSizeTimeDecoder(byteOrder, size, multiplier);
                }
                if ("UNIX".equals(epoc)) {
                    timeEpoch = TimeEpochs.UNIX;
                } else {
                    timeEpoch = TimeEpochs.NONE;
                }
            } else {
                timeDecoder = new FixedSizeTimeDecoder(byteOrder, 8, 1);
                timeEpoch = TimeEpochs.UNIX;
            }
        }

        public void setPacketGenerationTime(TmPacket tmPacket) {
            byte[] packet = tmPacket.getPacket();
            if (timestampOffset < 0 || tmDataList == null) {
                tmPacket.setGenerationTime(tmPacket.getReceptionTime());
                return;
            }
            for (TmData data : tmDataList) {
                if (data.isValidPortAndCommandId(packet)) {
                    // The packet matches the YAML-specified telemetry
                    // If commandID is not specified, any packet with a matching port is valid
                    Scsat1PacketPreprocessor.this.timeDecoder = timeDecoder;
                    Scsat1PacketPreprocessor.this.timeEpoch = timeEpoch;
                    setRealtimePacketTime(tmPacket, timestampOffset);
                    return;
                }
            }
            // No match found in tmDataList
            tmPacket.setGenerationTime(tmPacket.getReceptionTime());
            return;
        }
    }

    class ExtractEPSFilePacket {
        private byte[] header;
        private final int sport = 10;
        private final int commandId = 2;
        private final int commandIdOffset = 4;
        private final int fileIdOffset = 5;
        private final int blockDataListOffset = 12;
        private final int epsSrcId = 4;

        public ExtractEPSFilePacket(TmPacket tmPacket) {
            byte[] packet = tmPacket.getPacket();
            if (packet.length < blockDataListOffset) {
                header = null;
                return;
            }
            CspPacket cspHeader = new CspPacket(packet);
            int srcId = cspHeader.getSource();
            // If not EPS, header is null.
            if (srcId != epsSrcId) {
                header = null;
                return;
            }
            int packetSport = cspHeader.getSourcePort();
            int packetCommandId = ByteArrayUtils.decodeUnsignedByte(packet, commandIdOffset);
            // If not /SCSAT1/EPS/DATA_PACKET_STREAM, header is null.
            if (sport != packetSport || commandId != packetCommandId ) {
                header = null;
                return;
            }
            // Set the appropriate header
            setHeader(packet);
        }

        private void setHeader(byte[] packet) {
            final int startupT = 7;
            final int generalT = 10;
            final int fileId = ByteArrayUtils.decodeUnsignedByte(packet, fileIdOffset);
            switch(fileId) {
                case startupT:
                    header = new byte[] { (byte) 0x88, 0x18, 0x07, 0x00, 0x01, 0x00 };
                    break;
                case generalT:
                    header = new byte[] { (byte) 0x88, 0x18, 0x07, 0x00, 0x00, 0x00 };
                    break;
                default:
                    // Return it as is if the fileID is unexpected.
                    header = null;
            }
        }

        public void dumpHex(byte[] data, int bytesPerLine) {
            if (data == null) {
                System.out.println("data is null");
                return;
            }
            for (int i = 0; i < data.length; i++) {
                System.out.printf("%02X ", data[i]);
                if ((i + 1) % bytesPerLine == 0) {
                    System.out.println();
                }
            }
            System.out.println();
        }

        private void ResetContinuousValue() {
            extractData = null;
            payloadLength = -1;
            expectedEntryId = -1;
            expectedOffset = 0;
        }

        // Packet size must be less than the one file size due to only one file can be constructed
        public TmPacket extractedDataFromPacket(TmPacket tmPacket) {
            // 1. Basic checks and preparation
            if (header == null) return tmPacket;
            
            byte[] packet = tmPacket.getPacket();
            
            // Constants based on ICD 6.1.8 Data Packet Stream
            final int ENTRY_ID_OFFSET = 6;
            final int OFFSET_FIELD_OFFSET = 10;
            final int ENTRY_HEADER_SIZE = 6;      // CRC(2) + Length(2) + ID(2)
            final int PAYLOAD_LEN_FIELD_POS = 4;  // Position of 'length' field within the entry header
            final int ERROR_BLOCK_LEN = 255;      // Error indicator for block length
        
            long entryId = ByteArrayUtils.decodeUnsignedIntLE(packet, ENTRY_ID_OFFSET);
            int offset = ByteArrayUtils.decodeUnsignedShortLE(packet, OFFSET_FIELD_OFFSET);
        
            // 2. Check entry continuity and sequence
            if (shouldResetEntry(entryId, offset)) {
                ResetContinuousValue();
                expectedEntryId = entryId;
            }
        
            if (offset != expectedOffset) {
                System.err.printf("[WARN] Offset mismatch for Entry %d. Expected %d, got %d. Discarding.\n", 
                                  entryId, expectedOffset, offset);
                ResetContinuousValue();
                return null;
            }
        
            // 3. Extract and process data blocks from the CSP packet
            byte[] blockDataList = Arrays.copyOfRange(packet, blockDataListOffset, packet.length);
            int cursor = 0;
            TmPacket resultPacket = null;
        
            while (cursor < blockDataList.length) {
                int blockLen = ByteArrayUtils.decodeUnsignedByte(blockDataList, cursor);
                
                // Handle ICD specific error condition (block_len = 0xFF)
                if (blockLen == ERROR_BLOCK_LEN) {
                    System.err.println("[ERROR] Received 0xFF block length (EPS Error condition).");
                    ResetContinuousValue();
                    return null;
                }
        
                // Safety check for array boundaries
                if (cursor + 1 + blockLen > blockDataList.length) {
                    System.err.println("[ERROR] Block length exceeds packet size boundaries.");
                    ResetContinuousValue();
                    return null;
                }
        
                // Accumulate data into the buffer
                byte[] block = Arrays.copyOfRange(blockDataList, cursor + 1, cursor + 1 + blockLen);
                appendExtractedData(block);
                
                cursor += 1 + blockLen;
                expectedOffset += blockLen;
        
                // 4. Completion check and decoding
                // Extract payload length once enough bytes are accumulated for the entry header
                if (payloadLength == -1 && extractData.length >= ENTRY_HEADER_SIZE) {
                    payloadLength = ByteArrayUtils.decodeUnsignedShortLE(extractData, PAYLOAD_LEN_FIELD_POS);
                }
        
                // BUG FIX: Ensure payloadLength is valid (!= -1) before performing size checks
                // to prevent IllegalArgumentException when extractData.length is small.
                if (payloadLength != -1 && extractData.length >= (payloadLength + ENTRY_HEADER_SIZE)) {
                    resultPacket = createYamcsPacket(tmPacket);
                    
                    // Prepare for the next entry (handles multiple entries within a single CSP packet)
                    ResetContinuousValue(); 
                    expectedEntryId = entryId + 1; // Anticipate next sequence ID
                    expectedOffset = 0;
                }
            }
        
            return resultPacket;
        }
        
        // Helper to determine if the current entry process should be reset.
        private boolean shouldResetEntry(long entryId, int offset) {
            return (expectedEntryId == -1) || (entryId != expectedEntryId) || (offset == 0);
        }
        
        // Appends the new data block to the existing extraction buffer.
        private void appendExtractedData(byte[] block) {
            if (extractData == null) {
                extractData = block;
            } else {
                byte[] merged = new byte[extractData.length + block.length];
                System.arraycopy(extractData, 0, merged, 0, extractData.length);
                System.arraycopy(block, 0, merged, extractData.length, block.length);
                extractData = merged;
            }
        }
        
        // Generates a Yamcs TmPacket from the successfully reassembled entry data.
        private TmPacket createYamcsPacket(TmPacket original) {
            final int ENTRY_HEADER_SIZE = 6;
            // Extract only the payload portion (excluding the 6-byte entry header)
            byte[] payload = Arrays.copyOfRange(extractData, ENTRY_HEADER_SIZE, ENTRY_HEADER_SIZE + payloadLength);
            
            // Combine Yamcs header and the extracted payload
            byte[] newPacketData = new byte[header.length + payload.length];
            System.arraycopy(header, 0, newPacketData, 0, header.length);
            System.arraycopy(payload, 0, newPacketData, header.length, payload.length);
            
            System.out.printf("[INFO] Entry reconstruction complete. Created packet size: %d\n", newPacketData.length);
            return new TmPacket(original.getReceptionTime(), newPacketData);
        }
    }

    // Save the generation time setting
    private Map<Integer, TimeSettings> timeSettingsMap = new HashMap<>();

    public Scsat1PacketPreprocessor(String yamcsInstance, YConfiguration config) {
        super(yamcsInstance, config);
        if (!config.containsKey("timesettings")) {
            return;
        }
        // Save the time setting read from yaml for each source ID
        List<YConfiguration> timesettings = config.getConfigList("timesettings");
        for (YConfiguration timesettingConfig : timesettings) {
            int srcId =  timesettingConfig.getInt("sourceId");
            TimeSettings srcTimesetting = new TimeSettings(timesettingConfig);
            timeSettingsMap.put(srcId, srcTimesetting);
        }
    }

    // Sets generation time from source ID, or uses reception time if not found
    public void setGenerationTimeBySourceId(TmPacket tmPacket) {
        byte[] packet = tmPacket.getPacket();
        CspPacket cspHeader = new CspPacket(packet);
        int srcId = cspHeader.getSource();
        TimeSettings settings = timeSettingsMap.get(srcId);
        if (settings == null) {
            tmPacket.setGenerationTime(tmPacket.getReceptionTime());
            return;
        }
        settings.setPacketGenerationTime(tmPacket);
    }

    private byte[] extractData = null;
    private int  payloadLength = -1;
    private long expectedEntryId = -1;
    private int expectedOffset = 0;

    public TmPacket extractPacket(TmPacket tmPacket) {
        ExtractEPSFilePacket extractedParamData = new ExtractEPSFilePacket(tmPacket);
        return extractedParamData.extractedDataFromPacket(tmPacket);
    }

    @Override
    public TmPacket process(TmPacket tmPacket) {
        tmPacket = extractPacket(tmPacket);
        if (tmPacket != null) {
            setGenerationTimeBySourceId(tmPacket);
        }
        return tmPacket;
    }

    @Override
    protected TimeDecoderType getDefaultDecoderType() {
        return TimeDecoderType.FIXED;
    }
}
