import argparse
from pathlib import Path
from ruamel.yaml import YAML
from yamcs.client import YamcsClient
from yamcs.tmtc.model import RangeSet


def set_range(level, alarm):
    alm = alarm.get(level, None)
    if alm is not None:
        return (alm.get("low", None), alm.get("high", None))
    else:
        return None


def set_alarm(processor, alarm_data):
    try:
        for alarm in alarm_data["alarms"]:
            if alarm is not None:
                ranges = [
                    RangeSet(
                        context=None,
                        watch=set_range("watch", alarm),
                        warning=set_range("warning", alarm),
                        distress=set_range("distress", alarm),
                        critical=set_range("critical", alarm),
                        severe=set_range("severe", alarm),
                        min_violations=alarm.get("min_violations", 1)
                    )
                ]
                processor.set_alarm_range_sets(parameter=alarm_data["prefix"]+alarm["name"], sets=ranges)
    except KeyError:
        print("'alarms' is not correctly defined.")


def main():
    # argument
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", metavar="YAML_FILE", help='Path of yaml file for alarm', required=True)
    parser.add_argument("--instance", metavar="INSTANCE_NAME", default="scsat1", help='instance name (default: sccat1)')
    args = parser.parse_args()
    # set alarm
    client = YamcsClient('localhost:8090')
    processor = client.get_processor(instance=args.instance, processor='realtime')
    yaml = YAML()
    yaml_file = Path(args.data)
    try:
        with open(yaml_file, 'r') as file:
            alarm_data = yaml.load(file)
        set_alarm(processor, alarm_data)
    except OSError as e:
        print(e)


if __name__ == '__main__':
    main()
