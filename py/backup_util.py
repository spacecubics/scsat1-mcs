import sys
import subprocess
from datetime import datetime
from zoneinfo import ZoneInfo
import re


def fail_exit(action: str, name: str):
    print(f"{action} failed for {name}. Exiting.", file=sys.stderr)
    sys.exit(1)


def check_dir(dir_path):
    if not dir_path.is_dir():
        print(f"Directory does not exist: {dir_path}", file=sys.stderr)
        sys.exit(1)


def create_dir(dir_path):
    if not dir_path.exists():
        print(f"Creating output directory: {dir_path}")
        try:
            dir_path.mkdir(parents=True, exist_ok=True)
        except Exception as e:
            print(f"Failed to create directory: {dir_path} ({e})", file=sys.stderr)
            sys.exit(1)


def get_max_numbered_file(dir_path, suffix=".txt"):
    if dir_path.exists():
        files = dir_path.glob(f"*{suffix}")
        max_num = None
        for f in files:
            try:
                num = int(f.stem)
            except ValueError:
                continue
            if max_num is None or num > max_num:
                max_num = num
    else:
        create_dir(dir_path)
        max_num = 0
    return max_num


def list_backups(etc_dir, backup_dir, names=None):
    # Get the backup list inside the backup directory
    if names is None:
        names = get_tablespace_names_from_backup_dir(backup_dir)
    last_name = sorted(names)[-1]
    print(f"Listing backups: {last_name}")
    try:
        cmd_output = subprocess.check_output(
            [
                "/opt/yamcs/bin/yamcsadmin",
                "--etc-dir", str(etc_dir),
                "backup", "list",
                "--backup-dir", str(backup_dir / last_name),
            ],
        )
    except subprocess.CalledProcessError:
        list_fail_exit(last_name)
    # Get Yamcs version data at the time of backup
    yamcs_version_dict = get_yamcs_version_dict(backup_dir/"config")
    # Prints the header of lists
    lines = cmd_output.decode("utf-8").strip().splitlines()
    name_list = re.split(r"\s{2,}", lines[0].strip())
    # Pad "time [JST]" to 19 chars (datetime "%Y-%m-%d %H:%M:%S" is 19 chars)
    name_list[-1] = "time (JST)".ljust(19)
    name_list = name_list + ["yamcs_version"]
    name_len_list = [len(name)+2 for name in name_list]
    header = "".join(n.ljust(w) for n, w in zip(name_list, name_len_list))
    print(header)
    # Prints the baskup list and yamcs version
    for backup_data in lines[1:]:
        items = backup_data.split()
        # Convert UTC time to JST
        utc_time = datetime.strptime(items[-1], "%Y-%m-%dT%H:%M:%SZ[%Z]").replace(tzinfo=ZoneInfo("UTC"))
        jst_time = utc_time.astimezone(ZoneInfo("Asia/Tokyo"))
        items[-1] = jst_time.strftime("%Y-%m-%d %H:%M:%S")
        # Get Yamcs version of backup id
        backup_id = items[0]
        yamcs_version = yamcs_version_dict.get(f"{backup_id}", "-")
        items.append(yamcs_version)
        # Format each field with fixed width using ljust and join them into a row string
        row = "".join(str(item).ljust(w) for item, w in zip(items + [yamcs_version], name_len_list))
        print(row)

def get_yamcs_version_dict(dir_path, suffix=".txt"):
    yamcs_version_dict = {}
    if dir_path.exists():
        files = sorted(dir_path.glob(f"*{suffix}"), key=lambda f: int(f.stem))
        for f in files:
            try:
                id = int(f.stem)
                yamcs_version = read_yamcs_version(f)
                if yamcs_version:
                    yamcs_version_dict[f"{id}"] = yamcs_version
                else:
                    yamcs_version_dict[f"{id}"] = "unknown"
            except ValueError:
                continue
    else:
        print("Config directory not found. Exiting.", file=sys.stderr)
        sys.exit(1)
    return yamcs_version_dict


def read_yamcs_version(file_path):
    with file_path.open(encoding="utf-8") as f:
        for line in f:
            if line.startswith("yamcs_version:"):
                return line.split(":", 1)[1].strip()
    return None


def get_tablespace_names_from_backup_dir(backup_dir):
    # Get the list of tablespace names directly under backup directory
    names = [p.name for p in backup_dir.iterdir() if p.is_dir() and p.name != "config"]
    if not names:
        print("No backups found.", file=sys.stderr)
        sys.exit(1)
    return names
