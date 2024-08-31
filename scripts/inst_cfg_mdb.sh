#/bin/sh -e

die()
{
	echo >&2
	echo "$*" >&2
	exit 1
}

if [ -z "$1" ]; then
  echo "Usage: $0 <directory-name>"
  exit 1
fi

work_dir=$1
mkdir -p "${work_dir}"

echo "Create MDB files..."
create_xtce=$(readlink -f $(dirname $0)/../py/create_xtce.py)
$create_xtce --name main || die "main failed"
$create_xtce --name adcs || die "adcs failed"
$create_xtce --name eps  || die "eps failed"
$create_xtce --name srs3 || die "srs3 failed"

echo "Installing configuration and MDB files..."
cp -a $(dirname $0)/../etc "${work_dir}"/  || die "copy etc failed"
cp -a $(dirname $0)/../mdb "${work_dir}"/  || die "copy mdb failed"

sed -i "s|mdb\/|${work_dir}\/mdb\/|g" "${work_dir}"/etc/yamcs.*.yaml
sed -i "s|\/storage\/|${work_dir}\/storage\/|g" "${work_dir}"/etc/yamcs.yaml

if [ ! -e "${work_dir}"/storage ]; then
    echo "Creating storage directory..."
    mkdir "${work_dir}"/storage
fi

echo "Installation finished."
