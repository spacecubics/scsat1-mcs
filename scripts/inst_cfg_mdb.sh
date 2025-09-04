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
mkdir -p "${work_dir}/mdb"

echo "Create MDB files..."
data=$(readlink -f $(dirname $0)/../py/tctm)
create_xtce=$(readlink -f $(dirname $0)/../py/create_xtce.py)
$create_xtce --verbose --header "${work_dir}"/mdb/scsat1_header.xml || die "header failed"
$create_xtce --verbose --name main --tm $data/main_tm.yaml --tc $data/main_tc.yaml --out "${work_dir}"/mdb/scsat1_main.xml || die "main failed"
$create_xtce --verbose --name adcs --tm $data/adcs_tm.yaml --tc $data/adcs_tc.yaml --out "${work_dir}"/mdb/scsat1_adcs.xml || die "adcs failed"
$create_xtce --verbose --name eps  --tm $data/eps_tm.yaml  --tc $data/eps_tc.yaml  --out "${work_dir}"/mdb/scsat1_eps.xml  || die "eps failed"
$create_xtce --verbose --name srs3 --tm $data/srs3_tm.yaml --tc $data/srs3_tc.yaml --out "${work_dir}"/mdb/scsat1_srs3.xml || die "srs3 failed"
$create_xtce --verbose --name zero --tm $data/zero_tm.yaml --tc $data/zero_tc.yaml --out "${work_dir}"/mdb/scsat1_zero.xml || die "zero failed"
$create_xtce --verbose --name pico --tm $data/pico_tm.yaml --tc $data/pico_tc.yaml --out "${work_dir}"/mdb/scsat1_pico.xml || die "pico failed"

echo "Installing configuration files from etc to ${work_dir}..."
cp -a $(dirname $0)/../etc "${work_dir}"/  || die "copy etc failed"

echo "Fixing up configuration files in ${work_dir}/etc..."
sed -i "s|mdb\/|${work_dir}\/mdb\/|g" "${work_dir}"/etc/yamcs.*.yaml
sed -i "s|\/storage\/|${work_dir}\/storage\/|g" "${work_dir}"/etc/yamcs.yaml
sed -i "s|\/storage\/|${work_dir}\/storage\/|g" "${work_dir}"/etc/logging.properties

if [ ! -e "${work_dir}"/storage ]; then
    echo "Creating storage directory..."
    mkdir "${work_dir}"/storage
fi

if [ ! -e "${work_dir}"/storage/log ]; then
    echo "Creating log directory..."
    mkdir "${work_dir}"/storage/log
fi

echo "Installation finished."
