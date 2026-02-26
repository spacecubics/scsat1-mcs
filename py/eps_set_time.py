import time
from yamcs_utils import get_yamcs_client

INSTANCE = "scsat1"
COMMAND_NAME = "/SCSAT1/EPS/REQ_SET_TIME"

def eps_set_time():
    # Initialize client using default paths defined in yamcs_utils.py
    # Note: You can override paths like get_yamcs_client(yaml_path="...", auth_path="...")
    client = get_yamcs_client()

    if not client:
        print("[Error] Exiting due to connection failure.")
        return

    # Issue command
    try:
        processor = client.get_processor(INSTANCE, "realtime")

        timestamp = time.time()
        ts_value = int(timestamp)

        print(f"Issuing {COMMAND_NAME} with timestamp: {ts_value}")

        processor.issue_command(
            COMMAND_NAME,
            args={
                "command_id": 1,
                "timestamp": ts_value
            }
        )
        print("Command issued successfully.")
    except Exception as e:
        print(f"[Error] Command execution failed: {e}")

if __name__ == "__main__":
    eps_set_time()
