import argparse
import subprocess
import sys
import requests
import json
from pathlib import Path

from backup_util import fail_exit, check_dir, create_dir, list_backups, get_max_numbered_file


def output_config_txt_hot(etc_dir, backup_dir, yamcs_version, pid):
    if not yamcs_version:
        yamcs_version = "Unknown"
    if not pid:
        yamcs_version = "Unknown"
    config_list = [
        f"yamcs_version: {yamcs_version}",
        f"pid: {pid}",
        f"etc_dir: {etc_dir}"
    ]
    output_dir = backup_dir / "config"
    last_file_num = get_max_numbered_file(output_dir)
    file_path = output_dir / f"{last_file_num + 1}.txt"
    with open(file_path, "w") as f:
        f.writelines('\n'.join(config_list))


def get_yamcs_version_by_api(host_address):
    # Get Yamcs server version via API
    api_url = f"http://{host_address}/api/sysinfo"
    try:
        resp = requests.get(api_url, timeout=5)
        resp.raise_for_status()
        json_data = resp.json()
    except Exception as e:
        print(f"Failed to fetch tablespaces from {api_url}: {e}", file=sys.stderr)
        sys.exit(1)
    yamcs_version = json_data.get("yamcsVersion", None)
    if not yamcs_version:
        print("Could not get yamcs version.", file=sys.stderr)
    pid = json_data.get("process", None).get("pid", None)
    if not pid:
        print("Could not get pid.", file=sys.stderr)
    return yamcs_version, pid


def get_tablespace_names_by_api(host_address):
    # Get the list of tablespace names via the API
    api_url = f"http://{host_address}/api/archive/rocksdb/tablespaces"
    try:
        resp = requests.get(api_url, timeout=5)
        resp.raise_for_status()
        json_data = resp.json()
    except Exception as e:
        print(f"Failed to fetch tablespaces from {api_url}: {e}", file=sys.stderr)
        sys.exit(1)
    names = [ts["name"] for ts in json_data.get("tablespaces", [])]

    if not names:
        print("No tablespaces found. Exiting.", file=sys.stderr)
        sys.exit(1)
    return names


def hot_backup_tablespace(etc_dir, backup_dir, name, pid):
    print(f"Tablespace: {name}")
    cmd = [
        "/opt/yamcs/bin/yamcsadmin",
        "--etc-dir", str(etc_dir),
        "backup", "create",
        "--backup-dir", str(backup_dir / name),
    ]
    if pid:
        cmd.extend(["--pid", pid])
    cmd.append(name)
    try:
        subprocess.run(cmd, check=True)
        print("Backup performed successfully")
    except subprocess.CalledProcessError:
        fail_exit("Backup", name)


def main():
    parser = argparse.ArgumentParser(
        description="Yamcs hot backup script. Use this if Yamcs is running.",
        formatter_class=lambda prog: argparse.RawTextHelpFormatter(prog, max_help_position=30),
        add_help=False
    )
    # Required arguments
    parser.add_argument(
        "--etc-dir",
        required=True,
        help="Path to Yamcs configuration directory.")
    parser.add_argument(
        "--backup-dir",
        required=True,
        help="Path to directory for backups (auto-created if missing)."
    )
    # Optional arguments
    parser.add_argument(
        "-h", "--help",
        action="help",
        help="Show this help message and exit."
    )
    parser.add_argument(
        "-l", "--list",
        action="store_true",
        help="List available backups instead of creating one."
    )
    parser.add_argument(
        "--url",
        metavar="HOST:PORT",
        default="localhost:8090",
        help="Host address of Yamcs server (default: localhost:8090)."
    )

    args = parser.parse_args()
    etc_dir = Path(args.etc_dir)
    backup_dir = Path(args.backup_dir)
    host_address = args.url

    check_dir(etc_dir)

    # Print the backups inside the backup directory
    if args.list:
        check_dir(backup_dir)
        list_backups(etc_dir, backup_dir)
        sys.exit(0)
    
    # Getting system info
    yamcs_version, pid = get_yamcs_version_by_api(host_address)

    # Getting tablespace name
    names = get_tablespace_names_by_api(host_address)

    print("Creating backup...")
    print(f"host address: {host_address}")
    create_dir(backup_dir)
    for name in names:
        hot_backup_tablespace(etc_dir, backup_dir, name, pid)
    output_config_txt_hot(etc_dir, backup_dir, yamcs_version, pid)
    print(f"Backup completed! (output: {backup_dir})")


if __name__ == "__main__":
    main()
