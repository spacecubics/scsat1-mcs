#/bin/sh -e

(
    read -r -p "Please input the working directory name: " work_dir
    if [ ! -d ${work_dir} ]; then
        echo "${work_dir} does not exist or is not a directory."
        exit 1
    fi

    echo "Create MDB files..."
    python $(dirname $0)/../py/create_xtce.py --data main
    python $(dirname $0)/../py/create_xtce.py --data adcs
    python $(dirname $0)/../py/create_xtce.py --data eps
    python $(dirname $0)/../py/create_xtce.py --data srs3

    echo "Installing configuration and MDB files..."
    cp -a $(dirname $0)/../etc ${work_dir}/
    cp -a $(dirname $0)/../mdb ${work_dir}/

    sed -i "s|mdb\/|${work_dir}\/mdb\/|g" ${work_dir}/etc/yamcs.*.yaml
    sed -i "s|\/storage\/|${work_dir}\/storage\/|g" ${work_dir}/etc/yamcs.yaml

    if [ ! -e ${work_dir}/storage ]; then
        echo "Creating storage directory..."
        mkdir ${work_dir}/storage
    fi

    echo "Installation finished."
)
