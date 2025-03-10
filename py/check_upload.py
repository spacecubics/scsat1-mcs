import argparse
from datetime import datetime, timezone, timedelta
from yamcs.client import YamcsClient

DEFAULT_YAMCS_URL = 'localhost:8090'
DEFAULT_YAMCS_INSTANCE = 'scsat1'
REPLY_PARAM_NUM = 20


def main(args):

    client = YamcsClient(args.url)
    archive = client.get_archive(instance=args.instance)
    board = args.board

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

    # Collect offset parameter name
    offset_params = []
    for no in range(REPLY_PARAM_NUM):
        offset_params.append(f"/SCSAT1/{board}/OFFSET_{no+1:02d}_OF_UPLOAD_DATA")

    # Search
    last_offset = -args.chunk
    stream = archive.stream_parameter_values(offset_params, start=start, stop=stop)
    for pdata in stream:
        for data in pdata:
            if data.raw_value == 0 and last_offset != -args.chunk:
                break
            if data.raw_value - last_offset != args.chunk:
                print("OFFSET skipped")
            print(f"{data.generation_time} {data.name} : {data.raw_value}")
            last_offset = data.raw_value


if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        description="SC-Sat1 Check file Upload result tool")
    parser.add_argument("--url", type=str, default=DEFAULT_YAMCS_URL,
                        help=f"Yamcs URL and Port number (default: {DEFAULT_YAMCS_URL})")
    parser.add_argument("--instance", type=str, default=DEFAULT_YAMCS_INSTANCE,
                        help=f"Yamcs instance name (default: {DEFAULT_YAMCS_INSTANCE})")
    parser.add_argument("--chunk", type=int, default=200,
                        help="Chunk size (byte)")
    parser.add_argument("--board", type=str, default="MAIN",
                        help="Target board. MAIN or ADCS or ZERO")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--min", type=int, default=-1,
                       help="Target minutes from current time for searching")
    group.add_argument("--start", type=str, default=None,
                       help="Start time for searching")
    parser.add_argument("--end", type=str, default=None,
                        help="End time for searching")
    args = parser.parse_args()
    main(args)
