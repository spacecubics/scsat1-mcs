#!/usr/bin/python

import argparse
import struct
import paho.mqtt.client as mqtt
import socket
import threading
import logging
import json

NORAD_ID =
USERNAME =
PASSWORD =

logging.basicConfig(level=logging.INFO, format='[%(asctime)s] %(levelname)s: %(message)s')


def mqtt_to_udp(mqtt_host, mqtt_port, username, password, sub_topic, udp_host, udp_send_port, gs, stop_event):
    def on_connect(client, userdata, flags, reason_code, properties):
        if reason_code != 0:
            logging.error(f"Failed to connect to MQTT broker, {reason_code}")
        else:
            logging.info(f"Connected to MQTT broker at {mqtt_host}:{mqtt_port}, subscribing to topic '{sub_topic}'")

    def on_disconnect(client, userdata, flags, reason_code, properties):
        logging.info(f"on_disconnect() : {reason_code}")

    def on_message(client, userdata, message):
        data = json_to_binary(message.payload)
        src_addr, _, dst_addr, _ = parse_csp_header(data)

        logging.info(f"Received message from MQTT topic '{message.topic}': {data}, csp src: {src_addr}, dst: {dst_addr}")
        if gs and src_addr != 1:
            return
        if not gs and dst_addr != 1:
            return

        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp_socket:
            udp_socket.sendto(data, (udp_host, udp_send_port))
            logging.info(f"Forwarded message to UDP {udp_host}:{udp_send_port}")

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
    client.username_pw_set(username, password)
    client.on_connect = on_connect
    client.on_disconnect = on_disconnect
    client.on_message = on_message

    client.connect(mqtt_host, mqtt_port, keepalive=60)
    client.subscribe(sub_topic)

    while not stop_event.is_set():
        client.loop(timeout=1.0)

    client.disconnect()


def udp_to_mqtt(mqtt_host, mqtt_port, username, password, pub_topic, udp_recv_port, stop_event):
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp_socket:
        udp_socket.bind(("0.0.0.0", udp_recv_port))
        logging.info(f"Listening for UDP packets on any:{udp_recv_port}")

        client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
        client.username_pw_set(username, password)
        client.connect(mqtt_host, mqtt_port, keepalive=60)
        client.loop_start()

        while not stop_event.is_set():
            udp_socket.settimeout(1.0)
            try:
                data, addr = udp_socket.recvfrom(1024)
                logging.info(f"Received UDP packet from {addr}: {data}")
                client.publish(pub_topic, data)
                logging.info(f"Published message to MQTT topic '{pub_topic}'")
            except socket.timeout:
                continue

        client.loop_stop()
        client.disconnect()


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


def parse_csp_header(data):
    if len(data) < 4:
        raise ValueError("Data is too short to contain a valid CSP header.")
    csp_header = struct.unpack(">I", data[:4])[0]
    src_addr = (csp_header >> 25) & 0x1F
    dest_addr = (csp_header >> 20) & 0x1F
    src_port = (csp_header >> 14) & 0x3F
    dest_port = (csp_header >> 8) & 0x3F
    return src_addr, src_port, dest_addr, dest_port


def main():
    parser = argparse.ArgumentParser(description="MQTT to UDP and UDP to MQTT bridge with default authentication")
    parser.add_argument("--mqtt_host", default="mqtt.leaf.space", help="MQTT broker hostname")
    parser.add_argument("--mqtt_port", type=int, default=1883, help="MQTT broker port")
    parser.add_argument("--username", default=USERNAME, help="MQTT username")
    parser.add_argument("--password", default=PASSWORD, help="MQTT password")
    parser.add_argument("--udp_send_host", default="localhost", help="UDP hostname")
    parser.add_argument("--udp_send_port", type=int, default=52004, help="UDP send port")
    parser.add_argument("--udp_recv_port", type=int, default=52002, help="UDP receive port")
    parser.add_argument("--sub_topic", default=f"{NORAD_ID}/downlink", help="MQTT topic to subscribe")
    parser.add_argument("--pub_topic", default=f"{NORAD_ID}/uplink", help="MQTT topic to publish")
    parser.add_argument("--gs", action="store_true", help="GS simulation mode")

    args = parser.parse_args()

    stop_event = threading.Event()

    mqtt_thread = threading.Thread(target=mqtt_to_udp, args=(args.mqtt_host, args.mqtt_port, args.username, args.password, args.sub_topic, args.udp_send_host, args.udp_send_port, args.gs, stop_event))
    udp_thread = threading.Thread(target=udp_to_mqtt, args=(args.mqtt_host, args.mqtt_port, args.username, args.password, args.pub_topic, args.udp_recv_port, stop_event))

    mqtt_thread.start()
    udp_thread.start()

    try:
        while mqtt_thread.is_alive() or udp_thread.is_alive():
            mqtt_thread.join(timeout=1.0)
            udp_thread.join(timeout=1.0)
    except KeyboardInterrupt:
        logging.info("Interrupted by user, shutting down...")
        stop_event.set()
        mqtt_thread.join()
        udp_thread.join()
        logging.info("All threads have been terminated.")


if __name__ == "__main__":
    main()
