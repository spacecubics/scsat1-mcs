import argparse
import time
from yamcs.client import YamcsClient

DEFAULT_YAMCS_URL = 'localhost:8090'
DEFAULT_YAMCS_INSTANCE = 'scsat1'


def main(args):

    client = YamcsClient(args.url)
    processor = client.get_processor(args.instance, "realtime")

    # Power on
    command = processor.issue_command(
        "/SCSAT1/ADCS/POWER_CONTROL_CMD",
        args={
            "target": "IMU",
            "onoff": "ON",
        }
    )
    print("Issued", command)

    time.sleep(1)

    command = processor.issue_command(
        "/SCSAT1/ADCS/IMU_ENABLE_CMD",
        args={
            "onoff": "ENABLE",
        }
    )
    print("Issued", command)

    time.sleep(1)

    while True:

        command = processor.issue_command("/SCSAT1/ADCS/GET_IMU_TLM")
        print("Issued", command)

        time.sleep(args.interval)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        description="SC-Sat1 Get IMU telemetry tool")
    parser.add_argument("--url", type=str, default=DEFAULT_YAMCS_URL,
                        help=f"Yamcs URL and Port number (default: {DEFAULT_YAMCS_URL})")
    parser.add_argument("--instance", type=str, default=DEFAULT_YAMCS_INSTANCE,
                        help=f"Yamcs instance name (default: {DEFAULT_YAMCS_INSTANCE})")
    parser.add_argument("--interval", type=float, default=1.0,
                        help="Interval time (seconds)")
    args = parser.parse_args()
    main(args)
