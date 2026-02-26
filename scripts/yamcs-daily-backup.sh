#!/bin/bash

# --- Configuration ---
PYTHON_BIN="/opt/sc-mcs/venv/bin/python"
BACKUP_SCRIPT="/opt/sc-mcs/scsat1-mcs/py/hot_backup.py"

# Directories and configuration files
ETC_DIR="/opt/sc-mcs/yamcs_work/etc"
BACKUP_ROOT="/opt/sc-mcs/backups/"
YAMCS_YAML="${ETC_DIR}/yamcs.yaml"
SECURITY_YAML="${ETC_DIR}/security.yaml"
YAMCS_ENV="/opt/sc-mcs/etc/yamcs-auth.env"

# --- 1. Check if security is enabled ---
SECURITY_ENABLED="false"
if [ -f "$SECURITY_YAML" ]; then
    # Extract 'enabled:' value from security.yaml
    SECURITY_ENABLED=$(grep "enabled:" "$SECURITY_YAML" | awk '{print $2}' | tr -d '"' | tr -d "'" | tr '[:upper:]' '[:lower:]')
fi

# --- 2. Load credentials if security is enabled ---
AUTH_ARGS=""
if [ "$SECURITY_ENABLED" = "true" ]; then
    if [ -f "$YAMCS_ENV" ]; then
        source "$YAMCS_ENV"
        if [ -n "$YAMCS_USER" ] && [ -n "$YAMCS_PASSWORD" ]; then
            AUTH_ARGS="--username $YAMCS_USER --password $YAMCS_PASSWORD"
        else
            echo "[Warning] Security is enabled but credentials are not set in $YAMCS_ENV"
        fi
    else
        echo "[Warning] Security is enabled but $YAMCS_ENV was not found."
    fi
fi

# --- 3. Get Yamcs URL from yamcs.yaml ---
# Extract address and port 
YAMCS_HOST=$(grep "address:" "$YAMCS_YAML" | head -n 1 | awk '{print $2}' | tr -d '"' | tr -d "'")
YAMCS_PORT=$(grep "port:" "$YAMCS_YAML" | head -n 1 | awk '{print $2}')

# Fallback defaults 
YAMCS_HOST=${YAMCS_HOST:-"localhost"}
YAMCS_PORT=${YAMCS_PORT:-"8090"}

# Handle "0.0.0.0" case: Replace with localhost for connection
if [ "$YAMCS_HOST" = "0.0.0.0" ]; then
    YAMCS_HOST="localhost"
fi

YAMCS_URL="${YAMCS_HOST}:${YAMCS_PORT}"
echo "Detected Yamcs URL: ${YAMCS_URL} (Security enabled: ${SECURITY_ENABLED})"

# --- 4. Determine target directory ---
TODAY=$(date +%Y%m%d)
TARGET_DIR="${BACKUP_ROOT}/${TODAY}"

echo "=== Starting Daily Yamcs Backup: ${TODAY} ==="

# --- 5. Execute hot backup ---
# Pass the credentials ONLY if AUTH_ARGS is not empty
$PYTHON_BIN $BACKUP_SCRIPT \
    --etc-dir "$ETC_DIR" \
    --backup-dir "$TARGET_DIR" \
    --url "$YAMCS_URL" \
    $AUTH_ARGS

if [ $? -ne 0 ]; then
    echo "[Error] Backup script failed. Skipping cleanup." >&2
    exit 1
fi

echo "Backup created successfully at ${TARGET_DIR}."

# --- 6. 3-generation management (Rotation) ---
cd "$BACKUP_ROOT" || { echo "Directory not found"; exit 1; }
ls -1t | grep -E "^[0-9]{8}$" | tail -n +4 | xargs -I {} rm -rf {}

echo "=== Daily backup process finished ==="
