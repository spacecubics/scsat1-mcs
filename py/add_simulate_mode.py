import argparse
import sys
import re
import yaml
from collections import OrderedDict
from pathlib import Path


PY_DIR = Path(__file__).parent
OUT_DIR = Path(PY_DIR.parent, "input_yaml")


def main():
    parser = argparse.ArgumentParser(
        formatter_class=argparse.RawTextHelpFormatter,
        exit_on_error=False,
        description="""
        output options are optional.
        If not set the output option, output file will be input_yaml/FILE_mod.json
        Example:
        $ python py/add_simulate_mode.py --test-tm REPLY_GENERAL_TELEMETRY --file py/tctm/eps_tm.yaml --output test_eps
        input_yaml/test_eps.yaml will be output.\n
        Simulate mode Initial Settings:
        - Parameter type is string: Word
        - Parameter name contains "TEMP": Wave
        - Parameter name contains "TIMESTAMP" or "WALL_CLOCK": Time
        - Others: Liner
        """
    )
    parser.add_argument('--file', required=True, help='YAML file with telemetry data')
    parser.add_argument('--test-tm', required=True, help='Telemetry name to test')
    parser.add_argument('--output', default=None, help='Output file name')
    try:
        args = vars(parser.parse_args())
    except argparse.ArgumentError as e:
        print(f'Error: {e}')
        parser.print_help()
        sys.exit(1)
    test_tm = args['test_tm']
    test_file = args['file']
    # Preserve the order of elements in the YAML file
    yaml.add_constructor(
        yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
        lambda loader,
        node: OrderedDict(loader.construct_pairs(node))
        )
    with open(test_file, encoding='utf-8')as f:
        data = yaml.safe_load(f)
    OUT_DIR.mkdir(exist_ok=True)
    if args['output']:
        out_file = Path(OUT_DIR, f'{args['output']}.yaml')
    else:
        out_file = Path(OUT_DIR, f'{Path(test_file).stem}_mod.yaml')
    # Append the simulation mode based on parameter properties
    tm_data = {}
    for cont in data["containers"]:
        if cont["name"] == test_tm:
            for param in cont["parameters"]:
                if param.get("type", "int") == "string":
                    param["simulate"] = "Word"
                elif re.search(r'TEMP', param["name"]):
                    param["simulate"] = "Wave"
                elif re.search(r'TIMESTAMP|WALL_CLOCK', param["name"]):
                    param["simulate"] = "Time"
                else:
                    param["simulate"] = "Liner"
            tm_data["containers"] = [cont]
    with open(out_file, 'w')as f:
        yaml.dump(tm_data, f, default_flow_style=False, sort_keys=False, allow_unicode=True)


if __name__ == '__main__':
    main()
