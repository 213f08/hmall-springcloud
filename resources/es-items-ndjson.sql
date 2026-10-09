-- 生成 ES _bulk 需要的 ndjson：一行动作、一行文档
-- 用法见 README「索引初始化与首次全量导入」一节：
--   docker exec -i sky-mysql mysql -uroot -proot --default-character-set=utf8mb4 \
--     --raw -N -B hm-item < resources/es-items-ndjson.sql > items.ndjson
-- --raw 必须加，否则 MySQL 批量模式会把每行中间的 \n 再转义一次，ndjson 就废了。
SELECT CONCAT(
  '{"index":{"_index":"items","_id":', id, '}}', CHAR(10),
  '{"id":', id,
  ',"name":', IFNULL(JSON_QUOTE(`name`), '""'),
  ',"price":', IFNULL(CAST(`price` AS CHAR), '0'),
  ',"image":', IFNULL(JSON_QUOTE(`image`), 'null'),
  ',"category":', IFNULL(JSON_QUOTE(`category`), 'null'),
  ',"brand":', IFNULL(JSON_QUOTE(`brand`), 'null'),
  ',"spec":', IFNULL(JSON_QUOTE(`spec`), 'null'),
  ',"sold":', IFNULL(CAST(`sold` AS CHAR), 'null'),
  ',"commentCount":', IFNULL(CAST(`comment_count` AS CHAR), 'null'),
  ',"isAD":', IF(`isAD` = 1, 'true', 'false'),
  '}'
) AS nd
FROM `item`
WHERE `status` = 1;
