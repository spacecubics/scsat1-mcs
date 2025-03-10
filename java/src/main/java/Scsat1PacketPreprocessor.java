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


public class Scsat1PacketPreprocessor extends AbstractPacketPreprocessor {
    static class TmData {
        public int portOffset;
        public int portLength;
        public int portNum;
        public int commandId;

        public TmData(YConfiguration config) {
            this.portOffset = config.getInt("portOffset");
            this.portLength = config.getInt("portLength");
            this.portNum = config.getInt("portNum");
            this.commandId = config.getInt("commandId");
        }
    }

    static class TimeSettings {
        final int timestampOffset;
        final ByteOrder byteOrder;
        final TimeDecoder timeDecoder;
        public List<TmData> tmDataList;

        TimeSettings(YConfiguration config) {
            this.timestampOffset = config.getInt("timestampOffset", -1);

            // Byte order setting (default is BIG_ENDIAN)
            String byteOrderStr = config.getString("byteOrder", "BIG_ENDIAN");
            this.byteOrder = "LITTLE_ENDIAN".equalsIgnoreCase(byteOrderStr) ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;

            if (config.containsKey("tmDataList")) {
                this.tmDataList =config.getConfigList("tmDataList").stream().map(tmConfig -> new TmData(tmConfig)).collect(Collectors.toList());
            }else {
                this.tmDataList = List.of();
            }

            // Time Encoding setting
            if (config.containsKey("timeEncoding")) {
                YConfiguration timeEncodingConfig = config.getConfig("timeEncoding");
                int size = timeEncodingConfig.getInt("size", 8);
                long multiplier = timeEncodingConfig.getLong("multiplier", 1);
                String type = timeEncodingConfig.getString("type", "FIXED");
                if ("FLOAT64".equals(type)) {
                    this.timeDecoder = new Float64TimeDecoder(this.byteOrder);
                } else {
                    this.timeDecoder = new FixedSizeTimeDecoder(this.byteOrder, size, multiplier);
                }
            } else {
                this.timeDecoder = new FixedSizeTimeDecoder(this.byteOrder, 8, 1);
            }
        }
    }

    // Where to read the command ID from the packet
    final int commandIdOffset = 32;
    final int commandIdLength = 8;
    // Where to read the CSP source port number from the packet
    final int srcLength;
    final int srcOffset;

    // Save the generation time setting
    private final Map<String, TimeSettings> timeSettingsMap;

    public Scsat1PacketPreprocessor(String yamcsInstance, YConfiguration config) {
        super(yamcsInstance, config);
        srcOffset = config.getInt("srcOffset");
        srcLength = config.getInt("srcLength");

        // Save the time setting read from yaml by key (e.g. EPS)
        this.timeSettingsMap = new HashMap<>();
        if (config.containsKey("timesettings")) {
            Map<String, Object> settingsMap = config.getMap("timesettings");
            this.timeSettingsMap.put("default", new TimeSettings(YConfiguration.wrap(new HashMap<>())));
            for (Map.Entry<String, Object> entry : settingsMap.entrySet()) {
                String key = entry.getKey();
                this.timeSettingsMap.put(key, new TimeSettings(YConfiguration.wrap((Map<String, Object>) entry.getValue())));
            }
        }
        if (timeDecoder == null) {
            this.timeDecoder = new FixedSizeTimeDecoder(byteOrder, 8, 1);
            this.timeEpoch = TimeEpochs.UNIX;
        }

    }

    public class ReadPacketTimeMethod {
        private static final int EPS = 4;
        private static final int MAIN = 8;
        private static final int ADCS = 16;
        public void setRealtimePacketTimeParam(TmPacket tmPacket) {
            byte[] packet = tmPacket.getPacket();
            // Get the CSP source port number from the packet
            int src = PacketUtils.getPacketValue(packet, srcOffset, srcLength);
            // Set the time settings for each system
            TimeSettings settings;
            TimeSettings systemSettings;
            settings = timeSettingsMap.get("default");
            switch (src) {
                case EPS:
                    settings = setSystemSetting(settings, packet, "EPS");
                    break;
                case MAIN:
                    settings = setSystemSetting(settings, packet, "MAIN");
                    break;
                case ADCS:
                    settings = setSystemSetting(settings, packet, "ADCS");
                    break;
                default:
                    break;
            }
            if (settings != null) {
                if (settings.timestampOffset < 0) {
                    tmPacket.setGenerationTime(TimeEncoding.getWallclockTime());
                } else {
                    Scsat1PacketPreprocessor.this.timeDecoder = settings.timeDecoder;
                    setRealtimePacketTime(tmPacket, settings.timestampOffset);
                }
            }
        }
    }

    public TimeSettings setSystemSetting(TimeSettings settings, byte[] packet, String systemSrc) {
        TimeSettings systemSettings = timeSettingsMap.get(systemSrc);
        for (TmData data : systemSettings.tmDataList) {
            int port = PacketUtils.getPacketValue(packet, data.portOffset, data.portLength);
            int commandId = PacketUtils.getPacketValue(packet, commandIdOffset, commandIdLength);
            if (port == data.portNum && commandId == data.commandId){
                settings = systemSettings;
                break;
            }
        }
        return settings;
    }

    public class PacketUtils {
        // Method to retrieve data from a specified offset and length
        public static int getPacketValue(byte[] packet, int dataOffset, int dataLength) {
            if (packet.length * 8 >= dataOffset + dataLength){
                int byteIndex = dataOffset / 8;
                int bitStart = dataOffset % 8;
                int value = 0;
                int bitsRead = 0;

                while (bitsRead < dataLength) {
                    int bitsAvailable = Math.min(8 - bitStart, dataLength - bitsRead);
                    int extractedBits = (packet[byteIndex] >> (8 - bitStart - bitsAvailable)) & ((1 << bitsAvailable) - 1);
                    value = (value << bitsAvailable) | extractedBits;
                    bitsRead += bitsAvailable;
                    byteIndex++;
                    bitStart = 0;
                }
                return value;
            }else{
                return -1;
            }
        }
    }

    @Override
    public TmPacket process(TmPacket tmPacket) {
        byte[] packet = tmPacket.getPacket();
        ReadPacketTimeMethod rptm = new ReadPacketTimeMethod();
        rptm.setRealtimePacketTimeParam(tmPacket);
        return tmPacket;
    }

    @Override
    protected TimeDecoderType getDefaultDecoderType() {
        return TimeDecoderType.FIXED;
    }
}
