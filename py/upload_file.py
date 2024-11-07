import argparse
import time
import pathlib
from yamcs.client import YamcsClient

DEFAULT_YAMCS_URL = 'localhost:8090'
DEFAULT_YAMCS_INSTANCE = 'scsat1'


def main(args):

    client = YamcsClient(args.url)
    processor = client.get_processor(args.instance, "realtime")
    path = pathlib.Path(args.src)
    board = args.board

    command = processor.issue_command(
        f"/SCSAT1/{board}/UPLOAD_OPEN_CMD",
        args={
            "session_id": args.id,
            "file_name": f"/storage/{path.name}",
        }
    )
    print("Issued", command)

    time.sleep(1)

    offset = args.offset
    if args.size != 0:
        remaining_size = args.size
    else:
        remaining_size = path.stat().st_size

    try:
        with open(args.src, "rb") as file:

            file.seek(offset, 0)

            while remaining_size != 0:
                if remaining_size < args.chunk:
                    size = remaining_size
                else:
                    size = args.chunk

                chunk = file.read(size)

                command = processor.issue_command(
                    f"/SCSAT1/{board}/UPLOAD_DATA_CMD",
                    args={
                        "session_id": args.id,
                        "offset": offset,
                        "size": len(chunk),
                        "data": chunk
                    }
                )
                time.sleep(args.interval)
                offset += len(chunk)
                remaining_size -= len(chunk)

            if offset % args.chunk == 0:
                command = processor.issue_command(
                    f"/SCSAT1/{board}/UPLOAD_DATA_CMD",
                    args={
                        "session_id": args.id,
                        "offset": 0,
                        "size": 0,
                        "data": 0
                    }
                )
            time.sleep(1)

            command = processor.issue_command(
                f"/SCSAT1/{board}/UPLOAD_CLOSE_CMD",
                args={
                    "session_id": args.id,
                }
            )
            print("Issued", command)
    except FileNotFoundError:
        print(f"File not found: {args.src}")
    except Exception as e:
        print(f"An error occurred: {e}")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        description="SC-Sat1 Upload file tool")
    parser.add_argument("--board", type=str, default="MAIN",
                        help="Target board. MAIN or ADCS or PYLD")
    parser.add_argument("--src", type=str, required=True,
                        help="Upload file on local host")
    parser.add_argument("--chunk", type=int, default=200,
                        help="Chunk size (byte)")
    parser.add_argument("--id", type=int, default=0,
                        help="Session ID")
    parser.add_argument("--offset", type=int, default=0,
                        help="Start offset")
    parser.add_argument("--size", type=int, default=0,
                        help="Send size. `0` is all size")
    parser.add_argument("--interval", type=float, default=0.066,
                        help="Session ID")
    parser.add_argument("--url", type=str, default=DEFAULT_YAMCS_URL,
                        help=f"Yamcs URL and Port number (default: {DEFAULT_YAMCS_URL})")
    parser.add_argument("--instance", type=str, default=DEFAULT_YAMCS_INSTANCE,
                        help=f"Yamcs instance name (default: {DEFAULT_YAMCS_INSTANCE})")
    args = parser.parse_args()
    main(args)
