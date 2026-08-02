#!/usr/bin/env python

import sys
import argparse
import logging
from enum import Enum
from ruamel.yaml import YAML
from yamcs.pymdb import csp
from yamcs.pymdb.systems import System
from yamcs.pymdb.commands import (
    ArgumentEntry,
    Command,
    IntegerArgument,
    BinaryArgument,
    StringArgument,
    EnumeratedArgument,
)
from yamcs.pymdb.containers import Container, ParameterEntry
from yamcs.pymdb.parameters import (
    IntegerParameter,
    FloatParameter,
    StringParameter,
    BinaryParameter
)
from yamcs.pymdb.encodings import (
    IntegerEncoding,
    FloatEncoding,
    StringEncoding,
    BinaryEncoding,
    IntegerEncodingScheme,
    FloatEncodingScheme,
)
from yamcs.pymdb.expressions import (
    all_of,
    eq,
    ne
)


HEADER_OUT = 'mdb/scsat1_header.xml'


class Subsystem(Enum):
    YAMCS = 1
    SRS3 = 2
    EPS = 4
    MAIN = 8
    ADCS = 16
    ZERO = 24
    PICO = 26


def set_encoding(param, endian):
    param_type = param.get("type", "int")
    if param_type == "int":
        if param.get("signed", False) is False:
            scheme = IntegerEncodingScheme.UNSIGNED
        else:
            scheme = IntegerEncodingScheme.TWOS_COMPLEMENT
        enc = IntegerEncoding(
            bits=param.get("bit", 16),
            little_endian=endian,
            scheme=scheme,
        )
    elif param_type == "double" or param_type == "float":
        enc = FloatEncoding(
            bits=param.get("bit", 64),
            little_endian=endian,
            scheme=FloatEncodingScheme.IEEE754_1985
        )
    elif param_type == "string":
        enc = StringEncoding(
            bits=param.get("bit", 256),
        )
    elif param_type == "binary":
        enc = BinaryEncoding(
            bits=param.get("bit", 48),
        )
    elif param_type == "enum":
        if param.get("signed", False) is False:
            scheme = IntegerEncodingScheme.UNSIGNED
        else:
            scheme = IntegerEncodingScheme.TWOS_COMPLEMENT
        enc = IntegerEncoding(
            bits=param.get("bit", 8),
            little_endian=endian,
            scheme=scheme,
        )
    else:
        print(f"encoding error: {param_type} is not defined.")
    return enc


def set_conditions(cont):
    try:
        condition_data = cont["conditions"]
        all_exp = []
        for cond in condition_data:
            comp_mode = cond.get("comparison", "equal")
            if comp_mode == "equal":
                exp = eq(cond["name"], cond["val"], calibrated=True)
            if comp_mode == "not_equal":
                exp = ne(cond["name"], cond["val"], calibrated=True)
            all_exp.append(exp)
        if len(condition_data) == 1:
            return all_exp[0]
        else:
            return all_of(*all_exp)
    except KeyError:
        return None


def create_header(system):
    csp.add_csp_header(system, ids=Subsystem)


# subsystem header
def create_header_sub(system, data):
    for cont in data:
        Container(
            system=system,
            name=cont["name"],
            base=cont["base"],
            abstract=True,
            entries=set_entries_list(system, cont),
            condition=set_conditions(cont),
        )


# Telemetry
def set_entries_list(system, cont):
    try:
        param_data = cont["parameters"]
        entries_list = []
        for param in param_data:
            if param.get("type", "int") == "int":
                tm = IntegerParameter(
                    system=system,
                    name=param["name"],
                    signed=param.get("signed", False),
                    encoding=set_encoding(param, cont.get("endian", False)),
                )
            elif param.get("type", "int") == "double" or param.get("type", "int") == "float":
                tm = FloatParameter(
                    system=system,
                    name=param["name"],
                    bits=param.get("bit", 64),
                    encoding=set_encoding(param, cont.get("endian", False)),
                )
            elif param.get("type", "int") == "string":
                tm = StringParameter(
                    system=system,
                    name=param["name"],
                    encoding=set_encoding(param, cont.get("endian", False)),
                )
            elif param.get("type", "int") == "binary":
                tm = BinaryParameter(
                    system=system,
                    name=param["name"],
                    encoding=set_encoding(param, cont.get("endian", False)),
                )
            else:
                print("set parameter error: "+param["name"]+"\n")
            entries_list.append(ParameterEntry(tm, offset=param.get("offset", 0)))
        return entries_list
    except KeyError:
        return None


def set_telemetry(system, data, abstract=False):
    for cont in data:
        try:
            base = cont["base"]
            Container(
                system=system,
                name=cont["name"],
                base=base,
                entries=set_entries_list(system, cont),
                abstract=abstract,
                condition=set_conditions(cont),
            )
        except KeyError:
            print(f'Error: Missing required "base" field in container: {cont.get("name", "<unknown>")}')


def create_tm(system, yaml, yaml_file):
    try:
        with yaml_file as file:
            data = yaml.load(file)
        create_header_sub(system, data["headers"])
        set_telemetry(system, data["containers"])
    except OSError as e:
        print("Telemetry was not created.")
        print(e)


# Command
def set_command(system, csp_header, base, tc_data):
    for i in tc_data:
        for commands in tc_data[i]:
            for tc in commands["commands"]:
                arg_list = []
                entries_list = []
                if tc.get("arguments", None) is not None:
                    for arguments in tc["arguments"]:
                        if arguments.get("type", "int") == "int":
                            argument = IntegerArgument(
                                name=arguments["name"],
                                signed=arguments.get("signed", False),
                                encoding=set_encoding(arguments, tc.get("endian", False)),
                                default=arguments.get("val", None),
                            )
                        elif arguments.get("type", "int") == "binary":
                            argument = BinaryArgument(
                                name=arguments["name"],
                                encoding=set_encoding(arguments, tc.get("endian", False)),
                                default=arguments.get("val", None),
                            )
                        elif arguments.get("type", "int") == "string":
                            argument = StringArgument(
                                name=arguments["name"],
                                encoding=set_encoding(arguments, tc.get("endian", False)),
                                default=arguments.get("val", None),
                            )
                        elif arguments.get("type", "int") == "enum":
                            argument = EnumeratedArgument(
                                name=arguments["name"],
                                encoding=set_encoding(arguments, tc.get("endian", False)),
                                default=arguments.get("default", None),
                                choices=[tuple(item) for item in arguments['choices']],
                            )
                        else:
                            print("Not defined argument type")
                            sys.exit(1)
                        arg_list.append(argument)
                        entries_list.append(ArgumentEntry(argument))
                Command(
                    system=system,
                    base=base,
                    name=tc["name"],
                    assignments={
                        csp_header.tc_dport.name: tc["port"],
                    },
                    arguments=arg_list,
                    entries=entries_list,
                )


def create_tc(system, yaml, yaml_file, sys_name):
    try:
        with yaml_file as file:
            tc_data = yaml.load(file)
        csp_header = csp.add_csp_header(system, ids=Subsystem)
        general_command = Command(
            system=system,
            name="MyGeneralCommand",
            abstract=True,
            base=csp_header.tc_container,
            assignments={
                csp_header.tc_dst.name: sys_name.upper(),
                csp_header.tc_src.name: "YAMCS",
            },
        )
        set_command(system, csp_header, general_command, tc_data)
    except OSError as e:
        print("Command was not created.")
        print(e)


def main():
    # option
    parser = argparse.ArgumentParser(
        description='Generate Yamcs mdb definition file from YAML via Yamcs PyMDB'
    )
    parser.add_argument("--name", choices=["srs3", "eps", "main", "adcs", "zero", "pico"])
    parser.add_argument('--tm', '--telemetry', type=argparse.FileType('r'), help='Path to a Telemetry YAML')
    parser.add_argument('--tc', '--telecommand', type=argparse.FileType('r'), help='Path to a Telecommand YAML')
    parser.add_argument('--outfile', '--out', type=argparse.FileType('w'), help='Path to mdb out file')
    parser.add_argument('--header', nargs='?', type=argparse.FileType('w'), const=HEADER_OUT,
                        help=f'Generate a header file (default {HEADER_OUT})')
    parser.add_argument('--verbose', action='store_true', help='Enable verbose output')
    args = parser.parse_args()

    if args.verbose:
        logging.basicConfig(stream=sys.stderr, level=logging.DEBUG, format='%(message)s')

    sys_name = args.name

    if args.header:
        system = System("SCSAT1")
        create_header(system)
        logging.info(f'Generating {args.header.name}')
        with args.header as f:
            system.dump(f)

    if args.tm or args.tc:
        yaml = YAML()
        system = System(sys_name.upper())
        if args.tm:
            with args.tm as tm:
                create_tm(system, yaml, tm)
        if args.tc:
            with args.tc as tc:
                create_tc(system, yaml, tc, sys_name)
        if args.outfile:
            logging.info(f'Generating {args.outfile.name}')
            with args.outfile as out:
                system.dump(out)
        else:
            system.dump(sys.stdout)


if __name__ == '__main__':
    main()
