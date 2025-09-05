import argparse
import subprocess
import sys
from pathlib import Path

from backup_util import fail_exit, check_dir, create_dir, list_backups, get_tablespace_names_from_backup_dir

def delete_backup(etc_dir, backup_dir, names):
    target_id = get_target_id_from_input(etc_dir, backup_dir, names, "delete")
    print("Deleting backup...")
    for name in names:
        delete_tablespace(etc_dir, backup_dir, name, target_id)
    delete_config_txt(etc_dir, backup_dir, target_id)
    print(f"Delete completed! (deleted: {target_id})")


def delete_tablespace(etc_dir, backup_dir, name, backup_id):
    cmd = [
        "/opt/yamcs/bin/yamcsadmin",
        "--etc-dir", str(etc_dir),
        "backup", "delete",
        "--backup-dir", str(backup_dir / name),
        str(backup_id),
    ]
    try:
        subprocess.run(cmd, check=True)
    except subprocess.CalledProcessError:
        fail_exit("Delete", name)


def delete_config_txt(etc_dir, backup_dir, backup_id):
    file_name = f"{backup_id}.txt"
    cmd = [
        "rm", str(backup_dir / "config" / file_name)
    ]
    try:
        subprocess.run(cmd, check=True)
    except subprocess.CalledProcessError:
        fail_exit("Delete", "config")


def restore_backup(etc_dir, backup_dir, restore_dir, names):
    target_id = get_target_id_from_input(etc_dir, backup_dir, names, "restore")
    print("Creating restore...")
    create_dir(restore_dir)
    for name in names:
        restore_tablespace(etc_dir, backup_dir, restore_dir, name, target_id)
    print(f"Restore completed! (output: {restore_dir})")


def restore_tablespace(etc_dir, backup_dir, restore_dir, name, backup_id):
    cmd = [
        "/opt/yamcs/bin/yamcsadmin",
        "--etc-dir", str(etc_dir),
        "backup", "restore",
        "--backup-dir", str(backup_dir / name),
        "--restore-dir", str(restore_dir / f"{name}.rdb"),
        str(backup_id),
    ]
    try:
        subprocess.run(cmd, check=True)
    except subprocess.CalledProcessError:
        fail_exit("Restore", name)


def get_target_id_from_input(etc_dir, backup_dir, names, mode, max_retries=3):
    for attempt in range(max_retries):
        list_backups(etc_dir, backup_dir, names=names)
        try:
            # Prompt the user to enter the backup ID
            target_id = int(input(f"Enter the backup ID to {mode} from the list above: "))
            break
        except ValueError:
            # Print error message if input is not a valid integer
            print(
                f"Error: Invalid input. Please enter a valid integer ({attempt+1}/{max_retries}).",
                file=sys.stderr
            )
    else:
        # Exit if the user fails too many times
        print("Too many invalid attempts. Exiting.", file=sys.stderr)
        sys.exit(1)
    return target_id


def main():
    parser = argparse.ArgumentParser(
        description="Yamcs restore script. Use this if Yamcs is not running.\n"+
                    "Optionally, the script can list the available backups and delete backups.",
        formatter_class=lambda prog: argparse.RawTextHelpFormatter(prog, max_help_position=30),
        add_help=False
    )
    # Required arguments
    parser.add_argument(
        "--etc-dir",
        required=True,
        help="Path to Yamcs configuration directory"
    )
    parser.add_argument(
        "--backup-dir",
        required=True,
        help="Path to the source backup directory"
    )
    parser.add_argument(
        "--restore-dir",
        required=True,
        help="Path to the directory where backups will be restored"
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
        "-d", "--delete",
        action="store_true",
        help="Delete one backup from the available backups."
    )

    args = parser.parse_args()
    etc_dir = Path(args.etc_dir)
    backup_dir = Path(args.backup_dir)
    restore_dir = Path(args.restore_dir)

    check_dir(etc_dir)
    check_dir(backup_dir)

    # Getting tablespace name
    names = get_tablespace_names_from_backup_dir(backup_dir)
    # Print the backups inside the backup directory
    if args.list:
        list_backups(etc_dir, backup_dir, names=names)
        sys.exit(0)
    
    # Deletes the backup of the ID entered by the user
    if args.delete:
        try:
            delete_backup(etc_dir, backup_dir, names)
        except KeyboardInterrupt:
            print("\nBackup deletion canceled")
        sys.exit(0)

    # Restore the backup of the ID entered by the user
    try:
        restore_backup(etc_dir, backup_dir, restore_dir, names)
    except KeyboardInterrupt:
        print("\nBackup restore canceled")


if __name__ == "__main__":
    main()
