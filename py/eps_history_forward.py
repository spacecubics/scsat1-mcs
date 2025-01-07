#!/usr/bin/python3
"""
This program subscribes to MQTT topics to receive data packets from an EPS (Electrical Power System),
and transfers the data to Yamcs if specified.
The command format follows the EPS debug console.
Refer to the "Remote CLI" chapter in the EPS ICD for details.
"""

import struct
import os
import sys
import json
import logging
import argparse
import time
import threading
import paho.mqtt.client as mqtt

# Constants
LOG_DIR = "/home/yamcs/eps_history"

EPS_CSP_ADDR = 4
EPS_CSP_PORT = 10
GND_CSP_ADDR = 1
GND_CSP_PORT = 30
SRC_CSP_ADDR = 31

DL_CMD_ID = 2  # Command ID for downloading files

CSP_HEADER_SIZE = 4

# Yamcs Configuration
YAMCS_HOST = "localhost"
YAMCS_PORT = 52005

# CSP Header Bit Offsets (CubeSat Space Protocol)
CSP_ADDR_BITS = 5
CSP_PORT_BITS = 6
OTHER_BITS = 8

FILE_DATA_HEADER_SIZE = 12
ENTRY_HEADER_SIZE = 6

# MQTT
NORAD_ID =
USERNAME =
PASSWORD =

message_received_event = threading.Event()

PRIORITY_OFFSET = (CSP_ADDR_BITS * 2) + (CSP_PORT_BITS * 2) + OTHER_BITS
SRC_CSP_ADDR_OFFSET = (CSP_ADDR_BITS) + (CSP_PORT_BITS * 2) + OTHER_BITS
DST_CSP_ADDR_OFFSET = (CSP_PORT_BITS * 2) + OTHER_BITS
DST_CSP_PORT_OFFSET = (CSP_PORT_BITS) + OTHER_BITS
SRC_CSP_PORT_OFFSET = OTHER_BITS


def setup_logging(verbose):
    """Sets up logging based on the verbosity flag."""
    level = logging.DEBUG if verbose else logging.INFO
    logging.basicConfig(level=level, format='%(asctime)s - %(levelname)s - %(message)s')


def create_csp_header(dst_csp_addr, dst_csp_port, src_csp_addr, src_csp_port=GND_CSP_PORT, verbose=False):
    """Creates a CSP header with the given addresses and ports."""
    header = (2 << PRIORITY_OFFSET) | \
             (((1 << CSP_ADDR_BITS) - 1) & src_csp_addr) << SRC_CSP_ADDR_OFFSET | \
             (((1 << CSP_ADDR_BITS) - 1) & dst_csp_addr) << DST_CSP_ADDR_OFFSET | \
             (((1 << CSP_PORT_BITS) - 1) & dst_csp_port) << DST_CSP_PORT_OFFSET | \
             (((1 << CSP_PORT_BITS) - 1) & src_csp_port) << SRC_CSP_PORT_OFFSET

    logging.debug(f"CSP Header -> Src Addr: {src_csp_addr}, Dst Addr: {dst_csp_addr}, "
                  f"Src Port: {src_csp_port}, Dst Port: {dst_csp_port}")
    logging.debug(f"CSP Header: 0x{header:X}")

    return header.to_bytes(4, 'big')


def parse_csp_header(header):
    """Parses and displays CSP header information."""
    src_csp_addr = (header >> SRC_CSP_ADDR_OFFSET) & 0x1F
    dst_csp_addr = (header >> DST_CSP_ADDR_OFFSET) & 0x1F
    dst_csp_port = (header >> DST_CSP_PORT_OFFSET) & 0x3F
    src_csp_port = (header >> SRC_CSP_PORT_OFFSET) & 0x3F

    logging.debug(f"Parsed CSP Header -> Src Addr: {src_csp_addr}, Dst Addr: {dst_csp_addr}, "
                  f"Src Port: {src_csp_port}, Dst Port: {dst_csp_port}")

    return src_csp_addr, src_csp_port, dst_csp_addr, dst_csp_port


def json_to_binary(json_string):
    try:
        data = json.loads(json_string)
        payload_hex = data.get("payload", "")
        if not payload_hex:
            raise ValueError("The 'payload' attribute is missing or empty.")
        payload_bytes = bytes(int(byte, 16) for byte in payload_hex.split())
        return payload_bytes
    except (ValueError, KeyError, json.JSONDecodeError) as e:
        logging.error(f"Error: {e}")
        return None


def transfer_entry_to_yamcs(yamcs_host, yamcs_port, file_id, entry_data, entry_id):
    """Transfers the received entry data to Yamcs via UDP."""
    import socket

    header = create_csp_header(GND_CSP_ADDR, GND_CSP_PORT, SRC_CSP_ADDR, EPS_CSP_PORT)
    data_to_send = struct.pack("<4sBBI", header, DL_CMD_ID, file_id, entry_id)
    data_to_send += entry_data

    # Set up UDP socket and send the modified data
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.sendto(data_to_send, (yamcs_host, yamcs_port))
    sock.close()

    logging.info(f"Data has been sent to {yamcs_host}:{yamcs_port}.")


def on_message(client, userdata, msg):
    """Callback for when a message is received from the MQTT broker."""
    try:
        json_string = msg.payload.decode('utf-8')
        logging.debug(f"Received MQTT message: {json_string}")
        packet = json_to_binary(json_string)

        if not packet or len(packet) < FILE_DATA_HEADER_SIZE:
            logging.debug("No file data received or packet is too short.")
            return

        src_csp_addr, src_csp_port, _, _ = parse_csp_header(
            int.from_bytes(packet[:CSP_HEADER_SIZE], 'big'))
        if src_csp_addr == EPS_CSP_ADDR and src_csp_port == EPS_CSP_PORT:
            logging.info("Target CSP packet received.")
            userdata.append(packet)
            message_received_event.set()
    except Exception as e:
        logging.error(f"Error processing MQTT message: {e}")


def get_packet_param(packet):
    _, cmd_id, file_id, entry_id, offset = struct.unpack("<IBBIH", packet[:FILE_DATA_HEADER_SIZE])

    if cmd_id != DL_CMD_ID:
        return 0, 0, 0

    return file_id, entry_id, offset


def handle_one_entry(file_id, entry_id, entry_data):
    filename = f"{LOG_DIR}/entry_{file_id}_{entry_id}.bin"
    with open(filename, "wb") as f:
        f.write(entry_data)

    logging.info(f"Entry ID {entry_id} data saved to {filename}.")

#   Note: When forwarding file data to Ymacs is necessary, enable two lines below:
#
#    transfer_entry_to_yamcs(YAMCS_HOST, YAMCS_PORT, file_id, entry_data, entry_id)
#    time.sleep(0.01)


def process_file_download_data(packets):
    """Processes the received MQTT data packets."""
    entry_data = b''
    entry_len = 0

    while True:

        while len(packets) == 0:
            # wait for new packet
            message_received_event.wait()

        packet = packets.pop(0)
        file_id, entry_id, offset = get_packet_param(packet)
        logging.info(f"packet received, file: {file_id}, entry: {entry_id}, offset: {offset}")
        if file_id == 0:  # not file download packet
            continue

        if offset == 0:
            if len(entry_data):
                logging.info(f"last data aborted, remain size: {len(entry_data)}")
            # new downloading
            entry_data = b''
            entry_len = 0

        elif offset != len(entry_data):  # something wrong
            logging.info(f"offset is not match, offset: {offset}, len: {len(entry_data)}")
            entry_data = b''
            entry_len = 0
            continue

        data_remain = packet[FILE_DATA_HEADER_SIZE:]

        while len(data_remain):
            data_len = data_remain[0]
            if data_len == 0xff:
                logging.error(f"Error found, error code: {data_remain[1]}, entry: {entry_id}")
                data_remain = data_remain[2:]
                entry_id += 1
                continue

            entry_data += data_remain[1:data_len + 1]
            data_remain = data_remain[data_len + 1:]

            if entry_len == 0 and len(entry_data) >= ENTRY_HEADER_SIZE:
                _, entry_len = struct.unpack("<IH", entry_data[:ENTRY_HEADER_SIZE])
                logging.info(f"entry len: {entry_len}")

            if len(entry_data) >= entry_len + ENTRY_HEADER_SIZE:
                handle_one_entry(file_id, entry_id, entry_data)
                entry_data = b''
                entry_len = 0
                entry_id += 1
                logging.info(f"Next entry: {entry_id}, remain len: {len(data_remain)}")


def main():
    parser = argparse.ArgumentParser(description="Process EPS data packets.")
    parser.add_argument("--verbose", action="store_true", help="Enable verbose logging")
    parser.add_argument("--mqtt-host", default="mqtt.leaf.space", help="MQTT broker host")
    parser.add_argument("--mqtt-port", type=int, default=1883, help="MQTT broker port")
    parser.add_argument("--mqtt-topic", default=f"{NORAD_ID}/downlink", help="MQTT topic to subscribe to")
    args = parser.parse_args()

    setup_logging(args.verbose)

    logging.info("Starting MQTT client...")

    packets = []
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
    client.user_data_set(packets)

    logging.info(f"user: {USERNAME}, pass: {PASSWORD}, mqtt host: {args.mqtt_host}, port: {args.mqtt_port}, topic: {args.mqtt_topic}")
    client.on_message = on_message
    client.username_pw_set(USERNAME, PASSWORD)

    client.connect(args.mqtt_host, args.mqtt_port, 60)
    client.subscribe(args.mqtt_topic)

    client.loop_start()

    if not os.path.exists(LOG_DIR):
        os.makedirs(LOG_DIR)

    try:
        process_file_download_data(packets)
    except KeyboardInterrupt:
        logging.info("\nTerminating the program.")
    except Exception as e:
        logging.error(f"Error: {e}")
    finally:
        client.loop_stop()
        client.disconnect()


if __name__ == "__main__":
    main()
