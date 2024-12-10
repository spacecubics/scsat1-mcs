import yaml
from collections import OrderedDict
import re
import argparse
from pathlib import Path


PY_DIR = Path(__file__).parent
OUT_DIR = Path(PY_DIR.parent, "input_yaml")
OUT_DIR.mkdir(exist_ok=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--file')
    parser.add_argument('--test_tm')
    parser.add_argument('--output', default=None)
    args = vars(parser.parse_args())
    test_tm = args['test_tm']
    test_file = args['file']

    # 順番保持
    yaml.add_constructor(
        yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
        lambda loader,
        node: OrderedDict(loader.construct_pairs(node))
        )

    with open(test_file, encoding='utf-8')as f:
        data = yaml.safe_load(f)

    if args['output']:
        out_file = Path(OUT_DIR, f'{args['output']}.yaml')
    else:
        out_file = Path(OUT_DIR, f'{Path(test_file).stem}_mod.yaml')

    data2 = {}
    for cont in data["containers"]:
        if cont["name"] == test_tm:
            for param in cont["parameters"]:
                if param.get("type", "int") == "string":
                    param["simulate"] = "Word"
                elif re.search(r'TEMP', param["name"]):
                    param["simulate"] = "Wave"
                else:
                    param["simulate"] = "Liner"
            data2["containers"] = [cont]

    with open(out_file, 'w')as f:
        yaml.dump(data2, f, default_flow_style=False, sort_keys=False, allow_unicode=True)


if __name__ == '__main__':
    main()
