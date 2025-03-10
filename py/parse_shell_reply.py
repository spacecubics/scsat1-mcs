import argparse
from datetime import datetime, timezone, timedelta
from yamcs.client import YamcsClient

DEFAULT_YAMCS_URL = 'localhost:8090'
DEFAULT_YAMCS_INSTANCE = 'scsat1'


def main(args):

    client = YamcsClient(args.url)
    archive = client.get_archive(instance=args.instance)

    # Determin search time
    search_min = args.min
    start_time = args.start
    end_time = args.end
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

    seq_number = []
    stream = archive.stream_parameter_values("/SCSAT1/ZERO/SEQUENCE_NUMBER", start=start, stop=stop)
    for pdata in stream:
        for data in pdata:
            seq_number.append(data.raw_value)

    seq_idx = 0
    last_seq = -1
    stream = archive.stream_parameter_values("/SCSAT1/ZERO/SHELL_CMD_RESULT", start=start, stop=stop)
    for pdata in stream:
        for data in pdata:
            if last_seq != seq_number[seq_idx]:
                print(f"\n=====[ #{seq_number[seq_idx]} ]=====\n")
            print(data.raw_value)
            last_seq = seq_number[seq_idx]
            seq_idx += 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        description="SC-Sat1 Check file Upload result tool")
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
