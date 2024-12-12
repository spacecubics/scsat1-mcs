import argparse
import os
import yaml
from datetime import datetime, timezone, timedelta
from yamcs.client import YamcsClient

DEFAULT_YAMCS_URL = 'localhost:8090'
DEFAULT_YAMCS_INSTANCE = 'scsat1'
BLANK_TIME_SEC = 60

configs = {}
mismatchs = {}
matchs = {}
overs = {}
unders = {}
params = []
times = {}
values = {}
errors = {}


def write_csv():

    for param in params:
        csvdir = configs['csv-dir']
        csvname = param.split('/')[-1]
        csvname = csvname.lower()
        with open(f"{csvdir}/{csvname}.csv", 'w',  encoding="utf-8") as outf:
            for idx, time in enumerate(times[param]):
                outf.write(f"{time},\"{values[param][idx]}\",{errors[param][idx]}\n")
                if errors[param][idx] != '':
                    print(f"\033[31m{time},{param},\"{values[param][idx]}\",{errors[param][idx]}\033[0m")


def check_yamcs_params(args):

    search_min = args.min
    yamcs_url = args.url
    yamcs_instance = args.instance
    start_time = args.start
    end_time = args.end

    client = YamcsClient(yamcs_url)
    archive = client.get_archive(instance=yamcs_instance)

    now = datetime.now(tz=timezone.utc)

    if end_time is None:
        stop = now
    else:
        stop = datetime.strptime(end_time + '+0900', '%Y-%m-%d %H:%M:%S%z')

    if search_min < 0:
        if start_time is not None:
            start = datetime.strptime(start_time + '+0900', '%Y-%m-%d %H:%M:%S%z')
        else:
            start = None
    else:
        start = stop - timedelta(minutes=search_min)

    try:
        stream = archive.stream_parameter_values(params, start=start, stop=stop)
        for pdata in stream:
            for data in pdata:
                target = data.name
                time = data.generation_time.strftime("%Y-%m-%d %H:%M:%S %z")

                if isinstance(data.raw_value, (bytes, bytearray)):
                    val = int.from_bytes(data.raw_value, byteorder='big')
                else:
                    val = data.raw_value

                times[target].append(time)
                values[target].append(val)

                if target in mismatchs and val != mismatchs[target]:
                    errors[target].append('Mismatch')
                elif target in matchs and val == matchs[target]:
                    errors[target].append('Match')
                elif target in overs and val > overs[target]:
                    errors[target].append('Over')
                elif target in unders and val < unders[target]:
                    errors[target].append('Under')
                else:
                    errors[target].append('')

    except Exception as e:
        print(e)


def init(yaml_file):

    global configs

    with open(yaml_file, 'r') as file:
        configs = yaml.safe_load(file)

    if not os.path.exists(configs['csv-dir']):
        os.makedirs(configs['csv-dir'])

    prefix = configs['prefix']

    for param in configs['parameters']:
        target = prefix + param['name']
        params.append(target)
        times[target] = []
        values[target] = []
        errors[target] = []
        if 'mismatch' in param['critical']:
            mismatchs[target] = param['critical']['mismatch']
        elif 'match' in param['critical']:
            matchs[target] = param['critical']['match']
        elif 'over' in param['critical']:
            overs[target] = param['critical']['over']
        elif 'under' in param['critical']:
            unders[target] = param['critical']['under']


def main(args):

    for yaml_file in args.yaml:

        if not os.path.exists(yaml_file):
            print(f"{yaml_file} is not exist, so skip the check the parameters")
            continue
        else:
            print(f"Start to check the parameters according to {yaml_file}")

        init(yaml_file)
        check_yamcs_params(args)
        write_csv()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        description="SC-Sat1 Yamcs telemetry parse tool")
    parser.add_argument("--yaml", type=str, required=True, nargs="+",
                        help="Target yaml files")
    parser.add_argument("--url", type=str, default=DEFAULT_YAMCS_URL,
                        help=f"Yamcs URL and Port number (default: {DEFAULT_YAMCS_URL})")
    parser.add_argument("--instance", type=str, default=DEFAULT_YAMCS_INSTANCE,
                        help=f"Yamcs instance name (default: {DEFAULT_YAMCS_INSTANCE})")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--min", type=int, default=-1,
                       help="Target minutes from current time for searching")
    group.add_argument("--start", type=str, default=None,
                       help="Start time for searching")
    parser.add_argument("--end", type=str, default=None,
                        help="End time for searching")
    args = parser.parse_args()
    main(args)
