#!/usr/bin/env python3
import sys
import binascii
import socket
import argparse
import time
import struct
import numpy as np
import pandas as pd
from dataclasses import dataclass
from threading import Thread
from time import sleep
from ruamel.yaml import YAML


parser = argparse.ArgumentParser(description='Yamcs Simulator')
parser.add_argument('--test-tm', required=True, help='Telemetry name to test')
parser.add_argument('--file', type=argparse.FileType('r'), required=True, help='YAML file with telemetry data')
parser.add_argument('--mod-file', default=None, help='YAML file with modified simulation mode')

# telemetry
parser.add_argument('--tm-host',    type=str, default='127.0.0.1', help='TM host')
parser.add_argument('--tm-port',    type=int, default=10015,       help='TM port')
parser.add_argument('-r', '--rate', type=int, default=1,           help='TM playback rage in Hz')

# telecommand
parser.add_argument('--tc-host', type=str, default='127.0.0.1', help='TC host')
parser.add_argument('--tc-port', type=int, default=10025,      help='TC port')

args = vars(parser.parse_args())
TEST_TM = args['test_tm']
TM_FILE = args['file']
MOD_FILE = args['mod_file']

# telemetry
TM_SEND_ADDRESS = args['tm_host']
TM_SEND_PORT = args['tm_port']
RATE = args['rate']

# telecommand
TC_RECEIVE_ADDRESS = args['tc_host']
TC_RECEIVE_PORT = args['tc_port']


class Net():
    def __init__(self):
        self.dst = "YAMCS"
        self.src = None
        self.sport = 0
        self.dport = 0
        self.command_id = None
        self.endian = False
        self.parameters = None
        self.srs3_reserved = 99
        self.srs3_id = 99


class Simulator():
    def __init__(self, rate):
        self.tm_counter = 0
        self.tc_counter = 0
        self.tm_thread = None
        self.tc_thread = None
        self.last_tc = None
        self.rate = rate

    def start(self):
        self.tm_thread = Thread(target=send_tm, args=(self,))
        self.tm_thread.daemon = True
        self.tm_thread.start()
        self.tc_thread = Thread(target=receive_tc, args=(self,))
        self.tc_thread.daemon = True
        self.tc_thread.start()

    def print_status(self):
        cmdhex = None
        if self.last_tc:
            cmdhex = binascii.hexlify(self.last_tc).decode('ascii')
        return 'Sent: {} packets. Received: {} commands. Last command: {}'.format(
                        self.tm_counter, self.tc_counter, cmdhex)


def send_tm(simulator):
    tm_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    simulator.tm_counter = 1
    header = set_header("YAMCS", net.src, net.sport, net.dport)
    t = 0
    while True:
        packet = header + set_data(t)
        tm_socket.sendto(packet, (TM_SEND_ADDRESS, TM_SEND_PORT))
        simulator.tm_counter += 1
        t += 1
        sleep(1 / simulator.rate)


def receive_tc(simulator):
    tc_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    tc_socket.bind((TC_RECEIVE_ADDRESS, TC_RECEIVE_PORT))
    while True:
        data, _ = tc_socket.recvfrom(4096)
        simulator.last_tc = data
        simulator.tc_counter += 1


def search_data(data):
    for p in data["headers"]:
        if p["name"] == "common_container":
            for c in p["conditions"]:
                if c["name"] == "../csp_src":
                    net.src = c["val"]
    if net.src is not None:
        for p in data["containers"]:
            if p["name"] == TEST_TM:
                net.endian = p.get("endian", False)
                for c in p["conditions"]:
                    if c["name"] == "../csp_sport":
                        net.sport = c["val"]
                    elif c["name"] == "../csp_dport":
                        net.dport = c["val"]
                    elif c["name"] == f"{net.src}/command_id" or c["name"] == f"{net.src}/telemetry_id":
                        net.command_id = c["val"]
                    net.parameters = p["parameters"]
        if net.src == "SRS3" and net.sport == 0:
            for p in data["headers"]:
                if p["name"] == "srs3_header":
                    for c in p["conditions"]:
                        if c["name"] == "../csp_sport":
                            net.sport = c["val"]
            for p in data["containers"]:
                if p["name"] == TEST_TM:
                    for c in p["conditions"]:
                        if c["name"] == "SRS3/srs3_id":
                            net.srs3_id = c["val"]
                        elif c["name"] == "SRS3/srs3_reserved":
                            net.srs3_reserved = c["val"]
    else:
        print("Not Find csp_src")
        exit(1)


def set_header(dst, src, sport, dport):
    @dataclass
    class CSP_header:
        value:  int = 0
        bit:    int = 1
        sign:  bool = False
    csp_pri = CSP_header(bit=2)
    csp_src = CSP_header(bit=5)
    csp_dst = CSP_header(value=6, bit=5)
    csp_dport = CSP_header(value=19, bit=6)
    csp_sport = CSP_header(bit=6)
    csp_reserved = CSP_header(bit=4)
    csp_hmac = CSP_header()
    csp_xtea = CSP_header()
    csp_rdp = CSP_header(1)
    csp_crc = CSP_header()
    system_dict = {
        "YAMCS": 1,
        "SRS3":  2,
        "EPS":   4,
        "MAIN":  8,
        "ADCS": 16,
        "ZERO": 24,
        "PICO": 26
    }
    csp_dst.value = system_dict[dst]
    csp_src.value = system_dict[src]
    csp_sport.value = sport
    csp_dport.value = dport
    header_list = [
        csp_pri, csp_src, csp_dst, csp_dport, csp_sport,
        csp_reserved, csp_hmac, csp_xtea, csp_rdp, csp_crc
    ]
    hsum = 0
    for x in header_list:
        hsum = (hsum << x.bit) | x.value
    b_header = hsum.to_bytes(4, byteorder='big')
    return bytearray(b_header)


def set_data(t):
    packet_data = parameters_data(net.parameters, net.endian, t)
    if net.src == "SRS3" and net.sport == 19:
        srs3 = bytearray(net.srs3_reserved.to_bytes(2, byteorder='big')) \
                + bytearray(net.srs3_id.to_bytes(2, byteorder='big'))
        b_id = bytearray(1) + srs3
    elif net.command_id is None:
        return packet_data
    else:
        b_id = bytearray(net.command_id.to_bytes(1, byteorder='big'))
    return b_id + packet_data


def create_y(mode, t, a, f):
    if mode == "Word":
        return "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    if mode == "Wave":
        return a * np.sin(2 * np.pi * f * (t/10) + 0)
    if mode == "Wave_abs":
        return abs(a * np.sin(2 * np.pi * f * (t/10) + 0))
    if mode == "Liner":
        if a*t < sys.float_info.min:
            y = sys.float_info.min
        elif a*t > sys.float_info.max:
            y = sys.float_info.max
        else:
            y = a*t
        return y
    if mode == "Time":
        return time.time()
    if mode == "Step":
        return (t // f) % a
    print(f"Not defined: {mode}")
    exit(1)


def create_data(mode, t, p_type, p_bit, signed, a=10, f=0.5):
    if p_type == "binary":
        p_byte = p_bit // 8
        max_value = 2 ** (p_byte * 8) - 1
        t = t % (max_value + 1)
        return t
    y = create_y(mode, t, a, f)
    if mode == "Word":
        p_byte = p_bit // 8
        return (y * ((p_byte // len(y)) + 1))[:p_byte]
    y = np.double(y)
    if p_type == "int":
        if signed:
            y_max = 2 ** (p_bit-1) - 1
            y_min = (2 ** (p_bit-1) - 1) * (-1)
            if y > y_max:
                y = y % y_max
            elif y < y_min:
                y = y_min
            y = np.int64(y)
        else:
            y_max = 2 ** p_bit - 1
            y_min = 0
            if y > y_max:
                y = y % y_max
            elif y < y_min:
                y = y_min
            y = np.uint64(y)
    return y


def parameters_data(parameters, endian, t):
    data = [
        ["c", "char", 1, False],
        ["b", "int", 1, True],
        ["B", "int", 1, False],
        ["h", "int", 2, True],
        ["H", "int", 2, False],
        ["i", "int", 4, True],
        ["I", "int", 4, False],
        ["l", "long", 4, True],
        ["L", "long", 4, False],
        ["q", "int", 8, True],
        ["Q", "int", 8, False],
        ["f", "float", 4, False],
        ["d", "double", 8, False],
        ["?", "bool", 1, False],
        ["x", "padding", None, False],
        ["s", "char", None, False],
        ["p", "char", None, False],
        ["p", "int", None, False]
    ]
    df = pd.DataFrame(data, columns=['format', 'type', 'byte', 'signed'])
    packet = bytearray()
    b_data = b''
    b_int = 0
    b_bitlen = 0
    # True: little-endian, False: big-endian
    en = '<' if endian else '>'
    for p in parameters:
        p_type = p.get("type", "int")
        p_bit = p.get("bit", 16)
        p_byte = p_bit // 8
        p_simulate = p.get("simulate", "Liner")
        p_signed = p.get("signed", False)
        p_a = p.get("a", 10)
        p_f = p.get("f", 0.5)
        # Fill offset with 0
        if p.get("offset", 0) != 0:
            packet += bytearray(b_data) + bytearray(p.get("offset", 0)//8)
            b_data = b''
        # Creating parameter data
        if p_type == "binary":
            t = create_data(p_simulate, t, p_type, p_bit, p_signed)
            packet += bytearray(b_data) + bytearray(t.to_bytes(p_byte, byteorder='little' if endian else 'big'))
            b_data = b''
        elif p_type == "int" or p_type == "long":
            y = create_data(p_simulate, t, p_type, p_bit, p_signed, a=p_a, f=p_f)
            if p_bit < 8:
                b_int = b_int << p_bit | y
                b_bitlen = b_bitlen + p_bit
                if b_bitlen == 8:
                    b_data += struct.pack(
                        f'{en}{df.query(f'type=="int" and byte==1 and signed==False').iat[0, 0]}',
                        b_int
                    )
                    b_bitlen = b_int = 0
            else:
                b_data += struct.pack(
                        f'{en}{df.query(f'type=="{p_type}" and byte=={p_byte} and signed=={p_signed}').iat[0, 0]}',
                        y
                    )
        elif p_type == "string":
            packet += bytearray(b_data) + bytearray(create_data("Word", t, p_type, p_bit, p_signed).encode())
            b_data = b''
        else:
            b_data += struct.pack(
                    f'{en}{df.query(f'type=="{p_type}"').iat[0, 0]}',
                    create_data(p_simulate, t, p_type, p_bit, p_signed, a=p_a, f=p_f)
                )
    packet += bytearray(b_data)
    return packet


def main():
    yaml = YAML()
    with TM_FILE as file:
        data = yaml.load(file)
    if MOD_FILE:
        with open(MOD_FILE, encoding='utf-8')as f:
            mod_data = yaml.load(f)
        search_data(data | mod_data)
    else:
        search_data(data)
    simulator = Simulator(RATE)
    simulator.start()
    sys.stdout.write('Using playback rate of ' + str(RATE) + 'Hz, ')
    sys.stdout.write('TM host=' + str(TM_SEND_ADDRESS) + ', TM port=' + str(TM_SEND_PORT) + ', ')
    sys.stdout.write('TC host=' + str(TC_RECEIVE_ADDRESS) + ', TC port=' + str(TC_RECEIVE_PORT) + '\r\n')
    try:
        prev_status = None
        while True:
            status = simulator.print_status()
            if status != prev_status:
                # Using '\r' to overwrite the output
                sys.stdout.write('\r')
                sys.stdout.write(status)
                sys.stdout.flush()
                prev_status = status
            sleep(0.5)
    except KeyboardInterrupt:
        sys.stdout.write('\n')
        sys.stdout.flush()


if __name__ == '__main__':
    net = Net()
    main()
