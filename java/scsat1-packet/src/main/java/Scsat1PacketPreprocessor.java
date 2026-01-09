package org.yamcs.tctm;

import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;

import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.time.FixedSizeTimeDecoder;
import org.yamcs.time.Float64TimeDecoder;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.time.TimeDecoder;
import org.yamcs.tctm.csp.CspPacket;
import org.yamcs.utils.ByteArrayUtils;

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
            if (packet.length < commandIdOffset + commandIdLength){
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

    @Override
    public TmPacket process(TmPacket tmPacket) {
        setGenerationTimeBySourceId(tmPacket);
        return tmPacket;
    }

    @Override
    protected TimeDecoderType getDefaultDecoderType() {
        return TimeDecoderType.FIXED;
    }
}
