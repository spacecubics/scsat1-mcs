import os
import yaml
from yamcs.client import YamcsClient, Credentials

DEFAULT_YAMCS_YAML_PATH = "/opt/sc-mcs/yamcs_work/etc/yamcs.yaml"
DEFAULT_YAMCS_AUTH_PATH = "/opt/sc-mcs/etc/yamcs_auth.yaml"

def load_yamcs_config(yaml_path=DEFAULT_YAMCS_YAML_PATH):
    """Extract hostname and port from yamcs.yaml."""
    try:
        with open(yaml_path, 'r') as f:
            config = yaml.safe_load(f)

        for service in config.get('services', []):
            if service.get('class') == 'org.yamcs.http.HttpServer':
                args = service.get('args', {})
                address = args.get('address', 'localhost')
                port = args.get('port', 8090)

                if address == "0.0.0.0":
                    address = "localhost"

                return f"{address}:{port}"
        return "localhost:8090"
    except Exception as e:
        print(f"[Warning] Could not read {yaml_path}. Using default. ({e})")
        return "localhost:8090"

def load_auth_info(auth_path=DEFAULT_YAMCS_AUTH_PATH):
    """Extract user and password from the YAML auth file."""
    user, password = None, None
    if os.path.exists(auth_path):
        try:
            with open(auth_path, 'r') as f:
                auth_config = yaml.safe_load(f)
                if auth_config:
                    user = auth_config.get('user')
                    password = auth_config.get('password')
        except Exception as e:
            print(f"[Warning] Could not read {auth_path}. ({e})")
    return user, password

def get_yamcs_client(yaml_path=DEFAULT_YAMCS_YAML_PATH, auth_path=DEFAULT_YAMCS_AUTH_PATH):
    """Create and return a connected YamcsClient with fallback logic."""
    address = load_yamcs_config(yaml_path)
    user, password = load_auth_info(auth_path)

    client = None
    if user and password:
        try:
            creds = Credentials(username=user, password=password)
            client = YamcsClient(address, credentials=creds)

            client.get_instances()
            print("[Info] Authenticated connection established.")
        except Exception as e:
            print(f"[Info] Authentication failed. Falling back to anonymous. ({e})")
            client = None

    if client is None:
        try:
            client = YamcsClient(address)
            print("[Info] Anonymous connection established.")
        except Exception as e:
            print(f"[Error] Failed to initialize YamcsClient: {e}")
            return None

    return client
