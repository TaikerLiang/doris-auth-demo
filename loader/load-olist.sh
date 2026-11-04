#!/usr/bin/env bash
# Usage: load-olist.sh <fe-host>...
# Creates the olist tables on each FE and stream-loads the CSVs from /data into them.
set -euo pipefail

declare -A FILES=(
  [customers]=olist_customers_dataset.csv
  [orders]=olist_orders_dataset.csv
  [order_items]=olist_order_items_dataset.csv
  [order_payments]=olist_order_payments_dataset.csv
  [products]=olist_products_dataset.csv
  [sellers]=olist_sellers_dataset.csv
)

for file in "${FILES[@]}"; do
  [[ -f /data/$file ]] || { echo "missing /data/$file: unzip the Kaggle dataset into data/olist/" >&2; exit 1; }
done

for fe in "$@"; do
  # A BE turns Alive before it reports its disks; CREATE TABLE fails until TotalCapacity > 0.
  echo "== $fe: waiting for a BE with disk capacity"
  until mysql -h"$fe" -P9030 -uroot -e 'SHOW BACKENDS\G' 2>/dev/null | grep -q 'TotalCapacity: [1-9]'; do sleep 2; done

  mysql -h"$fe" -P9030 -uroot < /scripts/schema.sql

  for table in "${!FILES[@]}"; do
    resp=$(curl -sS --location-trusted -u root: \
      -H 'Expect: 100-continue' -H 'format: csv_with_names' -H 'column_separator: ,' \
      -H 'enclose: "' -H 'trim_double_quotes: true' \
      -T "/data/${FILES[$table]}" "http://$fe:8030/api/olist/$table/_stream_load")
    if ! grep -q '"Status": "Success"' <<<"$resp"; then
      echo "$fe olist.$table: load failed" >&2
      echo "$resp" >&2
      exit 1
    fi
    echo "$fe olist.$table: $(grep -o '"NumberLoadedRows": [0-9]*' <<<"$resp" | grep -o '[0-9]*') rows"
  done
done
