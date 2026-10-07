#!/bin/bash
# 把本目录下的配置发布到 Nacos（配置中心）。
# 用法：bash publish.sh [nacos地址]，默认 localhost:8848
# 注意：必须带 charset=UTF-8，否则 Nacos 会把中文存成乱码（U+FFFD）
set -e
cd "$(dirname "$0")"
ADDR="${1:-localhost:8848}"

publish() { # $1=dataId $2=file $3=type
  r=$(curl -s --noproxy '*' -X POST "http://$ADDR/nacos/v1/cs/configs" \
      -H 'Content-Type: application/x-www-form-urlencoded;charset=UTF-8' \
      --data-urlencode "dataId=$1" \
      --data-urlencode 'group=DEFAULT_GROUP' \
      --data-urlencode "type=$3" \
      --data-urlencode "content@$2")
  echo "$1 -> $r"
}

for f in shared-*.yaml cart-service.yaml; do
  [ -f "$f" ] && publish "$f" "$f" yaml
done
[ -f gateway.json ] && publish gateway.json gateway.json json
