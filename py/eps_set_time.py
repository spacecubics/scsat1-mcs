import time
from datetime import datetime
from yamcs.client import YamcsClient

YAMCS_URL = "http://[hostname]:[port]"
INSTANCE = "scsat1"
COMMAND_NAME = "SCSAT1/EPS/REQ_SET_TIME"

def eps_set_time():
    client = YamcsClient(YAMCS_URL)
    processor = client.get_processor(INSTANCE, "realtime")
    timestamp = time.time()
    print(f'Sending {COMMAND_NAME} with timestamp: {int(timestamp)}')

    processor.issue_command(
        COMMAND_NAME,
        args={
            "command_id": "1",
            "timestamp": int(timestamp)
        }
    )

if __name__ == "__main__":
    eps_set_time()
