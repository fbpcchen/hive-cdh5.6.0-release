-- SORT_QUERY_RESULTS;

-- we're setting this so that TestNegaiveCliDriver.vm doesn't stop processing after DROP TABLE fails;

set hive.cli.errors.ignore=true;
set hive.exec.dynamic.partition.mode=nonstrict;

DROP TABLE IF EXISTS encrypted_table_dp PURGE;
CREATE TABLE encrypted_table_dp (key INT, value STRING) partitioned by (p STRING) LOCATION '${hiveconf:hive.metastore.warehouse.dir}/default/encrypted_table_dp';
CRYPTO CREATE_KEY --keyName key_128 --bitLength 128;
CRYPTO CREATE_ZONE --keyName key_128 --path ${hiveconf:hive.metastore.warehouse.dir}/default/encrypted_table_dp;

INSERT INTO encrypted_table_dp PARTITION(p) values(1, 'foo', '2014-09-23'),(2, 'bar', '2014-09-24');
SELECT * FROM encrypted_table_dp;
ALTER TABLE encrypted_table_dp DROP PARTITION (p='2014-09-23');
SELECT * FROM encrypted_table_dp;
ALTER TABLE encrypted_table_dp DROP PARTITION (p='2014-09-23') PURGE;
SELECT * FROM encrypted_table_dp;
ALTER TABLE encrypted_table_dp ADD PARTITION (p='2014-09-23');
SELECT * FROM encrypted_table_dp;
