import argparse
import subprocess
import sys
import requests
import json
import re
from pathlib import Path

from backup_util import fail_exit, check_dir, create_dir, list_backups, get_max_numbered_file


def output_config_txt_cold(etc_dir, backup_dir, data_dir):
    yamcs_version = get_yamcs_version_by_command()
    config_list = [
        f"yamcs_version: {yamcs_version}",
        f"etc_dir: {etc_dir}",
        f"data_dir: {data_dir}"
    ]
    output_dir = backup_dir / "config"
    last_file_num = get_max_numbered_file(output_dir)
    file_path = output_dir / f"{last_file_num+1}.txt"
    with open(file_path, "w") as f:
        f.writelines('\n'.join(config_list))


def get_yamcs_version_by_command():
    # Get Yamcs version by command
    result = subprocess.run(
        ["/opt/yamcs/bin/yamcsd", "-v"], capture_output=True, text=True, check=True
    )
    output = result.stdout
    # Search for 'Yamcs <yamcs_version>'
    match = re.search(r"^Yamcs (\d+\.\d+\.\d+)\b", output, re.MULTILINE)
    if match:
        yamcs_version = match.group(1)
    else:
        print("Yamcs version not found.")
    return yamcs_version


def get_tablespace_names_from_data_dir(data_dir):
    # Get the list of tablespace names directly under data directory
    names = [p.name for p in data_dir.iterdir() if p.is_dir() and p.name.endswith(".rdb")]
    if not names:
        print("No tablespaces found. Exiting.", file=sys.stderr)
        sys.exit(1)
    return names


def cold_backup_tablespace(etc_dir, backup_dir, data_dir, name):
    ts_name = name.removesuffix(".rdb")
    print(f"Tablespace: {ts_name}")
    cmd = [
        "/opt/yamcs/bin/yamcsadmin",
        "--etc-dir", str(etc_dir),
        "backup", "create",
        "--backup-dir", str(backup_dir / ts_name),
        "--data-dir", str(data_dir),
        ts_name,
    ]
    try:
        subprocess.run(cmd, check=True)
    except subprocess.CalledProcessError:
        fail_exit("Backup", ts_name)


def main():
    parser = argparse.ArgumentParser(
        description="Yamcs cold backup script. Use this if Yamcs is not running.",
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
    parser.add_argument(
        "--data-dir",
        required=True,
        help="Path to a Yamcs data directory."
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

    args = parser.parse_args()
    etc_dir = Path(args.etc_dir)
    data_dir = Path(args.data_dir)
    backup_dir = Path(args.backup_dir)

    check_dir(etc_dir)
    check_dir(data_dir)

    # Print the backups inside the backup directory
    if args.list:
        check_dir(backup_dir)
        list_backups(etc_dir, backup_dir)
        sys.exit(0)

    # Getting tablespace name
    names = get_tablespace_names_from_data_dir(data_dir)

    print("Creating backup...")
    print(f"data: {data_dir}")
    create_dir(backup_dir)
    for name in names:
        cold_backup_tablespace(etc_dir, backup_dir, data_dir, name)
    output_config_txt_cold(etc_dir, backup_dir, data_dir)
    print(f"Backup completed! (output: {backup_dir})")


if __name__ == "__main__":
    main()
